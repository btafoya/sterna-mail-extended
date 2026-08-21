package app.sterna.core.data.account

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
import app.sterna.core.data.mail.recentUnifiedInboxFrom
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What "all inboxes" MEANS, kept apart from storage so it can be executed in a JVM test — and,
 * more importantly, so it exists exactly once.
 *
 * The number the drawer shows as "All inboxes (N)" is the one a home-screen widget will have to
 * show too, and two places deriving it by hand is two places where it can drift. Until now the
 * scopes were a caller's parameter: [AccountStore.allInboxScopes] recomputed them and four call
 * sites in `InboxViewModel` passed them along. A new caller could just as well have derived them
 * differently, or reached for [AccountStore.totalUnreadCount] — a server-side counter that no
 * longer matches anything on screen. So the derivation lives here, [AccountStore.allInboxScopes]
 * delegates to it, and a new caller needs to know about exactly two no-argument calls:
 * `MailRepository.observeUnifiedInboxUnreadByAccount()` for a line per account, and
 * `MailRepository.observeUnifiedInboxUnread()` for the single number, which is the FIRST totalled
 * ([total]) and not a second derivation.
 *
 * ⭐ READ-ONLY, deliberately: nothing here writes, so nothing here can walk past
 * [AccountBlobGate]'s write refusal — there is no path from this file to a stored blob at all.
 *
 * It does not read storage either. Its input is [AccountStore.accountsFlow], the list last
 * PUBLISHED in memory by `saveAccounts`, so a blob that becomes undecodable after start-up leaves
 * this count exactly where it was rather than dropping it to 0. Only a blob already undecodable
 * when the store was constructed yields an empty list, and therefore no scopes and a count of 0.
 */
internal object UnifiedInbox {

    /**
     * One inbox that counts: the scope it is counted under, and the stored counter it contributes
     * on its own. Everything the unified total depends on, per account, and nothing else — which
     * is what makes it usable as the key that decides when to count again.
     */
    data class CountedInbox(val scope: Pair<String, String>, val storedUnread: Int)

    /**
     * The inboxes of every account synced at least once — an account whose inbox is still unknown
     * (`inboxId == null`) contributes nothing rather than a made-up scope.
     *
     * ⛔ The scope is a PAIR, never a bare folder id (Codeberg #121). Two accounts on the same
     * server routinely get the same mailbox id, so a bare id matches the other account's rows as
     * well: the unified count would then read one account's inbox twice and the other's never.
     * Both halves are load bearing — see `MailRepository.folderScopeSql`.
     *
     * [CountedInbox.storedUnread] is here because it is the OTHER input to the total: an IMAP
     * inbox contributes `accountStore.account(id)?.unread`, a flat read no Room invalidation ever
     * wakes. See [unreadByAccount].
     */
    fun countedInboxes(accounts: List<StoredAccount>): List<CountedInbox> =
        accounts.mapNotNull { a -> a.inboxId?.let { CountedInbox(a.id to it, a.unread) } }

    /**
     * The (account id, inbox id) pairs the counter is asked for — [countedInboxes] projected, so
     * which accounts count is decided in ONE place and not in two that can drift.
     */
    fun scopes(accounts: List<StoredAccount>): List<Pair<String, String>> =
        countedInboxes(accounts).map { it.scope }

    /**
     * The unified unread total over time, BROKEN DOWN per account — keyed by account id, one entry
     * per counted inbox. Whenever [countedInboxes] CHANGES, the scopes are handed to [unreadFor]
     * afresh and the previous subscription is dropped ([flatMapLatest] — nothing keeps counting a
     * removed account's inbox; `MailRepository` hands back a `combine` of Room flows, which never
     * completes on its own).
     *
     * ⭐ The breakdown is the ONLY derivation, and the drawer's single "All inboxes (N)" is
     * [total] applied to it. A large widget cell draws a line per account, a small one and the
     * drawer draw the sum; deriving them separately would be two `combine`s of the same two global
     * Room aggregates, woken by the same invalidations, for the same information.
     *
     * ⛔ An account with no known inbox is ABSENT from the map, never an entry worth 0: the map is
     * built from the SCOPES ([countedInboxes] has already dropped it), and a 0 on a widget line
     * would affirm "no unread mail" about an inbox the app has never seen.
     *
     * [unreadFor] is a parameter and not a call into `MailRepository` for one reason: it makes
     * the ARGUMENTS observable. A test can pin what this hands the counter — the pairs, in order —
     * instead of merely checking that some counting happened.
     *
     * ⚠ The key is [countedInboxes], NOT the scopes. Both halves are load bearing:
     *
     *  - the STORED COUNTER has to be in it, or the IMAP half freezes. An IMAP inbox contributes
     *    `accountStore.account(id)?.unread`, read flat inside `MailRepository`'s `combine` block,
     *    and no Room invalidation ever wakes that read: the account list changing IS the only
     *    notification there is, and it changes without the scopes changing at all;
     *  - the DEDUPE has to be there, or this re-counts on writes that cannot have moved the number.
     *    [AccountStore.accountsFlow] republishes on every `saveAccounts` — some 27 call sites: an
     *    OAuth token refreshed, an account colour, identities discovered on connect, a sync window,
     *    a watched folder. Each of those otherwise cancels and rebuilds a `combine` of two global
     *    Room aggregates, in the collector's context — the drawer collects on `Main.immediate`.
     *    `InboxViewModel.folderRolesFlow` arbitrated the same question the same way, keyed on the
     *    account ids it needs.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun unreadByAccount(
        accounts: Flow<List<StoredAccount>>,
        unreadFor: (List<Pair<String, String>>) -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = accounts
        .map(::countedInboxes)
        .distinctUntilChanged()
        .map { counted -> counted.map { it.scope } }
        .flatMapLatest(unreadFor)

    /**
     * "All inboxes (N)": every counted inbox's share of [unreadByAccount], ADDED — the drawer's
     * header and the small widget cell, derived from the breakdown rather than counted again.
     *
     * Trivial on purpose, and named all the same: it is the one place that says the single number
     * is a SUM and not the largest account's, the first account's, or how many accounts there are.
     * No entry at all is 0 — nothing to count, not "no answer".
     */
    fun total(byAccount: Map<String, Int>): Int = byAccount.values.sum()

    /**
     * The unified inbox's latest read over time — [unreadByAccount]'s sibling, for the home
     * screen's "latest messages" cell, and written here for the same one reason: which inboxes the
     * widget reads is the drawer's decision, and a second derivation would be a second rule.
     *
     * [rowsFor] is a parameter and not a call into `MailRepository`, exactly as [unreadByAccount]'s
     * own `unreadFor` is: a flow handed in is already built, so a test could not tell "never
     * subscribed" from "subscribed and empty", and could not see WHICH pairs were asked for.
     *
     * ⛔ IT CARRIES [RecentInbox], NOT A BARE LIST OF ROWS, and `configured` is the reason. Two
     * different cells look alike from the rows alone: an inbox with no mail (drawn "No messages")
     * and no account to read at all (drawn with the app's own name, claiming nothing). A trigger
     * that only carried rows would hand the collector two identical empty lists for those two
     * states, its de-duplication would swallow the second, and the cell would go on affirming "No
     * messages" to someone who has just removed their last account. ⭐ The identity that travels
     * must be the identity that is DRAWN.
     *
     * ⭐ And `configured` is not decided here: [recentUnifiedInboxFrom] decides it, the same
     * function the snapshot read `MailRepository.recentUnifiedInbox` runs. Inverting that
     * condition must redden both, which is exactly what one shared function buys.
     *
     * ⚠ THE KEY IS [scopes], NOT [countedInboxes] — the one deliberate divergence from
     * [unreadByAccount], and it is worth stating because the two functions otherwise read alike.
     * The count needs [CountedInbox.storedUnread] in its key because its IMAP half is a flat read
     * of the stored record inside a `combine`, which no Room invalidation ever wakes. These rows
     * come ENTIRELY from Room: the stored counter cannot change one of them, and putting it in the
     * key would re-subscribe on every `saveAccounts` — some 27 call sites, an OAuth token
     * refreshed, an account colour, identities discovered on connect — cancelling and rebuilding
     * an observed query, and redrawing a home-screen cell, for rows that cannot have moved.
     *
     * ⛔ NO SCOPE IS ONE EMISSION, not [kotlinx.coroutines.flow.emptyFlow] and not a query.
     * One emission, because removing the last account must reach the home screen: silence would
     * leave the cell listing a deleted account's mail until something else redrew it. Not a query,
     * because `folderScopeSql(0, …)` is the constant `0` — a statement that matches nothing while
     * still subscribing Room to every write to `emails`, on behalf of a reader who has no account.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentRows(
        accounts: Flow<List<StoredAccount>>,
        rowsFor: (List<Pair<String, String>>) -> Flow<List<RecentEmailRow>>,
    ): Flow<RecentInbox> = accounts
        .map(::scopes)
        .distinctUntilChanged()
        .flatMapLatest { scopes ->
            if (scopes.isEmpty()) flowOf(recentUnifiedInboxFrom(scopes) { emptyList() })
            else rowsFor(scopes).map { rows -> recentUnifiedInboxFrom(scopes) { rows } }
        }
}

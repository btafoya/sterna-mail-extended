package app.sterna.core.data.account

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
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
 * delegates to it, and `MailRepository.observeUnifiedInboxUnread()` (no arguments) is the only
 * thing a new caller needs to know about.
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
     * wakes. See [unreadCount].
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
     * The unified unread total over time: whenever [countedInboxes] CHANGES, the scopes are handed
     * to [unreadFor] afresh and the previous subscription is dropped ([flatMapLatest] — nothing
     * keeps counting a removed account's inbox; `MailRepository` hands back a `combine` of Room
     * flows, which never completes on its own).
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
    fun unreadCount(
        accounts: Flow<List<StoredAccount>>,
        unreadFor: (List<Pair<String, String>>) -> Flow<Int>,
    ): Flow<Int> = accounts
        .map(::countedInboxes)
        .distinctUntilChanged()
        .map { counted -> counted.map { it.scope } }
        .flatMapLatest(unreadFor)
}

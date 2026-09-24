package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.folderHidingOf
import app.sterna.core.data.mail.AccountGoneException
import app.sterna.core.jmap.model.Mailbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/*
 * The unread view of every account (#235): reached from "All inboxes" by the drawer's "Unread"
 * entry, and taken for "All inboxes" by the rest of the app wherever that view is asked about.
 */

internal fun unreadOfEveryAccount(sel: Sel?): Boolean = sel is Sel.Unread && sel.allAccounts

/** "All inboxes" or the unread view of every account: one answer for the push side, the
 *  notification, the search and the drawer's count. */
internal fun showsEveryAccount(sel: Sel?): Boolean = sel is Sel.Unified || unreadOfEveryAccount(sel)

/**
 * The unread view the "Unread" entry opens, decided on the view it replaces. From an unread view,
 * that same one: a second tap never flips its reach. [unifiedViewExists] false (one account) never
 * answers every account, whatever the selection claims.
 */
internal fun unreadSelectionReplacing(replaced: Sel, unifiedViewExists: Boolean): Sel.Unread = when (replaced) {
    Sel.Unified -> Sel.Unread(allAccounts = unifiedViewExists)
    is Sel.Unread -> replaced
    is Sel.Folder -> Sel.Unread(allAccounts = false)
}

internal fun unreadScopesFor(
    sel: Sel,
    accountScopes: List<Pair<String, String>>,
    everyAccountScopes: List<Pair<String, String>>,
): List<Pair<String, String>> = if (unreadOfEveryAccount(sel)) everyAccountScopes else accountScopes

/** The accounts whose Sent folders the unread view's conversation chips count, as "All inboxes"
 *  does: the accounts of its own [scopes] when it spans every account. */
internal fun unreadViewAccounts(
    sel: Sel.Unread,
    scopes: List<Pair<String, String>>,
    currentAccountId: String?,
): List<String> = if (sel.allAccounts) scopes.map { it.first }.distinct() else listOfNotNull(currentAccountId)

/** The number on the drawer's "Unread" entry: what a tap on it opens, so every account's under
 *  "All inboxes" too. */
internal fun unreadEntryCount(sel: Sel, accountCount: Int, everyAccountCount: Int): Int =
    if (showsEveryAccount(sel)) everyAccountCount else accountCount

/** Where Back lands out of [sel] when it is not the inbox: "All inboxes" out of the unread view of
 *  every account, the view it was opened from. */
internal fun backLandsOnUnified(sel: Sel): Boolean = unreadOfEveryAccount(sel)

/** Whether the rows carry their account's chip, which then wins over [unreadRowFolder]'s folder
 *  name, a lookup in the current account's list only. */
internal fun rowsNameTheirAccount(ui: MailUi): Boolean = ui.unified || ui.unreadAllAccounts

/** The header of an unread view: every account's claims the unified view's internal label, which
 *  the screen swaps for the translated "All inboxes" (`UnifiedTitleWiringLintTest`). */
internal fun unreadViewMeta(
    sel: Sel.Unread,
    accountLabel: String,
    inboxName: String,
    inboxUnread: Int,
    totalUnread: Int,
): Meta = if (sel.allAccounts) Meta(UNIFIED_LABEL, UNIFIED_LABEL, totalUnread) else Meta(accountLabel, inboxName, inboxUnread)

/**
 * Every account's folder list, keyed by the id it was opened for, never an id read back later
 * (#121/#31). Empty while [wanted] is false: outside a view of every account nothing reads them,
 * and each list is a Room query of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun everyAccountFoldersFlow(
    accounts: Flow<List<StoredAccount>>,
    wanted: Flow<Boolean>,
    folders: (String) -> Flow<List<Mailbox>>,
): Flow<Map<String, List<Mailbox>>> =
    combine(accounts.map { list -> list.map { it.id } }, wanted) { ids, on -> if (on) ids else emptyList() }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(ids.map { id -> folders(id).map { list -> id to list } }) { lists -> lists.toMap() }
            }
        }

/**
 * Each account's own [unreadViewScopes], after that account's own subscription setting (#174) and
 * hide registry (#207). Never the current account's applied to all: one account's tick would hide
 * folders in the others. Pairs, never bare ids: two accounts of one server share folder ids.
 */
internal fun everyAccountUnreadScopes(
    folders: Map<String, List<Mailbox>>,
    accounts: List<StoredAccount>,
): List<Pair<String, String>> = accounts.flatMap { account ->
    val hiding = folderHidingOf(account.id, accounts)
    val listed = visibleFolders(folders[account.id].orEmpty(), showOnlySubscribedFor(account.id, accounts))
    unreadViewScopes(account.id, shownFolders(listed, hiding.ids, hiding.inboxId))
}

/** Each pair looked up in its own account's list. Not `totalUnreadCount`, which counts inboxes
 *  only. Over-counts in conversation view (debt). */
internal fun everyAccountUnreadCount(
    scopes: List<Pair<String, String>>,
    folders: Map<String, List<Mailbox>>,
): Int = scopes.sumOf { (accountId, folderId) ->
    folders[accountId]?.firstOrNull { it.id == folderId }?.unreadForList ?: 0
}

internal data class EveryAccountPass(val refreshed: Int, val failures: List<Throwable>)

/**
 * [refreshOne] for each of [accounts] in turn, the loop of `refreshAllInboxes`. An account signed
 * out mid-pass throws [AccountGoneException], a [CancellationException]: its arm stays above the
 * cancellation's, or the accounts after it are never refreshed and the indicator spins for ever.
 * It is skipped, not counted as a failure: no offline banner for a deliberate gesture.
 */
internal suspend fun <A> refreshEachAccount(accounts: List<A>, refreshOne: suspend (A) -> Unit): EveryAccountPass {
    var refreshed = 0
    val failures = mutableListOf<Throwable>()
    for (account in accounts) {
        try {
            refreshOne(account)
            refreshed++
        } catch (gone: AccountGoneException) {
            continue
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            failures += t
        }
    }
    return EveryAccountPass(refreshed, failures)
}

/** The first failure when no account came back, nothing otherwise: one server down must not raise
 *  the offline banner over the others' mail, the rule of "All inboxes". */
internal fun everyAccountRefreshFailure(pass: EveryAccountPass): Throwable? =
    pass.failures.firstOrNull()?.takeIf { pass.refreshed == 0 }

package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.mail.ListFilter
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.components.EmptyArt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the list is showing: a single folder, the unified inbox, or the unread mail of one account or all. */
internal sealed interface Sel {
    data class Folder(val id: String?) : Sel
    data object Unified : Sel

    /** Every folder of the current account, or of every account when [allAccounts], except Trash,
     * Junk/Spam, Sent and Drafts, filtered to unread. Its folders live in [PageKey.unreadScopes] and
     *  are not re-read when used, so the rows on screen and the rows a bulk action reaches are one set. */
    data class Unread(val allAccounts: Boolean) : Sel
}

/** Inputs that, together, determine the current paged source. */
internal data class PageKey(
    val sel: Sel,
    // (account id, folder id) pairs, never bare ids: a bare id also matches a same-server
    // sibling's colliding folder and a removed account's leftovers (#121).
    val unifiedScopes: List<Pair<String, String>>,
    val unreadScopes: List<Pair<String, String>>,
    val sort: SortOrder,
    val toggles: ListFilter,
    val conversationView: Boolean,
    // The active account, so a switch re-subscribes the pager even when the new inbox shares the
    // old one's mailbox id (JMAP numbers mailboxes per account, so they often collide).
    val accountId: String? = null,
)

/** The key [InboxViewModel.pagedEmails] pages from. Every emission rebuilds the pager, so the list
 *  jumps to the top, hence [distinctUntilChanged], here and not in the caller, DataStore
 * republishing all `Preferences` on every write. Needs equality on every [PageKey] member. */
internal fun pageKeyFlow(
    selection: Flow<Sel>,
    unifiedInboxScopes: Flow<List<Pair<String, String>>>,
    unreadViewScopes: Flow<List<Pair<String, String>>>,
    sortOrder: Flow<SortOrder>,
    filterToggles: Flow<ListFilter>,
    conversationView: Flow<Boolean>,
    currentAccountId: Flow<String?>,
): Flow<PageKey> =
    combine(selection, unifiedInboxScopes, sortOrder, filterToggles, conversationView) {
            sel, scopes, sort, toggles, conversation ->
        PageKey(sel, scopes, emptyList(), sort, toggles, conversation)
    }.combine(unreadViewScopes) { key, scopes -> key.copy(unreadScopes = scopes) }
        .combine(currentAccountId) { key, accountId -> key.copy(accountId = accountId) }
        .distinctUntilChanged()

/** The folders the "unread" view pages: every folder of [accountId] except Trash, Junk/Spam, Sent
 * and Drafts. Pairs, never bare folder ids (#121/#31); the role is judged, never the name. A
 *  losing IMAP trash stays in, so a shared `Shared/…/Trash` is paged, deleted mail and all (debt). */
internal fun unreadViewScopes(accountId: String?, folders: List<Mailbox>): List<Pair<String, String>> {
    if (accountId == null) return emptyList()
    return folders.filterNot { it.role?.trim()?.lowercase() in UNREAD_VIEW_EXCLUDED_ROLES }
        .map { accountId to it.id }
}

/** The folders a pull-to-refresh in the "unread" view must sync on top of the inbox: [scopes] of
 *  [accountId], minus [inboxId] (which `includeInbox` covers) and minus other accounts' (#121/#31).
 *  Syncing the inbox alone answers "no unread mail" for folders the cache was never asked about. */
internal fun unreadRefreshTargets(
    accountId: String?,
    scopes: List<Pair<String, String>>,
    inboxId: String?,
): Set<String> {
    if (accountId == null) return emptySet()
    return scopes.filter { it.first == accountId }
        .map { it.second }
        .filterNot { it == inboxId }
        .toSet()
}

/** The mailbox the unarchive-on-reply hook (#50) may fire for after a multi-folder refresh: the
 * account's inbox, and only if [refreshedIds] says it came back. Never "the first refresh":
 *  `onInboxRefreshed` re-files threads and would move mail nobody asked to move. */
internal fun refreshedInboxId(refreshedIds: List<String>, inboxId: String?): String? =
    inboxId?.takeIf { it in refreshedIds }

internal fun realignedInboxId(requested: String?, storedInboxId: String?, roleInboxId: String?): String? =
    roleInboxId.takeIf { requested != null && requested == storedInboxId && it != storedInboxId }

/** How the list is filtered, one answer for the pager and for "Select all"; the unread view forces
 *  the unread criterion on. The pager forcing a criterion while `selectAll` reads the raw toggles is
 *  the destructive half of #126: "Select all" then "Delete" trashes what the list was hiding. */
internal fun listFilter(sel: Sel, toggles: ListFilter): ListFilter = listFilter(sel is Sel.Unread, toggles)

/** The same decision asked by the screen, which holds a flag and not a [Sel]. An overload that
 *  delegates, never a second spelling of the rule: the empty state and the pager must agree. */
internal fun listFilter(unreadView: Boolean, toggles: ListFilter): ListFilter =
    if (unreadView) toggles.copy(unreadOnly = true) else toggles

internal enum class ListCriterion { UNREAD, FLAGGED, ATTACHMENT }

internal fun ListFilter.toggled(criterion: ListCriterion): ListFilter = when (criterion) {
    ListCriterion.UNREAD -> copy(unreadOnly = !unreadOnly)
    ListCriterion.FLAGGED -> copy(flaggedOnly = !flaggedOnly)
    ListCriterion.ATTACHMENT -> copy(withAttachment = !withAttachment)
}

internal fun ListFilter.has(criterion: ListCriterion): Boolean = when (criterion) {
    ListCriterion.UNREAD -> unreadOnly
    ListCriterion.FLAGGED -> flaggedOnly
    ListCriterion.ATTACHMENT -> withAttachment
}

internal fun ListFilter.narrowsBeyondUnread(): Boolean = flaggedOnly || withAttachment

internal fun ListFilter.afterShowAll(): ListFilter = ListFilter.NONE

private fun offeredCriteria(unreadView: Boolean): List<ListCriterion> =
    ListCriterion.entries.filterNot { unreadView && it == ListCriterion.UNREAD }

internal data class QuickFilter(val criterion: ListCriterion, val checked: Boolean)

/** [active] reads offered filters only: in the unread view, `unreadOnly` is the view itself. */
internal data class FilterMenu(val filters: List<QuickFilter>, val active: Boolean)

internal fun filterMenu(unreadView: Boolean, toggles: ListFilter): FilterMenu {
    val filters = offeredCriteria(unreadView).map { QuickFilter(it, toggles.has(it)) }
    return FilterMenu(filters, active = filters.any { it.checked })
}

internal fun filterLabel(criterion: ListCriterion): Int = when (criterion) {
    ListCriterion.UNREAD -> R.string.filter_unread
    ListCriterion.FLAGGED -> R.string.filter_starred
    ListCriterion.ATTACHMENT -> R.string.filter_attachment
}

/** The number the drawer's "unread" entry carries: [Mailbox.unreadForList] summed over the scope
 * that view pages. Never a second exclusion rule: no role is named here, the scope is the
 * argument. Over-counts in conversation view (debt). */
internal fun unreadViewCount(
    accountId: String?,
    scopes: List<Pair<String, String>>,
    folders: List<Mailbox>,
): Int {
    if (accountId == null) return 0
    val inScope = scopes.filter { it.first == accountId }.map { it.second }.toSet()
    return folders.filter { it.id in inScope }.sumOf { it.unreadForList }
}

/** What an empty list draws. String ids rather than text: the words are resolved in the screen's
 *  configuration, so a hot locale switch redraws them. */
internal data class EmptyScene(
    val art: EmptyArt,
    val title: Int,
    val body: Int,
    val clearsFilter: Boolean,
    val activeFilters: List<ListCriterion> = emptyList(),
)

/** Which empty scene an empty list shows. The unread view selects no mailbox, so the null-mailbox
 *  inbox scene would lie there; it gets folder art, and a button only while a starred or attachment
 *  toggle is on, which the view does not force. Those two take the words: "read everything" lies. */
internal fun emptyListScene(
    unreadView: Boolean,
    toggles: ListFilter,
    unified: Boolean,
    selectedMailboxId: String?,
    folderRole: String?,
): EmptyScene {
    val art = when {
        unreadView -> EmptyArt.FOLDER
        unified || selectedMailboxId == null || folderRole == "inbox" -> EmptyArt.INBOX_ZERO
        folderRole == "trash" -> EmptyArt.TRASH
        else -> EmptyArt.FOLDER
    }
    val filtered = listFilter(unreadView, toggles).unreadOnly
    val narrowed = toggles.narrowsBeyondUnread()
    return EmptyScene(
        art = art,
        title = when {
            narrowed -> R.string.empty_filtered_title
            filtered -> R.string.empty_unread_title
            art == EmptyArt.TRASH -> R.string.empty_trash_title
            art == EmptyArt.FOLDER -> R.string.empty_folder_title
            else -> R.string.empty_inbox_title
        },
        body = when {
            narrowed -> R.string.empty_filtered_body
            // Above the unread toggle's arm: that body points at a chip this view does not draw.
            unreadView -> R.string.empty_unread_view_body
            filtered -> R.string.empty_unread_body
            art == EmptyArt.TRASH -> R.string.empty_trash_body
            art == EmptyArt.FOLDER -> R.string.empty_folder_body
            else -> R.string.empty_inbox_body
        },
        clearsFilter = narrowed || (toggles.unreadOnly && !unreadView),
        activeFilters = if (narrowed) offeredCriteria(unreadView).filter { toggles.has(it) } else emptyList(),
    )
}

/** The roles the "unread" view never pages: Trash and Junk/Spam because what is thrown away is
 *  not waiting to be read, Sent and Drafts because their unread state means nothing (#82). */
private val UNREAD_VIEW_EXCLUDED_ROLES = setOf("trash", "junk", "spam", "sent", "drafts")

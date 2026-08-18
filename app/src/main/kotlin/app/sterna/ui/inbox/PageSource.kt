package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.settings.SortOrder
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.components.EmptyArt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** What the list is showing: a single folder, the unified inbox, or one account's unread mail. */
internal sealed interface Sel {
    data class Folder(val id: String?) : Sel
    data object Unified : Sel

    /**
     * Every folder of the CURRENT account except Trash, Junk/Spam, Sent and Drafts, filtered to
     * unread. Its folders live in [PageKey.unreadScopes], exactly as the unified inbox's live in
     * [PageKey.unifiedScopes] — the scope is part of the key, not re-read at the moment it is used,
     * so the rows on screen and the rows a bulk action reaches are the same set.
     */
    data object Unread : Sel
}

/** Inputs that, together, determine the current paged source. */
internal data class PageKey(
    val sel: Sel,
    // The unified inbox's folders as (account id, inbox id) PAIRS. Bare ids listed the rows
    // of accounts that are not in this list — a same-server sibling's colliding folder id,
    // and above all a REMOVED account's leftovers, which no account could then label, sync or
    // act on (Codeberg #121). MailRepository.folderScopeSql says the rest.
    val unifiedScopes: List<Pair<String, String>>,
    // The "unread" view's folders, same shape and same reason: (account id, folder id) PAIRS,
    // never bare ids. Computed by [unreadViewScopes] from the current account's folder list.
    val unreadScopes: List<Pair<String, String>>,
    val sort: SortOrder,
    val unreadOnly: Boolean,
    val conversationView: Boolean,
    // The active account, so switching accounts re-subscribes the pager even when the
    // new inbox shares the old one's mailbox id (JMAP servers number mailboxes per
    // account, so two accounts' inboxes often collide on the same id, e.g. "a").
    val accountId: String? = null,
)

/**
 * The key [InboxViewModel.pagedEmails] pages from, as a flow of its own so a test can run it.
 *
 * Every emission of this flow REBUILDS the pager: the caller's `flatMapLatest` cancels the running
 * `Pager` and constructs a new one, which starts from `initialKey = null` — the first page of the
 * cache. So an emission is not free: it is the list jumping back to the top, and the reader's
 * swipe-to-next-message losing its place with it (both page the same flow).
 *
 * That is why the chain ends on [distinctUntilChanged], and why the guard has to live HERE rather
 * than in the caller: DataStore republishes the WHOLE `Preferences` on every write, whatever key was
 * touched, so a setting with nothing to do with the list ("always show images from this sender",
 * `SettingsRepository.setImageAllowed`) re-emitted an EQUAL key and rebuilt the pager. `cachedIn`
 * cannot help: it caches the flow, not the decision to build a new one.
 *
 * ⚠ The dedupe is only correct because all seven members of [PageKey] have value equality — `Sel` is
 * a sealed interface of a data class and two data objects, `unifiedScopes` and `unreadScopes` lists
 * of pairs, the rest primitives and an enum. A member added later WITHOUT it (a lambda, a repository
 * handle, any identity-compared object) would make every key distinct and quietly bring the rebuild
 * storm back; a member added with equality but not read here would stop the list rebuilding when it
 * must. `PageKeyFlowTest` pins both directions, member by member.
 *
 * ⚠ The typed `combine` stops at five flows, so the last two inputs are folded in with `copy` —
 * which is why they are seeded with a placeholder in the first stage. A stage dropped from the chain
 * leaves its member frozen at that placeholder, and the member's own `PageKeyFlowTest` rule is what
 * says so.
 */
internal fun pageKeyFlow(
    selection: Flow<Sel>,
    unifiedInboxScopes: Flow<List<Pair<String, String>>>,
    unreadViewScopes: Flow<List<Pair<String, String>>>,
    sortOrder: Flow<SortOrder>,
    unreadOnly: Flow<Boolean>,
    conversationView: Flow<Boolean>,
    currentAccountId: Flow<String?>,
): Flow<PageKey> =
    combine(selection, unifiedInboxScopes, sortOrder, unreadOnly, conversationView) {
            sel, scopes, sort, unread, conversation ->
        PageKey(sel, scopes, emptyList(), sort, unread, conversation)
    }.combine(unreadViewScopes) { key, scopes -> key.copy(unreadScopes = scopes) }
        .combine(currentAccountId) { key, accountId -> key.copy(accountId = accountId) }
        .distinctUntilChanged()

/**
 * The folders the "unread" view pages: every folder of [accountId] EXCEPT the ones whose role says
 * the mail in them is not waiting to be read — Trash, Junk/Spam, Sent and Drafts.
 *
 * Pairs, never bare folder ids (#121/#31): servers number mailboxes per account, so a bare id
 * matches a sibling account's homonymous folder and a removed account's leftovers, which no account
 * could then label, sync or act on. See `MailRepository.folderScopeSql`.
 *
 * Two folders deliberately stay IN:
 *
 *  - a folder with NO role — a user folder like `Projets`. Unread mail filed there is unread mail;
 *    the rule excludes only what it can name;
 *  - a folder with a MARKED role (`~trash`, `MailRepository.UNELECTED_ROLE_MARK`), which means "this
 *    folder claimed trash and another folder was elected to it".
 *
 * ⚠ Say the second one plainly, because the excluded set is NOT what decides it. `MailboxMapper`
 * erases the mark on the way to [Mailbox] (`MailboxMapper.kt:38`, `unmarkedRole`), so a losing IMAP
 * trash arrives here with `role = null` and is included as a role-less folder — the marked spelling
 * in this filter never fires at all. The consequence is real and is not a belt: an account with a
 * personal `Trash` and a shared `Shared/…/Trash` has the shared one PAGED by this view, deleted mail
 * and all. That is a known debt carried in the batch journal, not something this function fixes.
 *
 * The role is trimmed and lower-cased, never the name: the JMAP side hands roles through as the
 * server spells them, so a server answering `"Sent"` or `" trash "` must not slip past. Same shape
 * as `MailRepository.NOT_SEARCHED_ROLES`, which cannot be reused — it is `internal` to `:core:data`
 * and it names three roles, not five.
 *
 * A null [accountId] (no account configured, or none current yet) yields nothing: an empty scope
 * list is what `MailRepository.pagedMailbox` answers `PagingData.empty()` to, and it is the only
 * honest answer — there is no account to pin the pairs to.
 */
internal fun unreadViewScopes(accountId: String?, folders: List<Mailbox>): List<Pair<String, String>> {
    if (accountId == null) return emptyList()
    return folders.filterNot { it.role?.trim()?.lowercase() in UNREAD_VIEW_EXCLUDED_ROLES }
        .map { accountId to it.id }
}

/**
 * The folders a pull-to-refresh in the "unread" view must SYNC, on top of the inbox: every folder of
 * [scopes] belonging to [accountId], minus [inboxId].
 *
 * The view pages the `emails` cache, and that cache holds the inbox, the folders the user has
 * actually VISITED, and the IMAP watched ones — nothing else. A user folder never opened is not in
 * it, so a view that syncs the inbox alone would be true by construction and false in fact: it would
 * answer "no unread mail" for folders it never asked the server about. This is the set that closes
 * that gap, handed to `MailRepository.refreshAccountFolders(extraFolderIds = …)`.
 *
 * Two removals, and both are the point:
 *
 *  - **another account's folders.** [scopes] is a list of (account, folder) PAIRS, and only the
 *    current account's credentials are in hand. A bare folder id from a sibling account sent to THIS
 *    account's server names either nothing or, on a shared server, a homonymous folder of somebody
 *    else's mailbox — the same collision `folderScopeSql` exists for (#121/#31). The account is
 *    filtered FIRST, so the inbox removal below can only ever concern this account's own inbox;
 *  - **the inbox.** It is already synced by `includeInbox = true`. Passed again it is synced twice,
 *    which on IMAP is a second SELECT plus a second FETCH of `limit` messages, per pull.
 *
 * A null [accountId] (no account configured) or an empty scope list yields nothing: there is then no
 * account whose folders these could be, and `refreshAccountFolders` would still sync the inbox. A
 * null [inboxId] (an account never synced, so nothing cached to compare against) removes nothing —
 * the id it would remove is not known yet, and syncing a folder twice is a cost, not a defect.
 *
 * A [Set], because it is what `refreshAccountFolders` takes and because a folder listed twice in the
 * scope must not be fetched twice.
 */
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

/**
 * The mailbox the unarchive-on-reply hook (#50) may be fired for after a multi-folder refresh: the
 * account's inbox [inboxId], and only if [refreshedIds] says it was really among the folders that
 * came back.
 *
 * ⛔ Never "the first refresh". The inbox being first is an invariant of another module, five hops
 * away, and `FetchAndNotify.onInboxRefreshed` is not a read: it WRITES the folder's notification
 * baseline and runs `unarchiveThreadsOnReply` over the threads it finds new. Aimed at a folder that
 * is not the inbox, it either swallows that folder's notifications (its baseline advances without
 * anything being announced) or re-files threads nobody asked to see moved. `refreshFolder` has
 * always asked this question — `updated.mailboxId == store.inboxMailboxId()` — and this is the same
 * question asked of a list.
 *
 * A null [inboxId] — an account whose inbox has never been synced, so nothing cached to compare
 * against — yields null: the hook is best-effort, and the safe answer when we cannot say WHICH
 * folder is the inbox is to fire it for none.
 */
internal fun refreshedInboxId(refreshedIds: List<String>, inboxId: String?): String? =
    inboxId?.takeIf { it in refreshedIds }

/**
 * ⭐ Whether THE LIST is filtered to unread — one answer, for the pager AND for "Select all".
 *
 * The toolbar's funnel ([toggle]) says what the reader asked for. [Sel.Unread] says what the view
 * IS: a scope called "Unread" must show unread mail whatever the funnel is set to (WYSIWYG), so it
 * forces the filter on and the funnel can only ever agree with it.
 *
 * ⛔ ONE function, called from both places, and that is the whole point of extracting it. The pager
 * forcing the filter while `selectAll` read the raw toggle is the destructive half of #126 rebuilt
 * from scratch: the list shows twelve unread rows, `MailRepository.selectableIds` is asked for the
 * folder UNFILTERED, and "Select all" then "Delete" sends to the Trash every READ message of the
 * Inbox, the Archive and every user folder of the account — mail nobody was ever shown. Two
 * predicates that merely look alike are exactly the shape that defect takes, which is why this is a
 * function and not a rule written twice.
 */
internal fun listUnreadOnly(sel: Sel, toggle: Boolean): Boolean = listUnreadOnly(sel == Sel.Unread, toggle)

/**
 * The same one decision, asked by the screen, which holds a flag and not a [Sel] ([MailUi] is public
 * and [Sel] is internal to this package, so the state cannot carry the selection itself).
 *
 * ⛔ An overload that DELEGATES, never a second spelling of `|| toggle`: the empty state and the
 * pager have to agree on what "filtered" means, or the list says "no unread messages" over rows it
 * is showing.
 */
internal fun listUnreadOnly(unreadView: Boolean, toggle: Boolean): Boolean = unreadView || toggle

/**
 * The number the drawer's "unread" entry carries: the unread of the folders that view PAGES, summed
 * over the scope it is built from ([unreadViewScopes], through [PageKey.unreadScopes]).
 *
 * ⛔ Never a second exclusion rule written next to the first: the badge must offer exactly the
 * folders the list pages, and two predicates that merely look alike drift in silence — the day a
 * role is added to the excluded set, a count of its own would keep advertising mail the list no
 * longer shows. So the roles are not named here at all; the SCOPE is the argument.
 *
 * ⭐ What the number IS, exactly: the sum of [Mailbox.unreadForList] over the scope — the very
 * aggregate the folder rows under this entry are badged with, so the drawer cannot contradict
 * itself. That is a definition, not a row count, and it does not promise one:
 *
 *  - `unreadForList` is a LIVE local aggregate for JMAP accounts (unread threads in conversation
 *    view, unread messages in flat view) and, for IMAP accounts, the server's stored counter that
 *    `MailboxMapper` copies into it — a windowed cache cannot honestly aggregate what it has not
 *    got. Whichever it is, it is what the folder rows show, which is the property that matters here;
 *  - ⛔ in conversation view it can OVER-count against the list: the badge sums per-folder counts,
 *    while the list groups a thread over the whole scope, so a thread with unread messages in two
 *    folders of the scope is counted twice and drawn once. Counting it right needs a repository
 *    query over the scope, which this volet does not add — carried as debt in the batch journal.
 *
 * [scopes] is filtered on [accountId] before anything is summed, and that is the #121/#31 rule
 * again: the pairs may still be the PREVIOUS account's for the tick between an account switch and
 * the new folder list arriving, and a bare id matches a sibling account's homonymous folder. A scope
 * naming a folder that is not in [folders] (deleted server-side, or not synced yet) adds nothing —
 * there is no count to add, and zero is the honest answer.
 */
internal fun unreadViewCount(
    accountId: String?,
    scopes: List<Pair<String, String>>,
    folders: List<Mailbox>,
): Int {
    if (accountId == null) return 0
    val inScope = scopes.filter { it.first == accountId }.map { it.second }.toSet()
    return folders.filter { it.id in inScope }.sumOf { it.unreadForList }
}

/**
 * What an empty list draws: the picture, the two string resources, and whether the way out of the
 * filter is offered. String ids rather than text — the words are resolved by the screen, in the
 * screen's configuration, so a hot locale switch redraws them.
 */
internal data class EmptyScene(
    val art: EmptyArt,
    val title: Int,
    val body: Int,
    val clearsFilter: Boolean,
)

/**
 * Which empty scene an empty list shows. Extracted from the screen so the four combinations of
 * (the "unread" view, the toolbar funnel) can be RUN — `InboxScreen` is not instantiable on the JVM,
 * and this is the decision, not the drawing.
 *
 * Two things the unread view had to change, and both were wrong by inheritance rather than by
 * accident: it selects no mailbox, so `selectedMailboxId == null` used to hand it the unified
 * inbox's hero drawing and the words "your inbox is empty" over a view that spans every folder of
 * the account.
 *
 *  - the ART is the neutral folder one. INBOX_ZERO draws — and promises — an empty INBOX;
 *  - the WORDS are the unread ones, because [listUnreadOnly] is what says whether this list is
 *    filtered, and it answers yes for the whole scope. One function, the same one the pager and
 *    "Select all" ask.
 *
 * ⛔ The action button is the asymmetry, and it is deliberate: it offers to lift the funnel, and in
 * the unread view there is no funnel to lift — the scope forces the filter on, so the same empty
 * list would stay on screen. It is offered for the TOGGLE alone, whatever the scope has been told.
 */
internal fun emptyListScene(
    unreadView: Boolean,
    unreadToggle: Boolean,
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
    val filtered = listUnreadOnly(unreadView, unreadToggle)
    return EmptyScene(
        art = art,
        title = when {
            filtered -> R.string.empty_unread_title
            art == EmptyArt.TRASH -> R.string.empty_trash_title
            art == EmptyArt.FOLDER -> R.string.empty_folder_title
            else -> R.string.empty_inbox_title
        },
        body = when {
            // ⛔ Before the funnel's arm, and it is not a nuance: empty_unread_body ends on "turn
            // off the unread filter to see all mail", and in this view there is no funnel on the
            // toolbar and no button under the text to turn off. Its own sentence says "your
            // folders" and never "all mail" — Trash and Junk are out of the scope on purpose.
            unreadView -> R.string.empty_unread_view_body
            filtered -> R.string.empty_unread_body
            art == EmptyArt.TRASH -> R.string.empty_trash_body
            art == EmptyArt.FOLDER -> R.string.empty_folder_body
            else -> R.string.empty_inbox_body
        },
        clearsFilter = unreadToggle && !unreadView,
    )
}

/** The roles the "unread" view never pages. Trash and Junk/Spam because what is thrown away or
 *  refused is not waiting to be read; Sent and Drafts because their unread state means nothing —
 *  the same reason `isOutgoingFolder` hides "mark all read" there (#82). */
private val UNREAD_VIEW_EXCLUDED_ROLES = setOf("trash", "junk", "spam", "sent", "drafts")

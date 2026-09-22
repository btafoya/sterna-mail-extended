package app.sterna.ui.settings

import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.folderMayBeHidden
import app.sterna.ui.inbox.folderParentId
import app.sterna.ui.inbox.folderRank
import app.sterna.ui.inbox.hiddenBySubtree
import app.sterna.ui.inbox.mailboxTree
import app.sterna.ui.inbox.shownFolders
import app.sterna.ui.inbox.siblingsInDrawerOrder
import app.sterna.ui.inbox.visibleFolders

/**
 * What the folder-management screen draws (#207), decided here so a JVM test can run it: nothing
 * in this file composes.
 */

/** Folder roles the "notify about new mail" switch is not offered on: the inbox is always watched
 * (#16), and notifying about one's own sent/drafts/trash/junk would be noise. The inbox is left to
 *  [folderMayBeHidden], which also knows the account's own id for it, where the role alone does not. */
private val WATCH_SILENT_ROLES = setOf("sent", "drafts", "trash", "junk")

/** One row of the screen: the folder, its depth in the tree, and the state of its two switches. */
internal data class FolderManagementRow(
    val mailbox: Mailbox,
    val depth: Int,
    /** What the "show this folder" switch reads. The Inbox reads checked whatever the registry
     *  holds, since nothing can hide it. */
    val shown: Boolean,
    val showToggleEnabled: Boolean,
    val mayWatch: Boolean,
    val watched: Boolean,
    /** The move buttons the reorder mode gives this row, none on a folder with no place to trade. */
    val moves: FolderMoves? = null,
)

/** The Up and Down buttons of one row: the fratrie a tap reorders, and which of the two it takes. */
internal data class FolderMoves(val siblings: List<String>, val mayMoveUp: Boolean, val mayMoveDown: Boolean)

/**
 * The rows for one account, in the drawer's own order ([mailboxTree], nothing folded: a folder must
 * stay reachable to be unhidden), built from the folders the account's screens can show.
 *
 * With [reordering], only what the drawer draws ([shownFolders]), and the fratries with it: the mode
 * shows no switch, so a hidden sibling would be a place Up trades with while the drawer stays still.
 */
internal fun folderManagementRows(
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
    hidden: Set<String>,
    watched: Set<String>,
    inboxId: String?,
    order: List<String> = emptyList(),
    reordering: Boolean = false,
): List<FolderManagementRow> {
    val listed = visibleFolders(mailboxes, showOnlySubscribed)
    val byId = listed.associateBy { it.id }
    val drawn = if (reordering) shownFolders(listed, hidden, inboxId) else listed
    val drawnById = drawn.associateBy { it.id }
    val fratries = reorderableFratries(drawn, order)
    return mailboxTree(drawn, collapsed = emptySet(), order = order).map { node ->
        val mailbox = node.mailbox
        val mayHide = folderMayBeHidden(mailbox, inboxId)
        // Asked of the ANCESTORS, never of the folder itself: hiding takes the subtree with it, so
        // a row under a hidden parent would write over the choice already made under it.
        val parent = folderParentId(mailbox, byId)?.let { byId[it] }
        val underHiddenParent = parent != null && hiddenBySubtree(parent, byId, hidden)
        FolderManagementRow(
            mailbox = mailbox,
            depth = node.depth,
            shown = !mayHide || mailbox.id !in hidden,
            showToggleEnabled = mayHide && !underHiddenParent,
            mayWatch = mayHide && mailbox.role !in WATCH_SILENT_ROLES,
            watched = mailbox.id in watched,
            moves = folderMovesOf(mailbox, fratrieOf(mailbox, drawnById, fratries)),
        )
    }
}

/** Whether [mailbox] has a place in the user's folder order. A folder [folderRank] pins ahead of the
 *  rest has none: `siblingsInDrawerOrder` draws it by rank whatever the order holds, so a button on
 *  it would write a move nothing shows. */
internal fun folderMayBeReordered(mailbox: Mailbox): Boolean = folderRank(mailbox.role) >= folderRank(null)

/** Each parent's reorderable children, keyed by parent id (null at the top), in the order the
 *  screen draws them. Parents are resolved by [folderParentId] over [listed], as [mailboxTree] does,
 *  so a fratrie here is a group of rows the user sees together. */
internal fun reorderableFratries(listed: List<Mailbox>, order: List<String>): Map<String?, List<String>> {
    val byId = listed.associateBy { it.id }
    return listed.groupBy { folderParentId(it, byId) }
        .mapValues { (_, kids) -> siblingsInDrawerOrder(kids, order).filter(::folderMayBeReordered).map { it.id } }
}

/** The fratrie [mailbox] moves within: its own parent's, never the top level for a nested folder. */
internal fun fratrieOf(mailbox: Mailbox, byId: Map<String, Mailbox>, fratries: Map<String?, List<String>>): List<String> =
    fratries[folderParentId(mailbox, byId)].orEmpty()

/** The buttons [mailbox]'s row carries in [fratrie], or none when it has no place there or no
 *  sibling to trade places with. */
internal fun folderMovesOf(mailbox: Mailbox, fratrie: List<String>): FolderMoves? {
    val at = fratrie.indexOf(mailbox.id)
    if (at < 0 || fratrie.size < 2) return null
    return FolderMoves(fratrie, mayMoveUp = at > 0, mayMoveDown = at < fratrie.lastIndex)
}

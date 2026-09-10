package app.sterna.ui.settings

import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.folderMayBeHidden
import app.sterna.ui.inbox.folderParentId
import app.sterna.ui.inbox.hiddenBySubtree
import app.sterna.ui.inbox.mailboxTree
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
)

/**
 * The rows for one account, in the drawer's own order ([mailboxTree], nothing folded: a folder must
 * stay reachable to be unhidden), built from the folders the account's screens can show.
 */
internal fun folderManagementRows(
    mailboxes: List<Mailbox>,
    showOnlySubscribed: Boolean,
    hidden: Set<String>,
    watched: Set<String>,
    inboxId: String?,
): List<FolderManagementRow> {
    val listed = visibleFolders(mailboxes, showOnlySubscribed)
    val byId = listed.associateBy { it.id }
    return mailboxTree(listed, collapsed = emptySet()).map { node ->
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
        )
    }
}

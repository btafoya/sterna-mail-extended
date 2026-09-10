package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox

/**
 * The folders the screens still show once the account's own hide registry is applied (#207), the
 * second filter, run on what [visibleFolders] answered and never folded into it.
 */

/** Whether [mailbox] may be hidden at all. Two witnesses, never the role alone: a server listing
 * no folder with the inbox role at all leaves the account's Inbox id on the first folder listed,
 *  which carries none, and hiding it would leave the account with no default folder list. */
internal fun folderMayBeHidden(mailbox: Mailbox, inboxId: String?): Boolean =
    mailbox.role != "inbox" && mailbox.id != inboxId

/** Whether [mailbox] or one of its ancestors is in [hidden], walked up through [byId]. `seen`
 * guards a `parentId` cycle, which a server can answer and which would spin here forever. A child
 *  whose parent is absent from the list is not caught: it is drawn at the root, so it is shown. */
internal fun hiddenBySubtree(mailbox: Mailbox, byId: Map<String, Mailbox>, hidden: Set<String>): Boolean {
    val seen = mutableSetOf<String>()
    var current: Mailbox? = mailbox
    while (current != null && seen.add(current.id)) {
        if (current.id in hidden) return true
        current = folderParentId(current, byId)?.let { byId[it] }
    }
    return false
}

/** [mailboxes] minus what [hidden] hides, in the order they came in. Hiding takes the subtree with
 * it, and that is a correction, not a preference: [visibleFolders] walks the ancestors of every
 *  survivor back in, so hiding the named folder alone is inert on any parent with a visible child. */
internal fun shownFolders(mailboxes: List<Mailbox>, hidden: Set<String>, inboxId: String?): List<Mailbox> {
    if (hidden.isEmpty()) return mailboxes
    val byId = mailboxes.associateBy { it.id }
    return mailboxes.filter { !folderMayBeHidden(it, inboxId) || !hiddenBySubtree(it, byId, hidden) }
}

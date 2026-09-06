package app.sterna.ui

/**
 * Which folder the list behind should show when a new-mail notification is opened — the folder half
 * of [NotificationAccountSwitch]'s rule, not a second mechanism beside it.
 *
 * Same defect one level down: the notification carried the message and its account but nothing about
 * the folder, so opening a message living outside the Inbox — a snoozed mail woken in Archive (#91)
 * — dropped the user, on Back, into a list that does not hold it.
 *
 * The invariant both halves enforce: Back lands in a list that contains the message just read. Pure
 * and storage-free, so the caller supplies the folder list and the current selection.
 */
object NotificationFolderSwitch {

    /**
     * The folder to show, or null to leave the list where it is. Null in four cases: the
     * notification carries no folder; it is the folder already on screen, which is the ordinary
     * inbox arrival; the folder is not in [knownMailboxIds], since a vanished folder already falls
     * back to the Inbox and switching INTO one would undo that (an EMPTY list means "not known yet",
     * which the caller must not treat as a verdict); or the list behind is the unified inbox, a mode
     * the user explicitly chose, which leaving would drop them into one account's folder.
     */
    /*
     * A FIFTH case, judged by the caller and not here: a folder the account's "show subscribed
     * folders only" setting hides (#174). The answer below is a folder id, and whether one may be
     * parked in it is one rule shared with that setting's own fallback. Do not select this answer
     * without passing it through it.
     */
    fun resolve(
        notificationMailboxId: String?,
        selectedMailboxId: String?,
        unifiedView: Boolean,
        knownMailboxIds: Collection<String>,
    ): String? {
        val target = notificationMailboxId?.takeIf { it.isNotBlank() } ?: return null
        if (unifiedView) return null
        if (target == selectedMailboxId) return null
        if (target !in knownMailboxIds) return null
        return target
    }
}

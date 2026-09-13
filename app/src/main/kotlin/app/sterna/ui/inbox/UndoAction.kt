package app.sterna.ui.inbox

/** One message that can be moved back to its original mailbox by an Undo. [mailboxId] is the
 *  source folder to restore to; [destMailboxId] is where the forward action put it (Trash /
 *  Archive / target), so the undo can reverse the drawer-count nudge (null = it was destroyed).
 *  [destMailboxId] is a `var` the swipe fills in on the ack, its bar being offered at the finger. */
data class UndoEntry(
    val emailId: String,
    val accountId: String?,
    val mailboxId: String,
    var destMailboxId: String? = null,
)

/**
 * A reversible swipe action, surfaced as an "Undo" snackbar. Holds one entry for a single-message
 * swipe, or every message of a thread for a collapsed-conversation swipe.
 */
data class UndoAction(
    val entries: List<UndoEntry>,
    val label: String,
    /** Has the write this reverses landed? False only between a swipe's offer, made when the finger
     *  leaves, and its write coming back; every other gesture offers Undo on an ack it already has. */
    var landed: Boolean = true,
    /** Has the offer's window run out unused? [undo] cancels the deadline, so it stays false there. */
    var windowClosed: Boolean = false,
) {
    /** Only a write that landed may be reversed: nothing moved, nothing to move back, and offline
     *  the round trip fails too and puts a second error banner over a row already home. */
    val mayReverse: Boolean get() = landed

    /** A banner is a message that arrived: it goes when that message really left AND the window
     *  the reader could have taken it back in has run out. Either alone takes down the notification
     *  of a message still in the folder. */
    val bannersMayGo: Boolean get() = landed && windowClosed

    /** Record where the write landed: on every entry, for the count reversal [undo] reads off them,
     *  and on the action. Called even when the bar is gone, the entries being this gesture's own. */
    fun landedIn(destMailboxId: String) {
        entries.forEach { it.destMailboxId = destMailboxId }
        landed = true
    }
}

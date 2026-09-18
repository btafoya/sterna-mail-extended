package app.sterna.ui.inbox

import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext

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
    /** A `var`: an offer made at the gesture is narrowed to what the write really moved, and the
     *  object is the one [InboxViewModel.undo] holds while it waits for [flight]. */
    var entries: List<UndoEntry>,
    val label: String,
    /** Has the write this reverses landed? False between an offer made with the rows (a swipe's
     *  when the finger leaves, a selection's at the tap) and its write coming back. A bar tapped
     *  in that window reverses nothing: the message is still where it was. */
    var landed: Boolean = true,
    /** Has the offer's window run out unused? [undo] cancels the deadline, so it stays false there. */
    var windowClosed: Boolean = false,
) {
    /** The write this offer reverses, while it is still out. [InboxViewModel.undo] joins it before
     *  restoring anything, so it must be the job making THIS offer's write and no other: a
     *  put-back sent into a move still on the wire crosses it. Written by [undoOffer] alone. */
    var flight: Job? = null

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

    /** Narrow an offer made at the gesture to [moved], what the write really did. [undo] reads
     *  [entries] again after joining [flight], so a row the server refused drops out here: it never
     *  left its folder, and moving it back would move it out of it. */
    fun landedOn(moved: List<UndoEntry>) {
        entries = moved
        landed = true
    }
}

/**
 * The bar a verdict leaves standing: [posted] is what was offered at the gesture, [moved] the rows
 * the server really took, always a subset of it. The same object when every offered row left, a new
 * bar over [moved] when fewer did, and null when nothing did.
 *
 * A new object is not cosmetic. `InboxViewModel.scheduleUndoneDeparture` freezes the banner keys of
 * the bar it is posted for, so a bar narrowed in place keeps a deadline naming rows that never left,
 * and that deadline takes down the notification of a message still in its folder. What this answers
 * is re-posted with a deadline of its own.
 */
internal suspend fun narrowedUndo(posted: UndoAction?, moved: List<UndoEntry>, label: String): UndoAction? = when {
    posted == null || moved.isEmpty() -> null
    moved.size == posted.entries.size -> posted
    else -> undoOffer(moved, label, landed = true)
}

/** An offer and the write it reverses, made together: [UndoAction.flight] is the coroutine this is
 *  called from, the one making that very write. Built beside a field instead, a bar carries
 *  whichever gesture wrote that field last, and Undo waits for a write that is not the one on
 *  screen. Every offer is built here. */
internal suspend fun undoOffer(
    entries: List<UndoEntry>,
    label: String,
    landed: Boolean = true,
): UndoAction = UndoAction(entries, label, landed = landed).also { it.flight = coroutineContext[Job] }

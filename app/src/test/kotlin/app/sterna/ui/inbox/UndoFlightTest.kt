package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A swipe offers its Undo when the finger leaves, seconds before the server answers. Three things
 * then hang on what that write did, and all three are read off the offer itself: whether Undo may
 * send anything at all, where the message landed (the drawer's count reversal reads it off the
 * entries), and whether the message's notification may be taken down.
 *
 * Executed here because `InboxViewModel` cannot be built on the JVM; that it is these answers the
 * view model consults is pinned as text by `LocalRemovalDropsItsBannerTest` and
 * `SwipeRewindWiringTest`.
 */
class UndoFlightTest {

    private fun entry(id: String = "m1") = UndoEntry(id, "acc", "inbox")

    // -- which bar a verdict leaves standing ----------------------------------------------------

    private fun landed(id: String) = UndoEntry(id, "acc", "inbox", "archive")

    @Test fun `a verdict that took every row leaves the very bar the gesture posted`() = runTest {
        val posted = UndoAction(listOf(entry("m1"), entry("m2")), "Archived", landed = false)

        assertSame(
            "nothing was refused, so the deadline already posted names exactly these rows: a " +
                "second bar here would be a second deadline over one gesture, and the first one " +
                "would still be running",
            posted,
            narrowedUndo(posted, listOf(landed("m1"), landed("m2")), "Archived"),
        )
    }

    @Test fun `a partly refused verdict answers a new bar over what really left`() = runTest {
        val posted = UndoAction(listOf(entry("m1"), entry("m2"), entry("m3")), "Archived", landed = false)

        val standing = narrowedUndo(posted, listOf(landed("m1")), "Archived")

        assertNotSame(
            "the deadline posted at the gesture froze the banner keys of all three. Narrowed in " +
                "place, it runs out six seconds later over rows the server refused: they are back " +
                "in the list, back in the folder, and their notifications are taken down with " +
                "nothing left to bring them back. A new bar is what the caller re-posts, with a " +
                "deadline of its own.",
            posted, standing,
        )
        assertEquals(
            "and it names only what left: the keys this bar's deadline will carry",
            listOf(EmailKey("acc", "m1")),
            undoBannerKeys(standing!!.entries),
        )
        assertTrue("landed, since what it holds is what the server took", standing!!.landed)
    }

    @Test fun `a verdict that moved nothing leaves no bar at all`() = runTest {
        val posted = UndoAction(listOf(entry("m1")), "Archived", landed = false)

        assertNull(
            "nothing departed, so there is nothing to offer back and no banner to take down: the " +
                "caller retracts the bar it put up at the finger",
            narrowedUndo(posted, emptyList(), "Archived"),
        )
    }

    @Test fun `a gesture that offers Undo on an ack it already has is reversible at once`() {
        val action = UndoAction(listOf(entry()), "Deleted")

        assertTrue(
            "the thread swipe and the batched selection build their entries from what the server " +
                "acknowledged, so their offer is reversible the moment it exists",
            action.mayReverse,
        )
    }

    @Test fun `a swipe's offer reverses nothing until its write lands`() {
        val posted = UndoAction(listOf(entry()), "Deleted", landed = false)

        assertFalse(
            "the offer is on screen while the write is still out. Reversing it would send a move " +
                "back for a message that never moved, and offline that round trip fails too: a " +
                "second error banner over a row that is already home.",
            posted.mayReverse,
        )
        assertFalse("and nothing departed yet, so its notification stays", posted.bannersMayGo)
    }

    @Test fun `a landing writes the destination on every entry, and opens the reversal`() {
        val posted = UndoAction(listOf(entry("m1"), entry("m2")), "Deleted", landed = false)

        posted.landedIn("trash")

        assertEquals(
            "every entry must carry it: restoreCounts reads the destination off each one and skips " +
                "the whole reversal for a null, which leaves the drawer counting the message in the " +
                "folder it was taken out of",
            listOf("trash", "trash"),
            posted.entries.map { it.destMailboxId },
        )
        assertTrue(posted.mayReverse)
    }

    @Test fun `a write that never landed keeps its banners, window closed or not`() {
        val refused = UndoAction(listOf(entry()), "Deleted", landed = false)

        assertFalse(refused.bannersMayGo)
        refused.windowClosed = true
        assertFalse(
            "the window runs out in six seconds and a phone's write can take ten. Taking the " +
                "notification down here announces a departure nobody has vouched for, and a " +
                "refused write then puts the row back with its banner already gone.",
            refused.bannersMayGo,
        )
    }

    @Test fun `a landing after the window closed releases the banners`() {
        val slow = UndoAction(listOf(entry()), "Deleted", landed = false)
        slow.windowClosed = true

        slow.landedIn("trash")

        assertTrue(
            "the message did depart in the end, and nothing else will come back to take its " +
                "notification down: this is the moment that must",
            slow.bannersMayGo,
        )
    }

    @Test fun `an undone departure keeps its banners`() {
        val undone = UndoAction(listOf(entry()), "Deleted", landed = false)

        undone.landedIn("trash")

        assertFalse(
            "undo() cancels the deadline, so the window never closes: the message is on its way " +
                "back into the folder and its notification must stay",
            undone.bannersMayGo,
        )
    }

    // -- the write an offer reverses ------------------------------------------------------------

    @Test fun `an offer holds the write that is making it, not the last one made`() = runTest {
        val letGo = CompletableDeferred<Unit>()
        lateinit var swipe: UndoAction
        lateinit var batch: UndoAction

        val swiping = launch {
            swipe = undoOffer(listOf(entry("m1")), "Archived", landed = false)
            letGo.await()
        }
        val batching = launch { batch = undoOffer(listOf(entry("m2")), "Deleted", landed = false) }
        batching.join()

        assertSame(
            "undo() joins the write of the bar it took, and the bar on screen can be the swipe's " +
                "while a later selection is the last thing to have written a field. Joined against " +
                "that later job, the put-back leaves while the move it reverses is still on the " +
                "wire, which is the one thing the wait exists to prevent.",
            swiping,
            swipe.flight,
        )
        assertSame(batching, batch.flight)
        assertTrue("and the later gesture being over says nothing about the swipe's write", swiping.isActive)

        letGo.complete(Unit)
        swiping.join()
    }

    @Test fun `an offer narrowed to what really left forgets what the server refused`() {
        val posted = UndoAction(listOf(entry("m1"), entry("m2")), "Archived 2", landed = false)

        posted.landedOn(listOf(UndoEntry("m1", "acc", "inbox", "archive")))

        assertEquals(
            "m2 was refused: it never left its folder, and reversing it would move it out of the " +
                "folder it is sitting in. The object is the one Undo holds while it waits.",
            listOf("m1"),
            posted.entries.map { it.emailId },
        )
        assertTrue("and what did leave may now be reversed", posted.mayReverse)
    }
}

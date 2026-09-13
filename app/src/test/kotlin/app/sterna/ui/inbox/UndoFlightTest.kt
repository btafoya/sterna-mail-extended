package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

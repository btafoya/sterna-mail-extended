package app.sterna.ui.inbox

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision a swipe takes when its write comes back, executed rather than read off the arm that
 * calls it. The row has already left the list by then, so what this answers is whether the message
 * the user watched fly away comes back to its place.
 */
class SwipeWriteTest {

    @Test fun `a write that names a destination landed`() {
        assertEquals(SwipeWrite.LANDED, swipeWrite(Result.success("mailbox-trash")))
    }

    @Test fun `a landed write keeps the row gone, and its Undo standing`() {
        assertFalse(
            "the message really moved: putting the row back would show mail that has left the folder",
            SwipeWrite.LANDED.putsRowBack,
        )
        assertTrue("a move that landed is the one thing there is to undo", SwipeWrite.LANDED.offersUndo)
    }

    @Test fun `a refused write puts the row back, and offers nothing to undo`() {
        val refused = swipeWrite(Result.failure(IOException("no route to host")))

        assertEquals(SwipeWrite.REFUSED, refused)
        assertTrue(
            "the row left the list when the finger did, and a refusal is the only thing that " +
                "brings it back — the message is still on the server, in the folder being read",
            refused.putsRowBack,
        )
        assertFalse("never an Undo for a gesture the server refused", refused.offersUndo)
    }

    @Test fun `a write that moved nothing puts the row back, and offers nothing to undo`() {
        val nothing = swipeWrite(Result.success(null))

        assertEquals(SwipeWrite.NOTHING_MOVED, nothing)
        assertTrue(
            "a null destination is the mover saying it moved nothing: the message is still in the " +
                "folder, and the movers return here BEFORE nudging any count, so leaving the row " +
                "out loses the line and a total and an unread from the drawer. The one message " +
                "that really is gone is the one the server answered notFound for, and that one is " +
                "not held any more, so asking for it back puts nothing back.",
            nothing.putsRowBack,
        )
        assertFalse("never an Undo for a gesture that did not happen", nothing.offersUndo)
    }
}

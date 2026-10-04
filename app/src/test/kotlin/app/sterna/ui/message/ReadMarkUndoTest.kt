package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A read mark whose write failed is taken back from the page: the reader was showing "read" for a
 * message neither the cache nor the server held as read. Both halves of that are run here, the
 * decision in [readMarkToUndo] and the keyword in [withoutReadMark]; [ReadMarkUndoWiringTest] pins
 * that the view model reaches them.
 */
class ReadMarkUndoTest {

    @Test fun `the message still on the page, on the account written to, is the one undone`() {
        assertEquals(
            "m1",
            readMarkToUndo(shownId = "m1", shownAccount = "a", writtenId = "m1", writtenAccount = "a"),
        )
    }

    @Test fun `a page that has loaded another message is left alone`() {
        assertNull(
            "the write for m1 returned after the page loaded m2: undoing would reset the settle of m2",
            readMarkToUndo(shownId = "m2", shownAccount = "a", writtenId = "m1", writtenAccount = "a"),
        )
    }

    @Test fun `the same id on another account is left alone`() {
        assertNull(
            "ids repeat across accounts: m1 of account b is not the m1 whose write failed",
            readMarkToUndo(shownId = "m1", shownAccount = "b", writtenId = "m1", writtenAccount = "a"),
        )
    }

    @Test fun `a page with nothing loaded, or no account, is left alone`() {
        assertNull(readMarkToUndo(shownId = null, shownAccount = "a", writtenId = "m1", writtenAccount = "a"))
        assertNull(readMarkToUndo(shownId = "m1", shownAccount = null, writtenId = "m1", writtenAccount = "a"))
    }

    @Test fun `taking the read mark back removes the seen keyword and keeps the star`() {
        val painted = Email(id = "1", keywords = mapOf("\$seen" to true, "\$flagged" to true))

        val shown = painted.withoutReadMark()

        assertFalse(shown.isSeen)
        assertEquals(mapOf("\$flagged" to true), shown.keywords)
    }

    @Test fun `a message that carries no read mark is unchanged`() {
        val unread = Email(id = "1", keywords = mapOf("\$flagged" to true))

        assertEquals(unread, unread.withoutReadMark())
    }
}

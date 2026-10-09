package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a screen reader is told of a mail row's selected, unread and starred states, as one phrase. */
class RowStateDescriptionTest {

    @Test fun `an unread row says so`() {
        assertEquals("Unread", describe(unread = true, starred = false))
    }

    @Test fun `a starred row says so`() {
        assertEquals("Starred", describe(unread = false, starred = true))
    }

    @Test fun `both states are joined, unread first`() {
        assertEquals("Unread, Starred", describe(unread = true, starred = true))
    }

    @Test fun `a read, unstarred row has no state at all, not an empty one`() {
        assertNull(describe(unread = false, starred = false))
    }

    @Test fun `a selected row says so first, then what it said before`() {
        assertEquals("Selected, Unread, Starred", describe(selected = true, unread = true, starred = true))
    }

    @Test fun `a selected row keeps the one state it has`() {
        assertEquals("Selected, Unread", describe(selected = true, unread = true, starred = false))
        assertEquals("Selected, Starred", describe(selected = true, unread = false, starred = true))
    }

    @Test fun `a selected row that is read and unstarred says selected alone`() {
        assertEquals("Selected", describe(selected = true, unread = false, starred = false))
    }

    private fun describe(unread: Boolean, starred: Boolean, selected: Boolean = false): String? =
        rowStateDescription(
            selected = selected,
            unread = unread,
            starred = starred,
            selectedLabel = "Selected",
            unreadLabel = "Unread",
            starredLabel = "Starred",
        )
}

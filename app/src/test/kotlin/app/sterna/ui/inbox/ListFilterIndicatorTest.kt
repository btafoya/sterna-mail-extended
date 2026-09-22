package app.sterna.ui.inbox

import app.sterna.core.data.mail.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions behind the toolbar funnel's sign and the empty state's "Show all", run.
 * `FilterEntryWiringLintTest` pins that the screen asks them.
 */
class ListFilterIndicatorTest {

    @Test
    fun `each toggle alone lights the funnel`() {
        assertTrue(
            "Starred alone hides rows, and the menu entry that set it is closed: with no dot and " +
                "no accent the reader sees a short list and nothing saying why.",
            ListFilter(flaggedOnly = true).anyActive(),
        )
        assertTrue("attachment alone must light the funnel", ListFilter(withAttachment = true).anyActive())
        assertTrue("unread alone must light the funnel", ListFilter(unreadOnly = true).anyActive())
    }

    @Test
    fun `no toggle, no sign`() {
        assertFalse(ListFilter.NONE.anyActive())
    }

    @Test
    fun `starred or attachment narrow beyond unread, unread alone does not`() {
        assertTrue(ListFilter(flaggedOnly = true).narrowsBeyondUnread())
        assertTrue(ListFilter(withAttachment = true).narrowsBeyondUnread())
        assertFalse(
            "Unread alone must not make the funnel say 'other filters on': there are none.",
            ListFilter(unreadOnly = true).narrowsBeyondUnread(),
        )
        assertFalse(ListFilter.NONE.narrowsBeyondUnread())
    }

    @Test
    fun `in the unread view a starred or attachment toggle puts the dot on the overflow button`() {
        assertTrue(
            "The unread view hides the funnel: under starred there, the overflow button is the " +
                "only sign on screen that rows are hidden.",
            ListFilter(flaggedOnly = true).dotOnOverflow(unreadView = true),
        )
        assertTrue(ListFilter(withAttachment = true).dotOnOverflow(unreadView = true))
    }

    @Test
    fun `no overflow dot for what the unread view forces, nor outside that view`() {
        assertFalse(
            "The view forces unread on; a dot for it would be on every visit to the view.",
            ListFilter(unreadOnly = true).dotOnOverflow(unreadView = true),
        )
        assertFalse(ListFilter.NONE.dotOnOverflow(unreadView = true))
        assertFalse(
            "Outside the unread view the funnel carries the sign; a second dot on the overflow " +
                "button would be read as the outbox's.",
            ListFilter(flaggedOnly = true, withAttachment = true).dotOnOverflow(unreadView = false),
        )
    }

    @Test
    fun `show all under starred alone lifts the starred toggle`() {
        assertEquals(
            "Under starred alone, turning off only the funnel toggles unread ON and keeps starred: " +
                "the list stays empty, or gets emptier, under a button called Show all.",
            ListFilter.NONE,
            ListFilter(flaggedOnly = true).afterShowAll(),
        )
    }

    @Test
    fun `show all lifts every combination of toggles`() {
        listOf(false, true).forEach { unread ->
            listOf(false, true).forEach { flagged ->
                listOf(false, true).forEach { attachment ->
                    val before = ListFilter(unread, flagged, attachment)
                    assertEquals("after Show all from $before", ListFilter.NONE, before.afterShowAll())
                }
            }
        }
    }
}

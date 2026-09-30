package app.sterna.ui.inbox

import app.sterna.core.data.mail.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions behind the empty state's filtered words and its "Clear filters", run.
 * `FilterEntryWiringLintTest` pins that the screen asks them.
 */
class ListFilterIndicatorTest {

    @Test
    fun `starred or attachment narrow beyond unread, unread alone does not`() {
        assertTrue(ListFilter(flaggedOnly = true).narrowsBeyondUnread())
        assertTrue(ListFilter(withAttachment = true).narrowsBeyondUnread())
        assertFalse(
            "Unread alone keeps the unread words: the general ones would name no other filter.",
            ListFilter(unreadOnly = true).narrowsBeyondUnread(),
        )
        assertFalse(ListFilter.NONE.narrowsBeyondUnread())
    }

    @Test
    fun `show all under starred alone lifts the starred toggle`() {
        assertEquals(
            "Under starred alone, flipping only unread turns it ON and keeps starred: " +
                "the list stays empty, or gets emptier, under a button called Clear filters.",
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
                    assertEquals("after Clear filters from $before", ListFilter.NONE, before.afterShowAll())
                }
            }
        }
    }
}

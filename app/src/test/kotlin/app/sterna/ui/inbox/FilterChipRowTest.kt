package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.mail.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which quick-filter chips the list offers, run. `FilterEntryWiringLintTest` pins that the screen asks. */
class FilterChipRowTest {

    @Test
    fun `a folder or the unified inbox offers the three chips, in row order`() {
        assertEquals(
            QuickFilterRow(
                listOf(
                    QuickFilter(ListCriterion.UNREAD, false),
                    QuickFilter(ListCriterion.FLAGGED, true),
                    QuickFilter(ListCriterion.ATTACHMENT, false),
                ),
                enabled = true,
            ),
            filterChipRow(unreadView = false, selectionActive = false, searching = false, toggles = ListFilter(flaggedOnly = true)),
        )
    }

    @Test
    fun `the unread view offers no Unread chip`() {
        assertEquals(
            "The view forces unread on: an Unread chip there would be drawn off over a filtered " +
                "list, and tapping it would change nothing on screen.",
            QuickFilterRow(
                listOf(QuickFilter(ListCriterion.FLAGGED, false), QuickFilter(ListCriterion.ATTACHMENT, true)),
                enabled = true,
            ),
            filterChipRow(
                unreadView = true,
                selectionActive = false,
                searching = false,
                toggles = ListFilter(unreadOnly = true, withAttachment = true),
            ),
        )
    }

    @Test
    fun `a selection leaves the chips on screen, inert, their state still shown`() {
        assertEquals(
            "selectAll reads the same listFilter (#126): a chip flipped mid-selection would change " +
                "what the selection covers under the reader's thumb.",
            QuickFilterRow(
                listOf(
                    QuickFilter(ListCriterion.UNREAD, true),
                    QuickFilter(ListCriterion.FLAGGED, false),
                    QuickFilter(ListCriterion.ATTACHMENT, true),
                ),
                enabled = false,
            ),
            filterChipRow(
                unreadView = false,
                selectionActive = true,
                searching = false,
                toggles = ListFilter(unreadOnly = true, withAttachment = true),
            ),
        )
    }

    @Test
    fun `an open search bar takes the list's chips away, its own row stands in their place`() {
        assertNull(
            "One row of chips at a time: the search's row carries its own Unread, Starred and " +
                "Has attachment, and search results are not filtered by the list's toggles.",
            filterChipRow(unreadView = false, selectionActive = false, searching = true, toggles = ListFilter(flaggedOnly = true)),
        )
        assertEquals(
            filterChipRow(unreadView = false, selectionActive = false, searching = false, toggles = ListFilter.NONE),
            QuickFilterRow(
                listOf(
                    QuickFilter(ListCriterion.UNREAD, false),
                    QuickFilter(ListCriterion.FLAGGED, false),
                    QuickFilter(ListCriterion.ATTACHMENT, false),
                ),
                enabled = true,
            ),
        )
    }

    @Test
    fun `each chip is named by its own word`() {
        assertEquals(
            listOf(R.string.filter_unread, R.string.filter_starred, R.string.filter_attachment),
            listOf(ListCriterion.UNREAD, ListCriterion.FLAGGED, ListCriterion.ATTACHMENT).map { filterLabel(it) },
        )
        assertEquals(
            "Two filter labels resolve to the same id, so the assertion above compares a value with itself.",
            3,
            setOf(R.string.filter_unread, R.string.filter_starred, R.string.filter_attachment).size,
        )
    }
}

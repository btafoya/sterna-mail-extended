package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.mail.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the bar's funnel offers and whether it is lit, run. `FilterEntryWiringLintTest` pins that the screen asks. */
class FilterMenuTest {

    @Test
    fun `a folder or the unified inbox offers the three filters, in menu order, each with its tick`() {
        assertEquals(
            listOf(
                QuickFilter(ListCriterion.UNREAD, false),
                QuickFilter(ListCriterion.FLAGGED, true),
                QuickFilter(ListCriterion.ATTACHMENT, false),
            ),
            filterMenu(unreadView = false, toggles = ListFilter(flaggedOnly = true)).filters,
        )
    }

    @Test
    fun `the unread view offers no Unread filter`() {
        assertEquals(
            "The view forces unread on: an Unread entry there would be drawn unticked over a " +
                "filtered list, and ticking it would change nothing on screen.",
            listOf(QuickFilter(ListCriterion.FLAGGED, false), QuickFilter(ListCriterion.ATTACHMENT, true)),
            filterMenu(unreadView = true, toggles = ListFilter(unreadOnly = true, withAttachment = true)).filters,
        )
    }

    @Test
    fun `the funnel is lit by any one filter, and dark with none`() {
        assertFalse(filterMenu(unreadView = false, toggles = ListFilter.NONE).active)
        listOf(
            ListFilter(unreadOnly = true),
            ListFilter(flaggedOnly = true),
            ListFilter(withAttachment = true),
        ).forEach { toggles ->
            assertTrue("$toggles must light the funnel", filterMenu(unreadView = false, toggles = toggles).active)
        }
    }

    @Test
    fun `in the unread view the unread toggle alone leaves the funnel dark`() {
        assertFalse(
            "unreadOnly is the view there, not a filter the menu offers to lift: a lit funnel " +
                "would open on three unticked boxes, or two.",
            filterMenu(unreadView = true, toggles = ListFilter(unreadOnly = true)).active,
        )
        assertTrue(filterMenu(unreadView = true, toggles = ListFilter(unreadOnly = true, flaggedOnly = true)).active)
        assertTrue(filterMenu(unreadView = true, toggles = ListFilter(withAttachment = true)).active)
    }

    @Test
    fun `each filter is named by its own word`() {
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

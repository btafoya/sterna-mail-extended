package app.sterna.ui.inbox

import app.sterna.core.data.mail.ListFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the LIST is filtered, the one answer the pager and "Select all" both ask.
 */
class ListFilterDecisionTest {

    @Test
    fun `the unread scope filters to unread whether or not the funnel is on`() {
        assertTrue(
            "a view called \"Unread\" showing read mail is a lie on the screen (WYSIWYG), and — worse " +
                "— a \"Select all\" that reaches it",
            listFilter(Sel.Unread, ListFilter.NONE).unreadOnly,
        )
        assertTrue(listFilter(Sel.Unread, ListFilter(unreadOnly = true)).unreadOnly)
    }

    @Test
    fun `a folder follows the funnel and nothing else`() {
        assertFalse(
            "the funnel off in a plain folder must show the whole folder — forcing it on anywhere " +
                "else is the mirror mistake, and it hides mail",
            listFilter(Sel.Folder("inbox-a"), ListFilter.NONE).unreadOnly,
        )
        assertTrue(listFilter(Sel.Folder("inbox-a"), ListFilter(unreadOnly = true)).unreadOnly)
    }

    @Test
    fun `the unified inbox follows the funnel and nothing else`() {
        assertFalse(listFilter(Sel.Unified, ListFilter.NONE).unreadOnly)
        assertTrue(listFilter(Sel.Unified, ListFilter(unreadOnly = true)).unreadOnly)
    }

    @Test
    fun `a null folder id does not make a folder into the unread scope`() {
        // Sel.Folder(null) is the state before the inbox id is known. It is a folder, not the
        // unread scope, and a comparison written on the wrong thing would force the filter there.
        assertFalse(listFilter(Sel.Folder(null), ListFilter.NONE).unreadOnly)
    }

    @Test
    fun `the starred and attachment toggles pass through in every scope, the unread view included`() {
        val both = ListFilter(flaggedOnly = true, withAttachment = true)
        listOf(Sel.Folder("i"), Sel.Unified, Sel.Unread).forEach { sel ->
            val answer = listFilter(sel, both)
            assertTrue("starred dropped in $sel: a hidden message would be selectable", answer.flaggedOnly)
            assertTrue("attachment dropped in $sel", answer.withAttachment)
        }
        listOf(Sel.Folder("i"), Sel.Unified, Sel.Unread).forEach { sel ->
            val answer = listFilter(sel, ListFilter.NONE)
            assertFalse("starred forced on in $sel", answer.flaggedOnly)
            assertFalse("attachment forced on in $sel", answer.withAttachment)
        }
    }

    @Test
    fun `every scope and toggle state, written out`() {
        val starred = ListFilter(flaggedOnly = true)
        val clip = ListFilter(withAttachment = true)
        assertEquals(
            listOf(
                ListFilter.NONE,
                ListFilter(unreadOnly = true),
                starred,
                clip,
                ListFilter(unreadOnly = true),
                ListFilter(unreadOnly = true, flaggedOnly = true),
                ListFilter(unreadOnly = true, withAttachment = true),
            ),
            listOf(
                listFilter(Sel.Unified, ListFilter.NONE),
                listFilter(Sel.Folder("i"), ListFilter(unreadOnly = true)),
                listFilter(Sel.Folder("i"), starred),
                listFilter(Sel.Unified, clip),
                listFilter(Sel.Unread, ListFilter.NONE),
                listFilter(Sel.Unread, starred),
                listFilter(Sel.Unread, clip),
            ),
        )
    }

    @Test
    fun `the screen's overload answers what the pager's does`() {
        val toggles = ListFilter(flaggedOnly = true, withAttachment = true)
        assertEquals(listFilter(Sel.Unread, toggles), listFilter(unreadView = true, toggles))
        assertEquals(listFilter(Sel.Unified, toggles), listFilter(unreadView = false, toggles))
    }

    // -- the toggles --------------------------------------------------------------------------------

    @Test
    fun `each toggle flips its own criterion and leaves the other two alone`() {
        val none = ListFilter.NONE
        val all = ListFilter(unreadOnly = true, flaggedOnly = true, withAttachment = true)
        assertEquals(ListFilter(unreadOnly = true), none.toggled(ListCriterion.UNREAD))
        assertEquals(ListFilter(flaggedOnly = true), none.toggled(ListCriterion.FLAGGED))
        assertEquals(ListFilter(withAttachment = true), none.toggled(ListCriterion.ATTACHMENT))
        assertEquals(ListFilter(flaggedOnly = true, withAttachment = true), all.toggled(ListCriterion.UNREAD))
        assertEquals(ListFilter(unreadOnly = true, withAttachment = true), all.toggled(ListCriterion.FLAGGED))
        assertEquals(ListFilter(unreadOnly = true, flaggedOnly = true), all.toggled(ListCriterion.ATTACHMENT))
        assertEquals(
            "a toggle turns its criterion off as well as on",
            ListFilter(unreadOnly = true),
            ListFilter(unreadOnly = true, flaggedOnly = true).toggled(ListCriterion.FLAGGED),
        )
    }

    @Test
    fun `toggling twice gives back the filter, from every state`() {
        val states = listOf(false, true).flatMap { u ->
            listOf(false, true).flatMap { f -> listOf(false, true).map { a -> ListFilter(u, f, a) } }
        }
        states.forEach { start ->
            ListCriterion.entries.forEach { c ->
                assertEquals("$c twice from $start", start, start.toggled(c).toggled(c))
                assertTrue("$c must change something from $start", start.toggled(c) != start)
            }
        }
    }
}

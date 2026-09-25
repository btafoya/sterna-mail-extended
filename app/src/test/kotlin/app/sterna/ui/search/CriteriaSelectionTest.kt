package app.sterna.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.inbox.RemovedRows
import app.sterna.ui.inbox.RowTap
import app.sterna.ui.inbox.SelectionOrigin
import app.sterna.ui.inbox.SelectionSources
import app.sterna.ui.inbox.ShownResults
import app.sterna.ui.inbox.selectionShownOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advanced search's selection (#234), executed: the tap decision, the snapshot the inbox's
 * batch gestures write into ([CriteriaResults]), and the sources that route those writes and
 * "select all" ([SelectionSources]).
 */
class CriteriaSelectionTest {

    private fun email(account: String?, id: String, seen: Boolean = false) =
        Email(id = id, accountId = account, keywords = if (seen) mapOf("\$seen" to true) else emptyMap())

    private val a = email("accA", "m1")
    private val b = email("accA", "m2")
    private val c = email("accA", "m3")

    /** Same JMAP id in another account (#92). */
    private val bElsewhere = email("accB", "m2")

    private fun results(vararg emails: Email, complete: Boolean = true) = MutableStateFlow<SearchState>(
        SearchState.Results(
            query = SearchQuery(text = "invoice"),
            emails = emails.toList(),
            complete = complete,
            excerpts = emails.associate { searchResultKey(it) to "passage of ${it.accountId}/${it.id}" },
        ),
    )

    private fun MutableStateFlow<SearchState>.shown(): SearchState.Results = value as SearchState.Results

    /** The inbox's search bar, as the sources see it: a plain list. */
    private class Bar(var list: List<Email>?) : ShownResults {
        private val removed = RemovedRows()
        override val rows: List<Email>? get() = list
        override fun drop(keys: Set<EmailKey>) { removed.drop(list, keys)?.let { list = it } }
        override fun restore(keys: Collection<EmailKey>) { removed.restore(list, keys)?.let { list = it } }
        override fun patch(keys: Set<EmailKey>, transform: (Email) -> Email) = Unit
    }

    /** The search screen's ViewModel, which only the store that holds it may clear. */
    class Owner : ViewModel()

    private val store = ViewModelStore()
    private var owners = 0

    private fun criteria(state: MutableStateFlow<SearchState>) =
        CriteriaResults(state, ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())["owner${owners++}", Owner::class.java])

    // ---- the tap ----

    @Test fun `in selection a tap ticks the result, whatever folder it is filed in`() {
        assertEquals(RowTap.SELECT_ROW, criteriaHitTap(localDraft = false, selecting = true, hitRole = "inbox"))
        assertEquals(RowTap.SELECT_ROW, criteriaHitTap(localDraft = false, selecting = true, hitRole = "drafts"))
        assertEquals(RowTap.SELECT_ROW, criteriaHitTap(localDraft = false, selecting = true, hitRole = null))
    }

    @Test fun `outside a selection a tap opens, and a hit filed in Drafts opens the composer`() {
        assertEquals(RowTap.OPEN, criteriaHitTap(localDraft = false, selecting = false, hitRole = "inbox"))
        assertEquals(RowTap.OPEN, criteriaHitTap(localDraft = false, selecting = false, hitRole = null))
        assertEquals(RowTap.EDIT_DRAFT, criteriaHitTap(localDraft = false, selecting = false, hitRole = "drafts"))
    }

    // ---- the snapshot ----

    @Test fun `a removal is written into the results, and its passage goes with it`() {
        val state = results(a, b, c)
        criteria(state).drop(setOf(b.emailKey()))
        assertEquals(listOf("m1", "m3"), state.shown().emails.map { it.id })
        assertEquals(setOf(searchResultKey(a), searchResultKey(c)), state.shown().excerpts.keys)
    }

    @Test fun `a failed key is put back where it was, with its passage`() {
        val state = results(a, b, c)
        val shown = criteria(state)
        shown.drop(setOf(b.emailKey(), c.emailKey()))
        shown.restore(listOf(b.emailKey()))
        assertEquals(listOf("m1", "m2"), state.shown().emails.map { it.id })
        assertEquals("passage of accA/m2", state.shown().excerpts[searchResultKey(b)])
        assertNull(state.shown().excerpts[searchResultKey(c)])
    }

    @Test fun `a restore puts back only what was removed, and never twice`() {
        val state = results(a, b)
        val shown = criteria(state)
        shown.restore(listOf(a.emailKey()))
        assertEquals(listOf("m1", "m2"), state.shown().emails.map { it.id })
        shown.drop(setOf(a.emailKey()))
        shown.restore(listOf(a.emailKey()))
        shown.restore(listOf(a.emailKey()))
        assertEquals(listOf("m1", "m2"), state.shown().emails.map { it.id })
    }

    @Test fun `the same id in another account is neither removed nor stripped of its passage`() {
        val state = results(b, bElsewhere)
        criteria(state).drop(setOf(b.emailKey()))
        assertEquals(listOf(bElsewhere.emailKey()), state.shown().emails.map { it.emailKey() })
        assertEquals(setOf(searchResultKey(bElsewhere)), state.shown().excerpts.keys)
    }

    @Test fun `marking read rewrites the rows in place`() {
        val state = results(a, b)
        criteria(state).patch(setOf(b.emailKey())) { it.copy(keywords = mapOf("\$seen" to true)) }
        assertEquals(listOf(false, true), state.shown().emails.map { it.isSeen })
    }

    @Test fun `an Undo after a new search puts none of the previous answer's rows into the new one`() {
        val state = results(a, b)
        val shown = criteria(state)
        shown.drop(setOf(a.emailKey()))
        state.value = SearchState.Searching
        state.value = results(b, c).value
        shown.restore(listOf(a.emailKey()))
        assertEquals(listOf("m2", "m3"), state.shown().emails.map { it.id })
    }

    @Test fun `the screen's ViewModel cleared hands the origin back, and the inbox draws its own selection again`() {
        val shown = criteria(results(a))
        val sources = SelectionSources(Bar(listOf(c))).apply { attach(shown) }
        var cleared = false
        shown.whenGone { cleared = sources.detach(shown) }
        sources.startOn(SelectionOrigin.CRITERIA)
        store.clear()
        assertTrue(cleared)
        assertEquals(SelectionOrigin.LIST, sources.origin.value)
        assertTrue(selectionShownOn(active = true, origin = sources.origin.value, screen = SelectionOrigin.LIST))
        assertEquals(listOf("m3"), sources.displayed()?.map { it.id })
    }

    // ---- the sources ----

    @Test fun `a batch's removal reaches the advanced search's results`() {
        val state = results(a, b, c)
        val sources = SelectionSources(Bar(null)).apply { attach(criteria(state)) }
        sources.drop(setOf(a.emailKey(), c.emailKey()))
        assertEquals(listOf("m2"), state.shown().emails.map { it.id })
        assertEquals(setOf(searchResultKey(b)), state.shown().excerpts.keys)
    }

    @Test fun `the keys a batch failed on are put back into the advanced search's results`() {
        val state = results(a, b, c)
        val sources = SelectionSources(Bar(null)).apply { attach(criteria(state)) }
        sources.drop(setOf(a.emailKey(), c.emailKey()))
        sources.restore(setOf(c.emailKey()))
        assertEquals(listOf("m2", "m3"), state.shown().emails.map { it.id })
        assertEquals("passage of accA/m3", state.shown().excerpts[searchResultKey(c)])
    }

    @Test fun `a removal reaches the search bar's results too`() {
        val bar = Bar(listOf(a, b))
        val sources = SelectionSources(bar).apply { attach(criteria(results(a, b))) }
        sources.drop(setOf(a.emailKey()))
        assertEquals(listOf("m2"), bar.list?.map { it.id })
    }

    @Test fun `select all takes the drawn results and nothing else, even under 'at least N'`() {
        val state = results(a, b, complete = false)
        val sources = SelectionSources(Bar(listOf(c))).apply { attach(criteria(state)) }
        sources.startOn(SelectionOrigin.CRITERIA)
        assertEquals(setOf(a.emailKey(), b.emailKey()), sources.shownKeys())
    }

    @Test fun `select all never takes a draft only this phone holds`() {
        val local = email("accA", "${LOCAL_DRAFT_ID_PREFIX}7")
        val sources = SelectionSources(Bar(null)).apply { attach(criteria(results(a, local))) }
        assertEquals(setOf(a.emailKey()), sources.shownKeys())
    }

    @Test fun `select all on a screen that is gone takes nothing`() {
        val shown = criteria(results(a, b))
        val sources = SelectionSources(Bar(listOf(c))).apply { attach(shown) }
        sources.detach(shown)
        assertEquals(emptySet<EmailKey>(), sources.shownKeys())
    }

    @Test fun `the rows a selection resolves against are those of the screen it was ticked on`() {
        val sources = SelectionSources(Bar(listOf(c))).apply { attach(criteria(results(a, b))) }
        assertEquals(listOf("m3"), sources.displayed()?.map { it.id })
        sources.startOn(SelectionOrigin.CRITERIA)
        assertEquals(listOf("m1", "m2"), sources.displayed()?.map { it.id })
    }

    @Test fun `leaving the screen clears only a selection ticked there, and hands the origin back`() {
        val shown = criteria(results(a))
        val sources = SelectionSources(Bar(null)).apply { attach(shown) }
        assertFalse(sources.detach(criteria(results(a))))
        sources.startOn(SelectionOrigin.CRITERIA)
        assertTrue(sources.detach(shown))
        assertEquals(SelectionOrigin.LIST, sources.origin.value)
        assertFalse(sources.detach(shown))
    }

    @Test fun `each screen draws only the selection ticked on it`() {
        assertTrue(selectionShownOn(active = true, origin = SelectionOrigin.CRITERIA, screen = SelectionOrigin.CRITERIA))
        assertFalse(selectionShownOn(active = true, origin = SelectionOrigin.CRITERIA, screen = SelectionOrigin.LIST))
        assertFalse(selectionShownOn(active = true, origin = SelectionOrigin.LIST, screen = SelectionOrigin.CRITERIA))
        assertFalse(selectionShownOn(active = false, origin = SelectionOrigin.CRITERIA, screen = SelectionOrigin.CRITERIA))
    }

    // ---- handing a selection back ----

    @Test fun `a selection handed back after its screen is gone is not ticked again`() {
        val shown = criteria(results(a))
        val sources = SelectionSources(Bar(null)).apply { attach(shown) }
        sources.startOn(SelectionOrigin.CRITERIA)
        val seat = sources.seat()
        assertTrue(sources.stillOn(seat))
        sources.detach(shown)
        assertFalse(sources.stillOn(seat))
    }

    @Test fun `nor on another advanced search opened since`() {
        val shown = criteria(results(a))
        val sources = SelectionSources(Bar(null)).apply { attach(shown) }
        sources.startOn(SelectionOrigin.CRITERIA)
        val seat = sources.seat()
        sources.detach(shown)
        sources.attach(criteria(results(a)))
        sources.startOn(SelectionOrigin.CRITERIA)
        assertFalse(sources.stillOn(seat))
    }

    @Test fun `a selection handed back to the list is ticked again unless a search screen's selection took over`() {
        val sources = SelectionSources(Bar(listOf(c)))
        val seat = sources.seat()
        sources.attach(criteria(results(a)))
        assertTrue(sources.stillOn(seat))
        sources.startOn(SelectionOrigin.CRITERIA)
        assertFalse(sources.stillOn(seat))
    }

    // ---- a write one message after the other (the snooze) ----

    @Test fun `a written result leaves both lists, one that failed stays, and an Undo brings the first back`() = runBlocking {
        val state = results(a, b, c)
        val bar = Bar(listOf(a, b, c))
        val sources = SelectionSources(bar).apply { attach(criteria(state)) }
        var drawnDuringTheWrite: List<String>? = null
        sources.writingOut(setOf(a.emailKey(), b.emailKey())) {
            drawnDuringTheWrite = state.shown().emails.map { it.id }
            listOf(b.emailKey())
        }
        assertEquals(listOf("m3"), drawnDuringTheWrite)
        assertEquals(listOf("m1", "m3"), state.shown().emails.map { it.id })
        assertEquals(listOf("m1", "m3"), bar.list?.map { it.id })
        sources.restore(listOf(b.emailKey()))
        assertEquals(listOf("m1", "m2", "m3"), state.shown().emails.map { it.id })
        assertEquals(listOf("m1", "m2", "m3"), bar.list?.map { it.id })
    }
}

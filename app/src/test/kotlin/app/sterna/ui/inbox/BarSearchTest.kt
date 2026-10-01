package app.sterna.ui.inbox

import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.search.AddressNames
import app.sterna.ui.search.PickedName
import app.sterna.ui.search.SearchDisplay
import app.sterna.ui.search.SearchField
import app.sterna.ui.search.SearchToggle
import app.sterna.ui.search.searchDisplay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The search bar's two legs, executed in virtual time over fakes of the index and the server.
 *
 * A leg answered "late" is a leg suspended in `suspendCoroutine`, which a cancellation does not
 * wake: resumed after the criteria changed, the cancelled run goes on to its write, as a transport
 * that ignores cancellation would. Without that, cancelling the run would hide the guard under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BarSearchTest {

    private fun email(id: String, at: String = "2026-09-01T10:00:00Z") = Email(id = id, accountId = "acc", receivedAt = at)

    private val state = MutableStateFlow(SearchUi(active = true))

    private fun ids(): List<String>? = state.value.results?.map { it.id }

    /**
     * Answers held back until the test gives them: a leg parked here is not woken by a
     * cancellation. [drain] hands every answer still held its [fallback], so an assertion that fails
     * midway never leaves a run suspended for ever, which would hang the whole test task.
     */
    private class Gate<T>(private val fallback: T) {
        private val held = mutableListOf<Pair<SearchQuery, Continuation<T>>>()

        suspend fun hold(criteria: SearchQuery): T = suspendCoroutine { held += criteria to it }

        fun answer(criteria: SearchQuery, value: T) {
            val at = held.indexOfFirst { it.first == criteria }
            check(at >= 0) { "no leg is waiting on $criteria; waiting: ${held.map { it.first }}" }
            held.removeAt(at).second.resume(value)
        }

        fun holding(): Boolean = held.isNotEmpty()

        fun releaseAll() {
            while (held.isNotEmpty()) held.removeAt(0).second.resume(fallback)
        }
    }

    private fun TestScope.drain(gate: Gate<*>) {
        while (gate.holding()) {
            gate.releaseAll()
            advanceUntilIdle()
        }
    }

    private fun TestScope.bar(
        local: suspend (SearchQuery) -> List<Email> = { emptyList() },
        server: suspend (SearchQuery) -> MailSearchResult = { MailSearchResult(emptyList()) },
    ) = BarSearch(this, state, RemovedRows(), indexReady = {}, local = { q, _ -> local(q) }, server = { q, _ -> server(q) })

    /** A bar whose legs see the scope they are asked under, the only way a test can tell two scopes apart. */
    private fun TestScope.scopedBar(
        local: suspend (SearchQuery, SearchScope) -> List<Email> = { _, _ -> emptyList() },
        server: suspend (SearchQuery, SearchScope) -> MailSearchResult = { _, _ -> MailSearchResult(emptyList()) },
    ) = BarSearch(this, state, RemovedRows(), indexReady = {}, local = local, server = server)

    // ---- M5: one debounce for every change ----

    @Test fun `a burst of chips costs one server run, the pause after the last one`() = runTest {
        val asked = mutableListOf<SearchQuery>()
        val bar = bar(server = { asked += it; MailSearchResult(emptyList()) })

        bar.toggle(SearchToggle.UNREAD)
        advanceTimeBy(100)
        bar.toggle(SearchToggle.FLAGGED)
        advanceTimeBy(100)
        bar.toggle(SearchToggle.ATTACHMENT)
        advanceTimeBy(SERVER_SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("no server run inside the pause after the last chip", emptyList<SearchQuery>(), asked)

        advanceUntilIdle()
        assertEquals(listOf(SearchQuery(unread = true, flagged = true, hasAttachment = true)), asked)
    }

    @Test fun `a chip and a keystroke share the same pause`() = runTest {
        val asked = mutableListOf<SearchQuery>()
        val bar = bar(server = { asked += it; MailSearchResult(emptyList()) })

        bar.ask(SearchQuery(text = "inv"), AddressNames())
        advanceTimeBy(200)
        bar.toggle(SearchToggle.UNREAD)
        advanceTimeBy(200)
        bar.remove(SearchField.FROM)
        advanceUntilIdle()
        assertEquals(listOf(SearchQuery(text = "inv", unread = true)), asked)
    }

    @Test fun `a picked name stays beside the criteria, never reaches a leg, and leaves with its chip`() = runTest {
        val asked = mutableListOf<SearchQuery>()
        val bar = bar(local = { asked += it; emptyList() }, server = { asked += it; MailSearchResult(emptyList()) })
        val names = AddressNames(from = PickedName("j.dupont@exemple.org", "Jeanne Dupont"))

        bar.ask(SearchQuery(from = "j.dupont@exemple.org"), names)
        advanceUntilIdle()
        assertEquals(listOf(SearchQuery(from = "j.dupont@exemple.org"), SearchQuery(from = "j.dupont@exemple.org")), asked)
        assertEquals(names, state.value.names)

        bar.toggle(SearchToggle.UNREAD)
        assertEquals("a chip keeps the name", names, state.value.names)
        bar.remove(SearchField.FROM)
        assertEquals(AddressNames(), state.value.names)
        advanceUntilIdle()
    }

    @Test fun `the index answers at once, before the pause`() = runTest {
        val bar = bar(local = { listOf(email("local")) })
        bar.toggle(SearchToggle.FLAGGED)
        runCurrent()
        assertEquals(listOf("local"), ids())
        assertTrue(state.value.loading)
    }

    // ---- M1: an answer is written only under the criteria it was asked for ----

    @Test fun `a server answer for the criteria before a chip is thrown away`() = runTest {
        val gate = Gate(MailSearchResult(emptyList()))
        val bar = bar(server = gate::hold)
        try {
            bar.ask(SearchQuery(text = "invoice"), AddressNames())
            advanceUntilIdle()
            bar.toggle(SearchToggle.UNREAD)
            runCurrent()
            gate.answer(SearchQuery(text = "invoice"), MailSearchResult(listOf(email("read-one"))))
            runCurrent()
            assertEquals(
                "the answer for 'invoice' alone must not land under 'invoice' + Unread",
                emptyList<String>(),
                ids(),
            )

            advanceUntilIdle()
            gate.answer(SearchQuery(text = "invoice", unread = true), MailSearchResult(listOf(email("unread-one"))))
            runCurrent()
            assertEquals(listOf("unread-one"), ids())
            assertFalse(state.value.loading)
        } finally {
            drain(gate)
        }
    }

    @Test fun `an index answer for the criteria before a chip is thrown away`() = runTest {
        val gate = Gate(emptyList<Email>())
        val bar = bar(local = gate::hold)
        try {
            bar.ask(SearchQuery(text = "invoice"), AddressNames())
            runCurrent()
            bar.toggle(SearchToggle.ATTACHMENT)
            runCurrent()
            gate.answer(SearchQuery(text = "invoice"), listOf(email("no-attachment")))
            runCurrent()
            assertEquals(null, ids())
            assertTrue(state.value.criteria.hasAttachment)
        } finally {
            drain(gate)
        }
    }

    @Test fun `the crawl's refresh does not merge an answer for criteria since changed`() = runTest {
        val gate = Gate(emptyList<Email>())
        val bar = bar(local = gate::hold)
        state.value = SearchUi(active = true, criteria = SearchQuery(text = "invoice"), results = emptyList())
        try {
            launch { bar.refreshLocal() }
            runCurrent()
            bar.toggle(SearchToggle.UNREAD)
            runCurrent()
            gate.answer(SearchQuery(text = "invoice"), listOf(email("read-one")))
            runCurrent()
            assertFalse(ids().orEmpty().contains("read-one"))
        } finally {
            drain(gate)
        }
    }

    // ---- M2: To or Cc leaves the index out ----

    @Test fun `with To or Cc set the index is not asked, and the answer waits for the server`() = runTest {
        val localAsked = mutableListOf<SearchQuery>()
        val bar = bar(
            local = { localAsked += it; listOf(email("sent-to-anyone")) },
            server = { MailSearchResult(listOf(email("sent-to-sam"))) },
        )

        bar.ask(SearchQuery(recipient = "sam@example.org"), AddressNames())
        runCurrent()
        assertEquals(emptyList<SearchQuery>(), localAsked)
        assertEquals(emptyList<String>(), ids())
        assertTrue(state.value.loading)
        assertFalse("nothing is proven before the server answers", state.value.complete)

        advanceUntilIdle()
        assertEquals(listOf("sent-to-sam"), ids())
        assertTrue(state.value.complete)
        assertFalse(state.value.loading)
    }

    @Test fun `offline, a To or Cc search that found nothing says it stopped short, never that nothing matched`() = runTest {
        val bar = bar(local = { listOf(email("sent-to-anyone")) }, server = { error("offline") })
        val criteria = SearchQuery(text = "invoice", recipient = "sam@example.org")

        bar.ask(criteria, AddressNames())
        advanceUntilIdle()
        assertEquals(emptyList<String>(), ids())
        assertEquals(
            SearchDisplay.INCOMPLETE_EMPTY,
            searchDisplay(0, state.value.loading, state.value.complete),
        )
        assertFalse(localLegAnswers(criteria))
    }

    @Test fun `the crawl's refresh leaves the index out too while To or Cc is set`() = runTest {
        val localAsked = mutableListOf<SearchQuery>()
        val bar = bar(local = { localAsked += it; listOf(email("sent-to-anyone")) })
        state.value = SearchUi(active = true, criteria = SearchQuery(text = "x", recipient = "sam@example.org"), results = emptyList())

        bar.refreshLocal()
        assertEquals(emptyList<SearchQuery>(), localAsked)
        assertEquals(emptyList<String>(), ids())
    }

    @Test fun `without To or Cc the index is asked the whole criteria`() = runTest {
        val localAsked = mutableListOf<SearchQuery>()
        val bar = bar(local = { localAsked += it; emptyList() })
        val criteria = SearchQuery(text = "invoice", from = "alex", unread = true, afterMillis = 5L)

        bar.ask(criteria, AddressNames())
        advanceUntilIdle()
        assertEquals(listOf(criteria), localAsked)
        assertTrue(localLegAnswers(criteria))
        assertFalse(localLegAnswers(criteria.copy(recipient = "sam@example.org")))
    }

    // ---- the rest of the contract ----

    @Test fun `a search asking nothing clears the results and runs no leg`() = runTest {
        val asked = mutableListOf<SearchQuery>()
        val bar = bar(local = { asked += it; emptyList() }, server = { asked += it; MailSearchResult(emptyList()) })
        state.value = SearchUi(active = true, criteria = SearchQuery(unread = true), results = listOf(email("old")))

        bar.toggle(SearchToggle.UNREAD)
        advanceUntilIdle()
        assertEquals(null, ids())
        assertEquals(emptyList<SearchQuery>(), asked)
    }

    @Test fun `a failed index never passes for a total, a good server answer notwithstanding`() = runTest {
        val bar = bar(local = { error("locked") }, server = { MailSearchResult(listOf(email("s"))) })
        bar.ask(SearchQuery(text = "invoice"), AddressNames())
        advanceUntilIdle()
        assertEquals(listOf("s"), ids())
        assertFalse(state.value.complete)
    }

    // ---- the scope chip ----

    @Test fun `the comparison of an answer takes in the scope, not the criteria alone`() {
        val invoice = SearchQuery(text = "invoice")
        val onScreen = SearchUi(active = true, criteria = invoice, scope = SearchScope.THIS_ACCOUNT)
        assertTrue(answersCurrent(onScreen, invoice, SearchScope.THIS_ACCOUNT))
        assertFalse(
            "an answer for every account must not land under 'This account'",
            answersCurrent(onScreen, invoice, SearchScope.ALL_ACCOUNTS),
        )
        assertFalse(answersCurrent(onScreen, SearchQuery(text = "invoices"), SearchScope.THIS_ACCOUNT))
    }

    @Test fun `a server answer for the scope before a tap on the chip is thrown away`() = runTest {
        val held = mutableListOf<Pair<SearchScope, Continuation<MailSearchResult>>>()
        val bar = scopedBar(server = { _, scope -> suspendCoroutine { held += scope to it } })
        state.value = SearchUi(active = true, scope = SearchScope.ALL_ACCOUNTS)
        try {
            bar.ask(SearchQuery(text = "invoice"), AddressNames())
            advanceUntilIdle()
            bar.scope(SearchScope.THIS_ACCOUNT)
            runCurrent()
            held.removeAt(held.indexOfFirst { it.first == SearchScope.ALL_ACCOUNTS }).second
                .resume(MailSearchResult(listOf(email("other-account"))))
            runCurrent()
            assertEquals(
                "every account's answer must not land under 'This account'",
                emptyList<String>(),
                ids(),
            )

            advanceUntilIdle()
            held.removeAt(held.indexOfFirst { it.first == SearchScope.THIS_ACCOUNT }).second
                .resume(MailSearchResult(listOf(email("this-account"))))
            runCurrent()
            assertEquals(listOf("this-account"), ids())
        } finally {
            while (held.isNotEmpty()) {
                held.removeAt(0).second.resume(MailSearchResult(emptyList()))
                advanceUntilIdle()
            }
        }
    }

    @Test fun `a tap on the chip asks the index at once and the server after the pause, under the new scope`() = runTest {
        val localAsked = mutableListOf<SearchScope>()
        val serverAsked = mutableListOf<SearchScope>()
        val bar = scopedBar(
            local = { _, scope -> localAsked += scope; emptyList() },
            server = { _, scope -> serverAsked += scope; MailSearchResult(emptyList()) },
        )
        state.value = SearchUi(active = true, criteria = SearchQuery(text = "invoice"), scope = SearchScope.THIS_ACCOUNT)

        bar.scope(SearchScope.ALL_ACCOUNTS)
        runCurrent()
        assertEquals(SearchScope.ALL_ACCOUNTS, state.value.scope)
        assertEquals(listOf(SearchScope.ALL_ACCOUNTS), localAsked)
        advanceTimeBy(SERVER_SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals("no server run inside the pause", emptyList<SearchScope>(), serverAsked)

        advanceUntilIdle()
        assertEquals(listOf(SearchScope.ALL_ACCOUNTS), serverAsked)
    }

    @Test fun `a chip after the scope keeps the scope`() = runTest {
        val serverAsked = mutableListOf<Pair<SearchQuery, SearchScope>>()
        val bar = scopedBar(server = { q, scope -> serverAsked += q to scope; MailSearchResult(emptyList()) })
        state.value = SearchUi(active = true, criteria = SearchQuery(text = "invoice"))

        bar.scope(SearchScope.ALL_ACCOUNTS)
        bar.toggle(SearchToggle.UNREAD)
        advanceUntilIdle()
        assertEquals(listOf(SearchQuery(text = "invoice", unread = true) to SearchScope.ALL_ACCOUNTS), serverAsked)
    }

    // ---- Retry under a search that stopped short ----

    @Test fun `Retry runs the server leg again and leaves the index alone`() = runTest {
        val localAsked = mutableListOf<SearchQuery>()
        val serverAsked = mutableListOf<SearchQuery>()
        val bar = bar(
            local = { localAsked += it; emptyList() },
            server = {
                serverAsked += it
                if (serverAsked.size == 1) error("offline") else MailSearchResult(listOf(email("on-server")))
            },
        )
        val criteria = SearchQuery(text = "invoice")
        bar.ask(criteria, AddressNames())
        advanceUntilIdle()
        assertEquals(SearchDisplay.INCOMPLETE_EMPTY, searchDisplay(ids()!!.size, state.value.loading, state.value.complete))

        bar.retryServer()
        assertTrue("Retry shows the search running again", state.value.loading)
        advanceUntilIdle()

        assertEquals("the index is not asked again", listOf(criteria), localAsked)
        assertEquals(listOf(criteria, criteria), serverAsked)
        assertEquals(listOf("on-server"), ids())
        assertTrue(state.value.complete)
        assertFalse(state.value.loading)
    }

    @Test fun `Retry keeps the index's hits and its failure on the count`() = runTest {
        val localAsked = mutableListOf<SearchQuery>()
        var serverRuns = 0
        val bar = bar(
            local = { localAsked += it; error("locked") },
            server = { serverRuns++; if (serverRuns == 1) error("offline") else MailSearchResult(emptyList()) },
        )
        bar.ask(SearchQuery(text = "invoice"), AddressNames())
        advanceUntilIdle()
        state.value = state.value.copy(results = listOf(email("kept")))

        bar.retryServer()
        advanceUntilIdle()
        assertEquals(1, localAsked.size)
        assertEquals("the rows on screen are not reset by a Retry", listOf("kept"), ids())
        assertFalse("a failed index still keeps the count at 'at least'", state.value.complete)
    }

    @Test fun `Retry under every account answers under every account`() = runTest {
        val serverAsked = mutableListOf<SearchScope>()
        val bar = scopedBar(server = { _, scope ->
            serverAsked += scope
            if (serverAsked.size == 1) error("offline") else MailSearchResult(listOf(email("elsewhere")))
        })
        state.value = SearchUi(active = true, scope = SearchScope.ALL_ACCOUNTS)
        bar.ask(SearchQuery(text = "invoice"), AddressNames())
        advanceUntilIdle()

        bar.retryServer()
        advanceUntilIdle()
        assertEquals(listOf(SearchScope.ALL_ACCOUNTS, SearchScope.ALL_ACCOUNTS), serverAsked)
        assertFalse("the answer must land, or the spinner turns for ever", state.value.loading)
        assertEquals(listOf("elsewhere"), ids())
    }

    @Test fun `Retry cancels a server run still in flight, so only one answer is written`() = runTest {
        var runs = 0
        val finished = mutableListOf<Int>()
        val bar = bar(server = {
            val run = ++runs
            kotlinx.coroutines.delay(1_000)
            finished += run
            MailSearchResult(listOf(email("run-$run")))
        })
        bar.ask(SearchQuery(text = "invoice"), AddressNames())
        advanceTimeBy(SERVER_SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, runs)

        bar.retryServer()
        advanceUntilIdle()
        assertEquals("the first server run must have been cancelled", listOf(2), finished)
        assertEquals(listOf("run-2"), ids())
    }

    @Test fun `the empty state's Retry is wired to the server leg alone`() {
        val screen = java.io.File(root(), "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt").readText()
        val incomplete = screen.substring(screen.indexOf("SearchDisplay.INCOMPLETE_EMPTY -> EmptyState("), screen.indexOf("SearchDisplay.EMPTY -> EmptyState("))
        assertTrue(incomplete.contains("Button(onClick = viewModel::retrySearchServer) { Text(stringResource(R.string.inbox_retry)) }"))
        val vm = java.io.File(root(), "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt").readText()
        assertTrue(vm.contains("fun retrySearchServer() = barSearch.retryServer()"))
    }

    private fun root(): java.io.File =
        generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "app/src/main").isDirectory }
}

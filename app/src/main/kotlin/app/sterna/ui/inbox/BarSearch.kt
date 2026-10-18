package app.sterna.ui.inbox

import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.search.AddressNames
import app.sterna.ui.search.SearchField
import app.sterna.ui.search.SearchToggle
import app.sterna.ui.search.excerptsByResultKey
import app.sterna.ui.search.forCriteria
import app.sterna.ui.search.keptFor
import app.sterna.ui.search.searchComplete
import app.sterna.ui.search.toggled
import app.sterna.ui.search.without
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

internal data class SearchUi(
    val active: Boolean = false,
    /** What the search asks: the words of the bar, the chips under it, the fields of the sheet. */
    val criteria: SearchQuery = SearchQuery(),
    val scope: SearchScope = SearchScope.THIS_ACCOUNT,
    /** What the address criteria show; never asked of a leg. */
    val names: AddressNames = AddressNames(),
    val results: List<Email>? = null,
    val loading: Boolean = false,
    /** False when a leg stopped short, the server's cap, an account that failed and was dropped,
     *  or a local leg that could not answer. The count then says "at least N" (see
     *  [app.sterna.core.data.mail.MailSearchResult]). */
    val complete: Boolean = true,
    val excerpts: Map<String, String> = emptyMap(),
    val typing: Boolean = false,
) {
    /** The words typed in the bar, the only part the history keeps. */
    val query: String get() = criteria.text
}

/** Pause after the last change of the criteria before the (unioned-in) server search fires. */
internal const val SERVER_SEARCH_DEBOUNCE_MS = 350L

/**
 * Whether an answer computed for [asked] over [askedScope] may still be written: the criteria and
 * the scope on screen are the same, whole. Comparing the words alone lets an answer for "invoice"
 * land under "invoice" + Unread, rows the chip was tapped to take away; leaving the scope out lets
 * every account's answer land under "This account".
 */
internal fun answersCurrent(current: SearchUi, asked: SearchQuery, askedScope: SearchScope): Boolean =
    current.criteria == asked && current.scope == askedScope

/**
 * Whether the local index can answer [criteria]. It holds no recipients, so with To or Cc set its
 * hits would ignore that criterion and show mail the search excludes: the leg is skipped, and the
 * answer is left to the server.
 */
internal fun localLegAnswers(criteria: SearchQuery): Boolean = criteria.recipient.isBlank()

/**
 * The search bar's two legs over [state]: the local index at once, the server after
 * [SERVER_SEARCH_DEBOUNCE_MS], unioned in. Every change of the criteria, a keystroke, a chip, the scope, the
 * sheet's Apply, a picked history line, goes through [ask], so a burst of chips costs one server
 * run, the one for the last of them. A leg that failed or stopped short never passes for a total.
 */
internal class BarSearch(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<SearchUi>,
    /** Rows the user removed from the results: the index and the server outlive them. */
    private val removed: RemovedRows,
    /** The index's first fill, which the first local leg waits for. */
    private val indexReady: suspend () -> Unit,
    private val local: suspend (SearchQuery, SearchScope) -> List<Email>,
    private val server: suspend (SearchQuery, SearchScope) -> MailSearchResult,
) {
    private var job: Job? = null

    /**
     * Whether the local leg answered for the criteria on screen, set by [ask] only. [retryServer]
     * reuses it rather than run the index again: Retry is offered for the server's failure, and a
     * fresh local run would redo work that already answered and could reshuffle the rows on screen.
     */
    private var localLegComplete = true

    fun cancel() {
        job?.cancel()
    }

    fun toggle(toggle: SearchToggle) = ask(state.value.criteria.toggled(toggle), state.value.names)

    fun remove(field: SearchField) = ask(state.value.criteria.without(field), state.value.names)

    fun scope(within: SearchScope) = ask(state.value.criteria, state.value.names, within)

    /** [names] has no default: a caller that forgot it would show addresses again, or names of a search before. */
    fun ask(criteria: SearchQuery, names: AddressNames, within: SearchScope = state.value.scope) {
        job?.cancel()
        val kept = names.keptFor(criteria)
        if (criteria.isEmpty()) {
            state.value = state.value.forCriteria(criteria).copy(scope = within, names = kept, results = null, loading = false)
            return
        }
        state.value = state.value.forCriteria(criteria).copy(scope = within, names = kept, loading = true, complete = true)
        job = scope.launch {
            indexReady()
            val localRuns = localLegAnswers(criteria)
            // A failed local index is a leg of the answer that didn't run, like a failed server
            // leg below: a locked or damaged FTS table (the ground of #71) must not leave a count
            // calling itself a total.
            var localComplete = true
            val hits = if (localRuns) {
                runCatching { local(criteria, within) }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    localComplete = false
                    emptyList()
                }
            } else {
                emptyList()
            }
            if (!answersCurrent(state.value, criteria, within)) return@launch
            state.value = state.value.copy(
                results = hits.filterNot { it.emailKey() in removed },
                loading = true,
                // A skipped leg has proven nothing: until the server answers, "at least".
                complete = localComplete && localRuns,
            )
            localLegComplete = localComplete
            delay(SERVER_SEARCH_DEBOUNCE_MS)
            serverLeg(criteria, within)
        }
    }

    /**
     * The server leg alone, again, for the criteria on screen: what "Retry" under a search that
     * stopped short asks. The local leg is not run again, its hits stay as they are, and its own
     * verdict ([localLegComplete]) still counts, so a failed index keeps the count at "at least".
     */
    fun retryServer() {
        val criteria = state.value.criteria
        val within = state.value.scope
        if (criteria.isEmpty()) return
        job?.cancel()
        state.value = state.value.copy(loading = true)
        job = scope.launch { serverLeg(criteria, within) }
    }

    private suspend fun serverLeg(criteria: SearchQuery, within: SearchScope) {
        val found = runCatching { server(criteria, within) }.getOrElse { error ->
            if (error is CancellationException) throw error
            MailSearchResult(emptyList(), complete = false)
        }
        if (answersCurrent(state.value, criteria, within)) {
            state.value = state.value.copy(
                // The local leg may have shown a row the server just proved gone and purged.
                results = mergeHits(state.value.results.orEmpty(), found.emails)
                    .filterNot { it.emailKey() in found.purged },
                loading = false,
                complete = searchComplete(local = localLegComplete, server = found.complete),
                excerpts = excerptsByResultKey(found),
            )
        }
    }

    /** The local leg again, over an index the crawl just grew: merged in, never replacing. */
    suspend fun refreshLocal() {
        val criteria = state.value.criteria
        val within = state.value.scope
        if (criteria.isEmpty() || !localLegAnswers(criteria)) return
        val hits = runCatching { local(criteria, within) }.getOrNull() ?: return
        if (answersCurrent(state.value, criteria, within)) {
            state.value = state.value.copy(results = mergeHits(state.value.results.orEmpty(), hits))
        }
    }

    /** Union of two hit lists (by account+id), newest first. Rows the user just removed stay out:
     *  the FTS index and the server both still know an evicted message (see [removed]). */
    private fun mergeHits(a: List<Email>, b: List<Email>): List<Email> =
        (a + b).distinctBy { it.accountId to it.id }
            .filterNot { it.emailKey() in removed }
            // receivedAt is an ISO-8601 UTC string, so lexicographic sort == chronological.
            .sortedByDescending { it.receivedAt ?: "" }
}

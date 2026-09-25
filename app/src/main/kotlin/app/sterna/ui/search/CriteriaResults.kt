package app.sterna.ui.search

import androidx.lifecycle.ViewModel
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Email
import app.sterna.ui.inbox.RemovedRows
import app.sterna.ui.inbox.RowTap
import app.sterna.ui.inbox.ShownResults
import app.sterna.ui.inbox.patchedRows
import app.sterna.ui.inbox.rowTapAction
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The advanced search's results as the inbox's selection writes them: [SearchState.Results] is a
 * snapshot, so a row an action removes is taken out of it here, with the passage that matched it,
 * and put back, passage included, on failure or Undo.
 *
 * Tied to [owner] at construction rather than by calls its ViewModel could forget: cleared, the
 * owner closes it; and a snapshot this class did not write is a new answer, whose rows the
 * previous one's removals have no position in.
 */
class CriteriaResults internal constructor(
    private val state: MutableStateFlow<SearchState>,
    owner: ViewModel,
) : ShownResults, AutoCloseable {
    private val removed = RemovedRows()
    private val removedExcerpts = mutableMapOf<String, String>()
    private var whenGone: (() -> Unit)? = null
    private var answer: SearchState.Results? = null

    init {
        owner.addCloseable(this)
    }

    override val rows: List<Email>?
        get() = (state.value as? SearchState.Results)?.emails

    private fun current(): SearchState.Results? {
        val results = state.value as? SearchState.Results ?: return null
        if (results !== answer) {
            removed.clear()
            removedExcerpts.clear()
            answer = results
        }
        return results
    }

    private fun write(results: SearchState.Results) {
        answer = results
        state.value = results
    }

    override fun drop(keys: Set<EmailKey>) {
        val results = current() ?: return
        val remaining = removed.drop(results.emails, keys) ?: return
        val gone = keys.mapTo(HashSet()) { searchResultKey(it.accountId, it.emailId) }
        results.excerpts.filterKeys { it in gone }.forEach { (key, excerpt) -> removedExcerpts[key] = excerpt }
        write(results.copy(emails = remaining, excerpts = results.excerpts - gone))
    }

    override fun restore(keys: Collection<EmailKey>) {
        val results = current() ?: return
        val restored = removed.restore(results.emails, keys) ?: return
        val back = keys.mapNotNull { key ->
            val resultKey = searchResultKey(key.accountId, key.emailId)
            removedExcerpts.remove(resultKey)?.let { resultKey to it }
        }
        write(results.copy(emails = restored, excerpts = results.excerpts + back))
    }

    override fun patch(keys: Set<EmailKey>, transform: (Email) -> Email) {
        val results = current() ?: return
        val patched = patchedRows(results.emails, keys, transform) ?: return
        write(results.copy(emails = patched))
    }

    /** One listener, the inbox that holds the selection: this screen's rows die with it. */
    fun whenGone(action: () -> Unit) {
        whenGone = action
    }

    override fun close() {
        whenGone?.invoke()
        whenGone = null
    }
}

/**
 * What a tap on an advanced-search result does: the list's own decision, the selection first, then
 * the hit's own folder ([hitRole]), so outside a selection a hit filed in Drafts still opens the
 * composer (#216). A result is always one message here, never a folded conversation.
 */
internal fun criteriaHitTap(localDraft: Boolean, selecting: Boolean, hitRole: String?): RowTap =
    rowTapAction(
        localDraft = localDraft,
        selectionActive = selecting,
        expandable = false,
        fromSearch = true,
        rowRole = null,
        hitRole = hitRole,
    )

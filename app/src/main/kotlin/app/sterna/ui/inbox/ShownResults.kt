package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A list of results drawn from a frozen snapshot rather than from the cache: nothing refreshes it,
 * so every removal an action makes has to be written into it, and taken back on failure or Undo,
 * or the row stays on screen until the list is left.
 */
interface ShownResults {
    /** The rows drawn, null when the list shows no results at all. */
    val rows: List<Email>?
    fun drop(keys: Set<EmailKey>)
    fun restore(keys: Collection<EmailKey>)
    fun patch(keys: Set<EmailKey>, transform: (Email) -> Email)
}

/**
 * Rows an action removed from a snapshot, with their position, so a restore puts them back where
 * they were. Doubles as a tombstone set: an index or a server outlives an evicted row, and a later
 * merge must not resurrect what the user just removed. Account-qualified [EmailKey] (#31).
 */
internal class RemovedRows {
    private val removed = mutableMapOf<EmailKey, IndexedValue<Email>>()

    operator fun contains(key: EmailKey): Boolean = key in removed

    fun clear() = removed.clear()

    /** [rows] less [keys], each one stashed; null when none of them was drawn. */
    fun drop(rows: List<Email>?, keys: Set<EmailKey>): List<Email>? {
        if (rows == null) return null
        val remaining = ArrayList<Email>(rows.size)
        rows.forEachIndexed { index, email ->
            if (email.emailKey() in keys) removed[email.emailKey()] = IndexedValue(index, email)
            else remaining += email
        }
        return remaining.takeIf { it.size != rows.size }
    }

    /**
     * [rows] with the stashed entries among [keys] put back at (best-effort) their original
     * position; null when there is nothing to put back. The stash is emptied of [keys] even when
     * [rows] is null. A key already drawn is not inserted a second time: a duplicate key in a
     * lazy list throws (#92).
     */
    fun restore(rows: List<Email>?, keys: Collection<EmailKey>): List<Email>? {
        val entries = keys.mapNotNull { removed.remove(it) }.sortedBy { it.index }
        if (entries.isEmpty() || rows == null) return null
        val drawn = rows.mapTo(HashSet()) { it.emailKey() }
        val restored = rows.toMutableList()
        entries.filterNot { it.value.emailKey() in drawn }
            .forEach { (index, email) -> restored.add(index.coerceAtMost(restored.size), email) }
        return restored
    }
}

/** [rows] with the rows among [keys] rewritten (read state, star); null when none is drawn. */
internal fun patchedRows(rows: List<Email>?, keys: Set<EmailKey>, transform: (Email) -> Email): List<Email>? {
    if (rows == null || rows.none { it.emailKey() in keys }) return null
    return rows.map { if (it.emailKey() in keys) transform(it) else it }
}

/** Which list a selection was ticked on: the inbox's own (its search bar included), or the advanced search screen's. */
enum class SelectionOrigin { LIST, CRITERIA }

/**
 * Whether the selection is drawn on the screen showing [screen]'s list. Both screens share one
 * selection, and a selection ticked on one must never show, nor be acted on, from the other.
 */
internal fun selectionShownOn(active: Boolean, origin: SelectionOrigin, screen: SelectionOrigin): Boolean =
    active && origin == screen

/**
 * The snapshots a selection reads its rows from and writes its removals into: [list], the inbox's
 * search bar, always, and the advanced search's results while that screen exists.
 *
 * Removals are written into every snapshot, whatever the origin: a message moved away is gone from
 * both lists, and a batch still in flight when the other screen takes over must still land.
 */
internal class SelectionSources(private val list: ShownResults) {
    private val _origin = MutableStateFlow(SelectionOrigin.LIST)
    val origin: StateFlow<SelectionOrigin> = _origin.asStateFlow()

    private var criteria: ShownResults? = null

    fun attach(results: ShownResults) {
        criteria = results
    }

    /**
     * The advanced search screen is gone for good. True when its selection must be cleared: it
     * was the one attached and the selection was ticked there. The origin goes back to the list
     * either way, or the inbox would keep hiding its own selection.
     */
    fun detach(results: ShownResults): Boolean {
        if (criteria !== results) return false
        criteria = null
        val wasShown = _origin.value == SelectionOrigin.CRITERIA
        _origin.value = SelectionOrigin.LIST
        return wasShown
    }

    /** Set before the first tick is written, so the collectors of the keys read the right rows. */
    fun startOn(origin: SelectionOrigin) {
        _origin.value = origin
    }

    /**
     * The rows the selection was ticked on, `displayed` for [resolveSelectionTargets]: a result
     * with no row in the cache resolves only from here, as an untrusted row.
     */
    fun displayed(): List<Email>? = when (_origin.value) {
        SelectionOrigin.LIST -> list.rows
        SelectionOrigin.CRITERIA -> criteria?.rows
    }

    /**
     * "Select all" on the advanced search: the rows it draws, and nothing else. Never the folder
     * behind the inbox, and never more than drawn when the screen says "at least N": what was
     * not drawn was never seen. A draft only this phone holds is not selectable ([isLocalDraftRow]).
     */
    fun shownKeys(): Set<EmailKey> =
        criteria?.rows.orEmpty()
            .filterNot { isLocalDraftRow(it.id) }
            .mapTo(LinkedHashSet()) { it.emailKey() }

    /** Taken at the tap of a gesture that may hand part of its selection back when it ends. */
    fun seat(): SelectionSeat = SelectionSeat(_origin.value, criteria)

    /**
     * Whether a selection handed back at the end of a gesture may be ticked again: only on the
     * screen that gesture was tapped on, still attached. Anywhere else the rows are not drawn, and
     * the next "Delete" would act on messages nobody sees ticked.
     */
    fun stillOn(seat: SelectionSeat): Boolean =
        seat.origin == _origin.value && (seat.origin == SelectionOrigin.LIST || seat.criteria === criteria)

    /**
     * One write over rows the snapshots may draw, as `bulkBatched` does it: [leaving] out of every
     * snapshot before [write], and back in for each key [write] does not answer as written.
     */
    suspend fun writingOut(leaving: Set<EmailKey>, write: suspend () -> Collection<EmailKey>) {
        drop(leaving)
        val written = write().toSet()
        restore(leaving - written)
    }

    fun drop(keys: Set<EmailKey>) {
        list.drop(keys)
        criteria?.drop(keys)
    }

    fun restore(keys: Collection<EmailKey>) {
        list.restore(keys)
        criteria?.restore(keys)
    }

    fun patch(keys: Set<EmailKey>, transform: (Email) -> Email) {
        list.patch(keys, transform)
        criteria?.patch(keys, transform)
    }
}

/** Which screen a gesture's selection was ticked on, and which instance of it. */
internal class SelectionSeat(val origin: SelectionOrigin, val criteria: ShownResults?)

package app.sterna.ui.inbox

import androidx.paging.PagingData
import androidx.paging.filter
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.emailKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * The rows a swipe took off the list that the list has not caught up with yet. The repository
 * evicts the row at the finger, but the list only loses it once Room has invalidated, a new
 * generation has been paged and diffed: a blank band where the row flew off, half a second on a
 * phone. The mask hides the row from the generation still on screen meanwhile.
 *
 * Immutable, and every transition that changes nothing returns the same instance: the list writes
 * back each generation it receives, and an unequal copy would re-emit the state into itself.
 */
internal data class SwipeMask(
    private val holds: Map<EmailKey, Hold> = emptyMap(),
    private val generation: Any? = null,
) {
    /** Why a key is held. */
    sealed interface Hold

    /** The eviction has not returned: every generation may still hold the row. */
    object Evicting : Hold

    /** Evicted while [seen] was the generation on screen. Compared by identity, see [received]. */
    class Evicted(val seen: Any?) : Hold {
        override fun equals(other: Any?) = other is Evicted && other.seen === seen
        override fun hashCode() = System.identityHashCode(seen)
    }

    val hiddenKeys: Set<EmailKey> get() = holds.keys

    /** At the gesture, before anything suspends. */
    fun posed(key: EmailKey): SwipeMask = SwipeMask(holds + (key to Evicting), generation)

    /** `beginRemoval` has returned. Nothing [evicted] means no generation is coming that could
     *  lift the key, so it goes now. A key no longer held was lifted meanwhile and stays lifted. */
    fun removalBegun(key: EmailKey, evicted: Boolean): SwipeMask = when {
        holds[key] != Evicting -> this
        !evicted -> lifted(listOf(key))
        else -> SwipeMask(holds + (key to Evicted(generation)), generation)
    }

    /**
     * The list was handed [generation]. It lifts every key evicted under another generation, on the
     * assumption that a generation the list receives later was loaded later, so read Room without
     * the row; while the write is out `sparingInFlight` keeps a sync page from writing it back.
     * The assumption holds while the list is what starts the load. A generation the reader's
     * direct collector of `pagedEmails` loaded before the eviction, reaching the list after it,
     * shows the row for as long as it is on screen. Distinct by identity, not counted: a
     * re-subscription replays the same instance out of `cachedIn`, read before the eviction.
     */
    fun received(generation: Any): SwipeMask {
        val kept = holds.filterValues { it !is Evicted || it.seen === generation }
        return if (generation === this.generation && kept.size == holds.size) this else SwipeMask(kept, generation)
    }

    /** A row put back is lifted after `endRemoval` has restored it, not before: unmasked first, it
     *  shows from the generation still on screen, goes with the one that evicted it, and returns. */
    fun lifted(keys: Collection<EmailKey>): SwipeMask =
        if (keys.none { it in holds }) this else SwipeMask(holds - keys.toSet(), generation)
}

/** A list generation and the keys the mask hides from it. */
internal data class MaskedGeneration<G : Any>(val generation: G, val hidden: Set<EmailKey>)

/** Each generation with what [mask] hides from it, recording in [mask] that it was received. A
 *  change of mask re-filters the generation on screen; a generation that lifts keys is handed
 *  over once, not once masked and again when the lift it wrote comes back. */
internal fun <G : Any> maskedGenerations(generations: Flow<G>, mask: MutableStateFlow<SwipeMask>): Flow<MaskedGeneration<G>> =
    combine(generations, mask) { generation, held -> MaskedGeneration(generation, held.received(generation).hiddenKeys) }
        .onEach { shown -> mask.update { it.received(shown.generation) } }
        .distinctUntilChanged()

/** A conversation of several messages is never hidden: a swiped child can be its representative,
 *  and the others stay in the folder. */
internal fun hidesSwipedRow(row: InboxRow, hidden: Set<EmailKey>): Boolean =
    row.threadCount <= 1 && row.email.emailKey() in hidden

internal fun withoutSwipedRows(paged: PagingData<InboxRow>, hidden: Set<EmailKey>): PagingData<InboxRow> =
    paged.filter { row -> !hidesSwipedRow(row, hidden) }

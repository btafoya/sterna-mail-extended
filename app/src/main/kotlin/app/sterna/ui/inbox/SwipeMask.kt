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
    private val conversations: Map<ThreadKey, Hold> = emptyMap(),
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

    val hiddenThreads: Set<ThreadKey> get() = conversations.keys

    /** At the gesture, before anything suspends. */
    fun posed(key: EmailKey): SwipeMask = SwipeMask(holds + (key to Evicting), conversations, generation)

    /** A whole selection at the tap, in one state write: posed key by key, the list is handed a
     *  generation that hides the first row and still draws the tenth. */
    fun posed(keys: Collection<EmailKey>): SwipeMask =
        if (keys.isEmpty()) this else SwipeMask(holds + keys.associateWith { Evicting }, conversations, generation)

    /** A collapsed conversation at the finger, whole: a row that draws several messages is hidden
     *  by this and by nothing else, see [hidesSwipedRow]. */
    fun posedThread(key: ThreadKey): SwipeMask = SwipeMask(holds, conversations + (key to Evicting), generation)

    /** `beginRemoval` has returned. Nothing [evicted] means no generation is coming that could
     *  lift the key, so it goes now. A key no longer held was lifted meanwhile and stays lifted. */
    fun removalBegun(key: EmailKey, evicted: Boolean): SwipeMask = when {
        holds[key] != Evicting -> this
        !evicted -> lifted(listOf(key))
        else -> SwipeMask(holds + (key to Evicted(generation)), conversations, generation)
    }

    /** The flight that took a whole conversation is over. Nothing [evicted] means no generation is
     *  coming that could lift the veil, so the row goes back on the list now; otherwise the veil
     *  waits for the generation paged after the eviction, as an evicted key's mask does. */
    fun threadSettled(key: ThreadKey, evicted: Boolean): SwipeMask = when {
        conversations[key] != Evicting -> this
        !evicted -> SwipeMask(holds, conversations - key, generation)
        else -> SwipeMask(holds, conversations + (key to Evicted(generation)), generation)
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
        val keptThreads = conversations.filterValues { it !is Evicted || it.seen === generation }
        val unchanged = generation === this.generation &&
            kept.size == holds.size && keptThreads.size == conversations.size
        return if (unchanged) this else SwipeMask(kept, keptThreads, generation)
    }

    /** A row put back is lifted after `endRemoval` has restored it, not before: unmasked first, it
     *  shows from the generation still on screen, goes with the one that evicted it, and returns. */
    fun lifted(keys: Collection<EmailKey>): SwipeMask =
        if (keys.none { it in holds }) this else SwipeMask(holds - keys.toSet(), conversations, generation)
}

/** A list generation, the keys the mask hides from it and the conversations it hides whole. */
internal data class MaskedGeneration<G : Any>(
    val generation: G,
    val hidden: Set<EmailKey>,
    val hiddenThreads: Set<ThreadKey>,
)

/** Each generation with what [mask] hides from it, recording in [mask] that it was received. A
 *  change of mask re-filters the generation on screen; a generation that lifts keys is handed
 *  over once, not once masked and again when the lift it wrote comes back. */
internal fun <G : Any> maskedGenerations(generations: Flow<G>, mask: MutableStateFlow<SwipeMask>): Flow<MaskedGeneration<G>> =
    combine(generations, mask) { generation, held ->
        val shown = held.received(generation)
        MaskedGeneration(generation, shown.hiddenKeys, shown.hiddenThreads)
    }
        .onEach { shown -> mask.update { it.received(shown.generation) } }
        .distinctUntilChanged()

/**
 * A key hides a row only while the list draws that row as one message: a swiped member can be the
 * representative of a conversation drawn as several, and the others stay in the folder. What hides
 * such a row is a veil over the conversation itself ([SwipeMask.posedThread]).
 *
 * A gesture on a collapsed conversation poses both, and owes both: the swipe is offered on
 * [InboxRow.threadExpandable], which counts the thread account-wide, where this branches on
 * [InboxRow.threadCount], which counts the viewed folders' members only. A conversation whose other
 * messages sit outside those folders is swiped whole on a row this reads as one message.
 */
internal fun hidesSwipedRow(row: InboxRow, hidden: Set<EmailKey>, hiddenThreads: Set<ThreadKey>): Boolean =
    if (row.threadCount <= 1) row.email.emailKey() in hidden
    else ConversationExpansion.threadKey(row.email.accountId, row.email.threadId, row.email.id) in hiddenThreads

internal fun withoutSwipedRows(
    paged: PagingData<InboxRow>,
    hidden: Set<EmailKey>,
    hiddenThreads: Set<ThreadKey>,
): PagingData<InboxRow> =
    paged.filter { row -> !hidesSwipedRow(row, hidden, hiddenThreads) }

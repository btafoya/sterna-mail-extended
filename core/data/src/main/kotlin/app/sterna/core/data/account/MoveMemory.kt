package app.sterna.core.data.account

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext

/** One move chosen in a move-to-folder picker and confirmed by the server: into [mailboxId] of the
 *  account whose memory holds it, at [at], carrying the lower-cased sender addresses of the moved
 *  messages. One gesture is one event, however many messages it moved. */
@Serializable
data class MoveEvent(val mailboxId: String, val at: Long, val senders: Set<String> = emptySet())

/** One account's memory as read from disk. [accountId] is the account it was read for, so a picker
 *  listing another account can refuse it: mailbox ids repeat between accounts (#92). */
data class MoveHistory(val accountId: String?, val events: List<MoveEvent>) {
    companion object {
        val NONE = MoveHistory(null, emptyList())
    }
}

const val MOVE_MEMORY_MAX_EVENTS = 200
const val MOVE_MEMORY_MAX_SENDERS = 50

/** The event for a move into [mailboxId]: senders lower-cased, blanks dropped, duplicates merged
 *  before the first [MOVE_MEMORY_MAX_SENDERS] are kept. */
fun moveEventOf(mailboxId: String, at: Long, senders: Iterable<String>): MoveEvent = MoveEvent(
    mailboxId = mailboxId,
    at = at,
    senders = senders.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .take(MOVE_MEMORY_MAX_SENDERS)
        .toCollection(LinkedHashSet()),
)

/** [events] with [event] appended, the oldest dropped past [MOVE_MEMORY_MAX_EVENTS]. */
fun withMoveEvent(events: List<MoveEvent>, event: MoveEvent): List<MoveEvent> =
    (events + event).takeLast(MOVE_MEMORY_MAX_EVENTS)

/** [events] less the last occurrence of [event], what an Undo takes back; unchanged when absent. */
fun withoutMoveEvent(events: List<MoveEvent>, event: MoveEvent): List<MoveEvent> {
    val at = events.lastIndexOf(event)
    if (at < 0) return events
    return events.toMutableList().apply { removeAt(at) }
}

/** Where the memory is kept: one value per account id. */
interface MoveMemoryFile {
    fun read(accountId: String): String?
    fun write(accountId: String, value: String?)
    fun accountIds(): Set<String>
}

/**
 * The per-account memory of moves chosen in the move-to-folder pickers. Local to the device, in a
 * file of its own: written into the accounts blob, every move would rewrite identities and tokens
 * and wake every collector of `AccountStore.accountsFlow`.
 */
class MoveMemory(
    private val file: MoveMemoryFile,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** The accounts read so far. An account absent from the map has not been read yet, which is not
     *  the same as an account read and found empty. */
    private val loaded = MutableStateFlow<Map<String, List<MoveEvent>>>(emptyMap())

    /** [accountId]'s memory, first emitted once read from disk. */
    fun history(accountId: String?): Flow<MoveHistory> {
        if (accountId == null) return flowOf(MoveHistory.NONE)
        return flow {
            withContext(io) { synchronized(this@MoveMemory) { eventsOf(accountId) } }
            emitAll(loaded.mapNotNull { all -> all[accountId]?.let { MoveHistory(accountId, it) } }.distinctUntilChanged())
        }
    }

    suspend fun record(accountId: String, event: MoveEvent) = change(accountId) { withMoveEvent(it, event) }

    suspend fun retract(accountId: String, event: MoveEvent) = change(accountId) { withoutMoveEvent(it, event) }

    /** Drop the memory of [accountIds]. Not suspending, for `AccountStore`'s removals. */
    fun forget(accountIds: Collection<String>) {
        if (accountIds.isEmpty()) return
        synchronized(this) {
            accountIds.forEach { file.write(it, null) }
            loaded.update { all -> all.mapValues { (id, events) -> if (id in accountIds) emptyList() else events } }
        }
    }

    /** The accounts that have a memory on disk, for the orphan sweep. */
    fun accountIds(): Set<String> = synchronized(this) { file.accountIds() }

    private suspend fun change(accountId: String, edit: (List<MoveEvent>) -> List<MoveEvent>) = withContext(io) {
        synchronized(this@MoveMemory) {
            val next = edit(eventsOf(accountId))
            file.write(accountId, json.encodeToString(next))
            loaded.update { it + (accountId to next) }
        }
    }

    /** Callers hold the monitor, so a [forget] cannot land between the read and the write. */
    private fun eventsOf(accountId: String): List<MoveEvent> {
        loaded.value[accountId]?.let { return it }
        val read = file.read(accountId)
            ?.let { runCatching { json.decodeFromString<List<MoveEvent>>(it) }.getOrNull() }
            .orEmpty()
        loaded.update { it + (accountId to read) }
        return read
    }

    companion object {
        @Volatile private var instance: MoveMemory? = null

        /** The one instance of the process: the pickers read what the moves write only if both
         *  hold the same [loaded]. */
        fun of(context: Context): MoveMemory = instance ?: synchronized(this) {
            instance ?: MoveMemory(MoveMemoryPrefs(context.applicationContext)).also { instance = it }
        }
    }
}

private class MoveMemoryPrefs(context: Context) : MoveMemoryFile {
    private val prefs = context.getSharedPreferences(MOVE_MEMORY_PREFS, Context.MODE_PRIVATE)

    override fun read(accountId: String): String? = prefs.getString(accountId, null)

    override fun write(accountId: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(accountId) else edit.putString(accountId, value)
        edit.apply()
    }

    override fun accountIds(): Set<String> = prefs.all.keys.toSet()
}

/** Also named in the backup exclusions: the file holds sender addresses. */
const val MOVE_MEMORY_PREFS = "move_memory"

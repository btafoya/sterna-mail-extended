package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.emailKey
import app.sterna.core.jmap.model.Email
import java.util.concurrent.ConcurrentHashMap

/**
 * What an Undo did to the identity of the messages it brought home, and the one place that knows.
 *
 * On IMAP the move back gives a message a new uid, so a new id: the key it was restored under names
 * nothing any more, in the cache or at the server. Three things still name it when the restore
 * returns: the search snapshots, which nothing refreshes, the ticks, and the keys of a gesture
 * tapped while the restore was out, which it keeps across its wait in [RestoresInFlight]. The
 * restore rewrites the first two itself; [rowsFor] answers the gesture with the rows its keys stand
 * for now. A key brought home without a row to show for it answers as unplaced and is never written.
 *
 * Only what the restore itself reported is recorded, never an id worked out from the folder: a uid
 * means something only under the numbering it was read in.
 *
 * A record has two readers. A gesture whose keys were taken before the restore returned, which
 * resumes as that job ends. And any gesture on a row left drawn under an unplaced key, for as long
 * as its snapshot is on screen. Neither outlives the snapshot, so [forget] is called when it is
 * opened or left. Until then a key the server hands to another message would be read as the old
 * one: a cached row under the key wins over the record, and a search hit with no cached row is
 * the case that guard does not cover.
 */
internal class RestoredKeys {

    private sealed interface Landing {
        data class At(val key: EmailKey) : Landing
        object Unknown : Landing
    }

    private val landings = ConcurrentHashMap<EmailKey, Landing>()

    /** Called by the restore before it ends, so a gesture waiting on it reads this once released. */
    fun record(landing: RestoredLanding) {
        landing.renumbered.forEach { (was, now) -> landings[was] = Landing.At(now) }
        landing.unplaced.forEach { landings[it] = Landing.Unknown }
    }

    /**
     * The rows [keys] stand for since an Undo renumbered them, read through [cached] under the keys
     * they carry now. A renumbered key whose row [cached] no longer answers for is unplaced too.
     * Costs no read when no key was renumbered. Asked after the gesture's wait, never before it:
     * a restore still out has recorded nothing, and its keys would resolve as if it had never been.
     */
    suspend fun rowsFor(keys: Collection<EmailKey>, cached: suspend (Set<EmailKey>) -> List<Email>): RestoredRows {
        val now = LinkedHashMap<EmailKey, EmailKey>()
        val unplaced = LinkedHashSet<EmailKey>()
        for (key in keys) {
            when (val landing = landingOf(key)) {
                null -> Unit
                is Landing.At -> now[key] = landing.key
                Landing.Unknown -> unplaced += key
            }
        }
        if (now.isEmpty() && unplaced.isEmpty()) return RestoredRows.NONE
        val rows: Map<EmailKey, Email> =
            if (now.isEmpty()) emptyMap() else cached(now.values.toSet()).associateBy { it.emailKey() }
        val placed = LinkedHashMap<EmailKey, Email>()
        now.forEach { (ticked, key) ->
            val row = rows[key]
            if (row != null) placed[ticked] = row else unplaced += ticked
        }
        return RestoredRows(placed, unplaced)
    }

    /**
     * The row a gesture handed [row] has to write. One answer for every gesture that carries a row
     * rather than ticks: act on [RowToWrite.Landed], take the failure path on [RowToWrite.Unwritable]
     * and send nothing. As [rowsFor], asked after the gesture's wait and never before it.
     */
    suspend fun rowToWrite(row: Email, cached: suspend (Set<EmailKey>) -> List<Email>): RowToWrite {
        val key = row.emailKey()
        val landing = landingOf(key) ?: return RowToWrite.AsHanded
        val home = (landing as? Landing.At)?.key
        val rows = cached(setOfNotNull(key, home)).associateBy { it.emailKey() }
        if (rows[key] != null) return RowToWrite.AsHanded
        val landed = home?.let { rows[it] } ?: return RowToWrite.Unwritable
        return RowToWrite.Landed(landed)
    }

    fun forget() = landings.clear()

    private fun landingOf(key: EmailKey): Landing? {
        var landing = landings[key] ?: return null
        // The Undo of a later gesture renumbers the same message again.
        repeat(landings.size) {
            val at = landing as? Landing.At ?: return landing
            landing = landings[at.key] ?: return landing
        }
        return landing
    }
}

/** What [RestoredKeys.rowToWrite] answers. [AsHanded]: no Undo renumbered the row, or the cache
 *  holds a row under its key. [Unwritable]: its message answers to an id nothing can name. */
internal sealed interface RowToWrite {
    object AsHanded : RowToWrite
    data class Landed(val row: Email) : RowToWrite
    object Unwritable : RowToWrite
}

/**
 * What [RestoredKeys.rowsFor] answers, and what [resolveSelectionTargets] does with it. [rows], by
 * the key each was ticked under, come from the cache and carry the id the message answers to now:
 * their folder is trusted. An [unplaced] key resolves to nothing, and the drawn row is no fallback
 * for it: that row carries the id the message has left.
 */
internal data class RestoredRows(val rows: Map<EmailKey, Email>, val unplaced: Set<EmailKey>) {
    companion object {
        val NONE = RestoredRows(emptyMap(), emptySet())
    }
}

/**
 * One account's share of an Undo, in the keys its rows are drawn and ticked under. [rows] is the
 * row the cache holds now for each message that came home under another id; [unplaced] came home
 * and no row says under which id.
 */
internal class RestoredLanding(val rows: Map<EmailKey, Email>, val unplaced: Set<EmailKey>) {
    val renumbered: Map<EmailKey, EmailKey> get() = rows.mapValues { it.value.emailKey() }
}

/**
 * [restored] as the list knows its rows. [accountId] is the one the Undo's entries carry, which is
 * the one the rows are drawn under; [cached] is what the cache answers for the new ids. A new id
 * with no cached row is unplaced: the pair alone gives a gesture nothing it may trust.
 *
 * On [imap] a message that did not come home is unplaced as well: it sits in the folder the Undo
 * failed to take it out of, under the uid that folder gave it, and the id its row is redrawn with
 * names a uid nothing carries. On JMAP that id is still the message's, and the gesture must go.
 */
internal fun restoredLanding(
    accountId: String?,
    restored: MailRepository.Restored,
    cached: List<Email>,
    imap: Boolean,
): RestoredLanding {
    val byId = cached.associateBy { it.id }
    val rows = LinkedHashMap<EmailKey, Email>()
    restored.renumbered.forEach { (was, now) -> byId[now]?.let { rows[EmailKey(accountId, was)] = it } }
    val away = if (imap) restored.unplaced + restored.unrestored else restored.unplaced
    val unplaced = (away + restored.renumbered.keys).mapTo(LinkedHashSet()) { EmailKey(accountId, it) }
    return RestoredLanding(rows, unplaced - rows.keys)
}

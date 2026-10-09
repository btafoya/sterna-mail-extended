package app.sterna.core.data.mail

import app.sterna.core.data.db.CATCH_UP_OFF_WALK
import app.sterna.core.data.db.CatchUpMark
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.IMAP_FOLDER_PAGE
import app.sterna.core.imap.ImapException
import app.sterna.core.imap.ImapFlagChange
import app.sterna.core.imap.ImapFolderWalk
import app.sterna.core.imap.ImapMailboxStatus
import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapSession
import app.sterna.core.imap.fetchUidsPaged
import app.sterna.core.imap.flagsChangedSince
import app.sterna.core.imap.liveUids
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

internal const val IMAP_CONDSTORE = "CONDSTORE"

/**
 * Whether a folder may be read as a delta (RFC 7162) rather than walked in full. All of these, or
 * the walk: a server that keeps no mod-sequence for the folder has no changes to ask for, a folder
 * never reconciled has no point to ask them since, and under any numbering but the recorded one
 * the cached UIDs the delta would trust name other messages. A mod-sequence stated below the
 * recorded one is an index the server rebuilt under the same UIDVALIDITY: no message is above the
 * recorded value any more, so a delta would be answered with no change at all and would then
 * record the lower value over flags it never read.
 */
internal fun mayReadIncrementally(
    condstoreAnnounced: Boolean,
    statedModSeq: Long?,
    recordedModSeq: Long?,
    numbering: UidValidity.Verdict,
): Boolean =
    condstoreAnnounced &&
        statedModSeq != null &&
        recordedModSeq != null &&
        statedModSeq >= recordedModSeq &&
        numbering == UidValidity.Verdict.SAME

/** A folder's `HIGHESTMODSEQ` and the numbering it was stated under, both read off one SELECT. */
data class ImapModSeqMark(val uidValidity: Long, val highestModSeq: Long)

/** Null when the SELECT stated no mod-sequence, or no numbering to keep one under. */
internal fun modSeqMarkOf(status: ImapMailboxStatus): ImapModSeqMark? {
    val highestModSeq = status.highestModSeq ?: return null
    val uidValidity = UidValidity.stated(status.uidValidity) ?: return null
    return ImapModSeqMark(uidValidity, highestModSeq)
}

/**
 * The mark to keep once [load] has been reconciled, null for a read that was not: the next delta
 * asks only for what changed since the mark, so a mark taken from a read that evicted nothing
 * would leave what that read failed to see in the cache for good.
 */
internal fun reconciledModSeq(load: ImapFolderLoad, accountId: String): ImapModSeqMark? {
    if (reconcilableIds(load, accountId) == null) return null
    return load.modSeq
}

/** What either reading of a folder hands back: the walk the reconcile judges, and the mark it may keep. */
internal class ImapFolderRead(val walk: ImapFolderWalk, val unread: Int, val modSeq: ImapModSeqMark?)

/**
 * How an attempt at a delta ended: [read] when it vouches for the folder, otherwise [opened], the
 * SELECT the full walk starts from. [abandoned] when the delta had begun and gave up: such a pass
 * keeps no mark, the full walk that follows it included.
 */
internal class IncrementalAttempt(
    val read: ImapFolderRead?,
    val opened: ImapMailboxStatus?,
    val abandoned: Boolean,
)

data class ImapFlagWrite(val id: String, val seen: Boolean, val flagged: Boolean)

/**
 * The flag writes [changes] ask for, of rows in [cachedIds] only: a message the cache does not hold
 * is fetched whole instead. The last answer for a UID wins, a server being free to repeat one.
 */
internal fun flagWritesFor(
    changes: List<ImapFlagChange>,
    accountId: String,
    mailboxId: String,
    cachedIds: Set<String>,
): List<ImapFlagWrite> = changes
    .map { change ->
        ImapFlagWrite(
            id = ImapMailService.emailId(accountId, mailboxId, change.uid),
            seen = change.flags.any { it.equals("\\Seen", true) },
            flagged = change.flags.any { it.equals("\\Flagged", true) },
        )
    }
    .filter { it.id in cachedIds }
    .associateBy { it.id }
    .values
    .toList()

/** The UIDs of [live] with no row in [cachedIds], newest first as a walk brings them. */
internal fun absentFromCache(
    live: Set<Long>,
    accountId: String,
    mailboxId: String,
    cachedIds: Set<String>,
): List<Long> = live
    .filterNot { ImapMailService.emailId(accountId, mailboxId, it) in cachedIds }
    .sortedDescending()

/**
 * The cache as a delta needs it, [ImapMailService] having a connection and no database.
 * [cachedIds] names the rows of a folder the delta may leave as they are; any other live message
 * is fetched whole. [writeFlags] answers whether every write was applied: a write held back is a
 * change the mod-sequence must not be moved past. It updates the flag columns of rows already
 * cached and never upserts one: `@Upsert` replaces the whole row, and a row rebuilt from a
 * flag-only answer would blank the preview and every column that answer did not carry.
 */
class ImapFolderDelta(
    val cachedIds: suspend (mailboxId: String) -> Set<String>,
    val writeFlags: suspend (List<ImapFlagWrite>) -> Boolean,
)

/**
 * [heldLocally] names the rows whose flags the user has just changed here: the server may still
 * hold the old ones, and its answer must not put them back.
 */
internal fun imapFolderDelta(
    cachedIds: suspend (mailboxId: String) -> List<String>,
    stillConfigured: suspend () -> Unit,
    heldLocally: () -> Collection<String>,
    setReadAndStar: suspend (ids: List<String>, seen: Boolean, flagged: Boolean) -> Unit,
): ImapFolderDelta = ImapFolderDelta(
    cachedIds = { mailboxId -> cachedIds(mailboxId).toSet() },
    writeFlags = { writes ->
        stillConfigured()
        val held = heldLocally().toSet()
        val (spared, due) = writes.partition { it.id in held }
        due.groupBy { it.seen to it.flagged }.forEach { (state, rows) ->
            rows.map { it.id }.chunked(MAX_CHANGES).forEach { ids -> setReadAndStar(ids, state.first, state.second) }
        }
        spared.isEmpty()
    },
)

/** The rows of [marks] still marked off their folder's walk. */
internal fun offWalkAmong(marks: List<CatchUpMark>): List<String> =
    marks.filter { (it.catchUp and CATCH_UP_OFF_WALK) != 0 }.map { it.id }

/**
 * The cache as one read of an IMAP folder writes to it, delta and walk alike. With opening lines
 * wanted, a row that has none is not one the delta may leave as it is: a full walk reads the line
 * it lacks, and nothing else of a delta would. [everyRowWritten] turns false for good once
 * [sparingInFlight] took a row out of a page, which [ImapIncrementalReader.remember] reads.
 * [markWalked] is told every row a page wrote: a row the unread catch-up had cached ahead of the
 * folder's list is an ordinary row of it once a page of that list has written it.
 */
internal class ImapFolderWrites(
    wantsPreviews: Boolean,
    idsForMailbox: suspend (mailboxId: String) -> List<String>,
    idsWithPreviewForMailbox: suspend (mailboxId: String) -> List<String>,
    private val stillConfigured: suspend () -> Unit,
    heldLocally: () -> Collection<String>,
    setReadAndStar: suspend (ids: List<String>, seen: Boolean, flagged: Boolean) -> Unit,
    private val sparingInFlight: (List<EmailEntity>) -> List<EmailEntity>,
    private val upsertAll: suspend (List<EmailEntity>) -> Unit,
    private val markWalked: suspend (mailboxId: String, ids: List<String>) -> Unit,
    private val marksOf: suspend (ids: List<String>) -> List<CatchUpMark>,
) {
    @Volatile
    var everyRowWritten: Boolean = true
        private set

    val delta: ImapFolderDelta = imapFolderDelta(
        cachedIds = { mailboxId ->
            if (wantsPreviews) idsWithPreviewForMailbox(mailboxId) else idsForMailbox(mailboxId)
        },
        stillConfigured = stillConfigured,
        heldLocally = heldLocally,
        setReadAndStar = setReadAndStar,
    )

    suspend fun writePage(page: List<EmailEntity>) {
        stillConfigured()
        val written = sparingInFlight(page)
        if (written.size != page.size) everyRowWritten = false
        upsertAll(written)
        written.groupBy({ it.mailboxId }, { it.id }).forEach { (mailboxId, ids) ->
            ids.chunked(MAX_CHANGES).forEach { markWalked(mailboxId, it) }
        }
    }

    /**
     * Takes off the catch-up the rows of [keepIds] that still carry its mark. To be called with
     * what a finished read was reconciled against, the window it vouches for: a delta leaves a
     * cached row as it is, so a caught-up row the window has slid over is written by no page, and
     * left marked it stays out of the older page's count, which then never reaches the folder's
     * size. The marks are read first and only the marked rows written: an ordinary refresh has
     * none, and must not write to the cache for that.
     */
    suspend fun markKeptWalked(mailboxId: String, keepIds: Set<String>) {
        keepIds.chunked(MAX_CHANGES).forEach { chunk ->
            val offWalk = offWalkAmong(marksOf(chunk))
            if (offWalk.isNotEmpty()) {
                stillConfigured()
                markWalked(mailboxId, offWalk)
            }
        }
    }
}

/**
 * The delta of [ImapMailService.loadFolder], which keeps the full walk. [numbering] is where a
 * folder's mod-sequence is kept beside its UIDVALIDITY.
 */
internal class ImapIncrementalReader(private val numbering: UidValidityStore) {

    // Folders whose delta died with its connection. The caller's retry opens a new one and finds
    // the folder here, once: a server that never answers one of the delta's commands would
    // otherwise fail every refresh the same way.
    private val distrusted: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    // Folders the server refused a command of the delta for, kept for the life of the process: a
    // server that says no to one refresh says no to the next, and each would pay for a delta and
    // then for the walk. A folder that merely moved under its delta is not one of them.
    private val refused: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /**
     * Opens [mailboxId] asking for CONDSTORE and reads it as a delta where [mayReadIncrementally]
     * allows it. When it does not, or when the delta cannot vouch for what it read, the answer
     * carries the SELECT a full walk starts from: a fresh one if the delta had begun, its commands
     * having dropped untagged lines that told how the folder moved. A tagged refusal falls back on
     * the same connection, the only failure that leaves it where the next command can be read;
     * any other climbs out to the caller's retry. A failure of the cache is no reason to walk: it
     * climbs out as it would from the full walk.
     */
    suspend fun attempt(
        session: ImapSession,
        accountId: String,
        mailboxId: String,
        limit: Int,
        delta: ImapFolderDelta,
        rowsOf: suspend (ImapMailboxStatus, List<ImapMessage>) -> List<EmailEntity>,
        onPage: suspend (List<EmailEntity>) -> Unit,
    ): IncrementalAttempt {
        val key = ImapMailService.emailId(accountId, mailboxId, 0L)
        val wasDistrusted = distrusted.remove(key)
        // Asked before the SELECT: to a server that volunteered no list this is a command, and
        // none may stand between the SELECT and the live set.
        val condstore = session.hasCapability(IMAP_CONDSTORE)
        val status = selectFolder(session, mailboxId, condstore)
        val recorded = numbering.recordedModSeq(accountId, mailboxId)
        val verdict = UidValidity.verdict(numbering.recorded(accountId, mailboxId), status.uidValidity)
        val since = recorded.takeIf { mayReadIncrementally(condstore, status.highestModSeq, recorded, verdict) }
        if (wasDistrusted || since == null || key in refused) return IncrementalAttempt(read = null, opened = status, abandoned = wasDistrusted)

        var cacheFailure: Throwable? = null
        suspend fun <T> cache(block: suspend () -> T): T =
            try {
                block()
            } catch (failure: Throwable) {
                cacheFailure = failure
                throw failure
            }
        val read = try {
            deltaRead(
                session, accountId, mailboxId, status, limit, since,
                cachedIds = { cache { delta.cachedIds(mailboxId) } },
                writeFlags = { writes -> cache { delta.writeFlags(writes) } },
                writePage = { page ->
                    val rows = rowsOf(status, page)
                    cache { onPage(rows) }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            if (failed === cacheFailure) throw failed
            if (failed !is ImapException || failed.taggedStatus == null) {
                distrusted += key
                throw failed
            }
            refused += key
            null
        }
        if (read != null) return IncrementalAttempt(read = read, opened = null, abandoned = false)
        return IncrementalAttempt(read = null, opened = selectFolder(session, mailboxId, condstore), abandoned = true)
    }

    /**
     * The SELECT of [attempt]. A server may announce CONDSTORE and refuse the parameter all the
     * same: the bare SELECT then opens the folder, and its own refusal is the one that climbs out.
     */
    private fun selectFolder(session: ImapSession, mailboxId: String, condstore: Boolean): ImapMailboxStatus =
        try {
            session.select(mailboxId, condstore = true)
        } catch (refusal: ImapException) {
            if (!condstore || refusal.taggedStatus == null) throw refusal
            session.select(mailboxId)
        }

    /**
     * The delta itself, null when it cannot vouch for the folder. Its walk names every live UID of
     * the window and not only the ones fetched: the reconcile evicts whatever the walk leaves out.
     */
    private suspend fun deltaRead(
        session: ImapSession,
        accountId: String,
        mailboxId: String,
        status: ImapMailboxStatus,
        limit: Int,
        since: Long,
        cachedIds: suspend () -> Set<String>,
        writeFlags: suspend (List<ImapFlagWrite>) -> Boolean,
        writePage: suspend (List<ImapMessage>) -> Unit,
    ): ImapFolderRead? {
        val live = session.liveUids(status, limit)
        if (live.moved) return null
        // No UID from a folder that holds messages is also what a server answers to a search key
        // it did not take in: the walk and its own guards decide.
        if (live.uids.isEmpty() && !live.folderStatedEmpty) return null
        var flagsApplied = true
        if (live.uids.isNotEmpty()) {
            val cached = cachedIds()
            val changed = session.flagsChangedSince(live.uids.min(), since, status.exists)
            if (changed.moved) return null
            flagsApplied = writeFlags(flagWritesFor(changed.changes, accountId, mailboxId, cached))
            val absent = absentFromCache(live.uids, accountId, mailboxId, cached)
            if (session.fetchUidsPaged(absent, status.exists, IMAP_FOLDER_PAGE, writePage).moved) return null
        }
        return ImapFolderRead(
            walk = ImapFolderWalk(uids = live.uids.sortedDescending(), moved = false, folderStatedEmpty = live.folderStatedEmpty),
            unread = session.unseenCount(),
            // The SELECT's own, not a later one: what changed while the delta ran is above it, and asked for next time.
            modSeq = modSeqMarkOf(status).takeIf { flagsApplied },
        )
    }

    /**
     * Keeps the mod-sequence [load] started from. To be called once [load] has been reconciled.
     * Nothing is kept unless [everyRowWritten]: a row a page was held from writing may come back
     * into the cache as it was, and only a read from the mark as it stands asks what changed on it.
     */
    suspend fun remember(accountId: String, load: ImapFolderLoad, everyRowWritten: Boolean) {
        if (!everyRowWritten) return
        val mark = reconciledModSeq(load, accountId) ?: return
        numbering.recordModSeq(accountId, load.targetMailboxId, mark)
    }
}

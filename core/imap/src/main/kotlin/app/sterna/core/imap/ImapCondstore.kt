package app.sterna.core.imap

/** The UIDs of a folder window that are not flagged `\Deleted`, [moved] as in [ImapFolderWalk]. */
data class ImapLiveUids(
    val uids: Set<Long>,
    val moved: Boolean,
    val folderStatedEmpty: Boolean = false,
)

/** The UIDs a paged fetch brought back, newest first within a page, [moved] as in [ImapFolderWalk]. */
data class ImapUidWalk(val uids: List<Long>, val moved: Boolean)

/** One message's flags as the server spelled them, `\Deleted` included. */
data class ImapFlagChange(val uid: Long, val flags: List<String>)

data class ImapFlagChanges(val changes: List<ImapFlagChange>, val moved: Boolean)

private val NO_MOD_SEQ = Regex("\\[NOMODSEQ]")
private val HIGHEST_MOD_SEQ = Regex("\\[HIGHESTMODSEQ (\\d+)]")

/** The mod-sequence a SELECT's untagged lines state for the folder (RFC 7162 §3.1.2), or null. */
internal fun highestModSeqStated(untagged: List<List<Any?>>): Long? {
    val lines = untagged.map { flatten(it) }
    // Null and never 0: zero is a mod-sequence every message is above, so a folder that keeps
    // none would read as one whose changes can all be asked for.
    if (lines.any { NO_MOD_SEQ.containsMatchIn(it) }) return null
    return lines.firstNotNullOfOrNull { HIGHEST_MOD_SEQ.find(it)?.groupValues?.get(1)?.toLongOrNull() }
}

/**
 * The (UID, flags) of every untagged `FETCH` in [untagged] that names both. Nothing below
 * [lowestUid]: `n:*` with n above the last UID still answers with the last message (RFC 3501
 * §6.4.8), which the caller did not ask about.
 */
internal fun flagChangesIn(untagged: List<List<Any?>>, lowestUid: Long): List<ImapFlagChange> =
    untagged.mapNotNull { line ->
        if (line.getOrNull(2) != "FETCH") return@mapNotNull null
        val items = pairUp(line.getOrNull(3) as? List<*> ?: return@mapNotNull null)
        val uid = (items["UID"] as? String)?.toLongOrNull()?.takeIf { it >= lowestUid } ?: return@mapNotNull null
        val flags = items["FLAGS"] as? List<*> ?: return@mapNotNull null
        ImapFlagChange(uid, flags.mapNotNull { it as? String })
    }

/**
 * Whether [line] is the answer a full fetch of one of [uids] is owed: a `FETCH` naming one of them
 * and carrying its `ENVELOPE`. A server may slip in a `FETCH` nobody asked for, the flags and
 * `MODSEQ` of a message changed elsewhere (RFC 7162): read as a message it is a row with no
 * subject, and it shadows the real answer for the same UID.
 */
internal fun isEnvelopeOfOneOf(line: List<Any?>, uids: Set<Long>): Boolean {
    if (line.getOrNull(2) != "FETCH") return false
    val items = pairUp(line.getOrNull(3) as? List<*> ?: return false)
    if (items["ENVELOPE"] !is List<*>) return false
    val uid = (items["UID"] as? String)?.toLongOrNull() ?: return false
    return uid in uids
}

/**
 * The UIDs of the newest [limit] positions of the selected folder that are not flagged `\Deleted`,
 * without fetching anything. The floor is a sequence number, which `UID SEARCH` takes as a search
 * key while answering in UIDs. Nothing is sent for a folder of zero, where `1:*` names nothing, and
 * [ImapLiveUids.folderStatedEmpty] then reads as in [ImapFolderWalk]; nothing is sent for a window
 * of zero either, as the walk sends nothing.
 *
 * To be sent before any other command once [status] was read: the floor is a position of that
 * SELECT, and a folder that shrank below it answers with its last message alone (RFC 3501 §6.4.8,
 * `n:*`), a message that may sit outside the window. Only the `EXPUNGE` tells, and a server hands
 * it to whichever command comes next: read here it is [ImapLiveUids.moved], read by a command that
 * drops its untagged lines it is lost.
 *
 * An empty set from a folder that holds messages is also what a server answers to a key it did not
 * understand: it does not vouch for an empty window. Every `SEARCH` line is read, a server being
 * free to split its result.
 */
fun ImapSession.liveUids(status: ImapMailboxStatus, limit: Int): ImapLiveUids {
    val exists = status.exists
    if (exists < 1) return ImapLiveUids(emptySet(), moved = false, folderStatedEmpty = status.existsObserved)
    if (limit <= 0) return ImapLiveUids(emptySet(), moved = false)
    val untagged = command("UID SEARCH ${folderWindowLowest(exists, limit)}:* UNDELETED").untagged
    val uids = untagged
        .filter { it.getOrNull(1) == "SEARCH" }
        .flatMap { line -> line.drop(2).mapNotNull { (it as? String)?.toLongOrNull() } }
        .toSet()
    return ImapLiveUids(uids, folderMoved(untagged, exists))
}

/**
 * [ImapSession.walkFolder] over named UIDs instead of a sequence window: same items, same messages,
 * same `NOOP` and the same reading of what moved, [pageSize] UIDs to a request in the order given.
 * Only the answers [isEnvelopeOfOneOf] the page asked for become messages. An `EXISTS` that grew
 * counts as movement here too, although under CONDSTORE it is only an arrival: refusing costs a
 * full walk, accepting it wrongly costs mail.
 *
 * A null [startedWith] is the caller that reconciles nothing and only wants the envelopes of UIDs
 * it holds: no movement is judged and [ImapUidWalk.moved] is always false, an `EXISTS` that grew
 * being mail that arrived and an `EXPUNGE` a UID that does not answer. The `NOOP`, which only asks
 * for that verdict, is not sent. Its sets go out through [compressUidSet]. The judged reading keeps
 * the comma list in the order given, which is what its callers' fixtures parse.
 */
suspend fun ImapSession.fetchUidsPaged(
    uids: List<Long>,
    startedWith: Int?,
    pageSize: Int,
    onPage: suspend (List<ImapMessage>) -> Unit,
): ImapUidWalk {
    val pages = uids.distinct().chunked(pageSize.coerceAtLeast(1)).iterator()
    var asked = emptySet<Long>()
    return walkRequests(startedWith, onPage, solicited = { isEnvelopeOfOneOf(it, asked) }) {
        if (!pages.hasNext()) return@walkRequests null
        val page = pages.next()
        asked = page.toSet()
        val set = if (startedWith == null) compressUidSet(page) else page.joinToString(",")
        "UID FETCH $set $FOLDER_LIST_ITEMS"
    }
}

/**
 * The flags of every message from [lowestUid] up whose mod-sequence is above [modSeq] (RFC 7162
 * §3.1.4.1), raw: a message that became `\Deleted` is in the answer with its flag, this being a
 * list of flags and not of messages. `CHANGEDSINCE` says nothing of an expunged message.
 */
fun ImapSession.flagsChangedSince(lowestUid: Long, modSeq: Long, startedWith: Int): ImapFlagChanges {
    val untagged = command("UID FETCH $lowestUid:* (UID FLAGS) (CHANGEDSINCE $modSeq)").untagged
    return ImapFlagChanges(flagChangesIn(untagged, lowestUid), folderMoved(untagged, startedWith))
}

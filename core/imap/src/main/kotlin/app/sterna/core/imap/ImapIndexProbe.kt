package app.sterna.core.imap

/** UIDs per probe command; a claim list is unbounded, a command line is not. */
private const val INDEX_PROBE_BATCH = 200

/**
 * Index rows claiming to match the search in one folder, by UID, and the numbering recorded for that
 * folder. An index row carries no numbering of its own: one written under an earlier numbering is
 * not told apart until a renumbering's invalidation purges the index.
 */
data class ImapIndexClaims(val uids: Set<Long>, val uidValidity: Long)

/**
 * What a folder read in full said of the claims missing from its answer ([ImapFolderHits.verdict]):
 * [gone] are no longer in it, [flags] are still there, with the flags read from it. A claim in
 * neither proved nothing.
 */
data class ImapIndexVerdict(val gone: Set<Long>, val flags: Map<Long, List<String>>)

/**
 * Whether a folder's answer may contradict the index: the SELECT of this walk stated the numbering
 * the claims were read under, and the scan was not cut short. A server stating no numbering (0)
 * vouches for nothing, and neither does a folder whose SELECT or SEARCH failed, which never reaches
 * this question.
 */
internal fun answersInFull(claims: ImapIndexClaims, observed: Long, truncated: Boolean): Boolean =
    observed > 0L && observed == claims.uidValidity && !truncated

/**
 * Ask the folder [searchFolders] still has selected about [unanswered]; asked after another SELECT,
 * a UID is looked up in the wrong folder. Absent from `UID SEARCH UID` is gone, present takes the
 * flags read there. A probe that failed, refused or cut, is no answer: the claims it did not settle
 * stay as they are, a wrong "gone" dropping the index row of a live message upstairs.
 *
 * [ImapSession.searchUids] reads one `SEARCH` line, so a server splitting its answer leaves matches
 * out of the walk's list; they are probed too, and come back present with their own flags.
 */
internal fun ImapSession.probeClaims(unanswered: Set<Long>): ImapIndexVerdict {
    val gone = mutableSetOf<Long>()
    val flags = mutableMapOf<Long, List<String>>()
    for (chunk in unanswered.sorted().chunked(INDEX_PROBE_BATCH)) {
        val present = runCatching { uidsStillInSource(compressUidSet(chunk)) }.getOrNull() ?: break
        gone += chunk.filterNot { it in present }
        val stillThere = chunk.filter { it in present }
        if (stillThere.isEmpty()) continue
        runCatching { flagsOf(stillThere) }.getOrNull()?.let { flags += it }
    }
    return ImapIndexVerdict(gone, flags)
}

/** The raw flags of [uids] in the selected folder; a `FETCH` for a UID not asked is ignored. */
private fun ImapSession.flagsOf(uids: List<Long>): Map<Long, List<String>> {
    val asked = uids.toSet()
    return flagChangesIn(command("UID FETCH ${compressUidSet(asked)} (UID FLAGS)").untagged, lowestUid = 1L)
        .filter { it.uid in asked }
        .associate { it.uid to it.flags }
}

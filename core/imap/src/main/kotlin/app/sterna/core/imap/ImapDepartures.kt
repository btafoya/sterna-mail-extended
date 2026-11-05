package app.sterna.core.imap

/** UIDs per departure question: a banner list is short, the difference it is cut from is not. */
private const val DEPARTURE_BATCH = 200

/**
 * Which of [uids] the selected folder no longer holds as a live message: absent from the answer, or
 * still there flagged `\Deleted`, which no list of ours shows. Any batch refused or cut throws: a
 * partial answer cannot tell an unasked UID from a live one, and a banner taken off a live message
 * hides mail.
 */
fun ImapSession.departedOf(uids: Collection<Long>): Set<Long> {
    val gone = mutableSetOf<Long>()
    for (chunk in uids.toSortedSet().chunked(DEPARTURE_BATCH)) {
        val held = uidsHeldUndeleted(compressUidSet(chunk))
        gone += chunk.filterNot { it in held }
    }
    return gone
}

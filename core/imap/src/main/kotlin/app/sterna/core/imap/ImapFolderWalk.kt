package app.sterna.core.imap

    /**
     * How many sequence positions one request of a folder walk asks for. IMAP has nothing to negotiate
     * here, so this is ours alone, and it is a MEMORY number: everything one `FETCH` brings back is
     * parsed and held whole before a single message is read out of it.
     */
const val IMAP_FOLDER_PAGE = 200

    /**
     * What one paginated walk of a folder brings back — UIDS and a verdict, never messages: the walk
     * hands each page to its caller as it lands and forgets it. [uids] is in walk order, newest first,
     * DE-DUPLICATED BY UID and never by sequence number — pages overlap after an expunge, and a
     * sequence number means a different message the moment the folder changes.
     *
     * [moved] is the whole IMAP-specific half of this walk and the caller MUST honour it: true means
     * the folder changed under the walk, so reconciling the cache against [uids] would DELETE messages
     * the server still holds. See [folderMoved] for which messages are at risk.
     */
data class ImapFolderWalk(
    val uids: List<Long>,
    val moved: Boolean,
        /**
         * Whether the `SELECT` this walk started from STATED that the folder holds nothing — an
         * `* 0 EXISTS` actually parsed, never the absence of an answer. It exists because [uids] being
         * empty says two opposite things: a folder that really holds nothing SHOULD have its cache
         * cleared, while one whose `SELECT` said nothing about its size answers with no UID too, and
         * clearing that destroys mail the server still holds. False by default, the refusing
         * direction: a fixture that does not state this cannot license a delete by omission.
         */
    val folderStatedEmpty: Boolean = false,
)

    /** The lowest sequence number of the newest [limit] messages of a folder holding [exists] of them.
     *  Never below 1, so a window larger than the folder is the whole folder rather than a range the
     *  server rejects. */
fun folderWindowLowest(exists: Int, limit: Int): Int = (exists - limit + 1).coerceAtLeast(1)

    /**
     * The sequence range of the NEXT request of a newest-first folder walk over [lowest]..[highest], or
     * null at the bottom of the window. The step is counted in SEQUENCE POSITIONS, never in messages
     * returned: a page can hold fewer messages than it covers positions (`\Deleted` ones are dropped),
     * and stepping by the message count would re-read the same slice for ever.
     */
fun nextFolderPage(lowest: Int, highest: Int, previous: IntRange?, pageSize: Int): IntRange? {
    if (pageSize <= 0 || highest < 1 || lowest > highest) return null
    if (previous == null) return (highest - pageSize + 1).coerceAtLeast(lowest)..highest
    if (previous.first <= lowest) return null
    val top = previous.first - 1
    return (top - pageSize + 1).coerceAtLeast(lowest)..top
}

    /**
     * Whether these untagged response lines say the folder is no longer the one the walk started on,
     * which held [startedWith] messages. THE GUARD THAT KEEPS THIS WALK FROM DELETING MAIL: a single
     * `FETCH` sees a folder frozen, a walk of several does not.
     *
     * 1. The FLOOR of the window slides out from under it: `lowest` is computed once, and each
     *    `EXPUNGE` moves every sequence number above it DOWN by one, so k messages end up below where
     * this walk ever looks. It is NOT true that a message can fall between two pages — the shift
     *    is downwards and the walk descends, so a renumbering makes the next range RE-READ what the
     * previous one had. It cannot happen at all when `limit >= exists`.
     * 2. An ARRIVAL while another path is writing: the push/IDLE path caches new messages itself, so a
     *    message cached during the walk is in the cache and not in the walk's UIDs.
     *
     * The safe answer to both is to finish the walk WITHOUT reconciling. Three things are read as "it
     * moved", each a fact the server stated: `* n EXPUNGE`, `* VANISHED …` (RFC 7162 §3.2.10), and
     * `* n EXISTS` with an `n` other than [startedWith] — an `n` that will not parse counts too.
     */
fun folderMoved(untagged: List<List<Any?>>, startedWith: Int): Boolean = untagged.any { line ->
    val second = line.getOrNull(1)
    when {
        line.getOrNull(2) == "EXPUNGE" -> true
        second == "VANISHED" -> true
        line.getOrNull(2) == "EXISTS" -> (second as? String)?.toIntOrNull() != startedWith
        else -> false
    }
}

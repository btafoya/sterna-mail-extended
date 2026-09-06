package app.sterna.core.jmap

    /**
     * What the previous request of a windowed folder walk asked for, and what it brought back.
     * [queryCount] is how many ids `Email/query` returned, NOT how many objects `Email/get` sent back:
     * the get can legitimately return fewer, and ending the walk on a short GET would abandon the
     * older mail behind it. [added] is how many were NEW, so a page that adds nothing ends the walk
     * instead of asking again for ever.
     */
data class WalkedPage(
    /** The `limit` that request carried. */
    val requested: Int,
    val queryCount: Int,
    val added: Int,
)

    /**
     * What a windowed folder walk brings back — IDS and cursors, never messages: the walk hands each
     * page to its caller as it lands and keeps one string per message. [ids] is in walk order, oldest
     * request first, DE-DUPLICATED, pages being able to overlap after a recovery.
     * [queryState]/[emailState] are the FIRST response's, captured as two strings while it is in hand.
     * [queryCount] is every id the walk's queries listed, and is what says whether an EMPTY walk means
     * "the folder is empty" or "the server listed messages and sent none" — only the first may be
     * reconciled against.
     *
     * [resumedByPosition] says the walk PAGED BY ABSOLUTE POSITION at least once, its anchor having
     * left the folder mid-walk. That recovery corrects for ONE row leaving; if k more left in the same
     * instant the restart lands k rows too far down and those ids are NEVER LISTED, so the caller must
     * not reconcile — the JMAP twin of `ImapFolderWalk.moved`. NO DEFAULT VALUE, deliberately: a
     * default `false` reads as safe and is the opposite, licensing a DELETE of every cached row
     * outside [ids] at any construction site that forgot the field.
     */
data class WindowWalk(
    val ids: List<String>,
    val queryState: String?,
    val emailState: String?,
    val queryCount: Int,
    val resumedByPosition: Boolean,
)

    /**
     * How big the NEXT `Email/query` of a windowed folder walk must be, or null when the walk is over.
     * Two numbers, never one: [target] is the sync window the user asked for, [pageSize] is what the
     * SERVER will hand over in one request (`maxObjectsInGet`, RFC 8620 §5.1 — past it the whole
     * request is rejected, not truncated). A window bigger than the page is fetched in several
     * requests, not clamped, or every window would silently shrink to what the server admits.
     *
     * The walk stops on the first of: [fetched] reaching [target]; the last query returning FEWER ids
     * than asked for (the folder is exhausted); the last page adding nothing new.
     */
fun nextWindowPageLimit(
    fetched: Int,
    target: Int,
    pageSize: Int,
    last: WalkedPage?,
): Int? {
    if (target <= 0 || pageSize <= 0) return null
    if (last == null) return minOf(target, pageSize)
    if (fetched >= target) return null
    if (last.queryCount < last.requested) return null
    if (last.added <= 0) return null
    return minOf(target - fetched, pageSize)
}

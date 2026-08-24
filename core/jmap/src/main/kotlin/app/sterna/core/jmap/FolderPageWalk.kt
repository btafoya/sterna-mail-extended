package app.sterna.core.jmap

/**
 * What the previous request of a windowed folder walk asked for, and what it brought back.
 *
 * [queryCount] is how many ids `Email/query` returned, NOT how many objects `Email/get` sent
 * back: the get can legitimately return fewer (a message destroyed between the two calls), and
 * ending the walk on a short GET would abandon the older mail behind it — the same reason
 * `CrawlPage` carries a query count.
 *
 * [added] is how many of them were NEW to the accumulation. It exists so the walk can never spin:
 * a page that adds nothing (an anchor that no longer advances, a server answering the same slice)
 * ends the walk instead of asking again for ever.
 */
data class WalkedPage(
    /** The `limit` that request carried. */
    val requested: Int,
    val queryCount: Int,
    val added: Int,
)

/**
 * What a windowed folder walk brings back — IDS and cursors, never messages.
 *
 * ⛔ There is no `emails` here, and that is the whole point of the shape. The walk hands each
 * page's messages to its caller as the page lands and forgets them; what it keeps for the end is
 * one string per message. Holding the window's `Email` objects instead — twenty fields, eight of
 * them collections — is what made "Messages to sync = All" a memory problem rather than a network
 * one, and it is the caller's ONE write at the end that required it.
 *
 * [ids] is in walk order, oldest request first, DE-DUPLICATED: the walk's pages can overlap (a
 * recovery page restarts at a position the previous one already covered) and an id counted twice
 * would be counted twice against the window.
 *
 * [queryState]/[emailState] are the FIRST response's, not the last's — see `queryEmailsWindow`.
 * They are captured as two strings while that response is in hand; keeping the response itself to
 * read them off at the end would pin a whole page of decoded messages alive for the walk, which is
 * the cost this type exists to remove.
 *
 * [queryCount] is every id the walk's queries listed, across all of them. It is what says whether
 * an EMPTY walk means "the folder is empty" or "the server listed messages and then sent none of
 * them", and only the first of those may be reconciled against — see
 * `MailRepository`'s `reconcilableWindowIds`. A caller also reads it to tell a short GET from an
 * exhausted folder.
 *
 * [resumedByPosition] says the walk PAGED BY ABSOLUTE POSITION at least once: its anchor left the
 * folder mid-walk (`anchorNotFound`) and it restarted at a computed index. That recovery corrects
 * for ONE row leaving the list — the anchor's own. If the anchor plus k messages of lower index
 * left in the same instant, the restart lands k rows too far down and the walk NEVER LISTS those k
 * ids, although the server still holds them. So true means "some of this folder may be missing
 * from [ids] for a reason that is not the folder's", and the caller must not reconcile against it
 * — the JMAP twin of `ImapFolderWalk.moved`.
 *
 * ⛔ NO DEFAULT VALUE, deliberately. A default of `false` reads as "safe" and is the opposite: it
 * says "you may DELETE every cached row outside [ids]" to any future construction site that forgot
 * the field — including a test fixture written for something else entirely. Every site must state
 * which kind of walk it describes.
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
 *
 * Two numbers, never one: [target] is how much of the folder the user asked to keep (the sync
 * window), [pageSize] is how much the SERVER will hand over in one request (`maxObjectsInGet`,
 * RFC 8620 §5.1 — past it the whole request is rejected, not truncated). A window bigger than the
 * page is therefore legal and ordinary; it is fetched in several requests, not clamped, because
 * clamping it would silently shrink everyone's window to whatever the server admits (and to the
 * 100-per-request fallback on a server that admits nothing).
 *
 * The walk stops on the first of:
 * - [fetched] has reached [target] — the window is full;
 * - the last `Email/query` returned FEWER ids than it was asked for — the folder is exhausted, so
 *   there is nothing behind it (this is what stops a 40-message folder after one request);
 * - the last page added nothing new — no progress, so asking again cannot help.
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

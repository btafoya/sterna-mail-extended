package app.sterna.ui.message

import app.sterna.core.imap.CryptoKind

/** Pure helpers for paging between list entries in the reading view (unit-tested). */
object MessagePaging {
    /** The identity of one reading-view entry — (owning account, email id) — as a single string. JMAP
     *  ids are handed out PER ACCOUNT, so an id alone let Compose treat two different messages as one
     *  page and show the wrong account's mail (#92). The separator is NUL, so the mapping is injective. */
    fun entryKey(emailId: String, accountId: String?): String = "${accountId.orEmpty()}\u0000$emailId"

    /**
     * Whether the reading view must (re)load: [settled] first, and everything else after it.
     *
     * [settled] — "the page the reader landed on, not a warmed neighbour" — is why this function
     * takes it rather than the screen keeping its own guard: the pager composes one page each side, so
     * without it opening one message loads three, and on IMAP each load hands the sender's announced
     * key to OpenKeychain, against ENCRYPTION.md:177-180.
     *
     * [failed] is IN HERE and is an `&&` not an `||`. The account is part of the identity question.
     */
    fun needsLoad(
        settled: Boolean,
        loadedId: String?,
        loadedAccountId: String?,
        emailId: String,
        accountId: String?,
        failed: Boolean,
    ): Boolean = settled && (failed || loadedId != emailId || loadedAccountId != accountId)

    /**
     * Whether a body already in the LOCAL cache may be put on a page the finger has not reached — the
     * decision behind `warmFromCache`, which moves the ~1 s rendering staircase before the gesture.
     * It moves nothing onto the NETWORK.
     * The three refusals are about what the reader would SEE on a page she has not swiped to, and
     * none of them keeps an unopened page off the network — that is the one-way `everActive` latch.
     * [cryptoKind] non-null would print the armour in clear; inline images not settled is exactly
     * `ensureInlineImages`'s own condition; [senderAllowedRemoteImages] buys COMFORT only, the
     * settle otherwise flipping `blockRemote`, half of the load key. The caller compares LOWER-CASED.
     */
    fun warmable(
        cryptoKind: CryptoKind?,
        inlineParts: Int,
        cachedInlineImages: Int,
        senderAllowedRemoteImages: Boolean,
    ): Boolean = cryptoKind == null &&
        (inlineParts == 0 || cachedInlineImages > 0) &&
        !senderAllowedRemoteImages

    /**
     * The entry the reader is SETTLED on: [previous] while the pager is [scrolling], [atRest]
     * otherwise. The caller holds the answer as the MESSAGE, never as its rank.
     *
     * Identity, not a page number: `settledPage` is an index refreshed only around a scroll, so mail
     * prepended under an open reader re-anchors the pager while that index keeps its old number and
     * names the neighbour above — whose page then believed itself settled and LOADED. [scrolling]
     * covers drag and fling alike. At rest the answer is [atRest] even when null.
     */
    fun <T> settledEntry(previous: T?, atRest: T?, scrolling: Boolean): T? =
        if (scrolling) previous else atRest

    /** The page the pager should open on: the position of [anchorId] within the ordered entries (robust
     *  to the list having shifted since the row was tapped), else [fallbackIndex]. Always a valid page
     *  index, or 0 for an empty list. */
    fun resolveInitialPage(orderedIds: List<String?>, anchorId: String, fallbackIndex: Int): Int {
        if (orderedIds.isEmpty()) return 0
        val found = orderedIds.indexOfFirst { it == anchorId }
        val index = if (found >= 0) found else fallbackIndex
        return index.coerceIn(0, orderedIds.size - 1)
    }

    /** Whether the reader can step back / forward from [page] in a context of [pageCount] messages —
     *  whether the position line's chevrons are live. The pager does not wrap and does not run past its
     *  context, so both are false at the matching end and on a single message. */
    fun hasPrevious(page: Int, pageCount: Int): Boolean = page > 0 && pageCount > 1

    fun hasNext(page: Int, pageCount: Int): Boolean = page < pageCount - 1 && pageCount > 1

    /**
     * Merge the live paged entries into the reading session's sticky entry list.
     *
     * The pager must never drop or shift an entry it has already shown: with the unread filter active,
     * marking the settled message read removes it from the live flow, and a pager that followed would
     * re-bind the settled slot to the next unread, mark it read in turn, and cascade. So entries
     * missing from [live] are kept where they were, while genuinely new ones are inserted at their
     * live position.
     */
    fun <T : Any> mergeEntries(stable: List<T>, live: List<T>, idOf: (T) -> String): List<T> {
        if (stable.isEmpty()) return live
        val result = stable.toMutableList()
        val known = result.mapTo(HashSet()) { idOf(it) }
        // pos = insertion cursor in result, advanced past each live entry as it is matched.
        var pos = 0
        for (entry in live) {
            if (idOf(entry) in known) {
                val offset = result.subList(pos, result.size).indexOfFirst { idOf(it) == idOf(entry) }
                if (offset >= 0) pos += offset + 1
            } else {
                result.add(pos, entry)
                known += idOf(entry)
                pos++
            }
        }
        return result
    }
}

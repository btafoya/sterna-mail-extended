package app.sterna.ui.message

/** Pure helpers for paging between list entries in the reading view (unit-tested). */
object MessagePaging {
    /**
     * The identity of one reading-view entry — (owning account, email id) — as a single string.
     *
     * JMAP ids are handed out PER ACCOUNT, so two accounts on one server can carry the same
     * message id; the unified inbox shows both side by side. Every identity check in the reader
     * (the pager's page key, the sticky merge, the live index, the "already loaded?" guard) goes
     * through the pair — an id alone let Compose treat two different messages as one page and
     * show the wrong account's mail (#92).
     *
     * The separator is NUL, which no account id or email id can contain, so the mapping from
     * pair to key is injective: for a single account the keys differ exactly where the ids do,
     * i.e. the page identity has the same granularity as before.
     */
    fun entryKey(emailId: String, accountId: String?): String = "${accountId.orEmpty()}\u0000$emailId"

    /**
     * Whether the reading view must (re)load: [settled] first, and everything else after it.
     *
     * ⛔ [settled] — "this page is the one the reader landed on, not a warmed neighbour" — is the
     * whole reason this function takes it rather than the screen keeping a guard of its own. The
     * pager composes one page each side of the one being read (`beyondViewportPageCount = 1`), so
     * without it, opening ONE message loads three. On an IMAP account a load fetches the whole
     * source, and a whole source hands the sender's announced public key to the OpenPGP provider
     * (`MailRepository.openEmailImap` → `importAutocryptKey`): both neighbours would deposit their
     * sender's key in OpenKeychain under the address it claims, from messages nobody opened,
     * replied to, forwarded or decrypted — irreversibly, and against what ENCRYPTION.md:177-180
     * and SECURITY.md say in those words.
     *
     * ⛔ And [failed] is IN HERE, not beside it, for the same reason it is an `&&` and not an
     * `||`: the caller's guard used to read "no reload needed AND we are not in error", so a page
     * left showing an error would reload whether or not it was settled. A warmed neighbour that
     * failed its previous fetch is still a message nobody touched.
     *
     * The rest is unchanged (#92): the account is part of the identity question, because
     * reopening the same id under another account is a DIFFERENT message (see [entryKey]), and an
     * id-only guard returned early and left the previous account's message on screen.
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
     * The entry the reader is SETTLED on: [previous] while the pager is [scrolling], [atRest]
     * otherwise. The caller holds the answer as the MESSAGE, never as its rank.
     *
     * ⛔ Why identity and not a page number. `PagerState.settledPage` is an index, and it is
     * refreshed only around a scroll. When new mail is prepended under an open reader (see
     * [mergeEntries]), the pager re-anchors itself onto the same message — its pages are keyed by
     * identity ([entryKey]) — while that index keeps its old number, which now names the
     * neighbour ABOVE. That neighbour's page then believed itself settled and LOADED: on IMAP a
     * load fetches the whole source and files the sender's announced key in OpenKeychain, from a
     * message nobody opened, under the address it claims, with nothing this app can take back
     * (bench G-décalage, 2026-09-04: a message injected at the top of the mailbox, the reader
     * untouched, and the neighbour's key in the provider inside 30 s). A movement of the list is
     * not a gesture, and only a gesture may load.
     *
     * ⛔ [scrolling] is the whole of what `settledPage` was bought for, and it is kept here: it is
     * true for the entire gesture, drag and fling alike, so a page merely brushed past never
     * becomes the settled one — [previous] stands until the pager comes to rest.
     *
     * ⚠ At rest the answer is [atRest] even when that is `null`: keeping [previous] there would
     * leave the fixed chrome (star, delete, move) wired to a message that is no longer in the
     * list, and acting on it is worse than showing nothing.
     */
    fun <T> settledEntry(previous: T?, atRest: T?, scrolling: Boolean): T? =
        if (scrolling) previous else atRest

    /**
     * The page the pager should open on. Prefer the position of [anchorId] within the
     * ordered entries (robust to the list having shifted since the row was tapped); fall
     * back to [fallbackIndex] when the anchor isn't in the loaded window. The result is
     * always a valid page index (clamped into the list), or 0 for an empty list.
     */
    fun resolveInitialPage(orderedIds: List<String?>, anchorId: String, fallbackIndex: Int): Int {
        if (orderedIds.isEmpty()) return 0
        val found = orderedIds.indexOfFirst { it == anchorId }
        val index = if (found >= 0) found else fallbackIndex
        return index.coerceIn(0, orderedIds.size - 1)
    }

    /**
     * Whether the reader can step back / forward from [page] in a context of [pageCount]
     * messages — i.e. whether the position line's chevrons are live or greyed out. The pager
     * does not wrap and does not run past its context, so both are false at the matching end,
     * and both are false when there is only one message.
     */
    fun hasPrevious(page: Int, pageCount: Int): Boolean = page > 0 && pageCount > 1

    fun hasNext(page: Int, pageCount: Int): Boolean = page < pageCount - 1 && pageCount > 1

    /**
     * Merge the live paged entries into the reading session's sticky entry list.
     *
     * The pager must never drop or shift an entry it has already shown: with the unread
     * filter active, marking the settled message read removes it from the live flow, and
     * a pager that followed the removal would re-bind the settled slot to the next unread,
     * mark it read in turn, and cascade through every unread message. So entries missing
     * from [live] are kept where they were (the session shows the list as it was entered),
     * while genuinely new entries — newly arrived mail prepended, older mail paged in at
     * the end — are inserted at their live position.
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

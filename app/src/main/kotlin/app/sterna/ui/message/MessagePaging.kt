package app.sterna.ui.message

import app.sterna.core.imap.CryptoKind

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
     * Whether a body already in the LOCAL cache may be put on a page the finger has not reached —
     * the single decision behind `MessageViewModel.warmFromCache`, taken on values so it can be
     * executed by a test rather than re-read.
     *
     * Why a warm-up exists at all: a page whose state holds no body composes no `ConversationBody`,
     * so no `WebView`, so the whole rendering staircase (creating the view, `loadDataWithBaseURL`,
     * `onPageFinished`, the height poll capped at 30 × 32 ms) starts only once the finger lands —
     * the ~1 s hourglass at the end of every swipe. Warming from the cache moves all of it before
     * the gesture. ⛔ It moves NOTHING onto the network: a fetch is what `798a5fd1` removed, and
     * what ENCRYPTION.md:177-180 and SECURITY.md promise never happens on an unopened message.
     *
     * Three refusals, all three about what the READER would see on a page she has not swiped to:
     * armour printed in clear, a document painted with holes, a body rebuilt under her finger.
     * ⛔ None of them is what keeps an unopened page off the network — that is the `everActive`
     * latch, named under the third refusal below. Read a refusal as a privacy guard and the guard
     * is somewhere else.
     *
     * ⛔ [cryptoKind] non-null — the body is OpenPGP. Rendering it warm would print the armour, in
     * clear, on a page nobody has swiped to, before any decrypt has been offered. The kind comes
     * from `MailRepository.cryptoKindOf`, the one place that detects PGP; ⛔ it is never
     * re-derived here, or the UI and the repository would drift apart.
     *
     * ⛔ Inline images not settled. This is EXACTLY the condition under which
     * `MailRepository.ensureInlineImages` returns the cached body untouched
     * (`inlineImageParts().isEmpty() || inlineImages.isNotEmpty()`), and it is copied from there
     * on purpose rather than approximated: anything wider warms a document whose images the
     * settle will then download, `html` is memoised on `msg.inlineImages` (MessageScreen), the
     * WebView RELOADS under the finger, and the swipe ends in a white flash — worse than the
     * spinner this replaces. The JMAP prefetch is the case that makes it real: it persists bodies
     * with `inlineImages = emptyMap()`.
     *
     * ⛔ [senderAllowedRemoteImages] — the sender is on the remote-image allowlist. The refusal
     * stays; ⚠ its ORIGINAL reason does not, and the sentence it was written with is the mistake
     * worth keeping in view. It read: before the warm-up an unswiped page held no body, so no
     * WebView and no request at all, and "this refusal is what keeps that true". A refusal is a
     * condition weighed at one instant, never an invariant — it holds only for the bodies it
     * happens to be asked about, and every other route to a warmed body walks straight past it.
     *
     * ⭐ What keeps a page nobody opened from fetching anything is a LATCH, not this refusal:
     * `MessagePage` holds `everActive`, seeded false and set only under the settle flag, one-way
     * (MessageScreen.kt:671-674). BOTH producers of the remote-image answer are gated on it — the
     * per-message override (`val manualShow = everActive && manualShowRaw`, MessageScreen.kt:1604)
     * and the allowlist answer (`val senderAllowed = everActive && …`, :1618) — so a page that was
     * only ever warmed answers `showRemoteImages` false whatever the allowlist and whatever was
     * tapped on it, `blockRemote` stays true (:1742) and `shouldInterceptRequest` blocks (:4899).
     * PRIVACY.md:288 is what that keeps: allow a sender only when you accept that "they learn you
     * opened the message".
     *
     * ⛔ Read the two together, and that is why each names the other: alone, each reads as
     * covered by the other and gets deleted. What is left to THIS refusal is COMFORT, and only
     * that — warm an allowlisted sender's body and the settle flips `blockRemote`, which is half
     * of the WebView's load key (MessageScreen.kt:4643), so the body RELOADS under the finger and
     * the swipe ends in the white flash the warm-up exists to remove. ⚠ And it leaves a mark the
     * reload does NOT clear: `imagesStrip` is memoised on `(full, plainText)`
     * (MessageScreen.kt:1887) and `senderAllowed` is deliberately not one of its keys, so a warmed
     * neighbour decides the strip PRESENT on any body carrying remote refs — the latch is still
     * holding `senderAllowed` false, and that is the input `imagesStripPresent` reads — and
     * at the settle `load` keeps the warmed body (MessageViewModel.kt:1457-1460 and :1516-1519),
     * so `full` never changes, the `remember` is never re-run, and the reader is left with a
     * ~49 dp row (MessageScreen.kt:1867) announcing pictures held back from the one sender whose
     * pictures were never held back. Delete this refusal and every swipe onto an allowed sender
     * flashes white AND keeps a dead strip; delete the latch and a pixel leaves. Both are bugs,
     * only one is a promise broken.
     *
     * ⚠ Its price is accepted, not overlooked: messages from an allowed sender keep the ~1 s
     * hourglass at the end of the swipe.
     * ⛔ The caller must compare LOWER-CASED, exactly as MessageScreen.kt:1618 does (the stored
     * allowlist is lower-cased on write, SettingsRepository.kt:356 and :369). A sender written
     * with a capital otherwise misses this guard and is warmed. Its images stay blocked all the
     * same — the latch answers for that, not this comparison — so what a mismatch costs is
     * exactly the comfort above: that reader's swipe flashes white and keeps a dead strip, on the
     * senders she took the trouble to allow.
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

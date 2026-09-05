package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 *
 * [MessagePagingTest] executes the decision ([MessagePaging.needsLoad]); this pins that the
 * decision is still WIRED to the pager, and above all WHICH VALUE it is handed. A pure function
 * that answers "not settled, so do not load" is worth nothing if the screen calls it with `true`.
 *
 * What rests on these three lines. The pager warms one page each side of the one being read
 * (`beyondViewportPageCount = 1`), so both neighbours of the opened message are composed. On an
 * IMAP account a load fetches the WHOLE source, and a whole source hands the sender's announced
 * public key to the OpenPGP provider (`MailRepository.openEmailImap` → `importAutocryptKey`).
 * With the load ungated, opening one message deposits the keys of its two list neighbours in
 * OpenKeychain — messages nobody opened, replied to, forwarded or decrypted — under the addresses
 * they claim, with no gesture and nothing this app can take back. ENCRYPTION.md:177-180 and
 * SECURITY.md promise the opposite in those words, and the promise is kept HERE, at the call site,
 * not in `core/data`.
 *
 * And what rests on the settled page being held as a MESSAGE rather than as a page number: a page
 * number is refreshed only around a scroll, so mail arriving at the top of the mailbox under a
 * motionless reader left it naming the neighbour ABOVE, which loaded and imported that sender's
 * key with nobody having touched anything (bench G-décalage, 2026-09-04).
 *
 * Read out of the shipped source because a `@Composable` cannot be built on a JVM bench: this
 * module has neither Robolectric nor instrumentation (same last resort, and the same instrument,
 * as [OriginalSenderCallSiteTest] and `PrefetchStaysInTheInboxWiringTest`).
 *
 * ⛔ Whole normalised lines compared by equality, never `contains`: a substring is blind to every
 * mutation that LENGTHENS the line. Complete block comments are removed from wherever they sit on
 * a line, and the line is dropped only when NOTHING is left of it — a comment may make a rule red,
 * never satisfy one.
 */
class LoadOnSettleCallSiteTest {

    /**
     * The load effect, whole and exactly once. ⛔ `active` must appear TWICE on it: as the third
     * argument (the value the guard decides on) and as a KEY of the `LaunchedEffect` (without
     * which the effect never replays at the moment the page settles, and the message the user
     * landed on never loads at all).
     */
    @Test fun `a page loads only once it is the page the reader settled on`() {
        assertEquals(
            "MessagePage's load effect must be this line, verbatim and once. ⚠ An empty " +
                "`but was []` means it is no longer in MessageScreen.kt in this form — renamed, " +
                "reordered, or lengthened. Two things break if it is: passing `true` (or " +
                "anything but `active`) as the third argument reloads both warmed neighbours of " +
                "the opened message, and on IMAP each of those loads plants the sender's " +
                "announced key in OpenKeychain with nothing opened; and dropping `active` from " +
                "the effect's KEYS leaves the effect never replayed when the page finally " +
                "settles, so the message the reader tapped stays on a spinner.",
            listOf(LOAD_EFFECT),
            screenLines().filter { it == LOAD_EFFECT },
        )
    }

    /**
     * And where that `active` comes from: the settled MESSAGE, compared by identity, pinned
     * inside the `MessagePage(` call that receives it so the argument cannot be re-pointed at a
     * neighbour.
     */
    @Test fun `active is the settled message compared by identity, inside the call that receives it`() {
        assertEquals(
            "the pager must hand MessagePage `active = pagerKey(entry) == settledKey,` — these " +
                "four code lines, in this order, starting at `MessagePage(`. ⛔ The mutation " +
                "this exists for is the return to a comparison of RANK — " +
                "`pagerState.settledPage == page` or `pagerState.currentPage == page`. Both " +
                "reopen G-décalage: a page number is refreshed only around a scroll, so mail " +
                "prepended under an open reader leaves it naming the neighbour ABOVE, which " +
                "then loads and, on IMAP, files that sender's key in OpenKeychain from a " +
                "message nobody opened. `currentPage == page` additionally counts a page merely " +
                "brushed past, mid-drag.",
            listOf(
                "MessagePage(",
                "emailId = entry.first,",
                "accountId = entry.second,",
                ACTIVE_ARGUMENT,
            ),
            adjacentBlock(screenLines(), "MessagePage(", 4),
        )
    }

    /**
     * ⛔ The inventory, and it covers EVERY name by which this screen may put a message on a page,
     * the cache warm-up included. Restricted to `viewModel.load(`, as it was, a method called
     * `warmFromCache(` passed underneath it unseen and the whole rule became decoration: the
     * warm-up is a second door into the same ViewModel, and it is opened on the neighbours.
     *
     * A fourth line is a load nobody audited — an effect keyed on something else, a lifecycle
     * callback, a refresh — and every unaudited load on IMAP is an unopened key import. The retry
     * button is pinned with them because it is the one call site that MUST pass `settled = true`:
     * a tap on it is a gesture, and its page is by definition the one on screen.
     */
    @Test fun `only these three lines put a message on a page at all`() {
        assertEquals(
            "MessageScreen.kt may name a message-loading method of the page ViewModel on exactly " +
                "three lines, these, in this order: the cache warm-up, the pager's settle-gated " +
                "load, and the Retry button. Fewer means a call site was deleted; more means a " +
                "way onto the page that no rule here gates — and on IMAP a load that nobody " +
                "gated files the sender's announced key in OpenKeychain from a message nobody " +
                "opened.",
            listOf(WARM_EFFECT, LOAD_EFFECT, RETRY_CALL),
            screenLines().filter { line -> PAGE_ENTRY_POINTS.any { it in line } },
        )
    }

    /**
     * The warm-up effect, whole, and ABOVE the load effect with nothing between them.
     *
     * ⛔ It must NOT take `active` as a key or as an argument: both neighbours are warmed, which
     * is the entire point — a warm-up gated on `active` runs only on the page already on screen
     * and buys nothing. And it must stay above the load effect: below it, the settled page's
     * `load` would already have wiped `_messages` and the warm-up would then paint a body over a
     * fetch in flight.
     */
    @Test fun `the cache warm-up stands directly above the load effect`() {
        assertEquals(
            "MessagePage must open on these two adjacent code lines, in this order: the cache " +
                "warm-up, then the settle-gated load. ⚠ An empty `but was []` means the warm-up " +
                "line is no longer in MessageScreen.kt in this exact form — and then every " +
                "neighbour is back to a blank page and a ~1 s spinner at the end of each swipe, " +
                "with the rest of this file green. A line appearing between them means the " +
                "warm-up became conditional on something no rule here reads.",
            listOf(WARM_EFFECT, LOAD_EFFECT),
            adjacentBlock(screenLines(), WARM_EFFECT, 2),
        )
    }

    /**
     * ⛔ THE promise this volet is allowed to exist under: the warm-up reads the LOCAL CACHE and
     * nothing else. Every name below reaches the network, and on IMAP a fetched source hands the
     * sender's announced key to the OpenPGP provider (`importAutocryptKey`) from a message nobody
     * opened — which is exactly why `798a5fd1` removed the previous warm-up, and what
     * ENCRYPTION.md:177-180 and SECURITY.md promise in those words.
     *
     * The whole body of the function is inventoried, not its first lines: a helper called from it
     * would move the fetch one frame away and leave this green, so the rule is stated on the
     * names and a reviewer must keep the body flat.
     */
    @Test fun `the warm-up names no fetch of any kind`() {
        assertEquals(
            "MessageViewModel.warmFromCache may not name any of $FETCHING_NAMES. Each of them " +
                "goes to the server for a body, and on IMAP the fetched source deposits the " +
                "sender's announced key in OpenKeychain, irreversibly, for a message the reader " +
                "has not swiped to. The warm-up is allowed to exist ONLY because it reads " +
                "`repo.cachedMessage` and stops when the cache is empty.",
            emptyList<String>(),
            warmBodyLines().filter { line -> FETCHING_NAMES.any { it in line } },
        )
    }

    /**
     * ⛔ RISK Nº 1 OF THE VOLET, and it loses the message silently. `loadedId` is what
     * [MessagePaging.needsLoad] reads to answer "already loaded?". If the warm-up writes it, a
     * neighbour whose body is NOT in the cache is marked as loaded without ever being fetched:
     * settling on it returns early from `load`, and the reader sits in front of an empty page
     * for good, with no error and no spinner.
     */
    @Test fun `only load writes the identity that says a message is already loaded`() {
        assertEquals(
            "MessageViewModel.kt may assign `loadedId` on exactly one line: `load`'s own. A " +
                "second assignment — the cache warm-up marking the page loaded — makes " +
                "MessagePaging.needsLoad answer `false` for a message that was never fetched: " +
                "settling on it loads NOTHING and the page stays blank forever. Every other rule " +
                "in this file, and MessagePagingTest, stay green.",
            listOf(LOADED_ID_WRITE),
            bindingsOf(viewModelLines(), "loadedId"),
        )
    }

    /**
     * ⛔ The middle link of the warm-up, and the only place the decision becomes a refusal.
     * [MessagePaging.warmable] is green against any arguments, so what counts is what is handed to
     * it AND that its answer is acted on. Passing `null` for the crypto kind paints PGP armour on
     * an unopened page; passing `cachedInlineImages = 1` unconditionally warms a body whose images
     * are still missing; dropping the sender argument warms a body whose remote images the reader
     * has already allowed, which fetches them from a page nobody swiped to; and deleting the
     * `return@launch` throws all three answers away while every other rule here stays green.
     */
    @Test fun `the warm-up asks the decision with the cached body's own values, and obeys it`() {
        assertEquals(
            "MessageViewModel.warmFromCache must consult MessagePaging.warmable on exactly these " +
                "code lines, adjacent and in this order, and RETURN on a refusal. ⚠ An empty " +
                "`but was []` means the call was renamed, moved or reshaped. What the damage is, " +
                "one line at a time. Without `if (!warmable) return@launch` the answer is " +
                "computed and dropped: OpenPGP armour prints in clear on a page nobody swiped " +
                "to, and a body from the JMAP prefetch (`inlineImages = emptyMap()`) is painted " +
                "with its holes and RELOADS under the finger. Without the sender argument — " +
                "lower-cased, against the allowlist as MessageScreen compares it — an allowed " +
                "sender's body is warmed on an unswiped page, its WebView composes, its remote " +
                "images are not blocked, and the tracking pixel reports a message nobody opened " +
                "(PRIVACY.md:288). And `repo.cryptoKindOf` is the single source of PGP detection " +
                "(⛔ never re-derived in the UI layer), while the two counts are exactly what " +
                "`MailRepository.ensureInlineImages` reads to decide it has nothing to download.",
            WARMABLE_CALL,
            adjacentBlock(viewModelLines(), WARMABLE_CALL.first(), WARMABLE_CALL.size),
        )
    }

    /**
     * ⭐ THE VOLET ITSELF, and without these two blocks it corrects nothing at all. `load` erased
     * the body in two places — the prologue, and the header-only repaint inside the coroutine. A
     * warmed neighbour that is erased at the instant the finger lands takes its WebView out of
     * composition, and the ~1 s spinner comes back unchanged while every other rule here stays
     * green.
     *
     * ⛔ The condition is pinned WITH its binding: `val warm = warmedId == emailId &&
     * warmedAccountId == accountId`. Dropping the account half re-opens #92 — two accounts of one
     * server carry the same id, and the page would keep the OTHER account's warmed body.
     */
    @Test fun `load keeps a body the warm-up put there, for this account and this id`() {
        assertEquals(
            "`warm` must be bound on exactly this line. Bound on the id alone, a neighbour from " +
                "another account of the same server keeps the wrong body (#92); bound to `false`, " +
                "the warm-up is erased the moment the finger lands and the swipe spinner returns.",
            listOf(WARM_BINDING),
            bindingsOf(viewModelLines(), "warm"),
        )
        assertEquals(
            "load()'s prologue must guard its two erasing lines with `if (!warm) {`. Unguarded, " +
                "the settle wipes the warmed body and the WebView leaves composition exactly " +
                "when the reader arrives — the whole defect, back, with this file otherwise green.",
            PROLOGUE_ERASURE,
            adjacentBlock(viewModelLines(), PROLOGUE_ERASURE.first(), PROLOGUE_ERASURE.size),
        )
        assertEquals(
            "load()'s cached-row repaint must be guarded the same way: it rewrites `_messages` " +
                "HEADER-ONLY (body = null), which drops the WebView just as surely as the " +
                "prologue does. ⛔ Only these two lines are conditional — the mailbox role, the " +
                "own-message flag, the delivered-to address, the cover subject and the mailbox id " +
                "below them must stay unconditional.",
            REPAINT_ERASURE,
            adjacentBlock(viewModelLines(), REPAINT_ERASURE.first(), REPAINT_ERASURE.size),
        )
    }

    /**
     * ⛔ T4 — an inventory proves a call EXISTS, never that it is REACHED. Wrapping the load
     * effect in any `if (…) { … }` leaves every text rule above it green while the load stops
     * happening (or, worse, happens under a condition nobody audited). So the effect is pinned
     * with its NEIGHBOUR: the read-marking effect must follow it immediately, with no code line
     * of any kind in between.
     *
     * ⛔ And with that neighbour's BODY, not merely its opening line. The flag governs TWO things
     * — the page that loads and the page that marks its message read — and pinning only
     * `LaunchedEffect(active) {` let them diverge: `viewModel.onActiveChanged(true)` compiles,
     * nothing else in this repository names `onActiveChanged`, and the whole suite stays green.
     */
    @Test fun `the load effect stands unguarded, next to the read-marking effect`() {
        assertEquals(
            "MessagePage's two effects must be these three adjacent code lines, in this order. A " +
                "line appearing between them — an `if (…) {`, a `when`, a `remember` — means the " +
                "load is now conditional on something no rule in this file reads: either the " +
                "message never loads, or it loads on a page the reader never settled on, which " +
                "on IMAP deposits that sender's key in OpenKeychain untouched. ⛔ The third line " +
                "is the read-marking relay, and it must forward `active` ITSELF: with " +
                "`onActiveChanged(true)` a page stays \"active\" after the reader has swiped off " +
                "it — the message she LEFT is marked read at the server, `settleMarking` is " +
                "written, and `maybeOfferReadReceipt()` puts the read-receipt question for a " +
                "message she never stopped on, which SECURITY.md forbids in those words. Loading " +
                "and marking read must stay the SAME flag.",
            listOf(LOAD_EFFECT, "LaunchedEffect(active) {", "viewModel.onActiveChanged(active)"),
            adjacentBlock(screenLines(), LOAD_EFFECT, 3),
        )
    }

    /**
     * ⛔ T3 — and a pin on a NAME is worth nothing without a pin on what that name is BOUND to.
     * `val active = true` inserted anywhere above the effect shadows the parameter: the pinned
     * line stays identical to the character, all three rules above stay green, and every page
     * loads again. Kotlin only warns about the shadowing, and this repository wires neither
     * `allWarningsAsErrors` nor ktlint nor detekt to turn that warning into a failure — the same
     * lesson `PrefetchStaysInTheInboxWiringTest` paid for with its `bindingsOf`.
     *
     * ⚠ The second binding is legitimate and must stay: `val active = activeMessage` is the fixed
     * chrome's own notion, in another function, and this rule pins it rather than forbidding it.
     */
    @Test fun `the whole screen binds the name active exactly twice, and to these`() {
        assertEquals(
            "MessageScreen.kt may bind `active` on exactly these two lines: the pager's argument " +
                "(the settled-page test) and the chrome's `val active = activeMessage`. A THIRD " +
                "binding is how the settle gate is defeated without touching a single pinned " +
                "line — `val active = true` above MessagePage's effects shadows the parameter, " +
                "Kotlin merely warns, and both neighbours of the opened message load and import " +
                "their sender's key again.",
            listOf(ACTIVE_ARGUMENT, "val active = activeMessage"),
            bindingsOf(screenLines(), "active"),
        )
    }

    /**
     * ⛔ T1 and T2 — the MIDDLE LINK, which nothing watched: the screen passes `active`, the pure
     * function decides on `settled`, and between them sits `MessageViewModel.load`, free to hand
     * the decision anything at all. `needsLoad(true, …)` there re-enables the neighbour loads with
     * [MessagePagingTest] and every rule above it green; `val failed = false` there leaves the
     * Retry button inert forever (settled, same id, nothing "failed" ⇒ the guard refuses), and
     * the error screen never reloads again.
     *
     * Four code lines, adjacent, from the declaration: the signature (⛔ no default on `settled`,
     * and none on `accountId` either — a default in the middle lets `load(id, settled = true)`
     * compile and silently drop the account), the error reading, the guard forwarding BOTH, and
     * the `return` that makes it a guard rather than a comment.
     */
    @Test fun `the ViewModel forwards the page's own settled and failed to the decision`() {
        assertEquals(
            "MessageViewModel.load must open on exactly these four code lines. ⛔ Read `but was` " +
                "closely: `needsLoad(true, …)` re-arms the unopened key import on both warmed " +
                "neighbours, and `val failed = false` makes the Retry button do nothing at all — " +
                "both leave MessagePagingTest and every source rule in this file green, because " +
                "neither the pure function nor MessageScreen.kt changes.",
            listOf(
                LOAD_DECLARATION,
                FAILED_BINDING,
                LOAD_GUARD,
                "return",
            ),
            adjacentBlock(viewModelLines(), LOAD_DECLARATION, 4),
        )
    }

    /** And the decision is consulted THERE and nowhere else in the ViewModel: a second call, on
     *  another path, is a load this file's reasoning does not cover. */
    @Test fun `the ViewModel asks the decision on exactly one line`() {
        assertEquals(
            "MessageViewModel.kt may name `MessagePaging.needsLoad(` on exactly one line, the " +
                "guard of `load`. A second is another load path, gated by arguments nobody read.",
            listOf(LOAD_GUARD),
            viewModelLines().filter { "MessagePaging.needsLoad(" in it },
        )
    }

    /**
     * ⛔ G-décalage, 2026-09-04 — the stale index must not be named ANYWHERE in the screen.
     * `PagerState.settledPage` is refreshed only around a scroll; a list that moves underneath a
     * motionless reader leaves it pointing one row off. It is not a value to be used carefully,
     * it is the defect, so the inventory of its uses is empty.
     */
    @Test fun `the screen never names the stale settled page index`() {
        assertEquals(
            "MessageScreen.kt may not name `pagerState.settledPage` on any line. It is an INDEX, " +
                "refreshed only around a scroll: when new mail is prepended under an open " +
                "reader the pager re-anchors onto the same message (its pages are keyed by " +
                "identity) while that index keeps its old number, which now names the neighbour " +
                "ABOVE. That neighbour's page believes itself settled and LOADS — on IMAP a " +
                "load files the sender's announced key in OpenKeychain, from a message nobody " +
                "opened. Hold the settled page as the MESSAGE, via MessagePaging.settledEntry.",
            emptyList<String>(),
            screenLines().filter { "pagerState.settledPage" in it },
        )
    }

    /**
     * ⛔ And the replacement, `currentPage`, is a live index too: it moves as soon as a drag
     * crosses the halfway point. The whole design rests on it being read ONLY at rest — behind
     * `isScrollInProgress` — so the reads themselves are inventoried.
     */
    @Test fun `currentPage is read on exactly these lines, and nowhere else`() {
        assertEquals(
            "MessageScreen.kt may read `pagerState.currentPage` on exactly these lines, in this " +
                "order: the settled entry's initial value (the message the reader opened on, " +
                "which must load), the snapshot pair that carries it with " +
                "`isScrollInProgress`, the page number handed to the reading pane's anchor " +
                "(#103), and the position line's counter. A read MORE, outside the " +
                "`isScrollInProgress` guard, makes a page merely brushed past count as settled: " +
                "it loads, marks itself read, and on IMAP imports its sender's key. A read " +
                "FEWER means the settled page is no longer tracked at all.",
            CURRENT_PAGE_READS,
            screenLines().filter { "pagerState.currentPage" in it },
        )
    }

    /**
     * ⛔ The middle link on THIS side: what is handed to the pure function. `settledEntry` is
     * green against every argument, so the rule that matters is which entry travels into it.
     */
    @Test fun `the settled entry is held from the page at rest, by identity`() {
        assertEquals(
            "these four code lines, adjacent and in this order, hold the settled page. ⛔ It is " +
                "`entryAt(page)` — the entry at the page the pager came to REST on — that " +
                "carries the identity into the decision. Re-pointing it at " +
                "`entryAt(pagerState.settledPage)` reopens G-décalage with " +
                "MessagePaging.settledEntry still green, because the pure function only ever " +
                "sees the entry it is given. Dropping `scrolling` (or passing `false`) makes a " +
                "page brushed past mid-gesture settle, which is what `settledPage` used to buy.",
            SETTLED_HOLD,
            adjacentBlock(screenLines(), SETTLED_HOLD.first(), SETTLED_HOLD.size),
        )
    }

    /**
     * ⛔ And the key the pages compare themselves against is bound ONCE, to the settled entry.
     * A second binding — `val settledKey = pagerKey(entry)` inside the pager's page lambda — makes
     * every page active, with the pinned `active = pagerKey(entry) == settledKey,` untouched.
     */
    @Test fun `settledKey is bound exactly once, to the settled entry`() {
        assertEquals(
            "MessageScreen.kt may bind `settledKey` exactly once, to `settledEntry?.let" +
                "(::pagerKey)`. Bound to anything else — or a second time, shadowing it inside " +
                "the page lambda — every page compares equal to itself, becomes active, loads, " +
                "and on IMAP deposits its sender's key in OpenKeychain: exactly the state this " +
                "file exists to prevent, with every other rule in it still green.",
            listOf(SETTLED_KEY),
            bindingsOf(screenLines(), "settledKey"),
        )
    }

    /**
     * ⛔ The identity itself, body included. Before this volet `active` compared two `Int`s and
     * never went through `pagerKey`; now the WHOLE load decision does, and nothing read that
     * body: [MessagePagingTest] exercises `MessagePaging.entryKey` directly, and every rule above
     * pins the CALL. `MessagePaging.entryKey(entry.first, null)` there leaves this file, that
     * one, and the whole suite green.
     */
    @Test fun `the pager's identity is the account and the id, not the id alone`() {
        assertEquals(
            "`pagerKey` must be these two code lines, adjacent and in this order. ⛔ Its body is " +
                "now what the entire load decision rests on. Drop the account from it — " +
                "`entry.second` → `null`, or a bare `entry.first` — and two messages held by two " +
                "accounts of one server under the same id collapse into ONE identity again " +
                "(#92): the neighbour compares equal to the settled message, becomes active, " +
                "loads, and on IMAP files its sender's announced key in OpenKeychain under an " +
                "address nobody opened a message from.",
            listOf(PAGER_KEY_DECLARATION, PAGER_KEY_BODY),
            adjacentBlock(screenLines(), PAGER_KEY_DECLARATION, 2),
        )
    }

    /**
     * ⛔ The `scrolling` guard can be undone from OUTSIDE the pure function, and every test of
     * [MessagePaging.settledEntry] stays green while it is: a second `settledEntry = entryAt(page)`
     * inside the same `collect`, after the decision, simply overwrites the answer — a page merely
     * brushed past becomes settled, loads, marks itself read and imports its sender's key. The
     * adjacency rule above cannot see it (it compares a fixed number of lines from its anchor, and
     * nothing forbids a fifth), so the writes are inventoried instead.
     *
     * ⚠ The seed — `var settledEntry by remember { … }` — is deliberately NOT in this list: it
     * binds through `by`, not `=`, so [bindingsOf] does not see it. It is pinned by the
     * `currentPage` inventory instead. Do not "fix" that here.
     */
    @Test fun `the settled entry is written on exactly one line`() {
        assertEquals(
            "MessageScreen.kt may assign `settledEntry` on exactly one line: the decision inside " +
                "the collect. A SECOND write in that block — `settledEntry = entryAt(page)` " +
                "after it — defeats the `scrolling` guard from outside the pure function, which " +
                "stays green because it is still called and still answers correctly; its answer " +
                "is simply thrown away. A page flicked past then settles, loads, marks itself " +
                "read, and on IMAP deposits its sender's key in OpenKeychain.",
            listOf(SETTLED_WRITE),
            bindingsOf(screenLines(), "settledEntry"),
        )
    }

    // --- reading the source ----------------------------------------------------------------------

    private fun screenLines(): List<String> = codeLines(screen().readText())

    private fun viewModelLines(): List<String> = codeLines(viewModel().readText())

    /**
     * The code lines of `fun warmFromCache(…)` only, from its declaration to the next member
     * declared at class indentation. Empty when the function is gone, which reddens the rule that
     * reads it rather than satisfying it against nothing.
     */
    private fun warmBodyLines(): List<String> {
        val text = viewModel().readText()
        val start = text.indexOf("fun warmFromCache(")
        // ⛔ Named after a forbidden call on purpose: a vanished warm-up must redden the rule
        // that reads this, not satisfy it against an empty list.
        if (start < 0) return listOf("warmFromCache is gone from MessageViewModel.kt: openMessage(")
        val end = NEXT_MEMBER.find(text, start + 1)?.range?.first ?: text.length
        return codeLines(text.substring(start, end))
    }

    /**
     * Every code line of [lines] that BINDS the name [name] — a `val`/`var` declaration, a bare
     * assignment, or a named argument spelling it — never a comparison (`==` is excluded).
     *
     * ⛔ Why bindings and not just "the line is still there": a line that is still present and no
     * longer decides anything satisfies every text pin in this file. Shadowing the name it reads
     * is the cheapest way to arrange that, and it is invisible to all of them.
     */
    private fun bindingsOf(lines: List<String>, name: String): List<String> =
        lines.filter { Regex("""^(val\s+|var\s+)?$name\s*=(?!=)""").containsMatchIn(it) }

    /**
     * [text]'s lines as CODE: every COMPLETE block comment removed from wherever it sits on the
     * line, runs of whitespace collapsed to one, and the line dropped only when nothing is left.
     *
     * ⛔ Dropping any line that merely STARTS with a comment opener is a hole, not a detail: an
     * empty block comment opened and closed at once, followed by a real statement, is valid Kotlin
     * and would take the statement away with it — see `PrefetchStaysInTheInboxWiringTest`, where
     * exactly that hid an unopened key import from every inventory in the file.
     */
    private fun codeLines(text: String): List<String> = text.lines()
        .map { WHITESPACE.replace(BLOCK_COMMENT.replace(it, " "), " ").trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * [count] lines starting at the single line equal to [needle]. Empty when [needle] is not
     * there exactly once, so a rename or a second call reddens the rule that uses it instead of
     * quietly satisfying it against nothing.
     */
    private fun adjacentBlock(lines: List<String>, needle: String, count: Int): List<String> {
        val at = lines.indices.filter { lines[it] == needle }.singleOrNull() ?: return emptyList()
        return if (at + count > lines.size) emptyList() else lines.subList(at, at + count)
    }

    private fun screen(): File = locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val LOAD_EFFECT =
            "LaunchedEffect(emailId, accountId, active) { viewModel.load(emailId, accountId, active) }"

        const val WARM_EFFECT =
            "LaunchedEffect(emailId, accountId) { viewModel.warmFromCache(emailId, accountId) }"

        /** Every name by which MessageScreen.kt may put a message on a page's ViewModel. */
        val PAGE_ENTRY_POINTS = listOf("viewModel.load(", "viewModel.warmFromCache(")

        /** Every name in the repository that goes to the SERVER for a body. */
        val FETCHING_NAMES = listOf(
            "openMessage(",
            "openEmail(",
            "openEmailImap(",
            "fetchEmail(",
            "ensureInlineImages(",
        )

        const val LOADED_ID_WRITE = "loadedId = emailId"

        const val WARM_BINDING = "val warm = warmedId == emailId && warmedAccountId == accountId"

        /**
         * The consultation of the pure warm/no-warm decision, argument by argument — AND the line
         * that OBEYS it. ⛔ The obedience belongs in the same block, not in a rule of its own:
         * pinning the call alone left `if (!warmable) return@launch` deletable with every rule in
         * this file and every case in `MessagePagingTest` still green.
         */
        val WARMABLE_CALL = listOf(
            "val warmable = MessagePaging.warmable(",
            "cryptoKind = repo.cryptoKindOf(cached.email),",
            "inlineParts = cached.email.inlineImageParts().size,",
            "cachedInlineImages = cached.inlineImages.size,",
            "senderAllowedRemoteImages =",
            "cached.email.from.firstOrNull()?.email?.lowercase()?.let { it in settings.imageAllowlist.first() } == true,",
            ")",
            "if (!warmable) return@launch",
        )

        /** load()'s prologue erasure, anchored on the unique line above it. */
        val PROLOGUE_ERASURE = listOf(
            "settleMarking = null",
            "if (!warm) {",
            "_state.value = MessageState.Loading",
            "_messages.value = emptyList()",
        )

        /** load()'s header-only repaint, anchored on the unique line that opens it. */
        val REPAINT_ERASURE = listOf(
            "listEmail?.let { cached ->",
            "if (!warm) {",
            "_messages.value = listOf(ThreadMessage(id = cached.id, header = cached))",
            "_state.value = MessageState.Loaded(cached)",
        )

        /** The next member declared at class indentation, which is where a function ends. */
        val NEXT_MEMBER = Regex("""\n {4}(private )?(suspend )?fun \w""")

        const val ACTIVE_ARGUMENT = "active = pagerKey(entry) == settledKey,"

        const val SETTLED_KEY = "val settledKey = settledEntry?.let(::pagerKey)"

        const val SETTLED_WRITE =
            "settledEntry = MessagePaging.settledEntry(settledEntry, entryAt(page), scrolling)"

        const val PAGER_KEY_DECLARATION =
            "private fun pagerKey(entry: Pair<String, String?>): String ="

        const val PAGER_KEY_BODY = "MessagePaging.entryKey(entry.first, entry.second)"

        /** The four code lines that hold the settled page, from the effect that opens them. */
        val SETTLED_HOLD = listOf(
            "LaunchedEffect(pagerState) {",
            "snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }",
            ".collect { (scrolling, page) ->",
            SETTLED_WRITE,
        )

        /** Every read of `pagerState.currentPage` in the file, in source order. */
        val CURRENT_PAGE_READS = listOf(
            "var settledEntry by remember { mutableStateOf(entryAt(pagerState.currentPage)) }",
            "snapshotFlow { pagerState.isScrollInProgress to pagerState.currentPage }",
            "else onPageSettled?.invoke(settled.first, settled.second, pagerState.currentPage)",
            "page = pagerState.currentPage,",
        )

        const val RETRY_CALL = "Button(onClick = { viewModel.load(emailId, accountId, settled = true) }) {"

        const val LOAD_DECLARATION = "fun load(emailId: String, accountId: String?, settled: Boolean) {"

        const val FAILED_BINDING = "val failed = _state.value is MessageState.Error"

        const val LOAD_GUARD =
            "if (!MessagePaging.needsLoad(settled, loadedId, this.accountId, emailId, accountId, failed)) {"

        /** A block comment that OPENS AND CLOSES on the same line — the only kind that can sit
         *  beside code, and therefore the only kind [codeLines] may remove rather than obey. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

        val WHITESPACE = Regex("""\s+""")
    }
}

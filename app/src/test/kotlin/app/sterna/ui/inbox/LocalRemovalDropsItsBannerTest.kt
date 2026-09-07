package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A message removed FROM SterNA must take its own notification banner with it.
 *
 * The report: a banner is posted, the user deletes / archives / moves that message in the app, and
 * the banner stays in the status bar — for good on IMAP (`FolderRefresh.departedIds` is empty by
 * construction there, so `confirmDepartures` never sees the departure), late (8–60 s, background
 * only) on JMAP, and never at all on JMAP once `markReadOnDelete` is on. Touching what is left
 * reopens a message that is not there any more. Nothing in the tree cancelled a banner from a LOCAL
 * gesture: the only two `Notifications.dismiss` callers are the "mark read" paths.
 *
 * The fix does it beside the sync rather than through it — `departedIds` may only ever be fed
 * from an AUTHORITATIVE signal (see `Notifications.departuresToCancel`'s KDoc) — by remembering the
 * (account, id) couple AS IT WAS AT THE GESTURE and taking the banners down when the Undo window
 * closes unused.
 *
 * Two instruments, on purpose:
 *
 *  - [undoBannerKeys] is the DECISION, and it is EXECUTED below. It is the only thing that says
 *    which banner a gesture takes down, and the two ways to get it wrong are silent: the id of the
 *    gesture (IMAP ids ARE `imap:account:folder:uid`, so a move rewrites them and the Undo writes a
 *    third one — only the captured id names the banner), and the account qualifying it (#92: two
 *    accounts on one server are routinely handed the same message id, and a bare id cancels the
 *    neighbour's banner).
 *  - the rest is a SOURCE LINT, same instrument and same disclaimer as [SelectionTargetsWiringTest]
 *    and [BulkSelectionWiringTest]: `InboxViewModel` is an `AndroidViewModel` no JVM test here can
 *    instantiate (no Robolectric, no instrumented tests), so WHERE the deadline is posted is read as
 *    text. Whole lines, compared as lists, never `in`/`contains` — a substring rule is blind to
 *    every mutation that lengthens the line it matched.
 */
class LocalRemovalDropsItsBannerTest {

    // -- the decision, executed --------------------------------------------------------------

    /**
     * The id is the one CAPTURED AT THE GESTURE, i.e. `UndoEntry.emailId` — not the source folder,
     * not the destination. On IMAP the id contains the folder, so the message wears a different id
     * after the move and a third one after an Undo; the banner on screen only ever answers to the
     * one that was live when it was posted.
     */
    @Test fun `the key carries the id the gesture was performed with`() {
        val entry = UndoEntry("imap:accA:INBOX:12", "accA", "imap:accA:INBOX", "imap:accA:Trash")
        assertEquals(listOf(EmailKey("accA", "imap:accA:INBOX:12")), undoBannerKeys(listOf(entry)))
    }

    /**
     * And the account of THAT entry. A thread swipe or a selection spans accounts; each entry's
     * banner lives on its own account's shade, and an id qualified with the wrong account cancels a
     * banner nobody asked to lose.
     */
    @Test fun `each entry keeps its own account`() {
        val entries = listOf(
            UndoEntry("m1", "accA", "mbA", "trashA"),
            UndoEntry("m1", "accB", "mbB", "trashB"),
            UndoEntry("m2", "accB", "mbB", null),
        )
        assertEquals(
            listOf(
                EmailKey("accA", "m1"),
                EmailKey("accB", "m1"),
                EmailKey("accB", "m2"),
            ),
            undoBannerKeys(entries),
        )
    }

    /**
     * A null account is NOT an account to invent. `UndoEntry.accountId` is nullable and the
     * dismissal adapter resolves null through `store.load()` (the current account) exactly as every
     * other key path does; filling it in here with a guess would aim the cancel at a shade the
     * gesture never touched.
     */
    @Test fun `a null account stays null`() {
        assertEquals(listOf(EmailKey(null, "m1")), undoBannerKeys(listOf(UndoEntry("m1", null, "mbA"))))
    }

    @Test fun `no entries, no keys`() {
        assertEquals(emptyList<EmailKey>(), undoBannerKeys(emptyList()))
    }

    // -- where the deadline is posted, read as text -------------------------------------------

    /**
     * The three sites are exactly the three that offer an Undo, and the deadline is posted THERE,
     * on the line right after the offer — so the pair is pinned whole, in order.
     *
     * `swipeRemove` is the one that matters most: it is network-first, so offline the op fails, the
     * row comes back and there was no departure at all. A deadline posted before the acknowledgement
     * would take the banner down for a gesture that never happened, and the two errors are not
     * symmetrical — one banner too many is a banner the user dismisses, one banner too few is mail
     * that was never announced.
     */
    @Test fun `the swipe posts its deadline in the success arm, right after the Undo it belongs to`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun swipeRemove(")
        assertEquals(
            "swipeRemove() must post exactly one banner deadline. Body was:\n$body",
            listOf(DEADLINE),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "the deadline must sit in the .onSuccess arm, immediately after the Undo it belongs " +
                "to — never before the runCatching, never in .onFailure: offline the op fails, the " +
                "row is restored, and the banner would be gone for a departure that never " +
                "happened. Body was:\n$body",
            listOf(
                "_undo.value = UndoAction(listOf(UndoEntry(email.id, email.accountId, source, dest)), label)",
                DEADLINE,
            ),
            deadlineWithItsNeighbour(body),
        )
    }

    @Test fun `the thread swipe posts its deadline right after the Undo it belongs to`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun threadSwipeRemove(")
        assertEquals(
            "threadSwipeRemove() must post exactly one banner deadline. Body was:\n$body",
            listOf(DEADLINE),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "the deadline must follow the Undo offer, which is built from the entries the server " +
                "ACKNOWLEDGED (the .onSuccess arm feeds them). Body was:\n$body",
            listOf(
                "_undo.value = UndoAction(entries, getApplication<Application>().getString(labelRes))",
                DEADLINE,
            ),
            deadlineWithItsNeighbour(body),
        )
    }

    @Test fun `the batched selection posts its deadline right after the Undo it belongs to`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun bulkBatched(")
        assertEquals(
            "bulkBatched() must post exactly one banner deadline. Body was:\n$body",
            listOf(DEADLINE),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "the deadline must follow the Undo offer, i.e. the messages the SERVER wrote. " +
                "Body was:\n$body",
            listOf("_undo.value = UndoAction(undoEntries, label)", DEADLINE),
            deadlineWithItsNeighbour(body),
        )
    }

    /**
     * The deadline is a `viewModelScope` coroutine, and its body is pinned as a SEQUENCE — every
     * line, in the order the source has them, never a set of independent greps recollés in the
     * order of the expectation. Three of these lines can be moved without breaking any rule that
     * only asks whether they EXIST, and each move inverts the whole promise:
     *
     *  - `dismissBanners(keys)` lifted above the `delay`: the banner falls AT THE GESTURE, and no
     *    Undo can ever keep it. The branch would then do the exact opposite of what it says;
     *  - `_undo.value = null` written unconditionally: a SECOND gesture performed inside the window
     *    gets its own Undo window wiped by the first one's deadline. Identity (`===`) on the very
     *    action this deadline was posted for, and nothing else;
     *  - the clear dropped altogether: the bar is `Indefinite` now, so it would hang for good.
     *
     * NOT an effect of the screen: `clearUndo()` has ONE caller, the "no Undo" branch of the
     * snackbar in `InboxScreen.kt`, and when the list leaves the composition the `LaunchedEffect` is
     * cancelled and NEITHER branch runs. Hung there, the banner of a message deleted just before
     * leaving the list never comes down at all — the very defect this branch closes.
     */
    @Test fun `the deadline waits, closes its own window, then dismisses — in that order`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "private fun scheduleUndoneDeparture()")
        assertEquals(
            "the deadline's body is pinned WHOLE and IN ORDER: it must wait the named window, " +
                "close the very window it was posted for (identity, not equality), and only then " +
                "take the banners down. Any other order or any missing line reverses what the " +
                "branch promises. Body was:\n$body",
            listOf(
                "private fun scheduleUndoneDeparture() {",
                "val action = _undo.value ?: return",
                "val keys = undoBannerKeys(action.entries)",
                "undoDepartureJob = viewModelScope.launch {",
                "delay(UNDO_BANNER_DISMISS_MS)",
                "if (_undo.value === action) _undo.value = null",
                "dismissBanners(keys)",
                "}",
                "}",
            ),
            normalised(body),
        )
    }

    /**
     * And the window has a VALUE, not just a name. `UNDO_BANNER_DISMISS_MS = 0L` is one character
     * and passes every rule that pins the identifier: the banner would fall the instant the server
     * acknowledged, with the Undo bar still on screen offering something that can no longer work.
     *
     * Pinned whole, so a rewrite that keeps `6_000L` in the line and appends to it is caught too.
     */
    @Test fun `the window is a real value, not just a name`() {
        assertEquals(
            "the Undo window must be a named constant with an actual duration behind it: the " +
                "snackbar is Indefinite now, so this value alone decides how long the user has.",
            listOf("private const val UNDO_BANNER_DISMISS_MS = 6_000L"),
            fileLinesNaming(INBOX_VIEW_MODEL, "UNDO_BANNER_DISMISS_MS ="),
        )
    }

    /**
     * ONE clock, and it is the ViewModel's.
     *
     * The bar used to be `SnackbarDuration.Short` while the deadline was a `delay` started at the
     * server acknowledgement: two independent clocks, and three reachable paths pull them apart —
     * a rotation restarts `LaunchedEffect(undo)` and gives the bar four fresh seconds, the
     * "Time to take action" accessibility setting stretches a `Short` through
     * `getRecommendedTimeoutMillis` (up to a minute or two), and the single `SnackbarHostState`
     * queues this bar behind four `Indefinite` ones. In all three the user presses Undo AFTER the
     * deadline fired: the message comes back into the folder with no banner, and on JMAP its id is
     * already in `NewMailNotifier`'s baseline, so it is never announced again.
     *
     * So the bar stops deciding: `Indefinite`, and the ViewModel clears `_undo` when the window
     * closes — `LaunchedEffect` is keyed on `undo`, so the bar goes with it. The window shown IS
     * the window that exists.
     */
    @Test fun `the Undo bar does not run a clock of its own`() {
        val body = bodyAt(INBOX_SCREEN, "LaunchedEffect(undo) {")
        assertEquals(
            "the Undo snackbar must not carry a duration of its own — the ViewModel's deadline is " +
                "the only clock, and it closes the window by clearing _undo. Body was:\n$body",
            listOf("duration = SnackbarDuration.Indefinite,"),
            codeLinesNaming(body, "duration ="),
        )
        assertEquals(
            "…and the two outcomes stay exactly what they were: the button undoes, anything else " +
                "(swipe, close button) leaves the deadline running. Body was:\n$body",
            listOf(
                "if (result == SnackbarResult.ActionPerformed) {",
                "viewModel.undo()",
                "revealTopSignal++",
                "} else viewModel.clearUndo()",
            ),
            normalised(body).dropLast(1).takeLast(4),
        )
    }

    /**
     * Undo cancels the deadline, and nothing else does. Without this, the user presses Undo, the
     * message comes back into the folder — and its banner is taken down a second later anyway, so a
     * message that IS there is announced nowhere.
     */
    @Test fun `Undo cancels the deadline`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "fun undo()")
        assertEquals(
            "undo() must cancel the pending banner deadline: the gesture was reversed, so the " +
                "banner must stay. Body was:\n$body",
            listOf("undoDepartureJob?.cancel()"),
            codeLinesNaming(body, "undoDepartureJob"),
        )
    }

    /**
     * And `clearUndo()` carries neither the deadline nor its cancellation. It is the screen's
     * "the snackbar went away" branch: hanging the dismissal there is mutation M2 (the banner
     * survives whenever the list leaves the composition first), and hanging the CANCELLATION there
     * would take the deadline down every time the bar simply timed out — i.e. exactly when it must
     * fire.
     */
    @Test fun `clearUndo carries neither the deadline nor its cancellation`() {
        val body = bodyAt(INBOX_VIEW_MODEL, "fun clearUndo()")
        assertEquals(
            "clearUndo() must not post the deadline — it is the screen's branch, and it does not " +
                "run at all when the list leaves the composition. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "scheduleUndoneDeparture("),
        )
        assertEquals(
            "…nor cancel it: the bar timing out is precisely when the banner must go. " +
                "Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "undoDepartureJob"),
        )
    }

    // -- reading the sources ------------------------------------------------------------------
    // Deliberately duplicated from the other lints rather than shared: a helper they all agree on
    // is a helper one edit can loosen for all of them at once.

    /** The deadline call, spelled once so the three sites cannot drift apart. */
    private val DEADLINE = "scheduleUndoneDeparture()"

    /** [DEADLINE]'s line and the one directly above it, whitespace-normalised, in order. */
    private fun deadlineWithItsNeighbour(body: String): List<String> {
        val lines = normalised(body)
        val at = lines.indexOfFirst { it == DEADLINE }
        if (at < 0) return listOf("<no $DEADLINE in this body>")
        return listOf(lines.getOrElse(at - 1) { "<nothing above>" }, lines[at])
    }

    private fun normalised(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.isBlank() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        normalised(body).filter { needle in it }

    /** The code lines of the whole [file] naming [needle], same normalisation. */
    private fun fileLinesNaming(file: File, needle: String): List<String> =
        codeLines(file).map { it.replace(Regex("""\s+"""), " ").trim() }.filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /**
     * The declaration [declaration] in [file] and its body, as text. Matched on the DECLARATION and
     * not on a bare name: `InboxViewModel.kt` declares `val undo: StateFlow<UndoAction?>` before
     * `fun undo()`, and a name-based reader silently hands back the flow.
     *
     * Fails loudly when the declaration is not found — a rename must break these rules rather than
     * satisfy them against an empty string.
     */
    private fun bodyAt(file: File, declaration: String): String {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { it.trimStart().startsWith(declaration) }
        check(start >= 0) { "${file.name} declares no '$declaration' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    companion object {
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}

package app.sterna.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the foot of the walk's column says, and whether it has to be read — EXECUTED, over every
 * (state, step) pair that matters, with the expected values written out in full and never
 * recomputed from the rule under test.
 *
 * ⛔ THE MEASURED DEFECT behind this file: address step, keyboard up, airplane mode, tap on
 * Continue. The walk answered correctly — a `ConnectState.Error` carrying `connect_offline`, still
 * on step 1 — and the sentence was drawn under the keyboard, absent from the uiautomator tree
 * until the screen was scrolled. The two decisions here are what the screen now draws AND scrolls
 * from, so a scroll can never be withheld from a sentence that is drawn.
 */
class ConnectStatusTest {

    /** The sentence the address step's offline answer carries, as `reportOffline` writes it. */
    private val offline = "You're offline. Adding an account needs a connection, so try again once you're back."

    @Test fun `a failure speaks at every step, and carries its own message`() {
        val said = ConnectStep.entries.map { it to statusLine(ConnectState.Error(offline), it) }
        assertEquals(
            "⛔ THE DEFECT, IN ITS EXECUTABLE HALF. `reportOffline` writes a ConnectState.Error " +
                "while the walk stands on the ADDRESS step, and that sentence is the whole of the " +
                "answer to a tap on Continue with no network. Condition this branch on the step — " +
                "the neighbouring NeedsServer branch is conditioned, so it is the idiomatic " +
                "one-line mutation — and the walk computes the message, writes it to the state, " +
                "and renders nothing at all. It must speak on EVERY step, and say what the walk " +
                "wrote, word for word.",
            ConnectStep.entries.map { it to StatusLine.Failed(offline) },
            said,
        )
    }

    @Test fun `a running attempt is a wheel at every step`() {
        assertEquals(
            "Connecting and Discovering are attempts still in flight; the block draws a wheel for " +
                "them and no sentence, wherever the walk stands.",
            ConnectStep.entries.flatMap { listOf(StatusLine.Working, StatusLine.Working) },
            ConnectStep.entries.flatMap {
                listOf(statusLine(ConnectState.Connecting, it), statusLine(ConnectState.Discovering, it))
            },
        )
    }

    @Test fun `only the manual form is told to fill a server in`() {
        assertEquals(
            "on the manual form this names the field she must now fill.",
            StatusLine.ServerNotFound, statusLine(ConnectState.NeedsServer, ConnectStep.MANUAL),
        )
        assertNull(
            "at the credentials step NeedsServer is a transient — the walk is already dialling " +
                "the endpoints the domain published — and 'enter it below' would point at nothing.",
            statusLine(ConnectState.NeedsServer, ConnectStep.CREDENTIALS),
        )
        assertNull(
            "and the address step shows no server field at all.",
            statusLine(ConnectState.NeedsServer, ConnectStep.ADDRESS),
        )
    }

    @Test fun `the states that have nothing to say say nothing, at every step`() {
        val silent = listOf(
            ConnectState.Idle,
            ConnectState.Connected,
            ConnectState.AwaitingApproval("ABCD-1234", "https://example.org/device", null),
            ConnectState.AwaitingBrowser("https://example.org/authorize"),
        )
        val spoke = silent.flatMap { state -> ConnectStep.entries.map { state to statusLine(state, it) } }
            .filter { it.second != null }
        assertEquals(
            "⛔ Idle and Connected are not verdicts, and the two awaiting-approval states own the " +
                "whole screen through their own panels — a line drawn under them would be a " +
                "second sentence about a sign-in that is being reported elsewhere, and (worse) " +
                "one that would scroll the column while a panel is up. Spoke anyway:\n" +
                spoke.joinToString("\n"),
            emptyList<Pair<ConnectState, StatusLine?>>(), spoke,
        )
    }

    @Test fun `sentences must be read, the wheel and the silence must not`() {
        assertTrue(
            "⛔ THE BENCH CASE, end to end: the offline answer at the address step is a sentence, " +
                "and a sentence under the keyboard is a screen that ignored her.",
            statusMustBeRead(statusLine(ConnectState.Error(offline), ConnectStep.ADDRESS)),
        )
        assertTrue(
            "the manual form's 'couldn't find your server' is a sentence too, and that form is " +
                "the longest on this screen — it is the surest one to be off the bottom.",
            statusMustBeRead(statusLine(ConnectState.NeedsServer, ConnectStep.MANUAL)),
        )
        assertFalse(
            "⛔ NOT the wheel. It is drawn on every connection attempt, from a form she may be in " +
                "the middle of; scrolling to it drags that form out from under her fingers to " +
                "show a spinner. `line != null` is the mutation this refuses.",
            statusMustBeRead(StatusLine.Working),
        )
        assertFalse(
            "and nothing to say is nothing to scroll to — asking anyway moves the column on the " +
                "way OUT of every error, i.e. on the next keystroke.",
            statusMustBeRead(null),
        )
    }
}

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and same disclaimer as
 * [ImapDiscoveryWiringTest]: `ConnectScreen` is a composable and this module has neither
 * Robolectric nor instrumented tests, so nothing here can be composed. What [statusLine] and
 * [statusMustBeRead] DECIDE is executed in [ConnectStatusTest]; what is held here is that the foot
 * of the walk's column draws THAT value, and scrolls it into view when it is a sentence.
 *
 * ⛔ THE DEFECT THIS FILE EXISTS FOR, measured at the bench (emu, Android 16, 1080×2400, release):
 * address step, keyboard up, airplane mode, tap on Continue. The walk did everything right — it
 * stayed on step 1 and wrote a `ConnectState.Error` carrying `connect_offline` — and the sentence
 * was rendered at y≈1589, under a keyboard whose top edge is y≈1500. It was absent from the
 * uiautomator tree until the screen was scrolled. From the reader's side: she taps Continue and
 * nothing happens.
 *
 * ⛔ Every pin is a WHOLE trimmed line compared by equality, or a run of consecutive whole lines —
 * never a `contains` on a fragment, which is blind to any mutation that lengthens the line.
 */
class ConnectStatusWiringTest {

    private val lines: List<String> by lazy { source(SCREEN).lines().map { it.trim() } }

    @Test fun `the foot of the column is one value, drawn inside one stable container`() {
        assertRun(
            listOf(
                "val status = statusLine(state, walkStep)",
                "Column(modifier = Modifier.bringIntoViewRequester(statusReveal)) {",
                "when (status) {",
            ),
            "⛔ THE THREE LINES, CONSECUTIVE, AND IN THIS ORDER — each of them load-bearing.\n" +
                "  · `statusLine(state, walkStep)`: ONE source of truth. Draw the block from a " +
                "`when (state)` and decide separately whether it deserves a scroll, and the two " +
                "spellings of the same rule drift apart in silence — the sentence is drawn under " +
                "one and scrolled under the other. Both arguments pinned: drop `walkStep` and the " +
                "credentials step tells the reader to 'enter it below' under a field that is not " +
                "there.\n" +
                "  · the Column carrying the requester comes BETWEEN the value and the `when`, so " +
                "the container is composed whether or not there is anything to say. Put the " +
                "requester on the Text instead (or wrap the Column in `if (status != null)`) and " +
                "the node is attached in the same frame the effect fires in — the scroll then " +
                "asks a node that has never been placed, and the sentence stays under the " +
                "keyboard. That is the defect, restored.\n" +
                "  · the `when` is on the VALUE, not on `state`.\n" +
                "Nothing may be inserted between the three: a conditional there is exactly how the " +
                "container stops being stable.",
        )
        assertPinned(
            "val statusReveal = remember { BringIntoViewRequester() }",
            "The requester itself, remembered across recompositions. A fresh one per composition " +
                "is a requester whose node is never the one on screen.",
        )
        assertOnce("statusLine(", "the one seam between the screen and the executed decision")
        assertOnce("bringIntoViewRequester(statusReveal)", "the one container that is brought into view")
        val block = lines.indexOf("val status = statusLine(state, walkStep)")
        val steps = lines.indexOf("when (walkStep) {")
        assertTrue(
            "⛔ AND IT STAYS AT THE FOOT of the column: in $SCREEN the status block (line index " +
                "$block) must come AFTER the walk's `when (walkStep) {` (line index $steps). " +
                "Hoisted above the steps the whole thing still compiles, every pin above still " +
                "holds, and the suite stays green — while the answer to a tap on Continue is drawn " +
                "ABOVE the address field, and bringing it into view scrolls the field and the " +
                "button she just used off the top of the screen. Found as a surviving mutation " +
                "while writing this file.",
            steps in 0 until block,
        )
    }

    @Test fun `the block is a direct child of the walk's column, not something's else branch`() {
        // ⛔ THE HOLE THE COUNTER-AUDIT CAME THROUGH, and no trimmed pin in this repo can see it:
        // wrap the whole foot of the column in `if (walkStep != ConnectStep.ADDRESS) { … }` and
        // every run of lines above still follows in the same order, every assertOnce still counts
        // one, the anti-leak test still finds no `walkStep == ConnectStep.MANUAL` — and offline at
        // the address step the walk computes the message, writes the state and DRAWS NOTHING. The
        // original defect, whole, with a green suite. Only the leading whitespace says whether a
        // line has been moved inside a block. Same instrument as OFFLINE_GUARD, which pins its
        // four lines at eight spaces for this very family of mutations.
        listOf(
            "val status = statusLine(state, walkStep)" to
                "the value the foot of the column draws",
            "LaunchedEffect(status) {" to
                "the effect that scrolls it — wrapped alone, the sentence is drawn and never seen",
            "when (walkStep) {" to
                "the walk's own steps: the reference level. Both must be children of the SAME " +
                    "column, which is what makes 'the block is drawn at every step' true",
        ).forEach { (pinned, what) -> assertIndent(pinned, WALK_COLUMN_INDENT, what) }
    }

    @Test fun `the keyboard shrinks the viewport the scroll aims at`() {
        assertRun(
            listOf(
                ".imePadding()",
                ".verticalScroll(rememberScrollState())",
            ),
            "⛔ THE WHOLE CORRECTION RESTS ON THESE TWO, CONSECUTIVE AND IN THIS ORDER, and until " +
                "now nothing in this module held them (other screens do: ComposeDeleteWiringTest, " +
                "SettingChoiceDialogScrollTest, NotificationsGateWiringTest). imePadding() FIRST " +
                "is what shrinks the scrollable viewport to the space left above the keyboard; " +
                "bringIntoView() then has something to do. Remove it, or swap the two, and the " +
                "viewport is the whole window: the sentence measured at y≈1589 is 'already " +
                "visible' behind a keyboard whose top edge is y≈1500, the scroll is a no-op, and " +
                "the defect is back at 100 % — with every other pin in this file still green. It " +
                "would also kill the #52 guard (the focused field above the keyboard) on the way.",
        )
    }

    @Test fun `a sentence is scrolled into view, and the wheel is not`() {
        assertRun(
            listOf(
                "LaunchedEffect(status) {",
                "if (statusMustBeRead(status)) statusReveal.bringIntoView()",
                "}",
            ),
            "⛔ THE WHOLE EFFECT, closing brace included.\n" +
                "  · keyed on `status`, the value SAID. `LaunchedEffect(Unit)` runs once, at the " +
                "first composition, when there is nothing to say — the sentence is then never " +
                "scrolled to, 100 % of the time, and every executed test stays green because " +
                "nothing else in this repo pins the key of an effect. Keyed on `state` it re-runs " +
                "on states that say nothing at all.\n" +
                "  · guarded by `statusMustBeRead`, executed in ConnectStatusTest. Call " +
                "bringIntoView() unconditionally and every connection attempt drags the form out " +
                "from under her fingers to show a spinner.\n" +
                "  · and it must call bringIntoView() — dropping that call is the defect verbatim.",
        )
        assertOnce("statusMustBeRead(", "the one place the screen asks whether the line must be read")
    }

    @Test fun `each branch draws its own body`() {
        assertRun(
            listOf(
                "StatusLine.Working -> LoadingRing()",
            ),
            "the wheel, on the label's own line — a body cannot be swapped away from a label it " +
                "shares a line with. It is LoadingRing, not a raw CircularProgressIndicator: with " +
                "the system animations off Material 3's indeterminate arc is left on screen as a " +
                "few pixels of stroke and stops reading as \"working\" (#63).",
        )
        assertRun(
            listOf(
                "StatusLine.ServerNotFound -> Text(",
                "text = stringResource(R.string.connect_server_not_found),",
            ),
            "⛔ LABEL AND BODY, ADJACENT. Pinning the two lines separately is blind to the two " +
                "branches' bodies being EXCHANGED: each line would still occur exactly once while " +
                "the manual form said 'Could not connect: …' and a failed sign-in said 'enter it " +
                "below'. The string is an existing one, in all nine locales.",
        )
        assertRun(
            listOf(
                "is StatusLine.Failed -> Text(",
                "text = stringResource(R.string.connect_error, status.message),",
            ),
            "⛔ Same, for the branch that carries the offline sentence — and its ARGUMENT: " +
                "`stringResource(R.string.connect_error)` alone compiles to 'Could not connect: " +
                "%1\$s' with the placeholder left raw, and the reason the walk went to the trouble " +
                "of writing is dropped on the floor.",
        )
    }

    @Test fun `the step condition lives in the decision, not in the screen`() {
        val leaks = lines.withIndex().filter { it.value.contains("walkStep == ConnectStep.MANUAL") }
        assertEquals(
            "⛔ Which steps the 'couldn't find your server' line may speak on is [statusLine]'s " +
                "call, and ConnectStatusTest executes it at every step. Re-stated in $SCREEN it is " +
                "a second copy of the same rule, and the copy nothing can see is the one that " +
                "drifts. Found:\n" + leaks.joinToString("\n"),
            emptyList<IndexedValue<String>>(), leaks,
        )
    }

    // -- reading the file ---------------------------------------------------------------------

    /** [run] must appear in [SCREEN] as consecutive trimmed lines, exactly once. */
    private fun assertRun(run: List<String>, why: String) {
        val hits = (0..(lines.size - run.size)).count { lines.subList(it, it + run.size) == run }
        assertEquals(
            "expected exactly one place in $SCREEN where these lines follow one another, trimmed:\n" +
                run.joinToString("\n") { "    $it" } + "\nbut found $hits. A line was rewritten, " +
                "removed, split, duplicated, reordered, or something was inserted between them — " +
                "this lint compares WHOLE lines, so even a change that only lengthens one lands " +
                "here. $why",
            1, hits,
        )
    }

    private fun assertPinned(pinned: String, why: String) = assertRun(listOf(pinned), why)

    /**
     * The one line of [SCREEN] whose trimmed text is [pinned] must carry exactly [spaces] leading
     * spaces — i.e. sit at that nesting level, and no deeper.
     *
     * ⛔ Read on the RAW line, deliberately: every other lint in this repo trims first, and a
     * trimmed line is blind to the one mutation that changes nothing about the lines themselves —
     * a conditional wrapped around them.
     */
    private fun assertIndent(pinned: String, spaces: Int, what: String) {
        val raw = source(SCREEN).lines().filter { it.trim() == pinned }
        assertEquals("expected exactly one line of $SCREEN reading `$pinned` — $what", 1, raw.size)
        assertEquals(
            "`$pinned` ($what) must sit at exactly $spaces spaces of indentation in $SCREEN. " +
                "Deeper means something was wrapped around it — a step condition, a null check, a " +
                "panel branch — and a block that is not composed draws nothing while every " +
                "line-based pin in this repo stays green.",
            spaces, raw.single().takeWhile { it == ' ' }.length,
        )
    }

    private fun assertOnce(fragment: String, what: String) {
        val n = Regex(Regex.escape(fragment)).findAll(source(SCREEN)).count()
        assertTrue(
            "`$fragment` must occur exactly once in $SCREEN — $what. A second occurrence is a " +
                "second site deciding the same thing, which is how the drawn line and the " +
                "scrolled line come to disagree. Found $n.",
            n == 1,
        )
    }

    private fun source(path: String): String =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, path).isFile }
            ?.let { File(it, path).readText() }
            ?: error(
                "cannot locate the repo root from ${File("").absolutePath} — this test reads a " +
                    "source file as text and needs a working directory inside the checkout",
            )

    private companion object {
        const val SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"

        /**
         * Direct children of the walk's scrolling column: three levels of `Scaffold { Column {` in,
         * i.e. twelve spaces. The walk's `when (walkStep)` sits there, and so must the status block.
         */
        const val WALK_COLUMN_INDENT = 12
    }
}

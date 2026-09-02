package app.sterna.ui.outbox

import app.sterna.R
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.mail.draftSaveParksTheQueuedRowAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What the Outbox screen SAYS about each row, and what the Inbox banner is about.
 *
 * The first half EXECUTES the decisions ([outboxStateLabel], [outboxStateShowsReason],
 * [outboxStateIsError], [OutboxLogic.needsFailureBanner]) — every state's answer written out in
 * full, never recomputed from the shipped rule. The second half is a SOURCE LINT and says so: a
 * composable cannot be run here (no Robolectric, no instrumented tests in this module), so that the
 * screen still ASKS those functions is read as text and proves nothing about what is drawn.
 */
class OutboxStateWordingTest {

    /** The state the shipped rule parks a save kept on the phone in — asked, never assumed. */
    private val parked = draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE)
        ?: error("a save kept on the device parks nothing at all — see LocalDraftSaveTest")

    // -- executed: the wording, state by state ---------------------------------------------------

    /**
     * Every state's sentence, written out. ⛔ The pairs are pinned in full rather than checked one
     * by one: the defect being fixed is a state falling through an `else ->` into somebody else's
     * words, and only a complete list can see a state that has quietly lost its own branch.
     */
    @Test fun `each outbox state has its own sentence`() {
        assertEquals(
            mapOf(
                OutboxState.HELD to R.string.outbox_state_waiting,
                OutboxState.QUEUED to R.string.outbox_state_waiting,
                OutboxState.SENDING to R.string.outbox_state_sending,
                OutboxState.FAILED to R.string.outbox_state_failed,
                OutboxState.KEPT_AS_DRAFT to R.string.outbox_state_kept_as_draft,
                OutboxState.EDITING to R.string.outbox_state_waiting,
                OutboxState.INTERRUPTED to R.string.outbox_state_interrupted,
            ),
            OutboxState.entries.associateWith { outboxStateLabel(it) },
        )
    }

    /**
     * ⛔ THE sentence of this fix. A row saved as a draft on the phone must say so, on its own, and
     * must not be dressed as a failure: "Failed: saved as a draft on this device, not sent" is what
     * the previous attempt printed in nine languages, over a save that did exactly what was asked.
     */
    @Test fun `the row a kept-on-device save leaves behind says it was saved, not that it failed`() {
        assertEquals(
            "⛔ the parked row's line must be R.string.outbox_state_kept_as_draft, a WHOLE " +
                "sentence printed as it stands (#95)",
            R.string.outbox_state_kept_as_draft,
            outboxStateLabel(parked),
        )
        assertFalse(
            "⛔ and it must take no reason argument: R.string.outbox_state_failed is 'Failed: " +
                "%1\$s', so a state that substitutes into it announces a failure that never " +
                "happened — and with a null lastError it reads 'Failed: couldn't send' over a " +
                "message nobody ever tried to send",
            outboxStateShowsReason(parked),
        )
        assertFalse(
            "⛔ and it must not be painted in the error colour: nothing would ever repaint it, " +
                "there being no deferred upload whose success could take the red back",
            outboxStateIsError(parked),
        )
        assertFalse(
            "⛔ and it must not raise the Inbox's failure banner across the whole list",
            OutboxLogic.needsFailureBanner(parked),
        )
    }

    /**
     * ⛔ THE sentence of the interrupted-send fix. A delivery whose run was killed may ALREADY have
     * reached the server, and the row is parked exactly because this phone cannot tell. Every word
     * on the line has to survive both outcomes: it may not say the send failed, and it may not say
     * the message was not sent.
     */
    @Test fun `the row a killed delivery leaves behind says it may already be gone, never that it failed`() {
        assertEquals(
            "⛔ the parked row's line must be R.string.outbox_state_interrupted, a WHOLE sentence " +
                "printed as it stands — 'Sending…' would be a lie (nothing is sending any more) " +
                "and 'Waiting to send' would be worse (nothing is going to send it)",
            R.string.outbox_state_interrupted,
            outboxStateLabel(OutboxState.INTERRUPTED),
        )
        assertFalse(
            "⛔ and it must take no reason argument: R.string.outbox_state_failed is 'Failed: " +
                "%1\$s', so a state that substitutes into it announces a failure that never " +
                "happened over a message that may well have gone out",
            outboxStateShowsReason(OutboxState.INTERRUPTED),
        )
        assertFalse(
            "⛔ and it must not be painted in the error colour: red says the send went wrong, and " +
                "nothing would ever repaint it",
            outboxStateIsError(OutboxState.INTERRUPTED),
        )
        assertFalse(
            "⛔ and it must not raise the Inbox's failure banner across the whole list: 'Some " +
                "messages didn't send' over a message that did send is a claim nothing retracts",
            OutboxLogic.needsFailureBanner(OutboxState.INTERRUPTED),
        )
    }

    /**
     * ⛔ THE SENTENCE OF THE DESTRUCTIVE ACTION, state by state, written out by hand. The delete
     * confirmation is read at the one moment the only copy of a message is destroyed, and its
     * default body OPENS with "It hasn't been sent" — the exact claim this fix forbids over a row
     * whose delivery may have got through. Told the message never left, the reader deletes it.
     *
     * ⛔ The pairs are pinned in full, like the sentences above: a state quietly losing its branch
     * here is invisible one assertion at a time, and every state but one shares the same answer.
     */
    @Test fun `the row a killed delivery leaves behind is not told it was never sent, when it is deleted`() {
        assertEquals(
            "⛔ only OutboxState.INTERRUPTED may take R.string.outbox_delete_body_interrupted, and " +
                "it must take nothing else: R.string.outbox_delete_body says \"It hasn't been " +
                "sent\", which over an interrupted row is a promise this phone cannot keep — the " +
                "message may be at the recipient already, and the reader is deciding whether to " +
                "destroy her only copy of it",
            mapOf(
                OutboxState.HELD to R.string.outbox_delete_body,
                OutboxState.QUEUED to R.string.outbox_delete_body,
                OutboxState.SENDING to R.string.outbox_delete_body,
                OutboxState.FAILED to R.string.outbox_delete_body,
                OutboxState.KEPT_AS_DRAFT to R.string.outbox_delete_body,
                OutboxState.EDITING to R.string.outbox_delete_body,
                OutboxState.INTERRUPTED to R.string.outbox_delete_body_interrupted,
            ),
            OutboxState.entries.associateWith { outboxDeleteBody(it) },
        )
    }

    /** The two per-state flags, in full — same reason as the sentences above. */
    @Test fun `only a genuine send failure is dressed as one`() {
        assertEquals(
            "the only line that substitutes the row's lastError is the one whose string has a " +
                "placeholder for it",
            listOf(OutboxState.FAILED),
            OutboxState.entries.filter { outboxStateShowsReason(it) },
        )
        assertEquals(
            "the only line painted in the error colour",
            listOf(OutboxState.FAILED),
            OutboxState.entries.filter { outboxStateIsError(it) },
        )
        assertEquals(
            "and the only state the Inbox's 'Some messages didn't send' banner is about",
            listOf(OutboxState.FAILED),
            OutboxState.entries.filter { OutboxLogic.needsFailureBanner(it) },
        )
    }

    // -- executed: the reason a FAILED row prints -------------------------------------------------

    /**
     * ⭐ THE SENTENCE OF THE INTERRUPTED-EDIT FIX. A queued row closed over a lost body is parked
     * FAILED with `OutboxLogic.EDIT_INTERRUPTED` in `lastError` — a SENTINEL, because a sentence
     * stored in the row would stay in the language of the day it was written. The screen must
     * translate it, and must go on printing the server's own words (#183) and the fallback for a
     * row with no reason at all.
     */
    @Test fun `the reason a failed row prints is translated from its sentinel, never stored`() {
        assertEquals(
            "⛔ the sentinel must come out as R.string.outbox_error_edit_interrupted — printed raw " +
                "it reads 'Failed: edit-interrupted' in nine languages",
            OutboxFailureReason.Words(R.string.outbox_error_edit_interrupted),
            outboxFailureReason(OutboxLogic.EDIT_INTERRUPTED),
        )
        assertEquals(
            "a row with no reason keeps the fallback it always had",
            OutboxFailureReason.Words(R.string.outbox_state_failed_unknown),
            outboxFailureReason(null),
        )
        assertEquals(
            "the server's own words (#183) are printed as they are — another nature of reason, " +
                "and untouched",
            OutboxFailureReason.Text("550 5.1.1 No such user"),
            outboxFailureReason("550 5.1.1 No such user"),
        )
    }

    // -- source lint: that the screen and the banner still ask ------------------------------------

    /**
     * ⚠ SOURCE LINT. `OutboxScreen` is a composable; nothing here can render it. What is pinned is
     * that every reading of a row's state goes through the pure functions above — WHOLE LINES and
     * ALL of them, in order, because the mutation this exists for is a `when (item.state)` written
     * back into the composable with `KEPT_AS_DRAFT` falling into the failed branch, and that edit
     * neither shortens a line nor removes a name.
     */
    @Test fun `the Outbox screen only ever asks those functions about a row's state`() {
        val lines = codeLines(OUTBOX_SCREEN)
        assertEquals(
            "OutboxScreen must consult item.state only through outboxStateShowsReason / " +
                "outboxStateLabel / outboxStateIsError (and OutboxLogic.canEdit for the button). A " +
                "`when (item.state)` back in the composable is unreachable from every test in this " +
                "module, which is exactly where the `else ->` that printed 'Failed: …' over a " +
                "saved draft lived.",
            listOf(
                "if (outboxStateShowsReason(item.state)) {",
                "outboxStateLabel(item.state),",
                "stringResource(outboxStateLabel(item.state))",
                "color = if (outboxStateIsError(item.state)) {",
                "if (OutboxLogic.canEdit(item.pgpMode, item.state, unreplayable)) {",
            ),
            lines.map { it.trim() }.filter { "item.state" in it },
        )
        assertEquals(
            "and the reason it substitutes must be asked of outboxFailureReason(item.lastError) — " +
                "the row's lastError printed raw, as it used to be, puts 'Failed: edit-interrupted' " +
                "on screen in nine languages over a row this fix parks",
            listOf("when (val reason = outboxFailureReason(item.lastError)) {"),
            lines.map { it.trim() }.filter { "item.lastError" in it },
        )
        assertEquals(
            "and it must name no state string of its own: the wording is decided in " +
                "OutboxRowText, where a test can execute it",
            emptyList<String>(),
            lines.map { it.trim() }.filter { "R.string.outbox_state_" in it || "R.string.outbox_error_" in it },
        )
    }

    /**
     * ⚠ SOURCE LINT, same disclaimer as above: the dialog is a composable and nothing here renders
     * it. What is pinned is that the delete confirmation ASKS [outboxDeleteBody] which body this
     * row is owed — reverting the slot to a bare `stringResource(R.string.outbox_delete_body)`
     * compiles, reads fine, and puts "It hasn't been sent" back over a row that may already have
     * been sent, in nine languages, on the one action that cannot be undone.
     *
     * WHOLE LINES in sequence: the state is read from the list already collected by the screen, so
     * the mutation that matters (dropping the lookup, or wiring the dialog to the id alone) neither
     * shortens a line nor removes a name.
     */
    @Test fun `the delete confirmation asks which body the row awaiting confirmation is owed`() {
        val lines = codeLines(OUTBOX_SCREEN).map { it.trim() }
        val at = lines.indexOf("pendingDelete?.let { id ->")
        check(at >= 0) { "OutboxScreen opens no delete confirmation on 'pendingDelete' any more" }
        assertEquals(
            "the body of the delete dialog must come from outboxDeleteBody(the pending row's " +
                "state), read out of the list the screen already holds — no second trip to the " +
                "repository. A row gone from that list has no state to speak for it and keeps the " +
                "default body; a row that is INTERRUPTED must NOT be told it was never sent.",
            listOf(
                "pendingDelete?.let { id ->",
                "val pendingState = items.firstOrNull { it.id == id }?.state",
                "val deleteBody = pendingState?.let(::outboxDeleteBody) ?: R.string.outbox_delete_body",
                "AlertDialog(",
                "onDismissRequest = { pendingDelete = null },",
                "title = { Text(stringResource(R.string.outbox_delete_title)) },",
                "text = { Text(stringResource(deleteBody)) },",
            ),
            lines.drop(at).take(7),
        )
    }

    /**
     * ⚠ SOURCE LINT, same disclaimer: `InboxViewModel` is an `AndroidViewModel` and cannot be built
     * here. The banner's flow must ask [OutboxLogic.needsFailureBanner] and nothing else — a second
     * clause ORed onto it (`|| it.state == OutboxState.KEPT_AS_DRAFT`) LENGTHENS the line, so the
     * whole line is compared rather than searched.
     */
    @Test fun `the Inbox failure banner is raised through the one rule`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
        val at = lines.indexOfFirst { it.startsWith("val outboxHasFailures") }
        check(at >= 0) { "InboxViewModel declares no 'outboxHasFailures' — did it get renamed?" }
        assertEquals(
            "the banner must be raised by OutboxLogic.needsFailureBanner(it.state) and by nothing " +
                "else. Testing OutboxState.FAILED here by hand is how the rule and the screen drift " +
                "apart; adding KEPT_AS_DRAFT to it puts a permanent 'Some messages didn't send' " +
                "banner over a draft saved exactly as asked (#95).",
            listOf(
                "val outboxHasFailures: StateFlow<Boolean> = repo.outboxFlow()",
                ".map { items -> items.any { OutboxLogic.needsFailureBanner(it.state) } }",
            ),
            lines.drop(at).take(2),
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /** The lines of [file] that are code, with whole-line and trailing comments taken off. */
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

    companion object {
        private const val OUTBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt"
        private const val INBOX_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, OUTBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val OUTBOX_SCREEN: File by lazy { File(root, OUTBOX_SCREEN_PATH) }
        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}

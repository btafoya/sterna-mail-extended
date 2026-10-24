package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint, not a behaviour test: reads `ComposeScreen.kt` and `ComposeViewModel.kt` as text, as
 * neither can be built on the JVM. The decisions it wires are executed by [DecryptedQuoteGuardTest]
 * (`askBeforeQuoteInClear`, `QuoteInClearQuestion.confirm`); this pins what each call is handed.
 */
class QuoteInClearWiringTest {

    @Test fun `each of the three gestures asks before it goes on`() {
        assertEquals(
            "the send, the menu's draft and the menu's schedule must each ask the question while the " +
                "body is refused, and hand over their own gesture and their own way on",
            listOf(SEND_ASKS, DRAFT_TAP, SCHEDULE_TAP),
            screen().filter { "askBeforeQuoteInClear(" in it },
        )
        assertEquals(listOf("onClick = attemptSend,"), screen().filter { "attemptSend," in it || "attemptSend)" in it })
    }

    @Test fun `the presets open from one place only, and only past the question`() {
        assertEquals(
            "a second line raising the presets opens them past the question",
            listOf(OPEN_PRESETS),
            screen().filter { "scheduleMenu = true" in it },
        )
        assertEquals(
            "the presets must be opened by the asked tap and by the confirmation, nowhere else",
            listOf("QuoteInClearGesture.SCHEDULE -> openSchedulePresets()", SCHEDULE_TAP),
            screen().filter { "openSchedulePresets" in it && !it.startsWith("val openSchedulePresets") },
        )
        assertEquals(
            listOf(SEND_ASKS, "QuoteInClearGesture.SEND -> attemptSendPastQuote()"),
            screen().filter { "attemptSendPastQuote" in it && !it.startsWith("val attemptSendPastQuote") },
        )
        assertEquals(
            listOf("QuoteInClearGesture.DRAFT -> saveDraftNow()", DRAFT_TAP),
            screen().filter { "saveDraftNow" in it && !it.startsWith("val saveDraftNow") },
        )
    }

    @Test fun `the confirmation lowers the guard through the view model before resuming the gesture`() {
        val all = screen()
        val at = all.indexOf("if (quoteInClear != null) {")
        assertTrue("the question is not drawn behind its flag", at >= 0)
        assertEquals(
            "the confirm button must resume only the gesture `confirmQuoteInClear` hands back: that " +
                "call lowers the guard first, so the view model's refusals let the gesture through",
            listOf(
                "if (quoteInClear != null) {",
                "AlertDialog(",
                "onDismissRequest = { viewModel.dismissQuoteInClear() },",
                "text = { Text(stringResource(R.string.compose_quote_in_clear_message)) },",
                "confirmButton = {",
                "TextButton(onClick = {",
                "when (viewModel.confirmQuoteInClear()) {",
                "QuoteInClearGesture.SEND -> attemptSendPastQuote()",
                "QuoteInClearGesture.DRAFT -> saveDraftNow()",
                "QuoteInClearGesture.SCHEDULE -> openSchedulePresets()",
                "null -> Unit",
                "}",
                "}) { Text(stringResource(R.string.compose_quote_in_clear_confirm)) }",
                "},",
                "dismissButton = {",
                "TextButton(onClick = { viewModel.dismissQuoteInClear() }) {",
                "Text(stringResource(R.string.compose_forgot_attachment_back))",
            ),
            all.subList(at, (at + 17).coerceAtMost(all.size)),
        )
        val vm = code(VIEW_MODEL)
        assertEquals(
            "the question must lower this composer's own guard, and the screen reach it through these doors",
            listOf(
                "private val quoteInClear = QuoteInClearQuestion(decryptedQuote)",
                "val pendingQuoteInClear: StateFlow<QuoteInClearGesture?> = quoteInClear.pending",
                "fun askQuoteInClear(gesture: QuoteInClearGesture) = quoteInClear.ask(gesture)",
                "fun dismissQuoteInClear() = quoteInClear.dismiss()",
                CONFIRM,
                WITHDRAW,
            ),
            vm.filter { "quoteInClear" in it },
        )
        assertEquals(
            listOf("val quoteInClear by viewModel.pendingQuoteInClear.collectAsStateWithLifecycle()"),
            all.filter { "pendingQuoteInClear" in it },
        )
    }

    @Test fun `the schedule says its own refusal before it asks, read on the mode in force`() {
        val vm = code(VIEW_MODEL)
        val head = vm.indexOf("fun scheduleRefusedOrSay(to: String, cc: String, bcc: String): Boolean {")
        assertTrue("ComposeViewModel.scheduleRefusedOrSay is missing", head >= 0)
        assertEquals(
            "the refusal said before the question must be the schedule's alone, on the current mode: with " +
                "the quote's in it, the question would never be asked; without it said, the guard is " +
                "lowered for a schedule refused anyway",
            listOf(
                "val refusal = scheduleRefusal(to, cc, bcc, _pgpMode.value) ?: return false",
                "_notices.tryEmit(refusal)",
                "return true",
                "}",
            ),
            vm.subList(head + 1, head + 5),
        )
        assertEquals(
            "only the schedule's tap consults it, and before `askBeforeQuoteInClear`",
            listOf(SCHEDULE_TAP),
            screen().filter { "scheduleRefusedOrSay" in it },
        )
    }

    @Test fun `the question follows the refusal in force, and the confirmation hands it over`() {
        val vm = code(VIEW_MODEL)
        assertEquals(
            "the question must hear every change of the fact and of the mode, or it stays up over a lock " +
                "that turned encrypting and its confirmation lowers the guard for nothing",
            listOf(WITHDRAW),
            vm.filter { "refusalIs(" in it },
        )
        assertEquals(listOf(CONFIRM), vm.filter { "quoteInClear.confirm(" in it })
    }

    private fun screen(): List<String> = code(SCREEN)

    private fun code(path: String): List<String> = File(root, path).readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    companion object {
        const val SEND_ASKS =
            "val attemptSend = { askBeforeQuoteInClear(quoteRefusal, QuoteInClearGesture.SEND, viewModel::askQuoteInClear, attemptSendPastQuote) }"
        const val DRAFT_TAP =
            "onClick = { moreMenu = false; askBeforeQuoteInClear(quoteRefusal, QuoteInClearGesture.DRAFT, viewModel::askQuoteInClear, saveDraftNow) },"
        const val SCHEDULE_TAP =
            "onClick = { moreMenu = false; if (!viewModel.scheduleRefusedOrSay(to, cc, bcc)) " +
                "askBeforeQuoteInClear(quoteRefusal, QuoteInClearGesture.SCHEDULE, viewModel::askQuoteInClear, openSchedulePresets) },"
        const val CONFIRM =
            "fun confirmQuoteInClear(): QuoteInClearGesture? = quoteInClear.confirm(decryptedQuoteRefusal(decryptedQuote.held.value, _pgpMode.value))"
        const val WITHDRAW =
            "viewModelScope.launch { combine(decryptedQuote.held, _pgpMode) { held, mode -> decryptedQuoteRefusal(held, mode) }" +
                ".collect { quoteInClear.refusalIs(it) } }"
        const val OPEN_PRESETS =
            "val openSchedulePresets = { if (viewModel.mayScheduleOrSay(to, cc, bcc)) { menuOpenedAt = System.currentTimeMillis(); scheduleMenu = true } }"

        private const val VIEW_MODEL = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SCREEN = "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, VIEW_MODEL).isFile }
                ?: error("Cannot find $VIEW_MODEL from ${File("").absolutePath}")
        }
    }
}

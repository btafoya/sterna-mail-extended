package app.sterna.ui.message

import app.sterna.core.data.mail.MailtoUnsubscribe
import app.sterna.core.data.mail.UnsubscribeOptions
import app.sterna.core.data.unsubscribe.UnsubscribeFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shape the unsubscribe strip takes, and the one property that matters beyond looks: the
 * three ordinary shapes are the same height.
 */
class UnsubscribeStripTest {

    private val oneClick = UnsubscribeOptions(oneClickUrl = "https://l.example.com/u/abc")
    private val mail = UnsubscribeOptions(mailto = MailtoUnsubscribe("leave@l.example.com"))

    private val everyState = listOf(
        UnsubscribeState.Idle,
        UnsubscribeState.Sending,
        UnsubscribeState.Sent,
        UnsubscribeState.Queued,
    ) + UnsubscribeFailure.entries.map { UnsubscribeState.Failed(it) }

    @Test fun `an untouched strip is one button`() {
        assertEquals(UnsubscribeStripBody.ACTION, unsubscribeStripBody(UnsubscribeState.Idle))
        assertTrue(unsubscribeStripBody(UnsubscribeState.Idle).acts)
    }

    @Test fun `a request in flight keeps the button and takes the tap away`() {
        // The spinner goes where the icon was, inside the button: a spinner that replaces the
        // whole button is a strip of another height, and another height is a reload of the body.
        val body = unsubscribeStripBody(UnsubscribeState.Sending)
        assertEquals(UnsubscribeStripBody.SENDING, body)
        assertFalse("in flight, the button may not send again", body.acts)
    }

    @Test fun `a finished unsubscribe draws no button at all`() {
        listOf(UnsubscribeState.Sent, UnsubscribeState.Queued).forEach { state ->
            val body = unsubscribeStripBody(state)
            assertEquals("done is done, for $state", UnsubscribeStripBody.DONE, body)
            assertFalse(body.acts)
        }
    }

    @Test fun `a failure says what happened and offers the gesture again`() {
        UnsubscribeFailure.entries.forEach { reason ->
            val body = unsubscribeStripBody(UnsubscribeState.Failed(reason))
            assertEquals("after $reason", UnsubscribeStripBody.FAILED, body)
            assertTrue("a refusal or a dead network is a retry, not a dead end", body.acts)
        }
    }

    @Test fun `the strip's button and the action itself never disagree`() {
        // The defect this whole feature was audited for, in its last possible hiding place: the
        // strip drawing a live button where the action refuses to run is a button that does
        // nothing, and the reverse is a second POST to a list that already answered.
        everyState.forEach { state ->
            listOf(oneClick, mail).forEach { options ->
                assertEquals(
                    "$state: the strip acts exactly when offeredUnsubscribeAction offers something",
                    offeredUnsubscribeAction(options, state) != null,
                    unsubscribeStripBody(state).acts,
                )
            }
        }
    }

    /** Source lint from here: Compose and an `AndroidViewModel` do not run in a JVM test. Holes open, of
     *  the four in `CONTRIBUTING.md`: an argument name says nothing of its binding (1), hence every
     *  hand-over of `onUnsubscribe` pinned; nothing of the control structure around a line (3); nor
     *  of the value a composable collects (4). */
    @Test fun `a touch anywhere on the line opens the confirmation and nothing else`() {
        val strip = codeLines(stripSource())
        assertEquals(
            "the whole line is the gesture, live exactly where unsubscribeStripBody says so. Lines " +
                "naming onUnsubscribe in the strip:",
            listOf("onUnsubscribe: () -> Unit,", "HeaderCardLine(onClick = onUnsubscribe, enabled = body.acts) {"),
            strip.filter { "onUnsubscribe" in it },
        )
        val screen = codeLines(SOURCE.readText())
        assertEquals(
            "onUnsubscribe must reach the line from askUnsubscribe, through hand-overs by name only: " +
                "handed viewModel::unsubscribe, a stray touch on a line that now spans the screen " +
                "sends the POST with no dialog. Lines found:",
            listOf(
                "onUnsubscribe = viewModel::askUnsubscribe,",
                "onUnsubscribe = onUnsubscribe,",
                "onUnsubscribe = onUnsubscribe,",
            ),
            screen.filter { it.startsWith("onUnsubscribe =") },
        )
        assertEquals(
            "the card hands the line the same callback",
            listOf("is HeaderCardRow.Unsubscribe -> UnsubscribeStrip(row.action, unsubscribeState, onUnsubscribe)"),
            screen.filter { "UnsubscribeStrip(" in it && !it.startsWith("private fun") },
        )
        val requests = screen.filter { "viewModel.unsubscribe()" in it || "viewModel::unsubscribe" in it }
        assertEquals("the request is started from one place in the screen", listOf("viewModel.unsubscribe()"), requests)
        assertTrue(
            "…and that place is the confirmation's own button. Confirmation was:\n${confirmDialogSource()}",
            "viewModel.unsubscribe()" in confirmDialogSource(),
        )
        assertEquals(
            "askUnsubscribe opens the confirmation and sends nothing",
            listOf(
                "fun askUnsubscribe() {",
                "val options = _unsubscribe.value ?: return",
                "val action = offeredUnsubscribeAction(options, _unsubscribeState.value) ?: return",
                "_unsubscribeConfirm.value = PendingUnsubscribe(action, options)",
                "}",
            ),
            codeLines(VIEW_MODEL.readText()).dropWhile { it != "fun askUnsubscribe() {" }.take(5),
        )
    }

    @Test fun `the line keeps its height in the ordinary states and only a failure grows`() {
        val strip = codeLines(stripSource())
        listOf(
            "val body = unsubscribeStripBody(state)",
            "if (body == UnsubscribeStripBody.SENDING) {",
            "CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)",
            "HeaderCardIcon(Icons.Filled.Mail)",
            "style = MaterialTheme.typography.bodyMedium,",
            "color = if (body == UnsubscribeStripBody.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,",
            "maxLines = if (body == UnsubscribeStripBody.FAILED) Int.MAX_VALUE else 1,",
            "overflow = TextOverflow.Ellipsis,",
            "UnsubscribeStripBody.ACTION, UnsubscribeStripBody.SENDING -> R.string.message_mailing_list",
            "UnsubscribeStripBody.DONE -> Unit",
        ).forEach { rule ->
            assertEquals("the strip must carry, once and whole:\n  $rule\nStrip was:\n${strip.joinToString("\n")}", 1, strip.count { it == rule })
        }
        assertEquals(
            "the strip draws no button of its own and no row of its own: its geometry is HeaderCardLine's",
            emptyList<String>(),
            strip.filter { it.startsWith("Row(") || "Button(" in it || "heightIn" in it || "padding" in it },
        )
        assertEquals(
            "in flight, the action is greyed, never at 38 % alpha: onSurfaceVariant, named",
            listOf("color = MaterialTheme.colorScheme.onSurfaceVariant,"),
            strip.filter { "onSurfaceVariant" in it },
        )
    }

    /** The line is merged into one TalkBack target: a label on its icon is a sentence read before
     *  the line's own, and once the request is done it still says "unsubscribe". */
    @Test fun `the icon says nothing to TalkBack`() {
        val strip = codeLines(stripSource())
        assertEquals(
            "no contentDescription other than null in the strip. Lines found:",
            emptyList<String>(),
            strip.filter { "contentDescription" in it && "contentDescription = null" !in it },
        )
        assertEquals(emptyList<String>(), strip.filter { "message_unsubscribe_banner" in it })
    }

    /** A failure is a retry: the action is drawn again, the same label as before the first try. Without
     *  it the line stays tappable with nothing on it saying so. */
    @Test fun `a failure draws the action again`() {
        val strip = codeLines(stripSource())
        val at = strip.indexOf("UnsubscribeStripBody.ACTION, UnsubscribeStripBody.FAILED -> HeaderCardAction(")
        assertTrue("the failure no longer shares the action's branch. Strip was:\n${strip.joinToString("\n")}", at >= 0)
        assertEquals(
            listOf(
                "UnsubscribeStripBody.ACTION, UnsubscribeStripBody.FAILED -> HeaderCardAction(",
                "stringResource(",
                "if (action == UnsubscribeAction.OPEN_PAGE) R.string.message_unsubscribe_via_page else R.string.message_unsubscribe,",
                "),",
                ")",
            ),
            strip.subList(at, minOf(at + 5, strip.size)),
        )
    }

    @Test fun `nothing else in the reader was moved`() {
        val screen = SOURCE.readText()
        assertTrue(
            "the overflow entry must still gate on offeredUnsubscribeAction",
            "offeredUnsubscribeAction(unsubscribe, unsubscribeState)?.let { action ->" in screen,
        )
        assertTrue(
            "and the confirmation must still stand between the entry and the request",
            "R.string.message_unsubscribe_confirm_title" in screen,
        )
    }

    @Test fun `the fallback label names its subject in both places`() {
        val strip = stripSource()
        val openPage = strip.lines()
            .map { it.trim() }
            .filter { "UnsubscribeAction.OPEN_PAGE" in it && "R.string." in it }
        assertEquals("exactly one OPEN_PAGE label branch in the strip", 1, openPage.size)
        assertTrue(
            "the action must name what the gesture is ABOUT and not only what it does: " +
                "message_unsubscribe_via_page puts the subject first and the page in brackets, so " +
                "an ellipsis eats the gesture and never the word 'unsubscribe'. Branch read:\n" +
                openPage.single(),
            "R.string.message_unsubscribe_via_page" in openPage.single(),
        )
        val entry = overflowEntrySource()
        assertTrue(
            "the overflow entry must take the same label as the strip for OPEN_PAGE: both read " +
                "the one action offeredUnsubscribeAction returned, so naming it two ways would be " +
                "two names for one gesture. Entry was:\n$entry",
            "R.string.message_unsubscribe_via_page" in entry &&
                "R.string.message_unsubscribe_open_page" !in entry,
        )
    }

    @Test fun `only the confirmation is still allowed to say just 'open page'`() {
        val screen = SOURCE.readText()
        assertEquals(
            "R.string.message_unsubscribe_open_page must be left with exactly one use, the " +
                "confirmation's own button: this count holds both halves of the label change at " +
                "once, that the strip and the overflow entry left the bare gesture behind, and " +
                "that the dialog did not. Under the sentence naming the whole URL and what a page " +
                "load shows the sender, \"Open page\" is the exact consent being asked for, and a " +
                "wider word there would consent to something else",
            1,
            Regex("R\\.string\\.message_unsubscribe_open_page").findAll(screen).count(),
        )
        val confirm = confirmDialogSource()
        assertTrue(
            "…and that single use is the confirmation's button. Confirmation was:\n$confirm",
            "R.string.message_unsubscribe_open_page" in confirm,
        )
    }


    private fun overflowEntrySource(): String {
        val text = SOURCE.readText()
        val at = text.indexOf("offeredUnsubscribeAction(unsubscribe, unsubscribeState)?.let { action ->")
        check(at >= 0) { "MessageScreen.kt no longer gates an overflow entry on offeredUnsubscribeAction" }
        val end = text.indexOf("R.string.message_view_headers", at)
        check(end > at) { "the raw-headers entry no longer follows the unsubscribe entry" }
        return text.substring(at, end)
    }

    private fun confirmDialogSource(): String {
        val text = SOURCE.readText()
        val at = text.indexOf("R.string.message_unsubscribe_confirm_title")
        check(at >= 0) { "MessageScreen.kt no longer draws the unsubscribe confirmation" }
        val end = text.indexOf("R.string.settings_cancel", at)
        check(end > at) { "the confirmation no longer ends on its cancel button" }
        return text.substring(at, end)
    }

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun stripSource(): String {
        val text = SOURCE.readText()
        val at = text.indexOf("private fun UnsubscribeStrip(")
        check(at >= 0) { "MessageScreen.kt no longer declares UnsubscribeStrip — was it renamed?" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private companion object {
        val SOURCE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }

        val VIEW_MODEL: File by lazy { File(SOURCE.parentFile, "MessageViewModel.kt") }
    }
}

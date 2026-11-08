package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: [SenderUnverifiedMethodsTest] executes the decision, this pins
 * that the reader still draws what it decided (#276). `MessageScreen` is Compose and
 * [MessageViewModel] an `AndroidViewModel`, neither runs in a JVM test.
 *
 * Holes left open, of the four in `CONTRIBUTING.md`: an argument name says nothing about what it is
 * bound to (1); a pinned line says nothing about the control structure around the block nor about
 * execution order inside `load()` (3); nothing here holds the value the composable collects (4).
 * Hole 2 is closed for the strip: its block is pinned whole, line after line. Nothing here measures
 * how many lines the message takes at a given width and font scale: that the header's height holds
 * once drawn rests on its text never being rewritten before the next message, not on a line count.
 */
class SenderUnverifiedStripWiringTest {

    /** Last line of the header card, after the read receipt; `HeaderCardTest` runs the order. */
    @Test fun `the header card draws the strip last`() {
        val card = codeLines(topLevelSource("private fun HeaderCard("))
        val receipt = card.indexOf("is HeaderCardRow.ReadReceipt -> ReadReceiptStrip(")
        val strip = card.indexOf("is HeaderCardRow.SenderUnverified -> SenderUnverifiedStrip(row.methods, onViewHeaders)")

        assertTrue("HeaderCard no longer draws ReadReceiptStrip; re-read this test", receipt >= 0)
        assertTrue("HeaderCard does not draw the unverified-sender strip after the read receipt", strip > receipt)
        assertEquals(
            "the warning is the last branch of the card's `when`",
            listOf(
                "is HeaderCardRow.SenderUnverified -> SenderUnverifiedStrip(row.methods, onViewHeaders)",
                "}",
                "}",
                "}",
                "}",
            ),
            card.subList(strip, minOf(strip + 5, card.size)),
        )
        assertTrue(
            "MessageHeader hands the decided text to headerCardRows",
            "senderUnverified = senderUnverified," in codeLines(messageHeaderSource()),
        )
    }

    /** Every line of the screen naming the strip's state or its gesture, whole and in order: the
     *  collection, the two hand-overs, the two parameters, the call and the composable itself. */
    @Test fun `the decided text and the headers gesture reach the strip`() {
        assertEquals(
            listOf(
                "val senderUnverified by viewModel.senderUnverified.collectAsStateWithLifecycle()",
                "senderUnverified = senderUnverified,",
                "onViewHeaders = viewModel::viewHeaders,",
                "senderUnverified: String? = null,",
                "onViewHeaders: () -> Unit = {},",
                "senderUnverified = senderUnverified,",
                "onViewHeaders = onViewHeaders,",
                "senderUnverified: String? = null,",
                "onViewHeaders: () -> Unit = {},",
                "senderUnverified = senderUnverified,",
                "onViewHeaders = onViewHeaders,",
                "onViewHeaders: () -> Unit,",
                "is HeaderCardRow.SenderUnverified -> SenderUnverifiedStrip(row.methods, onViewHeaders)",
                "private fun SenderUnverifiedStrip(methods: String, onViewHeaders: () -> Unit) {",
                "HeaderCardLine(onClick = onViewHeaders) {",
                "text = stringResource(R.string.message_sender_unverified, methods),",
            ),
            codeLines(screen().readText()).filter {
                "senderUnverified" in it || "SenderUnverified" in it || "onViewHeaders" in it ||
                    "message_sender_unverified" in it
            },
        )
    }

    /** The flow is set from the opened message through the decision, and cleared when it fails. */
    @Test fun `the reader decides the strip from the opened message and clears it on failure`() {
        val code = codeLines(viewModel().readText())
        val lines = code.filter { "_senderUnverified.value" in it }

        assertEquals(
            listOf(
                "_senderUnverified.value = null",
                "_senderUnverified.value = senderUnverifiedMethods(anchor.authenticationFailures)",
                "_senderUnverified.value = null",
            ),
            lines,
        )
    }

    /**
     * Set before the body is published, never after: the strip arriving under a document already
     * loading changes the header's measured height, which keys the document, and reloads it.
     */
    @Test fun `the strip is decided before the opened body is published`() {
        val code = codeLines(viewModel().readText())
        val set = code.indexOf("_senderUnverified.value = senderUnverifiedMethods(anchor.authenticationFailures)")
        val published = code.indexOf("_state.value = MessageState.Loaded(anchorDisplay(anchor))")

        assertTrue("load() no longer publishes the opened body as this test expects", published >= 0)
        assertTrue("the strip must be decided before the body is published (set=$set, published=$published)", set in 0 until published)
    }

    /** Opening the headers commits nothing, so the whole line is the gesture: a line of the card like
     *  the others, its geometry `HeaderCardLine`'s, its action text and never a nested button. */
    @Test fun `the whole line opens the headers`() {
        val body = codeLines(stripSource())
        assertEquals(
            "the strip's one gesture is its line's, handed onViewHeaders. Lines naming onViewHeaders:",
            listOf(
                "private fun SenderUnverifiedStrip(methods: String, onViewHeaders: () -> Unit) {",
                "HeaderCardLine(onClick = onViewHeaders) {",
            ),
            body.filter { "onViewHeaders" in it },
        )
        assertEquals(
            "no row, floor, padding, button or clickable of its own: those are HeaderCardLine's",
            emptyList<String>(),
            body.filter {
                it.startsWith("Row(") || "Button" in it || "heightIn" in it || "padding" in it || "clickable" in it
            },
        )
        assertEquals(
            "the action is drawn as text, in the card's primary",
            listOf("HeaderCardAction(stringResource(R.string.message_view_headers))"),
            body.filter { "message_view_headers" in it },
        )
    }

    /**
     * The message wraps: the failed methods are what it says, and an ellipsis cut them off in every
     * language. Pinned whole, so a `maxLines` or an `error` colour slipped back in reddens it. The
     * icon is the card's muted one and says nothing to TalkBack: the sentence does.
     */
    @Test fun `the strip's text wraps in the sentence colour, after a muted warning icon`() {
        val body = codeLines(stripSource())
        val text = body.indexOf("text = stringResource(R.string.message_sender_unverified, methods),")

        assertTrue("SenderUnverifiedStrip no longer shows message_sender_unverified", text >= 2)
        assertEquals(
            listOf(
                "HeaderCardIcon(Icons.Outlined.Warning)",
                "Text(",
                "text = stringResource(R.string.message_sender_unverified, methods),",
                "style = MaterialTheme.typography.bodyMedium,",
                "color = MaterialTheme.colorScheme.onSurface,",
                "modifier = Modifier.weight(1f),",
                ")",
                "HeaderCardAction(stringResource(R.string.message_view_headers))",
                "}",
            ),
            body.subList(text - 2, minOf(text + 7, body.size)),
        )
        assertEquals("no error colour anywhere in the strip", emptyList<String>(), body.filter { "error" in it })
    }

    /** The text of `fun SenderUnverifiedStrip(…)`, up to the next top-level declaration. */
    private fun stripSource(): String = topLevelSource("private fun SenderUnverifiedStrip(")

    /** The text of `fun MessageHeader(…)`, up to the next top-level declaration. */
    private fun messageHeaderSource(): String = topLevelSource("private fun MessageHeader(")

    private fun topLevelSource(declaration: String): String {
        val text = screen().readText()
        val start = text.indexOf(declaration)
        assertTrue("MessageScreen.kt has no `$declaration` any more", start >= 0)
        val end = Regex("""\n(@Composable|private fun |internal fun |fun |/\*\*)""").find(text, start + 1)
            ?.range?.first ?: text.length
        return text.substring(start, end)
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun screen(): File = locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    private fun viewModel(): File = locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

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
}

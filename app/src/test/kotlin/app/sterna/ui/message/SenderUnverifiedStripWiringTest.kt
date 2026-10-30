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

    /** Last of the strips, after the read receipt and before the header's closing divider. */
    @Test fun `the header column draws the strip last, right before its closing divider`() {
        val body = codeLines(messageHeaderSource())
        val receipt = body.indexOf("ReadReceiptStrip(")
        val strip = body.indexOf("senderUnverified?.let { methods ->")

        assertTrue("MessageHeader no longer draws ReadReceiptStrip; re-read this test", receipt >= 0)
        assertTrue("MessageHeader does not draw the unverified-sender strip any more", strip > receipt)
        assertEquals(
            listOf(
                "senderUnverified?.let { methods ->",
                "HorizontalDivider()",
                "SenderUnverifiedStrip(methods, onViewHeaders)",
                "}",
                "HorizontalDivider()",
                "}",
            ),
            body.subList(strip, minOf(strip + 6, body.size)),
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
                "senderUnverified?.let { methods ->",
                "SenderUnverifiedStrip(methods, onViewHeaders)",
                "private fun SenderUnverifiedStrip(methods: String, onViewHeaders: () -> Unit) {",
                "text = stringResource(R.string.message_sender_unverified, methods),",
                "TextButton(onClick = onViewHeaders) {",
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

    /** A floor the button sets: the strip is never shorter than the button, however short the text.
     *  Centred vertically, so a message on two lines keeps the button beside it, not at its top. */
    @Test fun `the strip keeps the button's height as its floor`() {
        val body = codeLines(stripSource())
        val modifier = body.indexOf("modifier = Modifier")

        assertTrue("SenderUnverifiedStrip has no modifier chain any more", modifier >= 0)
        assertEquals(
            listOf(
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".heightIn(min = ButtonDefaults.MinHeight)",
                ".padding(horizontal = 16.dp),",
                "verticalAlignment = Alignment.CenterVertically,",
                ") {",
            ),
            body.subList(modifier, minOf(modifier + 6, body.size)),
        )
    }

    /**
     * The message wraps: the failed methods are what it says, and an ellipsis cut them off in every
     * language. Pinned whole, so a `maxLines` slipped back in reddens it. The button stays one line.
     */
    @Test fun `the strip's text wraps in the error colour, its button stays on one line`() {
        val body = codeLines(stripSource())
        val text = body.indexOf("text = stringResource(R.string.message_sender_unverified, methods),")
        val button = body.indexOf("TextButton(onClick = onViewHeaders) {")

        assertTrue("SenderUnverifiedStrip no longer shows message_sender_unverified", text >= 1)
        assertEquals(
            listOf(
                "Text(",
                "text = stringResource(R.string.message_sender_unverified, methods),",
                "style = MaterialTheme.typography.bodyMedium,",
                "color = MaterialTheme.colorScheme.error,",
                "modifier = Modifier.weight(1f).padding(end = 8.dp),",
                ")",
            ),
            body.subList(text - 1, minOf(text + 5, body.size)),
        )
        assertTrue("SenderUnverifiedStrip no longer offers View headers", button >= 0)
        assertEquals(
            listOf(
                "TextButton(onClick = onViewHeaders) {",
                "Text(stringResource(R.string.message_view_headers), maxLines = 1, overflow = TextOverflow.Ellipsis)",
                "}",
            ),
            body.subList(button, minOf(button + 3, body.size)),
        )
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

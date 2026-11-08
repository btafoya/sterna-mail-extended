package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two questions the "images are blocked" strip turns on, run rather than re-stated, plus the
 * source rules a JVM test cannot measure.
 */
class RemoteImagesTest {

    // ── hasRemoteRefs, executed ────────────────────────────────────────────────────────────────

    @Test fun `an http image is remote`() {
        assertTrue(hasRemoteRefs("""<p>hello</p><img src="http://t.example.com/px.gif">"""))
    }

    @Test fun `an https image is remote`() {
        assertTrue(hasRemoteRefs("""<img src="https://t.example.com/px.gif" width="1">"""))
    }

    @Test fun `a protocol-relative image is remote`() {
        assertTrue(
            "'//h/x.gif' carries no scheme at all and is fetched over the network just the same. " +
                "Keying this on http/https is the exact defect shouldInterceptRequest was " +
                "corrected for; here it would leave the message with no strip at all, so the " +
                "reader is never told the pictures were held back",
            hasRemoteRefs("""<img src="//h/x.gif">"""),
        )
    }

    @Test fun `an exotic scheme is remote too`() {
        assertTrue(
            "the client blocks everything that is not data:, cid: or about: — ftp: included — so " +
                "the strip must appear for it too, or the reader sees a hole and no explanation",
            hasRemoteRefs("""<img src="ftp://files.example.com/x.gif">"""),
        )
    }

    @Test fun `an inline cid image is not remote`() {
        assertFalse(
            "a cid: part travels inside the message and is never fetched: announcing it would " +
                "offer a button that has nothing to unblock",
            hasRemoteRefs("""<img src="cid:logo@example.com">"""),
        )
    }

    @Test fun `a data image is not remote`() {
        assertFalse(hasRemoteRefs("""<img src="data:image/png;base64,iVBORw0KGgo=">"""))
    }

    @Test fun `a body that is not there announces nothing`() {
        assertFalse(hasRemoteRefs(null))
    }

    @Test fun `a body with no markup at all announces nothing`() {
        assertFalse(hasRemoteRefs("Hello, see you Tuesday."))
    }

    @Test fun `a link is not a resource`() {
        assertFalse(
            "an <a href> is not loaded by the page — nothing about it is blocked, and a strip " +
                "over a message whose only remote thing is a link is a strip about nothing",
            hasRemoteRefs("""<p><a href="http://example.com/offer">click</a></p>"""),
        )
    }

    @Test fun `src is read on any element, not only img`() {
        assertTrue(hasRemoteRefs("""<video src="https://v.example.com/a.mp4"></video>"""))
    }

    @Test fun `the attribute is matched whatever its case and spacing`() {
        assertTrue(hasRemoteRefs("""<IMG SRC = 'https://t.example.com/px.gif'>"""))
    }

    @Test fun `an unquoted source still counts`() {
        assertTrue(hasRemoteRefs("<img src=https://t.example.com/px.gif>"))
    }

    @Test fun `a lazy-loading placeholder is not a load`() {
        // Written after watching a mutation survive: dropping the whitespace the attribute regex
        // requires makes `data-src` match, and nothing else in this class could tell. The WebView
        // runs no script, so a data-src is never fetched, announcing it is announcing a block
        // that did not happen, and the button then unblocks nothing.
        assertFalse(hasRemoteRefs("""<img data-src="https://t.example.com/px.gif" src="cid:a@b">"""))
    }

    // ── imagesStripBody, executed ──────────────────────────────────────────────────────────────

    @Test fun `while the pictures are held back the strip is a button`() {
        val body = imagesStripBody(blocked = true)
        assertEquals(ImagesStripBody.ACTION, body)
        assertTrue("blocked is the one state with something left to do", body.acts)
    }

    @Test fun `once they are shown the strip has nothing left to press`() {
        val body = imagesStripBody(blocked = false)
        assertEquals(ImagesStripBody.SHOWN, body)
        assertFalse(
            "there is nothing left to unblock; a live button here would show images twice",
            body.acts,
        )
    }

    /** The state → words correspondence, run: what the line says and what it offers. */
    @Test fun `the held-back state says the pictures are hidden and offers to show them`() {
        val body = imagesStripBody(blocked = true)
        assertEquals(
            "held-back pictures must read R.string.message_images_hidden",
            app.sterna.R.string.message_images_hidden,
            body.line,
        )
        assertEquals(
            "…and offer R.string.message_show at the right of the line",
            app.sterna.R.string.message_show,
            body.action,
        )
    }

    @Test fun `the shown state says so and offers nothing`() {
        val body = imagesStripBody(blocked = false)
        assertEquals(
            "once the pictures are there the line must read R.string.message_images_shown; " +
                "swapped with the other id it says they are shown over held-back pictures",
            app.sterna.R.string.message_images_shown,
            body.line,
        )
        assertNull("nothing left to offer once they are shown", body.action)
    }

    // ── imagesStripPresent, executed ───────────────────────────────────────────────────────────
    //
    // The whole truth table, inputs and answers written out as literals. Nothing here recomputes
    // `!plainText && !senderAllowed && …`: with the rule copied, the shipped condition could be
    // inverted and this section would follow it down.

    @Test fun `in text mode a blocked newsletter gets no strip`() {
        assertFalse(
            "reading mode is TEXT, so the document holds no image to hold back and " +
                "showRemoteImages() stays false for as long as that mode holds. A strip drawn " +
                "here says \"Show images\" with a LIVE button that changes nothing when tapped, " +
                "again and again, for good — the reported defect",
            imagesStripPresent(
                plainText = true,
                tooManyResources = false,
                senderAllowed = false,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in text mode an allowed sender gets no strip either`() {
        assertFalse(
            "nothing is held back and nothing is rendered: two reasons for no strip",
            imagesStripPresent(
                plainText = true,
                tooManyResources = false,
                senderAllowed = true,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in text mode a body with nothing remote gets no strip`() {
        assertFalse(
            imagesStripPresent(plainText = true, tooManyResources = false, senderAllowed = false, html = "<p>hello</p>"),
        )
    }

    @Test fun `in text mode a body that is not there gets no strip`() {
        assertFalse(imagesStripPresent(plainText = true, tooManyResources = false, senderAllowed = false, html = null))
    }

    @Test fun `in HTML mode a blocked newsletter gets its strip`() {
        assertTrue(
            "this is the nominal path of #153: HTML mode, sender not on the allowlist, a remote " +
                "image in the document. No strip here and the reader sees a hole in her " +
                "newsletter and is told nothing about it",
            imagesStripPresent(
                plainText = false,
                tooManyResources = false,
                senderAllowed = false,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in HTML mode an allowed sender gets no strip`() {
        assertFalse(
            "this sender's pictures are fetched without asking, so a strip would offer to " +
                "unblock something that was never blocked",
            imagesStripPresent(
                plainText = false,
                tooManyResources = false,
                senderAllowed = true,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `in HTML mode a body with nothing remote gets no strip`() {
        assertFalse(
            "nothing in this message is refused at load time, so the strip would be a row of " +
                "header about nothing — and the header's height keys the body's HTML document",
            imagesStripPresent(plainText = false, tooManyResources = false, senderAllowed = false, html = "<p>hello</p>"),
        )
    }

    @Test fun `in HTML mode a body that is not there gets no strip`() {
        assertFalse(imagesStripPresent(plainText = false, tooManyResources = false, senderAllowed = false, html = null))
    }

    @Test fun `a newsletter fallen back to text gets no strip`() {
        assertFalse(
            "the reader gave up on this document and paints its text: nothing is held back any " +
                "more, and a strip here offers \"Show images\" over a body with none, then says " +
                "\"Images shown\" when none are",
            imagesStripPresent(
                plainText = false,
                tooManyResources = true,
                senderAllowed = false,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    @Test fun `a newsletter fallen back to text in text mode gets no strip either`() {
        assertFalse(
            imagesStripPresent(
                plainText = true,
                tooManyResources = true,
                senderAllowed = false,
                html = """<img src="https://t.example.com/px.gif">""",
            ),
        )
    }

    /**
     * Source lint, not a measurement: Compose is not run here, so this would still pass on a line
     * that laid out wrong. What it holds is that both states are one call to `HeaderCardLine`, whose
     * floor and inset `HeaderCardTest` pins, with one sentence held to one line.
     */
    @Test fun `the line is one shape, tappable only while the pictures are held back`() {
        val body = codeLines(stripSource()).drop(1)
        assertEquals(
            "ImagesStrip must be exactly these lines. `enabled = body.acts` is the whole difference " +
                "in behaviour: the whole line answers the touch while the pictures are held back and " +
                "is inert once they are shown. `maxLines = 1` with the ellipsis is the height rule: " +
                "the two sentences differ in length in every language, and one that wraps in one " +
                "state makes the header a line taller at the tap, which reloads the body. The words " +
                "come from imagesStripBody, the line never branches on `blocked` itself. Strip " +
                "was:\n${body.joinToString("\n")}",
            STRIP_LINES,
            body,
        )
    }

    /**
     * The call site, which no other rule in this class reads: `stripSource` is the body of
     * `ImagesStrip`, [headerSource] the body of `MessageHeader`, and the call lives in
     * `ConversationBody`, in neither.
     */
    @Test fun `the header is handed the live blocking state, not its opposite`() {
        val call = headerCallLines()
        assertTrue(
            "ConversationBody no longer calls MessageHeader( on a line of its own — was the call " +
                "reformatted or moved? This rule reads that call and nothing else.",
            call.isNotEmpty(),
        )
        assertEquals(
            "ConversationBody must hand MessageHeader these arguments VERBATIM, one whole line " +
                "each: `imagesBlocked` is the LIVE state and is passed straight through, never " +
                "negated and never swapped with the frozen `imagesStrip`. Negated, the strip " +
                "announces \"Images shown\" while the pictures are held back and then offers a " +
                "button that does nothing once they are shown — and every other rule in this " +
                "class stays green. Call site was:\n${call.joinToString("\n")}",
            emptyList<String>(),
            HEADER_WIRING.filterNot { hasLine(call, it) },
        )
        assertTrue(
            "…and the card must hand the line its LIVE argument, verbatim " +
                "'HeaderCardRow.Images -> ImagesStrip(imagesBlocked, onShowImages)'. Handed " +
                "`imagesStrip` instead, the frozen presence flag, the line would keep offering to " +
                "show the pictures once they are on screen. Card was:\n${block("private fun HeaderCard(")}",
            hasLine(block("private fun HeaderCard(").lines().map { it.trim() }, "HeaderCardRow.Images -> ImagesStrip(imagesBlocked, onShowImages)"),
        )
    }

    /**
     * The hop above the one pinned just here, which nothing else in this class or in
     * [ReaderBodyTest] reads: `MessageContent` is where the two answers are handed down.
     */
    @Test fun `the body is handed the reading-mode answer and the blocking state, not their twins`() {
        val call = bodyCallLines()
        assertTrue(
            "MessageContent no longer opens the call on the line " +
                "'$BODY_CALL_OPENING' — was it reformatted or moved? This rule reads that call " +
                "and nothing else, so without this check it would pass on an empty list.",
            call.isNotEmpty(),
        )
        assertEquals(
            "MessageContent must hand ConversationBody these arguments VERBATIM, one whole line " +
                "each: ${BODY_WIRING.joinToString(" · ")}.\n" +
                "`blockRemote = !showRemote` is the app's privacy posture: NEGATED, the images of " +
                "a sender the reader never allowed are fetched — telling him she opened the " +
                "message and handing him her IP — while an allowed sender's are blocked.\n" +
                "`plainText = plainText` is the READING-mode answer; `imageMode` is the " +
                "image-side twin sitting on the line above it, and the two differ until DataStore " +
                "replies — handed that one, every message she opens draws a frame of plain text " +
                "before swapping to HTML, and the strip appears and disappears with it.\n" +
                "Call site was:\n${call.joinToString("\n")}",
            emptyList<String>(),
            BODY_WIRING.filterNot { hasLine(call, it) },
        )
    }

    /**
     * The third door the same blocking answer has to go through, and the one nothing here was
     * watching: the printer. `ReaderBodyTest` pins how `showRemote` is decided, `HEADER_WIRING`
     */
    @Test fun `the printer is handed the same blocking state as the screen`() {
        val lines = block("private fun MessageContent(").lines().map { it.trim() }
        assertEquals(
            "MessageContent must build the printed document with the blocking state the SCREEN " +
                "used, VERBATIM: `$PRINT_CALL_LINE`. Handed `blockRemote = false` (or no " +
                "argument at all), every print and every save-as-PDF goes to the network for " +
                "the sender's remote pictures — telling him she opened the message and handing " +
                "him her IP — for a message whose pictures she never allowed on screen. Handed " +
                "`showRemote`, the refused sender's pictures are fetched and the allowed one's " +
                "are held back. ⛔ EVERY line naming the call is compared, not the ones that " +
                "OPEN with it: a second print tucked after an `if` on the same line would send " +
                "its own document to the network while a `startsWith` rule stayed green. No " +
                "other rule in this class reads this line — bodyCallLines() starts at " +
                "'$BODY_CALL_OPENING', which is below it in the same composable. What was found:",
            listOf(PRINT_CALL_LINE),
            lines.filter { "printDocument(" in it },
        )
        assertEquals(
            "…and `printDocument(` may be named exactly TWICE in the whole file: this one call " +
                "and its own declaration. The rule above reads MessageContent only, so a second " +
                "print path opened anywhere else in this 4 900-line file — a \"print the whole " +
                "conversation\" action, say — could be handed `blockRemote = false` and fetch " +
                "the sender's pictures for a message whose pictures were held back, with that " +
                "rule still green.",
            2,
            SOURCE.readText().lines().count { "printDocument(" in it },
        )
    }

    /**
     * Whether the line is drawn at all, the condition no other rule in this class reads, and the
     * cheapest way left to bring the whole defect back: `headerCardRows` takes the frozen presence,
     * and the live state only reaches the line itself.
     */
    @Test fun `the row is drawn on presence alone, never on the live blocking state`() {
        val header = codeLines(headerSource())
        assertEquals(
            "MessageHeader must hand headerCardRows the frozen presence flag and nothing live. Anded " +
                "with `imagesBlocked`, the line VANISHES at the tap: the header loses about 49 dp at " +
                "once, which re-keys the remember() that builds the body's HTML, and the reader " +
                "waits behind the spinner or loses her place in the message. Lines found:",
            listOf("imagesStrip: Boolean = false,", "imagesStrip = imagesStrip,"),
            header.filter { "imagesStrip" in it },
        )
        assertEquals(
            "…and the live state goes to the card, to be handed to the line, nowhere else",
            listOf("imagesBlocked: Boolean = true,", "imagesBlocked = imagesBlocked,"),
            header.filter { "imagesBlocked" in it },
        )
    }

    /**
     * The presence lock, which is the half of the height rule that is easy to lose.
     */
    @Test fun `the strip's presence is frozen for the life of the page`() {
        assertTrue(
            "ConversationBody must decide the strip on `(full, plainText, tooManyResources)` and on " +
                "nothing else, and hand the last two to imagesStripPresent. " +
                "Every other key costs body reloads for no gesture: this strip's own height is an " +
                "input to the document's key (header height -> topSpacerCssPx), so anything that " +
                "flips the presence reloads the body a SECOND time, after the mode's own reload. " +
                "That is paid only for the reading-mode toggle and the text fallback, which " +
                "replace the document and re-top the message anyway. NOT `imageMode`, which flips " +
                "with no gesture when DataStore answers; NOT `senderAllowed`, which promises to " +
                "move nothing. Expected these lines, verbatim and in order:\n  " +
                PRESENCE_LINES.joinToString("\n  ") + "\nMessageScreen.kt has no such pair.",
            screenLines().windowed(PRESENCE_LINES.size).any { it == PRESENCE_LINES },
        )
    }

    /**
     * The lot's scope, held by a rule rather than by good intentions.
     */
    @Test fun `nothing else in the reader was moved`() {
        assertTrue(
            "the overflow entry must STAY: it is the only way to show the images when the " +
                "detection returns a false negative (CSS url(), srcset, background=, …), and a " +
                "false negative is the case where the reader has no strip to tap",
            hasLine(screenLines(), "text = { Text(stringResource(R.string.message_show_images)) },"),
        )
        assertTrue(
            "the card draws the images line on its own row kind; the order of the card's lines is " +
                "held by HeaderCardTest. Card was:\n${block("private fun HeaderCard(")}",
            hasLine(block("private fun HeaderCard(").lines().map { it.trim() }, "HeaderCardRow.Images -> ImagesStrip(imagesBlocked, onShowImages)"),
        )
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Whole-line match. The only slack is the trailing comma Kotlin puts on the last element of an
     * argument or modifier chain; anything else added to the line fails the rule, which is the
     * whole point of not using `contains`.
     */
    private fun hasLine(lines: List<String>, exact: String) =
        lines.any { it == exact || it == "$exact," }

    private fun screenLines(): List<String> = SOURCE.readText().lines().map { it.trim() }

    private fun stripSource(): String = block("private fun ImagesStrip(")

    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun headerSource(): String = block("private fun MessageHeader(")

    /**
     * The `MessageHeader(...)` call inside `ConversationBody`, the arguments only, not the whole
     */
    private fun headerCallLines(): List<String> =
        block("private fun ConversationBody(").lines().map { it.trim() }
            .dropWhile { it != "MessageHeader(" }
            .takeWhile { it != ")" }

    /**
     * The `ConversationBody(...)` call inside `MessageContent`, the arguments only, on the same
     */
    private fun bodyCallLines(): List<String> =
        block("private fun MessageContent(").lines().map { it.trim() }
            .dropWhile { it != BODY_CALL_OPENING }
            .takeWhile { it != ")" }

    private fun block(signature: String): String {
        val text = SOURCE.readText()
        val at = text.indexOf(signature)
        check(at >= 0) { "MessageScreen.kt no longer declares `$signature` — was it renamed or moved out?" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private companion object {
        /**
         * The images line, whole, after its signature. Both states are this one call, so the line
         * cannot be of two heights; only `enabled` and the words change, and both come from
         * [imagesStripBody].
         */
        val STRIP_LINES = listOf(
            "val body = imagesStripBody(blocked)",
            "HeaderCardLine(onClick = onShowImages, enabled = body.acts) {",
            "HeaderCardIcon(Icons.Filled.Image)",
            "Text(",
            "text = stringResource(body.line),",
            "style = MaterialTheme.typography.bodyMedium,",
            "color = MaterialTheme.colorScheme.onSurface,",
            "maxLines = 1,",
            "overflow = TextOverflow.Ellipsis,",
            "modifier = Modifier.weight(1f),",
            ")",
            "body.action?.let { HeaderCardAction(stringResource(it)) }",
            "}",
            "}",
        )

        /** The three arguments the images strip needs, as they must appear at the call site. */
        val HEADER_WIRING = listOf(
            "imagesStrip = imagesStrip",
            "imagesBlocked = blockRemote",
            "onShowImages = onShowImages",
        )

        /** Where `MessageContent` hands the page down; the arguments below are read from it. */
        const val BODY_CALL_OPENING = "is MessageState.Loaded -> ConversationBody("

        /**
         * The two answers handed down from `MessageContent`, as they must appear at that call
         * site. Each has a twin one token away (`showRemote`, `imageMode`) that compiles.
         */
        val BODY_WIRING = listOf(
            "blockRemote = !showRemote",
            "plainText = plainText",
        )

        /**
         * The print call, whole. `blockRemote` is the third and last place the reader's answer
         * about remote pictures has to be honoured, after the strip and the rendered page.
         */
        const val PRINT_CALL_LINE =
            "printDocument(activity, doc, printJobName(header.subject, appName), msg.inlineImages, " +
                "blockRemote = !showRemote)"

        val PRESENCE_LINES = listOf(
            "val imagesStrip = remember(full, plainText, tooManyResources) {",
            "imagesStripPresent(plainText, tooManyResources, senderAllowed, full?.htmlContent())",
        )


        val SOURCE: File by lazy {
            val root = generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        }
    }
}

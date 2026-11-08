package app.sterna.ui.message

import app.sterna.R
import app.sterna.core.data.mail.ReadReceiptPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * The read-receipt strip: when there is one at all, what it says, and the one property that
 * matters beyond looks, the three ordinary states are the same height.
 */
class ReadReceiptStripTest {

    private val asked = ReadReceiptRequest(
        receiptTo = listOf("sender@example.org"),
        originalSubject = "Quarterly report",
        originalMessageId = "<abc@example.org>",
        deliveredTo = "alias@mine.example",
    )

    // -- 1. whether there is a strip at all ---------------------------------------------------------

    /**
     * NO offer, NO strip, and no divider either. This is the state of very nearly every message
     * ever opened in this app: the reader never turned the switch on, or nobody asked.
     */
    @Test fun `no question means no strip`() {
        assertNull(readReceiptStrip(null, ReadReceiptState.Idle))
    }

    /**
     * The switch, executed through the strip. The two functions are composed here rather than
     */
    @Test fun `a switch that is off, or not yet read, draws nothing`() {
        listOf(ReadReceiptSetting.OFF, ReadReceiptSetting.NOT_LOADED).forEach { setting ->
            val offer = offeredReadReceipt(
                setting,
                ReadMarking.READER_SETTLE_UNREAD,
                asked,
                answered = false,
            )
            assertNull("with the setting $setting there is nothing to offer", offer)
            assertNull(
                "…and therefore no strip: with $setting the header must draw no row and no " +
                    "divider at all",
                readReceiptStrip(offer, ReadReceiptState.Idle),
            )
        }
    }

    /**
     * Saying NO sends nothing, and the strip goes. The refusal is executed through the same two
     */
    @Test fun `a refusal leaves no strip and nothing to send`() {
        val afterRefusal = offeredReadReceipt(
            ReadReceiptSetting.ON,
            ReadMarking.READER_SETTLE_UNREAD,
            asked,
            answered = ReadReceiptAnswer.DECLINED.answered,
        )

        assertNull(afterRefusal)
        assertNull(readReceiptStrip(afterRefusal, ReadReceiptState.Idle))
    }

    /** The question, when there is one: the ask shape, naming who would be told. */
    @Test fun `a standing question is the ask shape, naming who would be told`() {
        assertEquals(
            ReadReceiptStrip(ReadReceiptStripBody.ASK, "sender@example.org"),
            readReceiptStrip(asked, ReadReceiptState.Idle),
        )
    }

    /**
     * Every address the sender named is shown, not just the first: the reader is deciding who gets
     * told, and a second address hidden behind a comma is a decision she was not asked for.
     */
    @Test fun `every address the header named is on the line`() {
        val twice = asked.copy(receiptTo = listOf("sender@example.org", "notify@example.org"))

        assertEquals(
            "sender@example.org, notify@example.org",
            readReceiptStrip(twice, ReadReceiptState.Idle)?.named,
        )
    }

    // -- 2. what it says once the reader has said yes ----------------------------------------------

    /**
     * The text shown is the text that was queued, not one this file could rebuild. The subject
     */
    @Test fun `in the outbox, the strip names the row that was queued`() {
        val queued = ReadReceiptPreview(
            subject = "not a wording this app builds",
            body = "nor is this — it comes back from the row that was queued",
        )

        val strip = readReceiptStrip(null, ReadReceiptState.Queued(queued))

        assertEquals(ReadReceiptStripBody.QUEUED, strip?.body)
        assertSame(queued.subject, strip?.named)
    }

    /** In flight, and afterwards on failure, there is nothing to name, and the strip stays. */
    @Test fun `sending and failing draw a strip that names nothing`() {
        assertEquals(
            ReadReceiptStrip(ReadReceiptStripBody.SENDING, ""),
            readReceiptStrip(null, ReadReceiptState.Sending),
        )
        assertEquals(
            ReadReceiptStrip(ReadReceiptStripBody.FAILED, ""),
            readReceiptStrip(null, ReadReceiptState.Failed),
        )
    }

    /**
     * There is NO "sent", [ReadReceiptState] has four states and the strip has four shapes, one
     */
    @Test fun `every state of the receipt has exactly one shape`() {
        assertEquals(
            listOf(
                ReadReceiptStripBody.ASK,
                ReadReceiptStripBody.SENDING,
                ReadReceiptStripBody.QUEUED,
                ReadReceiptStripBody.FAILED,
            ),
            ReadReceiptStripBody.entries.toList(),
        )
        assertEquals(
            ReadReceiptStripBody.SENDING,
            readReceiptStrip(asked, ReadReceiptState.Sending)?.body,
        )
        assertEquals(
            ReadReceiptStripBody.FAILED,
            readReceiptStrip(asked, ReadReceiptState.Failed)?.body,
        )
    }

    // -- 3. the height ---------------------------------------------------------------------------

    /**
     * Which states are the same row, in literals. This is the whole point of the enum: asking,
     * sending and queueing are one shape, and only the failure may grow.
     */
    @Test fun `three of the four states are the same row, and only the failure may grow`() {
        assertEquals(
            mapOf(
                ReadReceiptStripShape.BUTTON_ROW to listOf(
                    ReadReceiptStripBody.ASK,
                    ReadReceiptStripBody.SENDING,
                    ReadReceiptStripBody.QUEUED,
                ),
                ReadReceiptStripShape.SENTENCE to listOf(ReadReceiptStripBody.FAILED),
            ),
            ReadReceiptStripBody.entries.groupBy { it.shape },
        )
    }

    /**
     * And the failure is only ever reached after a gesture: it cannot be the shape of a question.
     */
    @Test fun `the shape that may grow is never the one that asks`() {
        assertEquals(
            ReadReceiptStripShape.BUTTON_ROW,
            readReceiptStrip(asked, ReadReceiptState.Idle)?.body?.shape,
        )
    }

    /**
     * Which states act, and which may be refused, in literals, both ways.
     */
    @Test fun `the question and the failure act, and only the question can be refused`() {
        assertEquals(
            listOf(ReadReceiptStripBody.ASK, ReadReceiptStripBody.FAILED),
            ReadReceiptStripBody.entries.filter { it.acts },
        )
        assertEquals(
            listOf(ReadReceiptStripBody.ASK),
            ReadReceiptStripBody.entries.filter { it.declines },
        )
    }

    /**
     * Which words each state wears, by resource name, both the line and the button.
     */
    @Test fun `each state wears its own line and its own button label`() {
        assertEquals(
            mapOf(
                ReadReceiptStripBody.ASK to
                    ("message_read_receipt_ask" to "message_read_receipt_send"),
                ReadReceiptStripBody.SENDING to
                    ("message_read_receipt_sending" to "message_read_receipt_send_sending"),
                ReadReceiptStripBody.QUEUED to
                    ("message_read_receipt_queued" to "message_read_receipt_send_queued"),
                ReadReceiptStripBody.FAILED to
                    ("message_read_receipt_failed" to "message_read_receipt_send"),
            ),
            ReadReceiptStripBody.entries.associate { it to (nameOf(it.line) to nameOf(it.button)) },
        )
    }

    /** The two lines that carry a `%1$s`, and the two that do not, a mismatch is a crash or a
     *  blank. */
    @Test fun `only the two lines that name something are formatted`() {
        assertEquals(
            listOf(ReadReceiptStripBody.ASK, ReadReceiptStripBody.QUEUED),
            ReadReceiptStripBody.entries.filter { it.names },
        )
        // …and the decision supplies something to put in them, for exactly those two.
        assertEquals("sender@example.org", readReceiptStrip(asked, ReadReceiptState.Idle)?.named)
        assertEquals("", readReceiptStrip(asked, ReadReceiptState.Sending)?.named)
        assertEquals("", readReceiptStrip(asked, ReadReceiptState.Failed)?.named)
    }

    // -- 4. the strip as source: what no JVM test can run -------------------------------------------

    /**
     * Source lint, not a measurement. Compose is not run here; these rules read the strip as text
     */
    @Test fun `the strip adds no geometry to the card's line`() {
        val strip = stripLines()
        assertEquals(
            "the strip's floor and inset are HeaderCardLine's; anything added here can make one " +
                "state taller than another and reload the body under it. Geometry found in the strip:\n" +
                strip.filter { line -> GEOMETRY_WORDS.any { it in line.lowercase() } }
                    .joinToString("\n"),
            ALLOWED_GEOMETRY,
            strip.filter { line -> GEOMETRY_WORDS.any { it in line.lowercase() } }.toSet(),
        )
    }

    /** A question with two answers: the line takes no touch, only its two buttons do. */
    @Test fun `the line takes no touch, only its two buttons do`() {
        val strip = stripLines()
        assertEquals(
            "the strip must stand on HeaderCardLine(onClick = null): no clickable on the row",
            listOf("HeaderCardLine(onClick = null) {"),
            strip.filter { it.startsWith("HeaderCardLine(") },
        )
        assertEquals(
            "the only touches in the strip are the cross and the button. Lines found:",
            listOf(
                "HeaderCardLine(onClick = null) {",
                "IconButton(onClick = onDeclineReadReceipt) {",
                "onClick = onSendReadReceipt,",
            ),
            strip.filter { "onClick" in it || "clickable" in it },
        )
    }

    /**
     * Every composable the strip may draw, in order, the rule written the other way round, so
     */
    @Test fun `the strip draws these composables and no others`() {
        val drawn = stripLines().mapNotNull { line -> DRAW_CALL.find(line)?.value }
        assertEquals(
            "the strip's composables changed. It is one card line: the icon, the sentence, the refusal (a cross), and " +
                "the one button — with the spinner INSIDE that button, in the icon's place. " +
                "Anything added here is a second shape, and a second shape is a body reload.",
            ALLOWED_DRAWS,
            drawn,
        )
    }

    /** The line: one line in the ordinary states, the error colour when it is a failure. */
    @Test fun `the line is held to one line, and a failure is coloured as one`() {
        val strip = stripLines()
        LINE_RULES.forEach { rule ->
            assertEquals(
                "the strip's line must contain this line verbatim:\n  $rule\nStrip was:\n" +
                    strip.joinToString("\n"),
                1,
                strip.count { it == rule },
            )
        }
        assertEquals(
            "BOTH texts in this strip are ellipsised — the line beside the button AND the " +
                "button's own label. A label allowed to wrap makes the strip a whole line taller " +
                "in one state and not in another, which is the defect at its largest; German and " +
                "Russian are the longest of the ten.",
            2,
            strip.count { it == "overflow = TextOverflow.Ellipsis," },
        )
    }

    /**
     * The button, and the two things about it that a swap makes invisible: it fires the send,
     * and it is drawn UNCONDITIONALLY, in every state, not only in those that can be pressed.
     */
    @Test fun `the one button is drawn in every state, and it sends`() {
        val strip = stripLines()
        BUTTON_RULES.forEach { rule ->
            assertEquals(
                "the strip's button must contain this line verbatim:\n  $rule\nStrip was:\n" +
                    strip.joinToString("\n"),
                1,
                strip.count { it == rule },
            )
        }
        assertEquals(
            "the strip has exactly two branches: the refusal, and the spinner inside the button. " +
                "A third — `if (body.acts) {` around the TextButton, `if (body.shape == …) {` " +
                "around the row's tail — takes the button out of a state and with it the height " +
                "the header is standing on. Branches found:",
            listOf(
                "if (body.declines) {",
                "if (body == ReadReceiptStripBody.SENDING) {",
            ),
            strip.filter { it.startsWith("if (") },
        )
        assertEquals(
            "the ONLY thing `body.shape` may decide is whether the line may wrap",
            listOf(
                "maxLines = if (body.shape == ReadReceiptStripShape.BUTTON_ROW) 1 else Int.MAX_VALUE,",
            ),
            strip.filter { "body.shape" in it },
        )
    }

    /** The refusal is its own callback. Wired to the other one, a reader saying no answers a
     *  stranger, and no other rule in this file would see it. */
    @Test fun `the cross refuses and the button sends, and they are not the same callback`() {
        val strip = stripLines()
        assertEquals(
            "the refusal must be an IconButton on `onDeclineReadReceipt`, gated on `body.declines`",
            listOf("if (body.declines) {", "IconButton(onClick = onDeclineReadReceipt) {"),
            strip.filter { "declines" in it || "IconButton(" in it },
        )
        assertEquals(
            "and the button must fire `onSendReadReceipt` — the strip takes it as a parameter " +
                "and hands it to ONE onClick, nothing else",
            listOf("onSendReadReceipt: () -> Unit,", "onClick = onSendReadReceipt,"),
            strip.filter { "onSendReadReceipt" in it },
        )
    }

    // -- 5. the wiring, from the ViewModel to the row ----------------------------------------------

    /**
     * The presence of the row reads the offer, and its divider goes with it.
     */
    @Test fun `the header draws the strip on the decision, and the card hands both gestures by name`() {
        val header = headerLines().filterNot { it.startsWith("//") }
        assertEquals(
            "MessageHeader must run the decision once and hand it to headerCardRows, which decides " +
                "whether the line is drawn. Lines mentioning readReceiptStrip / ReadReceiptStrip:\n" +
                header.filter { "eadReceiptStrip" in it }.joinToString("\n"),
            listOf("readReceipt = readReceiptStrip(readReceiptOffer, readReceiptState),"),
            header.filter { "eadReceiptStrip" in it },
        )
        val card = block("private fun HeaderCard(").lines().map { it.trim() }.filterNot { it.startsWith("//") }
        assertEquals(
            "the card must draw the line with the decided strip and the two callbacks BY NAME, and " +
                "only the unverified-sender warning may follow it",
            listOf(
                "is HeaderCardRow.ReadReceipt -> ReadReceiptStrip(",
                "row.strip,",
                "onSendReadReceipt = onSendReadReceipt,",
                "onDeclineReadReceipt = onDeclineReadReceipt,",
                ")",
                "is HeaderCardRow.SenderUnverified -> SenderUnverifiedStrip(row.methods, onViewHeaders)",
            ),
            card.dropWhile { !it.startsWith("is HeaderCardRow.ReadReceipt ->") }.take(6),
        )
    }

    /**
     * The reader's two flows, collected, and the two callbacks, wired the right way round.
     */
    @Test fun `the screen collects the offer and the state, and wires both gestures by name`() {
        val screen = screenLines()
        WIRING.forEach { line ->
            assertEquals(
                "MessageScreen.kt must carry this line verbatim, exactly once:\n  $line\n" +
                    "Lines mentioning ReadReceipt:\n" +
                    screen.filter { "eadReceipt" in it }.joinToString("\n"),
                1,
                screen.count { it == line },
            )
        }
        NAMED_HANDOVERS.forEach { (line, times) ->
            assertEquals(
                "the two lambdas are handed over BY NAME at every level — ConversationBody → " +
                    "MessageHeader → HeaderCard → ReadReceiptStrip. This line must appear exactly $times " +
                    "times:\n  $line\nAnything positional there compiles with the two swapped, " +
                    "and then the cross that says 'do not answer' queues the receipt.",
                times,
                screen.count { it == line },
            )
        }
    }

    /**
     * And the screen builds no wording of a receipt of its own: what it shows is what
     * `sendReadReceipt` put in the outbox row, relayed. The same rule the ViewModel is held to.
     */
    @Test fun `the screen never rebuilds what a receipt says`() {
        assertEquals(
            "the strip must not compose a receipt's subject or body — those strings are the ones " +
                "that LEFT the device, handed back by the repository. Found:",
            emptyList<String>(),
            screenLines().filter { "readReceiptPreview(" in it || "ReadReceiptPreview(" in it },
        )
    }

    // -- reading the source -------------------------------------------------------------------------

    /**
     * The name of a string resource, so a swapped label fails with words rather than with two
     * generated integers nobody can tell apart.
     */
    private fun nameOf(id: Int): String = R.string::class.java.fields
        .firstOrNull { it.type == Int::class.java && it.getInt(null) == id }
        ?.name
        ?: error("no name in R.string for id $id — the generated R has changed shape")

    private fun screenLines(): List<String> = SOURCE.readLines().map { it.trim() }

    private fun stripLines(): List<String> =
        block("private fun ReadReceiptStrip(").lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") }

    private fun headerSource(): String = block("private fun MessageHeader(")

    private fun headerLines(): List<String> = headerSource().lines().map { it.trim() }

    private fun block(signature: String): String {
        val text = SOURCE.readText()
        val at = text.indexOf(signature)
        check(at >= 0) { "MessageScreen.kt no longer declares `$signature` — was it renamed or moved out?" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private companion object {
        /** The only lines of the strip allowed to carry any geometry at all. */
        val ALLOWED_GEOMETRY = setOf(
            "modifier = Modifier.weight(1f).padding(end = 8.dp),",
            "CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)",
            "Spacer(Modifier.width(8.dp))",
            "modifier = Modifier.size(18.dp),",
        )

        /** Anything that can make a row taller (or add a second thing to it), matched lowercased. */
        val GEOMETRY_WORDS = listOf(
            "padding", "spacer", "height", "size", "offset", ".dp", "weight", "aspectratio",
        )

        /** Every composable the strip may draw, by its opening call, in order. */
        val ALLOWED_DRAWS = listOf(
            "HeaderCardLine(",
            "HeaderCardIcon(",
            "Text(",
            "IconButton(",
            "Icon(",
            "TextButton(",
            "CircularProgressIndicator(",
            "Spacer(",
            "Text(",
        )

        /** A composable call opening a line: `Foo(`. Modifier chains start with `.`, arguments
         *  with a name. */
        val DRAW_CALL = Regex("""^[A-Z]\w*\(""")

        /** The line beside the button: one line in the ordinary states, error-coloured on failure. */
        val LINE_RULES = listOf(
            "text = if (body.names) stringResource(body.line, strip.named) else stringResource(body.line),",
            "color = if (body == ReadReceiptStripBody.FAILED) {",
            "MaterialTheme.colorScheme.error",
            "MaterialTheme.colorScheme.onSurface",
            "maxLines = if (body.shape == ReadReceiptStripShape.BUTTON_ROW) 1 else Int.MAX_VALUE,",
        )

        /** The one button: it sends, it is live only where the decision says so, and it says so
         *  legibly once it is spent. */
        val BUTTON_RULES = listOf(
            "TextButton(",
            "onClick = onSendReadReceipt,",
            "enabled = body.acts,",
            "disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,",
            "if (body == ReadReceiptStripBody.SENDING) {",
            "CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)",
            // The label comes from the state, never from one fixed resource: a constant "Send"
            // greyed out beside "In the outbox: …" is a button lying about where things stand.
            "stringResource(body.button),",
        )

        /**
         * What the screen must carry, whole, from the ViewModel down to the row.
         */
        val WIRING = listOf(
            "val readReceiptOffer by viewModel.readReceiptOffer.collectAsStateWithLifecycle()",
            "val readReceiptState by viewModel.readReceiptState.collectAsStateWithLifecycle()",
            "onSendReadReceipt = viewModel::sendReadReceipt,",
            "onDeclineReadReceipt = viewModel::declineReadReceipt,",
        )

        /**
         * Every hand-over between the three levels, by name, `ConversationBody`'s and
         * `MessageHeader`'s.
         */
        val NAMED_HANDOVERS = mapOf(
            "onSendReadReceipt = onSendReadReceipt," to 3,
            "onDeclineReadReceipt = onDeclineReadReceipt," to 3,
            "readReceiptOffer = readReceiptOffer," to 2,
            "readReceiptState = readReceiptState," to 2,
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

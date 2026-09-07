package app.sterna.ui.message

import app.sterna.R
import app.sterna.core.data.mail.ReadReceiptPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

/**
 * The read-receipt strip: WHEN there is one at all, WHAT it says, and the one property that
 * matters beyond looks — the three ordinary states are the same height.
 *
 * Height is not a taste here. The measured height of the reader's header is part of the key of the
 * `remember` that builds the body's HTML document, so a strip that changes size cancels the body
 * load in flight — and this strip sits in the header, which is exactly where its two gestures are
 * made. `heightIn` is a FLOOR and cannot level two different shapes: a `TextButton` stands well
 * above it while a bare `Text` sits back down on it, measured on the bench for the images strip
 * (#153) at 8 dp in the normal font and 13 dp at `font_scale 2.0`. So the states that are not a
 * failure draw the SAME button, and [ReadReceiptStripShape] is where that is written down.
 *
 * What a JVM test CAN do is RUN [readReceiptStrip] and read [ReadReceiptStripBody]'s table; what it
 * cannot do is measure Compose, so the source rules at the end say so and pin the ARGUMENTS — the
 * height floor, which shapes draw the button, which callback each gesture is wired to — because a
 * rule that only checks that `TextButton(` appears is satisfied by any strip containing one.
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
     * NO OFFER, NO STRIP — and no divider either. This is the state of very nearly every message
     * ever opened in this app: the reader never turned the switch on, or nobody asked.
     */
    @Test fun `no question means no strip`() {
        assertNull(readReceiptStrip(null, ReadReceiptState.Idle))
    }

    /**
     * THE SWITCH, EXECUTED THROUGH THE STRIP. The two functions are composed here rather than
     * described: whatever [offeredReadReceipt] answers for a setting that is off — or has not been
     * read yet, which is NOT the same thing — is what the strip is built from, and both answers are
     * "there is nothing to draw".
     *
     * Written this way round on purpose: the strip must not carry a rule of its own about the
     * setting. It has no way to see one, and a second guard here would be a second thing to keep in
     * step with the first.
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
     * SAYING NO SENDS NOTHING, AND THE STRIP GOES. The refusal is executed through the same two
     * functions the app runs: the reader has answered, so the question is null, so there is no
     * strip — and nothing anywhere in this path can queue anything.
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

    /** The question, when there is one: the ASK shape, naming who would be told. */
    @Test fun `a standing question is the ask shape, naming who would be told`() {
        assertEquals(
            ReadReceiptStrip(ReadReceiptStripBody.ASK, "sender@example.org"),
            readReceiptStrip(asked, ReadReceiptState.Idle),
        )
    }

    /**
     * Every address the sender named is shown, not just the first: the reader is deciding WHO gets
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
     * THE TEXT SHOWN IS THE TEXT THAT WAS QUEUED, not one this file could rebuild. The subject
     * below is deliberately not a wording this app composes for a receipt: if anything on the way
     * recomputed it, it could not come out the other end. `assertSame` because it must be the very
     * string the repository handed back.
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

    /** In flight, and afterwards on failure, there is nothing to name — and the strip stays. */
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
     * There is NO "sent" — [ReadReceiptState] has four states and the strip has four shapes, one
     * for one. A fifth of either is a claim this app cannot keep: a receipt has one road, the
     * durable outbox, and nothing here watches it leave.
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
     * WHICH STATES ARE THE SAME ROW, IN LITERALS. This is the whole point of the enum: asking,
     * sending and queueing are one shape, and only the failure may grow.
     *
     * Written out by hand rather than derived: this expectation is what goes red when someone
     * "tidies" the queued state into a bare sentence, which is the defect — the header would lose
     * the button's height at the tap, re-key the body's HTML document and cancel the load under the
     * reader's fingers.
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
     * And the failure is only ever reached AFTER a gesture: it cannot be the shape of a question.
     * (`readReceiptStrip` maps it off [ReadReceiptState.Failed], which only `sendReadReceipt` can
     * write — see [ReadReceiptTriggerWiringTest].)
     */
    @Test fun `the shape that may grow is never the one that asks`() {
        assertEquals(
            ReadReceiptStripShape.BUTTON_ROW,
            readReceiptStrip(asked, ReadReceiptState.Idle)?.body?.shape,
        )
    }

    /**
     * Which states act, and which may be refused — in literals, both ways.
     *
     * `acts` is the button's `enabled`. [ReadReceiptStripBody.ASK] acts because that is the
     * question; [ReadReceiptStripBody.FAILED] acts because nothing left the device and a refused
     * enqueue is a retry, not a dead end — the posture `UnsubscribeStripBody` already takes, and
     * `MessageViewModel.sendReadReceipt` puts the question back through the decision when it
     * fails. [ReadReceiptStripBody.QUEUED] must never act: what is in the outbox cannot be
     * recalled and a second tap would answer the stranger twice.
     *
     * `declines` is the refusal, offered only while the question stands untouched.
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
     * WHICH WORDS EACH STATE WEARS, by resource name, both the line and the button.
     *
     * Nothing else in this class reads either: `names`, `shape`, `acts` and `declines` are the same
     * on two of these states, so swapping [ReadReceiptStripBody.ASK]'s and
     * [ReadReceiptStripBody.QUEUED]'s resources leaves every other rule green — and the strip then
     * says "In the outbox: …" over a LIVE send button, before anything has left at all.
     *
     * And the button's label is not constant: a greyed "Send" beside a line saying the answer is
     * already queued is a button that lies about the state it is in.
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

    /** The two lines that carry a `%1$s`, and the two that do not — a mismatch is a crash or a
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
     * SOURCE LINT, NOT A MEASUREMENT. Compose is not run here; these rules read the strip as text
     * and would still pass if it laid out wrong. What they hold is what a reviewer cannot see: the
     * height floor, that every ordinary state stands on the same button, and which gesture each
     * callback is.
     *
     * Whole lines, never `contains` for something expected to be present: a substring check is
     * blind to any mutation that LENGTHENS the line, and that blindness has cost this repo three
     * defects.
     */
    @Test fun `the strip stands on a button, with a height floor and no padding of its own`() {
        val strip = stripLines()
        assertEquals(
            "the strip must stand on the button's own minimum height and carry no vertical " +
                "padding of its own, so the states that draw less cannot shrink the header and " +
                "reload the body under it. Geometry found in the strip:\n" +
                strip.filter { line -> GEOMETRY_WORDS.any { it in line.lowercase() } }
                    .joinToString("\n"),
            ALLOWED_GEOMETRY,
            strip.filter { line -> GEOMETRY_WORDS.any { it in line.lowercase() } }.toSet(),
        )
    }

    /**
     * EVERY COMPOSABLE THE STRIP MAY DRAW, IN ORDER — the rule written the other way round, so
     * it goes red on a line ADDED as well as on one removed. A second sentence, a badge or an icon
     * beside the line is a second shape and therefore another height.
     */
    @Test fun `the strip draws these composables and no others`() {
        val drawn = stripLines().mapNotNull { line -> DRAW_CALL.find(line)?.value }
        assertEquals(
            "the strip's composables changed. It is one Row: the line, the refusal (a cross), and " +
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
                "Russian are the longest of the nine.",
            2,
            strip.count { it == "overflow = TextOverflow.Ellipsis," },
        )
    }

    /**
     * THE BUTTON, and the two things about it that a swap makes invisible: it fires the SEND,
     * and it is drawn UNCONDITIONALLY — in every state, not only in those that can be pressed.
     *
     * `if (body.acts) { TextButton(…) }` reads like a tidy-up and is the measured defect: the
     * queued state would fall back to a bare line, the header would lose the button's height, and
     * the body's document would be re-keyed under the reader. The rule is therefore written as an
     * enumeration of the strip's `if`s, so it goes red on a condition ADDED as well as removed.
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
     *  stranger — and no other rule in this file would see it. */
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
     * THE PRESENCE OF THE ROW READS THE OFFER, and its divider goes with it.
     *
     * Pinned whole, because the two cheap mistakes here are invisible everywhere else: drawing the
     * row unconditionally leaves a divider and an empty band in the header of every message in the
     * app, and drawing it on `readReceiptState != Idle` would make the question — the only state
     * that can send anything — the one state that never appears.
     */
    @Test fun `the header draws the strip on the decision, last, with its own divider`() {
        val header = headerLines().filterNot { it.startsWith("//") }
        assertEquals(
            "MessageHeader must open the strip with exactly these lines: the decision, run once, " +
                "deciding the divider AND the row — and the two callbacks handed over BY NAME. " +
                "Lines mentioning readReceiptStrip / ReadReceiptStrip:\n" +
                header.filter { "eadReceiptStrip" in it }.joinToString("\n"),
            listOf(
                "readReceiptStrip(readReceiptOffer, readReceiptState)?.let { strip ->",
                "HorizontalDivider()",
                "ReadReceiptStrip(",
                "strip,",
                "onSendReadReceipt = onSendReadReceipt,",
                "onDeclineReadReceipt = onDeclineReadReceipt,",
                ")",
            ),
            header.dropWhile { !it.startsWith("readReceiptStrip(") }.take(7),
        )
        val strips = header.filter { STRIP_CALL.containsMatchIn(it) }
        assertEquals(
            "the read-receipt strip is the LAST strip in the header: it must never push the " +
                "crypto verdict or a meeting invitation below the fold. Strips found: $strips",
            "ReadReceiptStrip(",
            strips.last(),
        )
    }

    /**
     * The reader's two flows, collected, and the two callbacks, wired the right way round.
     *
     * `onSendReadReceipt = viewModel::declineReadReceipt` compiles, has the same signature, and
     * makes the button do nothing at all; the swap the other way answers a stranger on a reader who
     * refused. Nothing else in this suite can see either.
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
                "the two lambdas are handed over BY NAME at both levels — ConversationBody → " +
                    "MessageHeader → ReadReceiptStrip. This line must appear exactly $times " +
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
     * The NAME of a string resource, so a swapped label fails with words rather than with two
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
        /** The only two lines of the strip allowed to carry any geometry at all. */
        val ALLOWED_GEOMETRY = setOf(
            ".heightIn(min = ButtonDefaults.MinHeight)",
            ".padding(horizontal = 16.dp),",
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
            "Row(",
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

        /** A strip being drawn: a line that STARTS with `FooStrip(`. */
        val STRIP_CALL = Regex("""^[A-Z]\w*Strip\(""")

        /** The line beside the button: one line in the ordinary states, error-coloured on failure. */
        val LINE_RULES = listOf(
            "text = if (body.names) stringResource(body.line, strip.named) else stringResource(body.line),",
            "color = if (body == ReadReceiptStripBody.FAILED) {",
            "MaterialTheme.colorScheme.error",
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
            // The label comes from the STATE, never from one fixed resource: a constant "Send"
            // greyed out beside "In the outbox: …" is a button lying about where things stand.
            "stringResource(body.button),",
        )

        /**
         * What the screen must carry, whole, from the ViewModel down to the row.
         *
         * Every hand-over of the two lambdas is BY NAME, at all three levels. They have the same
         * type, so a positional call plus a swap of two lines in a signature compiles and wires
         * the refusal to the send — the cross would then queue a receipt to a stranger while the
         * button did nothing, and no rule reading either file in isolation could see it. Named,
         * that mistake cannot be expressed; these lines are what keeps it that way.
         */
        val WIRING = listOf(
            "val readReceiptOffer by viewModel.readReceiptOffer.collectAsStateWithLifecycle()",
            "val readReceiptState by viewModel.readReceiptState.collectAsStateWithLifecycle()",
            "onSendReadReceipt = viewModel::sendReadReceipt,",
            "onDeclineReadReceipt = viewModel::declineReadReceipt,",
        )

        /**
         * Every hand-over between the three levels, by name — `ConversationBody`'s and
         * `MessageHeader`'s.
         *
         * The two lambdas are the dangerous pair and the reason this rule exists. The offer and
         * the state joined them when the header's argument list grew past the point where anyone
         * could count positions by eye: a header taking a dozen arguments, four of them ours, is
         * read by nobody, and one slid into the wrong slot compiles.
         */
        val NAMED_HANDOVERS = mapOf(
            "onSendReadReceipt = onSendReadReceipt," to 2,
            "onDeclineReadReceipt = onDeclineReadReceipt," to 2,
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

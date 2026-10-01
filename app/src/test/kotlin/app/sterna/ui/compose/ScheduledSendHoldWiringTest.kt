package app.sterna.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source lint on `ComposeViewModel.scheduleSend`: it reads the source as text and proves nothing
 * about behaviour. `ComposeViewModel` is not instantiable on the JVM; the label it shows is executed
 * in [ScheduledHoldLabelTest], the ordering of write and consume in core:data's LocalDraftReopenTest.
 */
class ScheduledSendHoldWiringTest {

    @Test fun `the Undo window opens between the write and the consume`() {
        val lines = scheduleLines()
        val insert = lines.indexOf(INSERT)
        val enqueue = lines.indexOf(ENQUEUE)
        val holds = lines.withIndex().filter { it.value == HOLD }.map { it.index }
        val consumeLocal = lines.indexOf(CONSUME_LOCAL)
        val consumeOutbox = lines.indexOf(CONSUME_OUTBOX)
        assertTrue("scheduleSend lost '$INSERT'", insert >= 0)
        assertTrue("scheduleSend lost '$ENQUEUE'", enqueue >= 0)
        assertTrue("scheduleSend lost '$CONSUME_LOCAL'", consumeLocal >= 0)
        assertTrue("scheduleSend lost '$CONSUME_OUTBOX'", consumeOutbox >= 0)
        assertEquals("scheduleSend must open exactly one Undo window: '$HOLD'", 1, holds.size)
        val hold = holds.single()
        assertTrue(
            "the Undo is offered only once the row is written AND its worker booked: before them, " +
                "an insert that throws leaves a snackbar offering to undo nothing, then an error " +
                "(insert=$insert, enqueue=$enqueue, hold=$hold)",
            insert < enqueue && enqueue < hold,
        )
        assertTrue(
            "and before the phone's own draft and the queued row it came from are consumed, i.e. " +
                "inside the commit handed to commitThenConsumeLocalDraft " +
                "(hold=$hold, consume=$consumeLocal, consumeEditingOutbox=$consumeOutbox)",
            hold < consumeLocal && hold < consumeOutbox,
        )
    }

    @Test fun `Undo drops the scheduled row and cancels its worker, both by the row's id`() {
        val lines = scheduleLines()
        val hold = lines.indexOf(HOLD)
        assertTrue("no '$HOLD' in scheduleSend", hold >= 0)
        val open = lines.drop(hold).indexOf(") {")
        assertTrue("the hold has no trailing onUndo lambda", open >= 0)
        assertEquals(
            "the onUndo must be exactly these two lines. Without the delete the row stays in the " +
                "Scheduled list and the re-armed worker sends it at the hour while the composer " +
                "shows it back; without the cancel the booked work fires on a deleted row. `id` is " +
                "the one `insertScheduledSend` returned, the same the worker was booked under.",
            listOf("repo.sending.deleteScheduledSend(id)", "ScheduledSends.cancel(app, id)"),
            lines.drop(hold + open + 1).takeWhile { it != "}" },
        )
    }

    @Test fun `the hold carries the schedule's label, the composer's draft, and the send's window`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend")
        assertEquals(
            mapOf("label" to "label", "draft" to "draft", "holdMs" to "SendOutbox.HOLD_MS"),
            callsOf(body, "outbox.hold(").single(),
        )
        val lines = codeLines(body)
        val at = lines.indexOf(LABEL)
        assertTrue("the label must be the instant chosen, through scheduledHoldLabel: '$LABEL'", at >= 0)
        assertEquals(
            "…and its sentence the schedule's own string, not the immediate send's \"Sending…\"",
            listOf(LABEL, "app.getString(R.string.compose_scheduled_for, it)", "}"),
            lines.subList(at, at + 3),
        )
    }

    @Test fun `the draft handed back is the composer's, attachments included`() {
        val body = bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend")
        val draft = callsOf(body, "SendOutbox.ComposeDraft(").single()
        assertTrue("'val attachments = _attachments.value' missing", "val attachments = _attachments.value" in codeLines(body))
        assertEquals(
            "the attachments handed back on Undo are the composer's own parts. The copies under " +
                "scheduledFilesDir are exactly what deleteScheduledSend removes on that same Undo.",
            "attachments",
            draft["attachments"],
        )
    }

    /**
     * Every other field is compared with the immediate send's record, pair by pair, so any
     * divergence not in [UNDO_RECORD_DEVIATIONS] turns red: a swapped cc/bcc, a dropped identity.
     */
    @Test fun `Undo's record is the immediate send's, but for the listed deviations`() {
        val sent = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"), "SendOutbox.ComposeDraft(").single()
        val scheduled = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"), "SendOutbox.ComposeDraft(").single()
        assertEquals(
            "each deviation must still describe sendInternal's record as it is",
            UNDO_RECORD_DEVIATIONS.mapValues { it.value.first },
            sent.filterKeys { it in UNDO_RECORD_DEVIATIONS },
        )
        val expected = sent.toMutableMap()
        UNDO_RECORD_DEVIATIONS.forEach { (name, values) ->
            val scheduledValue = values.second
            if (scheduledValue == null) expected.remove(name) else expected[name] = scheduledValue
        }
        assertEquals(expected, scheduled)
    }

    @Test fun `the booked work never fires inside the Undo window`() {
        assertEquals(
            "the delay handed to the work must go through heldScheduleDelayMillis: an hour picked " +
                "a few seconds ahead would otherwise send while Undo is still on screen, and Undo " +
                "would hand back a message already gone",
            listOf(ENQUEUE),
            scheduleLines().filter { "ScheduledSends.enqueue(" in it },
        )
    }

    // --- reading the source ---------------------------------------------------------------------

    private fun scheduleLines(): List<String> = codeLines(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"))

    private fun bodyOf(file: File, name: String): String {
        val code = codeOf(file.readText())
        val at = Regex("""\bfun\s+$name\s*\(""").find(code)
            ?: error("${file.name} declares no 'fun $name(' — did it get renamed?")
        val start = code.indexOf('{', code.indexOf(')', at.range.last)) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    private fun codeLines(text: String): List<String> =
        codeOf(text).lines().map { it.trim() }.filter { it.isNotBlank() }

    /** The arguments of every `[call]…)` in [body], as name → value; positional keyed `#0`, `#1`, … */
    private fun callsOf(body: String, call: String): List<Map<String, String>> {
        val calls = mutableListOf<Map<String, String>>()
        var from = 0
        while (true) {
            val at = body.indexOf(call, from)
            if (at < 0) break
            val open = at + call.length - 1
            val close = matchingParen(body, open)
            calls += parseArguments(body.substring(open + 1, close))
            from = close
        }
        check(calls.isNotEmpty()) { "no call to '$call' left in this body — did it move?" }
        return calls
    }

    private fun matchingParen(code: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0 && c == ')') return i
                }
            }
            i++
        }
        error("unbalanced call in the source at offset $open")
    }

    private fun parseArguments(text: String): Map<String, String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> { current.append(c).append(text.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; current.append(c) }
                inString -> current.append(c)
                c == '(' || c == '[' || c == '{' -> { depth++; current.append(c) }
                c == ')' || c == ']' || c == '}' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { args += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) args += current.toString()
        return args.mapIndexed { index, raw ->
            val arg = raw.trim().replace(Regex("""\s+"""), " ")
            val eq = arg.indexOf('=')
            if (eq > 0 && arg.take(eq).trim().matches(Regex("""\w+""")) && arg.getOrNull(eq + 1) != '=') {
                arg.take(eq).trim() to arg.substring(eq + 1).trim()
            } else {
                "#$index" to arg
            }
        }.toMap()
    }

    /** [source] with every comment removed, string literals untouched. */
    private fun codeOf(source: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> { out.append(c).append(source.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private companion object {
        const val INSERT = "val id = repo.sending.insertScheduledSend("
        const val ENQUEUE =
            "ScheduledSends.enqueue(getApplication(), id, heldScheduleDelayMillis(scheduledSendDelayMillis(sendAtMillis, System.currentTimeMillis())))"

        /**
         * name to (sendInternal's value, scheduleSend's value); null drops the argument. The id is
         * the one the immediate send carries, account-guarded but not loss-filtered: the verdict
         * travels beside it, and only the scheduled row, which cannot carry the verdict, is filtered.
         */
        val UNDO_RECORD_DEVIATIONS: Map<String, Pair<String, String?>> = mapOf(
            // Named locals in sendInternal, the composer's fields read directly here.
            "inReplyTo" to ("replyTo" to "inReplyTo"),
            "references" to ("refs" to "references"),
            // A schedule is refused unless PGP is off, and null is ComposeDraft's "off".
            "pgpMode" to ("mode.takeIf { it != PgpMode.OFF }?.name" to null),
            // sendInternal reads SendArgs, kept for the OpenPGP round trip; scheduleSend its parameter.
            "requestReceipt" to ("args.requestReceipt" to "requestReceipt"),
            "draftEmailId" to ("sendTarget.emailId" to "undoTarget.emailId"),
            "draftUidValidity" to ("sendTarget.uidValidity" to "undoTarget.uidValidity"),
        )
        const val HOLD = "outbox.hold("
        const val CONSUME_LOCAL = "consume = { local -> consumeEditingLocalDraft(local) },"
        const val CONSUME_OUTBOX = "consumeEditingOutbox()"
        const val LABEL =
            "val label = scheduledHoldLabel(sendAtMillis, { millis, flags -> DateUtils.formatDateTime(app, millis, flags) }) {"

        const val COMPOSE_VIEW_MODEL_PATH = "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}

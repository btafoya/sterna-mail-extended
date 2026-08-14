package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The fidelity verdict of an edited draft ([ComposeViewModel.editingDraftLossy]) on its way from
 * the composer to the outbox row — SOURCE LINT, the last resort, for [ReadReceiptWiringTest]'s
 * reason: nothing in this module can instantiate `ComposeViewModel` (an `AndroidViewModel`, no
 * Robolectric here) or run a WorkManager worker. The decision the verdict feeds is EXECUTED in
 * `core:data` ([SendReplacesDraftTest] runs `replaceableDraftIdOrNull`); what nothing else can see
 * is whether this class still hands the verdict over — and a verdict dropped anywhere on this path
 * is not a missing bit, it is `false`, i.e. "destroy authorised": the send goes out without what
 * the composer could not carry and the draft that held the only copy is destroyed behind it.
 *
 * Arguments are parsed out of each call and compared whole ([callsOf]) — a `contains` is blind to
 * any mutation that lengthens a line ('editingDraftLossy && false' reads as harmless).
 */
class DraftVerdictWiringTest {

    @Test fun `the send hands the verdict to the queue beside the id`() {
        val call = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"), "repo.enqueueSend(").single()
        assertEquals(
            "the id of the draft being replaced must still ride the queue — dropping it leaves a " +
                "stale duplicate in Drafts after every faithful send",
            "editingDraftId",
            call["draftEmailId"],
        )
        assertEquals(
            "enqueueSend must be told the composer's own verdict, whole. `false` (or nothing at " +
                "all — the parameter defaults to the safe true) destroys a draft this composer " +
                "could not reproduce; the decision that reads it is executed in core:data, but " +
                "only this line makes that decision see THIS screen's verdict.",
            "editingDraftLossy",
            call["bodyIsLossy"],
        )
    }

    @Test fun `the draft kept for undo carries the verdict, so an undone send cannot launder it`() {
        val call = callsOf(bodyOf(COMPOSE_VIEW_MODEL, "sendInternal"), "SendOutbox.ComposeDraft(").single()
        assertEquals("editingDraftId", call["draftEmailId"])
        assertEquals(
            "the record an undone send reopens from must carry the verdict WITH the id: restoring " +
                "the id alone is the laundering — the re-send then destroys an original the first " +
                "send had deliberately spared",
            "editingDraftLossy",
            call["draftBodyIsLossy"],
        )
    }

    @Test fun `reopening an undone send restores the verdict beside the id`() {
        assertEquals(
            "prepare()'s restore branch must read the verdict back from the restored draft — the " +
                "whole line; before it existed, editingDraftLossy silently reset to false here " +
                "while editingDraftId came back, and that pair is exactly the destroy",
            listOf("editingDraftLossy = d.draftBodyIsLossy"),
            codeLinesNaming(bodyOf(COMPOSE_VIEW_MODEL, "prepare"), "d.draftBodyIsLossy"),
        )
    }

    @Test fun `the verdict is computed from the draft itself, every leg of it`() {
        // The mutation that used to pass: `editingDraftLossy = false` here left the whole suite
        // green and brought the entire symptom back — the verdict is the ONLY thing standing
        // between a reopened HTML/inline/calendar draft and its destruction on send.
        val body = bodyOf(COMPOSE_VIEW_MODEL, "prepare")
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = lines.indexOfFirst { it.startsWith("editingDraftLossy = reopenedDraftIsLossy(") }
        assertTrue("prepare() no longer computes the verdict from the fetched draft — did the reopen path move?", at >= 0)
        assertEquals(
            "the verdict must be the pure function's — `reopenedDraftIsLossy` in core:data, the " +
                "one a JVM test can EXECUTE — fed the four legs of what this editor cannot give " +
                "back, whole lines: an HTML body (flattened to text on open), inline images and " +
                "calendar parts (never carried), and the read-receipt request the draft's header " +
                "holds (the checkbox comes back clear — D1). Any leg dropped is that content, or " +
                "that request, silently destroyed on the next send.",
            listOf(
                "editingDraftLossy = reopenedDraftIsLossy(",
                "hasHtmlBody = draft.htmlContent() != null,",
                "inlineImageCount = draft.inlineImageParts().size,",
                "calendarPartCount = draft.calendarParts().size,",
                "receiptHeader = draft.dispositionNotificationTo,",
                ")",
            ),
            lines.subList(at, at + 6),
        )
        assertEquals(
            "each leg must be read off the DRAFT that was just fetched, never off this screen's " +
                "own state: the composer's receipt checkbox (`args.requestReceipt`) comes back " +
                "clear on a reopen, so feeding it here would make the receipt leg answer " +
                "\"nothing asked\" every single time and stop nothing at all",
            mapOf(
                "hasHtmlBody" to "draft.htmlContent() != null",
                "inlineImageCount" to "draft.inlineImageParts().size",
                "calendarPartCount" to "draft.calendarParts().size",
                "receiptHeader" to "draft.dispositionNotificationTo",
            ),
            callsOf(body, "reopenedDraftIsLossy(").single(),
        )
        assertEquals(
            "every assignment of the verdict in prepare(), whole lines and in order: the restore " +
                "branch reads it back from the undone send; the reopen branch computes it from " +
                "the draft, then hardens it on a failed attachment carry; the catch answers " +
                "`true` — unknown — for a draft that could not be read at all. " +
                "`editingDraftLossy = false` anywhere here is the defect, whole.",
            listOf(
                "editingDraftLossy = d.draftBodyIsLossy",
                "editingDraftLossy = reopenedDraftIsLossy(",
                "if (!carryDraftAttachments(credentials, draft)) editingDraftLossy = true",
                "editingDraftLossy = true",
            ),
            lines.filter { "editingDraftLossy" in it },
        )
    }

    @Test fun `the reopened draft's id is set only after the attachment carry has answered`() {
        // The race: editingDraftId used to be set BEFORE carryDraftAttachments. Sending while the
        // carry was still downloading went out without the attachments — flag still false — and
        // destroyed the only copy. No id until the carry has answered means no destroy; the price
        // is a duplicate if the user saves during those seconds.
        val lines = bodyOf(COMPOSE_VIEW_MODEL, "prepare").lines().map { it.trim() }
        val assignments = lines.withIndex()
            .filter { Regex("""\beditingDraftId\s*=""").containsMatchIn(it.value) }
        assertEquals(
            "every assignment of editingDraftId in prepare(), whole lines: exactly the restore " +
                "branch's and the reopen branch's, nothing else. An extra one — an alias like " +
                "`this.editingDraftId = draftId` slipped in above the carry — re-arms the destroy " +
                "before the verdict is settled, and the equality-only filter this test used to " +
                "run was blind to it.",
            listOf("editingDraftId = d.draftEmailId", "editingDraftId = draftId"),
            assignments.map { it.value },
        )
        val carry = lines.indexOfFirst { "carryDraftAttachments(" in it }
        assertTrue("prepare() no longer carries the draft's attachments — did the reopen path move?", carry >= 0)
        assertTrue(
            "prepare() must settle the lossy verdict (carryDraftAttachments included) BEFORE the " +
                "reopen branch sets editingDraftId — the id is what arms the destroy, so it comes " +
                "last",
            assignments.last().index > carry,
        )
    }

    @Test fun `a scheduled row's id is written only against a clean verdict, and the worker relies on it`() {
        // The scheduled table cannot carry the verdict (no column — a migration is forbidden
        // while another is in flight), so the id itself is the carrier. Two lines, one invariant,
        // pinned together: relax either alone and a scheduled send of a lossy draft destroys it.
        assertEquals(
            "scheduleSend must withhold the id when the composer's verdict is not clean — the " +
                "row has nowhere else to say so",
            "editingDraftId.takeUnless { editingDraftLossy }",
            callsOf(bodyOf(COMPOSE_VIEW_MODEL, "scheduleSend"), "ScheduledSendEntity(").single()["draftEmailId"],
        )
        val fired = callsOf(bodyOf(SCHEDULED_SEND_WORKER, "doWork"), "repo.enqueueSend(").single()
        assertEquals("row.draftEmailId", fired["draftEmailId"])
        assertEquals(
            "the worker may answer `false` ONLY because scheduleSend gated the id above: an id on " +
                "a scheduled row proves the body half, and enqueueSend still reads the addressing " +
                "half at fire time",
            "false",
            fired["bodyIsLossy"],
        )
    }

    // --- reading the source (the same instruments as ReadReceiptWiringTest) ---------------------

    /** The body of `fun [name](` in [file], braces balanced, comments cut. */
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

    /** The code lines of [body] naming [needle], trimmed — comments are already cut by [codeOf]. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }.filter { needle in it }

    /** The arguments of every `[call]…)` in [body], as name → value expression. */
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

    /** [source] with every comment removed, string literals untouched: the comments around these
     *  lines name the very arguments this pins, so prose must not be able to answer for the code. */
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
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val SCHEDULED_SEND_WORKER_PATH =
            "app/src/main/kotlin/app/sterna/send/ScheduledSendWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        val SCHEDULED_SEND_WORKER: File by lazy { File(root, SCHEDULED_SEND_WORKER_PATH) }
    }
}

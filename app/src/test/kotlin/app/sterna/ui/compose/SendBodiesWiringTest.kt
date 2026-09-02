package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The three lines of `ComposeViewModel` that carry the STYLING (#131) — SOURCE LINT, the last
 * resort: the one that puts it on the wire, and the two that take it out of a send and back into
 * the composer when the user taps Undo.
 *
 * `ComposeViewModel.bodiesForSend` is private, nothing in this module can instantiate an
 * `AndroidViewModel`, and no test traverses it: the decision it makes is `htmlBodyWithSignature`'s
 * (EXECUTED in [ComposeSignatureTest], which runs the substitution and the "a style range over the
 * signature block is an edit" rule), but WHICH BODY it is handed is visible nowhere else.
 *
 * ⛔ The mutation this exists for: `htmlBodyWithSignature(RichBody.plain(userBody.text), …)`. It
 * compiles, it type-checks, the whole suite stays green — and every message leaves with its bold
 * and its italics silently stripped from the html alternative, while the composer that sent it
 * showed them. The plain-text alternative is unaffected, so nothing on the sender's side ever
 * looks wrong.
 *
 * ⛔ WHOLE LINES, and exactly one occurrence of each. A `contains` is blind to every mutation that
 * LENGTHENS the line, which is the shape this one takes; a count of one is what stops a second
 * call being added beside the first and answering for it.
 */
class SendBodiesWiringTest {

    @Test fun `the send builds its html from the composer's rich body, once`() {
        val code = codeLines(COMPOSE_VIEW_MODEL.readText())

        assertEquals(
            "⛔ `bodiesForSend` must build the outgoing html through `htmlBodyWithSignature(`, in " +
                "exactly ONE place. A second call is a second answer to what the recipient sees; " +
                "none at all is an html alternative built somewhere this lint cannot see.",
            listOf("val html = htmlBodyWithSignature("),
            code.filter { "htmlBodyWithSignature(" in it },
        )
        assertEquals(
            "⛔ and it is handed the RICH body, whole — the `RichBody` the screen holds, spans " +
                "included. `RichBody.plain(userBody.text)` here, or `userBody.text`, is the whole " +
                "of #131 undone with the suite green: the styling never reaches the wire, and only " +
                "the recipient could tell.",
            listOf("userBody, signatureTextOf(identity), signatureHtmlOf(identity),"),
            code.filter { it.startsWith("userBody,") },
        )
    }

    /**
     * The undo round trip, both ends. The record kept for the undo window lives in memory, and the
     * branch that reopens from it is `prepare()`'s: nothing in this module can execute either.
     *
     * ⛔ Either line dropped and undoing a send hands the composer back a message with its bold
     * REMOVED — and the message that finally goes out carries the removal, since re-sending
     * enqueues a fresh row from the composer's state. Both are silent: the text is all there.
     */
    @Test fun `undoing a send gives the styling back, at both ends`() {
        val code = codeLines(COMPOSE_VIEW_MODEL.readText())

        assertEquals(
            "the record an undone send reopens from must carry the composer's own spans — ⛔ the " +
                "`body.ranges` it HELD, never a re-parse of the html that went out, which carries " +
                "an imported signature substituted verbatim and a forward's original appended and " +
                "would not read back",
            listOf("bodyRanges = body.ranges,"),
            code.filter { it.startsWith("bodyRanges = body.ranges") },
        )
        assertEquals(
            "…and prepare()'s restore branch must lay them back on the prefill: this is the one " +
                "way into the screen (the composer owns its `ranges` in a rememberSaveable)",
            listOf("bodyRanges = d.bodyRanges,"),
            code.filter { it.startsWith("bodyRanges = d.bodyRanges") },
        )
    }

    /** Source lines, trimmed, with comments and blanks removed: prose must not answer for code. */
    private fun codeLines(source: String): List<String> {
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
                    out.append('\n')
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
        return out.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}

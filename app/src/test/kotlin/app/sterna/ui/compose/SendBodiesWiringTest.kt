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
     * The TEXT alternative, the half no lint watched until the lists arrived (#131).
     *
     * Bold has no spelling in plain text, so the text part was rightly the body as typed. A LIST
     * does have one — `- ` and `1. `, written by `toPlainText`, which is EXECUTED by `RichBodyTest`
     * — and `userBody.text` carries none of it: `body.text` is what the editor holds, and the
     * markers live only in the drawing and in the html. A recipient reading the text part would
     * get the items as a run of loose lines, and so would the copy the sender keeps.
     *
     * ⛔ Both call sites, because the forward path builds its own string: fixing one and leaving
     * the other means a plain reply is fine and a forwarded one is not.
     *
     * ⛔ And both as WHOLE LINES, the class promise this test did not keep: the forward's return
     * was counted by its PREFIX, so removing the appended original from its tail left the count at
     * one and the suite green.
     */
    @Test fun `the text alternative spells the list markers and the addresses out`() {
        val code = codeLines(COMPOSE_VIEW_MODEL.readText())

        assertEquals(
            "the direct return of `bodiesForSend` must hand `toPlainText(userBody)` to the text " +
                "alternative, whole line, once.",
            listOf("val fwd = forwarded ?: return toPlainText(userBody) to html"),
            code.filter { it.startsWith("val fwd = forwarded ?:") },
        )
        assertEquals(
            "…and so must the forward's concatenation, which builds its own text alternative — " +
                "the WHOLE LINE, tail included. Drop `\\n\\n\${fwd.text}` from it and the text " +
                "alternative of every forward carries only what was typed: the carried original " +
                "gone from it, still there in the html beside it, so the sender's own copy looks " +
                "whole and only a recipient reading the text part gets a forward with nothing " +
                "forwarded. A prefix (or a `contains`) is blind to exactly that: the mutation " +
                "touches nothing before the first closing brace.",
            listOf(
                "return \"\${toPlainText(userBody)}\\n\\n\${fwd.text}\" to \"\$html<br><br>\${fwd.html}\"",
            ),
            code.filter { it.startsWith("return \"\${toPlainText(userBody)}") },
        )
        assertEquals(
            "⛔ and NOT ONE `userBody.text` may be left in this class: it is the exact mutation " +
                "this rule exists for, it compiles, and the only sign of it is on the recipient's " +
                "screen.",
            emptyList<String>(),
            code.filter { "userBody.text" in it },
        )
    }

    /**
     * ⭐ THE SAME ANSWER FOR THE DRAFT (#131), and it is a different call site: `saveDraft` takes
     * the text part as its own argument, and it said `body.text` until the links landed.
     *
     * ⛔ A draft stored with `body.text` is one whose text part has the list markers nowhere and
     * every URL missing, while the html part beside it — `draftHtmlToSave(body)`, on the very same
     * body — carries both. One body, two parts, two different messages: whichever part the next
     * reader takes decides what the draft says. And on a reopen the text part is the FALLBACK, so
     * the drift is not even stable.
     */
    @Test fun `the draft save stores the same plain-text answer as the send`() {
        val code = codeLines(COMPOSE_VIEW_MODEL.readText())

        assertEquals(
            "BOTH calls that open `credentials, recipients, subject, …` are pinned here, in " +
                "source order: the SEND's enqueue, which is handed the `textBody` " +
                "`bodiesForSend` built, and the draft save, which must be handed " +
                "`toPlainText(body)`. Pinning only the second would let the first be the one " +
                "that answers this rule.",
            listOf(
                "credentials, recipients, subject, textBody, replyTo, refs,",
                "credentials, recipients, subject, toPlainText(body),",
            ),
            code.filter { it.startsWith("credentials, recipients, subject,") },
        )
        assertEquals(
            "⛔ …so exactly THREE lines of this class may name `toPlainText(`: the send's two " +
                "arms and the draft save. Fewer means one of them writes a different message " +
                "about the same body; more means a fourth answer nothing here decides.",
            3,
            code.count { "toPlainText(" in it },
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
            "…and its LISTS, off that same composer state (#131): dropped, undoing a send hands " +
                "back a message whose list has been flattened, and the re-send delivers the " +
                "flattening.",
            listOf("bodyBlocks = body.blocks,"),
            code.filter { it.startsWith("bodyBlocks = body.blocks") },
        )
        assertEquals(
            "…and prepare()'s restore branch must lay them back on the prefill: this is the one " +
                "way into the screen (the composer owns its `ranges` in a rememberSaveable)",
            listOf("bodyRanges = d.bodyRanges,"),
            code.filter { it.startsWith("bodyRanges = d.bodyRanges") },
        )
        assertEquals(
            "…and the blocks with them, on the same statement: half the body handed back is a " +
                "message the composer shows differently from the one that was sent.",
            listOf("bodyBlocks = d.bodyBlocks,"),
            code.filter { it.startsWith("bodyBlocks = d.bodyBlocks") },
        )
        assertEquals(
            "…and its LINKS, off that same composer state: dropped, undoing a send hands back a " +
                "message whose addresses have been REMOVED, and the re-send delivers the removal.",
            listOf("bodyLinks = body.links,"),
            code.filter { it.startsWith("bodyLinks = body.links") },
        )
        assertEquals(
            "…the links likewise on prepare()'s restore branch — ROUTE 4 OF 4, and the only one " +
                "of the four whose record never touches a disk, so nothing else can see it.",
            listOf("bodyLinks = d.bodyLinks,"),
            code.filter { it.startsWith("bodyLinks = d.bodyLinks") },
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

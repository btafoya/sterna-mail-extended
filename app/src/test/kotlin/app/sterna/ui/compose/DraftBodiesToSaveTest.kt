package app.sterna.ui.compose

import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.draftHtmlToSave
import app.sterna.core.data.text.toPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Save draft" writes for a forward reopened from the Outbox: the save consumes the queued row,
 * the only other copy of the original, so the draft has to carry it.
 */
class DraftBodiesToSaveTest {

    private val fwd = buildForwardedBlocks(
        from = "Iris <iris@example.org>", subject = "Plans", date = "Tue, 29 Sep 2026",
        to = "alex@masto.top", originalText = "Meet at six.",
        originalHtml = "<p>Meet at <b>six</b>.</p><img src=\"cid:pic1@example.org\" alt=\"map\">",
        carriedCids = setOf("pic1@example.org"),
    )

    @Test fun `a reopened queued forward saves the note and the original, byte for byte in both parts`() {
        val note = RichBody.plain("a < b\nnote C2d")

        val saved = draftBodiesToSave(note, reopenedFromOutbox = true, forwarded = fwd)

        assertEquals("a < b\nnote C2d\n\n${fwd.text}", saved.text)
        assertEquals(
            "a plain note has no html of its own, so it is escaped before the original's html",
            "a &lt; b<br>note C2d<br><br>${fwd.html}",
            saved.html,
        )
        assertTrue(saved.html!!.contains("<img src=\"cid:pic1@example.org\""))
    }

    @Test fun `a styled note keeps its own html in front of the original`() {
        val note = RichBody.of("see you", mapOf(Inline.BOLD to listOf(Span(0, 3))))

        val saved = draftBodiesToSave(note, reopenedFromOutbox = true, forwarded = fwd)

        assertEquals("${draftHtmlToSave(note)}<br><br>${fwd.html}", saved.html)
        assertEquals("${toPlainText(note)}\n\n${fwd.text}", saved.text)
    }

    @Test fun `the saved draft splits back into the same note and original`() {
        val note = RichBody.plain("note C2d")
        val saved = draftBodiesToSave(note, reopenedFromOutbox = true, forwarded = fwd)

        val split = splitAssembledForward(saved.text, saved.html)

        assertEquals(fwd, split!!.forwarded)
        assertEquals("note C2d", split.noteText)
    }

    @Test fun `outside a reopened queued forward the draft is the note alone, as before`() {
        val plain = RichBody.plain("note C2d")
        val styled = RichBody.of("see you", mapOf(Inline.BOLD to listOf(Span(0, 3))))
        for (body in listOf(plain, styled)) {
            val expected = DraftBodies(toPlainText(body), draftHtmlToSave(body))
            assertEquals("a fresh or undone forward", expected, draftBodiesToSave(body, reopenedFromOutbox = false, forwarded = fwd))
            assertEquals("a queued row with no forward", expected, draftBodiesToSave(body, reopenedFromOutbox = true, forwarded = null))
            assertEquals(expected, draftBodiesToSave(body, reopenedFromOutbox = false, forwarded = null))
        }
    }

    @Test fun `the text part is the plain-text rendering, never the bare typed text`() {
        val linked = RichBody.of("see site", emptyMap(), links = listOf(Link(Span(4, 8), "https://example.org")))
        assertNotEquals("the witness needs a body whose rendering differs from its text", linked.text, toPlainText(linked))

        assertEquals(toPlainText(linked), draftBodiesToSave(linked, reopenedFromOutbox = false, forwarded = null).text)
        assertEquals(
            "${toPlainText(linked)}\n\n${fwd.text}",
            draftBodiesToSave(linked, reopenedFromOutbox = true, forwarded = fwd).text,
        )
    }
}

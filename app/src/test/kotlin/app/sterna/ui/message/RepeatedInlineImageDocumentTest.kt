package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message that points at the same inline image a hundred thousand times. Both documents are
 * built from it and their size is measured: it must stay of the order of the message's own HTML.
 * A document that grows with (references × image) runs the app out of memory on opening, on every
 * reopening and on printing. Neither builder takes the image map: the `cid:` answers are
 * [decideResourceRequest]'s, tested in `InlineImageRequestsTest`.
 */
class RepeatedInlineImageDocumentTest {

    private val references = 100_000
    private val fragment = "<p>" + "<img src=\"cid:a\">".repeat(references) + "</p>"
    /** The fixed part of either document (head, CSP, styles, header lines), generously. */
    private val templateAllowance = 64 * 1024

    private fun bound() = 2 * fragment.length + templateAllowance

    @Test fun `the print document does not grow with the number of references to one image`() {
        val body = ReaderBody(fragment, richHtml = true, derived = false)
        val doc = buildPrintDocument(
            PrintHeader("S", "F", "T", "", "D"),
            PrintLabels("f", "t", "c", "d"),
            body,
        )

        assertTrue(
            "the print document is ${doc.length} chars for a ${fragment.length}-char body; it must " +
                "stay under ${bound()}, not carry one copy of the image per reference",
            doc.length < bound(),
        )
        assertTrue("the references must reach paper as written", "<img src=\"cid:a\">" in doc)
    }

    @Test fun `the reader's document does not grow with the number of references to one image`() {
        val part = EmailBodyPart(partId = "h", type = "text/html")
        val email = Email(
            id = "1",
            htmlBody = listOf(part),
            bodyValues = mapOf("h" to EmailBodyValue(value = fragment)),
        )
        val doc = buildHtmlDocument(email, noContent = "NO-CONTENT")

        assertTrue(
            "the reader's document is ${doc.length} chars for a ${fragment.length}-char body; it " +
                "must stay under ${bound()}, not carry one copy of the image per reference",
            doc.length < bound(),
        )
        assertTrue("the references must reach the reader as written", "<img src=\"cid:a\">" in doc)
    }

    @Test fun `a message that asked for too many resources is read as text, under its own notice`() {
        val part = EmailBodyPart(partId = "h", type = "text/html")
        val email = Email(
            id = "1",
            htmlBody = listOf(part),
            bodyValues = mapOf("h" to EmailBodyValue(value = "<p>Hello there</p>$fragment")),
        )
        val tooMany = "TOO-MANY"
        val derived = "DERIVED-FROM-HTML"
        val body = readerBody(email, plainText = false, derivedNotice = derived, noContent = "NO-CONTENT", tooManyNotice = tooMany)

        assertFalse("the fallback is text we paint, never the message's own HTML", body.richHtml)
        assertTrue("the reader must be told why the message is text: ${body.fragment.take(200)}", tooMany in body.fragment)
        assertFalse("the notice is not the one of a text asked for by the reader", derived in body.fragment)
        assertFalse("the references must be gone from the fallback", "<img src=\"cid:a\">" in body.fragment)
        assertTrue("the message's text is kept", "Hello there" in body.fragment)
        assertTrue("the text is wrapped as the reader wraps text", "<pre class=\"plain\">" in body.fragment)
    }

    @Test fun `the reader's document carries the fallback when it is handed the notice`() {
        val part = EmailBodyPart(partId = "h", type = "text/html")
        val email = Email(
            id = "1",
            htmlBody = listOf(part),
            bodyValues = mapOf("h" to EmailBodyValue(value = "<p>Hello there</p><img src=\"cid:a\">")),
        )
        val doc = buildHtmlDocument(email, noContent = "NO-CONTENT", tooManyNotice = "TOO-MANY")

        assertTrue("buildHtmlDocument must hand the notice down to readerBody", "TOO-MANY" in doc)
        assertFalse(
            "the page that gave up on the message's HTML must not be handed that HTML again",
            "<img src=\"cid:a\">" in doc,
        )
        assertTrue("the message's text is kept", "Hello there" in doc)
    }
}

package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An HTML message leaves with its text as a text/plain alternative ahead of the HTML (#181), read
 * back through the same `MimeParser` the app opens a message with, so the structure is checked by
 * a reader and not by string positions alone.
 */
class HtmlBodyCarriesTextAlternativeTest {

    private val image = OutgoingAttachment(
        name = "logo.png", type = "image/png", bytes = byteArrayOf(1, 2, 3),
        cid = "logo@x", inline = true,
    )
    private val file = OutgoingAttachment(
        name = "doc.pdf", type = "application/pdf", bytes = byteArrayOf(4, 5, 6),
    )
    private val htmlBody = "<p>Bonjour <b>café</b> <img src=\"cid:logo@x\"></p>"
    private val textBody = "Bonjour café\nSecond line."

    private fun message(attachments: List<OutgoingAttachment>, body: String = textBody) = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("list@example.com"),
        subject = "Hi",
        body = body,
        html = htmlBody,
        messageId = "id-1@example.com",
        dateMillis = 0L,
        attachments = attachments,
    )

    /** Each branch, and where in the entity its alternative's two parts sit. */
    private val shapes = mapOf(
        "no attachment" to (emptyList<OutgoingAttachment>() to "1"),
        "files only" to (listOf(file) to "1.1"),
        "inline images only" to (listOf(image) to "1"),
        "inline images and files" to (listOf(image, file) to "1.1"),
    )

    private fun decodedAt(raw: String, section: String): String {
        val (cte, body) = requireNotNull(MimeParser.partAt(raw, section)) { "no part at $section" }
        assertEquals("part $section is base64", "base64", cte)
        return String(MimeParser.decodeBytes(body, cte), Charsets.UTF_8)
    }

    @Test fun everyBranchCarriesTheTextAndTheHtmlToOurOwnReader() {
        for ((name, shape) in shapes) {
            val parsed = MimeParser.parseBody(OutgoingMime.build(message(shape.first)))
            assertEquals("$name: the text part", textBody, parsed.text)
            assertEquals("$name: the html part", htmlBody, parsed.html)
        }
    }

    @Test fun theTextIsTheFirstAlternativeInEveryBranch() {
        // RFC 2046 § 5.1.4: the last alternative is the preferred one, the plainest goes first.
        for ((name, shape) in shapes) {
            val (attachments, first) = shape
            val raw = OutgoingMime.build(message(attachments))
            val second = first.dropLast(1) + "2"
            assertEquals("$name: part $first is the text", textBody, decodedAt(raw, first))
            if (attachments.any { it.inline }) {
                assertEquals("$name: part $second.1 is the html", htmlBody, decodedAt(raw, "$second.1"))
            } else {
                assertEquals("$name: part $second is the html", htmlBody, decodedAt(raw, second))
            }
        }
    }

    @Test fun theAttachmentsAreStillThereInEveryBranch() {
        for ((name, shape) in shapes) {
            val parsed = MimeParser.parseBody(OutgoingMime.build(message(shape.first)))
            assertEquals(
                "$name: attachments",
                shape.first.map { it.name }.sorted(),
                parsed.attachments.map { it.name }.sorted(),
            )
        }
    }

    @Test fun theInlineImagesKeepTheirCidAndOnlyTheFilesAreFiles() {
        // The reader resolves `cid:` against these; an image without its cid shows as a broken
        // picture in the HTML and as a stray file under it.
        for ((name, shape) in shapes) {
            val parsed = MimeParser.parseBody(OutgoingMime.build(message(shape.first)))
            assertEquals(
                "$name: inline images by cid",
                shape.first.filter { it.inline }.map { it.name to it.cid },
                parsed.attachments.filter { it.cid != null }.map { it.name to it.cid },
            )
            assertEquals(
                "$name: files",
                shape.first.filter { !it.inline }.map { it.name },
                parsed.attachments.filter { it.cid == null }.map { it.name },
            )
        }
    }

    @Test fun anEmptyTextStillEmitsItsPart() {
        val raw = OutgoingMime.build(message(emptyList(), body = ""))
        assertEquals("", decodedAt(raw, "1"))
        assertEquals(htmlBody, decodedAt(raw, "2"))
    }

    @Test fun theAlternativeBoundaryIsItsOwnNextToTheMixedOne() {
        val raw = OutgoingMime.build(message(listOf(image, file)))
        val boundaries = Regex("boundary=\"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
        assertEquals("mixed, alternative, related: three boundaries in <$raw>", 3, boundaries.size)
        assertEquals("three distinct boundaries", 3, boundaries.toSet().size)
        for (a in boundaries) for (b in boundaries) {
            if (a != b) assertTrue("$a is not a prefix of $b", !b.startsWith(a))
        }
    }

    @Test fun theSameMessageBuildsTheSameOctetsTwice() {
        // The Sent copy is rebuilt from the same message and must be the submitted bytes.
        for ((_, shape) in shapes) {
            assertEquals(OutgoingMime.build(message(shape.first)), OutgoingMime.build(message(shape.first)))
        }
    }
}

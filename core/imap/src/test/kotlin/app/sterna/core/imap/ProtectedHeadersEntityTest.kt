package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OutgoingMime.buildBodyEntity` with and without a protected subject (RFC 9788 § 3).
 */
class ProtectedHeadersEntityTest {

    // --- fixtures --------------------------------------------------------------------------------

    private val plain = OutgoingMessage(
        from = "alice@example.com",
        to = listOf("bob@example.com"),
        subject = "envelope subject, not this one",
        body = "hello",
        messageId = "id-1@example.com",
        dateMillis = 0L,
    )

    private fun html(attachments: List<OutgoingAttachment>) = plain.copy(
        html = "<p>hi <img src=\"cid:logo@x\"></p>",
        attachments = attachments,
    )

    private val image = OutgoingAttachment(
        name = "logo.png", type = "image/png", bytes = byteArrayOf(1, 2, 3),
        cid = "logo@x", inline = true,
    )
    private val file = OutgoingAttachment(
        name = "doc.pdf", type = "application/pdf", bytes = byteArrayOf(4, 5, 6),
    )

    /** The three `--boundary` delimiters this fixture's Message-ID produces, written out. */
    private val mixed = "------sterna_mixed_id1examplecom"
    private val related = "------sterna_related_id1examplecom"
    private val alternative = "------sterna_alternative_id1examplecom"

    private val plainPart =
        "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            "aGVsbG8=\r\n"

    private val htmlPart =
        "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            "PHA+aGkgPGltZyBzcmM9ImNpZDpsb2dvQHgiPjwvcD4=\r\n"
    private val pdfPart =
        "Content-Type: application/pdf; name=\"doc.pdf\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"doc.pdf\"\r\n\r\n" +
            "BAUG\r\n"
    private val logoPart =
        "Content-Type: image/png; name=\"logo.png\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-ID: <logo@x>\r\n" +
            "Content-Disposition: inline; filename=\"logo.png\"\r\n\r\n" +
            "AQID\r\n"

    /** The inside of a multipart/related: body part, inline image, closing delimiter. */
    private val relatedParts = "$related\r\n" + htmlPart + "$related\r\n" + logoPart + "$related--\r\n"

    private val textRelatedParts = "$related\r\n" + plainPart + "$related\r\n" + logoPart + "$related--\r\n"

    private val nestedRelatedHeader =
        "Content-Type: multipart/related; type=\"text/html\"; boundary=\"----sterna_related_id1examplecom\"\r\n\r\n"
    private val nestedAlternativeHeader =
        "Content-Type: multipart/alternative; boundary=\"----sterna_alternative_id1examplecom\"\r\n\r\n"

    private val alternativeOfHtml =
        "$alternative\r\n" + plainPart + "$alternative\r\n" + htmlPart + "$alternative--\r\n"

    private val alternativeOfRelated =
        "$alternative\r\n" + plainPart + "$alternative\r\n" + nestedRelatedHeader + relatedParts + "$alternative--\r\n"

    /** The inside of the outer multipart/mixed of the fourth branch (alternative + the file). */
    private val nestedAlternative =
        "$mixed\r\n" + nestedAlternativeHeader + alternativeOfRelated +
            "$mixed\r\n" + pdfPart + "$mixed--\r\n"

    private val everyShape =
        listOf(plain, html(emptyList()), html(listOf(file)), html(listOf(image)), html(listOf(image, file))) +
            listOf(listOf(file), listOf(image), listOf(image, file)).map { plain.copy(attachments = it) }

    private val topic = "Quarterly report"

    // --- no protected subject --------------------------------------------------------------------

    @Test fun singlePartWithoutAProtectedSubjectIsWhatItAlwaysWas() {
        assertEquals(
            "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "\r\n" +
                "aGVsbG8=",
            OutgoingMime.buildBodyEntity(plain),
        )
    }

    @Test fun aTextOnlyMessageWithAFileIsMixedOfTheTextAndTheFile() {
        assertEquals(
            "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"\r\n\r\n" +
                "$mixed\r\n" + plainPart + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(plain.copy(attachments = listOf(file))),
        )
    }

    @Test fun aTextOnlyMessageWithAnInlineImageIsRelatedOfTheText() {
        assertEquals(
            "Content-Type: multipart/related; type=\"text/plain\"; boundary=\"----sterna_related_id1examplecom\"\r\n\r\n" +
                textRelatedParts,
            OutgoingMime.buildBodyEntity(plain.copy(attachments = listOf(image))),
        )
    }

    @Test fun aTextOnlyMessageWithAnInlineImageAndAFileIsMixedOfRelatedOfTheText() {
        assertEquals(
            "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"\r\n\r\n" +
                "$mixed\r\n" +
                "Content-Type: multipart/related; type=\"text/plain\"; boundary=\"----sterna_related_id1examplecom\"\r\n\r\n" +
                textRelatedParts + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(plain.copy(attachments = listOf(image, file))),
        )
    }

    @Test fun htmlWithoutAttachmentsIsAlternativeOfTextThenHtml() {
        assertEquals(
            "Content-Type: multipart/alternative; boundary=\"----sterna_alternative_id1examplecom\"\r\n\r\n" +
                alternativeOfHtml,
            OutgoingMime.buildBodyEntity(html(emptyList())),
        )
    }

    @Test fun htmlWithAFileIsMixedOfTheAlternativeAndTheFile() {
        assertEquals(
            "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"\r\n\r\n" +
                "$mixed\r\n" + nestedAlternativeHeader + alternativeOfHtml + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(html(listOf(file))),
        )
    }

    @Test fun htmlWithAnInlineImageIsAlternativeOfTextThenRelated() {
        assertEquals(
            "Content-Type: multipart/alternative; boundary=\"----sterna_alternative_id1examplecom\"\r\n\r\n" +
                alternativeOfRelated,
            OutgoingMime.buildBodyEntity(html(listOf(image))),
        )
    }

    @Test fun htmlWithAnInlineImageAndAFileIsMixedOfAlternativeOfRelated() {
        assertEquals(
            "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"\r\n\r\n" + nestedAlternative,
            OutgoingMime.buildBodyEntity(html(listOf(image, file))),
        )
    }

    @Test fun theNullPathWritesNeitherASubjectNorTheParameter() {
        // The absence half, over every branch at once: a header line reaching an ordinary
        // send is the failure this whole default argument exists to make impossible.
        for (m in everyShape) {
            val entity = OutgoingMime.buildBodyEntity(m)
            assertFalse("no Subject: field in <$entity>", entity.contains("Subject:"))
            assertFalse("no protected-headers in <$entity>", entity.contains("protected-headers"))
        }
    }

    // --- with a protected subject -----------------------------------------------------------------

    @Test fun singlePartCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: text/plain; charset=utf-8; protected-headers=\"v1\"\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "\r\n" +
                "aGVsbG8=",
            OutgoingMime.buildBodyEntity(plain, topic),
        )
    }

    @Test fun aTextOnlyMixedCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                "$mixed\r\n" + plainPart + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(plain.copy(attachments = listOf(file)), topic),
        )
    }

    @Test fun aTextOnlyRelatedCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/related; type=\"text/plain\"; boundary=\"----sterna_related_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                textRelatedParts,
            OutgoingMime.buildBodyEntity(plain.copy(attachments = listOf(image)), topic),
        )
    }

    @Test fun aTextOnlyMixedOfRelatedMarksTheMixedAndLeavesTheRelatedAlone() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                "$mixed\r\n" +
                "Content-Type: multipart/related; type=\"text/plain\"; boundary=\"----sterna_related_id1examplecom\"\r\n\r\n" +
                textRelatedParts + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(plain.copy(attachments = listOf(image, file)), topic),
        )
    }

    @Test fun alternativeCarriesTheSubjectAboveAMarkedContentType() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/alternative; boundary=\"----sterna_alternative_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                alternativeOfHtml,
            OutgoingMime.buildBodyEntity(html(emptyList()), topic),
        )
    }

    @Test fun mixedCarriesTheSubjectAboveAMarkedContentTypeAndLeavesTheAlternativeAlone() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                "$mixed\r\n" + nestedAlternativeHeader + alternativeOfHtml + "$mixed\r\n" + pdfPart + "$mixed--\r\n",
            OutgoingMime.buildBodyEntity(html(listOf(file)), topic),
        )
    }

    @Test fun alternativeOfRelatedMarksTheAlternativeAndLeavesTheRelatedAlone() {
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/alternative; boundary=\"----sterna_alternative_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" +
                alternativeOfRelated,
            OutgoingMime.buildBodyEntity(html(listOf(image)), topic),
        )
    }

    @Test fun mixedOfAlternativeMarksTheOuterContentTypeAndLeavesTheNestedOnesAlone() {
        // The nested alternative and related are sub-parts. Marked, either would claim a second
        // protected header block inside the first, and the entity would describe two.
        assertEquals(
            "Subject: Quarterly report\r\n" +
                "Content-Type: multipart/mixed; boundary=\"----sterna_mixed_id1examplecom\"; " +
                "protected-headers=\"v1\"\r\n\r\n" + nestedAlternative,
            OutgoingMime.buildBodyEntity(html(listOf(image, file)), topic),
        )
    }

    @Test fun exactlyOneContentTypeIsMarkedInEveryBranch() {
        // Counts, so it catches the parameter appended by a shared helper to every part, which
        // the whole-entity expectations above catch too, but only for these fixtures' part lists.
        for (m in everyShape) {
            val entity = OutgoingMime.buildBodyEntity(m, topic)
            assertEquals(
                "exactly one protected-headers parameter in <$entity>",
                1,
                Regex("protected-headers").findAll(entity).count(),
            )
            assertEquals(
                "exactly one Subject: field in <$entity>",
                1,
                Regex("Subject:").findAll(entity).count(),
            )
            assertTrue(
                "the Subject: field opens the entity, above the Content-Type of head",
                entity.startsWith("Subject: Quarterly report\r\nContent-Type: "),
            )
        }
    }

    // --- the subject is header-encoded, and cannot be a header block of its own --------------------

    @Test fun aNonAsciiSubjectIsAnEncodedWordAndNotRawBytes() {
        val entity = OutgoingMime.buildBodyEntity(plain, "Betreff mit Ümläut — тема")
        assertEquals(
            "Subject: =?utf-8?B?QmV0cmVmZiBtaXQgw5xtbMOkdXQg4oCUINGC0LXQvNCw?=",
            entity.lineSequence().first(),
        )
        assertFalse("no raw non-ASCII on the wire", entity.contains("Ümläut"))
    }

    @Test fun aSubjectCarryingCrlfCannotSplitTheHeaderBlock() {
        // The subject reaching here is the composer's own, but it is the same sink as the
        // envelope's and it answers the same way: encoded whole, so the injected field never
        // begins a line, and the header block still ends at the one blank line below.
        val entity = OutgoingMime.buildBodyEntity(plain, "Real subject\r\nX-Injected: victim@evil.com")
        assertFalse("the smuggled field must not exist at all", entity.contains("X-Injected"))
        assertEquals(
            "the header block is still Subject + Content-Type + Content-Transfer-Encoding",
            3,
            entity.substringBefore("\r\n\r\n").split("\r\n").size,
        )
    }

    // --- end to end: our own reader gets the subject back -----------------------------------------

    @Test fun ourOwnParserReadsTheProtectedSubjectBackDecoded() {
        // The entity a recipient decrypts is exactly this string. `MimeParser.decodedHeaderOf` is
        // the door the reader uses on a decrypted entity, and it stops at the first blank line,
        // so this fails outright if the Subject: field is written anywhere below the Content-Type.
        val entity = OutgoingMime.buildBodyEntity(html(listOf(image, file)), "Betreff mit Ümläut — тема")
        assertEquals(
            "Betreff mit Ümläut — тема",
            MimeParser.decodedHeaderOf(entity, "Subject"),
        )
    }

    @Test fun theEnvelopeSubjectIsUntouchedWhileTheEntityCarriesItsOwn() {
        // The feature is integrity, not masking. `build` writes the real subject in the clear
        // exactly as before; nothing here is allowed to remove or rewrite it.
        val mime = OutgoingMime.build(plain)
        assertEquals(
            "Subject: envelope subject, not this one",
            mime.lineSequence().first { it.startsWith("Subject:") },
        )
        assertFalse("an ordinary send carries no protected-headers", mime.contains("protected-headers"))
    }

    // --- the base64 separator guard still holds with a header block above it ----------------------

    @Test fun aLongBodyUnderAProtectedSubjectStillHasNoBareCr() {
        // 2 kB, far past the 57-byte input where the MIME encoder starts wrapping, a `\r\r\n`
        // separator is invisible below that, and on an encrypted-and-signed message those bytes
        // are the signed bytes.
        val long = "The quick brown fox jumps over the lazy dog. ".repeat(50)
        assertTrue("the body really is past the wrap threshold", long.toByteArray(Charsets.UTF_8).size >= 2048)
        val entity = OutgoingMime.buildBodyEntity(plain.copy(body = long), topic)
        assertFalse("no bare CR anywhere in the entity", entity.contains("\r\r"))
        assertEquals(
            "the body still round-trips",
            long,
            String(
                java.util.Base64.getMimeDecoder()
                    .decode(entity.substringAfter("Content-Transfer-Encoding: base64\r\n\r\n")),
                Charsets.UTF_8,
            ),
        )
    }
}

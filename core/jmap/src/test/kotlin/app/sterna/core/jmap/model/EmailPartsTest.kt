package app.sterna.core.jmap.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure partitioning of attachments into inline images vs. downloadable files, both transports. */
class EmailPartsTest {
    private fun email(parts: List<EmailBodyPart>) = Email(id = "1", attachments = parts)

    private fun emailWithHtml(html: String, parts: List<EmailBodyPart>) = Email(
        id = "1",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(value = html)),
        attachments = parts,
    )

    @Test
    fun plainTextInHtmlBodyIsNotTreatedAsHtml() {
        // RFC 8621: a plain-text-only message gets its text/plain part listed in both textBody
        // and htmlBody. htmlContent() must not return it as HTML (issue #4: would collapse the
        // paragraph breaks); textContent() should still return it for the <pre> render path.
        val part = EmailBodyPart(partId = "0", type = "text/plain")
        val e = Email(
            id = "1",
            textBody = listOf(part),
            htmlBody = listOf(part),
            bodyValues = mapOf("0" to EmailBodyValue(value = "Para one.\n\nPara two.")),
        )
        assertNull("plain text in htmlBody is not HTML", e.htmlContent())
        assertEquals("Para one.\n\nPara two.", e.textContent())
    }

    @Test
    fun genuineHtmlPartIsHtmlContent() {
        val html = EmailBodyPart(partId = "1", type = "text/html")
        val text = EmailBodyPart(partId = "0", type = "text/plain")
        val e = Email(
            id = "1",
            textBody = listOf(text),
            htmlBody = listOf(html),
            bodyValues = mapOf(
                "1" to EmailBodyValue(value = "<p>Hi</p>"),
                "0" to EmailBodyValue(value = "Hi"),
            ),
        )
        assertEquals("<p>Hi</p>", e.htmlContent())
        assertEquals("Hi", e.textContent())
    }

    @Test
    fun jmapCitedImageIsInlineNotFile() {
        val inline = EmailBodyPart(blobId = "b1", cid = "logo@x", type = "image/png")
        val file = EmailBodyPart(blobId = "b2", name = "doc.pdf", type = "application/pdf")
        val e = emailWithHtml("<p><img src=\"cid:logo@x\"></p>", listOf(inline, file))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertEquals(listOf(file), e.fileAttachmentParts())
    }

    @Test
    fun imapCitedImageByPartIdIsInlineNotFile() {
        // IMAP parts carry a partId (the MIME section) and no blobId.
        val inline = EmailBodyPart(partId = "2", cid = "logo@x", type = "image/png", disposition = "inline")
        val file = EmailBodyPart(partId = "3", name = "doc.pdf", type = "application/pdf")
        val e = emailWithHtml("<img src='cid:logo@x'>", listOf(inline, file))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertEquals(listOf(file), e.fileAttachmentParts())
        assertFalse("inline image is not a file row", e.fileAttachmentParts().contains(inline))
    }

    @Test
    fun gmailPhotoWithAContentIdTheHtmlNeverCitesIsListed() {
        // #297: Gmail gives each attached photo a Content-ID, and its HTML names none of them.
        val photo = EmailBodyPart(
            partId = "2", name = "IMG_0001.jpg", type = "image/jpeg", cid = "ii_m3abc0", disposition = "attachment",
        )
        val e = emailWithHtml("<div dir=\"ltr\">Voici la photo</div>", listOf(photo))
        assertTrue(e.inlineImageParts().isEmpty())
        assertEquals(listOf(photo), e.fileAttachmentParts())
    }

    @Test
    fun anInlineImageTheHtmlDoesNotCiteIsListedToo() {
        val cited = EmailBodyPart(partId = "2", cid = "a@x", type = "image/png", disposition = "inline")
        val orphan = EmailBodyPart(partId = "3", cid = "b@x", type = "image/png", disposition = "inline")
        val e = emailWithHtml("<img src=\"cid:a@x\">", listOf(cited, orphan))
        assertEquals(listOf(cited), e.inlineImageParts())
        assertEquals(listOf(orphan), e.fileAttachmentParts())
    }

    @Test
    fun withoutHtmlEveryImageIsListed() {
        val img = EmailBodyPart(blobId = "b1", cid = "logo@x", type = "image/png", disposition = "inline")
        val textOnly = Email(
            id = "1",
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            bodyValues = mapOf("t" to EmailBodyValue(value = "voir cid:logo@x")),
            attachments = listOf(img),
        )
        for (e in listOf(email(listOf(img)), textOnly)) {
            assertTrue(e.inlineImageParts().isEmpty())
            assertEquals(listOf(img), e.fileAttachmentParts())
        }
    }

    @Test
    fun aCitationWrittenWithEncodedAngleBracketsStillCitesTheImage() {
        val inline = EmailBodyPart(partId = "2", cid = "<image001.png@01DB0000.00000000>", type = "image/png")
        val e = emailWithHtml("<img src=\"cid:%3Cimage001.png@01DB0000.00000000%3E\">", listOf(inline))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertTrue(e.fileAttachmentParts().isEmpty())
    }

    @Test
    fun aCitationWithRawAngleBracketsCitesTheImage() {
        val inline = EmailBodyPart(partId = "2", cid = "logo@x", type = "image/png")
        val e = emailWithHtml("<img src=\"CID:<logo@x>\">", listOf(inline))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertTrue(e.fileAttachmentParts().isEmpty())
    }

    @Test
    fun aPercentEncodedCitationCitesTheImage() {
        val inline = EmailBodyPart(partId = "2", cid = "photo 1@x", type = "image/png")
        val e = emailWithHtml("<img src=\"cid:photo%201@x\">", listOf(inline))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertTrue(e.fileAttachmentParts().isEmpty())
    }

    @Test
    fun aCssUrlCitesTheImage() {
        val inline = EmailBodyPart(partId = "2", cid = "bg@x", type = "image/png")
        val e = emailWithHtml("<td style=\"background:url(cid:bg@x) no-repeat\">x</td>", listOf(inline))
        assertEquals(listOf(inline), e.inlineImageParts())
        assertTrue(e.fileAttachmentParts().isEmpty())
    }

    @Test
    fun aCitationOfAnotherIdentifierCitesNeitherItsPrefixNorAnotherCase() {
        val prefix = EmailBodyPart(partId = "2", cid = "img1", type = "image/png")
        val otherCase = EmailBodyPart(partId = "3", cid = "LOGO@x", type = "image/png")
        val e = emailWithHtml("<img src=\"cid:img10\"><img src=\"cid:logo@x\">", listOf(prefix, otherCase))
        assertTrue(e.inlineImageParts().isEmpty())
        assertEquals(listOf(prefix, otherCase), e.fileAttachmentParts())
    }

    @Test
    fun aWordEndingInCidIsNoCitation() {
        val img = EmailBodyPart(partId = "2", cid = "rain", type = "image/png")
        val e = emailWithHtml("<p>acid:rain</p>", listOf(img))
        assertTrue(e.inlineImageParts().isEmpty())
        assertEquals(listOf(img), e.fileAttachmentParts())
    }

    @Test
    fun imageWithoutCidIsAFileNotInline() {
        val noCid = EmailBodyPart(partId = "2", name = "photo.png", type = "image/png")
        val e = email(listOf(noCid))
        assertTrue(e.inlineImageParts().isEmpty())
        assertEquals(listOf(noCid), e.fileAttachmentParts())
    }

    @Test
    fun inlineMediaOfAPlainTextMessageIsListedOnce() {
        val text = EmailBodyPart(partId = "1", type = "text/plain")
        val photo = EmailBodyPart(
            partId = "2", blobId = "b2", type = "image/jpeg", name = "photo.jpg",
            disposition = "inline", cid = "photo@x", size = 1234,
        )
        val e = Email(
            id = "1",
            textBody = listOf(text, photo),
            htmlBody = listOf(text, photo),
            bodyValues = mapOf("1" to EmailBodyValue(value = "See the photo.")),
        )
        assertTrue(e.inlineImageParts().isEmpty())
        assertEquals(listOf(photo), e.fileAttachmentParts())
    }

    @Test
    fun aPartInTheBodyListsAndInAttachmentsIsListedOnce() {
        val text = EmailBodyPart(partId = "1", type = "text/plain")
        val photo = EmailBodyPart(partId = "2", blobId = "b2", type = "image/jpeg", cid = "photo@x")
        val e = Email(
            id = "1",
            textBody = listOf(text, photo),
            htmlBody = listOf(text, photo),
            attachments = listOf(photo.copy()),
            bodyValues = mapOf("1" to EmailBodyValue(value = "See the photo.")),
        )
        assertEquals(listOf(photo), e.fileAttachmentParts())
    }

    @Test
    fun anInlineMediaImageTheHtmlCitesIsABodyImage() {
        val html = EmailBodyPart(partId = "1", type = "text/html")
        val photo = EmailBodyPart(partId = "2", blobId = "b2", type = "image/png", cid = "logo@x")
        val e = Email(
            id = "1",
            textBody = listOf(html, photo),
            htmlBody = listOf(html, photo),
            bodyValues = mapOf("1" to EmailBodyValue(value = "<img src=\"cid:logo@x\">")),
        )
        assertEquals(listOf(photo), e.inlineImageParts())
        assertTrue(e.fileAttachmentParts().isEmpty())
    }
}

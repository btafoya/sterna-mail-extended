package app.sterna.core.data.mail

import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `multipart/signed` shows only what its signature covers. The envelope handed to [displayOfEntity]
 * is built from the whole source, as the reader has it before verification, so anything a sender
 * slipped between the signed entity and the signature is on it and must not survive.
 */
class SignedEntityDisplayTest {

    @Test fun `an html part added after the signed entity is not shown`() {
        val raw = signed(
            textPart("Texte signé"),
            "Content-Type: text/html; charset=utf-8\r\n\r\n<h1>INJECTE</h1>",
        )

        val shown = show(raw)

        assertNull("html of the added part is rendered", shown.email.htmlContent())
        assertEquals("Texte signé", shown.email.textContent()?.trim())
        assertFalse(
            "INJECTE still reachable in ${shown.email.bodyValues}",
            shown.email.bodyValues.values.any { it.value.contains("INJECTE") },
        )
    }

    @Test fun `an attachment added between the entity and the signature is not listed`() {
        val raw = signed(
            textPart("Texte signé"),
            "Content-Type: text/plain; name=injecte.txt\r\n" +
                "Content-Disposition: attachment; filename=injecte.txt\r\n\r\npiège",
        )

        val shown = show(raw)

        val names = shown.email.fileAttachmentParts().map { it.name }
        assertFalse("injecte.txt listed among $names", "injecte.txt" in names)
        assertFalse("the paper clip still counts the added attachment", shown.email.hasAttachment)
    }

    @Test fun `an entity without any text leaf does not fall back on the added body`() {
        val raw = signed(
            "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=seul.bin\r\n\r\nxyz",
            "Content-Type: text/html; charset=utf-8\r\n\r\n<h1>INJECTE</h1>",
        )

        val shown = show(raw)

        assertNull(shown.email.htmlContent())
        assertNull(shown.email.textContent())
    }

    @Test fun `an entity without any text leaf leaves no added text nor preview behind`() {
        val raw = signed(
            "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=seul.bin\r\n\r\nxyz",
            "Content-Type: text/plain; charset=utf-8\r\n\r\nINJECTE en texte",
        )
        val envelope = wholeSourceEmail(raw).copy(preview = "INJECTE en aperçu")
        assertEquals("INJECTE en texte", envelope.textContent()?.trim())

        val shown = displayOfEntity(envelope, MimeParser.detectCrypto(raw)!!.signedEntityRaw!!).email

        assertNull(shown.textContent())
        assertNull(shown.htmlContent())
        assertNull(shown.preview)
        // `bodySource` reads `textBody` before anything else; no part of the envelope may be left to point at.
        assertEquals(emptyList<Any>(), shown.textBody)
        assertEquals(emptyList<Any>(), shown.htmlBody)
    }

    @Test fun `a signed mixed entity keeps its attachment, addressed inside the entity`() {
        val entity = "Content-Type: multipart/mixed; boundary=\"in\"\r\n\r\n" +
            "--in\r\n" + textPart("Texte signé") + "\r\n" +
            "--in\r\n" +
            "Content-Type: text/plain; name=signee.txt\r\n" +
            "Content-Disposition: attachment; filename=signee.txt\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            java.util.Base64.getEncoder().encodeToString("contenu signé".toByteArray(Charsets.UTF_8)) + "\r\n" +
            "--in--"
        val raw = signed(entity)
        val signedEntity = MimeParser.detectCrypto(raw)!!.signedEntityRaw!!

        val shown = show(raw)

        assertEquals("Texte signé", shown.email.textContent()?.trim())
        val files = shown.email.fileAttachmentParts()
        assertEquals(listOf("signee.txt"), files.map { it.name })
        val partId = files.single().partId!!
        assertTrue("partId $partId is not routed to the verified entity", partId.startsWith("pgp:"))
        assertTrue(shown.email.hasAttachment)
        val (cte, encoded) = MimeParser.partAt(signedEntity, partId.removePrefix("pgp:"))!!
        assertEquals("contenu signé", MimeParser.decodeBytes(encoded, cte).toString(Charsets.UTF_8))
    }

    @Test fun `a signed alternative entity shows its own html`() {
        val entity = "Content-Type: multipart/alternative; boundary=\"alt\"\r\n\r\n" +
            "--alt\r\n" + textPart("Texte signé") + "\r\n" +
            "--alt\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n\r\n<p>HTML signé</p>\r\n" +
            "--alt--"

        val shown = show(signed(entity))

        assertEquals("<p>HTML signé</p>", shown.email.htmlContent()?.trim())
    }

    @Test fun `a signed related entity carries its cid image`() {
        val entity = "Content-Type: multipart/related; boundary=\"rel\"\r\n\r\n" +
            "--rel\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n\r\n<img src=\"cid:img1\">\r\n" +
            "--rel\r\n" +
            "Content-Type: image/png\r\n" +
            "Content-ID: <img1>\r\n" +
            "Content-Transfer-Encoding: base64\r\n\r\n" +
            "iVBORw0KGgo=\r\n" +
            "--rel--"

        val shown = show(signed(entity))

        assertEquals(mapOf("img1" to "data:image/png;base64,iVBORw0KGgo="), shown.inlineImages)
    }

    private fun show(raw: String): EntityDisplay {
        val envelope = MimeParser.detectCrypto(raw)
        assertEquals(app.sterna.core.imap.CryptoKind.PGP_SIGNED, envelope?.kind)
        return displayOfEntity(wholeSourceEmail(raw), envelope!!.signedEntityRaw!!)
    }

    /** The reader's pre-verification view: the whole source parsed, added parts included. */
    private fun wholeSourceEmail(raw: String): Email {
        val body = MimeParser.parseBody(raw)
        val values = buildMap {
            body.html?.let { put("html", EmailBodyValue(it)) }
            body.text?.let { put("text", EmailBodyValue(it)) }
        }
        return Email(
            id = "m",
            subject = "Sujet",
            htmlBody = if (body.html != null) listOf(EmailBodyPart(partId = "html", type = "text/html")) else emptyList(),
            textBody = if (body.text != null) listOf(EmailBodyPart(partId = "text", type = "text/plain")) else emptyList(),
            bodyValues = values,
            attachments = body.attachments.map {
                EmailBodyPart(partId = it.section, name = it.name, type = it.type, cid = it.cid, encoding = it.encoding)
            },
            hasAttachment = body.attachments.any { it.cid == null },
        )
    }

    private fun textPart(text: String) = "Content-Type: text/plain; charset=utf-8\r\n\r\n$text"

    private fun signed(entity: String, vararg added: String): String {
        val parts = listOf(entity) + added + (
            "Content-Type: application/pgp-signature; name=signature.asc\r\n\r\n" +
                "-----BEGIN PGP SIGNATURE-----\r\n\r\niQEzBAEBCAAdFiEE\r\n-----END PGP SIGNATURE-----"
            )
        val source = "From: a@example.test\r\nSubject: Sujet\r\nMIME-Version: 1.0\r\n" +
            "Content-Type: multipart/signed; micalg=pgp-sha256; protocol=\"application/pgp-signature\"; " +
            "boundary=\"sig\"\r\n\r\n" +
            parts.joinToString("") { "--sig\r\n$it\r\n" } + "--sig--\r\n"
        // One char per octet, as `fetchRawSource` hands the source over.
        return String(source.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
    }
}

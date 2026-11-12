package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * A styled message signed the way `ComposeViewModel` signs it: `OutgoingMime.buildBodyEntity` with
 * an HTML body, so a `multipart/alternative` (#181), through `PgpMime.signablePayload`, wrapped,
 * sent through `OutgoingMime.build`, and cut back out of the octets on the wire by `MimeParser`.
 *
 * The signature is a JDK RSA signature, not an OpenPGP packet nor a CMS `SignedData`: this module
 * has neither library, and what is under test is that the octets signed are the octets a verifier
 * extracts, which any signature over raw bytes tells.
 */
class SignedAlternativeRoundTripTest {

    private val image = OutgoingAttachment(
        name = "logo.png", type = "image/png", bytes = byteArrayOf(1, 2, 3),
        cid = "logo@x", inline = true,
    )
    private val file = OutgoingAttachment(
        name = "doc.pdf", type = "application/pdf", bytes = byteArrayOf(4, 5, 6),
    )
    private val htmlBody = "<p>Bonjour <b>café</b> <img src=\"cid:logo@x\"></p>"
    private val textBody = "Bonjour café\nSecond line, long enough to be folded by the base64 encoder at 76."

    private val alternative = "----sterna_alternative_id1examplecom"
    private val mixed = "----sterna_mixed_id1examplecom"

    private fun sign(bytes: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run { initSign(keys.private); update(bytes); sign() }

    private fun verifies(bytes: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withRSA").run { initVerify(keys.public); update(bytes); verify(signature) }

    private fun inner(attachments: List<OutgoingAttachment>) = OutgoingMessage(
        from = "-", to = listOf("-"), subject = "",
        body = textBody, html = htmlBody,
        messageId = "id-1@example.com", dateMillis = 0L,
        attachments = attachments,
    )

    private fun wire(entity: String) = OutgoingMime.build(
        OutgoingMessage(
            from = "alice@example.com", to = listOf("bob@example.com"), subject = "Hi", body = "",
            messageId = "outer@example.com", dateMillis = 0L, prebuiltEntity = entity,
        ),
    )

    private fun armor(signature: ByteArray): ByteArray =
        ("-----BEGIN PGP SIGNATURE-----\n\n" + Base64.getMimeEncoder().encodeToString(signature) +
            "\n-----END PGP SIGNATURE-----\n").toByteArray(Charsets.US_ASCII)

    private fun unarmor(armor: String): ByteArray =
        Base64.getMimeDecoder().decode(
            armor.substringAfter("-----BEGIN PGP SIGNATURE-----").substringBefore("-----END PGP SIGNATURE-----").trim(),
        )

    private enum class Scheme { PGP, SMIME }

    private fun roundTrip(scheme: Scheme, attachments: List<OutgoingAttachment>, outerBoundary: String) {
        for (protectedSubject in listOf(null, "Café")) {
            val case = "$scheme, ${attachments.map { it.name }}, protected subject $protectedSubject"
            val payload = PgpMime.signablePayload(OutgoingMime.buildBodyEntity(inner(attachments), protectedSubject))
            val signature = sign(payload.toByteArray(Charsets.UTF_8))
            val entity = when (scheme) {
                Scheme.PGP -> PgpMime.wrapSigned(payload, armor(signature), "pgp-sha256", "----sterna_sig_abc")
                Scheme.SMIME -> SmimeMime.wrapSigned(payload, signature, "----sterna_smime_abc")
            }
            val envelope = requireNotNull(MimeParser.detectCrypto(wire(entity))) { "$case: no envelope" }
            val signed = requireNotNull(envelope.signedEntityRaw) { "$case: no signed entity" }

            val read = MimeParser.parseBody(signed)
            assertEquals("$case: the text read back", textBody, read.text)
            assertEquals("$case: the html read back", htmlBody, read.html)
            assertEquals(
                "$case: attachments",
                attachments.map { it.name }.sorted(),
                read.attachments.map { it.name }.sorted(),
            )

            val carried = when (scheme) {
                Scheme.PGP -> unarmor(requireNotNull(envelope.signatureArmor) { "$case: no armor" })
                Scheme.SMIME -> requireNotNull(envelope.signatureDer) { "$case: no signature" }
            }
            assertTrue(
                "$case: the signature does not verify on the octets cut from the wire, signed:\n$payload\nextracted:\n$signed",
                verifies(signed.toByteArray(Charsets.UTF_8), carried),
            )

            assertFalse("$case: the payload keeps its final CRLF", payload.endsWith("\r\n"))
            assertTrue("$case: the payload ends on its outer delimiter", payload.endsWith("--$outerBoundary--"))
            assertEquals("$case: extracted octets", payload, signed)
        }
    }

    private companion object {
        val keys: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @Test fun `PGP, no attachment`() = roundTrip(Scheme.PGP, emptyList(), alternative)

    @Test fun `PGP, files only`() = roundTrip(Scheme.PGP, listOf(file), mixed)

    @Test fun `PGP, inline images only`() = roundTrip(Scheme.PGP, listOf(image), alternative)

    @Test fun `PGP, inline images and files`() = roundTrip(Scheme.PGP, listOf(image, file), mixed)

    @Test fun `S-MIME, no attachment`() = roundTrip(Scheme.SMIME, emptyList(), alternative)

    @Test fun `S-MIME, files only`() = roundTrip(Scheme.SMIME, listOf(file), mixed)

    @Test fun `S-MIME, inline images only`() = roundTrip(Scheme.SMIME, listOf(image), alternative)

    @Test fun `S-MIME, inline images and files`() = roundTrip(Scheme.SMIME, listOf(image, file), mixed)
}

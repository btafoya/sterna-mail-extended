package app.sterna.core.imap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SmimeDetectTest {

    /** Not a real signature: every octet class a text reading would damage (high bytes, NUL, CR, LF). */
    private val der = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0xFF.toByte(), 0x80.toByte(), 0x00, 0x0D, 0x0A, 0xE9.toByte())

    private val derBase64 = Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(der)

    /** An 8-bit body, the UTF-8 octets of "é" read one char per octet as the raw source is. */
    private val signedEntity =
        "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            "CafÃ©  signed\r\nworld"

    private fun detached(protocolParam: String, sigHeaders: String) = buildString {
        append("From: a@b.c\r\n")
        append("Content-Type: multipart/signed; micalg=sha-256$protocolParam; boundary=\"SIG\"\r\n\r\n")
        append("This is an S/MIME signed message\r\n")
        append("--SIG\r\n")
        append(signedEntity)
        append("\r\n--SIG\r\n")
        append(sigHeaders)
        append("Content-Transfer-Encoding: base64\r\n\r\n")
        append("$derBase64\r\n")
        append("--SIG--\r\n")
    }

    private fun assertDetached(raw: String) {
        val env = MimeParser.detectCrypto(raw)
        assertNotNull("not recognised as S/MIME", env)
        assertEquals(CryptoKind.SMIME_SIGNED, env!!.kind)
        assertEquals("the signed entity must be the verbatim first part", signedEntity, env.signedEntityRaw)
        assertTrue(raw.contains(env.signedEntityRaw!!))
        assertArrayEquals("the signature must be the DER octets", der, env.signatureDer)
    }

    @Test fun `a detached signature named by its protocol is recognised, bytes kept`() {
        assertDetached(
            detached(
                "; protocol=\"application/pkcs7-signature\"",
                "Content-Type: application/pkcs7-signature; name=\"smime.p7s\"\r\n" +
                    "Content-Disposition: attachment; filename=\"smime.p7s\"\r\n",
            ),
        )
    }

    @Test fun `the pre-standard x-pkcs7-signature protocol is recognised`() {
        assertDetached(
            detached(
                "; protocol=\"application/x-pkcs7-signature\"",
                "Content-Type: application/x-pkcs7-signature; name=\"smime.p7s\"\r\n",
            ),
        )
    }

    @Test fun `without a protocol parameter an unnamed signature part gives it away`() {
        assertDetached(detached("", "Content-Type: application/pkcs7-signature\r\n"))
    }

    @Test fun `an opaque signed-data message is recognised and its DER kept as octets`() {
        val raw = "From: a@b.c\r\n" +
            "Content-Type: application/pkcs7-mime; smime-type=signed-data; name=\"smime.p7m\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"smime.p7m\"\r\n\r\n" +
            "$derBase64\r\n"
        val env = MimeParser.detectCrypto(raw)
        assertNotNull("not recognised as opaque S/MIME", env)
        assertEquals(CryptoKind.SMIME_OPAQUE_SIGNED, env!!.kind)
        assertArrayEquals(der, env.signedDataDer)
    }

    @Test fun `an encrypted enveloped-data message is not taken for a signed one`() {
        val raw = "From: a@b.c\r\n" +
            "Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"\r\n" +
            "Content-Transfer-Encoding: base64\r\n" +
            "Content-Disposition: attachment; filename=\"smime.p7m\"\r\n\r\n" +
            "$derBase64\r\n"
        assertNull(MimeParser.detectCrypto(raw))
        assertEquals(listOf("smime.p7m"), MimeParser.parseBody(raw).attachments.map { it.name })
    }

    @Test fun `an unnamed signature part stays a typed part the reader can see`() {
        listOf("application/pkcs7-signature", "application/x-pkcs7-signature").forEach { type ->
            val parts = MimeParser.parseBody(detached("", "Content-Type: $type\r\n")).attachments
            assertEquals(type, listOf(type), parts.map { it.type })
        }
    }
}

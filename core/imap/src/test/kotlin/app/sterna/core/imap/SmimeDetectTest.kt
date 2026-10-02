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

    /** The opening of a real `ContentInfo` of type `enveloped-data`, then octets a text reading would damage. */
    private val envelopedDer = byteArrayOf(
        0x30, 0x82.toByte(), 0x03, 0x28, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x07, 0x03,
        0xA0.toByte(), 0x80.toByte(), 0x00, 0x0D, 0x0A, 0xE9.toByte(), 0xFF.toByte(),
    )

    private fun p7m(contentType: String, der: ByteArray = envelopedDer) = "From: a@b.c\r\n" +
        "Content-Type: $contentType\r\n" +
        "Content-Transfer-Encoding: base64\r\n" +
        "Content-Disposition: attachment; filename=\"smime.p7m\"\r\n\r\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n"

    @Test fun `an encrypted enveloped-data message is recognised, its DER kept as octets`() {
        listOf("application/pkcs7-mime", "application/x-pkcs7-mime", "Application/PKCS7-MIME").forEach { type ->
            val env = MimeParser.detectCrypto(p7m("$type; smime-type=enveloped-data; name=\"smime.p7m\""))
            assertEquals(type, CryptoKind.SMIME_ENCRYPTED, env?.kind)
            assertArrayEquals(type, envelopedDer, env!!.envelopedDataDer)
            assertNull("an enveloped message is no signed-data", env.signedDataDer)
        }
        assertTrue("the encrypted kind is an S/MIME kind", CryptoKind.SMIME_ENCRYPTED.isSmime)
    }

    @Test fun `a smime p7m with no smime-type is encrypted when its DER says enveloped-data`() {
        val env = MimeParser.detectCrypto(p7m("application/pkcs7-mime; name=\"smime.p7m\""))
        assertEquals(CryptoKind.SMIME_ENCRYPTED, env?.kind)
        assertArrayEquals(envelopedDer, env!!.envelopedDataDer)

        val signedData = envelopedDer.copyOf().also { it[14] = 0x02 }
        assertNull("signed-data with no smime-type is not taken for an encrypted message", MimeParser.detectCrypto(p7m("application/pkcs7-mime", signedData)))
        assertNull("nor are octets that are no DER at all", MimeParser.detectCrypto(p7m("application/pkcs7-mime", "not DER".toByteArray())))
    }

    @Test fun `the enveloped-data content type is read under every DER length form`() {
        val oid = envelopedDer.copyOfRange(4, 15)
        fun contentInfo(vararg length: Int) = byteArrayOf(0x30) + length.map { it.toByte() }.toByteArray() + oid + byteArrayOf(0xA0.toByte(), 0x00)
        listOf(intArrayOf(0x40), intArrayOf(0x80), intArrayOf(0x81, 0xC8), intArrayOf(0x82, 0x03, 0x28), intArrayOf(0x84, 0, 0, 0x03, 0x28))
            .forEach { length -> assertTrue(length.joinToString(), MimeParser.isEnvelopedContentInfo(contentInfo(*length))) }
        assertEquals("a length of more than four octets is refused", false, MimeParser.isEnvelopedContentInfo(contentInfo(0x85, 0, 0, 0, 0x03, 0x28)))
        assertEquals("a truncated opening is refused, not read past", false, MimeParser.isEnvelopedContentInfo(envelopedDer.copyOf(10)))
        assertEquals(false, MimeParser.isEnvelopedContentInfo(ByteArray(0)))
    }

    @Test fun `ordinary mail parsing still lists the smime p7m of an encrypted message`() {
        assertEquals(listOf("smime.p7m"), MimeParser.parseBody(p7m("application/pkcs7-mime; smime-type=enveloped-data")).attachments.map { it.name })
    }

    @Test fun `an unnamed signature part stays a typed part the reader can see`() {
        listOf("application/pkcs7-signature", "application/x-pkcs7-signature").forEach { type ->
            val parts = MimeParser.parseBody(detached("", "Content-Type: $type\r\n")).attachments
            assertEquals(type, listOf(type), parts.map { it.type })
        }
    }
}

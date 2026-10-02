package app.sterna.core.imap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SmimeMime.wrapSigned], as the message leaves through [OutgoingMime.build] and as a reader cuts it up. */
class SmimeMimeTest {

    private val payload = PgpMime.signablePayload(
        OutgoingMime.buildBodyEntity(
            OutgoingMessage(
                from = "-", to = listOf("-"), subject = "", body = "Bonjour, café.\nSecond line.", html = null,
                messageId = "m@x", dateMillis = 1_700_000_000_000,
            ),
        ),
    )
    private val signature = ByteArray(700) { (it * 7).toByte() }
    private val entity = SmimeMime.wrapSigned(payload, signature, "----sterna_smime_abc")

    private fun sent(): String = OutgoingMime.build(
        OutgoingMessage(
            from = "alice@example.test", to = listOf("bob@example.test"), subject = "Hi", body = "", html = null,
            messageId = "m@x", dateMillis = 1_700_000_000_000, prebuiltEntity = entity,
        ),
    )

    @Test fun `the entity is a multipart signed for pkcs7 with sha-256`() {
        val header = entity.substringBefore("\r\n\r\n").replace("\r\n ", " ")
        assertEquals(
            "Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\"; micalg=sha-256; boundary=\"----sterna_smime_abc\"",
            header,
        )
        assertTrue(entity.contains("Content-Type: application/pkcs7-signature; name=\"smime.p7s\"\r\nContent-Transfer-Encoding: base64\r\n"))
        assertTrue(entity.contains("Content-Disposition: attachment; filename=\"smime.p7s\"\r\n"))
    }

    @Test fun `what leaves is CRLF only, its signature in lines of 76`() {
        val wire = sent()
        assertFalse("a bare LF", Regex("(?<!\r)\n").containsMatchIn(wire))
        assertFalse("a bare CR", Regex("\r(?!\n)").containsMatchIn(wire))
        val base64 = wire.substringAfter("filename=\"smime.p7s\"").substringAfter("\r\n\r\n").substringBefore("\r\n--")
        val lines = base64.split("\r\n")
        assertTrue("a line over 76: ${lines.maxOf { it.length }}", lines.all { it.length <= 76 })
        assertEquals(76, lines.first().length)
    }

    @Test fun `a reader cuts out the octets signed and the signature, unchanged`() {
        val envelope = MimeParser.detectCrypto(sent())!!
        assertEquals(CryptoKind.SMIME_SIGNED, envelope.kind)
        assertEquals(payload, envelope.signedEntityRaw)
        assertArrayEquals(signature, envelope.signatureDer)
    }

    @Test fun `an envelope leaves as one pkcs7-mime part the reader finds, CRLF only, in lines of 76`() {
        val der = ByteArray(900) { (it * 13).toByte() }
        val wire = OutgoingMime.build(
            OutgoingMessage(
                from = "alice@example.test", to = listOf("bob@example.test"), subject = "Hi", body = "", html = null,
                messageId = "m@x", dateMillis = 1_700_000_000_000, prebuiltEntity = SmimeMime.wrapEnveloped(der),
            ),
        )
        assertTrue(wire.contains("\r\nContent-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"\r\n"))
        assertTrue(wire.contains("\r\nContent-Disposition: attachment; filename=\"smime.p7m\"\r\n"))
        assertFalse("a bare LF", Regex("(?<!\r)\n").containsMatchIn(wire))
        assertFalse("a bare CR", Regex("\r(?!\n)").containsMatchIn(wire))
        val lines = wire.substringAfter("\r\n\r\n").trimEnd().split("\r\n")
        assertTrue("a line over 76: ${lines.maxOf { it.length }}", lines.all { it.length <= 76 })
        assertEquals(76, lines.first().length)
        val envelope = MimeParser.detectCrypto(wire)!!
        assertEquals(CryptoKind.SMIME_ENCRYPTED, envelope.kind)
        assertArrayEquals(der, envelope.envelopedDataDer)
    }
}

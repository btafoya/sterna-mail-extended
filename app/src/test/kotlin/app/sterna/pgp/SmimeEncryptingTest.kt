package app.sterna.pgp

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeParser
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSEnvelopedData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What leaves the composer in [PgpMode.SMIME_ENCRYPT] ([smimeEntity]): the signed entity whole, inside an envelope
 * the reader's own path opens ([BouncyCastleSmimeEngine.decrypt], then the signature checked as `clearOfEnvelope`
 * checks it), for each recipient and for the sender; or a refusal, and nothing signed.
 *
 * What this does NOT cover: a key from the Android keystore decrypting ([KeystoreOnlyKey] only signs), nor another
 * client opening the message. `encryptedForReader` writes one, for the fixture certificate `reader`, under
 * `build/smime-encrypted/` for `openssl cms -decrypt` to be run on by hand.
 */
class SmimeEncryptingTest {

    private val authority = SmimeTestAuthority()
    private val aliceKeys = authority.rsa()
    private val alice = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test")
    private val bobKeys = authority.rsa()
    private val bob = authority.leaf("CN=Bob", bobKeys, address = "bob@example.test")
    private val carolKeys = authority.rsa()
    private val carol = authority.leaf("CN=Carol", carolKeys, address = "carol@example.test")
    private val now = Date()

    private fun key(certificate: X509Certificate = alice) =
        SmimeKey(KeystoreOnlyKey(aliceKeys.private), listOf(certificate, authority.intermediate, authority.root))

    private class CountingEngine(private val inner: BouncyCastleSmimeEngine) : SmimeEngine by inner {
        var signed = 0
        var encrypted = 0

        override fun sign(signedBytes: ByteArray, key: SmimeKey): ByteArray {
            signed++
            return inner.sign(signedBytes, key)
        }

        override fun encrypt(content: ByteArray, recipients: List<X509Certificate>): ByteArray {
            encrypted++
            return inner.encrypt(content, recipients)
        }
    }

    private val bc = BouncyCastleSmimeEngine(trustAnchors = { setOf(authority.root) })
    private val engine = CountingEngine(bc)

    private val payload = "Content-Type: text/plain; charset=utf-8\r\n\r\nThe meeting moves to Thursday."

    private val kept = mapOf("bob@example.test" to bob.encoded, "carol@example.test" to carol.encoded)

    private fun encrypt(
        recipients: List<String> = listOf("bob@example.test", "carol@example.test"),
        bcc: List<String> = emptyList(),
        held: Map<String, ByteArray> = kept,
        own: X509Certificate = alice,
        keys: SmimeKeySource = SmimeKeySource { key(own) },
    ): String = withKeystoreProvider {
        smimeEntity(payload, "----sterna_smime_t", PgpMode.SMIME_ENCRYPT, "alice", "alice@example.test", keys, engine, now, recipients, bcc, held)
    }

    private fun envelopeOf(entity: String): ByteArray {
        val envelope = MimeParser.detectCrypto("MIME-Version: 1.0\r\n$entity")
            ?: error("the reader finds no S/MIME in <${entity.take(200)}>")
        assertEquals(CryptoKind.SMIME_ENCRYPTED, envelope.kind)
        return envelope.envelopedDataDer!!
    }

    /** The reader's path once the envelope is open: the inner entity, then its signature checked. */
    private fun assertSignedByAlice(clear: ByteArray) {
        val inner = MimeParser.detectCrypto(clear.toString(Charsets.ISO_8859_1))
            ?: error("the envelope carries no signature: <${clear.toString(Charsets.ISO_8859_1).take(200)}>")
        assertEquals(CryptoKind.SMIME_SIGNED, inner.kind)
        assertEquals("the payload is carried as it was signed", payload, inner.signedEntityRaw)
        val verified = bc.verifyDetached(inner.signedEntityRaw!!.toByteArray(Charsets.ISO_8859_1), inner.signatureDer!!, "alice@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
    }

    @Test fun `each recipient opens the message and finds it signed by the sender`() {
        val der = envelopeOf(encrypt())
        assertSignedByAlice(bc.decrypt(der, bobKeys.private, bob))
        assertSignedByAlice(bc.decrypt(der, carolKeys.private, carol))
    }

    @Test fun `the sender opens the copy in Sent`() {
        val der = envelopeOf(encrypt())
        assertTrue("the envelope does not name the sender's own certificate: Sent cannot be reopened", bc.namesRecipient(der, alice))
        assertSignedByAlice(bc.decrypt(der, aliceKeys.private, alice))
    }

    @Test fun `the envelope is for the recipients and the sender, no one else, in AES-256-CBC with RSA key transport`() {
        val envelope = CMSEnvelopedData(envelopeOf(encrypt(recipients = listOf("bob@example.test"))))
        assertEquals(CMSAlgorithm.AES256_CBC.id, envelope.encryptionAlgOID)
        val recipients = envelope.recipientInfos.recipients
        assertEquals(2, recipients.size)
        assertTrue(recipients.all { it.keyEncryptionAlgOID == PKCSObjectIdentifiers.rsaEncryption.id })
        assertTrue(recipients.any { it.rid.match(JcaX509CertificateHolder(bob)) })
        assertTrue(recipients.any { it.rid.match(JcaX509CertificateHolder(alice)) })
        assertFalse("carol, not addressed, can open it", bc.namesRecipient(envelope.encoded, carol))
    }

    @Test fun `the message is one pkcs7-mime entity, base64 in lines of 76, and the body is nowhere in it`() {
        val entity = encrypt()
        val header = entity.substringBefore("\r\n\r\n")
        assertEquals(
            listOf(
                "Content-Type: application/pkcs7-mime; smime-type=enveloped-data; name=\"smime.p7m\"",
                "Content-Disposition: attachment; filename=\"smime.p7m\"",
                "Content-Transfer-Encoding: base64",
                "Content-Description: S/MIME Encrypted Message",
            ),
            header.split("\r\n"),
        )
        assertFalse("a bare LF", Regex("(?<!\r)\n").containsMatchIn(entity))
        val lines = entity.substringAfter("\r\n\r\n").trimEnd().split("\r\n")
        assertTrue("a line over 76", lines.all { it.length <= 76 })
        assertEquals(76, lines.first().length)
        assertFalse("the body travels in the clear", "Thursday" in entity)
        assertFalse("the signature travels in the clear", "pkcs7-signature" in entity)
    }

    @Test fun `a Bcc recipient refuses the message, which is neither signed nor encrypted`() {
        assertRefused(SmimeRefusal.BCC_WHILE_ENCRYPTING) { encrypt(bcc = listOf("carol@example.test")) }
        assertEquals(0, engine.signed)
        assertEquals(0, engine.encrypted)
    }

    @Test fun `a recipient with no certificate kept, or one no longer valid, refuses the message`() {
        assertRefused(SmimeRefusal.RECIPIENT_WITHOUT_CERTIFICATE) { encrypt(held = mapOf("bob@example.test" to bob.encoded)) }
        val expired = authority.leaf(
            "CN=Carol", carolKeys, address = "carol@example.test",
            notBefore = Date(now.time - 400 * SmimeTestAuthority.DAY), notAfter = Date(now.time - SmimeTestAuthority.DAY),
        )
        assertRefused(SmimeRefusal.RECIPIENT_WITHOUT_CERTIFICATE) { encrypt(held = kept + ("carol@example.test" to expired.encoded)) }
        assertRefused(SmimeRefusal.RECIPIENT_WITHOUT_CERTIFICATE) { encrypt(held = kept + ("carol@example.test" to byteArrayOf(0x30, 0x03))) }
        val signOnly = authority.leaf("CN=Carol", carolKeys, address = "carol@example.test", usage = KeyUsage.digitalSignature)
        assertRefused(SmimeRefusal.RECIPIENT_WITHOUT_CERTIFICATE) { encrypt(held = kept + ("carol@example.test" to signOnly.encoded)) }
        assertEquals(0, engine.signed)
    }

    @Test fun `the sender's own certificate expired, gone, or not for encryption refuses the message`() {
        val expired = authority.leaf(
            "CN=Alice", aliceKeys, address = "alice@example.test",
            notBefore = Date(now.time - 400 * SmimeTestAuthority.DAY), notAfter = Date(now.time - SmimeTestAuthority.DAY),
        )
        assertRefused(SmimeRefusal.NOT_VALID_NOW) { encrypt(own = expired) }
        assertRefused(SmimeRefusal.NO_CERTIFICATE) { encrypt(keys = SmimeKeySource { null }) }
        assertRefused(SmimeRefusal.KEYSTORE_UNANSWERED) { encrypt(keys = SmimeKeySource { throw KeystoreUnanswered(InterruptedException()) }) }
        val signOnly = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test", usage = KeyUsage.digitalSignature)
        assertRefused(SmimeRefusal.NOT_FOR_ENCRYPTING) { encrypt(own = signOnly) }
        assertEquals(0, engine.signed)
    }

    @Test fun `the envelope's recipients are the addressed ones and the sender, read from what is kept`() {
        assertEquals(
            listOf(bob, carol, alice),
            smimeEnvelopeRecipients(listOf("bob@example.test", "carol@example.test"), emptyList(), kept, alice, "alice@example.test", now),
        )
        assertEquals(
            "a sender in To or Cc is covered by their own certificate, never kept from a message of theirs, and named once",
            listOf(alice, bob),
            smimeEnvelopeRecipients(listOf(" Alice@Example.TEST", "bob@example.test"), emptyList(), kept, alice, "alice@example.test", now),
        )
    }

    @Test fun `a sender who copies themself is sent the message, and opens it, with no certificate kept for them`() {
        assertFalse("alice@example.test" in kept)
        val der = envelopeOf(encrypt(recipients = listOf("bob@example.test", "Alice@example.test")))
        assertSignedByAlice(bc.decrypt(der, aliceKeys.private, alice))
        assertSignedByAlice(bc.decrypt(der, bobKeys.private, bob))
    }

    @Test fun `the keystore's private key is never read out`() {
        val signer = KeystoreOnlyKey(aliceKeys.private)
        withKeystoreProvider {
            smimeEntity(
                payload, "----sterna_smime_t", PgpMode.SMIME_ENCRYPT, "alice", "alice@example.test",
                { SmimeKey(signer, listOf(alice, authority.intermediate)) }, engine, now, listOf("bob@example.test"), emptyList(), kept,
            )
        }
        assertEquals(0, signer.readOut)
    }

    @Test fun `encryptedForReader, written for openssl to open`() {
        val reader = CertificateFactory.getInstance("X.509")
            .generateCertificate(javaClass.getResourceAsStream("/smime/reader-cert.pem")!!) as X509Certificate
        val entity = encrypt(recipients = listOf("reader@example.test"), held = mapOf("reader@example.test" to reader.encoded))
        val der = envelopeOf(entity)
        assertTrue(bc.namesRecipient(der, reader))
        val out = File("build/smime-encrypted").apply { mkdirs() }
        File(out, "for-reader.eml").writeText(
            "From: alice@example.test\r\nTo: reader@example.test\r\nSubject: S/MIME\r\nMIME-Version: 1.0\r\n$entity",
            Charsets.US_ASCII,
        )
        File(out, "payload.txt").writeBytes(payload.toByteArray(Charsets.UTF_8))
        File(out, "chain.pem").writeText(
            listOf(authority.root, authority.intermediate).joinToString("") {
                "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it.encoded) +
                    "\n-----END CERTIFICATE-----\n"
            },
        )
    }

    private fun assertRefused(reason: SmimeRefusal, send: () -> String) {
        try {
            val sent = send()
            fail("the message left, as <${sent.take(80)}>, instead of being refused for $reason")
        } catch (refused: SmimeSendRefused) {
            assertEquals(reason, refused.reason)
        }
    }
}

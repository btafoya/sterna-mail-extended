package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.pgp.SmimeVerified
import app.sterna.core.imap.MimeParser
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a checked S/MIME signature hands on to be kept: [smimeCertificateFound] executed on each verdict, and the
 * signature checks of [smimeVerified] and [openSmimeEnvelope] executed with a scripted engine, to show the
 * certificate reaches the keeper from a signed message and from one signed then encrypted, and that a keeper that
 * throws costs the reader nothing.
 */
class SmimeCertificateFoundTest {

    private val der = byteArrayOf(0x30, 0x01, 0x02)

    private class ScriptedEngine(private val verdict: SmimeVerified, private val clear: ByteArray = ByteArray(0)) : SmimeEngine {
        override fun verifyDetached(signedBytes: ByteArray, signature: ByteArray, senderAddress: String?) = verdict

        override fun verifyOpaque(signedData: ByteArray, senderAddress: String?) = verdict

        override fun namesRecipient(envelopedData: ByteArray, certificate: X509Certificate) = true

        override fun decrypt(envelopedData: ByteArray, key: PrivateKey, certificate: X509Certificate) = clear

        override fun sign(signedBytes: ByteArray, key: SmimeKey): ByteArray = error("a read path never signs")
        override fun encrypt(content: ByteArray, recipients: List<X509Certificate>): ByteArray = error("a read path never encrypts")
    }

    private fun resource(name: String) = javaClass.getResourceAsStream("/smime/$name")!!.readBytes()

    private fun signed() = MimeParser.detectCrypto(resource("a-detached.eml").toString(Charsets.ISO_8859_1))!!

    @Test fun `only a confirmed signature that named a certificate teaches one, for the From address`() {
        val found = smimeCertificateFound(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "alice@example.test", null, der), "Alice@example.test")
        assertEquals("Alice@example.test", found?.address)
        assertArrayEquals(der, found?.certificate)

        PgpSignatureState.entries.filter { it != PgpSignatureState.VALID_CONFIRMED }.forEach { state ->
            assertNull("$state", smimeCertificateFound(SmimeVerified(state, "alice@example.test", null, der), "alice@example.test"))
        }
        assertNull("no certificate", smimeCertificateFound(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "a", null, null), "alice@example.test"))
        assertNull("no From", smimeCertificateFound(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "a", null, der), null))
        assertNull("a blank From", smimeCertificateFound(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "a", null, der), " "))
    }

    @Test fun `the rank is the earlier of the signing time and the server's date`() {
        val stated = SmimeCertificateFound("a@example.test", der, signedAt = 1_000)
        assertEquals("an old message delivered again keeps its old rank", 1_000L, smimeCertificateRank(stated, serverDate = 9_000))
        assertEquals("a clock ahead of the server is bounded by it", 500L, smimeCertificateRank(stated, serverDate = 500))
        assertEquals("no signing time: the server's date", 9_000L, smimeCertificateRank(SmimeCertificateFound("a@example.test", der), 9_000))
        assertEquals(
            "the signing time travels from the verdict",
            1_234L,
            smimeCertificateFound(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "a", null, der, signedAt = 1_234), "a@example.test")?.signedAt,
        )
    }

    @Test fun `a signed message hands its certificate to the keeper`() {
        val kept = mutableListOf<SmimeCertificateFound>()
        val engine = ScriptedEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "alice@example.test", null, der))

        smimeVerified(engine, signed(), "alice@example.test", wasEncrypted = false) { kept += it }

        assertEquals(listOf("alice@example.test"), kept.map { it.address })
        assertArrayEquals(der, kept.single().certificate)
    }

    @Test fun `an unconfirmed signature hands nothing, even with a certificate in the verdict`() {
        val kept = mutableListOf<SmimeCertificateFound>()
        val engine = ScriptedEngine(SmimeVerified(PgpSignatureState.VALID_UNCONFIRMED, "alice@example.test", null, der))

        smimeVerified(engine, signed(), "alice@example.test", wasEncrypted = false) { kept += it }

        assertTrue(kept.isEmpty())
    }

    @Test fun `a keeper that throws leaves the verdict as it was`() {
        val engine = ScriptedEngine(SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "alice@example.test", null, der))

        val shown = smimeVerified(engine, signed(), "alice@example.test", wasEncrypted = false) { error("disk full") }

        assertEquals(PgpSignatureState.VALID_CONFIRMED, shown.signature)
        assertEquals("alice@example.test", shown.signatureUserId)
    }

    @Test fun `the signature inside an envelope hands its certificate to the keeper`() {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(resource("reader-cert.pem").inputStream()) as X509Certificate
        val key: PrivateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private
        val keys = SmimeKeySource { SmimeKey(key, listOf(certificate)) }
        val engine = ScriptedEngine(
            SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "alice@example.test", null, der),
            clear = resource("a-detached.eml"),
        )
        val kept = mutableListOf<SmimeCertificateFound>()

        val result = openSmimeEnvelope(byteArrayOf(0x30), "reader", keys, engine, "alice@example.test") { kept += it }

        assertTrue("$result", result is PgpResult.Success)
        assertEquals(listOf("alice@example.test"), kept.map { it.address })
    }
}

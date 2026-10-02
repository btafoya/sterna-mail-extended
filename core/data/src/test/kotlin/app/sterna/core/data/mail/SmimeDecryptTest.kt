package app.sterna.core.data.mail

import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.pgp.SmimeVerified
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executes the decisions `MailRepository.decryptMessage` makes on an S/MIME envelope, with a scripted engine:
 * which answer each state of the account and the keystore gives, and what a decrypted signature makes of the
 * message. `SmimeReadPathTest` runs the same through the repository.
 */
class SmimeDecryptTest {

    private class ScriptedEngine(
        private val names: () -> Boolean = { true },
        private val clear: () -> ByteArray = { ByteArray(0) },
        private val verdict: SmimeVerified = SmimeVerified(PgpSignatureState.VALID_UNCONFIRMED, "alice@example.test", null),
    ) : SmimeEngine {
        val calls = mutableListOf<String>()
        val detached = mutableListOf<ByteArray>()

        override fun verifyDetached(signedBytes: ByteArray, signature: ByteArray, senderAddress: String?): SmimeVerified {
            calls += "verifyDetached"
            detached += signedBytes
            return verdict
        }

        override fun verifyOpaque(signedData: ByteArray, senderAddress: String?): SmimeVerified {
            calls += "verifyOpaque"
            return verdict
        }

        override fun namesRecipient(envelopedData: ByteArray, certificate: X509Certificate): Boolean {
            calls += "namesRecipient"
            return names()
        }

        override fun decrypt(envelopedData: ByteArray, key: PrivateKey, certificate: X509Certificate): ByteArray {
            calls += "decrypt"
            return clear()
        }
    }

    private val certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/smime/reader-cert.pem")!!) as X509Certificate

    private val key: PrivateKey = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().private

    private val keystore = SmimeKeySource { alias -> if (alias == "reader") SmimeKey(key, listOf(certificate)) else null }

    private val envelope = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)

    private fun resource(name: String) = javaClass.getResourceAsStream("/smime/$name")!!.readBytes()

    private fun open(engine: SmimeEngine, alias: String = "reader", keys: SmimeKeySource? = keystore) =
        openSmimeEnvelope(envelope, alias, keys, engine, "alice@example.test")

    @Test fun `no certificate chosen asks for one, and nothing else is asked`() {
        val engine = ScriptedEngine()
        val asked = mutableListOf<String>()

        val result = open(engine, alias = "", keys = SmimeKeySource { asked += it; null })

        assertEquals(PgpResult.CertificateToChoose, result)
        assertEquals("the keystore is not asked", emptyList<String>(), asked)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test fun `a certificate the keystore will not hand over is asked for again`() {
        val engine = ScriptedEngine()

        val result = open(engine, alias = "removed")

        assertEquals(PgpResult.CertificateToChoose, result)
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test fun `a keystore that cannot answer now is a failure to decrypt, not a fetch error`() {
        val failing = open(ScriptedEngine(), keys = SmimeKeySource { error("keystore locked") })
        val missing = open(ScriptedEngine(), keys = null)

        assertEquals(PgpResult.DecryptFailed("keystore locked"), failing)
        assertTrue("$missing", missing is PgpResult.DecryptFailed)
    }

    @Test fun `an envelope that does not name the certificate says so before any decryption`() {
        val engine = ScriptedEngine(names = { false })

        val result = open(engine)

        assertEquals(PgpResult.NotEncryptedForCertificate, result)
        assertEquals(listOf("namesRecipient"), engine.calls)
    }

    @Test fun `an unreadable envelope or a refused decryption is a failure to decrypt, never a throw`() {
        val truncated = open(ScriptedEngine(names = { throw IllegalArgumentException("truncated DER") }))
        val elliptic = open(ScriptedEngine(clear = { error("Only RSA key transport is supported") }))

        assertEquals(PgpResult.DecryptFailed("truncated DER"), truncated)
        assertEquals(PgpResult.DecryptFailed("Only RSA key transport is supported"), elliptic)
    }

    @Test fun `a plain envelope is encrypted and unsigned, its content read one char per octet`() {
        val content = "Content-Type: text/plain; charset=ISO-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\ncafé ÿ\r\n"
            .toByteArray(Charsets.ISO_8859_1)
        val engine = ScriptedEngine(clear = { content })

        val opened = (open(engine) as? PgpResult.Success)?.value ?: error("expected the content")

        assertEquals(listOf("namesRecipient", "decrypt"), engine.calls)
        assertEquals(true, opened.wasEncrypted)
        assertEquals(PgpSignatureState.NONE, opened.signature)
        assertArrayEquals(content, opened.plaintext)
    }

    @Test fun `a detached signature inside the envelope is checked, and the message stays encrypted`() {
        val engine = ScriptedEngine(clear = { resource("a-detached.eml") })

        val opened = (open(engine) as? PgpResult.Success)?.value ?: error("expected the content")

        assertEquals(listOf("namesRecipient", "decrypt", "verifyDetached"), engine.calls)
        assertEquals("encrypted", true, opened.wasEncrypted)
        assertEquals("and signed", PgpSignatureState.VALID_UNCONFIRMED, opened.signature)
        assertEquals("alice@example.test", opened.signatureUserId)
        val shown = opened.plaintext.toString(Charsets.ISO_8859_1)
        assertTrue("what is shown is the signed entity alone: $shown", shown.startsWith("Content-Type: text/plain"))
        assertArrayEquals(canonicalizeCrlf(shown).toByteArray(Charsets.ISO_8859_1), engine.detached.single())
    }

    @Test fun `an opaque signature inside the envelope is checked, and what it carries is shown`() {
        val carried = "Content-Type: text/plain\r\n\r\ninside\r\n".toByteArray(Charsets.ISO_8859_1)
        val engine = ScriptedEngine(
            clear = { resource("e-opaque.eml") },
            verdict = SmimeVerified(PgpSignatureState.VALID_CONFIRMED, "alice@example.test", carried),
        )

        val opened = (open(engine) as? PgpResult.Success)?.value ?: error("expected the content")

        assertEquals(listOf("namesRecipient", "decrypt", "verifyOpaque"), engine.calls)
        assertEquals(true, opened.wasEncrypted)
        assertEquals(PgpSignatureState.VALID_CONFIRMED, opened.signature)
        assertArrayEquals(carried, opened.plaintext)
    }

    @Test fun `a signature on its own is not encrypted`() {
        val engine = ScriptedEngine()
        val inner = app.sterna.core.imap.MimeParser.detectCrypto(resource("a-detached.eml").toString(Charsets.ISO_8859_1))!!

        assertEquals(false, smimeVerified(engine, inner, "alice@example.test", wasEncrypted = false).wasEncrypted)
    }
}

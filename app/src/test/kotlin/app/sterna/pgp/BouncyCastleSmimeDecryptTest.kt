package app.sterna.pgp

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeParser
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.InvalidKeyException
import java.security.Key
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BouncyCastleSmimeEngine] opening messages `openssl cms -encrypt -aes-256-cbc` wrote, with throwaway self-signed
 * certificates whose keys are fixtures: `reader` (RSA), `reader-ec` (P-256), and `other`, whose key is not kept.
 * `alice`, whose key is not kept either, signed the entities of `m` and `n`.
 *
 * - `g-enveloped`: for reader, a multipart holding an 8-bit text and a binary file.
 * - `m-signed-then-enveloped`: for reader, alice's detached signature over the `a-detached` entity.
 * - `n-opaque-signed-then-enveloped`: for reader, alice's `signed-data` carrying that entity.
 * - `o-enveloped-for-other`: `g`'s entity, for other only. `p-enveloped-ec`: for reader-ec.
 * - `q-enveloped-truncated`: `g` with the last two fifths of its DER cut off.
 * - `r-enveloped-no-smime-type`: `g` with no `smime-type` on its content type.
 * - `s-enveloped-many-by-key-id`: for other then reader, each named by subject key identifier (`-keyid`).
 *
 * What this does NOT cover: a key from the Android keystore. [KeystoreOnlyKey] stands in for one: a key no
 * provider but its own can use, as only the keystore's provider can use a keystore key. A device is the only
 * place the real one runs.
 */
class BouncyCastleSmimeDecryptTest {

    private val engine = BouncyCastleSmimeEngine(trustAnchors = { emptySet() })

    private fun resource(name: String) = javaClass.getResourceAsStream("/smime/$name")!!.readBytes()

    private fun certificate(name: String) =
        CertificateFactory.getInstance("X.509").generateCertificate(resource(name).inputStream()) as X509Certificate

    private fun key(name: String, algorithm: String): PrivateKey = KeyFactory.getInstance(algorithm).generatePrivate(
        PKCS8EncodedKeySpec(
            Base64.getMimeDecoder().decode(
                resource(name).toString(Charsets.US_ASCII).lines().filterNot { it.startsWith("-----") }.joinToString(""),
            ),
        ),
    )

    private val reader = certificate("reader-cert.pem")
    private val readerKey = key("reader-key.pem", "RSA")
    private val readerEc = certificate("reader-ec-cert.pem")
    private val readerEcKey = key("reader-ec-key.pem", "EC")
    private val other = certificate("other-cert.pem")

    /** A key that refuses to be read out, as one the Android keystore holds; it still signs and decrypts. */
    private class OpaqueKey(private val inner: RSAPrivateKey) : RSAPrivateKey {
        var readOut = 0

        override fun getAlgorithm(): String = inner.algorithm

        override fun getFormat(): String? = null

        override fun getEncoded(): ByteArray? {
            readOut++
            return null
        }

        override fun getModulus(): BigInteger = inner.modulus

        override fun getPrivateExponent(): BigInteger = inner.privateExponent
    }

    /** Implements no `RSAPrivateKey`: Bouncy Castle cannot use it, [KeystoreOnlyProvider] alone can. */
    private class KeystoreOnlyKey(val inner: PrivateKey) : PrivateKey {
        override fun getAlgorithm(): String = "RSA"

        override fun getFormat(): String? = null

        override fun getEncoded(): ByteArray? = null
    }

    private class KeystoreOnlyCipher : CipherSpi() {
        private val delegate: Cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", "SunJCE")

        private fun inner(key: Key): PrivateKey = (key as? KeystoreOnlyKey)?.inner ?: throw InvalidKeyException("not a KeystoreOnlyKey")

        override fun engineSetMode(mode: String) = Unit

        override fun engineSetPadding(padding: String) = Unit

        override fun engineGetBlockSize(): Int = 0

        override fun engineGetOutputSize(inputLen: Int): Int = delegate.getOutputSize(inputLen)

        override fun engineGetIV(): ByteArray? = null

        override fun engineGetParameters(): AlgorithmParameters? = null

        override fun engineInit(opmode: Int, key: Key, random: SecureRandom?) = delegate.init(opmode, inner(key), random)

        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameterSpec?, random: SecureRandom?) =
            delegate.init(opmode, inner(key), params, random)

        override fun engineInit(opmode: Int, key: Key, params: AlgorithmParameters?, random: SecureRandom?) =
            delegate.init(opmode, inner(key), params, random)

        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? =
            delegate.update(input, inputOffset, inputLen)

        override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
            delegate.update(input, inputOffset, inputLen, output, outputOffset)

        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
            delegate.doFinal(input, inputOffset, inputLen)

        override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
            delegate.doFinal(input, inputOffset, inputLen, output, outputOffset)

        override fun engineUnwrap(wrappedKey: ByteArray, wrappedKeyAlgorithm: String, wrappedKeyType: Int): Key =
            delegate.unwrap(wrappedKey, wrappedKeyAlgorithm, wrappedKeyType)
    }

    /** The keystore's provider, in miniature: RSA for a [KeystoreOnlyKey] and nothing else. */
    private class KeystoreOnlyProvider : Provider("SternaTestKeystore", 1.0, "RSA for KeystoreOnlyKey only") {
        init {
            putService(
                object : Provider.Service(this, "Cipher", "RSA/ECB/PKCS1Padding", KeystoreOnlyCipher::class.java.name, null, null) {
                    override fun newInstance(constructorParameter: Any?): Any = KeystoreOnlyCipher()
                },
            )
        }
    }

    /**
     * [block] with [KeystoreOnlyProvider] registered, and removed after. Registration cannot be avoided: the engine
     * names no provider for the key, on purpose, so the JCA finds it among the registered ones, as on Android.
     */
    private fun <T> withKeystoreProvider(block: () -> T): T {
        val keystore = KeystoreOnlyProvider()
        Security.addProvider(keystore)
        try {
            return block()
        } finally {
            Security.removeProvider(keystore.name)
        }
    }

    private fun envelope(name: String): ByteArray {
        val raw = resource(name).toString(Charsets.ISO_8859_1)
        val found = MimeParser.detectCrypto(raw) ?: error("$name holds no S/MIME envelope")
        assertEquals(name, CryptoKind.SMIME_ENCRYPTED, found.kind)
        return found.envelopedDataDer!!
    }

    @Test fun `an envelope for the reader names the reader's certificate, and opens to the entity it carries`() {
        val der = envelope("g-enveloped.eml")

        assertEquals(true, engine.namesRecipient(der, reader))
        assertArrayEquals(MIXED_ENTITY.toByteArray(Charsets.ISO_8859_1), engine.decrypt(der, readerKey, reader))
    }

    @Test fun `the key is used where it lives and never read out`() {
        val opaque = OpaqueKey(readerKey as RSAPrivateKey)

        val clear = engine.decrypt(envelope("g-enveloped.eml"), opaque, reader)

        assertArrayEquals(MIXED_ENTITY.toByteArray(Charsets.ISO_8859_1), clear)
        assertEquals("getEncoded was called", 0, opaque.readOut)
    }

    @Test fun `a key only its own provider can use is handed to that provider, not to Bouncy Castle`() {
        val clear = withKeystoreProvider { engine.decrypt(envelope("g-enveloped.eml"), KeystoreOnlyKey(readerKey), reader) }

        assertArrayEquals(MIXED_ENTITY.toByteArray(Charsets.ISO_8859_1), clear)
    }

    @Test fun `the content is decrypted by Bouncy Castle, whatever cipher the platform lacks`() {
        val bc = BouncyCastleProvider()
        val camellia = CMSEnvelopedDataGenerator().run {
            addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(reader).setProvider(bc))
            generate(
                CMSProcessableByteArray(SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1)),
                JceCMSContentEncryptorBuilder(CMSAlgorithm.CAMELLIA128_CBC).setProvider(bc).build(),
            ).encoded
        }
        assertTrue("the JDK must not know Camellia, or this proves nothing", Security.getProviders("Cipher.CAMELLIA") == null)

        val clear = withKeystoreProvider { engine.decrypt(camellia, KeystoreOnlyKey(readerKey), reader) }

        assertArrayEquals(SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1), clear)
    }

    @Test fun `an envelope for another certificate does not name the reader's, and does not open with it`() {
        val der = envelope("o-enveloped-for-other.eml")

        assertEquals(false, engine.namesRecipient(der, reader))
        assertEquals(true, engine.namesRecipient(der, other))
        assertTrue(runCatching { engine.decrypt(der, readerKey, reader) }.isFailure)
    }

    @Test fun `recipients named by key identifier are matched, the reader second of two`() {
        val der = envelope("s-enveloped-many-by-key-id.eml")

        assertEquals(true, engine.namesRecipient(der, other))
        assertEquals(true, engine.namesRecipient(der, reader))
        assertEquals(false, engine.namesRecipient(der, readerEc))
        assertArrayEquals(MIXED_ENTITY.toByteArray(Charsets.ISO_8859_1), engine.decrypt(der, readerKey, reader))
    }

    @Test fun `an elliptic-curve recipient is named, and refused as an ordinary error`() {
        val der = envelope("p-enveloped-ec.eml")

        assertEquals(true, engine.namesRecipient(der, readerEc))
        val refusal = runCatching { engine.decrypt(der, readerEcKey, readerEc) }.exceptionOrNull()
        assertTrue("$refusal", refusal is IllegalStateException && "RSA" in refusal.message.orEmpty())
    }

    @Test fun `a truncated envelope is an error, not an answer about the certificate`() {
        val der = envelope("q-enveloped-truncated.eml")

        assertTrue(runCatching { engine.namesRecipient(der, reader) }.isFailure)
        assertTrue(runCatching { engine.decrypt(der, readerKey, reader) }.isFailure)
    }

    @Test fun `an envelope whose sender left out smime-type opens all the same`() {
        assertArrayEquals(
            MIXED_ENTITY.toByteArray(Charsets.ISO_8859_1),
            engine.decrypt(envelope("r-enveloped-no-smime-type.eml"), readerKey, reader),
        )
    }

    @Test fun `a detached signature inside an envelope verifies over the 8-bit entity it covers`() {
        val clear = engine.decrypt(envelope("m-signed-then-enveloped.eml"), readerKey, reader).toString(Charsets.ISO_8859_1)
        val inner = MimeParser.detectCrypto(clear)!!
        assertEquals(CryptoKind.SMIME_SIGNED, inner.kind)

        val signed = inner.signedEntityRaw!!.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
        val verified = engine.verifyDetached(signed, inner.signatureDer!!, "alice@example.test")

        assertArrayEquals(SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1), signed)
        assertEquals("self-signed, so valid and not confirmed", PgpSignatureState.VALID_UNCONFIRMED, verified.signature)
        assertEquals("alice@example.test", verified.signer)
    }

    @Test fun `an opaque signature inside an envelope verifies and hands back what it carries`() {
        val clear = engine.decrypt(envelope("n-opaque-signed-then-enveloped.eml"), readerKey, reader).toString(Charsets.ISO_8859_1)
        val inner = MimeParser.detectCrypto(clear)!!
        assertEquals(CryptoKind.SMIME_OPAQUE_SIGNED, inner.kind)

        val verified = engine.verifyOpaque(inner.signedDataDer!!, "alice@example.test")

        assertEquals(PgpSignatureState.VALID_UNCONFIRMED, verified.signature)
        assertArrayEquals(SIGNED_ENTITY.toByteArray(Charsets.ISO_8859_1), verified.content)
    }

    @Test fun `decrypting registers no security provider`() {
        val before = Security.getProviders().map { it.name }
        engine.decrypt(envelope("g-enveloped.eml"), readerKey, reader)
        assertEquals(before, Security.getProviders().map { it.name })
    }

    private companion object {
        val NOTE_BIN = byteArrayOf(0x00, 0xFF.toByte(), 0x80.toByte(), 0xE9.toByte(), 0x0D, 0x0A, 0x41)

        val MIXED_ENTITY = "Content-Type: multipart/mixed; boundary=\"INNER\"\r\n\r\n" +
            "--INNER\r\nContent-Type: text/plain; charset=ISO-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
            "Bonjour, café crème à 8 bits.\r\nSecond line.\r\n" +
            "--INNER\r\nContent-Type: application/octet-stream; name=\"note.bin\"\r\n" +
            "Content-Disposition: attachment; filename=\"note.bin\"\r\nContent-Transfer-Encoding: base64\r\n\r\n" +
            Base64.getEncoder().encodeToString(NOTE_BIN) + "\r\n--INNER--\r\n"

        const val SIGNED_ENTITY = "Content-Type: text/plain; charset=ISO-8859-1\r\n" +
            "Content-Transfer-Encoding: 8bit\r\n\r\n" +
            "Bonjour, café crème à 8 bits.\r\nSecond line.\r\n"
    }
}

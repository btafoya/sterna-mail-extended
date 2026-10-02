package app.sterna.pgp

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.SmimeMime
import java.io.File
import java.nio.file.Files
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.smime.SMIMEAttributes
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * [BouncyCastleSmimeEngine.sign], read back by the engine's own verification, by Bouncy Castle's parser, and by
 * `openssl cms`. The signing key is a [KeystoreOnlyKey], which only [KeystoreOnlyProvider] can use: a key from
 * Android's keystore is the one thing a JVM cannot hold, and a device is the only place it runs.
 */
class BouncyCastleSmimeSignTest {

    private val authority = SmimeTestAuthority()
    private val aliceKeys = authority.rsa()
    private val alice = authority.leaf("CN=Alice", aliceKeys, address = "alice@example.test")
    private val signingTime = Date(System.currentTimeMillis() / 1000 * 1000)
    private val engine = BouncyCastleSmimeEngine(trustAnchors = { setOf(authority.root) }, now = { signingTime })

    private val payload = "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nBonjour.\r\nSecond line."
    private val octets = payload.toByteArray(Charsets.UTF_8)

    private fun opaqueKey() = KeystoreOnlyKey(aliceKeys.private)

    private fun signed(key: KeystoreOnlyKey = opaqueKey(), chain: List<X509Certificate> = listOf(alice, authority.intermediate, authority.root)) =
        withKeystoreProvider { engine.sign(octets, SmimeKey(key, chain)) }

    @Test fun `what it signs is confirmed by the verification a reader runs, from the From address`() {
        val verified = engine.verifyDetached(octets, signed(), "alice@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertEquals("alice@example.test", verified.signer)
        assertNull("a detached signature carries no content", verified.content)
        assertEquals(
            "one octet changed after signing",
            PgpSignatureState.INVALID,
            engine.verifyDetached(payload.replace("Bonjour", "Bonsoir").toByteArray(), signed(), "alice@example.test").signature,
        )
    }

    @Test fun `the key is handed to its own provider and never read out`() {
        val key = opaqueKey()
        signed(key)
        assertEquals("getEncoded was called on the private key", 0, key.readOut)
    }

    @Test fun `the message carries the signer and the intermediate, never the root`() {
        val carried = CMSSignedData(CMSProcessableByteArray(octets), signed()).certificates.getMatches(null).map { it.encoded.toList() }
        assertEquals(
            "the certificates the signature carries",
            setOf(alice.encoded.toList(), authority.intermediate.encoded.toList()),
            carried.toSet(),
        )
        assertEquals("each carried once", 2, carried.size)
    }

    @Test fun `a chain Android gives without its root is carried whole`() {
        val carried = CMSSignedData(CMSProcessableByteArray(octets), signed(chain = listOf(alice, authority.intermediate)))
            .certificates.getMatches(null).map { it.encoded.toList() }.toSet()
        assertEquals(setOf(alice.encoded.toList(), authority.intermediate.encoded.toList()), carried)
    }

    @Test fun `a self-signed signer carries its own certificate, the only way to its key`() {
        val carolKeys = authority.rsa()
        val carol = authority.selfSigned("CN=Carol", carolKeys, "carol@example.test")
        val signature = withKeystoreProvider { engine.sign(octets, SmimeKey(KeystoreOnlyKey(carolKeys.private), listOf(carol))) }
        val carried = CMSSignedData(CMSProcessableByteArray(octets), signature).certificates.getMatches(null).map { it.encoded.toList() }
        assertEquals(listOf(carol.encoded.toList()), carried)
        assertEquals(
            "valid but vouched for by no authority, and not a missing key",
            PgpSignatureState.VALID_UNCONFIRMED,
            engine.verifyDetached(octets, signature, "carol@example.test").signature,
        )
    }

    @Test fun `SHA-256 with RSA, with the signing time and the signer's own certificate to encrypt to`() {
        val signer = CMSSignedData(CMSProcessableByteArray(octets), signed()).signerInfos.signers.single()
        assertEquals(NISTObjectIdentifiers.id_sha256.id, signer.digestAlgOID)
        assertEquals(PKCSObjectIdentifiers.sha256WithRSAEncryption.id, signer.encryptionAlgOID)
        val time = signer.signedAttributes.get(CMSAttributes.signingTime)
        assertEquals(signingTime, Time.getInstance(time.attrValues.getObjectAt(0)).date)
        val preference = signer.signedAttributes.get(SMIMEAttributes.encrypKeyPref)
        val named = IssuerAndSerialNumber.getInstance(
            ASN1Sequence.getInstance(ASN1TaggedObject.getInstance(preference.attrValues.getObjectAt(0)), false),
        )
        assertEquals(X500Name.getInstance(alice.issuerX500Principal.encoded), named.name)
        assertEquals(alice.serialNumber, named.serialNumber.value)
        assertArrayEquals(
            "the reader keeps the signer's own certificate to encrypt back with",
            alice.encoded,
            engine.verifyDetached(octets, signed(), "alice@example.test").encryptionCertificate,
        )
    }

    @Test fun `signing registers no security provider`() {
        withKeystoreProvider {
            val before = Security.getProviders().map { it.name }
            engine.sign(octets, SmimeKey(opaqueKey(), listOf(alice, authority.intermediate)))
            assertEquals(before, Security.getProviders().map { it.name })
        }
    }

    @Test fun `the wrapped message is read back by the parser as the signed entity and its signature`() {
        val signature = signed()
        val message = "MIME-Version: 1.0\r\n" + SmimeMime.wrapSigned(payload, signature, "----sterna_smime_test")
        val envelope = MimeParser.detectCrypto(message)!!
        assertEquals(CryptoKind.SMIME_SIGNED, envelope.kind)
        assertEquals(payload, envelope.signedEntityRaw)
        assertArrayEquals(signature, envelope.signatureDer)
    }

    @Test fun `openssl verifies the message as it is sent, and refuses it changed`() {
        assumeTrue("openssl is not on this machine", runCatching { openssl("version").first == 0 }.getOrDefault(false))
        val dir = Files.createTempDirectory("smime-sign").toFile()
        try {
            val ca = File(dir, "root.pem").apply { writeText(pem(authority.root)) }
            val message = "MIME-Version: 1.0\r\n" + SmimeMime.wrapSigned(payload, signed(), "----sterna_smime_test")
            val sent = File(dir, "sent.eml").apply { writeBytes(message.toByteArray(Charsets.UTF_8)) }
            val out = File(dir, "out.txt")
            val (code, said) = openssl("cms", "-verify", "-in", sent.path, "-CAfile", ca.path, "-out", out.path)
            assertEquals("openssl cms -verify: $said", 0, code)
            assertArrayEquals("openssl hands back the entity signed", octets, out.readBytes())
            val changed = File(dir, "changed.eml").apply { writeBytes(message.replace("Bonjour", "Bonsoir").toByteArray(Charsets.UTF_8)) }
            assertTrue("a changed body verifies", openssl("cms", "-verify", "-in", changed.path, "-CAfile", ca.path, "-out", out.path).first != 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun pem(cert: X509Certificate) =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

    private fun openssl(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("openssl") + args).redirectErrorStream(true).start()
        val said = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(30, TimeUnit.SECONDS)) { "openssl did not finish" }
        return process.exitValue() to said
    }
}

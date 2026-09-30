package app.sterna.pgp

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeVerified
import app.sterna.core.imap.CryptoEnvelope
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.MimeParser
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [BouncyCastleSmimeEngine] run on messages `openssl cms` signed with a throwaway authority, `ca.pem`, whose
 * certificates last a hundred years; `dave`'s alone was issued for 2020 only, and signed with in 2026:
 *
 * - `a-detached`: alice, issued by the authority, From alice, an 8-bit ISO-8859-1 body.
 * - `b-tampered`: `a-detached` with one letter of the signed body changed.
 * - `c-selfsigned`: carol's own certificate, From carol.
 * - `d-mismatch`: bob, issued by the authority, From alice.
 * - `e-opaque`: alice's `signed-data` carrying the entity. `f-expired`: dave, From dave.
 * - `x-legacy`: `a-detached` whose signature is typed `application/x-pkcs7-signature`.
 * - `h-signed-in-2020`: dave, whose certificate was valid then, with a `signingTime` of 2020-06-01 that Bouncy
 *   Castle wrote, `openssl cms` having no way to set one.
 * - `i-tls-only`: erin, issued by the authority for TLS server and client use only.
 * - `j-no-digital-signature`: kim, e-mail protection but a key usage without `digitalSignature`.
 * - `k-san-and-dn`: frank, `frank@example.test` in the subject alternative name, `frank.dn@example.test` in the DN.
 * - `l-dn-only`: grace, whose address is only in the DN, as `emailAddress`.
 * Every other leaf carries its address in both, the subject alternative name as rfc822.
 *
 * The signed octets are handed over as `MailRepository` hands them, CRLF: that preparation is the
 * interface's input, and `SmimeReadPathTest` pins that the repository makes it.
 */
class BouncyCastleSmimeEngineTest {

    private val authority: X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/smime/ca.pem")!!) as X509Certificate

    private val engine = BouncyCastleSmimeEngine(trustAnchors = { setOf(authority) })

    private fun envelope(name: String): CryptoEnvelope {
        val raw = javaClass.getResourceAsStream("/smime/$name")!!.readBytes().toString(Charsets.ISO_8859_1)
        return MimeParser.detectCrypto(raw) ?: error("$name holds no S/MIME signature")
    }

    private fun signedOctets(envelope: CryptoEnvelope): ByteArray =
        envelope.signedEntityRaw!!.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)

    private fun detached(name: String, from: String?, on: BouncyCastleSmimeEngine = engine): SmimeVerified {
        val envelope = envelope(name)
        assertEquals(name, CryptoKind.SMIME_SIGNED, envelope.kind)
        return on.verifyDetached(signedOctets(envelope), envelope.signatureDer!!, from)
    }

    @Test fun `a signature from a certificate an authority issued, sent from its address, is confirmed`() {
        val verified = detached("a-detached.eml", "alice@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertEquals("alice@example.test", verified.signer)
        assertNull("a detached signature carries no content", verified.content)
        assertEquals(
            "the address is compared without case",
            PgpSignatureState.VALID_CONFIRMED,
            detached("a-detached.eml", "Alice@Example.TEST").signature,
        )
    }

    @Test fun `the same signature with no authority to chain to is valid, not confirmed`() {
        val alone = BouncyCastleSmimeEngine(trustAnchors = { emptySet() })
        assertEquals(PgpSignatureState.VALID_UNCONFIRMED, detached("a-detached.eml", "alice@example.test", alone).signature)
        val broken = BouncyCastleSmimeEngine(trustAnchors = { error("no store") })
        assertEquals(PgpSignatureState.VALID_UNCONFIRMED, detached("a-detached.eml", "alice@example.test", broken).signature)
    }

    @Test fun `a signed body changed after signing is invalid`() {
        assertEquals(PgpSignatureState.INVALID, detached("b-tampered.eml", "alice@example.test").signature)
    }

    @Test fun `the 8-bit octets are what was signed, and their UTF-8 reading is not`() {
        val envelope = envelope("a-detached.eml")
        val octets = signedOctets(envelope)
        assertEquals("the fixture's body really is 8-bit", true, octets.any { it == 0xE9.toByte() })
        val utf8 = envelope.signedEntityRaw!!.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        assertEquals(
            PgpSignatureState.INVALID,
            engine.verifyDetached(utf8, envelope.signatureDer!!, "alice@example.test").signature,
        )
    }

    @Test fun `a self-signed certificate gives a valid signature that is not confirmed`() {
        val verified = detached("c-selfsigned.eml", "carol@example.test")
        assertEquals(PgpSignatureState.VALID_UNCONFIRMED, verified.signature)
        assertEquals("carol@example.test", verified.signer)
    }

    @Test fun `a certificate for another address than From is a sender mismatch, even from an authority`() {
        val verified = detached("d-mismatch.eml", "alice@example.test")
        assertEquals(PgpSignatureState.SENDER_MISMATCH, verified.signature)
        assertEquals("the signer named is the certificate's", "bob@example.test", verified.signer)
        assertEquals("no From at all confirms nobody", PgpSignatureState.SENDER_MISMATCH, detached("a-detached.eml", null).signature)
    }

    @Test fun `a certificate expired when the message was signed gives an expired verdict`() {
        assertEquals(PgpSignatureState.KEY_EXPIRED, detached("f-expired.eml", "dave@example.test").signature)
    }

    @Test fun `validity is judged at the signing time the message states, not at the time it is read`() {
        val later = BouncyCastleSmimeEngine(trustAnchors = { setOf(authority) }, now = { Date(7_258_118_400_000L) })
        assertEquals(
            "read in 2200, a certificate valid when it signed in 2026 still confirms",
            PgpSignatureState.VALID_CONFIRMED,
            detached("a-detached.eml", "alice@example.test", later).signature,
        )
    }

    @Test fun `the chain is judged at the signing time, when a certificate valid then has expired since`() {
        assertEquals(
            "signed in 2020 by a certificate for 2020: its chain held then, whatever it does now",
            PgpSignatureState.VALID_CONFIRMED,
            detached("h-signed-in-2020.eml", "dave@example.test").signature,
        )
    }

    @Test fun `a certificate an authority issued for something else than mail is never confirmed`() {
        val tls = detached("i-tls-only.eml", "erin@example.test")
        assertEquals("TLS server and client use only", PgpSignatureState.VALID_UNCONFIRMED, tls.signature)
        assertEquals("erin@example.test", tls.signer)
        assertEquals(
            "a key usage without digitalSignature",
            PgpSignatureState.VALID_UNCONFIRMED,
            detached("j-no-digital-signature.eml", "kim@example.test").signature,
        )
    }

    @Test fun `the subject alternative name is the address, before the DN`() {
        val verified = detached("k-san-and-dn.eml", "frank@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertEquals("frank@example.test", verified.signer)
        assertEquals(
            "the DN's address does not stand beside the alternative name's",
            PgpSignatureState.SENDER_MISMATCH,
            detached("k-san-and-dn.eml", "frank.dn@example.test").signature,
        )
    }

    @Test fun `with no subject alternative name the DN's address is the certificate's`() {
        val verified = detached("l-dn-only.eml", "grace@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertEquals("grace@example.test", verified.signer)
    }

    @Test fun `the pre-standard x-pkcs7-signature is checked the same way`() {
        assertEquals(PgpSignatureState.VALID_CONFIRMED, detached("x-legacy.eml", "alice@example.test").signature)
    }

    @Test fun `an opaque signed message hands back the entity it carries, confirmed`() {
        val envelope = envelope("e-opaque.eml")
        assertEquals(CryptoKind.SMIME_OPAQUE_SIGNED, envelope.kind)
        val verified = engine.verifyOpaque(envelope.signedDataDer!!, "alice@example.test")
        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertEquals("alice@example.test", verified.signer)
        assertArrayEquals(
            (
                "Content-Type: text/plain; charset=ISO-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" +
                    "Bonjour, café crème à 8 bits.\r\nSecond line.\r\n"
                ).toByteArray(Charsets.ISO_8859_1),
            verified.content,
        )
    }

    @Test fun `an unreadable signature is invalid, never thrown`() {
        val envelope = envelope("a-detached.eml")
        val junk = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x00)
        assertEquals(PgpSignatureState.INVALID, engine.verifyDetached(signedOctets(envelope), junk, "alice@example.test").signature)
        val opaque = engine.verifyOpaque(junk, "alice@example.test")
        assertEquals(PgpSignatureState.INVALID, opaque.signature)
        assertNull(opaque.content)
    }

    @Test fun `checking a signature registers no security provider`() {
        val before = Security.getProviders().map { it.name }
        detached("a-detached.eml", "alice@example.test")
        engine.verifyOpaque(envelope("e-opaque.eml").signedDataDer!!, "alice@example.test")
        assertEquals(before, Security.getProviders().map { it.name })
    }
}

package app.sterna.pgp

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeVerified
import app.sterna.core.imap.MimeParser
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.smime.SMIMEAttributes
import org.bouncycastle.asn1.smime.SMIMEEncryptionKeyPreferenceAttribute
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SmimeVerified.encryptionCertificate], the certificate [BouncyCastleSmimeEngine] says to encrypt to the sender
 * with. The `openssl cms` fixtures of [BouncyCastleSmimeEngineTest] stand for what a mail client sends; the cases
 * `openssl cms` cannot write (an `SMIMEEncryptionKeyPreference`, a signing-only key usage) are signed here by Bouncy
 * Castle, under an authority made for the run, whose key is never kept.
 */
class BouncyCastleSmimeEncryptionCertificateTest {

    private val bc = BouncyCastleProvider()

    private fun fixtureAuthority(): X509Certificate = CertificateFactory.getInstance("X.509")
        .generateCertificate(javaClass.getResourceAsStream("/smime/ca.pem")!!) as X509Certificate

    private fun detached(name: String, from: String, engine: BouncyCastleSmimeEngine): SmimeVerified {
        val raw = javaClass.getResourceAsStream("/smime/$name")!!.readBytes().toString(Charsets.ISO_8859_1)
        val envelope = MimeParser.detectCrypto(raw)!!
        val octets = envelope.signedEntityRaw!!.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)
        return engine.verifyDetached(octets, envelope.signatureDer!!, from)
    }

    /** The signer's certificate as the signature part carries it. */
    private fun signerCertificateOf(name: String): ByteArray {
        val raw = javaClass.getResourceAsStream("/smime/$name")!!.readBytes().toString(Charsets.ISO_8859_1)
        val cms = CMSSignedData(MimeParser.detectCrypto(raw)!!.signatureDer!!)
        val sid = cms.signerInfos.signers.single().sid
        return cms.certificates.getMatches(null).single { sid.match(it) }.encoded
    }

    // --- an authority of this run, and certificates it issues -----------------------------------------------

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val authorityKeys = rsa()
    private val authorityName = X500Name("CN=Run CA, O=Sterna tests")
    private var serial = 100L
    private val extensions = JcaX509ExtensionUtils()

    private fun issue(
        subject: X500Name,
        publicKey: PublicKey,
        issuer: X500Name,
        issuerKey: PrivateKey,
        authority: Boolean = false,
        address: String? = null,
        usage: Int? = null,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(serial++),
            Date(System.currentTimeMillis() - DAY),
            Date(System.currentTimeMillis() + 3650 * DAY),
            subject,
            publicKey,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(authority))
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(publicKey))
        if (authority) {
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        } else {
            usage?.let { builder.addExtension(Extension.keyUsage, true, KeyUsage(it)) }
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_emailProtection))
            address?.let { builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.rfc822Name, it))) }
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(issuerKey)
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
    }

    private val authority = issue(authorityName, authorityKeys.public, authorityName, authorityKeys.private, authority = true)

    private fun leaf(name: String, address: String, keys: KeyPair = rsa(), usage: Int? = SIGN_AND_ENCIPHER) =
        issue(X500Name("CN=$name"), keys.public, authorityName, authorityKeys.private, address = address, usage = usage)

    private val engine = BouncyCastleSmimeEngine(trustAnchors = { setOf(authority) })

    private val content = "Content-Type: text/plain\r\n\r\nHello.\r\n".toByteArray(Charsets.ISO_8859_1)

    /** A detached signature over [content] by [signerKey], carrying [carried] and, if any, the [preference]. */
    private fun sign(
        signerKey: PrivateKey,
        signer: X509Certificate,
        carried: List<X509Certificate>,
        preference: Attribute? = null,
    ): ByteArray {
        val attributes = ASN1EncodableVector().apply { preference?.let { add(it) } }
        val info = JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(bc).build())
            .setSignedAttributeGenerator(DefaultSignedAttributeTableGenerator(AttributeTable(attributes)))
            .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(signerKey), signer)
        return CMSSignedDataGenerator().run {
            addSignerInfoGenerator(info)
            addCertificates(JcaCertStore(carried))
            generate(CMSProcessableByteArray(content), false).encoded
        }
    }

    /** [sign] with every signed attribute but `signingTime`. */
    private fun signWithoutSigningTime(signerKey: PrivateKey, signer: X509Certificate): ByteArray {
        val info = JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(bc).build())
            .setSignedAttributeGenerator { parameters -> DefaultSignedAttributeTableGenerator().getAttributes(parameters).remove(CMSAttributes.signingTime) }
            .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(signerKey), signer)
        return CMSSignedDataGenerator().run {
            addSignerInfoGenerator(info)
            addCertificates(JcaCertStore(listOf(signer)))
            generate(CMSProcessableByteArray(content), false).encoded
        }
    }

    @Test fun `a signature that states no signing time gives none`() {
        val keys = rsa()
        val sam = leaf("Sam", "sam@example.test", keys)

        val verified = engine.verifyDetached(content, signWithoutSigningTime(keys.private, sam), "sam@example.test")

        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertNull(verified.signedAt)
        assertNotNull("a stated one comes through", engine.verifyDetached(content, sign(keys.private, sam, listOf(sam)), "sam@example.test").signedAt)
    }

    private fun byIssuerAndSerial(cert: X509Certificate) = SMIMEEncryptionKeyPreferenceAttribute(
        IssuerAndSerialNumber(X500Name.getInstance(cert.issuerX500Principal.encoded), cert.serialNumber),
    )

    private fun bySubjectKeyId(cert: X509Certificate) = Attribute(
        SMIMEAttributes.encrypKeyPref,
        DERSet(DERTaggedObject(false, 2, DEROctetString(extensions.createSubjectKeyIdentifier(cert.publicKey).keyIdentifier))),
    )

    // --- the fixtures `openssl cms` wrote -------------------------------------------------------------------

    @Test fun `a confirmed signature gives the signer's certificate to encrypt to`() {
        val verified = detached("a-detached.eml", "alice@example.test", BouncyCastleSmimeEngine(trustAnchors = { setOf(fixtureAuthority()) }))

        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertArrayEquals(signerCertificateOf("a-detached.eml"), verified.encryptionCertificate)
    }

    @Test fun `no certificate comes from a signature that is not confirmed`() {
        val trusting = BouncyCastleSmimeEngine(trustAnchors = { setOf(fixtureAuthority()) })
        val alone = BouncyCastleSmimeEngine(trustAnchors = { emptySet() })

        mapOf(
            "self-signed, in carol's name" to detached("c-selfsigned.eml", "carol@example.test", trusting),
            "alice's, with no authority to chain to" to detached("a-detached.eml", "alice@example.test", alone),
            "bob's, From alice" to detached("d-mismatch.eml", "alice@example.test", trusting),
            "expired when it signed" to detached("f-expired.eml", "dave@example.test", trusting),
            "issued for TLS only" to detached("i-tls-only.eml", "erin@example.test", trusting),
            "tampered" to detached("b-tampered.eml", "alice@example.test", trusting),
        ).forEach { (case, verified) ->
            assertEquals("$case: verdict", false, verified.signature == PgpSignatureState.VALID_CONFIRMED)
            assertNull("$case: ${verified.signature} gave a certificate", verified.encryptionCertificate)
        }
    }

    @Test fun `the verdict carries the signing time the signature states`() {
        val verified = detached("h-signed-in-2020.eml", "dave@example.test", BouncyCastleSmimeEngine(trustAnchors = { setOf(fixtureAuthority()) }))

        val day = java.time.Instant.parse("2020-06-01T00:00:00Z").toEpochMilli()
        assertTrue("${verified.signedAt}", verified.signedAt != null && verified.signedAt!! in day until day + DAY)
    }

    // --- signed here ----------------------------------------------------------------------------------------

    @Test fun `the certificate the signed preference names is the one to encrypt to, by issuer and serial or by key id`() {
        val signerKeys = rsa()
        val signer = leaf("Sam signing", "sam@example.test", signerKeys, usage = KeyUsage.digitalSignature)
        val encryption = leaf("Sam encryption", "sam@example.test", usage = KeyUsage.keyEncipherment)

        listOf(byIssuerAndSerial(encryption), bySubjectKeyId(encryption)).forEach { preference ->
            val signature = sign(signerKeys.private, signer, listOf(signer, encryption), preference)
            val verified = engine.verifyDetached(content, signature, "sam@example.test")

            assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
            assertArrayEquals("${preference.attrValues}", encryption.encoded, verified.encryptionCertificate)
        }
    }

    @Test fun `a signer's certificate that may not encipher keys gives none, though the signature is confirmed`() {
        val keys = rsa()
        val signingOnly = leaf("Sam", "sam@example.test", keys, usage = KeyUsage.digitalSignature)

        val verified = engine.verifyDetached(content, sign(keys.private, signingOnly, listOf(signingOnly)), "sam@example.test")

        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertNull(verified.encryptionCertificate)
        val allowed = leaf("Sam", "sam@example.test", keys)
        assertArrayEquals(
            "the same key, allowed to encipher, gives its certificate",
            allowed.encoded,
            engine.verifyDetached(content, sign(keys.private, allowed, listOf(allowed)), "sam@example.test").encryptionCertificate,
        )
    }

    @Test fun `a certificate with no key usage at all may be encrypted to`() {
        val keys = rsa()
        val unrestricted = leaf("Sue", "sue@example.test", keys, usage = null)

        val verified = engine.verifyDetached(content, sign(keys.private, unrestricted, listOf(unrestricted)), "sue@example.test")

        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertArrayEquals(unrestricted.encoded, verified.encryptionCertificate)
    }

    @Test fun `a preferred certificate that fails a check gives none, and the signer's is no fallback`() {
        val signerKeys = rsa()
        val signer = leaf("Sam", "sam@example.test", signerKeys)
        val elsewhereKeys = rsa()
        val elsewhereName = X500Name("CN=Other CA")
        val elsewhere = issue(elsewhereName, elsewhereKeys.public, elsewhereName, elsewhereKeys.private, authority = true)
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

        mapOf(
            "may not encipher keys" to leaf("Sam signing", "sam@example.test", usage = KeyUsage.digitalSignature),
            "issued for another address" to leaf("Sam", "someone.else@example.test"),
            "from an authority not trusted" to issue(
                X500Name("CN=Sam"), rsa().public, elsewhereName, elsewhereKeys.private, address = "sam@example.test", usage = SIGN_AND_ENCIPHER,
            ),
            "not RSA" to leaf("Sam", "sam@example.test", ec, usage = KeyUsage.keyEncipherment),
        ).forEach { (case, preferred) ->
            val carried = listOf(signer, preferred, elsewhere)
            val verified = engine.verifyDetached(content, sign(signerKeys.private, signer, carried, byIssuerAndSerial(preferred)), "sam@example.test")

            assertEquals("$case: the signature itself", PgpSignatureState.VALID_CONFIRMED, verified.signature)
            assertNull("$case: a certificate was given", verified.encryptionCertificate)
        }

        val absent = leaf("Sam", "sam@example.test")
        val verified = engine.verifyDetached(content, sign(signerKeys.private, signer, listOf(signer), byIssuerAndSerial(absent)), "sam@example.test")
        assertNull("preferred, and not in the message", verified.encryptionCertificate)
    }

    @Test fun `the certificate is the From address's when the signer's lists several`() {
        val keys = rsa()
        val builder = JcaX509v3CertificateBuilder(
            authorityName, BigInteger.valueOf(serial++), Date(System.currentTimeMillis() - DAY),
            Date(System.currentTimeMillis() + 3650 * DAY), X500Name("CN=Pat"), keys.public,
        ).apply {
            addExtension(Extension.keyUsage, true, KeyUsage(SIGN_AND_ENCIPHER))
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(arrayOf(GeneralName(GeneralName.rfc822Name, "pat@example.test"), GeneralName(GeneralName.rfc822Name, "p@example.test"))),
            )
        }
        val pat = JcaX509CertificateConverter().setProvider(bc)
            .getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(authorityKeys.private)))

        val verified = engine.verifyDetached(content, sign(keys.private, pat, listOf(pat)), "P@example.test")

        assertEquals(PgpSignatureState.VALID_CONFIRMED, verified.signature)
        assertArrayEquals(pat.encoded, verified.encryptionCertificate)
    }

    private companion object {
        const val DAY = 86_400_000L
        val SIGN_AND_ENCIPHER = KeyUsage.digitalSignature or KeyUsage.keyEncipherment
    }
}

package app.sterna.pgp

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeVerified
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DERSet
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.cms.RecipientKeyIdentifier
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.smime.SMIMEAttributes
import org.bouncycastle.asn1.smime.SMIMEEncryptionKeyPreferenceAttribute
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.KeyTransRecipientInformation
import org.bouncycastle.cms.RecipientInformation
import org.bouncycastle.cms.SignerId
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cms.jcajce.JceCMSContentEncryptorBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
import org.bouncycastle.cms.jcajce.JceKeyTransRecipientInfoGenerator
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder

/**
 * [SmimeEngine] on Bouncy Castle. The certificate chain is validated against [trustAnchors] at the time
 * the message says it was signed (its `signingTime`, else [now]), with no revocation check: none is
 * reachable offline, and the verdicts it would add are not ones the reader shows.
 */
class BouncyCastleSmimeEngine(
    private val trustAnchors: () -> Set<X509Certificate>,
    private val now: () -> Date = { Date() },
) : SmimeEngine {

    // Handed to each builder, never registered with Security.addProvider or insertProviderAt: Android
    // ships its own cut-down provider under the name "BC", and replacing it breaks the app's TLS.
    private val provider: Provider by lazy { BouncyCastleProvider() }

    override fun verifyDetached(signedBytes: ByteArray, signature: ByteArray, senderAddress: String?): SmimeVerified {
        val cms = try {
            CMSSignedData(CMSProcessableByteArray(signedBytes), signature)
        } catch (unreadable: Exception) {
            return SmimeVerified(PgpSignatureState.INVALID, null, null)
        }
        return verdict(cms, senderAddress, content = null)
    }

    override fun verifyOpaque(signedData: ByteArray, senderAddress: String?): SmimeVerified {
        val cms = try {
            CMSSignedData(signedData)
        } catch (unreadable: Exception) {
            return SmimeVerified(PgpSignatureState.INVALID, null, null)
        }
        val content = cms.signedContent?.content as? ByteArray
            ?: return SmimeVerified(PgpSignatureState.INVALID, null, null)
        return verdict(cms, senderAddress, content)
    }

    override fun namesRecipient(envelopedData: ByteArray, certificate: X509Certificate): Boolean =
        recipientFor(envelopedData, certificate) != null

    override fun decrypt(envelopedData: ByteArray, key: PrivateKey, certificate: X509Certificate): ByteArray {
        val recipient = recipientFor(envelopedData, certificate)
            ?: error("The message is not encrypted for this certificate")
        check(recipient is KeyTransRecipientInformation) {
            "Only RSA key transport is supported, not ${recipient.javaClass.simpleName}"
        }
        // No provider for the key: a key from the Android keystore cannot leave it, so only the keystore's own
        // provider, found by the JCA from the key, can unwrap with it. Bouncy Castle runs the content cipher.
        return recipient.getContent(JceKeyTransEnvelopedRecipient(key).setContentProvider(provider))
    }

    override fun sign(signedBytes: ByteArray, key: SmimeKey): ByteArray {
        val signer = key.chain.first()
        val attributes = ASN1EncodableVector().apply {
            add(Attribute(CMSAttributes.signingTime, DERSet(Time(now()))))
            add(SMIMEEncryptionKeyPreferenceAttribute(IssuerAndSerialNumber(X500Name.getInstance(signer.issuerX500Principal.encoded), signer.serialNumber)))
        }
        // No provider for the content signer: a keystore key cannot leave the keystore, and the JCA hands the RSA
        // operation to the provider the key belongs to. Bouncy Castle only digests and assembles.
        val contentSigner = JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(key.privateKey)
        val signerInfo = JcaSignerInfoGeneratorBuilder(JcaDigestCalculatorProviderBuilder().setProvider(provider).build())
            .setSignedAttributeGenerator(DefaultSignedAttributeTableGenerator(AttributeTable(attributes)))
            .build(contentSigner, signer)
        val generator = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(signerInfo)
            addCertificates(JcaCertStore(smimeCarriedChain(key.chain)))
        }
        return generator.generate(CMSProcessableByteArray(signedBytes), false).toASN1Structure().getEncoded(ASN1Encoding.DER)
    }

    override fun encrypt(content: ByteArray, recipients: List<X509Certificate>): ByteArray {
        require(recipients.isNotEmpty()) { "An envelope for nobody" }
        val generator = CMSEnvelopedDataGenerator()
        recipients.forEach { generator.addRecipientInfoGenerator(JceKeyTransRecipientInfoGenerator(it).setProvider(provider)) }
        val encryptor = JceCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).setProvider(provider).build()
        return generator.generate(CMSProcessableByteArray(content), encryptor).toASN1Structure().getEncoded(ASN1Encoding.DER)
    }

    private fun recipientFor(envelopedData: ByteArray, certificate: X509Certificate): RecipientInformation? {
        val holder = JcaX509CertificateHolder(certificate)
        return CMSEnvelopedData(envelopedData).recipientInfos.recipients.firstOrNull { it.rid.match(holder) }
    }

    private fun verdict(cms: CMSSignedData, senderAddress: String?, content: ByteArray?): SmimeVerified {
        val signer = cms.signerInfos.signers.firstOrNull()
            ?: return SmimeVerified(PgpSignatureState.INVALID, null, content)
        val carried = try {
            cms.certificates.getMatches(null).toList()
        } catch (unreadable: Exception) {
            return SmimeVerified(PgpSignatureState.INVALID, null, content)
        }
        val holder = carried.firstOrNull { signer.sid.match(it) }
            ?: return SmimeVerified(PgpSignatureState.KEY_MISSING, null, content)
        val converter = JcaX509CertificateConverter().setProvider(provider)
        val cert = try {
            converter.getCertificate(holder)
        } catch (unreadable: Exception) {
            return SmimeVerified(PgpSignatureState.INVALID, null, content)
        }
        val addresses = smimeAddressesOf(cert, holder.subject)
        val signerName = addresses.firstOrNull { it.equals(senderAddress, ignoreCase = true) }
            ?: addresses.firstOrNull()
            ?: holder.subject.toString()
        val signingTime = signingTimeOf(signer)
        fun answer(state: PgpSignatureState, encryptTo: ByteArray? = null) =
            SmimeVerified(state, signerName, content, encryptTo, signingTime?.time)

        val holds = runCatching { signer.verify(verifierOf(cert)) }.getOrDefault(false)
        if (!holds) return answer(PgpSignatureState.INVALID)
        val signedAt = signingTime ?: now()
        if (signedAt.after(cert.notAfter)) return answer(PgpSignatureState.KEY_EXPIRED)
        if (addresses.none { it.equals(senderAddress, ignoreCase = true) }) return answer(PgpSignatureState.SENDER_MISMATCH)
        if (!smimeSignsMail(cert)) return answer(PgpSignatureState.VALID_UNCONFIRMED)
        val chain = carried.mapNotNull { runCatching { converter.getCertificate(it) }.getOrNull() }
        if (!chainsToAnchor(cert, chain, signedAt)) return answer(PgpSignatureState.VALID_UNCONFIRMED)
        val encryptTo = runCatching { encryptionCertificate(signer, cert, carried, chain, senderAddress, signedAt) }.getOrNull()
        return answer(PgpSignatureState.VALID_CONFIRMED, encryptTo)
    }

    /**
     * The DER of the certificate to encrypt to the sender with, for a signature already confirmed from [signerCert]:
     * the one the signed `SMIMEEncryptionKeyPreference` names among [carried] when there is that attribute, else the
     * signer's. A preferred one is held to the signer's own trust, chaining to [trustAnchors] at [signedAt].
     */
    private fun encryptionCertificate(
        signer: SignerInformation,
        signerCert: X509Certificate,
        carried: List<X509CertificateHolder>,
        chain: List<X509Certificate>,
        senderAddress: String?,
        signedAt: Date,
    ): ByteArray? {
        val preference = signer.signedAttributes?.get(SMIMEAttributes.encrypKeyPref)
        val candidate = if (preference == null) {
            signerCert
        } else {
            val named = preferredIn(preference.attrValues.getObjectAt(0), carried) ?: return null
            val converted = JcaX509CertificateConverter().setProvider(provider).getCertificate(named)
            if (converted != signerCert && !chainsToAnchor(converted, chain, signedAt)) return null
            converted
        }
        if (!smimeEncryptsMail(candidate)) return null
        val addresses = smimeAddressesOf(candidate)
        if (addresses.none { it.equals(senderAddress, ignoreCase = true) }) return null
        return candidate.encoded
    }

    /** RFC 8551 section 2.5.3: the certificate [value] names, by issuer and serial or by subject key identifier. */
    private fun preferredIn(value: ASN1Encodable, carried: List<X509CertificateHolder>): X509CertificateHolder? {
        val choice = ASN1TaggedObject.getInstance(value)
        val wanted = when (choice.tagNo) {
            0 -> IssuerAndSerialNumber.getInstance(ASN1Sequence.getInstance(choice, false))
                .let { SignerId(it.name, it.serialNumber.value) }
            1 -> SignerId(RecipientKeyIdentifier.getInstance(choice, false).subjectKeyIdentifier.octets)
            2 -> SignerId(ASN1OctetString.getInstance(choice, false).octets)
            else -> return null
        }
        return carried.firstOrNull { wanted.match(it) }
    }

    // From the public key and not the certificate: given the certificate, Bouncy Castle refuses one that
    // had expired at signing time as though the signature were wrong, and an expired one has its own verdict.
    private fun verifierOf(cert: X509Certificate) =
        JcaSimpleSignerInfoVerifierBuilder().setProvider(provider).build(cert.publicKey)

    private fun signingTimeOf(signer: SignerInformation): Date? = runCatching {
        signer.signedAttributes?.get(CMSAttributes.signingTime)
            ?.let { Time.getInstance(it.attrValues.getObjectAt(0)).date }
    }.getOrNull()

    private fun chainsToAnchor(cert: X509Certificate, carried: List<X509Certificate>, at: Date): Boolean = try {
        val target = X509CertSelector().apply { certificate = cert }
        val params = PKIXBuilderParameters(trustAnchors().map { TrustAnchor(it, null) }.toSet(), target).apply {
            isRevocationEnabled = false
            date = at
            addCertStore(CertStore.getInstance("Collection", CollectionCertStoreParameters(carried), provider))
        }
        CertPathBuilder.getInstance("PKIX", provider).build(params)
        true
    } catch (unchained: Exception) {
        false
    }

    private companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
    }
}

/** `GeneralName.rfc822Name`, as `getSubjectAlternativeNames` numbers it. */
private const val RFC822_NAME = 1

private const val EMAIL_PROTECTION = "1.3.6.1.5.5.7.3.4"
private const val ANY_EXTENDED_KEY_USAGE = "2.5.29.37.0"

/**
 * RFC 8550 section 4.4: a certificate issued for another use, a TLS server say, vouches for no mail. A key
 * usage must allow `digitalSignature` or `nonRepudiation`; an extended key usage, e-mail protection or any.
 */
fun smimeSignsMail(cert: X509Certificate): Boolean = runCatching {
    val usage = cert.keyUsage
    val extended = cert.extendedKeyUsage
    (usage == null || usage.getOrElse(0) { false } || usage.getOrElse(1) { false }) &&
        (extended == null || EMAIL_PROTECTION in extended || ANY_EXTENDED_KEY_USAGE in extended)
}.getOrDefault(false)

/**
 * Whether mail may be encrypted to [cert]: RSA, the one key transport [BouncyCastleSmimeEngine.decrypt] reads; a key
 * usage, if any, allowing `keyEncipherment`; an extended key usage, if any, allowing e-mail protection. One rule for
 * a correspondent's certificate kept from a signature and for the sender's own, which every envelope also names.
 */
fun smimeEncryptsMail(cert: X509Certificate): Boolean = runCatching {
    val usage = cert.keyUsage
    val extended = cert.extendedKeyUsage
    cert.publicKey.algorithm == "RSA" &&
        (usage == null || usage.getOrElse(2) { false }) &&
        (extended == null || EMAIL_PROTECTION in extended || ANY_EXTENDED_KEY_USAGE in extended)
}.getOrDefault(false)

/**
 * The addresses [cert] is issued for: its subject alternative name's rfc822 addresses, else its subject's
 * `emailAddress`. The DN's address does not stand beside a subject alternative name's. One reading for the
 * signatures checked and for the From addresses this app signs for.
 */
fun smimeAddressesOf(
    cert: X509Certificate,
    subject: X500Name = X500Name.getInstance(cert.subjectX500Principal.encoded),
): List<String> {
    val alternative = runCatching {
        cert.subjectAlternativeNames.orEmpty()
            .filter { it.getOrNull(0) == RFC822_NAME }
            .mapNotNull { it.getOrNull(1) as? String }
    }.getOrDefault(emptyList())
    if (alternative.isNotEmpty()) return alternative
    return subject.getRDNs(BCStyle.EmailAddress).map { IETFUtils.valueToString(it.first.value) }
}

/**
 * The certificates a signature carries for [chain], the signer's first: all of it but a self-issued authority. A
 * root proves nothing a reader does not already trust, and the reader's own copy is the one that counts. The
 * signer's certificate stays even self-signed, being the only way to the key.
 */
fun smimeCarriedChain(chain: List<X509Certificate>): List<X509Certificate> =
    listOf(chain.first()) + chain.drop(1).filterNot { it.subjectX500Principal == it.issuerX500Principal }

/**
 * The authorities Android trusts for this user: the system's, less any the user turned off, and those
 * the user installed. Read at each check, so a certificate added in the settings counts at once.
 */
fun androidTrustedAuthorities(): Set<X509Certificate> {
    val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    return store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }.toSet()
}

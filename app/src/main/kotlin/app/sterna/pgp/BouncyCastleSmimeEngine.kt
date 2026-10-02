package app.sterna.pgp

import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.data.pgp.SmimeEngine
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
import org.bouncycastle.asn1.cms.CMSAttributes
import org.bouncycastle.asn1.cms.Time
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.KeyTransRecipientInformation
import org.bouncycastle.cms.RecipientInformation
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cms.jcajce.JceKeyTransEnvelopedRecipient
import org.bouncycastle.jce.provider.BouncyCastleProvider

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
        val addresses = addressesOf(cert, holder.subject)
        val signerName = addresses.firstOrNull { it.equals(senderAddress, ignoreCase = true) }
            ?: addresses.firstOrNull()
            ?: holder.subject.toString()
        fun answer(state: PgpSignatureState) = SmimeVerified(state, signerName, content)

        val holds = runCatching { signer.verify(verifierOf(cert)) }.getOrDefault(false)
        if (!holds) return answer(PgpSignatureState.INVALID)
        val signedAt = signingTimeOf(signer) ?: now()
        if (signedAt.after(cert.notAfter)) return answer(PgpSignatureState.KEY_EXPIRED)
        if (addresses.none { it.equals(senderAddress, ignoreCase = true) }) return answer(PgpSignatureState.SENDER_MISMATCH)
        if (!signsMail(cert)) return answer(PgpSignatureState.VALID_UNCONFIRMED)
        val chain = carried.mapNotNull { runCatching { converter.getCertificate(it) }.getOrNull() }
        return answer(if (chainsToAnchor(cert, chain, signedAt)) PgpSignatureState.VALID_CONFIRMED else PgpSignatureState.VALID_UNCONFIRMED)
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

    /**
     * RFC 8550 section 4.4: a certificate issued for another use, a TLS server say, vouches for no mail. A key
     * usage must allow `digitalSignature` or `nonRepudiation`; an extended key usage, e-mail protection or any.
     */
    private fun signsMail(cert: X509Certificate): Boolean = runCatching {
        val usage = cert.keyUsage
        val extended = cert.extendedKeyUsage
        (usage == null || usage.getOrElse(0) { false } || usage.getOrElse(1) { false }) &&
            (extended == null || EMAIL_PROTECTION in extended || ANY_EXTENDED_KEY_USAGE in extended)
    }.getOrDefault(false)

    /** The subject alternative name's rfc822 addresses, else the subject's `emailAddress`. */
    private fun addressesOf(cert: X509Certificate, subject: X500Name): List<String> {
        val alternative = runCatching {
            cert.subjectAlternativeNames.orEmpty()
                .filter { it.getOrNull(0) == RFC822_NAME }
                .mapNotNull { it.getOrNull(1) as? String }
        }.getOrDefault(emptyList())
        if (alternative.isNotEmpty()) return alternative
        return subject.getRDNs(BCStyle.EmailAddress).map { IETFUtils.valueToString(it.first.value) }
    }

    private companion object {
        /** `GeneralName.rfc822Name`, as `getSubjectAlternativeNames` numbers it. */
        const val RFC822_NAME = 1

        const val EMAIL_PROTECTION = "1.3.6.1.5.5.7.3.4"
        const val ANY_EXTENDED_KEY_USAGE = "2.5.29.37.0"
    }
}

/**
 * The authorities Android trusts for this user: the system's, less any the user turned off, and those
 * the user installed. Read at each check, so a certificate added in the settings counts at once.
 */
fun androidTrustedAuthorities(): Set<X509Certificate> {
    val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
    return store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }.toSet()
}

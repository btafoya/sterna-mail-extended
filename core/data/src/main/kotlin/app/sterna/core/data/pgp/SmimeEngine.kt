package app.sterna.core.data.pgp

import java.security.PrivateKey
import java.security.cert.X509Certificate

class SmimeVerified(
    val signature: PgpSignatureState,
    val signer: String?,
    val content: ByteArray?,
    /**
     * DER of the certificate to encrypt to the sender with, never set unless [signature] is
     * [PgpSignatureState.VALID_CONFIRMED]: a correspondent's certificate is only ever learnt from a signature
     * an authority vouches for, or a self-signed message in their name would have mail encrypted for someone else.
     */
    val encryptionCertificate: ByteArray? = null,
    /** The signed `signingTime`, in epoch millis, or null when the signature carries none. */
    val signedAt: Long? = null,
)

/**
 * S/MIME signature checks. [verifyDetached] takes the signed entity's canonical CRLF octets and the
 * DER of its `application/pkcs7-signature` part; [verifyOpaque] takes a whole `signed-data` DER and
 * hands back the entity it carries as [SmimeVerified.content] (null for a detached signature).
 * [SmimeVerified.signer] is the certificate's e-mail address, else its subject, as text.
 * [SmimeVerified.encryptionCertificate] is the one the signed `SMIMEEncryptionKeyPreference` names, else the
 * signer's; either must be RSA, allowed to encipher keys, issued for the `From` address, and chain as the
 * signer's does. A preference naming one that fails any of this gives none: the signer's is not a fallback.
 * Both answer on the spot: no user interaction, no `PendingIntent`. A signature that does not verify
 * is [PgpSignatureState.INVALID]; anything thrown is turned into the reader's status card by the caller.
 *
 * Implementations live outside the `core` modules, which carry no crypto dependency, and none may
 * register or replace a global security provider: Android ships its own cut-down "BC" provider, and
 * swapping it out breaks the app's TLS.
 */
interface SmimeEngine {
    fun verifyDetached(signedBytes: ByteArray, signature: ByteArray, senderAddress: String?): SmimeVerified

    fun verifyOpaque(signedData: ByteArray, senderAddress: String?): SmimeVerified

    /**
     * Whether [envelopedData] names [certificate] among its recipients, by issuer and serial number or by
     * subject key identifier. Throws on an envelope it cannot read: that is no answer about the certificate.
     */
    fun namesRecipient(envelopedData: ByteArray, certificate: X509Certificate): Boolean

    /**
     * The content [envelopedData] carries, opened with [key] as the recipient [certificate] names. [key] may
     * be held by a keystore that never lets it out, so it is only ever handed to its own provider, never read
     * through `getEncoded`. RSA key transport only: any other recipient, or a failure, throws.
     */
    fun decrypt(envelopedData: ByteArray, key: PrivateKey, certificate: X509Certificate): ByteArray

    /**
     * The DER of a detached `signed-data` over [signedBytes], the canonical CRLF octets of the entity that goes
     * out, by [key]: SHA-256 with RSA, signed attributes giving the signing time and an
     * `SMIMEEncryptionKeyPreference` for the signer's own certificate, carrying that certificate and its chain
     * without the root. Like [decrypt], the private key is only ever handed to its own provider. Throws when it
     * cannot sign: the caller sends nothing then.
     */
    fun sign(signedBytes: ByteArray, key: SmimeKey): ByteArray

    /**
     * The DER of an `enveloped-data` carrying [content]: AES-256-CBC, its key transported with RSA as `rsaEncryption`,
     * the one transport [decrypt] reads, to each of [recipients], each named by issuer and serial number. Only
     * public keys are used. Throws when it cannot encrypt to one of them: the caller sends nothing then.
     */
    fun encrypt(content: ByteArray, recipients: List<X509Certificate>): ByteArray
}

/** A private key and the chain of the certificate it belongs to, that certificate first. */
class SmimeKey(val privateKey: PrivateKey, val chain: List<X509Certificate>)

/**
 * The user's S/MIME keys, by the alias an account stores (`StoredAccount.smimeCertAlias`). Blocking: called off
 * the main thread. Null means no key can be used under [alias] now, and the user is asked to choose; a throw
 * means the store could not answer now. Neither erases the alias: Android's keystore answers a removed
 * certificate and one whose access was withdrawn alike, and the next choice replaces it anyway.
 */
fun interface SmimeKeySource {
    fun keyFor(alias: String): SmimeKey?
}

/**
 * The correspondents' S/MIME certificates, learnt from signatures [SmimeVerified.encryptionCertificate] came
 * with. One per address, whatever the account it arrived on: a certificate is a fact about an address.
 */
interface SmimeCertificates {
    /**
     * Keep [certificate] for [address], compared without case, unless one is kept already from a message ranked
     * [messageDate] or later. [messageDate], in epoch millis, is the earlier of the signed signing time and the
     * server's date for the message. The server's date alone is no rank: an old signed message sent again today
     * would bring its old certificate back. The signing time alone is none either: the sender's clock may run ahead.
     */
    suspend fun retain(address: String, certificate: ByteArray, messageDate: Long)

    /**
     * The DER of the certificate kept for each of [addresses] that is valid at [now] (epoch millis), keyed by the
     * address as the caller wrote it. An address with none, or with one expired or not yet valid, is absent.
     */
    suspend fun certificatesFor(addresses: Collection<String>, now: Long): Map<String, ByteArray>
}

/** Whether [certificate], as DER, is within its validity at [now]; one that does not parse is not. */
fun smimeCertificateValidAt(certificate: ByteArray, now: Long): Boolean = runCatching {
    val parsed = java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(certificate.inputStream()) as X509Certificate
    parsed.checkValidity(java.util.Date(now))
    true
}.getOrDefault(false)

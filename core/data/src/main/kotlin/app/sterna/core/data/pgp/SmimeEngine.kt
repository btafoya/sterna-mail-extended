package app.sterna.core.data.pgp

import java.security.PrivateKey
import java.security.cert.X509Certificate

class SmimeVerified(
    val signature: PgpSignatureState,
    val signer: String?,
    val content: ByteArray?,
)

/**
 * S/MIME signature checks. [verifyDetached] takes the signed entity's canonical CRLF octets and the
 * DER of its `application/pkcs7-signature` part; [verifyOpaque] takes a whole `signed-data` DER and
 * hands back the entity it carries as [SmimeVerified.content] (null for a detached signature).
 * [SmimeVerified.signer] is the certificate's e-mail address, else its subject, as text.
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

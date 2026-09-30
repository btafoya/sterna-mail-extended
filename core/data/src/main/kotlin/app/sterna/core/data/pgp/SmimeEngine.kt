package app.sterna.core.data.pgp

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
}

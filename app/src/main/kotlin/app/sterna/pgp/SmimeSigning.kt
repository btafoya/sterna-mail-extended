package app.sterna.pgp

import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.SmimeEngine
import app.sterna.core.data.pgp.SmimeKey
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.isSmime
import app.sterna.core.imap.SmimeMime
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

/** Why the account's S/MIME certificate cannot sign a message now. */
enum class SmimeRefusal {
    /** No alias chosen, or none the keystore lets this app use under it. */
    NO_CERTIFICATE,

    /** Expired, or not valid yet. */
    NOT_VALID_NOW,

    /** Not issued for the From address: the reader would be told the sender does not match. */
    OTHER_ADDRESS,

    /** Issued for another use than signing mail ([smimeSignsMail]). */
    NOT_FOR_SIGNING,

    /** The keystore did not answer in time. */
    KEYSTORE_UNANSWERED,

    /** Encrypting, with a certificate mail may not be encrypted to ([smimeEncryptsMail]): the Sent copy would be unreadable. */
    NOT_FOR_ENCRYPTING,

    /** Encrypting, and a recipient has no certificate kept, or only one that is no longer valid. */
    RECIPIENT_WITHOUT_CERTIFICATE,

    /** Encrypting with a Bcc recipient: the envelope names every recipient, so each would see the Bcc. */
    BCC_WHILE_ENCRYPTING,
}

sealed interface SmimeSigning {
    class Ready(val key: SmimeKey) : SmimeSigning

    data class Refused(val reason: SmimeRefusal) : SmimeSigning
}

class SmimeSendRefused(val reason: SmimeRefusal) : Exception("S/MIME refused to send: $reason")

/**
 * Whether the certificate under [alias] may sign for [fromAddress] at [now]: the keystore holds a key for it, the
 * certificate is valid, and [fromAddress] is one it is issued for ([smimeAddressesOf]). Blocking, the keystore is
 * asked: off the main thread. Never asks the user: an alias whose access was withdrawn is [SmimeRefusal.NO_CERTIFICATE].
 */
fun smimeSigning(alias: String, fromAddress: String, keys: SmimeKeySource, now: Date): SmimeSigning {
    if (alias.isEmpty()) return SmimeSigning.Refused(SmimeRefusal.NO_CERTIFICATE)
    val held = try {
        keys.keyFor(alias)
    } catch (unanswered: KeystoreUnanswered) {
        return SmimeSigning.Refused(SmimeRefusal.KEYSTORE_UNANSWERED)
    }
    val key = held ?: return SmimeSigning.Refused(SmimeRefusal.NO_CERTIFICATE)
    val certificate = key.chain.first()
    if (runCatching { certificate.checkValidity(now) }.isFailure) return SmimeSigning.Refused(SmimeRefusal.NOT_VALID_NOW)
    if (!smimeSignsMail(certificate)) return SmimeSigning.Refused(SmimeRefusal.NOT_FOR_SIGNING)
    val from = fromAddress.trim()
    if (smimeAddressesOf(certificate).none { it.equals(from, ignoreCase = true) }) {
        return SmimeSigning.Refused(SmimeRefusal.OTHER_ADDRESS)
    }
    return SmimeSigning.Ready(key)
}

/**
 * Every certificate an envelope for [recipients] is encrypted to: each recipient's, from [kept] (DER by address,
 * as `SmimeCertificates.certificatesFor` answers), then [own], so the copy in Sent can be opened. A Bcc
 * recipient refuses the message outright, before any certificate is looked at: the envelope lists whom it is
 * for, readable by every recipient. A recipient with no certificate, one that does not parse, is not valid at
 * [now] or may not be encrypted to ([smimeEncryptsMail]) refuses it too. A recipient who is [fromAddress] is
 * covered by [own], kept or not: [smimeSigning] has checked it is issued for that address.
 */
fun smimeEnvelopeRecipients(
    recipients: List<String>,
    bcc: List<String>,
    kept: Map<String, ByteArray>,
    own: X509Certificate,
    fromAddress: String,
    now: Date,
): List<X509Certificate> {
    if (bcc.isNotEmpty()) throw SmimeSendRefused(SmimeRefusal.BCC_WHILE_ENCRYPTING)
    require(recipients.isNotEmpty()) { "An envelope for no recipient but the sender" }
    if (!smimeEncryptsMail(own)) throw SmimeSendRefused(SmimeRefusal.NOT_FOR_ENCRYPTING)
    val theirs = recipients.map { address ->
        if (smimeIsSender(address, fromAddress)) return@map own
        val certificate = kept[address]?.let(::parsedCertificate)
            ?.takeIf { runCatching { it.checkValidity(now) }.isSuccess && smimeEncryptsMail(it) }
        certificate ?: throw SmimeSendRefused(SmimeRefusal.RECIPIENT_WITHOUT_CERTIFICATE)
    }
    return (theirs + own).distinct()
}

/** Whether [address] is [fromAddress], compared as a certificate's addresses are: trimmed, without case. */
internal fun smimeIsSender(address: String, fromAddress: String): Boolean =
    address.trim().equals(fromAddress.trim(), ignoreCase = true)

private fun parsedCertificate(der: ByteArray): X509Certificate? = runCatching {
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
}.getOrNull()

/**
 * The entity that replaces the body of a message sent in [mode], from [payload] as [app.sterna.core.imap.PgpMime.signablePayload]
 * made it: signed, and for [PgpMode.SMIME_ENCRYPT] that signed entity whole inside an envelope for
 * [smimeEnvelopeRecipients] of [recipients] (To and Cc) and [bcc]. Either that or a throw of [SmimeSendRefused],
 * every refusal coming before anything is signed: there is no path out of here that sends the payload unsigned,
 * or an encrypting mode's payload unencrypted. Blocking, as [smimeSigning].
 */
fun smimeEntity(
    payload: String,
    boundary: String,
    mode: PgpMode,
    alias: String,
    fromAddress: String,
    keys: SmimeKeySource,
    engine: SmimeEngine,
    now: Date,
    recipients: List<String>,
    bcc: List<String>,
    kept: Map<String, ByteArray>,
): String {
    require(mode.isSmime) { "smimeEntity called with $mode, which is not S/MIME" }
    val key = when (val signing = smimeSigning(alias, fromAddress, keys, now)) {
        is SmimeSigning.Ready -> signing.key
        is SmimeSigning.Refused -> throw SmimeSendRefused(signing.reason)
    }
    val envelopeFor = if (mode.encrypts) smimeEnvelopeRecipients(recipients, bcc, kept, key.chain.first(), fromAddress, now) else null
    val signature = engine.sign(payload.toByteArray(Charsets.UTF_8), key)
    val signed = SmimeMime.wrapSigned(payload, signature, boundary)
    if (envelopeFor == null) return signed
    return SmimeMime.wrapEnveloped(engine.encrypt(signed.toByteArray(Charsets.UTF_8), envelopeFor))
}

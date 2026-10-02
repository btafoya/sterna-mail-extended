package app.sterna.core.data.mail

import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.isSmime
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SMIME_SIGNATURE_TYPES

/** Where `MailRepository.decryptMessage` sends a message it has read. */
internal sealed interface CryptoRoute<out P, out S> {
    data class OpenPgp<P>(val engine: P) : CryptoRoute<P, Nothing>

    data class Smime<S>(val engine: S) : CryptoRoute<Nothing, S>

    data class SmimeEncrypted<S>(val engine: S) : CryptoRoute<Nothing, S>

    data object OpenPgpMissing : CryptoRoute<Nothing, Nothing>

    data object NothingFound : CryptoRoute<Nothing, Nothing>

    /**
     * An S/MIME message this build cannot check: shown as ordinary mail, with no seal and no card. A
     * missing S/MIME engine is the state of the app, not a fault of the message, and a card on every
     * signed mail would name something the user cannot fix.
     */
    data object Unverifiable : CryptoRoute<Nothing, Nothing>
}

/**
 * [detected] is what the raw source holds, [locked] what the opened message was locked as. The kind
 * picks the engine before the engine's absence is judged: asking "is OpenPGP there?" first is how an
 * S/MIME message would say "OpenPGP not available".
 */
internal fun <P : Any, S : Any> cryptoRoute(
    detected: CryptoKind?,
    locked: CryptoKind?,
    pgp: P?,
    smime: S?,
): CryptoRoute<P, S> {
    val kind = detected ?: locked
    if (kind?.isSmime == true) {
        if (detected == null || smime == null) return CryptoRoute.Unverifiable
        if (detected == CryptoKind.SMIME_ENCRYPTED) return CryptoRoute.SmimeEncrypted(smime)
        return CryptoRoute.Smime(smime)
    }
    if (pgp == null) return CryptoRoute.OpenPgpMissing
    if (detected == null) return CryptoRoute.NothingFound
    return CryptoRoute.OpenPgp(pgp)
}

/**
 * Answered before any fetch, on the kind the reader locked the message as: an OpenPGP message with no
 * OpenPGP app must not wait on the network, nor fail offline with a network error, to say so. A null
 * [locked] counts as OpenPGP, the only kind a message is locked as today.
 */
internal fun notAvailableBeforeFetch(locked: CryptoKind?, pgp: Any?): Boolean =
    pgp == null && locked?.isSmime != true

private val SMIME_OPAQUE_TYPES = setOf("application/pkcs7-mime", "application/x-pkcs7-mime")

/** What mail clients name these parts, plus the subtype IMAP names an unnamed one after. */
private val SMIME_SIGNATURE_NAMES = setOf("smime.p7s", "pkcs7-signature", "x-pkcs7-signature")

/**
 * The S/MIME kind an unlocked message shows signs of, read off its parts with no fetch: the reader checks
 * a signature over the body it shows only when this answers, so other mail costs no second download. A
 * `.p7m` or `.p7s` file attached under its own name, an invoice say, is a file and not the message's
 * signature. An opaque signed message has no text of its own; an encrypted one looks the same here, as only
 * the source's `smime-type` tells them apart: both answer the opaque kind, and the source read afterwards
 * picks the route.
 */
fun smimeSignalOf(email: Email): CryptoKind? {
    val parts = email.attachments.mapNotNull { part ->
        part.type?.lowercase()?.let { type -> type to part.name?.trim()?.lowercase() }
    }
    if (parts.any { (type, name) -> type in SMIME_SIGNATURE_TYPES && (name.isNullOrEmpty() || name in SMIME_SIGNATURE_NAMES) }) {
        return CryptoKind.SMIME_SIGNED
    }
    val textless = email.textContent().isNullOrBlank() && email.htmlContent().isNullOrBlank()
    if (textless && parts.any { (type, name) -> type in SMIME_OPAQUE_TYPES && (name.isNullOrEmpty() || name == "smime.p7m") }) {
        return CryptoKind.SMIME_OPAQUE_SIGNED
    }
    return null
}

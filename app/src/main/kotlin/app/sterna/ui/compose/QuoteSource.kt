package app.sterna.ui.compose

import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email

/**
 * The original the composer builds on, whether its body is the decrypted copy, and whether there is
 * [nothingToQuote]: the two are never both true.
 */
internal class QuoteSource(val email: Email, val fromDecrypted: Boolean, val nothingToQuote: Boolean)

/**
 * [fetched] with the body [decrypted] returns, for an opening that quotes. A forward never asks:
 * it carries the original as the server holds it, and would otherwise send the clear of an
 * encrypted message to a third party. With no decrypted copy, an original is not quoted at all
 * when [nothingReadable] says so: an attribution over ciphertext or over nothing announces a quote
 * the reply does not hold. Such a reply is an ordinary one, nothing decrypted is in its body: the
 * composer builds it on an empty quote, so it carries the signature of the identity it settled on,
 * says why no quote is there, and neither raises the fact nor sets the lock.
 */
internal fun quoteSource(opening: ComposeOpening, fetched: Email, kind: CryptoKind?, decrypted: () -> Email?): QuoteSource {
    val clear = if (opening.quotes) decrypted() else null
    val unreadable = opening.quotes && clear == null && nothingReadable(fetched, kind)
    return QuoteSource(clear ?: fetched, fromDecrypted = clear != null, nothingToQuote = unreadable)
}

private const val PGP_MESSAGE_ARMOUR = "-----BEGIN PGP MESSAGE-----"

private val ENCRYPTED_KINDS = setOf(CryptoKind.PGP_ENCRYPTED, CryptoKind.SMIME_ENCRYPTED, CryptoKind.SMIME_OPAQUE_SIGNED)

/**
 * Judged on the text [quotedOriginalText] would quote, never on [kind] alone nor on armour found
 * anywhere in it: a text beside an encrypted part, before an armoured block, or quoting one, is
 * readable and quoted. The body that opens with the armour is the block itself, whatever [kind]
 * says: over IMAP an armoured body under an HTML part has no text part, and is read as no kind.
 * An encrypted kind with no body is not read from the list preview, which the server draws from
 * whatever part it finds. The opaque S/MIME kind is also what an enveloped message is read as
 * before its source is: a signed one counted here loses a quote that was already empty. An
 * original in the clear with no text keeps its empty quote.
 */
private fun nothingReadable(fetched: Email, kind: CryptoKind?): Boolean {
    if (quotedOriginalText(fetched).trimStart().startsWith(PGP_MESSAGE_ARMOUR)) return true
    return kind in ENCRYPTED_KINDS && quotedOriginalText(fetched.copy(preview = null)).isBlank()
}

package app.sterna.ui.compose

import app.sterna.core.data.mail.smimeSignalOf
import app.sterna.core.imap.CryptoKind
import app.sterna.core.jmap.model.Email
import app.sterna.ui.message.CryptoUiState

/*
 * Edit as new (#254): an existing message's content in a composer that owns nothing.
 *
 * The comments of the `editAsNewId` branch of `ComposeViewModel.prepare` are kept here. That branch
 * is one of its own, and not the server draft arm with its arming switched off: every route that
 * destroys (send, re-save, emptied composer, Delete) reads the id that arm lays, so the one way to
 * leave the original alone is to never be where an id is laid. It takes no threading either, the new
 * message answers nothing. And it carries the attachments before it prefills, unlike the draft arm,
 * so the first prefill is applied with the files already staged. That holds for the first opening
 * only: on a composer rebuilt after a process death the saved `applied` flag draws the editor before
 * the carry has run again, as it does for a draft. A file the carry could not bring is said by the
 * forward's notice. Before any of it, the message read is put to [mayEditAsNew], whose comment says why.
 * `EditAsNewWiringTest` pins all five.
 */

/**
 * The initial fields: the message as it was written, read the way a reopened draft is. No cached row:
 * filling an addressing field the server returned empty is a repair owed to a draft this app saved,
 * and here it would address people the message never named.
 */
internal fun editAsNewFieldsOf(o: Email): DraftFields = draftFieldsOf(o, cached = null)

/** What the reader holds beside the message: whether its body has arrived, and its encryption state. */
internal data class EditAsNewReader(val bodyShown: Boolean, val crypto: CryptoUiState)

/**
 * Whether [o] may be reopened as a new message. An encrypted one may not. The composer is handed an id
 * and reads the message as the server holds it, never what the reader decrypted, so reopened it would be
 * filled with the ciphertext, an armour in the body or the encrypted part as a file, or with nothing.
 * The answer is the same decrypted on screen or not: an entry that came back once the message is
 * decrypted would promise the text on screen and open on that ciphertext.
 *
 * Two witnesses, and either one refuses. The structure of [o] is all `ComposeViewModel.prepare` has,
 * which calls this with no [reader] on the message it read from the server. It is not enough for the
 * menu: once decrypted, the reader holds the clear message, whose structure says nothing, and only its
 * encryption state remembers. The list row painted before the body arrives says nothing either, hence
 * [EditAsNewReader.bodyShown].
 *
 * The menu is not airtight, and is not what holds. A decrypted message reopened from memory is
 * published clear a few suspensions before its encryption state is, and for that instant the entry is
 * offered. An opaque S/MIME signature the reader verified is offered permanently: its state and the
 * message shown are those of a detached signature, and nothing the reader exposes tells them apart.
 * Both end on the check in `prepare`, which refuses with the failed-read notice.
 */
internal fun mayEditAsNew(o: Email, reader: EditAsNewReader?): Boolean =
    !encryptedByStructure(o) && (reader == null || (reader.bodyShown && readerSaysNotEncrypted(reader.crypto)))

/**
 * An OpenPGP/MIME envelope, an inline armoured message, or an opaque S/MIME body. Not
 * `MailRepository.cryptoKindOf`: it answers one kind for both inline armours, and the signed one is not
 * encrypted. `smimeSignalOf` cannot tell an S/MIME envelope from an opaque signature, only the raw
 * source can, so both are refused: reopened, an opaque signature is an empty composer with a `.p7m`.
 */
private fun encryptedByStructure(o: Email): Boolean =
    o.attachments.any { it.type.equals("application/pgp-encrypted", ignoreCase = true) } ||
        o.textContent().orEmpty().contains("-----BEGIN PGP MESSAGE-----") ||
        smimeSignalOf(o) == CryptoKind.SMIME_OPAQUE_SIGNED

/**
 * No `else`, so a state added later does not compile until it is classed. The three states that carry
 * the kind the message was locked as answer by it, and not by a flat no: a signed message stays locked
 * until its signature is checked, for good with no OpenPGP app or with the source out of reach.
 */
private fun readerSaysNotEncrypted(crypto: CryptoUiState): Boolean = when (crypto) {
    CryptoUiState.None -> true
    is CryptoUiState.Decrypted -> !crypto.result.wasEncrypted
    is CryptoUiState.Locked -> lockedAsSigned(crypto.kind)
    is CryptoUiState.NeedsInteraction -> lockedAsSigned(crypto.kind)
    is CryptoUiState.Failed -> lockedAsSigned(crypto.kind)
    CryptoUiState.SmimeNotForCertificate -> false
    CryptoUiState.SmimeFailed -> false
}

/**
 * `PGP_INLINE` is the kind of both armours and answers yes: in the states that carry a kind the reader
 * still holds the message as the server sent it, so [encryptedByStructure] has already refused the
 * encrypted armour. No `else` here either.
 */
private fun lockedAsSigned(kind: CryptoKind): Boolean = when (kind) {
    CryptoKind.PGP_SIGNED, CryptoKind.SMIME_SIGNED, CryptoKind.PGP_INLINE -> true
    CryptoKind.PGP_ENCRYPTED, CryptoKind.SMIME_ENCRYPTED, CryptoKind.SMIME_OPAQUE_SIGNED -> false
}

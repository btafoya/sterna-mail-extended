package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.pgp.PgpSignatureState
import app.sterna.core.imap.CryptoKind

/**
 * What the reader says about an OpenPGP message in [crypto]: the status strip's [title] and [action]
 * while it still waits on the user, and whether the header carries the lock. [title] and [action]
 * are null for the states whose strip says something else ([CryptoUiState.Decrypted],
 * [CryptoUiState.Failed]) or nothing ([CryptoUiState.None]).
 */
internal data class PgpLook(
    @StringRes val title: Int?,
    @StringRes val action: Int?,
    val headerLock: Boolean,
)

internal fun pgpLook(crypto: CryptoUiState): PgpLook = when (crypto) {
    CryptoUiState.None -> PgpLook(title = null, action = null, headerLock = false)
    is CryptoUiState.Decrypted ->
        PgpLook(title = null, action = null, headerLock = crypto.result.wasEncrypted)
    is CryptoUiState.Locked -> pendingLook(crypto.kind)
    is CryptoUiState.NeedsInteraction -> pendingLook(crypto.kind)
    is CryptoUiState.Failed -> PgpLook(title = null, action = null, headerLock = wearsLock(crypto.kind))
}

private fun pendingLook(kind: CryptoKind): PgpLook =
    if (kind == CryptoKind.PGP_SIGNED) {
        PgpLook(R.string.message_pgp_signed_title, R.string.message_pgp_verify, headerLock = wearsLock(kind))
    } else {
        PgpLook(R.string.message_pgp_encrypted_title, R.string.message_pgp_unlock, headerLock = wearsLock(kind))
    }

/** A clear-signed body is readable before any verification, so only the other kinds are sealed. Inline
 *  armour is counted sealed because an inline signed message cannot be told from an encrypted one here. */
private fun wearsLock(kind: CryptoKind): Boolean = kind != CryptoKind.PGP_SIGNED

/**
 * The one-line verdict under a signature, formatted with the signer. An S/MIME signer is a certificate: the
 * OpenPGP lines about a key to confirm, fetch or renew would name something it does not have.
 */
@StringRes
internal fun signatureLine(sig: PgpSignatureState, smime: Boolean): Int? = when (sig) {
    PgpSignatureState.NONE -> null
    PgpSignatureState.VALID_CONFIRMED -> if (smime) R.string.message_smime_sig_valid else R.string.message_pgp_sig_valid
    PgpSignatureState.VALID_UNCONFIRMED ->
        if (smime) R.string.message_smime_sig_unconfirmed else R.string.message_pgp_sig_unconfirmed
    PgpSignatureState.KEY_MISSING ->
        if (smime) R.string.message_smime_sig_missing_certificate else R.string.message_pgp_sig_missing_key
    PgpSignatureState.INVALID -> R.string.message_pgp_sig_invalid
    PgpSignatureState.KEY_REVOKED -> R.string.message_pgp_sig_revoked
    PgpSignatureState.KEY_EXPIRED -> if (smime) R.string.message_smime_sig_expired else R.string.message_pgp_sig_expired
    PgpSignatureState.INSECURE -> R.string.message_pgp_sig_insecure
    PgpSignatureState.SENDER_MISMATCH ->
        if (smime) R.string.message_smime_sig_mismatch else R.string.message_pgp_sig_mismatch
}

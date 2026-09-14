package app.sterna.ui.message

import androidx.annotation.StringRes
import app.sterna.R
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

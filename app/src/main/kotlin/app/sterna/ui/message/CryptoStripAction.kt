package app.sterna.ui.message

import app.sterna.core.data.mail.MessageBody
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.imap.CryptoKind
import app.sterna.core.imap.isSmime

internal enum class CryptoStripAction { RUN_PROVIDER_INTENT, CHOOSE_CERTIFICATE, DECRYPT }

/**
 * What the strip's button does in [crypto]. An S/MIME envelope waiting on a certificate opens Android's chooser;
 * retrying a failed one decrypts again with the certificate already chosen.
 */
internal fun cryptoStripActionOf(crypto: CryptoUiState): CryptoStripAction = when (crypto) {
    is CryptoUiState.NeedsInteraction -> CryptoStripAction.RUN_PROVIDER_INTENT
    is CryptoUiState.Locked ->
        if (crypto.kind == CryptoKind.SMIME_ENCRYPTED) CryptoStripAction.CHOOSE_CERTIFICATE else CryptoStripAction.DECRYPT
    CryptoUiState.SmimeNotForCertificate -> CryptoStripAction.CHOOSE_CERTIFICATE
    else -> CryptoStripAction.DECRYPT
}

/**
 * The certificate chooser's answer. A chosen [alias] is remembered for the account, then the message is
 * decrypted with it, in that order: the decryption reads the alias back from the account. No alias, the user
 * having cancelled or gone back, leaves the strip locked and writes and starts nothing.
 */
internal suspend fun applyCertificateChoice(
    alias: String?,
    remember: suspend (String) -> Unit,
    decrypt: () -> Unit,
    stayLocked: () -> Unit,
) {
    if (alias.isNullOrEmpty()) {
        stayLocked()
        return
    }
    remember(alias)
    decrypt()
}

/** The strip while a decryption asked for from [crypto] runs. */
internal fun decryptingState(crypto: CryptoUiState): CryptoUiState = when (crypto) {
    is CryptoUiState.Locked -> crypto.copy(decrypting = true)
    CryptoUiState.SmimeNotForCertificate, CryptoUiState.SmimeFailed ->
        CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED, decrypting = true)
    else -> crypto
}

/**
 * The strip after a decryption the user asked for. Unlike [overlayCrypto], a failed fetch here gets a card:
 * the user pressed a button and is owed an answer. A null [CryptoUiState.Failed] text is shown as "no OpenPGP
 * app", so an S/MIME message never gets one.
 */
internal fun cryptoStateAfterDecrypt(result: PgpResult<MessageBody>, lockedKind: CryptoKind?): CryptoUiState {
    val kind = lockedKind ?: CryptoKind.PGP_ENCRYPTED
    return when (result) {
        is PgpResult.Success -> (result.value.crypto as? MessageCrypto.Decrypted)?.let { CryptoUiState.Decrypted(it) }
            ?: CryptoUiState.None
        is PgpResult.UserInteractionRequired -> CryptoUiState.NeedsInteraction(result.pendingIntent, kind)
        is PgpResult.Error -> CryptoUiState.Failed(result.message, kind)
        PgpResult.NotAvailable -> if (kind.isSmime) CryptoUiState.SmimeFailed else CryptoUiState.Failed(null, kind)
        is PgpResult.SmimeCertificate -> smimeCardOf(result)
    }
}

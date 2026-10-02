package app.sterna.ui.message

import app.sterna.core.data.mail.MessageBody
import app.sterna.core.data.mail.MessageCrypto
import app.sterna.core.data.pgp.PgpResult
import app.sterna.core.imap.CryptoKind

/**
 * What a check laid over a body already on screen puts in the strip: the verdict of a check that came back,
 * or what an S/MIME envelope that did not open asks of the user ([smimeCardOf]), else nothing. A source out of
 * reach, a failed fetch, or a message with nothing to check leaves the ordinary mail it was shown as, with
 * neither seal nor card: the body being read is still the right one, and a card would call a working offline
 * read a failure.
 */
internal fun overlayCrypto(result: PgpResult<MessageBody>): CryptoUiState? = when (result) {
    is PgpResult.Success -> (result.value.crypto as? MessageCrypto.Decrypted)?.let { CryptoUiState.Decrypted(it) }
    is PgpResult.SmimeCertificate -> smimeCardOf(result)
    else -> null
}

/** The strip of an S/MIME envelope that did not open. Never [CryptoUiState.Failed]: its null text means "no OpenPGP app". */
internal fun smimeCardOf(answer: PgpResult.SmimeCertificate): CryptoUiState = when (answer) {
    PgpResult.CertificateToChoose -> CryptoUiState.Locked(CryptoKind.SMIME_ENCRYPTED)
    PgpResult.NotEncryptedForCertificate -> CryptoUiState.SmimeNotForCertificate
    is PgpResult.DecryptFailed -> CryptoUiState.SmimeFailed
}

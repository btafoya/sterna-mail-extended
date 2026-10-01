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

/** Drawn by the screen as `VerifiedUser`, `GppMaybe` and `GppBad`. */
internal enum class SignatureIcon { VERIFIED, MAYBE, BAD }

/** Drawn by the screen as `primary`, `tertiary`, `onSurfaceVariant` and `error`. */
internal enum class SignatureTone { PRIMARY, TERTIARY, MUTED, ERROR }

/** The verdict line, formatted with the signer, and the seal drawn beside it. */
internal data class SignatureLook(@StringRes val line: Int, val icon: SignatureIcon, val tone: SignatureTone)

/**
 * One table for OpenPGP and S/MIME alike: the header seal and the status card both read it, so they cannot
 * disagree. The first line never names a key or a certificate, which is what lets the two systems share it;
 * the mechanism is named once, in [signatureExplanation]'s technical line. The tones are a public promise:
 * only a confirmed sender gets the verified seal and the primary tone.
 */
internal fun signatureLook(state: PgpSignatureState): SignatureLook? = when (state) {
    PgpSignatureState.NONE -> null
    PgpSignatureState.VALID_CONFIRMED ->
        SignatureLook(R.string.message_sig_verified, SignatureIcon.VERIFIED, SignatureTone.PRIMARY)
    PgpSignatureState.VALID_UNCONFIRMED ->
        SignatureLook(R.string.message_sig_unconfirmed, SignatureIcon.MAYBE, SignatureTone.TERTIARY)
    PgpSignatureState.SENDER_MISMATCH ->
        SignatureLook(R.string.message_sig_mismatch, SignatureIcon.MAYBE, SignatureTone.TERTIARY)
    PgpSignatureState.KEY_MISSING ->
        SignatureLook(R.string.message_sig_missing, SignatureIcon.MAYBE, SignatureTone.MUTED)
    PgpSignatureState.INVALID -> SignatureLook(R.string.message_sig_invalid, SignatureIcon.BAD, SignatureTone.ERROR)
    PgpSignatureState.KEY_EXPIRED -> SignatureLook(R.string.message_sig_expired, SignatureIcon.BAD, SignatureTone.ERROR)
    PgpSignatureState.KEY_REVOKED -> SignatureLook(R.string.message_sig_revoked, SignatureIcon.BAD, SignatureTone.ERROR)
    PgpSignatureState.INSECURE -> SignatureLook(R.string.message_sig_insecure, SignatureIcon.BAD, SignatureTone.ERROR)
}

/** The signature sheet's three paragraphs, in reading order, and the one technical line under them. */
internal data class SignatureExplanation(
    @StringRes val checked: Int,
    @StringRes val unchecked: Int,
    @StringRes val meaning: Int,
    @StringRes val technical: Int,
)

/**
 * What the signature sheet says about [state]. Null for [PgpSignatureState.NONE], and for the verdicts the
 * S/MIME engine never gives (revoked, insecure): a sheet for them would explain a check that never ran.
 *
 * Words the engines cannot back are left out on purpose. OpenPGP [PgpSignatureState.INVALID] also stands
 * for every result OpenKeychain adds later, so it stays generic. S/MIME [PgpSignatureState.VALID_UNCONFIRMED]
 * is both an untrusted issuer and a certificate not meant for mail, which the engine does not tell apart, so
 * one text covers both. OpenPGP never says the signer owns the sender's address: OpenKeychain
 * confirms the key even when it could not compare the address. An expired signature still matches the message,
 * and says so.
 */
internal fun signatureExplanation(state: PgpSignatureState, smime: Boolean): SignatureExplanation? =
    if (smime) smimeExplanation(state) else openPgpExplanation(state)

private fun openPgpExplanation(state: PgpSignatureState): SignatureExplanation? = when (state) {
    PgpSignatureState.NONE -> null
    PgpSignatureState.VALID_CONFIRMED -> SignatureExplanation(
        R.string.signature_ex_confirmed_checked_pgp,
        R.string.signature_ex_confirmed_unchecked_pgp,
        R.string.signature_ex_confirmed_meaning,
        R.string.signature_tech_pgp_confirmed,
    )
    PgpSignatureState.VALID_UNCONFIRMED -> SignatureExplanation(
        R.string.signature_ex_intact,
        R.string.signature_ex_unconfirmed_unchecked_pgp,
        R.string.signature_ex_unconfirmed_meaning,
        R.string.signature_tech_pgp_unconfirmed,
    )
    PgpSignatureState.SENDER_MISMATCH -> SignatureExplanation(
        R.string.signature_ex_mismatch_checked,
        R.string.signature_ex_mismatch_unchecked,
        R.string.signature_ex_mismatch_meaning,
        R.string.signature_tech_pgp_mismatch,
    )
    PgpSignatureState.KEY_MISSING -> SignatureExplanation(
        R.string.signature_ex_missing_checked,
        R.string.signature_ex_missing_unchecked_pgp,
        R.string.signature_ex_missing_meaning,
        R.string.signature_tech_pgp_missing,
    )
    PgpSignatureState.INVALID -> SignatureExplanation(
        R.string.signature_ex_invalid_checked,
        R.string.signature_ex_invalid_unchecked,
        R.string.signature_ex_invalid_meaning,
        R.string.signature_tech_pgp_invalid,
    )
    PgpSignatureState.KEY_EXPIRED -> SignatureExplanation(
        R.string.signature_ex_intact,
        R.string.signature_ex_expired_unchecked_pgp,
        R.string.signature_ex_expired_meaning,
        R.string.signature_tech_pgp_expired,
    )
    PgpSignatureState.KEY_REVOKED -> SignatureExplanation(
        R.string.signature_ex_revoked_checked,
        R.string.signature_ex_revoked_unchecked,
        R.string.signature_ex_revoked_meaning,
        R.string.signature_tech_pgp_revoked,
    )
    PgpSignatureState.INSECURE -> SignatureExplanation(
        R.string.signature_ex_insecure_checked,
        R.string.signature_ex_insecure_unchecked,
        R.string.signature_ex_insecure_meaning,
        R.string.signature_tech_pgp_insecure,
    )
}

private fun smimeExplanation(state: PgpSignatureState): SignatureExplanation? = when (state) {
    PgpSignatureState.NONE, PgpSignatureState.KEY_REVOKED, PgpSignatureState.INSECURE -> null
    PgpSignatureState.VALID_CONFIRMED -> SignatureExplanation(
        R.string.signature_ex_confirmed_checked_smime,
        R.string.signature_ex_confirmed_unchecked_smime,
        R.string.signature_ex_confirmed_meaning,
        R.string.signature_tech_smime_confirmed,
    )
    PgpSignatureState.VALID_UNCONFIRMED -> SignatureExplanation(
        R.string.signature_ex_intact_for_sender,
        R.string.signature_ex_unconfirmed_unchecked_smime,
        R.string.signature_ex_unconfirmed_meaning,
        R.string.signature_tech_smime_unconfirmed,
    )
    PgpSignatureState.SENDER_MISMATCH -> SignatureExplanation(
        R.string.signature_ex_mismatch_checked,
        R.string.signature_ex_mismatch_unchecked,
        R.string.signature_ex_mismatch_meaning,
        R.string.signature_tech_smime_mismatch,
    )
    PgpSignatureState.KEY_MISSING -> SignatureExplanation(
        R.string.signature_ex_missing_checked,
        R.string.signature_ex_missing_unchecked_smime,
        R.string.signature_ex_missing_meaning,
        R.string.signature_tech_smime_missing,
    )
    PgpSignatureState.INVALID -> SignatureExplanation(
        R.string.signature_ex_invalid_checked,
        R.string.signature_ex_invalid_unchecked,
        R.string.signature_ex_invalid_meaning,
        R.string.signature_tech_smime_invalid,
    )
    PgpSignatureState.KEY_EXPIRED -> SignatureExplanation(
        R.string.signature_ex_intact,
        R.string.signature_ex_expired_unchecked_smime,
        R.string.signature_ex_expired_meaning,
        R.string.signature_tech_smime_expired,
    )
}

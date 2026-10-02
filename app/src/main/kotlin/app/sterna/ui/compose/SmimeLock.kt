package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.SmimeKeySource
import app.sterna.core.data.pgp.encrypts
import app.sterna.core.data.pgp.isSmime
import app.sterna.pgp.SmimeRefusal
import app.sterna.pgp.SmimeSigning
import app.sterna.pgp.smimeEncryptsMail
import app.sterna.pgp.smimeIsSender
import app.sterna.pgp.smimeSigning
import java.util.Date

/**
 * The S/MIME modes, in the order of the lock's short tap and of its long-press menu. Which of them are offered is
 * [smimeOffered]'s answer, one list for both gestures, so a mode offered in one is offered in the other.
 */
internal val SMIME_LOCK_MODES: List<PgpMode> = listOf(PgpMode.SMIME_SIGN, PgpMode.SMIME_ENCRYPT)

/**
 * The lock's short tap, given what the sending account can do: [smime] is [smimeOffered]'s list. With OpenPGP it
 * is [nextPgpMode], whatever S/MIME can do besides: an account that has both keeps the cycle it had, S/MIME being
 * in the long-press menu. With S/MIME alone, off then each mode of [smime] in turn, then off.
 */
internal fun nextLockMode(current: PgpMode, openPgp: Boolean, smime: List<PgpMode>): PgpMode = when {
    openPgp -> nextPgpMode(current)
    smime.isNotEmpty() -> (listOf(PgpMode.OFF) + smime).let { cycle -> cycle.getOrNull(cycle.indexOf(current) + 1) }
        ?: PgpMode.OFF
    else -> PgpMode.OFF
}

/** The long-press menu's rows, in order: off, the OpenPGP modes when [openPgp], then [smime]. */
internal fun lockMenuModes(openPgp: Boolean, smime: List<PgpMode>): List<PgpMode> = buildList {
    add(PgpMode.OFF)
    if (openPgp) addAll(listOf(PgpMode.SIGN, PgpMode.ENCRYPT, PgpMode.ENCRYPT_UNSIGNED))
    addAll(smime)
}

/**
 * The S/MIME modes the lock offers, in [SMIME_LOCK_MODES] order: none unless the certificate under [alias] can sign
 * for [fromAddress] now ([smimeSigning]); encrypting too when mail may be encrypted to that same certificate
 * ([smimeEncryptsMail]), every envelope being also for the sender. A keystore that throws offers nothing.
 * Blocking, the keystore is asked unless [alias] is empty.
 */
internal fun smimeOffered(alias: String, fromAddress: String, keys: SmimeKeySource, now: Date): List<PgpMode> {
    if (alias.isEmpty()) return emptyList()
    val signing = runCatching { smimeSigning(alias, fromAddress, keys, now) }.getOrNull() as? SmimeSigning.Ready
        ?: return emptyList()
    val encrypts = smimeEncryptsMail(signing.key.chain.first())
    return SMIME_LOCK_MODES.filter { it != PgpMode.SMIME_ENCRYPT || encrypts }
}

/**
 * The chips' answer for [addresses] while encrypting with S/MIME: whether each has a certificate in [kept], as
 * `SmimeCertificates.certificatesFor` gave it, so one expired or never kept is marked as a missing key is.
 * [fromAddress] is covered by the sender's own certificate, as [app.sterna.pgp.smimeEnvelopeRecipients] covers it.
 */
internal fun smimeCertificatesHeld(addresses: List<String>, kept: Map<String, ByteArray>, fromAddress: String): Map<String, Boolean> =
    addresses.associateWith { it in kept || smimeIsSender(it, fromAddress) }

/**
 * [pgpRefresh] for an OpenPGP mode, and for an S/MIME one, what [smimeOffered] answers for the From now chosen:
 * the mode kept if it is offered; encrypting falls back to signing when only that is; off otherwise, each drop
 * with its own reason. An S/MIME mode is never judged by OpenPGP's availability, which an account without
 * OpenPGP never has.
 */
internal fun lockRefresh(
    current: PgpMode,
    available: Boolean,
    accountConfigured: Boolean,
    encryptByDefault: Boolean,
    userSet: Boolean,
    smimeOffered: List<PgpMode>,
): PgpRefreshOutcome = when {
    !current.isSmime -> pgpRefresh(current, available, accountConfigured, encryptByDefault, userSet)
    current in smimeOffered -> PgpRefreshOutcome(current, PgpDropReason.NONE)
    current.encrypts && PgpMode.SMIME_SIGN in smimeOffered ->
        PgpRefreshOutcome(PgpMode.SMIME_SIGN, PgpDropReason.SMIME_NO_LONGER_ENCRYPTED)
    current.encrypts -> PgpRefreshOutcome(PgpMode.OFF, PgpDropReason.SMIME_ENCRYPT_UNAVAILABLE)
    else -> PgpRefreshOutcome(PgpMode.OFF, PgpDropReason.SMIME_UNAVAILABLE)
}

/** What the composer says when [lockRefresh] gave a mode up for [reason]; null when nothing was given up. */
internal fun lockDropNotice(reason: PgpDropReason): Int? = when (reason) {
    PgpDropReason.NONE -> null
    PgpDropReason.NOT_CONFIGURED -> R.string.compose_pgp_not_configured
    PgpDropReason.NO_PROVIDER -> R.string.message_pgp_no_provider
    PgpDropReason.SMIME_UNAVAILABLE -> R.string.compose_smime_unavailable
    PgpDropReason.SMIME_ENCRYPT_UNAVAILABLE -> R.string.compose_smime_encrypt_dropped
    PgpDropReason.SMIME_NO_LONGER_ENCRYPTED -> R.string.compose_smime_encrypt_to_sign
}

/**
 * The address a message goes out from: the identity's, else the account's login. `MailSender` writes the `From`
 * by the same rule, and an S/MIME certificate is judged against this one, or the reader is told the sender does
 * not match.
 */
internal fun sentFromAddress(identityEmail: String?, username: String): String =
    identityEmail?.trim()?.takeIf { it.isNotEmpty() } ?: username

/** What the composer says when [reason] stops an S/MIME send, after "Could not send:". */
internal fun smimeRefusalWording(reason: SmimeRefusal): Int = when (reason) {
    SmimeRefusal.NO_CERTIFICATE -> R.string.compose_smime_no_certificate
    SmimeRefusal.NOT_VALID_NOW -> R.string.compose_smime_not_valid_now
    SmimeRefusal.OTHER_ADDRESS -> R.string.compose_smime_other_address
    SmimeRefusal.NOT_FOR_SIGNING -> R.string.compose_smime_not_for_signing
    SmimeRefusal.KEYSTORE_UNANSWERED -> R.string.compose_smime_keystore_unanswered
    SmimeRefusal.NOT_FOR_ENCRYPTING -> R.string.compose_smime_not_for_encrypting
    SmimeRefusal.RECIPIENT_WITHOUT_CERTIFICATE -> R.string.compose_smime_recipient_without_certificate
    SmimeRefusal.BCC_WHILE_ENCRYPTING -> R.string.compose_smime_bcc_while_encrypting
}

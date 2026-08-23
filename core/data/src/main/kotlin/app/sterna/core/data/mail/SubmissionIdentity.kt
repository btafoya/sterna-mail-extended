package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Identity

/**
 * Which server identity a JMAP submission is created under, and whether it must carry an explicit
 * `envelope` (RFC 8621 §7.5) so the SMTP `MAIL FROM` matches the `From:` header the reader sees.
 *
 * The account's chosen sending address is not always one the server knows: identities typed by
 * hand in the settings screen exist only on the phone. The submission still has to name SOME
 * server identity, so the previous code fell back to the FIRST one in silence — and since
 * `EmailSubmission/set` carried nothing but `emailId` + `identityId`, the server derived the
 * envelope from that fallback identity. The message then left with `smtp.mailfrom` of one domain
 * and a `From:` of another, which is exactly what DMARC calls a fail (issue #172). Naming the
 * envelope hands the decision back to the server: it either accepts the address or refuses the
 * submission with `forbiddenFrom`, and a refusal is visible instead of a message quietly landing
 * in a spam folder. The SMTP path already works this way — `SmtpClient.kt:332` writes
 * `MAIL FROM:<chosen address>`.
 *
 * ⭐ A FUNCTION, because nothing else on this path can be run: `MailRepository.performDelivery`
 * wants the whole repository, a session and a socket. Here the rule is EXECUTED
 * (`SubmissionIdentityTest`), and the wiring that reaches it is pinned separately.
 *
 * ⛔ It decides NO policy beyond that. Not whether the address is legitimate — that is the
 * server's answer, and taking it here would rebuild the silent guess this replaces. Not what to
 * do with a refusal (the caller keeps the draft). Not the `From:` header, which the caller
 * already builds from the chosen address and must keep building unchanged.
 */
object SubmissionIdentity {

    /**
     * The identity to submit under, and the address to put in the envelope's `mailFrom` — null
     * when the server is to derive it from [identity], which is the ordinary case.
     */
    data class Choice(val identity: Identity, val envelopeMailFrom: String?)

    /**
     * [serverIdentities] is what `Identity/get` returned for the SUBMITTING account; [fromEmail]
     * is the address the composer chose (null or blank when it chose none). [onBehalf] is a
     * delegated sub-account send (issue #31).
     *
     * Returns null when the account has NO server identity at all: there is then nothing to
     * submit under, and the caller raises its own error — a decision about wording and timing
     * that stays where the user-facing message lives.
     *
     * ⛔ [onBehalf] never gets an envelope. On a delegated account the mismatch is DELIBERATE and
     * verified against Stalwart: the `From:` carries the delegated address while the envelope
     * belongs to the login's identity, because member-only methods are refused on the shared
     * account. Posing an envelope there would break a send that works today.
     *
     * ⛔ No chosen address means no envelope either: sending under the default identity is what
     * an account with no identity settings has always done, and the server's own derivation is
     * right for it.
     *
     * The match trims both sides and ignores case. `StoredAccount.resolvedIdentities` dedupes on
     * `trim().lowercase()` (`StoredAccount.kt:132`), so an address typed with a trailing space is
     * treated as a duplicate there; matching it strictly here would make that same address look
     * unknown to the server and pose an envelope for an identity the server actually has.
     */
    fun choose(fromEmail: String?, serverIdentities: List<Identity>, onBehalf: Boolean): Choice? {
        val chosen = fromEmail?.trim().orEmpty()
        // ⛔ An empty chosen address matches NOTHING, deliberately. `Identity.email` defaults to
        // "", so a bare `equals(chosen)` would let a server identity with no address answer the
        // "no address was chosen" case and displace the first identity — the very fallback this
        // is specified to leave alone.
        val matched = if (chosen.isEmpty()) null
        else serverIdentities.firstOrNull { it.email.trim().equals(chosen, true) }
        val identity = matched ?: serverIdentities.firstOrNull() ?: return null
        val needsEnvelope = !onBehalf && chosen.isNotEmpty() && matched == null
        return Choice(identity = identity, envelopeMailFrom = if (needsEnvelope) chosen else null)
    }
}

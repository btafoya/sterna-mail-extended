package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Identity

/** Which server identity a JMAP submission is created under, and whether it must carry an explicit
 *  `envelope` (RFC 8621 §7.5) so the SMTP `mail from` matches the `From:` the reader sees. An
 *  identity typed by hand exists only on the phone, and the envelope the server would derive from
 *  the fallback identity leaves the message with a mismatch DMARC fails (#172). */
object SubmissionIdentity {

    /** The identity to submit under, and the envelope's `mailFrom`, null when the server derives it. */
    data class Choice(val identity: Identity, val envelopeMailFrom: String?)

    /** Null when the account has no server identity at all. [onBehalf] never gets an envelope: on
     *  a delegated account (#31) the mismatch is deliberate and verified against Stalwart. The
     *  match trims and folds case, as `resolvedIdentities` dedupes on `trim().lowercase()`. A
     *  `+tag` sub-address folds to its base identity: an envelope naming the tag is refused (#212). */
    fun choose(fromEmail: String?, serverIdentities: List<Identity>, onBehalf: Boolean): Choice? {
        val chosen = fromEmail?.trim().orEmpty()
        // `Identity.email` defaults to "", so a bare `equals` would let an addressless identity win.
        val matched = if (chosen.isEmpty()) null
        else serverIdentities.withEmail(chosen)
            ?: subAddressBase(chosen)?.let { serverIdentities.withEmail(it) }
        val identity = matched ?: serverIdentities.firstOrNull() ?: return null
        val needsEnvelope = !onBehalf && chosen.isNotEmpty() && matched == null
        return Choice(identity = identity, envelopeMailFrom = if (needsEnvelope) chosen else null)
    }

    private fun List<Identity>.withEmail(email: String) =
        firstOrNull { it.email.trim().equals(email, true) }

    private fun subAddressBase(email: String): String? {
        val at = email.lastIndexOf('@')
        val plus = email.indexOf('+')
        if (at <= 0 || plus <= 0 || plus > at) return null
        return email.substring(0, plus) + email.substring(at)
    }
}

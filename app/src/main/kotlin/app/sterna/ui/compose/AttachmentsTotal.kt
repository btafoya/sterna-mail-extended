package app.sterna.ui.compose

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.MailRepository

/** What the composer says under its attachments: their summed size, against the server's [limit] when known. */
data class AttachmentsTotal(val sum: Long, val limit: Long?, val over: Boolean)

/** Null when no size is known, or for a single attachment within the limit, whose row already says its size. */
fun attachmentsTotal(sizes: List<Long>, limit: Long?): AttachmentsTotal? {
    if (sizes.isEmpty()) return null
    val sum = sizes.sumOf { it.coerceAtLeast(0) }
    if (sum == 0L) return null
    val over = limit != null && sum > limit
    if (sizes.size < 2 && !over) return null
    return AttachmentsTotal(sum, limit, over)
}

/**
 * The per-Email attachment limit for [sender], read from the repository's [cached] session only:
 * a lookup must never cost a request, so a cold cache or another login's session gives null.
 * Not `JmapSession.maxSizeUpload`: that bounds one blob, while `maxSizeAttachmentsPerEmail` is the
 * mail account's bound on the whole message (RFC 8621).
 */
fun sendingAttachmentsLimit(cached: MailRepository.JmapAccountSession?, sender: AccountCredentials?): Long? {
    if (cached == null || sender == null || sender.protocol != MailProtocol.JMAP) return null
    // Matched on the login, not the account id: a linked sub-account shares its login's session.
    if (cached.credentials.server != sender.server || cached.credentials.username != sender.username) return null
    // The account the send creates the Email under, so the limit that will judge it.
    val submissionAccountId = cached.session.mailAccountId() ?: cached.accountId
    return cached.session.maxSizeAttachmentsPerEmail(submissionAccountId)
}

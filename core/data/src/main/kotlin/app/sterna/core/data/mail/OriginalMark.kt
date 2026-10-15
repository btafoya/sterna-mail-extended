package app.sterna.core.data.mail

/** What a delivered reply or forward sets on the message it answers, on each protocol. */
enum class ReplyMark(val imapFlag: String, val jmapKeyword: String) {
    ANSWERED("\\Answered", "\$answered"),
    FORWARDED("\$Forwarded", "\$forwarded"),
}

/**
 * The message a queued send answers or forwards. [accountId] is the account the original was read
 * under, which is not the sending account once the composer's From has been switched to another.
 */
data class OriginalMark(val accountId: String, val emailId: String, val mark: ReplyMark)

/**
 * The three columns `outbox` and `scheduled_sends` keep as one [OriginalMark]. Null unless all
 * three name one: a row queued before v37, a new message, an "Edit as new", or a stored kind this
 * build does not know.
 */
fun originalMarkOf(accountId: String?, emailId: String?, mark: String?): OriginalMark? {
    val kind = ReplyMark.entries.firstOrNull { it.name == mark } ?: return null
    if (accountId.isNullOrBlank() || emailId.isNullOrBlank()) return null
    return OriginalMark(accountId, emailId, kind)
}

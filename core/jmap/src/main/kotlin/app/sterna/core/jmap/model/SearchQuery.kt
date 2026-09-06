package app.sterna.core.jmap.model

    /**
     * A structured mail search. Non-blank/non-null fields are combined with AND into a JMAP
     * Email/query filter (RFC 8621 §4.4.1); [afterMillis]/[beforeMillis] are epoch-millis bounds on
     * the received date. [recipient] matches `To` OR `Cc`, a single field on purpose: a message
     * addressed to an alias in copy WAS received at that address, and splitting the two would silently
     * drop half the hits on an account with aliases. [flagged] exists because 1.4.5 stopped pinning
     * flagged messages to the top, which was in practice the only way to find them again — the sort
     * only reorders the folder you stand in, and most IMAP servers expose no `flagged` role folder.
     */
data class SearchQuery(
    val text: String = "",
    val from: String = "",
    val recipient: String = "",
    val subject: String = "",
    val hasAttachment: Boolean = false,
    val flagged: Boolean = false,
    val afterMillis: Long? = null,
    val beforeMillis: Long? = null,
) {
    // [flagged] counts: a search whose ONLY criterion is "flagged" is a legitimate search (show me
    // my starred mail), and treating it as empty would leave the Search button doing nothing.
    fun isEmpty(): Boolean =
        text.isBlank() && from.isBlank() && recipient.isBlank() && subject.isBlank() &&
            !hasAttachment && !flagged && afterMillis == null && beforeMillis == null
}

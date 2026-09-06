package app.sterna.core.data.mail

/**
 * The thread key of an IMAP message, rebuilt on the client from its threading headers.
 *
 * A JMAP server hands every message a `threadId`; an IMAP server hands nothing of the kind, so
 * for an IMAP account to fold into conversations the key has to come from the headers the list
 * FETCH already carries: the ROOT of `References` (its first id, which every reply in the chain
 * repeats — RFC 5322 §3.6.4), failing that the parent named by `In-Reply-To`, failing that the
 * message's own `Message-ID`. A message that carries none of the three gets no key, and
 * `COALESCE(threadId, id)` makes it its own conversation, exactly as before.
 *
 * The value is stored AS IS, angle brackets included — `<m1@x>`, never `m1@x`: a bracketed key
 * cannot collide with an `imap:…` row id inside that COALESCE, and `ThreadKey.decode` cuts on the
 * FIRST `|`, so a `|` inside a Message-ID survives the round trip through a route argument. No case
 * folding either: a Message-ID is case-sensitive by RFC, and this function decides nothing more.
 *
 * Token rule = `MailRepository.headerIds`: the `<…>` tokens, brackets kept; when a non-blank
 * value carries no bracket at all, its bare words are wrapped as `<word>`.
 *
 * Pure and `internal` so that a JVM test can EXECUTE the rule instead of reading the SQL that
 * consumes it — `ImapThreadKeyTest`.
 */
internal fun imapThreadKey(references: String?, inReplyTo: String?, messageId: String?): String? =
    firstHeaderId(references) ?: firstHeaderId(inReplyTo) ?: firstHeaderId(messageId)

private val BRACKETED_ID = Regex("<[^<>]+>")

private fun firstHeaderId(value: String?): String? {
    if (value.isNullOrBlank()) return null
    BRACKETED_ID.find(value)?.let { return it.value }
    return value.trim().split(Regex("\\s+")).firstOrNull()?.let { "<$it>" }
}

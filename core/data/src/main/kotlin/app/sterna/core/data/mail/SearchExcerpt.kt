package app.sterna.core.data.mail

import app.sterna.core.data.text.unescapeEntities
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.withoutBidiAndControls

/**
 * A JMAP `SearchSnippet` preview (RFC 8621 §5) as one plain line, or null when nothing is left.
 * The server sends HTML-escaped text whose only tags are `<mark>`. Tags come off before entities
 * are decoded, so a `&lt;mark&gt;` the sender typed stays text; the bidi filter runs last, so an
 * encoded `&#x202E;` is caught too. Not `htmlToText`: its tag stripping would eat decoded text.
 */
fun searchExcerpt(raw: String?): String? {
    if (raw == null) return null
    val text = withoutBidiAndControls(unescapeEntities(raw.replace(MARK_TAG, "")))
        .replace(WHITESPACE_RUN, " ")
        .trim()
    return text.ifEmpty { null }
}

private val MARK_TAG = Regex("</?mark>", RegexOption.IGNORE_CASE)
private val WHITESPACE_RUN = Regex("\\s+")

/**
 * [MailSearchResult.excerpts] is keyed on (accountId, id), the key the unified merge dedupes on:
 * two accounts of one server can hold the same id (#92). Only [emails] get one; an id the get did
 * not return has no row to sit under.
 */
internal fun excerptsOf(emails: List<Email>, snippetPreviews: Map<String, String>): Map<Pair<String?, String>, String> =
    emails.mapNotNull { email ->
        searchExcerpt(snippetPreviews[email.id])?.let { (email.accountId to email.id) to it }
    }.toMap()

/** Stamps [accountId] on the emails, the excerpts' keys and the purged keys together, or the last
 *  two no longer meet the rows they belong to. */
internal fun MailSearchResult.ownedBy(accountId: String): MailSearchResult = copy(
    emails = emails.map { it.copy(accountId = accountId) },
    excerpts = excerpts.mapKeys { (key, _) -> accountId to key.second },
    purged = purged.mapTo(LinkedHashSet()) { EmailKey(accountId, it.emailId) },
)

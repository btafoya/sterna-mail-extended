package app.sterna.core.data.mail

import app.sterna.core.jmap.model.SearchQuery

/**
 * The FTS4 `MATCH` expression of the words [query] holds: every token a prefix term, the free text
 * over every indexed column, [SearchQuery.from] held to the `sender` column and
 * [SearchQuery.subject] to `subject`. Terms side by side are an implicit AND.
 *
 * Null when it holds none, and `EmailFtsDao.searchWithoutWords` runs instead: FTS4 has no "match
 * everything", and SQLite refuses a `MATCH` under an `OR` at run time, so a filter on flags alone
 * cannot share the statement that matches words.
 *
 * Tokens are cut on anything that is not a letter or a digit, the tokenizer's own rule: a quote, a
 * colon or a dash passed through would be read as FTS syntax, not as text.
 */
internal fun ftsMatch(query: SearchQuery): String? {
    val terms = ftsTokens(query.text).map { "$it*" } +
        ftsTokens(query.from).map { "sender:$it*" } +
        ftsTokens(query.subject).map { "subject:$it*" }
    return terms.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

private fun ftsTokens(value: String): List<String> =
    value.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }

/**
 * The account ids handed to the index for a search over [scope], or null when no hit may come back.
 *
 * An empty list reaching the DAO filters no account at all, deliberately: `AccountStore.accounts()`
 * is empty both for "no account" and for "the stored list could not be decoded" (#121). So
 * [configured] empty passes [scope] through, and a scope with no configured account left in it
 * answers null rather than an empty list, which would search every account.
 */
internal fun localSearchAccounts(configured: List<String>, scope: Collection<String>): List<String>? {
    if (configured.isEmpty()) return scope.toList()
    return configured.filter { it in scope }.ifEmpty { null }
}

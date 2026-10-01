package app.sterna.ui.search

import app.sterna.core.data.mail.MailSearchResult
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.inbox.SearchUi

/**
 * The repository's (accountId, id) excerpts under the lazy-list key of the row they belong to. They
 * live in the search's result state and die with it, never on the `Email` nor in Room: a passage
 * is true of one query only.
 */
internal fun excerptsByResultKey(result: MailSearchResult): Map<String, String> =
    result.excerpts.mapKeys { (key, _) -> searchResultKey(key.first, key.second) }

/** A passage matched the previous criteria, not these: it goes with the search that found it. */
internal fun SearchUi.forCriteria(criteria: SearchQuery): SearchUi = copy(criteria = criteria, excerpts = emptyMap())

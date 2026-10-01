package app.sterna.ui.search

import app.sterna.core.jmap.model.Email

/**
 * Lazy-list key for a result row. Account + id, never the id alone: search spans every account and
 * deduplicates by (accountId, id) on purpose, so the same JMAP id legitimately appears twice and a
 * duplicate key in a lazy list throws (#92). Same form as the inbox lists.
 */
internal fun searchResultKey(email: Email): String = searchResultKey(email.accountId, email.id)

internal fun searchResultKey(accountId: String?, id: String): String = "$accountId|$id"

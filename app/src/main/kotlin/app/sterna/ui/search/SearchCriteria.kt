package app.sterna.ui.search

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.inbox.SearchScope

/** The on/off criteria of a search, drawn as filter chips under its bar, in row order. */
enum class SearchToggle { UNREAD, FLAGGED, ATTACHMENT }

internal fun SearchQuery.has(toggle: SearchToggle): Boolean = when (toggle) {
    SearchToggle.UNREAD -> unread
    SearchToggle.FLAGGED -> flagged
    SearchToggle.ATTACHMENT -> hasAttachment
}

internal fun SearchQuery.toggled(toggle: SearchToggle): SearchQuery = when (toggle) {
    SearchToggle.UNREAD -> copy(unread = !unread)
    SearchToggle.FLAGGED -> copy(flagged = !flagged)
    SearchToggle.ATTACHMENT -> copy(hasAttachment = !hasAttachment)
}

@StringRes
internal fun searchToggleLabel(toggle: SearchToggle): Int = when (toggle) {
    SearchToggle.UNREAD -> R.string.filter_unread
    SearchToggle.FLAGGED -> R.string.filter_starred
    SearchToggle.ATTACHMENT -> R.string.filter_attachment
}

/** The criteria set from the Filters sheet, drawn as removable chips after the toggles. */
enum class SearchField { FROM, RECIPIENT, SUBJECT, SINCE, UNTIL }

/** One active sheet criterion: [text] typed, or [millis], a date bound the screen formats in its locale. */
data class SearchFieldChip(
    val field: SearchField,
    val text: String = "",
    val millis: Long? = null,
)

/**
 * The active sheet criteria of [query], in the sheet's order. A blank field is not a criterion; an
 * address criterion shows the name [names] holds for it, if any.
 */
fun searchFieldChips(query: SearchQuery, names: AddressNames): List<SearchFieldChip> = buildList {
    if (query.from.isNotBlank()) add(SearchFieldChip(SearchField.FROM, text = names.shown(AddressField.FROM, query).trim()))
    if (query.recipient.isNotBlank()) add(SearchFieldChip(SearchField.RECIPIENT, text = names.shown(AddressField.RECIPIENT, query).trim()))
    query.subject.trim().takeIf { it.isNotEmpty() }?.let { add(SearchFieldChip(SearchField.SUBJECT, text = it)) }
    query.afterMillis?.let { add(SearchFieldChip(SearchField.SINCE, millis = it)) }
    query.beforeMillis?.let { add(SearchFieldChip(SearchField.UNTIL, millis = it)) }
}

/** [this] without the one criterion a chip's cross removes; the text and the toggles stay. */
internal fun SearchQuery.without(field: SearchField): SearchQuery = when (field) {
    SearchField.FROM -> copy(from = "")
    SearchField.RECIPIENT -> copy(recipient = "")
    SearchField.SUBJECT -> copy(subject = "")
    SearchField.SINCE -> copy(afterMillis = null)
    SearchField.UNTIL -> copy(beforeMillis = null)
}

/** [this] with [sheet]'s five fields; the words and toggles stay, or a draft opened before a chip undoes it. */
internal fun SearchQuery.withFieldsOf(sheet: SearchQuery): SearchQuery = copy(
    from = sheet.from,
    recipient = sheet.recipient,
    subject = sheet.subject,
    afterMillis = sheet.afterMillis,
    beforeMillis = sheet.beforeMillis,
)

/** Whether the list shows a search's answer: the bar open and asking something. The screen's and "Select all"'s one predicate. */
internal fun searchAsked(searching: Boolean, criteria: SearchQuery): Boolean = searching && !criteria.isEmpty()

private const val KEY_ACTIVE = "search.active"
private const val KEY_TEXT = "search.text"
private const val KEY_FROM = "search.from"
private const val KEY_RECIPIENT = "search.recipient"
private const val KEY_FROM_NAME = "search.from.name"
private const val KEY_RECIPIENT_NAME = "search.recipient.name"
private const val KEY_SUBJECT = "search.subject"
private const val KEY_UNREAD = "search.unread"
private const val KEY_FLAGGED = "search.flagged"
private const val KEY_ATTACHMENT = "search.attachment"
private const val KEY_AFTER = "search.after"
private const val KEY_BEFORE = "search.before"
private const val KEY_SCOPE = "search.scope"

/** An open search as the inbox entry's `SavedStateHandle` keeps it across a process death. */
internal fun savedSearch(
    active: Boolean,
    criteria: SearchQuery,
    scope: SearchScope,
    names: AddressNames,
): Map<String, Any?> = mapOf(
    KEY_ACTIVE to active,
    KEY_TEXT to criteria.text,
    KEY_FROM to criteria.from,
    KEY_RECIPIENT to criteria.recipient,
    KEY_FROM_NAME to names.keptFor(criteria).from?.display,
    KEY_RECIPIENT_NAME to names.keptFor(criteria).recipient?.display,
    KEY_SUBJECT to criteria.subject,
    KEY_UNREAD to criteria.unread,
    KEY_FLAGGED to criteria.flagged,
    KEY_ATTACHMENT to criteria.hasAttachment,
    KEY_AFTER to criteria.afterMillis,
    KEY_BEFORE to criteria.beforeMillis,
    KEY_SCOPE to scope.name,
)

internal fun savedSearchActive(read: (String) -> Any?): Boolean = read(KEY_ACTIVE) as? Boolean ?: false

internal fun savedSearchCriteria(read: (String) -> Any?): SearchQuery = SearchQuery(
    text = read(KEY_TEXT) as? String ?: "",
    from = read(KEY_FROM) as? String ?: "",
    recipient = read(KEY_RECIPIENT) as? String ?: "",
    subject = read(KEY_SUBJECT) as? String ?: "",
    unread = read(KEY_UNREAD) as? Boolean ?: false,
    flagged = read(KEY_FLAGGED) as? Boolean ?: false,
    hasAttachment = read(KEY_ATTACHMENT) as? Boolean ?: false,
    afterMillis = read(KEY_AFTER) as? Long,
    beforeMillis = read(KEY_BEFORE) as? Long,
)

/** The kept names, each paired back with the address its criterion kept. */
internal fun savedSearchNames(read: (String) -> Any?): AddressNames = AddressNames(
    from = pickedName(read(KEY_FROM) as? String, read(KEY_FROM_NAME) as? String),
    recipient = pickedName(read(KEY_RECIPIENT) as? String, read(KEY_RECIPIENT_NAME) as? String),
)

/** Null when nothing was kept, or a name no [SearchScope] answers to: the caller reads the view instead. */
internal fun savedSearchScope(read: (String) -> Any?): SearchScope? =
    (read(KEY_SCOPE) as? String)?.let { name -> SearchScope.entries.firstOrNull { it.name == name } }

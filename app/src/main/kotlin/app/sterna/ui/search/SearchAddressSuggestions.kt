package app.sterna.ui.search

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.contacts.ContactSuggestion
import app.sterna.contacts.PoolContact
import app.sterna.contacts.buildRecipientPool
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.compose.RecipientSuggester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two search criteria that hold an address. */
internal enum class AddressField { FROM, RECIPIENT }

/**
 * The pool the search's Filters sheet ranks. Unlike the composer's `loadRecipientPool`, which reads the book
 * of the sending account alone, it reads the server book of every account in [accountIds]: the
 * search itself spans every account. Every source is a local copy, so neither a focus nor a
 * keystroke reaches a server; a null [device] means the phone's contacts are off, and the
 * permission is never asked from search: without it `AndroidContacts.all` returns nothing. There is
 * no sending account, so the suggester's sender is a constant and a change in the list of accounts
 * while typing is not followed.
 */
internal suspend fun loadSearchRecipientPool(
    accountIds: List<String>,
    sent: suspend () -> List<SentContactRow>,
    received: suspend () -> List<ReceivedContactRow>,
    serverBook: suspend (accountId: String) -> List<ContactRow>,
    device: (suspend () -> List<ContactSuggestion>)?,
    selves: Set<String>,
): List<PoolContact> = buildRecipientPool(
    sent = sent(),
    received = received(),
    book = accountIds.flatMap { serverBook(it) },
    phone = device?.invoke().orEmpty(),
    selves = selves,
)

/**
 * A pick replaces the criterion with the bare address, never "Name <address>" nor a list: the
 * criterion goes to the server as it is.
 */
internal fun SearchQuery.withAddress(field: AddressField, contact: ContactSuggestion): SearchQuery = when (field) {
    AddressField.FROM -> copy(from = contact.email)
    AddressField.RECIPIENT -> copy(recipient = contact.email)
}

/** The name a picked contact shows in place of [address], on the sheet's field and on the chip. */
data class PickedName(val address: String, val display: String)

/**
 * The names the two address criteria show. They are never part of the [SearchQuery]: the server and
 * the local index get the address alone, and a name stands for its criterion only while the
 * criterion still holds the address it was picked with.
 */
data class AddressNames(val from: PickedName? = null, val recipient: PickedName? = null)

internal fun SearchQuery.address(field: AddressField): String = when (field) {
    AddressField.FROM -> from
    AddressField.RECIPIENT -> recipient
}

internal fun AddressNames.of(field: AddressField): PickedName? = when (field) {
    AddressField.FROM -> from
    AddressField.RECIPIENT -> recipient
}

internal fun AddressNames.withName(field: AddressField, name: PickedName?): AddressNames = when (field) {
    AddressField.FROM -> copy(from = name)
    AddressField.RECIPIENT -> copy(recipient = name)
}

/** The pick's name for [field], or none for a contact without one, which then shows its address. */
internal fun AddressNames.picked(field: AddressField, contact: ContactSuggestion): AddressNames =
    withName(field, pickedName(contact.email, contact.name?.trim()))

/** One address field after an edit: the criterion it sends and the name it shows. */
data class AddressEdit(val criterion: String, val name: PickedName?)

/**
 * A change of an address field's text, from [shownBefore] to [received]. While the field shows the
 * name [picked] for [criterion], the first change, a letter typed or erased, is not taken: the name
 * drops and the field gives back the address, which the user then edits. Taking the keystroke would
 * send the name, whole or cut, as the criterion.
 */
internal fun addressEdited(shownBefore: String, received: String, picked: PickedName?, criterion: String): AddressEdit {
    if (received == shownBefore) return AddressEdit(criterion, picked)
    val showingName = picked != null && picked.address == criterion && shownBefore == picked.display
    return if (showingName) AddressEdit(criterion, null) else AddressEdit(received, null)
}

/** The sheet's five fields as Apply hands them over, with the names still standing for them. */
data class SheetFields(val query: SearchQuery, val names: AddressNames)

/** The criteria come from the fields' addresses, never from what the fields show. */
internal fun sheetFields(
    from: String,
    recipient: String,
    subject: String,
    since: Long?,
    until: Long?,
    names: AddressNames,
): SheetFields {
    val query = SearchQuery(from = from, recipient = recipient, subject = subject, afterMillis = since, beforeMillis = until)
    return SheetFields(query, names.keptFor(query))
}

/** The names still standing for [criteria]'s addresses; a criterion changed or removed loses its own. */
internal fun AddressNames.keptFor(criteria: SearchQuery): AddressNames = AddressNames(
    from = from?.takeIf { it.address == criteria.from },
    recipient = recipient?.takeIf { it.address == criteria.recipient },
)

/** What the field and the chip of [field] show: the picked name, or the criterion itself. */
internal fun AddressNames.shown(field: AddressField, criteria: SearchQuery): String =
    of(field)?.takeIf { it.address == criteria.address(field) }?.display ?: criteria.address(field)

/** For the sheet's `rememberSaveable`: a rotation keeps the names a pick gave its fields. */
internal val AddressNamesSaver: Saver<AddressNames, Any> = listSaver(
    save = { listOf(it.from?.address, it.from?.display, it.recipient?.address, it.recipient?.display) },
    restore = { AddressNames(pickedName(it[0], it[1]), pickedName(it[2], it[3])) },
)

/** A name equal to its address is none: the field could not tell it from the address and would swallow an edit. */
internal fun pickedName(address: String?, display: String?): PickedName? =
    if (address.isNullOrBlank() || display.isNullOrBlank() || display == address) null else PickedName(address, display)

/**
 * The field's own text and cursor, or [value] with the cursor at its end when [value] was set from
 * outside, by a pick: keeping the old cursor would put the next keystroke inside the address.
 */
internal fun TextFieldValue.showing(value: String): TextFieldValue =
    if (text == value) this else TextFieldValue(value, TextRange(value.length))

/** The suggester publishes one list for both fields: only the field holding the focus shows it. */
internal fun suggestionsUnder(
    field: AddressField,
    focused: AddressField?,
    suggestions: List<ContactSuggestion>,
): List<ContactSuggestion> = if (field == focused) suggestions else emptyList()

/**
 * The address menu of the Filters sheet, over the composer's [RecipientSuggester]. Nothing is ever
 * "already on screen" here: a criterion holds one address, so every call passes an empty set.
 */
internal class AddressSuggestions(private val suggester: RecipientSuggester) {
    private val _focused = MutableStateFlow<AddressField?>(null)
    val focused: StateFlow<AddressField?> = _focused.asStateFlow()
    val suggestions: StateFlow<List<ContactSuggestion>> = suggester.suggestions

    fun focusGained(field: AddressField, value: String) {
        _focused.value = field
        suggester.focused(value, emptySet())
    }

    fun typed(field: AddressField, value: String) {
        _focused.value = field
        suggester.suggest(value, emptySet())
    }

    /** Ignored when another field already took the focus: Compose does not promise which callback comes first. */
    fun focusLost(field: AddressField) {
        if (_focused.value != field) return
        _focused.value = null
        suggester.clear()
    }

    fun picked() = suggester.clear()

    /** The contacts setting loads after the first focus can land, which read a pool without the phone's contacts. */
    fun sourcesChanged() = suggester.sourcesChanged()
}

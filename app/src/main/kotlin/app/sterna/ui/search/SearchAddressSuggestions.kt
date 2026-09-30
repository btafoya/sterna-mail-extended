package app.sterna.ui.search

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
 * The pool the search screen ranks. Unlike the composer's `loadRecipientPool`, which reads the book
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
 * The address menu of the search screen, over the composer's [RecipientSuggester]. Nothing is ever
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

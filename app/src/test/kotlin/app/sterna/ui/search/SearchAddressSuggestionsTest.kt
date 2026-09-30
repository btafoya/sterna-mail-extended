package app.sterna.ui.search

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.contacts.ContactSuggestion
import app.sterna.contacts.PoolContact
import app.sterna.core.data.db.ContactRow
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.compose.RecipientSuggester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The address menu of the search screen: the pool spans the books of every account, a single menu
 * shows under the one field that holds the focus, and a pick replaces the criterion with the bare
 * address that goes to the server as it is.
 */
class SearchAddressSuggestionsTest {
    private val books = mapOf(
        "A" to listOf(ContactRow("ann@a.org", "Ann A")),
        "B" to listOf(ContactRow("anna@b.org", "Anna B")),
    )
    private val asked = mutableListOf<String>()

    @Test fun `the search pool holds the server books of every account`() {
        val pool = runBlocking {
            loadSearchRecipientPool(
                accountIds = listOf("A", "B"),
                sent = { emptyList() },
                received = { emptyList() },
                serverBook = { account -> asked += account; books[account].orEmpty() },
                device = null,
                selves = emptySet(),
            )
        }
        assertEquals(setOf("ann@a.org", "anna@b.org"), pool.map { it.email }.toSet())
        assertEquals(listOf("A", "B"), asked)
    }

    private fun contact(email: String) = PoolContact(email, null, null, 1, 1, 0, 0, true)

    private fun menu(block: suspend (AddressSuggestions) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val suggester = RecipientSuggester(
                scope = scope,
                sender = { null },
                loadPool = { listOf(contact("ann@a.org"), contact("bob@b.org")) },
                now = { 1_000L },
            )
            block(AddressSuggestions(suggester))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun settle() = repeat(10) { yield() }

    private fun AddressSuggestions.under(field: AddressField) =
        suggestionsUnder(field, focused.value, suggestions.value).map { it.email }

    @Test fun `the menu of From shows under From and never under To`() = menu { menu ->
        menu.focusGained(AddressField.FROM, "")
        settle()

        assertEquals(listOf("ann@a.org", "bob@b.org"), menu.under(AddressField.FROM))
        assertEquals(emptyList<String>(), menu.under(AddressField.RECIPIENT))
    }

    @Test fun `leaving the field empties the menu`() = menu { menu ->
        menu.focusGained(AddressField.FROM, "")
        settle()
        assertEquals(listOf("ann@a.org", "bob@b.org"), menu.under(AddressField.FROM))
        menu.focusLost(AddressField.FROM)
        settle()

        assertEquals(emptyList<String>(), menu.under(AddressField.FROM))
        assertEquals(emptyList<ContactSuggestion>(), menu.suggestions.value)
    }

    @Test fun `From losing the focus after To gained it leaves the menu of To`() = menu { menu ->
        menu.focusGained(AddressField.FROM, "")
        settle()
        menu.focusGained(AddressField.RECIPIENT, "")
        settle()
        menu.focusLost(AddressField.FROM)
        settle()

        assertEquals(listOf("ann@a.org", "bob@b.org"), menu.under(AddressField.RECIPIENT))
        assertEquals(emptyList<String>(), menu.under(AddressField.FROM))
    }

    @Test fun `typing in To moves the menu to To`() = menu { menu ->
        menu.focusGained(AddressField.FROM, "")
        settle()
        menu.typed(AddressField.RECIPIENT, "bo")
        settle()

        assertEquals(listOf("bob@b.org"), menu.under(AddressField.RECIPIENT))
        assertEquals(emptyList<String>(), menu.under(AddressField.FROM))
    }

    @Test fun `a pick closes the menu`() = menu { menu ->
        menu.typed(AddressField.FROM, "an")
        settle()
        assertEquals(listOf("ann@a.org"), menu.under(AddressField.FROM))
        menu.picked()
        settle()

        assertEquals(emptyList<ContactSuggestion>(), menu.suggestions.value)
    }

    @Test fun `a pick replaces the criterion with the bare address`() {
        val query = SearchQuery(from = "an", recipient = "someone@c.org")

        assertEquals(
            SearchQuery(from = "ann@a.org", recipient = "someone@c.org"),
            query.withAddress(AddressField.FROM, ContactSuggestion("ann@a.org", "Ann A")),
        )
        assertEquals(
            SearchQuery(from = "an", recipient = "bob@b.org"),
            query.withAddress(AddressField.RECIPIENT, ContactSuggestion("bob@b.org", "Bob")),
        )
    }

    @Test fun `after a pick the cursor sits at the end of the address, and typing keeps its own cursor`() {
        val typed = TextFieldValue("an", TextRange(2))

        assertEquals(TextRange(9), typed.showing("ann@a.org").selection)
        assertEquals(typed, typed.showing("an"))
    }
}

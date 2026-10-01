package app.sterna.ui.search

import androidx.compose.runtime.saveable.SaverScope
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
import org.junit.Assert.assertNull
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

    private val jeanne = ContactSuggestion("j.dupont@exemple.org", "Jeanne Dupont")

    @Test fun `a picked contact sends its address and shows its name, in From and in To or Cc`() {
        for (field in AddressField.entries) {
            val query = SearchQuery().withAddress(field, jeanne)
            val names = AddressNames().picked(field, jeanne)

            assertEquals("$field sends", "j.dupont@exemple.org", query.address(field))
            assertEquals("$field shows", "Jeanne Dupont", names.shown(field, query))
            assertEquals("$field names", PickedName("j.dupont@exemple.org", "Jeanne Dupont"), names.of(field))
        }
        assertEquals(
            "a pick in To or Cc leaves From's name",
            "Ann A",
            AddressNames(from = PickedName("ann@a.org", "Ann A")).picked(AddressField.RECIPIENT, jeanne)
                .shown(AddressField.FROM, SearchQuery(from = "ann@a.org")),
        )
    }

    @Test fun `a contact without a name shows its address`() {
        for (name in listOf(null, "", "   ")) {
            val bare = ContactSuggestion("j.dupont@exemple.org", name)
            val query = SearchQuery().withAddress(AddressField.FROM, bare)
            val names = AddressNames(from = PickedName("old@exemple.org", "Old")).picked(AddressField.FROM, bare)

            assertNull("name '$name'", names.from)
            assertEquals("name '$name'", "j.dupont@exemple.org", names.shown(AddressField.FROM, query))
        }
    }

    private val picked = PickedName("j.dupont@exemple.org", "Jeanne Dupont")

    @Test fun `the first change of a field showing a name gives back the address, and the keystroke is not taken`() {
        for (received in listOf("Jeanne Dupon", "Jeanne Dupontx", "")) {
            assertEquals(
                "'$received'",
                AddressEdit("j.dupont@exemple.org", null),
                addressEdited(shownBefore = "Jeanne Dupont", received = received, picked = picked, criterion = "j.dupont@exemple.org"),
            )
        }
    }

    @Test fun `once the address is back, the field edits the criterion itself`() {
        assertEquals(
            AddressEdit("j.dupont@exemple.or", null),
            addressEdited(shownBefore = "j.dupont@exemple.org", received = "j.dupont@exemple.or", picked = null, criterion = "j.dupont@exemple.org"),
        )
        assertEquals(
            "a name standing for another address is no name shown: the keystroke is taken",
            AddressEdit("bob@b.or", null),
            addressEdited(shownBefore = "bob@b.org", received = "bob@b.or", picked = picked, criterion = "bob@b.org"),
        )
        assertEquals(
            "no change keeps the name",
            AddressEdit("j.dupont@exemple.org", picked),
            addressEdited(shownBefore = "Jeanne Dupont", received = "Jeanne Dupont", picked = picked, criterion = "j.dupont@exemple.org"),
        )
    }

    @Test fun `a name equal to its address is no name, so an edit is never swallowed`() {
        assertNull(AddressNames().picked(AddressField.FROM, ContactSuggestion("j.dupont@exemple.org", "j.dupont@exemple.org")).from)
    }

    @Test fun `a name never stands for another address`() {
        val names = AddressNames().picked(AddressField.FROM, jeanne)
        val query = SearchQuery().withAddress(AddressField.FROM, jeanne)
        val other = query.copy(from = "bob@b.org")

        assertEquals("bob@b.org", names.shown(AddressField.FROM, other))
        assertEquals(AddressNames(), names.keptFor(other))
        assertEquals(names, names.keptFor(query))
    }

    @Test fun `Apply sends the fields' addresses, never the names they show`() {
        val names = AddressNames(from = picked, recipient = PickedName("sam@c.org", "Sam Roy"))
        val sheet = sheetFields("j.dupont@exemple.org", "sam@c.org", "Q3", 1L, 2L, names)

        assertEquals(
            SearchQuery(from = "j.dupont@exemple.org", recipient = "sam@c.org", subject = "Q3", afterMillis = 1L, beforeMillis = 2L),
            sheet.query,
        )
        assertEquals(names, sheet.names)
        assertEquals(
            "a name no longer standing for its field is not handed over",
            AddressNames(from = picked),
            sheetFields("j.dupont@exemple.org", "other@c.org", "", null, null, names).names,
        )
    }

    @Test fun `the sheet's names survive a rotation, each with its own address`() {
        val names = AddressNames(from = PickedName("ann@a.org", "Ann A"), recipient = PickedName("j.dupont@exemple.org", "Jeanne Dupont"))
        val saved = with(AddressNamesSaver) { SaverScope { true }.save(names) }!!
        assertEquals(names, AddressNamesSaver.restore(saved))
    }

    @Test fun `after a pick the cursor sits at the end of the address, and typing keeps its own cursor`() {
        val typed = TextFieldValue("an", TextRange(2))

        assertEquals(TextRange(9), typed.showing("ann@a.org").selection)
        assertEquals(typed, typed.showing("an"))
    }
}

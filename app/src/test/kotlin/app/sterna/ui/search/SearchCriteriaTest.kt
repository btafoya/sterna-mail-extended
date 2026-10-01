package app.sterna.ui.search

import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.inbox.SearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The search's chips and the Filters sheet's criteria, executed. */
class SearchCriteriaTest {

    private val everything = SearchQuery(
        text = "invoice",
        from = "alex@example.org",
        recipient = "sam@example.org",
        subject = "Q3",
        hasAttachment = true,
        flagged = true,
        unread = true,
        afterMillis = 1_000L,
        beforeMillis = 2_000L,
    )

    @Test fun `each toggle flips its own criterion and no other`() {
        for (toggle in SearchToggle.entries) {
            val flipped = SearchQuery().toggled(toggle)
            assertTrue("$toggle", flipped.has(toggle))
            SearchToggle.entries.filter { it != toggle }.forEach { assertFalse("$toggle set $it", flipped.has(it)) }
            assertEquals("$toggle twice", SearchQuery(), flipped.toggled(toggle))
        }
        assertTrue(SearchQuery().toggled(SearchToggle.UNREAD).unread)
        assertTrue(SearchQuery().toggled(SearchToggle.FLAGGED).flagged)
        assertTrue(SearchQuery().toggled(SearchToggle.ATTACHMENT).hasAttachment)
    }

    @Test fun `the toggles wear the list's own words`() {
        assertEquals(
            listOf(app.sterna.R.string.filter_unread, app.sterna.R.string.filter_starred, app.sterna.R.string.filter_attachment),
            SearchToggle.entries.map(::searchToggleLabel),
        )
    }

    @Test fun `every sheet criterion is a chip, in the sheet's order, and the toggles and words are not`() {
        assertEquals(
            listOf(
                SearchFieldChip(SearchField.FROM, text = "alex@example.org"),
                SearchFieldChip(SearchField.RECIPIENT, text = "sam@example.org"),
                SearchFieldChip(SearchField.SUBJECT, text = "Q3"),
                SearchFieldChip(SearchField.SINCE, millis = 1_000L),
                SearchFieldChip(SearchField.UNTIL, millis = 2_000L),
            ),
            searchFieldChips(everything, AddressNames()),
        )
        assertTrue(searchFieldChips(SearchQuery(text = "invoice", unread = true, flagged = true, hasAttachment = true), AddressNames()).isEmpty())
    }

    @Test fun `a blank field is no chip, and a chip shows the value trimmed`() {
        assertTrue(searchFieldChips(SearchQuery(from = "   ", recipient = "\t", subject = " "), AddressNames()).isEmpty())
        assertEquals("alex@example.org", searchFieldChips(SearchQuery(from = "  alex@example.org  "), AddressNames()).single().text)
    }

    private val alex = AddressNames(from = PickedName("alex@example.org", "Alex Martin"))

    @Test fun `an address chip shows the picked name, and the address for anything else`() {
        val chips = searchFieldChips(everything, alex)
        assertEquals(SearchFieldChip(SearchField.FROM, text = "Alex Martin"), chips[0])
        assertEquals(SearchFieldChip(SearchField.RECIPIENT, text = "sam@example.org"), chips[1])
        assertEquals(
            "a name picked for another address is not shown",
            "bob@example.org",
            searchFieldChips(everything.copy(from = "bob@example.org"), alex)[0].text,
        )
    }

    @Test fun `the From chip's cross takes the name with the address`() {
        val left = everything.without(SearchField.FROM)
        assertEquals(AddressNames(), alex.keptFor(left))
        assertEquals(alex, alex.keptFor(everything.without(SearchField.RECIPIENT)))
    }

    @Test fun `a picked name survives a process death, and the restored criterion is still the address`() {
        val names = alex.copy(recipient = PickedName("sam@example.org", "Sam Roy"))
        val handle = HashMap(savedSearch(active = true, criteria = everything, scope = SearchScope.ALL_ACCOUNTS, names = names))

        assertEquals(names, savedSearchNames(handle::get))
        assertEquals(everything, savedSearchCriteria(handle::get))
        assertEquals("alex@example.org", savedSearchCriteria(handle::get).from)
        assertEquals(AddressNames(), savedSearchNames(emptyMap<String, Any?>()::get))
        assertEquals(
            "a name no longer standing for its criterion is not kept",
            AddressNames(),
            savedSearchNames(HashMap(savedSearch(true, everything.copy(from = "bob@example.org"), SearchScope.ALL_ACCOUNTS, alex))::get),
        )
        assertEquals(
            "nor in To or Cc",
            alex,
            savedSearchNames(HashMap(savedSearch(true, everything.copy(recipient = "kim@example.org"), SearchScope.ALL_ACCOUNTS, names))::get),
        )
    }

    @Test fun `a chip's cross removes its criterion and nothing else`() {
        for (field in SearchField.entries) {
            val left = everything.without(field)
            assertEquals("$field", searchFieldChips(everything, AddressNames()).filter { it.field != field }, searchFieldChips(left, AddressNames()))
            assertEquals("$field kept the words", everything.text, left.text)
            SearchToggle.entries.forEach { assertTrue("$field kept $it", left.has(it)) }
        }
    }

    @Test fun `the sheet writes its five fields over the words and toggles of the moment`() {
        val now = SearchQuery(text = "receipt", unread = true, from = "old@example.org")
        val sheet = SearchQuery(text = "stale", flagged = true, recipient = "sam@example.org", afterMillis = 5L)
        assertEquals(
            SearchQuery(text = "receipt", unread = true, recipient = "sam@example.org", afterMillis = 5L),
            now.withFieldsOf(sheet),
        )
        assertEquals("Clear all", SearchQuery(text = "receipt", unread = true), now.withFieldsOf(SearchQuery()))
    }

    @Test fun `the results are drawn when the bar is open and asks something, a chip alone included`() {
        assertFalse(searchAsked(searching = false, criteria = everything))
        assertFalse(searchAsked(searching = true, criteria = SearchQuery(text = "   ")))
        assertTrue(searchAsked(searching = true, criteria = SearchQuery(text = "invoice")))
        assertTrue(searchAsked(searching = true, criteria = SearchQuery(unread = true)))
        assertTrue(searchAsked(searching = true, criteria = SearchQuery(recipient = "sam@example.org")))
    }

    @Test fun `an open search survives a process death, every criterion of it`() {
        val handle = HashMap(savedSearch(active = true, criteria = everything, scope = SearchScope.ALL_ACCOUNTS, names = AddressNames()))
        assertTrue(savedSearchActive(handle::get))
        assertEquals(everything, savedSearchCriteria(handle::get))
        assertEquals(SearchScope.ALL_ACCOUNTS, savedSearchScope(handle::get))
        assertEquals(
            SearchScope.THIS_ACCOUNT,
            savedSearchScope(HashMap(savedSearch(active = true, criteria = everything, scope = SearchScope.THIS_ACCOUNT, names = AddressNames()))::get),
        )
    }

    @Test fun `nothing saved restores a closed bar asking nothing`() {
        val empty = emptyMap<String, Any?>()
        assertFalse(savedSearchActive(empty::get))
        assertEquals(SearchQuery(), savedSearchCriteria(empty::get))
        assertNull("no scope kept: the view decides it again", savedSearchScope(empty::get))
    }
}

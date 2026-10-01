package app.sterna.core.data.search

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two decisions of the search history, executed: what a new search does to the list, and what
 *  the list offers for what is being typed. */
class SearchHistoryTest {

    // -- recording -----------------------------------------------------------------------------

    @Test fun `a search goes to the head of the list, trimmed`() {
        assertEquals(listOf("facture", "loyer"), withSearch(listOf("loyer"), "  facture  "))
    }

    @Test fun `a blank search is not a search`() {
        val history = listOf("loyer")
        assertEquals(history, withSearch(history, ""))
        assertEquals(history, withSearch(history, "   \n "))
    }

    @Test fun `a search already kept moves to the head with its latest spelling`() {
        assertEquals(
            listOf("Facture", "loyer", "impots"),
            withSearch(listOf("loyer", "facture", "impots"), "Facture"),
        )
    }

    @Test fun `case and accents do not make a second entry`() {
        assertEquals(listOf("HELENE"), withSearch(listOf("Hélène"), "HELENE"))
        assertEquals(listOf("écologie"), withSearch(listOf("Ecologie"), "écologie"))
        assertEquals(listOf("анна"), withSearch(listOf("Анна"), "анна"))
    }

    @Test fun `two different searches are both kept`() {
        assertEquals(listOf("factures", "facture"), withSearch(listOf("facture"), "factures"))
    }

    @Test fun `the list keeps twenty searches and drops the oldest`() {
        val full = (1..SEARCH_HISTORY_LIMIT).map { "q$it" }
        val after = withSearch(full, "new")
        assertEquals(20, SEARCH_HISTORY_LIMIT)
        assertEquals(SEARCH_HISTORY_LIMIT, after.size)
        assertEquals("new", after.first())
        assertEquals("q19", after.last())
    }

    @Test fun `a repeated search in a full list drops nothing else`() {
        val full = (1..SEARCH_HISTORY_LIMIT).map { "q$it" }
        val after = withSearch(full, "Q20")
        assertEquals(listOf("Q20") + (1..19).map { "q$it" }, after)
    }

    // -- removing ------------------------------------------------------------------------------

    @Test fun `removing an entry keeps the others in their order`() {
        assertEquals(listOf("loyer", "impots"), withoutSearch(listOf("loyer", "facture", "impots"), "facture"))
    }

    @Test fun `removing ignores case and accents, as recording does`() {
        assertEquals(listOf("loyer"), withoutSearch(listOf("facture", "loyer"), "Facture"))
        assertEquals(listOf("loyer"), withoutSearch(listOf("Hélène", "loyer"), "HELENE"))
        assertEquals(listOf("loyer"), withoutSearch(listOf("loyer", "Ecologie"), "écologie"))
        assertEquals(emptyList<String>(), withoutSearch(listOf("Анна"), "анна"))
    }

    @Test fun `removing a search not kept changes nothing`() {
        val history = listOf("facture", "loyer")
        assertEquals(history, withoutSearch(history, "factures"))
        assertEquals(history, withoutSearch(history, "fac"))
        assertEquals(history, withoutSearch(history, ""))
        assertEquals(emptyList<String>(), withoutSearch(emptyList(), "facture"))
    }

    @Test fun `removing after a respelt search takes the latest spelling away`() {
        val after = withoutSearch(withSearch(listOf("loyer", "facture"), "FACTURE"), "facture")
        assertEquals(listOf("loyer"), after)
    }

    // -- proposing -----------------------------------------------------------------------------

    private val kept = listOf(
        "Hélène Martin", "facture EDF", "Анна", "loyer", "rapport annuel", "impots 2025",
        "banque", "assurance", "garage", "école",
    )

    @Test fun `an empty field offers the eight most recent`() {
        assertEquals(8, SUGGESTIONS_WHEN_EMPTY)
        assertEquals(kept.take(8), searchHistorySuggestions(kept, ""))
        assertEquals(kept.take(8), searchHistorySuggestions(kept, "   "))
    }

    @Test fun `typing offers at most three, most recent first`() {
        val history = listOf("abc one", "abc two", "abc three", "abc four")
        assertEquals(3, SUGGESTIONS_WHILE_TYPING)
        assertEquals(listOf("abc one", "abc two", "abc three"), searchHistorySuggestions(history, "ab"))
    }

    @Test fun `a word has to start with what is typed`() {
        assertEquals(listOf("rapport annuel"), searchHistorySuggestions(kept, "ann"))
        assertEquals(emptyList<String>(), searchHistorySuggestions(kept, "nuel"))
        assertEquals(listOf("facture EDF"), searchHistorySuggestions(kept, "ed"))
    }

    @Test fun `accents and case are ignored while typing`() {
        assertEquals(listOf("Hélène Martin"), searchHistorySuggestions(kept, "hele"))
        assertEquals(listOf("école"), searchHistorySuggestions(kept, "ECO"))
        assertEquals(listOf("Анна"), searchHistorySuggestions(kept, "анн"))
    }

    @Test fun `several typed words match across the words of an entry`() {
        assertEquals(listOf("Hélène Martin"), searchHistorySuggestions(kept, "helene mar"))
    }

    @Test fun `the search being typed is not offered back to itself`() {
        assertEquals(emptyList<String>(), searchHistorySuggestions(kept, "LOYER"))
        assertEquals(listOf("loyers"), searchHistorySuggestions(listOf("loyer", "loyers"), "loyer"))
    }

    // -- the stored form -----------------------------------------------------------------------

    @Test fun `any text comes back from storage as it went in, in order`() {
        val history = listOf(
            "\"quoted\" words", "a, b, c", "two\nlines", "tab\there", "back\\slash", "📎 pièce jointe",
            "[\"not\", \"an array\"]", "",
        )
        assertEquals(history, decodeSearchHistory(encodeSearchHistory(history)))
    }

    @Test fun `an unreadable stored value reads as no history`() {
        assertEquals(emptyList<String>(), decodeSearchHistory(null))
        assertEquals(emptyList<String>(), decodeSearchHistory("not json"))
        assertEquals(emptyList<String>(), decodeSearchHistory("{\"a\":1}"))
        assertEquals(emptyList<String>(), decodeSearchHistory("[1, {\"a\": 2}]"))
    }
}

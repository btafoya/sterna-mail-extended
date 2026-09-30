package app.sterna.ui.search

import app.sterna.ui.inbox.SearchUi
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the inbox's search bar offers, executed. The history's own matching is `SearchHistoryTest`'s. */
class BarSuggestionsTest {

    private val kept = listOf("facture", "loyer", "impots")

    @Test fun `an empty field in an open bar offers the recent searches`() {
        assertEquals(kept, barSuggestions(kept, SearchUi(active = true), selecting = false))
    }

    @Test fun `while typing, the matches are offered`() {
        assertEquals(
            listOf("facture"),
            barSuggestions(kept, SearchUi(active = true, query = "fac", typing = true), selecting = false),
        )
    }

    @Test fun `once the text was kept as a search, nothing is offered over its results`() {
        assertEquals(
            "after the Search key, an opened result or a picked suggestion, the matches must not " +
                "stay stuck above the results",
            emptyList<String>(),
            barSuggestions(kept, SearchUi(active = true, query = "fac", typing = false), selecting = false),
        )
    }

    @Test fun `a selection hides every suggestion, typed or not`() {
        assertEquals(
            "a pick during a selection swaps the list under it, and Delete then hits rows no longer shown",
            emptyList<String>(),
            barSuggestions(kept, SearchUi(active = true, query = "fac", typing = true), selecting = true),
        )
        assertEquals(
            emptyList<String>(),
            barSuggestions(kept, SearchUi(active = true), selecting = true),
        )
    }

    @Test fun `a closed bar offers nothing`() {
        assertEquals(emptyList<String>(), barSuggestions(kept, SearchUi(active = false, typing = true), selecting = false))
    }
}

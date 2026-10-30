package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bar's field is a `TextField` on a `String` (#277): a keystroke whose text is not back in the
 * field before the next one reverts it and leaves the cursor behind. No `advanceUntilIdle` or
 * `runCurrent` here, on purpose: under an unconfined dispatcher, a value that needs the scheduler
 * to move is exactly a value that arrives late.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFieldTextTest {

    @Test fun `each keystroke is in the field text before the next one`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow(SearchUi(active = true))
        val fieldText = fieldTextOf(state, backgroundScope)
        backgroundScope.launch { fieldText.collect {} }

        val seen = listOf("S", "St", "Ste").map { typed ->
            state.value = state.value.copy(criteria = state.value.criteria.copy(text = typed), typing = true)
            fieldText.value
        }

        assertEquals(listOf("S", "St", "Ste"), seen)
    }

    @Test fun `results landing do not move the field text`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow(SearchUi(active = true, criteria = SearchQuery(text = "St")))
        val fieldText = fieldTextOf(state, backgroundScope)
        backgroundScope.launch { fieldText.collect {} }

        state.value = state.value.copy(results = listOf(Email(id = "M1", accountId = "A")))

        assertEquals("St", fieldText.value)
    }

    @Test fun `the field opens on the text already asked, before anything collects it`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow(SearchUi(active = true, criteria = SearchQuery(text = "invoice")))

        assertEquals("invoice", fieldTextOf(state, backgroundScope).value)
    }
}

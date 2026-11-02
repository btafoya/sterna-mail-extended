package app.sterna.contacts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Executes [senderNameBook], and drives [SenderNames] with a fake address book and a fake observer. */
@OptIn(ExperimentalCoroutinesApi::class)
class SenderNamesTest {

    @Test fun `a contact without a name writes no entry`() {
        assertEquals(emptyMap<String, String>(), senderNameBook(listOf(ContactSuggestion("a@x.org", null))))
    }

    @Test fun `a name that only repeats the address, in any case and spacing, writes no entry`() {
        assertEquals(
            emptyMap<String, String>(),
            senderNameBook(listOf(ContactSuggestion("Ann@X.org", "  ann@x.ORG "))),
        )
    }

    @Test fun `keys are the trimmed lowercase address, the name is trimmed`() {
        assertEquals(
            mapOf("ann@x.org" to "Ann Smith"),
            senderNameBook(listOf(ContactSuggestion(" Ann@X.Org ", "  Ann Smith "))),
        )
    }

    @Test fun `the first contact for an address wins`() {
        assertEquals(
            mapOf("ann@x.org" to "Ann"),
            senderNameBook(listOf(ContactSuggestion("ann@x.org", "Ann"), ContactSuggestion("ANN@x.org", "Annie"))),
        )
    }

    @Test fun `a name containing the address is still a name`() {
        assertEquals(
            mapOf("ann@x.org" to "Ann <ann@x.org>"),
            senderNameBook(listOf(ContactSuggestion("ann@x.org", "Ann <ann@x.org>"))),
        )
    }

    private class FakeBook(var contacts: List<ContactSuggestion>) {
        var reads = 0
        var onChange: (() -> Unit)? = null
        var watching = false
        suspend fun read(): List<ContactSuggestion> { reads++; return contacts }
        fun watch(changed: () -> Unit): () -> Unit {
            onChange = changed
            watching = true
            return { watching = false; onChange = null }
        }
    }

    @Test fun `setting off reads nothing, watches nothing, and answers an empty book`() = runTest {
        val book = FakeBook(listOf(ContactSuggestion("ann@x.org", "Ann")))
        val names = SenderNames(backgroundScope, MutableStateFlow(false), book::read, book::watch, UnconfinedTestDispatcher(testScheduler))
        assertEquals(emptyMap<String, String>(), names.current())
        assertEquals(0, book.reads)
        assertFalse(book.watching)
    }

    @Test fun `setting on loads the book, and a change in the contacts reloads it`() = runTest {
        val book = FakeBook(listOf(ContactSuggestion("ann@x.org", "Ann")))
        val names = SenderNames(backgroundScope, MutableStateFlow(true), book::read, book::watch, UnconfinedTestDispatcher(testScheduler))
        assertEquals(mapOf("ann@x.org" to "Ann"), names.names.value)
        assertTrue(book.watching)
        book.contacts = listOf(ContactSuggestion("ann@x.org", "Ann Smith"))
        book.onChange!!.invoke()
        assertEquals(mapOf("ann@x.org" to "Ann Smith"), names.names.value)
        assertEquals(2, book.reads)
    }

    @Test fun `turning the setting off empties the book and stops watching`() = runTest {
        val book = FakeBook(listOf(ContactSuggestion("ann@x.org", "Ann")))
        val enabled = MutableStateFlow(true)
        val names = SenderNames(backgroundScope, enabled, book::read, book::watch, UnconfinedTestDispatcher(testScheduler))
        assertTrue(book.watching)
        enabled.value = false
        assertEquals(emptyMap<String, String>(), names.names.value)
        assertFalse(book.watching)
    }

    @Test fun `current waits for the first read when the setting is on`() = runTest(UnconfinedTestDispatcher()) {
        val book = FakeBook(listOf(ContactSuggestion("ann@x.org", "Ann")))
        val enabled = MutableStateFlow<Boolean?>(null)
        val gate = MutableStateFlow(false)
        val names = SenderNames(
            backgroundScope,
            enabled.filterNotNull(),
            { gate.first { it }; book.read() },
            book::watch,
            UnconfinedTestDispatcher(testScheduler),
        )
        val answer = async { names.current() }
        assertFalse("nothing read yet: current() must not answer", answer.isCompleted)
        enabled.value = true
        assertFalse("setting on, read pending: current() must not answer an empty book", answer.isCompleted)
        gate.value = true
        assertEquals(mapOf("ann@x.org" to "Ann"), answer.await())
    }
}

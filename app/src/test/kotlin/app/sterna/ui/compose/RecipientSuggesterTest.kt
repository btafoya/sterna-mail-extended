package app.sterna.ui.compose

import app.sterna.core.data.db.ContactRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The menu while searches are in flight: each server-book read is held on a gate the test opens, so a
 * late answer is played after the keystroke, the dismissal or the sender switch that should retire it.
 */
class RecipientSuggesterTest {
    private val gates = mutableMapOf<Pair<String, String>, CompletableDeferred<List<ContactRow>>>()

    private fun gate(account: String, query: String) =
        gates.getOrPut(account to query) { CompletableDeferred() }

    private fun suggester(scope: CoroutineScope) = RecipientSuggester(
        scope = scope,
        recent = { _, _ -> emptyList() },
        serverBook = { account, query, _ -> gate(account, query).await() },
    )

    private fun answer(account: String, query: String, vararg emails: String) {
        gate(account, query).complete(emails.map { ContactRow(it, null) })
    }

    private suspend fun settle() = repeat(10) { yield() }

    private fun RecipientSuggester.shown() = suggestions.value.map { it.email }

    @Test fun `an earlier keystroke answering last does not overwrite the later one`() = runBlocking {
        val menu = suggester(this)
        menu.suggest("an", sender = { "A" }, device = null)
        settle()
        menu.suggest("ann", sender = { "A" }, device = null)
        settle()

        answer("A", "ann", "ann@a.org")
        settle()
        answer("A", "an", "andrew@a.org")
        settle()

        assertEquals(listOf("ann@a.org"), menu.shown())
    }

    @Test fun `a search still in flight does not reopen a dismissed menu`() = runBlocking {
        val menu = suggester(this)
        menu.suggest("an", sender = { "A" }, device = null)
        settle()

        menu.clear()
        answer("A", "an", "ann@a.org")
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `erasing below two characters retires the search in flight`() = runBlocking {
        val menu = suggester(this)
        menu.suggest("an", sender = { "A" }, device = null)
        settle()

        menu.suggest("a", sender = { "A" }, device = null)
        answer("A", "an", "ann@a.org")
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `switching the sender empties the menu of the previous sender's book`() = runBlocking {
        val menu = suggester(this)
        answer("A", "an", "ann@a.org")
        menu.suggest("an", sender = { "A" }, device = null)
        settle()
        assertEquals(listOf("ann@a.org"), menu.shown())

        menu.senderChanged()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `a search for A answering after the switch to B is dropped`() = runBlocking {
        val menu = suggester(this)
        menu.suggest("an", sender = { "A" }, device = null)
        settle()

        menu.senderChanged()
        answer("A", "an", "ann@a.org")
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `an answer whose account is no longer the sender is dropped, even unannounced`() = runBlocking {
        var sender = "A"
        val menu = suggester(this)
        menu.suggest("an", sender = { sender }, device = null)
        settle()

        sender = "B"
        answer("A", "an", "ann@a.org")
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `the book is read for the sender of the keystroke`() = runBlocking {
        val menu = suggester(this)
        answer("B", "an", "anna@b.org")
        menu.suggest("an", sender = { "B" }, device = null)
        settle()

        assertEquals(listOf("anna@b.org"), menu.shown())
    }
}

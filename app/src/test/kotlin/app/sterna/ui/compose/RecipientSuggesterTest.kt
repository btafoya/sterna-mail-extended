package app.sterna.ui.compose

import app.sterna.contacts.PoolContact
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The menu while pools are being read: each read is held on a gate per account the test opens, so
 * a late pool is played after the keystroke, the dismissal or the sender switch that should retire it.
 */
class RecipientSuggesterTest {
    private val gates = mutableMapOf<String?, MutableList<CompletableDeferred<List<PoolContact>>>>()
    private val reads = mutableListOf<String?>()
    private var sender: String? = "A"

    private fun contact(email: String, sent: Int = 0) = PoolContact(email, null, null, sent, if (sent > 0) 1 else 0, 0, 0, sent > 0)

    /** Opens every read of [account] waiting so far, and every later one, on [pool]. */
    private val opened = mutableMapOf<String?, List<PoolContact>>()

    private fun answer(account: String?, vararg pool: PoolContact) {
        opened[account] = pool.toList()
        gates[account].orEmpty().forEach { it.complete(pool.toList()) }
    }

    private fun suggester(scope: CoroutineScope) = RecipientSuggester(
        scope = scope,
        sender = { sender },
        loadPool = { account ->
            reads += account
            opened[account] ?: CompletableDeferred<List<PoolContact>>().also { gates.getOrPut(account) { mutableListOf() } += it }.await()
        },
        now = { 1_000L },
    )

    private fun test(block: suspend (RecipientSuggester) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            block(suggester(scope))
        } finally {
            scope.cancel()
        }
    }

    private suspend fun settle() = repeat(10) { yield() }

    private fun RecipientSuggester.shown() = suggestions.value.map { it.email }

    @Test fun `a keystroke before the pool waits for it, it does not rank an empty one`() = test { menu ->
        menu.suggest("a", emptySet())
        settle()
        answer("A", contact("ann@a.org"))
        settle()

        assertEquals(listOf("ann@a.org"), menu.shown())
    }

    @Test fun `one character is enough`() = test { menu ->
        answer("A", contact("ann@a.org"), contact("bob@a.org"))
        menu.suggest("b", emptySet())
        settle()

        assertEquals(listOf("bob@a.org"), menu.shown())
    }

    @Test fun `keystrokes rank the pool in memory, they read nothing`() = test { menu ->
        answer("A", contact("ann@a.org"), contact("anna@a.org"))
        menu.focused("", emptySet())
        settle()
        menu.suggest("a", emptySet())
        menu.suggest("an", emptySet())
        menu.suggest("anna", emptySet())
        settle()

        assertEquals(listOf("anna@a.org"), menu.shown())
        assertEquals(listOf<String?>("A"), reads)
    }

    @Test fun `each focus reads the pool afresh, so what changed meanwhile comes in`() = test { menu ->
        answer("A", contact("ann@a.org", sent = 1))
        menu.focused("", emptySet())
        settle()
        answer("A", contact("ann@a.org", sent = 1), contact("new@a.org", sent = 5))
        menu.focused("", emptySet())
        settle()

        assertEquals(listOf<String?>("A", "A"), reads)
        assertEquals(listOf("new@a.org", "ann@a.org"), menu.shown())
    }

    @Test fun `a source switched on while the field holds the focus is read, and the menu on screen stays`() = test { menu ->
        answer("A", contact("ann@a.org", sent = 1))
        menu.focused("", emptySet())
        settle()
        assertEquals(listOf("ann@a.org"), menu.shown())

        answer("A", contact("ann@a.org", sent = 1), contact("phone@a.org"))
        menu.sourcesChanged()
        settle()
        assertEquals(listOf("ann@a.org"), menu.shown())

        menu.suggest("ph", emptySet())
        settle()
        assertEquals(listOf("phone@a.org"), menu.shown())
        assertEquals(listOf<String?>("A", "A"), reads)
    }

    @Test fun `a source switched while the first pool is still read lets the waiting menu arrive`() = test { menu ->
        menu.focused("", emptySet())
        settle()
        menu.sourcesChanged()
        settle()
        answer("A", contact("ann@a.org", sent = 1))
        settle()

        assertEquals(listOf("ann@a.org"), menu.shown())
    }

    @Test fun `a source switched before any focus reads nothing`() = test { menu ->
        menu.sourcesChanged()
        settle()

        assertEquals(emptyList<String?>(), reads)
    }

    @Test fun `a focus on an empty field offers the usual recipients, on a half-typed one nothing`() = test { menu ->
        answer("A", contact("ann@a.org", sent = 2), contact("news@a.org"))
        menu.focused("bob@a.org, ", setOf("bob@a.org"))
        settle()
        assertEquals(listOf("ann@a.org"), menu.shown())

        menu.focused("bob@a.org, an", setOf("bob@a.org"))
        settle()
        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `what is already on screen is handed to the ranking`() = test { menu ->
        answer("A", contact("ann@a.org"), contact("anna@a.org"))
        menu.suggest("ann", setOf("ann@a.org"))
        settle()

        assertEquals(listOf("anna@a.org"), menu.shown())
    }

    @Test fun `a pool still in flight does not reopen a dismissed menu`() = test { menu ->
        menu.suggest("an", emptySet())
        settle()

        menu.clear()
        answer("A", contact("ann@a.org"))
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `erasing the last character retires the search in flight`() = test { menu ->
        menu.suggest("a", emptySet())
        settle()

        menu.suggest("", emptySet())
        answer("A", contact("ann@a.org"))
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `switching the sender empties the menu and reads the new sender's pool`() = test { menu ->
        answer("A", contact("ann@a.org"))
        menu.suggest("an", emptySet())
        settle()
        assertEquals(listOf("ann@a.org"), menu.shown())

        sender = "B"
        menu.senderChanged()
        settle()

        assertEquals(emptyList<String>(), menu.shown())
        assertEquals(listOf<String?>("A", "B"), reads)
    }

    @Test fun `a pool of A answering after the switch to B is dropped`() = test { menu ->
        menu.suggest("an", emptySet())
        settle()

        sender = "B"
        menu.senderChanged()
        answer("A", contact("ann@a.org"))
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `a pool whose account is no longer the sender is dropped, even unannounced`() = test { menu ->
        menu.suggest("an", emptySet())
        settle()

        sender = "B"
        answer("A", contact("ann@a.org"))
        settle()

        assertEquals(emptyList<String>(), menu.shown())
    }

    @Test fun `a keystroke after an unannounced switch reads the new sender's pool`() = test { menu ->
        answer("A", contact("ann@a.org"))
        answer("B", contact("anna@b.org"))
        menu.suggest("an", emptySet())
        settle()

        sender = "B"
        menu.suggest("ann", emptySet())
        settle()

        assertEquals(listOf("anna@b.org"), menu.shown())
        assertEquals(listOf<String?>("A", "B"), reads)
    }
}

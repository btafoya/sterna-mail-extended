package app.sterna.ui.compose

import app.sterna.contacts.ContactSuggestion
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.db.ContactRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server address book is per account while the other two sources are not: these tests hold two
 * accounts with two books and check which book reaches the menu for the sender picked in the composer.
 */
class RecipientSuggestionsTest {
    private val books = mapOf(
        "A" to listOf(ContactRow("ann@a.org", "Ann A")),
        "B" to listOf(ContactRow("anna@b.org", "Anna B")),
    )
    private val asked = mutableListOf<String>()
    private val serverBook: suspend (String, String, Int) -> List<ContactRow> = { account, _, _ ->
        asked += account
        books[account].orEmpty()
    }
    private val fromA = FromOption("A", StoredIdentity("ia", "Me", "me@a.org"))
    private val fromB = FromOption("B", StoredIdentity("ib", "Me", "me@b.org"))

    private fun suggest(
        selected: FromOption?,
        openedFor: String? = null,
        current: String? = null,
        device: (suspend (String, Int) -> List<ContactSuggestion>)? = null,
    ) = runBlocking {
        suggestRecipients(
            token = "an",
            senderAccountId = composingAccountId(selected, openedFor) { current },
            recent = { _, _ -> emptyList() },
            serverBook = serverBook,
            device = device,
        ).map { it.email }
    }

    @Test
    fun `the composer of A proposes A's book and never B's`() {
        assertEquals(listOf("ann@a.org"), suggest(fromA, openedFor = "B", current = "B"))
        assertEquals(listOf("A"), asked)
    }

    @Test
    fun `switching the sender to B makes the next suggestions follow B's book`() {
        assertEquals(listOf("ann@a.org"), suggest(fromA))
        assertEquals(listOf("anna@b.org"), suggest(fromB))
        assertEquals(listOf("A", "B"), asked)
    }

    @Test
    fun `without a picked sender the account the composer was opened for answers, then the current one`() {
        assertEquals(listOf("anna@b.org"), suggest(null, openedFor = "B", current = "A"))
        assertEquals(listOf("ann@a.org"), suggest(null, openedFor = null, current = "A"))
    }

    @Test
    fun `an account without a copy, an IMAP one, proposes nothing from the book`() {
        assertTrue(suggest(FromOption("imap", StoredIdentity("ii", "Me", "me@imap.org"))).isEmpty())
    }

    @Test
    fun `the server book is proposed without the contacts permission`() {
        assertEquals(listOf("ann@a.org"), suggest(fromA, device = null))
    }

    @Test
    fun `the three sources reach the menu in order`() {
        val emails = runBlocking {
            suggestRecipients(
                token = "an",
                senderAccountId = "A",
                recent = { _, _ -> listOf(ContactRow("seen@x.org", null)) },
                serverBook = serverBook,
                device = { _, _ -> listOf(ContactSuggestion("phone@x.org", "Phone", null)) },
            ).map { it.email }
        }
        assertEquals(listOf("seen@x.org", "ann@a.org", "phone@x.org"), emails)
    }
}

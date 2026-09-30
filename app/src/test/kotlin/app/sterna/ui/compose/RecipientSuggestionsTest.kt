package app.sterna.ui.compose

import app.sterna.contacts.ContactSuggestion
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the pool is read from. The server address book is per account while the other sources are
 * not: these tests hold two accounts with two books and check which book reaches the pool for the
 * sender picked in the composer, and that the phone's contacts are the only source the contacts
 * setting can take away.
 */
class RecipientSuggestionsTest {
    private val books = mapOf(
        "A" to listOf(ContactRow("ann@a.org", "Ann A")),
        "B" to listOf(ContactRow("anna@b.org", "Anna B")),
    )
    private val asked = mutableListOf<String>()
    private val serverBook: suspend (String) -> List<ContactRow> = { account ->
        asked += account
        books[account].orEmpty()
    }
    private val fromA = FromOption("A", StoredIdentity("ia", "Me", "me@a.org"))
    private val fromB = FromOption("B", StoredIdentity("ib", "Me", "me@b.org"))

    private fun pool(
        senderAccountId: String?,
        sent: List<SentContactRow> = emptyList(),
        received: List<ReceivedContactRow> = emptyList(),
        device: (suspend () -> List<ContactSuggestion>)? = null,
        selves: Set<String> = emptySet(),
    ) = runBlocking {
        loadRecipientPool(senderAccountId, { sent }, { received }, serverBook, device, selves).map { it.email }
    }

    private fun bookFor(selected: FromOption?, openedFor: String? = null, current: String? = null) =
        pool(composingAccountId(selected, openedFor) { current })

    @Test
    fun `the composer of A reads A's book and never B's`() {
        assertEquals(listOf("ann@a.org"), bookFor(fromA, openedFor = "B", current = "B"))
        assertEquals(listOf("A"), asked)
    }

    @Test
    fun `switching the sender to B makes the next pool follow B's book`() {
        assertEquals(listOf("ann@a.org"), bookFor(fromA))
        assertEquals(listOf("anna@b.org"), bookFor(fromB))
        assertEquals(listOf("A", "B"), asked)
    }

    @Test
    fun `without a picked sender the account the composer was opened for answers, then the current one`() {
        assertEquals(listOf("anna@b.org"), bookFor(null, openedFor = "B", current = "A"))
        assertEquals(listOf("ann@a.org"), bookFor(null, openedFor = null, current = "A"))
    }

    @Test
    fun `an account without a copy, an IMAP one, brings nothing from the book`() {
        assertTrue(bookFor(FromOption("imap", StoredIdentity("ii", "Me", "me@imap.org"))).isEmpty())
    }

    @Test
    fun `without the phone's contacts the book, the sent and the received all still come`() {
        assertEquals(
            setOf("ann@a.org", "sent@x.org", "from@x.org"),
            pool(
                "A",
                sent = listOf(SentContactRow("sent@x.org", null, 1, 1)),
                received = listOf(ReceivedContactRow("from@x.org", null, 1, 1)),
                device = null,
            ).toSet(),
        )
    }

    @Test
    fun `the phone's contacts come in only when handed a reader`() {
        var reads = 0
        val phone: suspend () -> List<ContactSuggestion> = { reads++; listOf(ContactSuggestion("phone@x.org", "Phone")) }
        assertEquals(setOf("ann@a.org", "phone@x.org"), pool("A", device = phone).toSet())
        assertEquals(1, reads)
    }

    @Test
    fun `her own addresses never enter the pool`() {
        assertEquals(
            listOf("ann@a.org"),
            pool("A", received = listOf(ReceivedContactRow("me@a.org", "Me", 4, 1)), selves = setOf("me@a.org")),
        )
    }

    @Test
    fun `her addresses are every login and every identity of every account, lowercased`() {
        val accounts = listOf(StoredAccount("A", "https://a", "Login@A.org"), StoredAccount("B", "https://b", "b-login"))
        val identities = mapOf(
            "A" to listOf(StoredIdentity("1", "Me", "me@a.org"), StoredIdentity("2", "Alias", " Alias@A.org ")),
            "B" to listOf(StoredIdentity("3", "Me", "me@b.org")),
        )
        assertEquals(
            setOf("login@a.org", "me@a.org", "alias@a.org", "b-login", "me@b.org"),
            ownAddresses(accounts) { identities[it].orEmpty() },
        )
    }

    @Test
    fun `the addresses on screen are the chips of the three fields, the typed text left out`() {
        assertEquals(
            setOf("ann@x.org", "bob@x.org", "cat@x.org"),
            recipientsOnScreen("Ann@X.org, Bob <BOB@x.org>, still typ", "cat@x.org, ", "dan"),
        )
    }
}

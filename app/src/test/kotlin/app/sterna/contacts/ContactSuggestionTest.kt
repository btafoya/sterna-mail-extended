package app.sterna.contacts

import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.ReceivedContactRow
import app.sterna.core.data.db.SentContactRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How [buildRecipientPool] folds the four sources into one line per address: which name and which
 * photo (else a monogram is drawn) that line carries, and who is left out before any ranking.
 */
class ContactSuggestionTest {
    private fun sent(email: String, name: String? = null, count: Int = 1, at: Long = 1) = SentContactRow(email, name, count, at)
    private fun received(email: String, name: String? = null, count: Int = 1, at: Long = 1) = ReceivedContactRow(email, name, count, at)
    private fun phone(email: String, name: String? = null, photo: String? = null) = ContactSuggestion(email, name, photo)

    private fun pool(
        sent: List<SentContactRow> = emptyList(),
        received: List<ReceivedContactRow> = emptyList(),
        book: List<ContactRow> = emptyList(),
        phone: List<ContactSuggestion> = emptyList(),
        selves: Set<String> = emptySet(),
    ) = buildRecipientPool(sent, received, book, phone, selves)

    @Test
    fun `the name comes from the phone, then the server book, then what she wrote, then the newest reception`() {
        val all = pool(
            sent = listOf(sent("a@x.org", "Sent A"), sent("b@x.org", "Sent B"), sent("c@x.org", "Sent C")),
            received = listOf(received("a@x.org", "From A"), received("b@x.org", "From B"), received("c@x.org", "From C"), received("d@x.org", "From D")),
            book = listOf(ContactRow("a@x.org", "Book A"), ContactRow("b@x.org", "Book B")),
            phone = listOf(phone("a@x.org", "Phone A")),
        )
        assertEquals(
            mapOf("a@x.org" to "Phone A", "b@x.org" to "Book B", "c@x.org" to "Sent C", "d@x.org" to "From D"),
            all.associate { it.email to it.name },
        )
    }

    @Test
    fun `a sender's name does not hide the server book's`() {
        val one = pool(received = listOf(received("ada@x.org", "ADA NEWSLETTER")), book = listOf(ContactRow("ada@x.org", "Ada Lovelace"))).single()
        assertEquals("Ada Lovelace", one.name)
    }

    @Test
    fun `a blank name is no name, and the next source speaks`() {
        val one = pool(book = listOf(ContactRow("ada@x.org", "")), phone = listOf(phone("ada@x.org", "Ada Lovelace", "content://photo/1"))).single()
        assertEquals("Ada Lovelace", one.name)
        assertNull(pool(book = listOf(ContactRow("anon@x.org", ""))).single().name)
    }

    @Test
    fun `the photo only comes from the phone`() {
        val all = pool(sent = listOf(sent("ada@x.org", "Ada")), phone = listOf(phone("bob@x.org", "Bob", "content://photo/b")))
        assertEquals(mapOf("ada@x.org" to null, "bob@x.org" to "content://photo/b"), all.associate { it.email to it.photoUri })
    }

    @Test
    fun `one line per address whatever its case, the phone's first photo kept`() {
        val all = pool(
            sent = listOf(sent("ada@example.org", count = 2, at = 5)),
            received = listOf(received("ada@example.org", count = 3, at = 9)),
            phone = listOf(phone("ADA@example.org", "Ada", "content://photo/1"), phone("ada@example.org", "Ada bis", "content://photo/2")),
        )
        val ada = all.single()
        assertEquals("content://photo/1", ada.photoUri)
        assertEquals(listOf(2L, 5L, 3L, 9L), listOf(ada.sent.toLong(), ada.lastSent, ada.received.toLong(), ada.lastReceived))
    }

    @Test
    fun `known is written to, or in the book, or on the phone, and never only received from`() {
        val all = pool(
            sent = listOf(sent("wrote@x.org")),
            received = listOf(received("only@x.org"), received("book@x.org"), received("phone@x.org")),
            book = listOf(ContactRow("book@x.org", "B")),
            phone = listOf(phone("phone@x.org")),
        )
        assertEquals(
            mapOf("wrote@x.org" to true, "only@x.org" to false, "book@x.org" to true, "phone@x.org" to true),
            all.associate { it.email to it.known },
        )
    }

    @Test
    fun `her own addresses leave, matched exactly, and a sub-address of hers stays`() {
        val all = pool(
            sent = listOf(sent("me@x.org"), sent("me+test@x.org")),
            received = listOf(received("Me@X.org"), received("alias@x.org")),
            selves = setOf("me@x.org", "alias@x.org"),
        )
        assertEquals(listOf("me+test@x.org"), all.map { it.email })
    }

    @Test
    fun `machine senders leave, whatever their tag or case`() {
        val machines = listOf(
            "noreply@x.org", "no-reply+abc@x.org", "No_Reply-billing@x.org", "donotreply@x.org",
            "do-not-reply@x.org", "do_not_reply@x.org", "MAILER-DAEMON@x.org", "postmaster@x.org",
            "bounce@x.org", "bounces+123@x.org",
        )
        val all = pool(received = (machines + "bouncer@x.org" + "reply@x.org").map { received(it) })
        assertEquals(listOf("bouncer@x.org", "reply@x.org"), all.map { it.email })
    }

    @Test
    fun `a machine-looking address she has written to stays`() {
        val all = pool(sent = listOf(sent("noreply@x.org")), book = listOf(ContactRow("postmaster@x.org", "Postmaster")))
        assertEquals(listOf("noreply@x.org"), all.map { it.email })
    }

    @Test
    fun `an entry without an at sign is no address`() {
        assertEquals(emptyList<PoolContact>(), pool(phone = listOf(phone("Ada")), book = listOf(ContactRow(" ", "x"))))
    }
}

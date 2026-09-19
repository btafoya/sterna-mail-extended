package app.sterna.contacts

import app.sterna.core.data.db.ContactRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The suggestion menu draws a photo when [ContactSuggestion.photoUri] is set and a monogram
 * otherwise, so what these tests pin down is which rows end up with one.
 */
class ContactSuggestionTest {
    private fun row(email: String, name: String? = null) = ContactRow(email, name)

    @Test
    fun `local-only rows have no photo`() {
        val merged = mergeSuggestions(
            local = listOf(row("ada@example.org", "Ada"), row("bob@example.org")),
            server = emptyList(),
            device = emptyList(),
            limit = 6,
        )
        assertEquals(listOf("ada@example.org", "bob@example.org"), merged.map { it.email })
        assertEquals(listOf(null, null), merged.map { it.photoUri })
    }

    @Test
    fun `address-book rows keep their photo`() {
        val merged = mergeSuggestions(
            local = emptyList(),
            server = emptyList(),
            device = listOf(ContactSuggestion("ada@example.org", "Ada", "content://photo/1")),
            limit = 6,
        )
        assertEquals("content://photo/1", merged.single().photoUri)
    }

    @Test
    fun `an address book contact without a picture falls back to the monogram`() {
        val merged = mergeSuggestions(
            local = emptyList(),
            server = emptyList(),
            device = listOf(ContactSuggestion("bob@example.org", "Bob", null)),
            limit = 6,
        )
        assertNull(merged.single().photoUri)
    }

    @Test
    fun `a recent recipient also in the address book takes its photo and name`() {
        val merged = mergeSuggestions(
            local = listOf(row("Ada@Example.org")),
            server = emptyList(),
            device = listOf(ContactSuggestion("ada@example.org", "Ada Lovelace", "content://photo/1")),
            limit = 6,
        )
        // One row, the local address kept verbatim, enriched by the address book.
        assertEquals(1, merged.size)
        assertEquals("Ada@Example.org", merged.single().email)
        assertEquals("Ada Lovelace", merged.single().name)
        assertEquals("content://photo/1", merged.single().photoUri)
    }

    @Test
    fun `a local name wins over the address book name`() {
        val merged = mergeSuggestions(
            local = listOf(row("ada@example.org", "Ada (work)")),
            server = emptyList(),
            device = listOf(ContactSuggestion("ada@example.org", "Ada Lovelace", "content://photo/1")),
            limit = 6,
        )
        assertEquals("Ada (work)", merged.single().name)
        assertEquals("content://photo/1", merged.single().photoUri)
    }

    @Test
    fun `local rows come first and the limit is honoured`() {
        val merged = mergeSuggestions(
            local = listOf(row("a@x.org"), row("b@x.org")),
            server = emptyList(),
            device = listOf(
                ContactSuggestion("c@x.org", "C", "content://photo/c"),
                ContactSuggestion("d@x.org", "D", null),
            ),
            limit = 3,
        )
        assertEquals(listOf("a@x.org", "b@x.org", "c@x.org"), merged.map { it.email })
    }

    @Test
    fun `duplicate device rows keep the first photo seen`() {
        val merged = mergeSuggestions(
            local = listOf(row("ada@example.org")),
            server = emptyList(),
            device = listOf(
                ContactSuggestion("ADA@example.org", "Ada", "content://photo/1"),
                ContactSuggestion("ada@example.org", "Ada bis", "content://photo/2"),
            ),
            limit = 6,
        )
        assertEquals(1, merged.size)
        assertEquals("content://photo/1", merged.single().photoUri)
    }

    @Test
    fun `seen addresses come first, then the server address book, then the device contacts`() {
        val merged = mergeSuggestions(
            local = listOf(row("seen@x.org")),
            server = listOf(row("book@x.org", "Book")),
            device = listOf(ContactSuggestion("phone@x.org", "Phone", null)),
            limit = 6,
        )
        assertEquals(listOf("seen@x.org", "book@x.org", "phone@x.org"), merged.map { it.email })
    }

    @Test
    fun `a server book address already seen or on the device is listed once, at its first place`() {
        val merged = mergeSuggestions(
            local = listOf(row("Ada@x.org")),
            server = listOf(row("ada@x.org", "Ada"), row("BOB@x.org", "Bob")),
            device = listOf(ContactSuggestion("bob@x.org", "Bob phone", "content://photo/b")),
            limit = 6,
        )
        assertEquals(listOf("Ada@x.org", "BOB@x.org"), merged.map { it.email })
    }

    @Test
    fun `the limit cuts across the three sources`() {
        val merged = mergeSuggestions(
            local = listOf(row("a@x.org"), row("b@x.org")),
            server = listOf(row("c@x.org"), row("d@x.org"), row("e@x.org")),
            device = listOf(ContactSuggestion("f@x.org", "F", null), ContactSuggestion("g@x.org", "G", null)),
            limit = 6,
        )
        assertEquals(listOf("a@x.org", "b@x.org", "c@x.org", "d@x.org", "e@x.org", "f@x.org"), merged.map { it.email })
    }

    @Test
    fun `a server card without a name shows no empty name`() {
        val merged = mergeSuggestions(
            local = emptyList(),
            server = listOf(row("anon@x.org", "")),
            device = emptyList(),
            limit = 6,
        )
        assertNull(merged.single().name)
    }

    @Test
    fun `a seen address with an empty name keeps it, as before the server book`() {
        val merged = mergeSuggestions(
            local = listOf(row("ada@x.org", "")),
            server = emptyList(),
            device = listOf(ContactSuggestion("ada@x.org", "Ada Lovelace", "content://photo/1")),
            limit = 6,
        )
        assertEquals("", merged.single().name)
    }

    @Test
    fun `a server card without a name takes the device contact's name`() {
        val merged = mergeSuggestions(
            local = emptyList(),
            server = listOf(row("ada@x.org", "")),
            device = listOf(ContactSuggestion("ada@x.org", "Ada Lovelace", "content://photo/1")),
            limit = 6,
        )
        assertEquals("Ada Lovelace", merged.single().name)
    }
}

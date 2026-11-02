package app.sterna.ui.components

import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Executes [withAddressBookName], then the list row's own decisions on what it returns. */
class AddressBookNameTest {

    private val book = mapOf("ann@x.org" to "Ann Smith")

    @Test fun `a sender in the book takes the saved name and keeps the address`() {
        assertEquals(
            EmailAddress(name = "Ann Smith", email = "ann@x.org"),
            withAddressBookName(EmailAddress(name = "ASMITH-CORP", email = "ann@x.org"), book),
        )
    }

    @Test fun `the From address matches whatever its case and surrounding spaces, and is kept as sent`() {
        assertEquals(
            EmailAddress(name = "Ann Smith", email = "  ANN@X.org "),
            withAddressBookName(EmailAddress(name = null, email = "  ANN@X.org "), book),
        )
    }

    @Test fun `a sender not in the book is returned untouched`() {
        val sender = EmailAddress(name = "Bob", email = "bob@x.org")
        assertSame(sender, withAddressBookName(sender, book))
    }

    @Test fun `a nameless sender not in the book still shows its address`() {
        val shown = withAddressBookName(EmailAddress(name = null, email = "bob@x.org"), book)
        assertEquals("bob@x.org", shown?.display())
        assertNull(listAddressBesideName(shown, rowShowsRecipients = false))
    }

    @Test fun `an empty book changes nothing`() {
        val sender = EmailAddress(name = "Ann", email = "ann@x.org")
        assertSame(sender, withAddressBookName(sender, emptyMap()))
    }

    @Test fun `no sender stays no sender`() {
        assertNull(withAddressBookName(null, book))
    }

    @Test fun `a nameless sender found in the book gets its grey address beside the saved name`() {
        val shown = withAddressBookName(EmailAddress(name = null, email = "ann@x.org"), book)
        assertEquals("Ann Smith", shown?.display())
        assertEquals("ann@x.org", listAddressBesideName(shown, rowShowsRecipients = false))
    }
}

package app.sterna.push

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Executes [Notifications.notificationSenderName], the name a new-mail notification writes (#280). */
class NotificationSenderNameTest {

    private val book = mapOf("mom@x.org" to "Mom")

    private fun mail(vararg from: EmailAddress) = Email(id = "m", from = from.toList())

    @Test fun `a sender in the book is named as saved in the contacts`() {
        assertEquals(
            "Mom",
            Notifications.notificationSenderName(mail(EmailAddress(name = "Jane Doe", email = "MOM@x.org ")), book),
        )
    }

    @Test fun `a nameless sender in the book is named as saved too`() {
        assertEquals("Mom", Notifications.notificationSenderName(mail(EmailAddress(email = "mom@x.org")), book))
    }

    @Test fun `a sender not in the book keeps the From name, or the address without one`() {
        assertEquals("Bob", Notifications.notificationSenderName(mail(EmailAddress(name = "Bob", email = "bob@x.org")), book))
        assertEquals("bob@x.org", Notifications.notificationSenderName(mail(EmailAddress(email = "bob@x.org")), book))
    }

    @Test fun `only the first From address is looked up`() {
        assertEquals(
            "Bob",
            Notifications.notificationSenderName(
                mail(EmailAddress(name = "Bob", email = "bob@x.org"), EmailAddress(name = "Jane", email = "mom@x.org")),
                book,
            ),
        )
    }

    @Test fun `a mail without a From names no one`() {
        assertNull(Notifications.notificationSenderName(mail(), book))
    }
}

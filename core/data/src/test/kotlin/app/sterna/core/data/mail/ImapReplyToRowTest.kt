package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.imap.ImapAddress
import app.sterna.core.imap.ImapMessage
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⭐ The end of the IMAP road: the envelope's `Reply-To` has to land in the CACHE ROW.
 *
 * The IMAP side does not go through [EmailMapper.toEntity] — that one starts from a JMAP `Email`,
 * which it never has — so it builds its own [app.sterna.core.data.db.EmailEntity]. A column the
 * JMAP mapper fills and this one forgets is the worst kind of half-fix: a JMAP account answers the
 * right address, an IMAP account answers the From, and whoever tests on one account sees nothing.
 *
 * The row is also the ONLY thing the offline reply and the notification quick reply ever see: they
 * never re-fetch the message, so an address that stops at [ImapMessage] is an address they do not
 * have.
 */
class ImapReplyToRowTest {

    private val support = ImapAddress(name = "Support", email = "support@masto.top")
    private val billing = ImapAddress(name = "Billing", email = "billing@masto.top")
    private val team = ImapAddress(name = "Team", email = "team@masto.top")

    private fun message(replyTo: List<ImapAddress>) = ImapMessage(
        uid = 7L,
        subject = "Your ticket",
        fromName = "No Reply",
        fromEmail = "no-reply@masto.top",
        to = listOf(team),
        replyTo = replyTo,
        dateMillis = 1_780_000_000_000L,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = "<7@masto.top>",
        inReplyTo = null,
    )

    @Test
    fun `the reply-to address is written to the row`() {
        val entity = message(listOf(support)).toEntity("accA", "INBOX", 42L)

        assertNotNull("the IMAP row must carry Reply-To, not only From", entity.replyToJson)
        assertEquals(
            listOf(EmailAddress(name = "Support", email = "support@masto.top")),
            EmailRecipients.decode(entity.replyToJson),
        )
    }

    /** What the reader actually holds: the row rendered back into an `Email`. */
    @Test
    fun `a row read back offline answers the reply-to, not the from`() {
        val email = message(listOf(support)).toEntity("accA", "INBOX", 42L).toEmail()

        assertEquals(listOf("support@masto.top"), email.replyTo.map { it.email })
        assertEquals("no-reply@masto.top", email.from.single().email)
    }

    @Test
    fun `several reply-to addresses keep their order in the row`() {
        val email = message(listOf(support, billing)).toEntity("accA", "INBOX", 42L).toEmail()

        assertEquals(listOf("support@masto.top", "billing@masto.top"), email.replyTo.map { it.email })
        assertEquals(listOf("Support", "Billing"), email.replyTo.map { it.name })
    }

    /** Two columns, two lists: a Reply-To is not a recipient and must not be stored as one. */
    @Test
    fun `reply-to and the recipients are stored in separate columns`() {
        val entity = message(listOf(support)).toEntity("accA", "INBOX", 42L)

        assertEquals(
            listOf(EmailAddress(name = "Team", email = "team@masto.top")),
            EmailRecipients.decode(entity.recipientsJson),
        )
        assertEquals(
            listOf(EmailAddress(name = "Support", email = "support@masto.top")),
            EmailRecipients.decode(entity.replyToJson),
        )
    }

    /** No Reply-To: nothing stored, and the recipients beside it are untouched. */
    @Test
    fun `a message without reply-to stores nothing in that column`() {
        val entity = message(emptyList()).toEntity("accA", "INBOX", 42L)

        assertNull(entity.replyToJson)
        assertEquals(emptyList<EmailAddress>(), entity.toEmail().replyTo)
        assertNotNull(entity.recipientsJson)
    }
}

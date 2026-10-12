package app.sterna.ui.compose

import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Span
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Edit as new (#254) lays in the composer, executed on a message a reply would read differently. */
class EditAsNewFieldsTest {

    @Test fun `the recipients are the original's own, each in its field`() {
        val fields = editAsNewFieldsOf(RECEIVED)
        assertEquals("to1@example.com, to2@example.com", fields.to)
        assertEquals("cc@example.com", fields.cc)
        assertEquals("bcc@example.com", fields.bcc)
        assertTrue("a message that carried Cc or Bcc opens with that row shown", fields.expand)
    }

    @Test fun `neither the sender nor the Reply-To of the original becomes a recipient`() {
        val fields = editAsNewFieldsOf(RECEIVED)
        val everyField = listOf(fields.to, fields.cc, fields.bcc).joinToString(",")
        assertFalse("the original's From was addressed: this is a reply. Fields were $everyField", "sender@example.com" in everyField)
        assertFalse("the original's Reply-To was addressed: this is a reply. Fields were $everyField", "answers@example.com" in everyField)
    }

    @Test fun `the subject is the original's, with nothing put in front of one that already has a prefix`() {
        assertEquals("Re: Fwd: the minutes", editAsNewFieldsOf(RECEIVED).subject)
        assertEquals("the minutes", editAsNewFieldsOf(RECEIVED.copy(subject = "the minutes")).subject)
    }

    @Test fun `the body is the original's html, read with its styling, and nothing around it`() {
        val fields = editAsNewFieldsOf(RECEIVED)
        assertEquals("no quote marker, no attribution line, no signature: the text as written", "hello world", fields.body)
        assertEquals(mapOf(Inline.BOLD to listOf(Span(0, 5))), fields.bodyRanges)
    }

    @Test fun `a message with no html part gives its text`() {
        val plain = RECEIVED.copy(htmlBody = emptyList(), bodyValues = mapOf("t" to EmailBodyValue("first\nsecond")))
        assertEquals("first\nsecond", editAsNewFieldsOf(plain).body)
    }

    /**
     * The fields a reopened draft is allowed to fill back from the row this app cached. Here no such
     * row exists, and anything standing in for it addresses people the message never named.
     */
    @Test fun `an addressing field the original left empty stays empty`() {
        val bccOnly = RECEIVED.copy(to = emptyList(), cc = emptyList())
        val fields = editAsNewFieldsOf(bccOnly)
        assertEquals("a message sent in blind copy only has no To, and none is made up for it", "", fields.to)
        assertEquals("", fields.cc)
        assertEquals("bcc@example.com", fields.bcc)
        val toOnly = editAsNewFieldsOf(RECEIVED.copy(cc = emptyList(), bcc = emptyList()))
        assertEquals("", toOnly.cc)
        assertEquals("", toOnly.bcc)
        assertFalse("no Cc and no Bcc, so that row stays folded", toOnly.expand)
    }

    @Test fun `nothing the original asked of its readers is asked again`() {
        val fields = editAsNewFieldsOf(RECEIVED.copy(dispositionNotificationTo = "sender@example.com"))
        assertFalse("the original's read-receipt request ticked the new message's box", fields.requestReceipt)
        assertFalse("the body was marked as carrying a reply's quote", fields.quoted)
    }

    private companion object {
        /** From, Reply-To, To, Cc and Bcc all differ, and the two body parts disagree, so every source is told apart. */
        val RECEIVED = Email(
            id = "m-42",
            subject = "Re: Fwd: the minutes",
            from = listOf(EmailAddress(name = "Sender", email = "sender@example.com")),
            replyTo = listOf(EmailAddress(email = "answers@example.com")),
            to = listOf(EmailAddress(email = "to1@example.com"), EmailAddress(name = "Two", email = "to2@example.com")),
            cc = listOf(EmailAddress(email = "cc@example.com")),
            bcc = listOf(EmailAddress(email = "bcc@example.com")),
            messageId = listOf("<m42@example.com>"),
            inReplyTo = listOf("<m41@example.com>"),
            references = listOf("<m40@example.com>", "<m41@example.com>"),
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue("ok"),
                "h" to EmailBodyValue("<b>hello</b> world"),
            ),
        )
    }
}

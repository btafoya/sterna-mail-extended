package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.FtsHit
import app.sterna.core.jmap.model.Email
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A JMAP header cached before the filter existed is shown filtered all the same: the `emails` row,
 * the search index, a recipients column and a cached body all carry what the server sent, raw.
 */
class CachedDisplayTextTest {

    private val rawSubject = "Invoice \u202E\u2066\u200F\u0007fdp.exe"
    private val rawName = "Support \u202E\u2066\u200F\u0007moc.knab@troppus"
    private val rawAddress = "support\u202E@evil.example"

    private fun entity() = EmailEntity(
        id = "m1",
        accountId = "acc1",
        mailboxId = "inbox",
        threadId = "t1",
        subject = rawSubject,
        preview = "preview \u202Etext",
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = rawName,
        fromEmail = rawAddress,
        seen = false,
        flagged = false,
        hasAttachment = false,
        sortKey = 0L,
    )

    private fun hit() = FtsHit(
        emailId = "m1",
        accountId = "acc1",
        mailboxId = "inbox",
        threadId = "t1",
        subject = rawSubject,
        preview = null,
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = rawName,
        fromEmail = rawAddress,
        seen = false,
        flagged = false,
        hasAttachment = false,
    )

    @Test fun `an emails row shows its subject filtered`() {
        assertEquals("subject", "Invoice fdp.exe", entity().toEmail().subject)
    }

    @Test fun `an emails row shows its sender name filtered`() {
        assertEquals("from[0].name", "Support moc.knab@troppus", entity().toEmail().from.single().name)
    }

    @Test fun `an emails row keeps its sender address as stored`() {
        assertEquals("from[0].email", "support\u202E@evil.example", entity().toEmail().from.single().email)
    }

    @Test fun `an emails row shows its preview filtered`() {
        assertEquals("preview", "preview text", entity().toEmail().preview)
    }

    @Test fun `an emails row shows its preview without the sender's preheader`() {
        val row = entity().copy(preview = "Your order has shipped&#847;&zwnj; &#847;&zwnj; AT&amp;T\u034F\u200C")
        assertEquals("preview", "Your order has shipped AT&T", row.toEmail().preview)
    }

    private val paddedMessage = "Hello" + "&#847;&zwnj; ".repeat(40) + "Your PayID is now registered."

    @Test fun `an emails row shows one space where the preheader left a run of blanks`() {
        assertEquals(
            "preview",
            "Hello Your PayID is now registered.",
            entity().copy(preview = paddedMessage).toEmail().preview,
        )
    }

    @Test fun `a search hit shows one space where the preheader left a run of blanks`() {
        assertEquals(
            "preview",
            "Hello Your PayID is now registered.",
            hit().copy(preview = paddedMessage).toEmail().preview,
        )
    }

    @Test fun `an emails row holding only a preheader reads as having no preview`() {
        val row = entity().copy(preview = "&#847;&zwnj; &#847;&zwnj; \u034F\u200C &#847;&zwnj;")
        assertNull("preview", row.toEmail().preview)
    }

    /** Once per read: a second pass would show `&` where the sender wrote `&amp;`. */
    @Test fun `an emails row decodes its preview once`() {
        assertEquals("preview", "&amp;", entity().copy(preview = "&amp;amp;").toEmail().preview)
    }

    @Test fun `a search hit shows its preview without the sender's preheader`() {
        val found = hit().copy(preview = "Your order has shipped&#847;&zwnj; &#847;&zwnj; AT&amp;T\u034F\u200C\u202E")
        assertEquals("preview", "Your order has shipped AT&T", found.toEmail().preview)
    }

    @Test fun `a search hit holding only a preheader reads as having no preview`() {
        assertNull("preview", hit().copy(preview = "&#847;&zwnj; &#847;&zwnj;").toEmail().preview)
    }

    @Test fun `a search hit decodes its preview once`() {
        assertEquals("preview", "&amp;", hit().copy(preview = "&amp;amp;").toEmail().preview)
    }

    /** The filter is a reading: the row written back from the wire keeps the server's bytes. */
    @Test fun `a preview is written to the row as the server sent it`() {
        val raw = "Hello&#847;&zwnj; \u034F\u200C"
        val written = Email(id = "m1", preview = raw).toEntity("acc1", "inbox")
        assertEquals("preview", raw, written.preview)
    }

    @Test fun `a search hit shows its subject filtered`() {
        assertEquals("subject", "Invoice fdp.exe", hit().toEmail().subject)
    }

    @Test fun `a search hit shows its sender name filtered`() {
        assertEquals("from[0].name", "Support moc.knab@troppus", hit().toEmail().from.single().name)
    }

    @Test fun `a search hit keeps its sender address as stored`() {
        assertEquals("from[0].email", "support\u202E@evil.example", hit().toEmail().from.single().email)
    }

    @Test fun `a stored recipients column decodes to filtered names and raw addresses`() {
        val stored = """[{"name":"Bob \u202E\u2066\u200F\u0007boB","email":"bob\u202E@example.org"}]"""
        val decoded = EmailRecipients.decode(stored).single()
        assertEquals("to[0].name", "Bob boB", decoded.name)
        assertEquals("to[0].email", "bob\u202E@example.org", decoded.email)
    }

    /** A body row holding the server's raw strings, as `persistBody` writes them: plain JSON. */
    private val cachedBody by lazy {
        decodeCachedEmail(
            """{"id":"m1","subject":"Invoice \u202E\u2066\u200F\u0007fdp.exe",""" +
                """"from":[{"name":"Support \u202Emoc.knab@troppus","email":"support\u202E@evil.example"}],""" +
                """"cc":[{"name":"Cc \u2066name","email":"cc@example.org"}],""" +
                """"attachments":[{"blobId":"b2","name":"report\u202Efdp.exe"}],""" +
                """"headers":[{"name":"Subject","value":"Invoice \u202Efdp.exe"}]}""",
        )
    }

    @Test fun `a cached body shows its subject filtered`() {
        assertEquals("subject", "Invoice fdp.exe", cachedBody.subject)
    }

    @Test fun `a cached body shows its sender name filtered`() {
        assertEquals("from[0].name", "Support moc.knab@troppus", cachedBody.from.single().name)
    }

    @Test fun `a cached body shows its cc name filtered`() {
        assertEquals("cc[0].name", "Cc name", cachedBody.cc.single().name)
    }

    @Test fun `a cached body shows its attachment name filtered`() {
        assertEquals("attachments[0].name", "reportfdp.exe", cachedBody.attachments.single().name)
    }

    @Test fun `a cached body keeps its address and raw headers as stored`() {
        assertEquals("from[0].email", "support\u202E@evil.example", cachedBody.from.single().email)
        assertEquals("headers[0].value", "Invoice \u202Efdp.exe", cachedBody.headers.single().value)
    }
}

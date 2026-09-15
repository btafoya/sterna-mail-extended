package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailRecipients
import app.sterna.core.data.db.FtsHit
import org.junit.Assert.assertEquals
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

    @Test fun `an emails row keeps its sender address and preview as stored`() {
        val email = entity().toEmail()
        assertEquals("from[0].email", "support\u202E@evil.example", email.from.single().email)
        assertEquals("preview", "preview \u202Etext", email.preview)
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

package app.sterna.core.jmap

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a JMAP server sends as a subject, a correspondent's name or an attachment's name loses its
 * control characters and bidi marks on the way into the model, as IMAP's headers already do
 * (`SECURITY.md`). Everything that is not shown as a label, or that #60 shows raw, stays as sent.
 */
class DisplayedHeadersOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")

    // U+202E right-to-left override, U+2066 left-to-right isolate, U+200F right-to-left mark, U+0007 bell,
    // written as JSON escapes so the fixture is legible and no editor can drop them.
    private val marks = """\u202E\u2066\u200F\u0007"""

    private fun emailGet(properties: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[
              {"id":"m1"$properties}
            ],"notFound":[]},"g0"]]}
            """.trimIndent(),
        )

    private fun fetch(properties: String): Email {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(emailGet(properties))
            return runBlocking {
                JmapClient().getEmail(JmapSession(apiUrl = server.url("/jmap/api/").toString()), "acc1", "m1", auth)
            }
        } finally {
            server.shutdown()
        }
    }

    private val spoofed: Email by lazy {
        fetch(
            """,
            "subject":"Invoice ${marks}fdp.exe",
            "from":[{"name":"Support ${marks}moc.knab@troppus","email":"support${marks}@evil.example"}],
            "to":[{"name":"To ${marks}name","email":"to@example.org"}],
            "cc":[{"name":"Cc ${marks}name","email":"cc@example.org"}],
            "bcc":[{"name":"Bcc ${marks}name","email":"bcc@example.org"}],
            "replyTo":[{"name":"ReplyTo ${marks}name","email":"reply@example.org"}],
            "attachments":[{"partId":"2","blobId":"b2","type":"application/pdf","name":"report${marks}fdp.exe"}],
            "headers":[{"name":"Subject","value":"Invoice ${marks}fdp.exe"}],
            "preview":"preview ${marks}text",
            "textBody":[{"partId":"1","type":"text/plain"}],
            "bodyValues":{"1":{"value":"body ${marks}text"}},
            "header:List-Unsubscribe:asText":"<mailto:u${marks}@example.org>"
            """.trimIndent(),
        )
    }

    @Test fun `the subject loses the marks`() {
        assertEquals("subject", "Invoice fdp.exe", spoofed.subject)
    }

    @Test fun `the from name loses the marks`() {
        assertEquals("from[0].name", "Support moc.knab@troppus", spoofed.from.single().name)
    }

    @Test fun `the to name loses the marks`() {
        assertEquals("to[0].name", "To name", spoofed.to.single().name)
    }

    @Test fun `the cc name loses the marks`() {
        assertEquals("cc[0].name", "Cc name", spoofed.cc.single().name)
    }

    @Test fun `the bcc name loses the marks`() {
        assertEquals("bcc[0].name", "Bcc name", spoofed.bcc.single().name)
    }

    @Test fun `the replyTo name loses the marks`() {
        assertEquals("replyTo[0].name", "ReplyTo name", spoofed.replyTo.single().name)
    }

    @Test fun `the attachment name loses the marks`() {
        assertEquals("attachments[0].name", "reportfdp.exe", spoofed.attachments.single().name)
    }

    // ---- guards: what must come back exactly as sent ----

    @Test fun `the address itself is not filtered`() {
        assertEquals("from[0].email", "support\u202E\u2066\u200F\u0007@evil.example", spoofed.from.single().email)
    }

    @Test fun `the raw header list is not filtered`() {
        assertEquals("headers[0].value", "Invoice \u202E\u2066\u200F\u0007fdp.exe", spoofed.headers.single().value)
    }

    @Test fun `the preview is not filtered`() {
        assertEquals("preview", "preview \u202E\u2066\u200F\u0007text", spoofed.preview)
    }

    @Test fun `the body is not filtered`() {
        assertEquals("bodyValues[1].value", "body \u202E\u2066\u200F\u0007text", spoofed.bodyValues.getValue("1").value)
    }

    @Test fun `List-Unsubscribe is not filtered`() {
        assertEquals("listUnsubscribe", "<mailto:u\u202E\u2066\u200F\u0007@example.org>", spoofed.listUnsubscribe)
    }

    @Test fun `a tab and Hebrew in a subject survive`() {
        val email = fetch(""","subject":"שלום\tעולם"""")
        assertEquals("subject", "שלום\tעולם", email.subject)
    }
}

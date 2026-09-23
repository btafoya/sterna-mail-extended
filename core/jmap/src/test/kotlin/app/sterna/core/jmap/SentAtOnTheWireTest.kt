package app.sterna.core.jmap

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone

/**
 * `sentAt` on the JMAP wire: without it the server writes `Date:` in the server account's zone,
 * not the device's.
 */
class SentAtOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("alex@masto.top", "secret")

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun session() = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        uploadUrl = server.url("/jmap/upload/").toString() + "{accountId}",
    )

    private val sendResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","created":{"draft":{"id":"e123"}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","created":{"sub":{"id":"s1"}}},"s0"]
           ]}"""

    private val submissionGetResponse =
        """{"methodResponses":[
             ["EmailSubmission/get",{"accountId":"acc1","list":[{"id":"s1","deliveryStatus":{
                "bob@example.org":{"delivered":"unknown","smtpReply":"250 2.1.5 Queued"}}}],
              "notFound":[]},"g0"]
           ]}"""

    private val refusesAutocryptResponse =
        """{"methodResponses":[
             ["Email/set",{"accountId":"acc1","notCreated":{"draft":{"type":"invalidProperties",
                "properties":["header:Autocrypt:asText"]}}},"e0"],
             ["EmailSubmission/set",{"accountId":"acc1","notCreated":{"sub":{"type":"invalidProperties"}}},"s0"]
           ]}"""

    private fun createdDraft(body: String): JsonObject =
        Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray
            .map { it.jsonArray }
            .first { it[0].jsonPrimitive.content == "Email/set" }[1].jsonObject["create"]!!
            .jsonObject["draft"]!!.jsonObject

    private fun sentAtOf(body: String): String? = createdDraft(body)["sentAt"]?.jsonPrimitive?.content

    private val elevenTwentyTwoInBrazil =
        OffsetDateTime.of(2026, 9, 1, 11, 22, 37, 123_000_000, ZoneOffset.ofHours(-3))

    /** A null [sentAt] leaves the parameter out, so the default is what runs. */
    private fun send(sentAt: OffsetDateTime?, autocrypt: String? = null): String? = runBlocking {
        val from = EmailAddress(email = "alex@masto.top")
        val to = listOf(EmailAddress(email = "bob@example.org"))
        if (sentAt == null) {
            client.sendEmail(
                session = session(), accountId = "acc1", auth = auth, identityId = "i1",
                from = from, to = to, subject = "Six o'clock", textBody = "see you there",
                draftMailboxId = "mbDrafts", sentMailboxId = "mbSent", autocryptHeader = autocrypt,
            )
        } else {
            client.sendEmail(
                session = session(), accountId = "acc1", auth = auth, identityId = "i1",
                from = from, to = to, subject = "Six o'clock", textBody = "see you there",
                draftMailboxId = "mbDrafts", sentMailboxId = "mbSent", autocryptHeader = autocrypt,
                sentAt = sentAt,
            )
        }
    }

    @Test fun `sentAt keeps the device offset and drops the fraction`() {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        send(elevenTwentyTwoInBrazil)
        assertEquals("2026-09-01T11:22:37-03:00", sentAtOf(server.takeRequest().body.readUtf8()))
    }

    @Test fun `by default sentAt is now, in the device zone`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            server.enqueue(MockResponse().setBody(sendResponse))
            server.enqueue(MockResponse().setBody(submissionGetResponse))
            send(sentAt = null)
            val sentAt = sentAtOf(server.takeRequest().body.readUtf8())
            assertNotNull("the server would write its own Date", sentAt)
            assertTrue("offset of the device zone, got $sentAt", sentAt!!.endsWith("+09:00"))
            assertTrue(
                "whole seconds, got $sentAt",
                Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+09:00""").matches(sentAt),
            )
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun `the replay without Autocrypt carries the same sentAt`() {
        server.enqueue(MockResponse().setBody(refusesAutocryptResponse))
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        assertEquals("e123", send(elevenTwentyTwoInBrazil, autocrypt = "addr=alex@masto.top; keydata=AAAA"))
        val first = server.takeRequest().body.readUtf8()
        val second = server.takeRequest().body.readUtf8()
        assertTrue("this is the replay", first.contains("Autocrypt") && !second.contains("Autocrypt"))
        assertEquals("2026-09-01T11:22:37-03:00", sentAtOf(first))
        assertEquals(sentAtOf(first), sentAtOf(second))
    }
}

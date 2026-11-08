package app.sterna.core.jmap

import app.sterna.core.jmap.model.EmailAddress
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * `messageId` on the JMAP wire: in the create, as a one-entry array without angle brackets (RFC 8621
 * §4.1.2.5). Written as a string, or bracketed, it is a property the server refuses or an id no reply
 * matches.
 */
class MessageIdOnTheWireTest {

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

    @Test fun `the id is the one entry of messageId in the create, without angle brackets`() {
        server.enqueue(MockResponse().setBody(sendResponse))
        server.enqueue(MockResponse().setBody(submissionGetResponse))
        runBlocking {
            client.sendEmail(
                session = session(), accountId = "acc1", auth = auth, identityId = "i1",
                from = EmailAddress(email = "alex@masto.top"), to = listOf(EmailAddress(email = "bob@example.org")),
                subject = "Six o'clock", textBody = "see you there",
                draftMailboxId = "mbDrafts", sentMailboxId = "mbSent",
                messageId = "3f2b1c0e-7a9d-4e8f-b6c5-d4a3e2f1c0b9@masto.top",
            )
        }
        val draft = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray
            .map { it.jsonArray }
            .first { it[0].jsonPrimitive.content == "Email/set" }[1].jsonObject["create"]!!
            .jsonObject["draft"]!!.jsonObject
        assertEquals(
            "null here is a create that left the stamp to the server",
            listOf("3f2b1c0e-7a9d-4e8f-b6c5-d4a3e2f1c0b9@masto.top"),
            draft["messageId"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }
}

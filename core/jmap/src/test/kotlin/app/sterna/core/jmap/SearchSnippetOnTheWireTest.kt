package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.SearchQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The passage of the body a server search matched (RFC 8621 §5, `SearchSnippet/get`), asked in the
 * same batch as the search itself: what goes on the wire, when it does not, and a server that
 * refuses the method never costing the search.
 */
class SearchSnippetOnTheWireTest {

    private lateinit var server: MockWebServer
    private val client = JmapClient()
    private val auth = BasicAuth("alex@masto.top", "secret")

    private fun session() = JmapSession(apiUrl = server.url("/jmap/api/").toString())

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun respond(vararg extraResponses: String) {
        val extra = extraResponses.joinToString("") { ",$it" }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """
                {"methodResponses":[
                  ["Email/query",{"accountId":"acc1","queryState":"q1","ids":["e1","e2"]},"q0"],
                  ["Email/get",{"accountId":"acc1","state":"s1","notFound":[],"list":[
                    {"id":"e1","subject":"Invoice 1","preview":"usual preview 1"},
                    {"id":"e2","subject":"Invoice 2","preview":"usual preview 2"}
                  ]},"g0"]$extra
                ]}
                """.trimIndent(),
            ),
        )
    }

    private fun sentCalls(): JsonArray =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray

    private fun call(calls: JsonArray, name: String): JsonObject? =
        calls.map { it.jsonArray }.firstOrNull { it[0].jsonPrimitive.content == name }?.get(1)?.jsonObject

    @Test fun `a text search asks for the snippets with the query's own filter and its ids`() = runBlocking {
        respond()
        val query = SearchQuery(text = "invoice", from = "alex@masto.top", afterMillis = 1_780_000_000_000)

        client.searchEmails(session(), "acc1", query, 50, auth, excludeMailboxIds = listOf("trash1"))

        val calls = sentCalls()
        val snippet = call(calls, "SearchSnippet/get") ?: throw AssertionError("no SearchSnippet/get in the batch")
        val queried = call(calls, "Email/query")!!
        assertEquals("the snippet filter must be the query's, byte for byte", queried["filter"], snippet["filter"])
        assertEquals(searchFilter(query, listOf("trash1")), snippet["filter"])
        assertEquals("acc1", snippet["accountId"]!!.jsonPrimitive.content)
        val ref = snippet["#emailIds"]!!.jsonObject
        assertEquals("q0", ref["resultOf"]!!.jsonPrimitive.content)
        assertEquals("Email/query", ref["name"]!!.jsonPrimitive.content)
        assertEquals("/ids", ref["path"]!!.jsonPrimitive.content)
    }

    @Test fun `a subject search asks for the snippets too`() = runBlocking {
        respond()

        client.searchEmails(session(), "acc1", SearchQuery(subject = "facture"), 50, auth)

        assertNotNull(call(sentCalls(), "SearchSnippet/get"))
    }

    /** No words were searched for, so there is no passage to show: the call would be wasted. */
    @Test fun `a search on the sender, dates and attachment alone does not ask for snippets`() = runBlocking {
        respond()

        client.searchEmails(
            session(), "acc1",
            SearchQuery(from = "alex@masto.top", hasAttachment = true, afterMillis = 1_780_000_000_000), 50, auth,
        )

        val calls = sentCalls()
        assertNotNull("the search itself still went out", call(calls, "Email/query"))
        assertNull(call(calls, "SearchSnippet/get"))
    }

    @Test fun `the snippets come back by email id, raw, and a null preview is no snippet`() = runBlocking {
        respond(
            """["SearchSnippet/get",{"accountId":"acc1","list":[
                 {"emailId":"e1","subject":null,"preview":"the <mark>invoice</mark> &amp; more"},
                 {"emailId":"e2","subject":"<mark>Invoice</mark> 2","preview":null}
               ],"notFound":[]},"s0"]""",
        )

        val page = client.searchEmails(session(), "acc1", SearchQuery(text = "invoice"), 50, auth)

        assertEquals(mapOf("e1" to "the <mark>invoice</mark> &amp; more"), page.snippetPreviews)
        assertEquals(2, page.emails.size)
    }

    @Test fun `a server that refuses the snippet method still answers the search`() = runBlocking {
        respond("""["error",{"type":"unknownMethod"},"s0"]""")

        val page = client.searchEmails(session(), "acc1", SearchQuery(text = "invoice"), 50, auth)

        assertEquals(listOf("e1", "e2"), page.emails.map { it.id })
        assertEquals(2, page.matchedIds)
        assertTrue(page.snippetPreviews.isEmpty())
    }
}

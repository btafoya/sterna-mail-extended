package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The unread messages of a whole account as they go over the wire: an ids-only `Email/query` scoped
 * by exclusion rather than by folder, then an `Email/get` of those ids that also says where each
 * message is filed.
 */
class AccountUnreadOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun session(maxObjectsInGet: Int? = null) = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        capabilities = mapOf(
            Jmap.CORE_CAPABILITY to
                if (maxObjectsInGet == null) buildJsonObject { }
                else Json.parseToJsonElement("""{"maxObjectsInGet":$maxObjectsInGet}""").jsonObject,
        ),
    )

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun queryResponse(ids: List<String>, total: Int? = null): MockResponse {
        val idList = ids.joinToString(",") { "\"$it\"" }
        val totalField = if (total == null) "" else ""","total":$total"""
        return json("""{"methodResponses":[["Email/query",{"ids":[$idList],"queryState":"q1"$totalField},"q0"]]}""")
    }

    private fun getResponse(rows: List<String>): MockResponse =
        json("""{"methodResponses":[["Email/get",{"state":"s1","list":[${rows.joinToString(",")}],"notFound":[]},"g0"]]}""")

    /** Every method call of the next request this server received, as (name, arguments). */
    private fun nextCalls(): List<Pair<String, JsonObject>> {
        val body = server.takeRequest().body.readUtf8()
        return Json.parseToJsonElement(body).jsonObject.getValue("methodCalls").jsonArray.map { entry ->
            entry.jsonArray[0].jsonPrimitive.content to entry.jsonArray[1].jsonObject
        }
    }

    private fun onlyQuery(): JsonObject {
        val calls = nextCalls()
        assertEquals("an ids-only page is a lone Email/query", listOf("Email/query"), calls.map { it.first })
        return calls.single().second
    }

    /** The conditions the filter really ANDs together; a lone condition is its own filter. */
    private fun conditionsOf(filter: JsonObject): List<JsonObject> {
        val operator = filter["operator"] ?: return listOf(filter)
        assertEquals("AND", operator.jsonPrimitive.content)
        return filter.getValue("conditions").jsonArray.map { it.jsonObject }
    }

    @Test fun `the account unread query filters on the unseen keyword and on the excluded folders`() {
        server.enqueue(queryResponse(listOf("m1")))

        runBlocking {
            JmapClient().queryUnreadEmailIds(session(), "acc1", listOf("trash", "junk"), limit = 50, auth = auth)
        }

        val args = onlyQuery()
        assertEquals("acc1", args.getValue("accountId").jsonPrimitive.content)
        val conditions = conditionsOf(args.getValue("filter").jsonObject)
        assertEquals(
            "filter sent: ${args["filter"]}",
            listOf("\$seen"),
            conditions.mapNotNull { it["notKeyword"]?.jsonPrimitive?.content },
        )
        assertEquals(
            "filter sent: ${args["filter"]}",
            listOf(listOf("trash", "junk")),
            conditions.mapNotNull { c -> c["inMailboxOtherThan"]?.jsonArray?.map { it.jsonPrimitive.content } },
        )
        assertEquals("nothing else narrows the query: ${args["filter"]}", 2, conditions.size)
        assertFalse(
            "a whole-account query must not name a folder: ${args["filter"]}",
            conditions.any { "inMailbox" in it } || "inMailbox" in args.getValue("filter").jsonObject,
        )
    }

    @Test fun `with nothing to exclude the filter is the unseen condition alone`() {
        server.enqueue(queryResponse(emptyList()))

        runBlocking { JmapClient().queryUnreadEmailIds(session(), "acc1", emptyList(), limit = 50, auth = auth) }

        assertEquals(
            Json.parseToJsonElement("""{"notKeyword":"${'$'}seen"}"""),
            onlyQuery().getValue("filter"),
        )
    }

    @Test fun `the account unread query is uncollapsed and newest first`() {
        server.enqueue(queryResponse(emptyList()))

        runBlocking { JmapClient().queryUnreadEmailIds(session(), "acc1", listOf("trash"), limit = 50, auth = auth) }

        val args = onlyQuery()
        assertFalse(args.getValue("collapseThreads").jsonPrimitive.boolean)
        assertEquals(
            Json.parseToJsonElement("""[{"property":"receivedAt","isAscending":false}]"""),
            args.getValue("sort"),
        )
    }

    @Test fun `position limit and calculateTotal are sent as given`() {
        server.enqueue(queryResponse(listOf("m1")))
        server.enqueue(queryResponse(listOf("m2"), total = 120))
        val client = JmapClient()

        runBlocking {
            client.queryUnreadEmailIds(session(), "acc1", listOf("trash"), limit = 50, auth = auth)
            client.queryUnreadEmailIds(
                session(), "acc1", listOf("trash"), limit = 70, auth = auth, position = 50, calculateTotal = true,
            )
        }

        val first = onlyQuery()
        assertEquals(0, first.getValue("position").jsonPrimitive.int)
        assertEquals(50, first.getValue("limit").jsonPrimitive.int)
        assertNull("calculateTotal is only sent when asked for", first["calculateTotal"])
        val second = onlyQuery()
        assertEquals(50, second.getValue("position").jsonPrimitive.int)
        assertEquals(70, second.getValue("limit").jsonPrimitive.int)
        assertTrue(second.getValue("calculateTotal").jsonPrimitive.boolean)
    }

    @Test fun `ids and total are read back from the answer`() {
        server.enqueue(queryResponse(listOf("m7", "m3", "m9"), total = 412))
        server.enqueue(queryResponse(listOf("m1")))
        val client = JmapClient()

        val (counted, uncounted) = runBlocking {
            client.queryUnreadEmailIds(session(), "acc1", listOf("trash"), 50, auth, calculateTotal = true) to
                client.queryUnreadEmailIds(session(), "acc1", listOf("trash"), 50, auth)
        }

        assertEquals(listOf("m7", "m3", "m9"), counted.ids)
        assertEquals(412, counted.total)
        assertEquals(listOf("m1"), uncounted.ids)
        assertNull(uncounted.total)
    }

    @Test fun `a query the server refuses is thrown, not read as an empty page`() {
        server.enqueue(json("""{"methodResponses":[["error",{"type":"unsupportedFilter"},"q0"]]}"""))

        val refusal = try {
            runBlocking { JmapClient().queryUnreadEmailIds(session(), "acc1", listOf("trash"), limit = 50, auth = auth) }
            null
        } catch (thrown: JmapException) {
            thrown
        }

        assertEquals("unsupportedFilter", refusal?.errorType)
    }

    @Test fun `the read by ids asks for a whole list row and for mailboxIds`() {
        server.enqueue(getResponse(listOf("""{"id":"m1"}""")))

        runBlocking { JmapClient().getEmailsWithMailboxIds(session(), "acc1", listOf("m1"), auth) }

        val calls = nextCalls()
        assertEquals(listOf("Email/get"), calls.map { it.first })
        val args = calls.single().second
        assertEquals("acc1", args.getValue("accountId").jsonPrimitive.content)
        val asked = args.getValue("properties").jsonArray.map { it.jsonPrimitive.content }
        assertTrue("`mailboxIds` is not asked for. Asked for: $asked", "mailboxIds" in asked)
        val listRow = listOf(
            "id", "threadId", "subject", "preview", "receivedAt",
            "from", "replyTo", "to", "cc", "bcc", "hasAttachment", "keywords",
        )
        assertEquals(
            "these rows are written to the cache whole, so a property left out here is erased there",
            emptyList<String>(),
            listRow.filterNot { it in asked },
        )
    }

    @Test fun `the read by ids hands back each message with the folders it is filed in`() {
        server.enqueue(
            getResponse(
                listOf(
                    """{"id":"m1","subject":"Six o'clock","mailboxIds":{"inbox":true,"work":true}}""",
                    """{"id":"m2","subject":"Seven","mailboxIds":{"archive":true}}""",
                ),
            ),
        )

        val emails = runBlocking { JmapClient().getEmailsWithMailboxIds(session(), "acc1", listOf("m1", "m2"), auth) }

        assertEquals(listOf("m1", "m2"), emails.map { it.id })
        assertEquals("Six o'clock", emails[0].subject)
        assertEquals(setOf("inbox", "work"), emails[0].mailboxIds.keys)
        assertEquals(setOf("archive"), emails[1].mailboxIds.keys)
    }

    @Test fun `the read by ids is split into as many gets as maxObjectsInGet requires`() {
        server.enqueue(getResponse(listOf("""{"id":"a"}""", """{"id":"b"}""")))
        server.enqueue(getResponse(listOf("""{"id":"c"}""", """{"id":"d"}""")))
        server.enqueue(getResponse(listOf("""{"id":"e"}""")))

        val emails = runBlocking {
            JmapClient().getEmailsWithMailboxIds(
                session(maxObjectsInGet = 2), "acc1", listOf("a", "b", "c", "d", "e"), auth,
            )
        }

        assertEquals(3, server.requestCount)
        val batches = List(3) {
            val call = nextCalls().single()
            assertEquals("Email/get", call.first)
            assertTrue(
                "every batch asks for mailboxIds",
                call.second.getValue("properties").jsonArray.any { it.jsonPrimitive.content == "mailboxIds" },
            )
            call.second.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e")), batches)
        assertEquals(listOf("a", "b", "c", "d", "e"), emails.map { it.id })
    }

    @Test fun `no id means no request`() {
        val emails = runBlocking { JmapClient().getEmailsWithMailboxIds(session(), "acc1", emptyList(), auth) }

        assertEquals(emptyList<String>(), emails.map { it.id })
        assertEquals(0, server.requestCount)
    }
}

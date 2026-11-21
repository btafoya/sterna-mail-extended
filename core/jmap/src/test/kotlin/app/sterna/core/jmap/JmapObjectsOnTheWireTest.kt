package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class JmapObjectsOnTheWireTest {
    private val auth = BasicAuth("a@b.c", "x")
    private lateinit var server: MockWebServer

    @Before fun start() { server = MockWebServer().also { it.start() } }
    @After fun stop() = server.shutdown()

    private fun session(withCalendars: Boolean = true) = JmapSession(
        apiUrl = server.url("/api/").toString(),
        capabilities = buildMap {
            put(Jmap.CORE_CAPABILITY, buildJsonObject { })
            put(Jmap.MAIL_CAPABILITY, buildJsonObject { })
            if (withCalendars) put(Jmap.CALENDARS_CAPABILITY, buildJsonObject { })
        },
        accounts = mapOf("m" to JmapAccount("me", mapOf(Jmap.MAIL_CAPABILITY to buildJsonObject { }))),
        primaryAccounts = buildMap {
            put(Jmap.MAIL_CAPABILITY, "m")
            if (withCalendars) put(Jmap.CALENDARS_CAPABILITY, "cal")
        },
    )

    private fun respond(json: String) =
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))

    private fun body(i: Int): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.also { assertTrue(i >= 0) }

    @Test fun `a type the session does not list is not asked for`() = runBlocking {
        val client = JmapClient()
        assertNull(client.getAllObjects(session(withCalendars = false), auth, JmapType.CALENDAR_EVENT))
        assertEquals(0, server.requestCount)
    }

    @Test fun `getAll pages by position and keeps the first state`() = runBlocking {
        respond("""{"methodResponses":[["CalendarEvent/query",{"ids":["a"],"total":2},"q"],["CalendarEvent/get",{"state":"s1","list":[{"id":"a","title":"A"}]},"g"]]}""")
        respond("""{"methodResponses":[["CalendarEvent/query",{"ids":["b"],"total":2},"q"],["CalendarEvent/get",{"state":"s2","list":[{"id":"b","title":"B"}]},"g"]]}""")
        val all = JmapClient().getAllObjects(session(), auth, JmapType.CALENDAR_EVENT)!!
        assertEquals(listOf("a", "b"), all.objects.map { it.id })
        assertEquals("s1", all.state)
        assertEquals("cal", body(0)["methodCalls"]!!.jsonArray[0].jsonArray[1].jsonObject["accountId"]!!.jsonPrimitive.content)
    }

    @Test fun `changes reads the created and updated ids and reports the destroyed and the vanished`() = runBlocking {
        respond("""{"methodResponses":[["CalendarEvent/changes",{"created":["a"],"updated":["b"],"destroyed":["c"],"newState":"s2","hasMoreChanges":false},"c"]]}""")
        respond("""{"methodResponses":[["CalendarEvent/get",{"list":[{"id":"a"}],"notFound":["b"]},"g"]]}""")
        val c = JmapClient().objectChanges(session(), auth, JmapType.CALENDAR_EVENT, "s1")!!
        assertEquals(listOf("a"), c.changed.map { it.id })
        assertEquals(setOf("b", "c"), c.destroyed.toSet())
        assertEquals("s2", c.newState)
    }

    @Test fun `cannotCalculateChanges falls back on the whole collection`() = runBlocking {
        respond("""{"methodResponses":[["error",{"type":"cannotCalculateChanges"},"c"]]}""")
        respond("""{"methodResponses":[["CalendarEvent/query",{"ids":["a"],"total":1},"q"],["CalendarEvent/get",{"state":"s9","list":[{"id":"a"}]},"g"]]}""")
        val c = JmapClient().objectChanges(session(), auth, JmapType.CALENDAR_EVENT, "old")!!
        assertTrue(c.replacesAll)
        assertEquals("s9", c.newState)
    }

    @Test fun `set sends ifInState and patches, maps created and failures, and a stale state throws`() = runBlocking {
        respond("""{"methodResponses":[["CalendarEvent/set",{"newState":"s3","created":{"n":{"id":"x1"}},"updated":{"a":null},"notUpdated":{"b":{"type":"forbidden"}}},"s"]]}""")
        val r = JmapClient().setObjects(
            session(), auth, JmapType.CALENDAR_EVENT, ifInState = "s2",
            create = mapOf("n" to buildJsonObject { put("title", "T") }),
            update = mapOf("a" to buildJsonObject { put("title", "U") }, "b" to buildJsonObject { put("title", "V") }),
        )
        assertEquals("x1", r.created["n"]!!.id)
        assertEquals(setOf("a"), r.updated)
        assertEquals("forbidden", r.failed["b"]!!.type)
        val args = body(0)["methodCalls"]!!.jsonArray[0].jsonArray[1].jsonObject
        assertEquals("s2", args["ifInState"]!!.jsonPrimitive.content)

        respond("""{"methodResponses":[["error",{"type":"stateMismatch"},"s"]]}""")
        try {
            JmapClient().setObjects(session(), auth, JmapType.CALENDAR_EVENT, ifInState = "old", destroy = listOf("a"))
            fail()
        } catch (e: JmapException) {
            assertEquals(JMAP_STATE_MISMATCH, e.errorType)
        }
    }
}

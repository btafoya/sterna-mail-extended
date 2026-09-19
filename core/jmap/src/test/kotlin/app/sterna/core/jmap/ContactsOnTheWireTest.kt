package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.ServerContact
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * The server address book (RFC 9610) as it goes over the wire: which account, which calls, how the
 * full read pages and how the delta read follows `hasMoreChanges` and falls back on a full read.
 */
class ContactsOnTheWireTest {

    private val auth = BasicAuth("alex@masto.top", "secret")
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun session(
        maxObjectsInGet: Int? = null,
        withContacts: Boolean = true,
        contactsAccount: String = "book",
    ) = JmapSession(
        apiUrl = server.url("/jmap/api/").toString(),
        capabilities = buildMap {
            put(
                Jmap.CORE_CAPABILITY,
                if (maxObjectsInGet == null) buildJsonObject { }
                else Json.parseToJsonElement("""{"maxObjectsInGet":$maxObjectsInGet}""").jsonObject,
            )
            put(Jmap.MAIL_CAPABILITY, buildJsonObject { })
            if (withContacts) put(Jmap.CONTACTS_CAPABILITY, buildJsonObject { })
        },
        accounts = mapOf(
            "mail" to JmapAccount("me", mapOf(Jmap.MAIL_CAPABILITY to buildJsonObject { })),
            contactsAccount to JmapAccount("me", mapOf(Jmap.CONTACTS_CAPABILITY to buildJsonObject { })),
        ),
        primaryAccounts = buildMap {
            put(Jmap.MAIL_CAPABILITY, "mail")
            if (withContacts) put(Jmap.CONTACTS_CAPABILITY, contactsAccount)
        },
    )

    private fun respond(json: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(json))
    }

    private fun card(id: String) = """{"id":"$id","name":{"full":"Name $id"},"emails":{"e":{"address":"$id@example.org"}}}"""

    private fun page(ids: List<String>, total: Int, state: String = "s1") = """
        {"methodResponses":[
          ["ContactCard/query",{"accountId":"book","queryState":"q1","ids":[${ids.joinToString(",") { "\"$it\"" }}],"position":0,"total":$total},"q0"],
          ["ContactCard/get",{"accountId":"book","state":"$state","list":[${ids.joinToString(",") { card(it) }}],"notFound":[]},"g0"]
        ]}
    """.trimIndent()

    private class Sent(val using: List<String>, val calls: List<Pair<String, JsonObject>>)

    private fun sent(): List<Sent> = List(server.requestCount) {
        val root = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        Sent(
            using = root["using"]!!.jsonArray.map { it.jsonPrimitive.content },
            calls = root["methodCalls"]!!.jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonObject },
        )
    }

    private fun Sent.args(method: String): JsonObject =
        calls.firstOrNull { it.first == method }?.second ?: error("no $method in $calls")

    @Test fun `a session without the contacts capability sends nothing`() {
        val withoutContacts = session(withContacts = false)

        val all = runBlocking { JmapClient().getAllContacts(withoutContacts, auth) }
        val changes = runBlocking { JmapClient().contactChanges(withoutContacts, "s1", auth) }

        assertNull(all)
        assertNull(changes)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the full read pages the query by the get limit and brings every card back`() {
        respond(page(listOf("c1", "c2"), total = 5, state = "s-first"))
        respond(page(listOf("c3", "c4"), total = 5, state = "s-later"))
        respond(page(listOf("c5"), total = 5, state = "s-later"))

        val snapshot = runBlocking { JmapClient().getAllContacts(session(maxObjectsInGet = 2), auth) }!!

        assertEquals(listOf("c1", "c2", "c3", "c4", "c5"), snapshot.contacts.map { it.id })
        // The earliest state: a card changed while the later pages were read is then replayed by
        // `ContactCard/changes` instead of being missed.
        assertEquals("s-first", snapshot.state)
        val requests = sent()
        assertEquals(3, requests.size)
        assertEquals(listOf(0, 2, 4), requests.map { it.args("ContactCard/query")["position"]!!.jsonPrimitive.content.toInt() })
        requests.forEach { request ->
            assertEquals(2, request.args("ContactCard/query")["limit"]!!.jsonPrimitive.content.toInt())
            val get = request.args("ContactCard/get")
            assertEquals(setOf("id", "name", "emails"), get["properties"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        }
    }

    @Test fun `an empty address book still yields its state`() {
        respond(page(emptyList(), total = 0, state = "s0"))

        val snapshot = runBlocking { JmapClient().getAllContacts(session(), auth) }!!

        assertEquals(emptyList<ServerContact>(), snapshot.contacts)
        assertEquals("s0", snapshot.state)
        assertEquals(1, server.requestCount)
    }

    @Test fun `changes follow hasMoreChanges and return created, updated and destroyed`() {
        respond(
            """{"methodResponses":[["ContactCard/changes",{"accountId":"book","oldState":"s1","newState":"s2",
               "hasMoreChanges":true,"created":["c1"],"updated":["c2"],"destroyed":["c3"]},"c0"]]}""",
        )
        respond(
            """{"methodResponses":[["ContactCard/changes",{"accountId":"book","oldState":"s2","newState":"s3",
               "hasMoreChanges":false,"created":["c4"],"updated":[],"destroyed":["c5"]},"c0"]]}""",
        )
        respond(
            """{"methodResponses":[["ContactCard/get",{"accountId":"book","state":"s3",
               "list":[${card("c1")},${card("c2")},${card("c4")}],"notFound":[]},"g0"]]}""",
        )

        val changes = runBlocking { JmapClient().contactChanges(session(), "s1", auth) }!!

        assertFalse(changes.replacesAll)
        assertEquals(setOf("c1", "c2", "c4"), changes.changed.map { it.id }.toSet())
        assertEquals(setOf("c3", "c5"), changes.destroyed.toSet())
        assertEquals("s3", changes.newState)
        val requests = sent()
        assertEquals(3, requests.size)
        assertEquals("s1", requests[0].args("ContactCard/changes")["sinceState"]!!.jsonPrimitive.content)
        assertEquals("s2", requests[1].args("ContactCard/changes")["sinceState"]!!.jsonPrimitive.content)
        assertEquals(
            setOf("c1", "c2", "c4"),
            requests[2].args("ContactCard/get")["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
    }

    @Test fun `a card created then destroyed within the same delta is only reported destroyed`() {
        respond(
            """{"methodResponses":[["ContactCard/changes",{"newState":"s2","hasMoreChanges":true,
               "created":["c1"],"updated":[],"destroyed":[]},"c0"]]}""",
        )
        respond(
            """{"methodResponses":[["ContactCard/changes",{"newState":"s3","hasMoreChanges":false,
               "created":[],"updated":[],"destroyed":["c1"]},"c0"]]}""",
        )

        val changes = runBlocking { JmapClient().contactChanges(session(), "s1", auth) }!!

        assertEquals(emptyList<ServerContact>(), changes.changed)
        assertEquals(listOf("c1"), changes.destroyed)
        assertEquals(2, server.requestCount)
    }

    @Test fun `a card announced changed but not found by the read is reported destroyed`() {
        respond(
            """{"methodResponses":[["ContactCard/changes",{"newState":"s2","hasMoreChanges":false,
               "created":["c1"],"updated":["c2"],"destroyed":[]},"c0"]]}""",
        )
        respond(
            """{"methodResponses":[["ContactCard/get",{"accountId":"book","state":"s3",
               "list":[${card("c1")}],"notFound":["c2"]},"g0"]]}""",
        )

        val changes = runBlocking { JmapClient().contactChanges(session(), "s1", auth) }!!

        assertEquals(listOf("c1"), changes.changed.map { it.id })
        assertEquals(
            "c2 was updated then deleted before the read: its old rows must go, not stay as they were",
            listOf("c2"), changes.destroyed,
        )
        assertEquals("s2", changes.newState)
    }

    @Test fun `cannotCalculateChanges falls back on a full read that replaces everything`() {
        respond("""{"methodResponses":[["error",{"type":"cannotCalculateChanges"},"c0"]]}""")
        respond(page(listOf("c1", "c2"), total = 2, state = "s9"))

        val changes = runBlocking { JmapClient().contactChanges(session(), "s1", auth) }!!

        assertTrue(changes.replacesAll)
        assertEquals(listOf("c1", "c2"), changes.changed.map { it.id })
        assertEquals(emptyList<String>(), changes.destroyed)
        assertEquals("s9", changes.newState)
        val requests = sent()
        assertEquals("ContactCard/changes", requests[0].calls.single().first)
        assertEquals(0, requests[1].args("ContactCard/query")["position"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun `any other method error is not mistaken for a lost state`() {
        respond("""{"methodResponses":[["error",{"type":"serverFail"},"c0"]]}""")

        try {
            runBlocking { JmapClient().contactChanges(session(), "s1", auth) }
            fail("a serverFail must surface, not turn into a full replacement")
        } catch (e: JmapException) {
            assertEquals("serverFail", e.errorType)
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun `the name falls back on given and surname components, and every address is kept`() {
        respond(
            """
            {"methodResponses":[
              ["ContactCard/query",{"ids":["a","b","c","d"],"total":4},"q0"],
              ["ContactCard/get",{"state":"s1","list":[
                {"id":"a","name":{"full":"Ada King"},"emails":{"x":{"address":"ada@example.org"}}},
                {"id":"b","name":{"full":"","components":[{"kind":"title","value":"Dr"},{"kind":"surname","value":"Lovelace"},{"kind":"given","value":"Ada"}]},
                 "emails":{"w":{"address":"ada@work.example"},"h":{"address":"ada@home.example","pref":1}}},
                {"id":"c","emails":{"x":{"address":"nobody@example.org"}}},
                {"id":"d","name":{"components":[{"kind":"given","value":"Solo"}]}}
              ],"notFound":[]},"g0"]
            ]}
            """.trimIndent(),
        )

        val contacts = runBlocking { JmapClient().getAllContacts(session(), auth) }!!.contacts

        assertEquals(
            listOf(
                ServerContact("a", "Ada King", listOf("ada@example.org")),
                ServerContact("b", "Ada Lovelace", listOf("ada@work.example", "ada@home.example")),
                ServerContact("c", "", listOf("nobody@example.org")),
                ServerContact("d", "Solo", emptyList()),
            ),
            contacts,
        )
    }

    @Test fun `the contacts account goes on the wire, not the mail account, with the contacts capability`() {
        respond(page(listOf("c1"), total = 1))
        respond(
            """{"methodResponses":[["ContactCard/changes",{"newState":"s2","hasMoreChanges":false,
               "created":[],"updated":[],"destroyed":[]},"c0"]]}""",
        )

        runBlocking {
            val client = JmapClient()
            val separate = session(contactsAccount = "address-book")
            client.getAllContacts(separate, auth)
            client.contactChanges(separate, "s1", auth)
        }

        val requests = sent()
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals(listOf(Jmap.CORE_CAPABILITY, Jmap.CONTACTS_CAPABILITY), request.using)
            request.calls.forEach { (method, args) ->
                assertEquals("$method accountId", "address-book", args["accountId"]!!.jsonPrimitive.content)
            }
        }
    }
}

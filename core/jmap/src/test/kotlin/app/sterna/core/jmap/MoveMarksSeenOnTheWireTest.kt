package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A move that also marks its message read must be one update object of one `Email/set`: the server
 * applies an update whole or not at all (RFC 8620 §5.3), which two requests cannot promise.
 */
class MoveMarksSeenOnTheWireTest {

    private val auth = BasicAuth("alex@example.test", "secret")

    /** What the server was sent for `m1` by every `Email/set` of one single-id `move`. */
    private fun updatesSentBy(move: suspend (JmapClient, JmapSession) -> Unit): List<JsonObject> {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"methodResponses":[["Email/set",{"accountId":"acc1","newState":"s2","updated":{"m1":null}},"s0"]]}""",
                ),
            )
            runBlocking { move(JmapClient(), JmapSession(apiUrl = server.url("/jmap/api/").toString())) }
            return (0 until server.requestCount).flatMap {
                Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray
                    .map { it.jsonArray }
                    .filter { it[0].jsonPrimitive.content == "Email/set" }
                    .map { it[1].jsonObject["update"]!!.jsonObject["m1"]!!.jsonObject }
            }
        } finally {
            server.shutdown()
        }
    }

    private val toTrash = buildJsonObject { putJsonObject("mailboxIds") { put("trash", true) } }

    @Test fun `a move asked to mark seen sends both in the one update object`() {
        assertEquals(
            "one `Email/set`, whose update for the message carries the folder and the keyword together",
            listOf(JsonObject(toTrash + buildJsonObject { put("keywords/\$seen", true) })),
            updatesSentBy { client, session -> client.move(session, "acc1", "m1", "trash", auth, markSeen = true) },
        )
    }

    @Test fun `a move not asked to mark seen sends the folder alone`() {
        assertEquals(
            listOf(toTrash),
            updatesSentBy { client, session -> client.move(session, "acc1", "m1", "trash", auth) },
        )
    }
}

package app.sterna.ui.message

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteRsvpTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test fun `the user is found by address, case aside, and a stranger is not a participant`() {
        val ps = obj("""{"p1":{"email":"org@x.test"},"p2":{"email":"Me@X.test"}}""")
        assertEquals("p2", ownParticipantId(ps, listOf("me@x.test")))
        assertNull(ownParticipantId(ps, listOf("other@x.test")))
        assertNull(ownParticipantId(null, listOf("me@x.test")))
    }

    @Test fun `a stored copy drops what the server sets and lands in the chosen calendar`() {
        val body = inviteCreateBody(
            obj("""{"id":"x","utcStart":"z","utcEnd":"z","method":"REQUEST","calendarIds":{"old":true},"uid":"u1","title":"T"}"""),
            "cal1",
        )
        assertEquals(obj("""{"uid":"u1","title":"T","calendarIds":{"cal1":true}}"""), body)
    }

    @Test fun `the answer is one pointer patch`() {
        assertEquals(obj("""{"participants/p2/participationStatus":"declined"}"""), rsvpPatch("p2", "DECLINED"))
        assertEquals("tentative", participationStatus("TENTATIVE"))
    }
}

package app.sterna.ui.message

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The pure half of answering an invitation through the server's calendar: which participant is the
 * user, what a stored copy of the invitation is made of, and the one-key patch that is the answer.
 */

/** JSCalendar `participationStatus` for the iTIP PARTSTAT the card's buttons use. */
fun participationStatus(partstat: String): String = when (partstat) {
    "ACCEPTED" -> "accepted"
    "DECLINED" -> "declined"
    else -> "tentative"
}

/** The id of the participant whose address is one of [own], or null when the user is not invited. */
fun ownParticipantId(participants: JsonObject?, own: Collection<String>): String? =
    participants?.entries?.firstOrNull { (_, p) ->
        val email = ((p as? JsonObject)?.get("email") as? JsonPrimitive)?.contentOrNull
        email != null && own.any { it.equals(email, ignoreCase = true) }
    }?.key

/** The invitation as a new event in [calendarId]: what the server parsed, without what only the server sets. */
fun inviteCreateBody(parsed: JsonObject, calendarId: String): JsonObject =
    JsonObject(
        parsed.filterKeys { it !in SERVER_SET } +
            ("calendarIds" to JsonObject(mapOf(calendarId to JsonPrimitive(true)))),
    )

/** The answer: one pointer patch, which the queue sends with `sendSchedulingMessages` so the server mails the reply. */
fun rsvpPatch(participantId: String, partstat: String): JsonObject =
    JsonObject(mapOf("participants/$participantId/participationStatus" to JsonPrimitive(participationStatus(partstat))))

private val SERVER_SET = setOf("id", "utcStart", "utcEnd", "method", "calendarIds")

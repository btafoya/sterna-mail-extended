package app.sterna.ui.dav

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

class CalendarLogicTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val t = LocalDateTime.parse("2026-03-01T10:00:00")
    private fun form(vararg mod: (EventForm) -> EventForm) =
        mod.fold(EventForm(title = "Lunch", start = t, end = t.plusMinutes(90), calendarId = "c1")) { f, m -> m(f) }

    @Test fun `month grid is whole weeks from the week start covering the month`() {
        val g = monthGrid(YearMonth.of(2026, 2), DayOfWeek.MONDAY) // 1 Feb 2026 is a Sunday
        assertEquals(LocalDate.of(2026, 1, 26), g.first())
        assertEquals(35, g.size)
        assertEquals(LocalDate.of(2026, 3, 1), g.last())
        assertEquals(LocalDate.of(2026, 2, 1), monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).first())
        assertEquals(28, monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size)
    }

    @Test fun `a new event is the server's shape`() {
        val j = applyForm(null, form(), "u1", "Europe/Berlin")
        assertEquals("2026-03-01T10:00:00", j["start"]!!.jsonPrimitive.content)
        assertEquals("PT1H30M", j["duration"]!!.jsonPrimitive.content)
        assertEquals("Europe/Berlin", j["timeZone"]!!.jsonPrimitive.content)
        assertEquals("u1", j["uid"]!!.jsonPrimitive.content)
        assertTrue(j["calendarIds"]!!.jsonObject.containsKey("c1"))
        assertTrue("recurrenceRules" !in j && "alerts" !in j && "participants" !in j)
    }

    @Test fun `an all-day event spans whole days with no zone`() {
        val j = applyForm(null, form({ it.copy(allDay = true, end = t.plusDays(1)) }), "u", "Europe/Berlin")
        assertEquals("2026-03-01T00:00:00", j["start"]!!.jsonPrimitive.content)
        assertEquals("P2D", j["duration"]!!.jsonPrimitive.content)
        assertTrue("timeZone" !in j && j["showWithoutTime"]!!.jsonPrimitive.content == "true")
    }

    @Test fun `repeat, alert and attendees are written from presets`() {
        val j = applyForm(null, form({ it.copy(repeat = Repeat.WEEKLY, repeatCount = 4, alert = AlertPreset.MIN10, attendees = "a@x.org, b@x.org") }), "u", "UTC")
        assertEquals("weekly", j["recurrenceRules"]!!.jsonArray[0].jsonObject["frequency"]!!.jsonPrimitive.content)
        assertEquals("-PT10M", j["alerts"]!!.jsonObject["a1"]!!.jsonObject["trigger"]!!.jsonObject["offset"]!!.jsonPrimitive.content)
        assertEquals(2, j["participants"]!!.jsonObject.size)
    }

    @Test fun `an edit sends only what changed and keeps what the editor does not know`() {
        val stored = obj(
            """{"@type":"Event","uid":"u","calendarIds":{"c1":true},"title":"Old","start":"2026-03-01T10:00:00","timeZone":"Europe/Berlin","duration":"PT1H",
               "locations":{"l1":{"name":"Room"}},"recurrenceRules":[{"frequency":"weekly","byDay":[{"day":"mo"}]}],
               "participants":{"o":{"email":"me@x.org","roles":{"owner":true}},"p":{"email":"a@x.org","roles":{"attendee":true},"participationStatus":"accepted"}}}""",
        )
        val f = formOf(stored, "c1", t)
        assertEquals(null, f.repeat) // custom rule: shown as kept
        assertEquals("a@x.org", f.attendees)
        val patch = diffPatch(stored, applyForm(stored, f.copy(title = "New"), "ignored", "UTC"))
        assertEquals(setOf("title"), patch.keys)
        // clearing the description and the attendees removes them; the owner stays
        val withDesc = stored.toMutableMap().apply { put("description", JsonPrimitive("x")) }.let(::JsonObject)
        val p2 = diffPatch(withDesc, applyForm(withDesc, formOf(withDesc, "c1", t).copy(description = "", attendees = "b@x.org"), "i", "UTC"))
        assertEquals(JsonNull, p2["description"])
        assertEquals(setOf("o", "n0"), p2["participants"]!!.jsonObject.keys)
    }

    @Test fun `a task keeps its due time and completes with a percentage`() {
        val j = applyForm(null, form({ it.copy(isTask = true, end = LocalDateTime.parse("2026-04-15T17:00:00")) }), "u", "UTC")
        assertEquals("Task", j["@type"]!!.jsonPrimitive.content)
        assertEquals("2026-04-15T17:00:00", j["due"]!!.jsonPrimitive.content)
        val done = applyForm(j, formOf(j, "c1", t).copy(completed = true), "u", "UTC")
        assertEquals("completed", done["progress"]!!.jsonPrimitive.content)
        assertEquals("100", done["percentComplete"]!!.jsonPrimitive.content)
    }
}

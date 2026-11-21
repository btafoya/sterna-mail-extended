package app.sterna.ui.dav

import app.sterna.core.data.dav.applyPatch
import app.sterna.core.data.dav.expand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Editing or deleting one occurrence of a series must leave the rest of it alone. */
class RecurringScopeTest {
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    // Mondays 2 / 9 / 16 / 23 March 2026, 10:00 UTC.
    private val series = obj(
        """{"@type":"Event","uid":"u1","title":"Standup","start":"2026-03-02T10:00:00","duration":"PT30M","timeZone":"UTC",
           "calendarIds":{"c1":true},"recurrenceRules":[{"frequency":"weekly","count":4}]}""",
    )
    private val second = "2026-03-09T10:00:00"
    private val third = "2026-03-16T10:00:00"
    private val end = java.time.Instant.parse("2027-01-01T00:00:00Z").toEpochMilli()

    private fun days(e: JsonObject) = expand(e, 0, end, ZoneId.of("UTC")).occurrences.map { it.recurrenceId }
    private fun titles(e: JsonObject) = expand(e, 0, end, ZoneId.of("UTC")).occurrences.map { it.json["title"]!!.jsonPrimitive.content }
    private fun form(e: JsonObject, rid: String) =
        formOf(expand(e, 0, end, ZoneId.of("UTC")).occurrences.first { it.recurrenceId == rid }.json, "c1", LocalDateTime.now())

    @Test fun `an occurrence is shown starting at its own time`() {
        assertEquals(LocalDateTime.parse(second), form(series, second).start)
    }

    @Test fun `deleting this event drops only that occurrence`() {
        val after = applyPatch(series, excludePatch(series, second))
        assertEquals(listOf("2026-03-02T10:00:00", third, "2026-03-23T10:00:00"), days(after))
    }

    @Test fun `deleting a second one keeps the first exclusion`() {
        val once = applyPatch(series, excludePatch(series, second))
        val twice = applyPatch(once, excludePatch(once, third))
        assertEquals(listOf("2026-03-02T10:00:00", "2026-03-23T10:00:00"), days(twice))
    }

    @Test fun `editing this event changes only that occurrence`() {
        val patch = editOccurrencePatch(series, second, form(series, second).copy(title = "Retro"), "UTC")
        assertEquals(setOf("recurrenceOverrides"), patch.keys)
        assertEquals(listOf("Standup", "Retro", "Standup", "Standup"), titles(applyPatch(series, patch)))
    }

    @Test fun `moving this event moves only that occurrence, and nothing is sent when nothing changed`() {
        val f = form(series, second)
        val moved = applyPatch(series, editOccurrencePatch(series, second, f.copy(start = f.start.plusHours(2), end = f.end!!.plusHours(2)), "UTC"))
        assertEquals(4, days(moved).size)
        assertEquals(1, expand(moved, 0, end, ZoneId.of("UTC")).occurrences.count { it.json["start"]!!.jsonPrimitive.content.endsWith("12:00:00") })
        assertEquals(0, editOccurrencePatch(series, second, f, "UTC").size)
    }

    @Test fun `an override never carries a property the server refuses in one`() {
        val patch = editOccurrencePatch(series, second, form(series, second).copy(title = "Retro", repeat = Repeat.DAILY), "UTC")
        val override = patch["recurrenceOverrides"]!!.jsonObject[second]!!.jsonObject
        assertEquals(setOf("title"), override.keys)
    }

    @Test fun `editing all events from a later one keeps the series' own start`() {
        val f = form(series, third).copy(title = "Sync")
        val whole = applyForm(series, wholeSeriesForm(series, third, f), "", "UTC")
        assertEquals("2026-03-02T10:00:00", whole["start"]!!.jsonPrimitive.content)
        assertEquals(listOf("Sync", "Sync", "Sync", "Sync"), titles(whole))
    }

    @Test fun `editing all events by moving the time moves the whole series`() {
        val f = form(series, third)
        val whole = applyForm(series, wholeSeriesForm(series, third, f.copy(start = f.start.plusHours(1), end = f.end!!.plusHours(1))), "", "UTC")
        assertEquals("2026-03-02T11:00:00", whole["start"]!!.jsonPrimitive.content)
    }

    @Test fun `deleting this and following ends the series before it`() {
        val after = applyPatch(series, cutSeriesPatch(series, third))
        assertEquals(listOf("2026-03-02T10:00:00", second), days(after))
        assertNull(after["recurrenceRules"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["count"] })
    }

    @Test fun `cutting a series drops the overrides from the cut on and keeps the earlier ones`() {
        val withOverrides = applyPatch(
            applyPatch(series, excludePatch(series, second)),
            JsonObject(mapOf("recurrenceOverrides" to obj("""{"$second":{"excluded":true},"2026-03-23T10:00:00":{"title":"Late"}}"""))),
        )
        val cut = applyPatch(withOverrides, cutSeriesPatch(withOverrides, third))
        assertEquals(setOf(second), cut["recurrenceOverrides"]!!.jsonObject.keys)
    }

    @Test fun `the first occurrence is the whole series`() {
        assertEquals(true, isFirstOccurrence(series, "2026-03-02T10:00:00"))
        assertFalse(isFirstOccurrence(series, second))
    }

    @Test fun `changing this and following starts a new series from it and ends the old one before it`() {
        val f = form(series, third).copy(title = "Sync")
        val next = followingSeries(series, third, before = 2, form = f, uid = "u2", zone = "UTC")
        assertEquals("u2", next["uid"]!!.jsonPrimitive.content)
        assertEquals("2026-03-16T10:00:00", next["start"]!!.jsonPrimitive.content)
        assertEquals(listOf(third, "2026-03-23T10:00:00"), days(next)) // the 4 of the old series less the 2 it keeps
        assertEquals(listOf("Sync", "Sync"), titles(next))
        assertNotNull(next["calendarIds"])
        assertEquals(listOf("2026-03-02T10:00:00", second), days(applyPatch(series, cutSeriesPatch(series, third))))
    }

    @Test fun `a new series takes the overrides after the cut`() {
        val withLate = applyPatch(series, JsonObject(mapOf("recurrenceOverrides" to obj("""{"2026-03-23T10:00:00":{"title":"Late"}}"""))))
        val next = followingSeries(withLate, third, 2, form(withLate, third), "u2", "UTC")
        assertEquals(listOf("Standup", "Late"), titles(next))
    }
}

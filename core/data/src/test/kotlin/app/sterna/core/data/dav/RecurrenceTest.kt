package app.sterna.core.data.dav

import app.sterna.core.data.db.DavObjectEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RecurrenceTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun ms(local: String, zone: ZoneId = berlin) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()
    private fun starts(e: JsonObject, from: String, to: String) =
        expand(e, ms(from), ms(to), berlin).occurrences.map { it.recurrenceId.removeSuffix(":00") }

    private fun event(rules: String, extra: String = "") =
        obj("""{"@type":"Event","title":"T","start":"2026-01-05T10:00:00","timeZone":"Europe/Berlin","duration":"PT1H","recurrenceRules":$rules$extra}""")

    @Test fun `a one-off shows only when it overlaps the window`() {
        val e = obj("""{"start":"2026-03-01T10:00:00","timeZone":"Europe/Berlin","duration":"PT2H"}""")
        assertEquals(1, expand(e, ms("2026-03-01T11:00:00"), ms("2026-03-02T00:00:00"), berlin).occurrences.size)
        assertEquals(0, expand(e, ms("2026-03-01T13:00:00"), ms("2026-03-02T00:00:00"), berlin).occurrences.size)
    }

    @Test fun `weekly on given days with a count`() {
        val e = event("""[{"frequency":"weekly","byDay":[{"day":"mo"},{"day":"we"}],"count":4}]""")
        assertEquals(
            listOf("2026-01-05T10:00", "2026-01-07T10:00", "2026-01-12T10:00", "2026-01-14T10:00"),
            starts(e, "2026-01-01T00:00:00", "2026-03-01T00:00:00"),
        )
    }

    @Test fun `count includes occurrences before the window`() {
        val e = event("""[{"frequency":"daily","count":3}]""") // Jan 5, 6, 7
        assertEquals(listOf("2026-01-07T10:00"), starts(e, "2026-01-07T00:00:00", "2026-02-01T00:00:00"))
    }

    @Test fun `monthly on the last friday and on the 31st that skips short months`() {
        val last = event("""[{"frequency":"monthly","byDay":[{"day":"fr","nthOfPeriod":-1}],"count":2}]""")
        assertEquals(listOf("2026-01-30T10:00", "2026-02-27T10:00"), starts(last, "2026-01-01T00:00:00", "2026-06-01T00:00:00"))
        val d31 = obj("""{"start":"2026-01-31T10:00:00","timeZone":"Europe/Berlin","duration":"PT1H","recurrenceRules":[{"frequency":"monthly","count":3}]}""")
        assertEquals(listOf("2026-01-31T10:00", "2026-03-31T10:00", "2026-05-31T10:00"), starts(d31, "2026-01-01T00:00:00", "2026-12-01T00:00:00"))
    }

    @Test fun `yearly with until stops`() {
        val e = event("""[{"frequency":"yearly","until":"2027-06-01T00:00:00"}]""")
        assertEquals(listOf("2026-01-05T10:00", "2027-01-05T10:00"), starts(e, "2026-01-01T00:00:00", "2030-01-01T00:00:00"))
    }

    @Test fun `an excluded override drops one, a patched override moves one, an extra adds one`() {
        val e = event(
            """[{"frequency":"daily","count":3}]""",
            ""","recurrenceOverrides":{"2026-01-06T10:00:00":{"excluded":true},"2026-01-07T10:00:00":{"title":"Moved","start":"2026-01-07T15:00:00"},"2026-01-20T09:00:00":{"title":"Extra"}}""",
        )
        val occ = expand(e, ms("2026-01-01T00:00:00"), ms("2026-02-01T00:00:00"), berlin).occurrences
        assertEquals(listOf("2026-01-05T10:00", "2026-01-07T10:00", "2026-01-20T09:00"), occ.map { it.recurrenceId.removeSuffix(":00") })
        assertEquals(ms("2026-01-07T15:00:00"), occ[1].startMs)
        assertEquals("Moved", (occ[1].json["title"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `an excluded rule removes what it generates`() {
        val e = event(
            """[{"frequency":"daily","count":7}]""",
            ""","excludedRecurrenceRules":[{"frequency":"weekly","byDay":[{"day":"sa"},{"day":"su"}]}]""",
        )
        assertEquals(5, starts(e, "2026-01-01T00:00:00", "2026-02-01T00:00:00").size)
    }

    @Test fun `a series keeps its wall-clock time across the daylight saving change`() {
        val e = obj("""{"start":"2026-03-27T10:00:00","timeZone":"Europe/Berlin","duration":"PT1H","recurrenceRules":[{"frequency":"daily","count":3}]}""")
        val occ = expand(e, ms("2026-03-01T00:00:00"), ms("2026-05-01T00:00:00"), berlin).occurrences
        assertEquals(listOf(10, 10, 10), occ.map { java.time.Instant.ofEpochMilli(it.startMs).atZone(berlin).hour })
        assertEquals(23 * 3_600_000L, occ[2].startMs - occ[1].startMs) // 29 Mar: the clocks went forward
    }

    @Test fun `a sub-daily rule is flagged and not expanded`() {
        val e = event("""[{"frequency":"hourly"}]""")
        val x = expand(e, ms("2026-01-01T00:00:00"), ms("2026-02-01T00:00:00"), berlin)
        assertTrue(x.unsupportedRule)
        assertEquals(1, x.occurrences.size)
    }

    @Test fun `durations parse with weeks, days and a sign`() {
        assertEquals(90 * 60_000L, parseDurationMs("PT1H30M"))
        assertEquals(8 * 86_400_000L, parseDurationMs("P1W1D"))
        assertEquals(-15 * 60_000L, parseDurationMs("-PT15M"))
        assertFalse(parseDurationMs("nonsense") != null)
    }

    @Test fun `a patch sets nested keys and removes on null`() {
        val base = obj("""{"title":"A","participants":{"p1":{"participationStatus":"needs-action"}}}""")
        val out = applyPatch(base, obj("""{"title":null,"participants/p1/participationStatus":"accepted"}"""))
        assertEquals(obj("""{"participants":{"p1":{"participationStatus":"accepted"}}}"""), out)
    }

    @Test fun `alarms come from offsets, defaults and skip email alerts, and a cancelled event`() {
        val now = ms("2026-01-05T00:00:00")
        fun row(id: String, json: String) = DavObjectEntity("a", "CalendarEvent", id, groupId = "cal", json = json)
        val rows = listOf(
            row("e1", """{"title":"One","start":"2026-01-05T10:00:00","timeZone":"Europe/Berlin","duration":"PT1H","alerts":{"x":{"trigger":{"@type":"OffsetTrigger","offset":"-PT15M"},"action":"display"},"m":{"trigger":{"@type":"OffsetTrigger","offset":"-PT1H"},"action":"email"}}}"""),
            row("e2", """{"title":"Def","start":"2026-01-05T12:00:00","timeZone":"Europe/Berlin","duration":"PT1H","useDefaultAlerts":true}"""),
            row("e3", """{"title":"Off","status":"cancelled","start":"2026-01-05T12:00:00","timeZone":"Europe/Berlin","alerts":{"x":{"trigger":{"@type":"OffsetTrigger","offset":"-PT15M"}}}}"""),
        )
        val cal = mapOf("cal" to obj("""{"defaultAlertsWithTime":{"d":{"trigger":{"@type":"OffsetTrigger","offset":"-PT30M"},"action":"display"}}}"""))
        val plan = planAlarms(rows, cal, now, 86_400_000L, berlin)
        assertEquals(listOf(ms("2026-01-05T09:45:00"), ms("2026-01-05T11:30:00")), plan.map { it.atMs })
        assertEquals(listOf("One", "Def"), plan.map { it.title })
    }
}

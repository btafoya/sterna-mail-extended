package app.sterna.core.data.dav

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** One occurrence of an event or task. [recurrenceId] is the local start the series gave it ("" for a one-off). */
data class Occurrence(
    val recurrenceId: String,
    val startMs: Long,
    val endMs: Long,
    val json: JsonObject,
)

/** An event's start, as UTC millis, read from JSCalendar `start` + `timeZone`; floating or all-day times use [device]. */
fun JsonObject.startMs(device: ZoneId = ZoneId.systemDefault()): Long? {
    val local = (this["start"] as? JsonPrimitive)?.contentOrNull?.let(::localDateTime) ?: return null
    return local.atZone(zoneOf(this, device)).toInstant().toEpochMilli()
}

/** JSCalendar `duration` ("P1DT2H30M15S", weeks too, with a leading "-" for offsets) in millis. */
fun parseDurationMs(text: String): Long? {
    val m = DURATION.matchEntire(text.trim()) ?: return null
    val (sign, w, d, h, min, s) = m.destructured
    val total = ((w.toLongOrNull() ?: 0) * 7 + (d.toLongOrNull() ?: 0)) * 86_400_000L +
        (h.toLongOrNull() ?: 0) * 3_600_000L + (min.toLongOrNull() ?: 0) * 60_000L + (s.toLongOrNull() ?: 0) * 1000L
    return if (sign == "-") -total else total
}

private val DURATION = Regex("""(-?)P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.\d+)?S)?)?""")

private fun localDateTime(text: String): LocalDateTime? =
    runCatching { LocalDateTime.parse(text.removeSuffix("Z")) }.getOrNull()

private fun zoneOf(event: JsonObject, device: ZoneId): ZoneId =
    (event["timeZone"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: device

/** What expansion can say about an event: its occurrences in the window, and whether a rule was too exotic to expand. */
class Expansion(val occurrences: List<Occurrence>, val unsupportedRule: Boolean)

const val MAX_OCCURRENCES_PER_EVENT = 1000
private const val MAX_PERIODS = 20_000

/**
 * The occurrences of [event] (a JSCalendar Event or Task as the server sent it) starting in
 * [fromMs, toMs), or overlapping it for a one-off. Recurrence is RFC 8984's: `recurrenceRules`,
 * `excludedRecurrenceRules`, `recurrenceOverrides`. Frequencies below daily are not expanded
 * ([Expansion.unsupportedRule]): the first occurrence is shown alone rather than a wrong series.
 * ponytail: no sub-daily rules, no `byWeekNo`/`byYearDay`/`byHour`; add when a server sends them.
 */
fun expand(event: JsonObject, fromMs: Long, toMs: Long, device: ZoneId = ZoneId.systemDefault()): Expansion {
    val startLocal = ((event["start"] ?: event["due"]) as? JsonPrimitive)?.contentOrNull?.let(::localDateTime)
        ?: return Expansion(emptyList(), false)
    val zone = zoneOf(event, device)
    val durationMs = (event["duration"] as? JsonPrimitive)?.contentOrNull?.let(::parseDurationMs) ?: 0L
    val rules = (event["recurrenceRules"] as? JsonArray)?.map { it.jsonObject } ?: listOfNotNull(event["recurrenceRule"] as? JsonObject)
    fun make(local: LocalDateTime, patch: JsonObject?): Occurrence {
        var json = event
        var start = local.atZone(zone).toInstant().toEpochMilli()
        var dur = durationMs
        if (patch != null) {
            json = JsonObject(event + patch.filterKeys { "/" !in it })
        }
        // An occurrence starts at its own time, not the series': the editor and a later edit read it from here.
        if (rules.isNotEmpty() && event["start"] != null && patch?.get("start") == null) json = JsonObject(json + ("start" to JsonPrimitive(key(local))))
        if (patch != null) {
            (patch["start"] as? JsonPrimitive)?.contentOrNull?.let(::localDateTime)
                ?.let { start = it.atZone(zone).toInstant().toEpochMilli() }
            (patch["duration"] as? JsonPrimitive)?.contentOrNull?.let(::parseDurationMs)?.let { dur = it }
        }
        return Occurrence(if (rules.isEmpty()) "" else key(local), start, start + dur, json)
    }
    if (rules.isEmpty()) {
        val o = make(startLocal, null)
        return Expansion(listOf(o).filter { it.startMs < toMs && maxOf(it.endMs, it.startMs) >= fromMs }, false)
    }
    val unsupported = rules.any { frequency(it) == null }
    val supported = rules.filter { frequency(it) != null }
    val overrides = (event["recurrenceOverrides"] as? JsonObject).orEmpty()
    val excludedByRule = (event["excludedRecurrenceRules"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        .filter { frequency(it) != null }
        .flatMap { generate(it, startLocal, zone, toMs) }.toSet()
    val dates = if (supported.isEmpty()) listOf(startLocal) else supported.flatMap { generate(it, startLocal, zone, toMs) }.distinct().sorted()
    val out = mutableListOf<Occurrence>()
    // Added occurrences, `recurrenceOverrides` keys that are not part of the rule.
    val extras = overrides.keys.mapNotNull(::localDateTime).filter { it !in dates }
    for (local in (dates + extras).sorted()) {
        val patch = overrides[key(local)] as? JsonObject
        if (local in excludedByRule || patch?.get("excluded")?.let { (it as? JsonPrimitive)?.booleanOrNull } == true) continue
        val o = make(local, patch)!!
        if (o.startMs >= toMs) { if (patch == null) break else continue }
        if (maxOf(o.endMs, o.startMs) >= fromMs) out += o
        if (out.size >= MAX_OCCURRENCES_PER_EVENT) break
    }
    return Expansion(out, unsupported)
}

private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())

/** A local time as JSCalendar writes a recurrence id: seconds always present. */
private fun key(local: LocalDateTime): String = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(local)

private fun frequency(rule: JsonObject): String? =
    (rule["frequency"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("daily", "weekly", "monthly", "yearly") }

private fun DayOfWeek.code() = name.substring(0, 2).lowercase()
private fun dayOf(code: String): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.code() == code }

private class ByDay(val day: DayOfWeek, val nth: Int?)

private fun byDays(rule: JsonObject): List<ByDay> = (rule["byDay"] as? JsonArray).orEmpty().mapNotNull { e ->
    val o = e as? JsonObject ?: return@mapNotNull null
    val d = dayOf((o["day"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null) ?: return@mapNotNull null
    ByDay(d, (o["nthOfPeriod"] as? JsonPrimitive)?.intOrNull)
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

private fun ints(rule: JsonObject, key: String): List<Int> =
    (rule[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }

private fun months(rule: JsonObject): List<Int> =
    (rule["byMonth"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.removeSuffix("L")?.toIntOrNull() }

/** Local start times a rule produces from [start], in order, until `count`, `until`, or [toMs] is passed. */
private fun generate(rule: JsonObject, start: LocalDateTime, zone: ZoneId, toMs: Long): List<LocalDateTime> {
    val freq = frequency(rule)!!
    val interval = (rule["interval"] as? JsonPrimitive)?.intOrNull?.coerceAtLeast(1) ?: 1
    val count = (rule["count"] as? JsonPrimitive)?.intOrNull
    val until = (rule["until"] as? JsonPrimitive)?.contentOrNull?.let(::localDateTime)
    val weekStart = dayOf((rule["firstDayOfWeek"] as? JsonPrimitive)?.contentOrNull ?: "mo") ?: DayOfWeek.MONDAY
    val byDay = byDays(rule)
    val byMonthDay = ints(rule, "byMonthDay")
    val byMonth = months(rule)
    val setPos = ints(rule, "bySetPosition")
    val time: LocalTime = start.toLocalTime()
    val out = mutableListOf<LocalDateTime>()
    var produced = 0
    var k = 0L
    while (k < MAX_PERIODS) {
        val dates: List<LocalDate> = when (freq) {
            "daily" -> listOf(start.toLocalDate().plusDays(k * interval)).filter { d ->
                (byMonth.isEmpty() || d.monthValue in byMonth) && (byDay.isEmpty() || byDay.any { it.day == d.dayOfWeek }) &&
                    (byMonthDay.isEmpty() || monthDayMatches(d, byMonthDay))
            }
            "weekly" -> {
                val first = start.toLocalDate().with(TemporalAdjusters.previousOrSame(weekStart)).plusWeeks(k * interval)
                (if (byDay.isEmpty()) listOf(start.dayOfWeek) else byDay.map { it.day }).map { dow ->
                    first.plusDays(((dow.value - weekStart.value + 7) % 7).toLong())
                }.filter { byMonth.isEmpty() || it.monthValue in byMonth }
            }
            "monthly" -> monthCandidates(YearMonth.from(start).plusMonths(k * interval), start, byDay, byMonthDay)
                .filter { byMonth.isEmpty() || it.monthValue in byMonth }
            else -> { // yearly
                val year = start.year + (k * interval).toInt()
                (byMonth.ifEmpty { listOf(start.monthValue) }).flatMap { m ->
                    monthCandidates(YearMonth.of(year, m), start, byDay, byMonthDay)
                }
            }
        }.sorted()
        val picked = if (setPos.isEmpty()) dates else setPos.mapNotNull { p ->
            if (p > 0) dates.getOrNull(p - 1) else dates.getOrNull(dates.size + p)
        }.sorted()
        for (d in picked) {
            val local = LocalDateTime.of(d, time)
            if (local < start) continue
            if (until != null && local > until) return out
            out += local
            produced++
            if (count != null && produced >= count) return out
            if (out.size >= MAX_OCCURRENCES_PER_EVENT) return out
        }
        // Past the window: nothing later in this rule can start before it ends.
        val firstOfNext = when (freq) {
            "daily" -> start.toLocalDate().plusDays((k + 1) * interval)
            "weekly" -> start.toLocalDate().plusWeeks((k + 1) * interval)
            "monthly" -> start.toLocalDate().plusMonths((k + 1) * interval)
            else -> start.toLocalDate().plusYears((k + 1) * interval)
        }
        // A week of slack: a weekly period's days can fall before its first date.
        if (ZonedDateTime.of(LocalDateTime.of(firstOfNext, LocalTime.MIN), zone).toInstant().toEpochMilli() > toMs + 7 * 86_400_000L) return out
        k++
    }
    return out
}

private fun monthDayMatches(d: LocalDate, days: List<Int>) =
    days.any { if (it > 0) d.dayOfMonth == it else d.dayOfMonth == d.lengthOfMonth() + it + 1 }

private fun monthCandidates(month: YearMonth, start: LocalDateTime, byDay: List<ByDay>, byMonthDay: List<Int>): List<LocalDate> = when {
    byMonthDay.isNotEmpty() ->
        byMonthDay.mapNotNull { n ->
            val day = if (n > 0) n else month.lengthOfMonth() + n + 1
            day.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
        }.filter { d -> byDay.isEmpty() || byDay.any { it.day == d.dayOfWeek } }
    byDay.isNotEmpty() -> byDay.flatMap { bd ->
        val all = (1..month.lengthOfMonth()).map(month::atDay).filter { it.dayOfWeek == bd.day }
        when {
            bd.nth == null -> all
            bd.nth > 0 -> listOfNotNull(all.getOrNull(bd.nth - 1))
            else -> listOfNotNull(all.getOrNull(all.size + bd.nth))
        }
    }
    else -> listOfNotNull(start.dayOfMonth.takeIf { it <= month.lengthOfMonth() }?.let(month::atDay))
}

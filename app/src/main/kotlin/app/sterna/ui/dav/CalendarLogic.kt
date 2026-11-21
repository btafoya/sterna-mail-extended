package app.sterna.ui.dav

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/*
 * The pure half of the calendar screen: month-grid dates, and turning the editor's form into the
 * JSCalendar object the server takes (and into the patch of an edit). No Android, so it is unit tested.
 */

enum class Repeat(val frequency: String?) { NONE(null), DAILY("daily"), WEEKLY("weekly"), MONTHLY("monthly"), YEARLY("yearly") }

/** Offset in minutes before the start; null offset = no alert. */
enum class AlertPreset(val offset: String?) {
    NONE(null), AT_START("PT0S"), MIN10("-PT10M"), MIN30("-PT30M"), HOUR1("-PT1H"), DAY1("-P1D")
}

/**
 * What the editor holds. [repeat], [alert] and [attendees] are null for a value the editor cannot
 * show (a rule with `byDay`, two alerts): it is then left exactly as the server has it.
 */
data class EventForm(
    val isTask: Boolean = false,
    val title: String = "",
    val allDay: Boolean = false,
    val start: LocalDateTime,
    /** Event: the end (all-day: the last day, inclusive). Task: the due time, or null for no due date. */
    val end: LocalDateTime?,
    val completed: Boolean = false,
    val calendarId: String = "",
    val description: String = "",
    val repeat: Repeat? = Repeat.NONE,
    val repeatCount: Int? = null,
    val alert: AlertPreset? = AlertPreset.NONE,
    /** Comma- or space-separated addresses. */
    val attendees: String? = "",
)

private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

fun localString(t: LocalDateTime): String = LOCAL.format(t)

/** The weeks a month view shows: whole weeks from [weekStart], enough to cover the month. */
fun monthGrid(month: YearMonth, weekStart: DayOfWeek): List<LocalDate> {
    val first = month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))
    val last = month.atEndOfMonth()
    val weeks = ((java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1 + 6) / 7).toInt()
    return List(weeks * 7) { first.plusDays(it.toLong()) }
}

/** JSCalendar duration for a span: whole days as P#D, else PT#H#M. */
fun durationString(d: Duration): String {
    if (d.isZero || d.isNegative) return "PT0S"
    val days = d.toDays()
    val rest = d.minusDays(days)
    return buildString {
        append('P')
        if (days > 0) append(days).append('D')
        if (!rest.isZero) {
            append('T')
            if (rest.toHours() > 0) append(rest.toHours()).append('H')
            if (rest.toMinutesPart() > 0) append(rest.toMinutesPart()).append('M')
        }
    }
}

private fun parseLocal(text: String?): LocalDateTime? =
    text?.let { runCatching { LocalDateTime.parse(it.removeSuffix("Z")) }.getOrNull() }

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String) = this[key] as? JsonObject

/** A single plain rule the editor can show, as its preset and count; null for anything richer. */
fun repeatOf(json: JsonObject): Pair<Repeat, Int?>? {
    val rules = (json["recurrenceRules"] as? JsonArray)?.map { it as? JsonObject ?: return null }
        ?: listOfNotNull(json.obj("recurrenceRule"))
    if (rules.isEmpty()) return Repeat.NONE to null
    val r = rules.singleOrNull() ?: return null
    if (!r.keys.all { it in setOf("@type", "frequency", "interval", "count") }) return null
    if ((r["interval"] as? JsonPrimitive)?.intOrNull?.let { it != 1 } == true) return null
    val repeat = Repeat.entries.firstOrNull { it.frequency != null && it.frequency == r.str("frequency") } ?: return null
    return repeat to (r["count"] as? JsonPrimitive)?.intOrNull
}

fun alertOf(json: JsonObject): AlertPreset? {
    val alerts = json.obj("alerts") ?: return AlertPreset.NONE
    if (alerts.isEmpty()) return AlertPreset.NONE
    val a = alerts.values.singleOrNull() as? JsonObject ?: return null
    val t = a.obj("trigger") ?: return null
    if (t.str("@type") == "AbsoluteTrigger" || (t.str("relativeTo") ?: "start") != "start") return null
    return AlertPreset.entries.firstOrNull { it.offset != null && it.offset == t.str("offset") }
}

fun attendeesOf(json: JsonObject): String? {
    val p = json.obj("participants") ?: return ""
    return p.values.mapNotNull { it as? JsonObject }
        .filter { it.obj("roles")?.get("owner") == null }
        .mapNotNull { it.str("email") }.joinToString(", ")
}

/** The form for an existing event or task, as stored: times are the stored local ones, in the event's own zone. */
fun formOf(json: JsonObject, calendarId: String, fallback: LocalDateTime): EventForm {
    val isTask = json.str("@type") == "Task"
    val allDay = (json["showWithoutTime"] as? JsonPrimitive)?.booleanOrNull == true
    val start = parseLocal(json.str("start")) ?: parseLocal(json.str("due")) ?: fallback
    val durationMs = json.str("duration")?.let(::parseIsoDuration) ?: 0L
    var end: LocalDateTime? = start.plus(Duration.ofMillis(durationMs))
    if (allDay) end = end!!.minusDays(1).coerceAtLeast(start)
    if (isTask) end = parseLocal(json.str("due"))
    val rep = repeatOf(json)
    return EventForm(
        isTask = isTask,
        title = json.str("title").orEmpty(),
        allDay = allDay,
        start = start,
        end = end,
        completed = json.str("progress") == "completed",
        calendarId = calendarId,
        description = json.str("description").orEmpty(),
        repeat = rep?.first, repeatCount = rep?.second,
        alert = alertOf(json),
        attendees = attendeesOf(json),
    )
}

private fun LocalDateTime.coerceAtLeast(o: LocalDateTime) = if (this < o) o else this

/** `PT1H30M`, `P1DT2H`, `P1W` in millis; the shape the server writes and [durationString] writes. */
fun parseIsoDuration(text: String): Long? {
    val m = Regex("""P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(text.trim()) ?: return null
    fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
    return ((g(1) * 7 + g(2)) * 86_400L + g(3) * 3600L + g(4) * 60L + g(5)) * 1000L
}

/**
 * The object to send for [f], over [base] when editing (so every property the editor does not know
 * survives). Repeat, alerts and attendees are rewritten only when the form changed them.
 * Over a stored series it edits the whole series; one occurrence, or this and the following, goes through
 * the functions below.
 */
fun applyForm(base: JsonObject?, f: EventForm, uid: String, deviceZone: String): JsonObject {
    val m = (base ?: JsonObject(emptyMap())).toMutableMap()
    val old = base ?: JsonObject(emptyMap())
    if (base == null) {
        m["@type"] = JsonPrimitive(if (f.isTask) "Task" else "Event")
        m["uid"] = JsonPrimitive(uid)
    }
    m["calendarIds"] = buildJsonObject { put(f.calendarId, true) }
    m["title"] = JsonPrimitive(f.title.trim())
    if (f.description.isBlank()) m.remove("description") else m["description"] = JsonPrimitive(f.description)

    if (f.isTask) {
        if (f.end == null) m.remove("due") else m["due"] = JsonPrimitive(localString(f.end))
        m["progress"] = JsonPrimitive(if (f.completed) "completed" else if (old.str("progress") == "in-process") "in-process" else "needs-action")
        if (f.completed) m["percentComplete"] = JsonPrimitive(100) else if (old.str("progress") == "completed") m.remove("percentComplete")
        // A task with a due time needs a zone to mean the same moment everywhere.
        if (f.end != null && "timeZone" !in m) m["timeZone"] = JsonPrimitive(deviceZone)
    } else {
        val start = if (f.allDay) f.start.toLocalDate().atStartOfDay() else f.start
        val end = f.end ?: start
        val span = if (f.allDay) Duration.ofDays(java.time.temporal.ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()).coerceAtLeast(0) + 1)
        else Duration.between(start, end.coerceAtLeast(start))
        m["start"] = JsonPrimitive(localString(start))
        m["duration"] = JsonPrimitive(durationString(span))
        if (f.allDay) { m["showWithoutTime"] = JsonPrimitive(true); m.remove("timeZone") }
        else { m.remove("showWithoutTime"); if ("timeZone" !in m) m["timeZone"] = JsonPrimitive(deviceZone) }
    }

    val oldRepeat = repeatOf(old)
    if (f.repeat != null && (oldRepeat == null || (f.repeat to f.repeatCount) != oldRepeat)) {
        m.remove("recurrenceRule")
        if (f.repeat == Repeat.NONE) m.remove("recurrenceRules")
        else m["recurrenceRules"] = JsonArray(listOf(buildJsonObject {
            put("frequency", f.repeat.frequency!!)
            f.repeatCount?.takeIf { it > 0 }?.let { put("count", it) }
        }))
    }
    if (f.alert != null && f.alert != alertOf(old)) {
        if (f.alert == AlertPreset.NONE) m.remove("alerts")
        else m["alerts"] = buildJsonObject {
            put("a1", buildJsonObject {
                put("trigger", buildJsonObject { put("@type", "OffsetTrigger"); put("offset", f.alert.offset!!); put("relativeTo", "start") })
                put("action", "display")
            })
        }
        // The calendar's default alerts would otherwise still apply on top of the chosen one.
        if (m["useDefaultAlerts"] != null) m["useDefaultAlerts"] = JsonPrimitive(false)
    }
    if (f.attendees != null && f.attendees != attendeesOf(old)) {
        val emails = f.attendees.split(',', ' ', ';', '\n').map { it.trim() }.filter { it.contains('@') }.distinct()
        val existing = old.obj("participants").orEmptyMap()
        val owners = existing.filterValues { (it as? JsonObject)?.obj("roles")?.get("owner") != null }
        val byEmail = existing.entries.filter { it.key !in owners }.associateBy { (it.value as? JsonObject)?.str("email") }
        val attendees = emails.mapIndexed { i, email ->
            byEmail[email]?.let { it.key to it.value }
                ?: ("n$i" to buildJsonObject {
                    put("email", email)
                    put("roles", buildJsonObject { put("attendee", true) })
                    put("participationStatus", "needs-action")
                })
        }
        val all = owners.toList() + attendees
        if (all.isEmpty()) m.remove("participants") else m["participants"] = JsonObject(all.toMap())
    }
    return JsonObject(m)
}

private fun JsonObject?.orEmptyMap(): Map<String, JsonElement> = this ?: emptyMap()

/** The top-level properties of [new] that differ from [old]; a property gone from [new] is null (removed). */
fun diffPatch(old: JsonObject, new: JsonObject): JsonObject {
    val out = mutableMapOf<String, JsonElement>()
    new.forEach { (k, v) -> if (old[k] != v) out[k] = v }
    old.keys.filter { it !in new }.forEach { out[it] = JsonNull }
    return JsonObject(out)
}

// ---- one occurrence of a series ----

/** Which part of a recurring series an edit or a delete is for. */
enum class Scope { THIS, FOLLOWING, ALL }

private const val OVERRIDES = "recurrenceOverrides"

/** Never inside an override (the server refuses them) and never copied by hand. */
private val SERIES_ONLY = setOf("@type", "uid", "calendarIds", "recurrenceRule", "recurrenceRules", "excludedRecurrenceRule", "excludedRecurrenceRules", OVERRIDES)

/** What a new series made from a cut one carries over from the old. */
private val SERIES_KEYS = setOf(
    "@type", "title", "description", "start", "duration", "timeZone", "showWithoutTime", "recurrenceRule", "recurrenceRules",
    "excludedRecurrenceRules", "alerts", "useDefaultAlerts", "participants", "status", "freeBusyStatus", "privacy", "locations",
    "calendarIds", "color", "keywords", "categories",
)

private fun JsonObject.overrides(): Map<String, JsonElement> = obj(OVERRIDES) ?: emptyMap()

/** The patch that stores [map] as the whole `recurrenceOverrides` (the server cannot patch into one that is absent). */
private fun overridesPatch(map: Map<String, JsonElement>): JsonObject = JsonObject(mapOf(OVERRIDES to if (map.isEmpty()) JsonNull else JsonObject(map)))

/** [stored] with the override of [rid] applied and starting at its own time: the occurrence as one event. */
fun occurrenceBase(stored: JsonObject, rid: String): JsonObject {
    val patch = stored.overrides()[rid] as? JsonObject
    val merged = if (patch == null) stored else JsonObject(stored + patch.filterKeys { "/" !in it })
    return if (patch?.get("start") != null) merged else JsonObject(merged + ("start" to JsonPrimitive(rid)))
}

/** Drops only the occurrence [rid]. */
fun excludePatch(stored: JsonObject, rid: String): JsonObject =
    overridesPatch(stored.overrides() + (rid to buildJsonObject { put("excluded", true) }))

/** Changes only the occurrence [rid] to [form]; empty when nothing differs. */
fun editOccurrencePatch(stored: JsonObject, rid: String, form: EventForm, zone: String): JsonObject {
    val base = occurrenceBase(stored, rid)
    val changed = diffPatch(base, applyForm(base, form, "", zone)).filterKeys { it !in SERIES_ONLY }
    if (changed.isEmpty()) return JsonObject(emptyMap())
    val old = stored.overrides()[rid] as? JsonObject ?: JsonObject(emptyMap())
    return overridesPatch(stored.overrides() + (rid to JsonObject(old + changed)))
}

/** [form] as the whole series' form: the editor shows an occurrence's date, the series keeps its own. */
fun wholeSeriesForm(stored: JsonObject, rid: String, form: EventForm): EventForm {
    val shift = Duration.between(parseLocal(rid) ?: return form, parseLocal(stored.str("start")) ?: return form)
    return form.copy(start = form.start.plus(shift), end = form.end?.plus(shift))
}

/** True when [rid] is the series' first occurrence, so "this and following" is the whole series. */
fun isFirstOccurrence(stored: JsonObject, rid: String): Boolean = parseLocal(stored.str("start")) == parseLocal(rid)

/** Ends the series just before [rid]: a `count` becomes an `until`, and overrides from [rid] on go. */
fun cutSeriesPatch(stored: JsonObject, rid: String): JsonObject {
    val until = JsonPrimitive(localString((parseLocal(rid) ?: return JsonObject(emptyMap())).minusSeconds(1)))
    fun cut(r: JsonElement) = JsonObject((r as? JsonObject ?: JsonObject(emptyMap())).filterKeys { it != "count" } + ("until" to until))
    val out = mutableMapOf<String, JsonElement>()
    (stored["recurrenceRules"] as? JsonArray)?.let { out["recurrenceRules"] = JsonArray(it.map(::cut)) }
    stored.obj("recurrenceRule")?.let { out["recurrenceRule"] = cut(it) }
    if (stored.overrides().keys.any { it >= rid }) out += overridesPatch(stored.overrides().filterKeys { it < rid })
    return JsonObject(out)
}

/**
 * The new series that takes over from [rid]: the old one's properties, the overrides from [rid] on, a
 * `count` reduced by the [before] occurrences the old series keeps, and [form] on top.
 */
fun followingSeries(stored: JsonObject, rid: String, before: Int, form: EventForm, uid: String, zone: String): JsonObject {
    val kept = occurrenceBase(stored, rid).filterKeys { it in SERIES_KEYS }.toMutableMap()
    fun recount(r: JsonElement) = (r as? JsonObject)?.let { o ->
        (o["count"] as? JsonPrimitive)?.intOrNull?.let { JsonObject(o + ("count" to JsonPrimitive(it - before))) } ?: o
    } ?: r
    (kept["recurrenceRules"] as? JsonArray)?.let { kept["recurrenceRules"] = JsonArray(it.map(::recount)) }
    kept["recurrenceRule"]?.let { kept["recurrenceRule"] = recount(it) }
    stored.overrides().filterKeys { it > rid }.takeIf { it.isNotEmpty() }?.let { kept[OVERRIDES] = JsonObject(it) }
    kept["uid"] = JsonPrimitive(uid)
    val oldCount = repeatOf(stored)?.second
    val adjusted = if (oldCount != null && form.repeatCount == oldCount) form.copy(repeatCount = oldCount - before) else form
    return applyForm(JsonObject(kept), adjusted, uid, zone)
}

package app.sterna.core.data.dav

import app.sterna.core.data.db.DavObjectEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneId

/** A notification to raise at [atMs] for one occurrence. [key] names it, stable across plans, so a rescheduled alarm replaces itself. */
data class PlannedAlarm(val key: String, val atMs: Long, val title: String, val startMs: Long)

/**
 * The alarms of [events] (rows of `CalendarEvent`, events and tasks) falling in [nowMs, nowMs + horizonMs],
 * from each occurrence's own alerts, or its calendar's defaults when `useDefaultAlerts`. Only `display`
 * alerts: an `email` alert is the server's to send. [calendars] is calendar id -> its JSON.
 * ponytail: a cancelled event and a completed task are skipped; snooze is the notification's, not modelled here.
 */
fun planAlarms(
    events: List<DavObjectEntity>,
    calendars: Map<String, JsonObject>,
    nowMs: Long,
    horizonMs: Long,
    device: ZoneId = ZoneId.systemDefault(),
): List<PlannedAlarm> {
    val to = nowMs + horizonMs
    val out = mutableListOf<PlannedAlarm>()
    for (row in events) {
        val event = Json.parseToJsonElement(row.json).jsonObject
        if (event.str("status") == "cancelled" || event.str("progress") == "completed") continue
        // An alarm can precede its occurrence by days: look that far ahead of the window for the occurrence.
        val look = expand(event, nowMs - 7 * 86_400_000L, to + 7 * 86_400_000L, device)
        for (o in look.occurrences) {
            for ((alertId, alert) in alertsOf(o.json, calendars[row.groupId])) {
                if (alert.str("action") == "email") continue
                val at = alarmTime(alert.obj("trigger") ?: continue, o.startMs, o.endMs) ?: continue
                if (at in nowMs..to) out += PlannedAlarm("${row.accountId}/${row.id}/${o.recurrenceId}/$alertId", at, o.json.str("title").orEmpty(), o.startMs)
            }
        }
    }
    return out.sortedBy { it.atMs }
}

private fun alertsOf(event: JsonObject, calendar: JsonObject?): Map<String, JsonObject> {
    val own = event.obj("alerts")
    if (own != null) return own.mapValues { it.value.jsonObject }
    if ((event["useDefaultAlerts"] as? JsonPrimitive)?.booleanOrNull != true || calendar == null) return emptyMap()
    val key = if ((event["showWithoutTime"] as? JsonPrimitive)?.booleanOrNull == true) "defaultAlertsWithoutTime" else "defaultAlertsWithTime"
    return calendar.obj(key)?.mapValues { it.value.jsonObject }.orEmpty()
}

private fun alarmTime(trigger: JsonObject, startMs: Long, endMs: Long): Long? = when (trigger.str("@type")) {
    "AbsoluteTrigger" -> trigger.str("when")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    else -> trigger.str("offset")?.let(::parseDurationMs)?.let { off ->
        off + if (trigger.str("relativeTo") == "end") endMs else startMs
    }
}

private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.obj(key: String) = this[key] as? JsonObject
private fun <K, V> Map<K, V>?.orEmpty(): Map<K, V> = this ?: emptyMap()

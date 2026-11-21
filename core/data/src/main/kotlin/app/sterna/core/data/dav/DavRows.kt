package app.sterna.core.data.dav

import app.sterna.core.data.db.DavObjectEntity
import app.sterna.core.jmap.JmapObject
import app.sterna.core.jmap.JmapType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

/** The server JSON of one object as the row a screen reads, with the columns the queries filter on. */
fun davRow(accountId: String, type: JmapType, o: JmapObject, device: ZoneId = ZoneId.systemDefault()): DavObjectEntity {
    val j = o.json
    return when (type) {
        JmapType.CALENDAR_EVENT -> {
            val isTask = o.string("@type") == "Task"
            // The server sends utcStart/utcEnd for what it stored; a row made on the phone has neither yet.
            val utcStart = o.string("utcStart")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val utcEnd = o.string("utcEnd")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
            val startMs = utcStart ?: j.anchorMs(device)
            // `recurrenceRule` (one rule) is accepted by the reference server beside the draft's `recurrenceRules`.
            val recurring = (j["recurrenceRules"] as? JsonArray)?.isNotEmpty() == true || j["recurrenceRule"] is JsonObject
            val dur = o.string("duration")?.let(::parseDurationMs) ?: 0L
            DavObjectEntity(
                accountId, type.name, o.id,
                groupId = (j["calendarIds"] as? JsonObject)?.keys?.firstOrNull().orEmpty(),
                name = o.string("title").orEmpty(),
                startMs = startMs,
                endMs = if (recurring) null else utcEnd ?: startMs?.plus(dur),
                recurring = recurring,
                isTask = isTask,
                json = j.toString(),
            )
        }
        JmapType.CALENDAR -> DavObjectEntity(accountId, type.name, o.id, name = o.string("name").orEmpty(), json = j.toString())
        JmapType.ADDRESS_BOOK -> DavObjectEntity(accountId, type.name, o.id, name = o.string("name").orEmpty(), json = j.toString())
        JmapType.CONTACT_CARD -> DavObjectEntity(
            accountId, type.name, o.id,
            groupId = (j["addressBookIds"] as? JsonObject)?.keys?.firstOrNull().orEmpty(),
            name = cardName(j), json = j.toString(),
        )
        else -> DavObjectEntity( // FileNode
            accountId, type.name, o.id,
            groupId = o.string("parentId").orEmpty(), name = o.string("name").orEmpty(),
            // Folders sort first through isTask = true; the column is otherwise unused for files.
            isTask = o.string("blobId") == null, json = j.toString(),
        )
    }
}

/** The start a one-off or a series is anchored on: `start`, or a task's `due` when it has no start. */
fun JsonObject.anchorMs(device: ZoneId = ZoneId.systemDefault()): Long? =
    startMs(device) ?: (this["due"] as? JsonPrimitive)?.contentOrNull?.let { JsonObject(mapOf("start" to JsonPrimitive(it)) + filterKeys { k -> k == "timeZone" }).startMs(device) }

/** `name.full`, else given + surname components, else the first address: what a list shows. */
fun cardName(card: JsonObject): String {
    val name = card["name"] as? JsonObject
    name?.get("full")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val parts = (name?.get("components") as? JsonArray).orEmpty().map { it.jsonObject }
        .filter { it["kind"]?.jsonPrimitive?.contentOrNull in setOf("given", "surname") }
        .mapNotNull { it["value"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
    if (parts.isNotEmpty()) return parts.joinToString(" ")
    return (card["emails"] as? JsonObject)?.values?.firstNotNullOfOrNull { it.jsonObject["address"]?.jsonPrimitive?.contentOrNull }.orEmpty()
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

/**
 * [patch] (JSON-pointer keys, "a/b/c") applied to [base]; a null value removes the key. This is how
 * an edit is shown at once, before the server has taken it.
 */
fun applyPatch(base: JsonObject, patch: JsonObject): JsonObject {
    var out: JsonElement = base
    for ((path, value) in patch) out = setPath(out, path.split('/').map { it.replace("~1", "/").replace("~0", "~") }, value)
    return out as JsonObject
}

private fun setPath(node: JsonElement, path: List<String>, value: JsonElement): JsonElement {
    val map = ((node as? JsonObject) ?: JsonObject(emptyMap())).toMutableMap()
    val head = path.first()
    if (path.size == 1) {
        if (value is JsonNull) map.remove(head) else map[head] = value
    } else {
        map[head] = setPath(map[head] ?: JsonObject(emptyMap()), path.drop(1), value)
    }
    return JsonObject(map)
}

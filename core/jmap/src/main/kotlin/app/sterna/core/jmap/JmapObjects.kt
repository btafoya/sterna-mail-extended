package app.sterna.core.jmap

import app.sterna.core.jmap.model.JmapSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * One generic get / query / changes / set for the types added after mail and contacts (calendars,
 * tasks, address books, contact cards, file nodes). An object is its id plus the server's JSON,
 * unread: the app extracts the few fields it filters on and keeps the rest verbatim, so an edit
 * sent back as a patch never drops a property this client does not know.
 * ponytail: untyped on purpose, typed models per draft are 4x the code for the same screens.
 */

/** A JMAP data type, the capability it is used under, and the properties asked for (null = all). */
class JmapType(val name: String, val capability: String, val properties: List<String>? = null) {
    companion object {
        val CALENDAR = JmapType("Calendar", Jmap.CALENDARS_CAPABILITY)
        val CALENDAR_EVENT = JmapType("CalendarEvent", Jmap.CALENDARS_CAPABILITY)
        val ADDRESS_BOOK = JmapType("AddressBook", Jmap.CONTACTS_CAPABILITY)
        val CONTACT_CARD = JmapType("ContactCard", Jmap.CONTACTS_CAPABILITY)
        val FILE_NODE = JmapType("FileNode", Jmap.FILENODE_CAPABILITY)
    }
}

class JmapObject(val id: String, val json: JsonObject) {
    fun string(key: String): String? = (json[key] as? JsonPrimitive)?.contentOrNull
    fun bool(key: String): Boolean? = (json[key] as? JsonPrimitive)?.booleanOrNull
    fun int(key: String): Int? = (json[key] as? JsonPrimitive)?.intOrNull
    fun obj(key: String): JsonObject? = json[key] as? JsonObject
}

class ObjectSnapshot(val objects: List<JmapObject>, val state: String?)

/** [replacesAll]: the server could not compute a delta, [changed] is the whole collection. */
class ObjectChanges(
    val changed: List<JmapObject>,
    val destroyed: List<String>,
    val newState: String?,
    val replacesAll: Boolean,
)

class SetError(val type: String, val description: String?)

class ObjectSetResult(
    val newState: String?,
    /** client id -> the object the server made. */
    val created: Map<String, JmapObject>,
    val updated: Set<String>,
    val destroyed: Set<String>,
    val failed: Map<String, SetError>,
)

const val JMAP_STATE_MISMATCH = "stateMismatch"
private const val CANNOT_CALCULATE_CHANGES = "cannotCalculateChanges"

private fun request(type: JmapType, vararg calls: Triple<String, String, JsonObject>): JsonObject = buildJsonObject {
    putJsonArray("using") { add(JsonPrimitive(Jmap.CORE_CAPABILITY)); add(JsonPrimitive(type.capability)) }
    put("methodCalls", JsonArray(calls.map { (m, id, args) -> JsonArray(listOf(JsonPrimitive(m), args, JsonPrimitive(id))) }))
}

private fun propsOf(type: JmapType): JsonElement =
    type.properties?.let { JsonArray(it.map(::JsonPrimitive)) } ?: JsonNull

private fun objectsOf(args: JsonObject): List<JmapObject> =
    (args["list"] as? JsonArray).orEmpty().map { val o = it.jsonObject; JmapObject(o["id"]!!.jsonPrimitive.content, o) }

private fun errors(args: JsonObject, key: String): Map<String, SetError> =
    (args[key] as? JsonObject).orEmpty().mapValues { (_, v) ->
        SetError(v.jsonObject["type"]?.jsonPrimitive?.contentOrNull ?: "unknown", v.jsonObject["description"]?.jsonPrimitive?.contentOrNull)
    }

private fun JsonObject?.orEmpty() = this ?: JsonObject(emptyMap())

/** The whole collection and the state it was read at. A `/query` + back-referenced `/get`, paged by
 *  `position`; [paged] false is for small collections (`Calendar`, `AddressBook`) with no usable query. */
suspend fun JmapClient.getAllObjects(
    session: JmapSession,
    auth: JmapAuth,
    type: JmapType,
    paged: Boolean = true,
): ObjectSnapshot? {
    val accountId = session.accountIdFor(type.capability) ?: return null
    if (!paged) {
        val args = methodResponseArgs(
            postJmap(session, auth, request(type, Triple("${type.name}/get", "g", buildJsonObject {
                put("accountId", accountId); put("ids", JsonNull); put("properties", propsOf(type))
            }))),
            "${type.name}/get",
        )
        return ObjectSnapshot(objectsOf(args), args["state"]?.jsonPrimitive?.contentOrNull)
    }
    val all = mutableListOf<JmapObject>()
    var state: String? = null
    var position = 0
    val pageSize = session.getBatchSize()
    while (true) {
        val body = postJmap(session, auth, request(
            type,
            Triple("${type.name}/query", "q", buildJsonObject {
                put("accountId", accountId); put("position", position); put("limit", pageSize); put("calculateTotal", true)
            }),
            Triple("${type.name}/get", "g", buildJsonObject {
                put("accountId", accountId)
                put("#ids", buildJsonObject { put("resultOf", "q"); put("name", "${type.name}/query"); put("path", "/ids") })
                put("properties", propsOf(type))
            }),
        ))
        val query = methodResponseArgs(body, "${type.name}/query")
        val got = methodResponseArgs(body, "${type.name}/get")
        if (state == null) state = got["state"]?.jsonPrimitive?.contentOrNull
        all += objectsOf(got)
        val listed = (query["ids"] as? JsonArray)?.size ?: 0
        position += listed
        val total = query["total"]?.jsonPrimitive?.intOrNull
        if (listed == 0 || (total != null && position >= total)) break
    }
    return ObjectSnapshot(all, state)
}

/** The delta since [since]; `cannotCalculateChanges` falls back on the whole collection. */
suspend fun JmapClient.objectChanges(
    session: JmapSession,
    auth: JmapAuth,
    type: JmapType,
    since: String,
    paged: Boolean = true,
): ObjectChanges? {
    val accountId = session.accountIdFor(type.capability) ?: return null
    val changed = LinkedHashSet<String>()
    val destroyed = LinkedHashSet<String>()
    var cursor = since
    do {
        val args = try {
            methodResponseArgs(
                postJmap(session, auth, request(type, Triple("${type.name}/changes", "c", buildJsonObject {
                    put("accountId", accountId); put("sinceState", cursor)
                }))),
                "${type.name}/changes",
            )
        } catch (e: JmapException) {
            if (e.errorType != CANNOT_CALCULATE_CHANGES) throw e
            val all = getAllObjects(session, auth, type, paged) ?: return null
            return ObjectChanges(all.objects, emptyList(), all.state, replacesAll = true)
        }
        fun ids(key: String) = (args[key] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        changed += ids("created") + ids("updated")
        ids("destroyed").forEach { changed -= it; destroyed += it }
        val newState = args["newState"]?.jsonPrimitive?.contentOrNull
        val more = args["hasMoreChanges"]?.jsonPrimitive?.booleanOrNull ?: false
        val advanced = newState != null && newState != cursor
        if (newState != null) cursor = newState
    } while (more && advanced)
    val found = mutableListOf<JmapObject>()
    changed.toList().chunked(session.getBatchSize()).forEach { batch ->
        found += objectsOf(methodResponseArgs(
            postJmap(session, auth, request(type, Triple("${type.name}/get", "g", buildJsonObject {
                put("accountId", accountId)
                put("ids", JsonArray(batch.map(::JsonPrimitive)))
                put("properties", propsOf(type))
            }))),
            "${type.name}/get",
        ))
    }
    val ok = found.mapTo(HashSet()) { it.id }
    changed.filterNotTo(destroyed) { it in ok }
    return ObjectChanges(found, destroyed.toList(), cursor, replacesAll = false)
}

/** One `/set`. [ifInState] makes a stale write throw [JmapException] with [JMAP_STATE_MISMATCH].
 *  [create] is keyed by client id, [update] by object id, each value a JSON-pointer patch. */
suspend fun JmapClient.setObjects(
    session: JmapSession,
    auth: JmapAuth,
    type: JmapType,
    ifInState: String? = null,
    create: Map<String, JsonObject> = emptyMap(),
    update: Map<String, JsonObject> = emptyMap(),
    destroy: List<String> = emptyList(),
    extra: JsonObject = JsonObject(emptyMap()),
): ObjectSetResult {
    val accountId = session.accountIdFor(type.capability) ?: throw JmapException("Server has no ${type.name} account")
    val args = methodResponseArgs(
        postJmap(session, auth, request(type, Triple("${type.name}/set", "s", buildJsonObject {
            extra.forEach { (k, v) -> put(k, v) }
            put("accountId", accountId)
            if (ifInState != null) put("ifInState", ifInState)
            if (create.isNotEmpty()) put("create", JsonObject(create))
            if (update.isNotEmpty()) put("update", JsonObject(update))
            if (destroy.isNotEmpty()) put("destroy", JsonArray(destroy.map(::JsonPrimitive)))
        }))),
        "${type.name}/set",
    )
    val made = (args["created"] as? JsonObject).orEmpty().mapValues { (_, v) ->
        JmapObject(v.jsonObject["id"]!!.jsonPrimitive.content, v.jsonObject)
    }
    return ObjectSetResult(
        newState = args["newState"]?.jsonPrimitive?.contentOrNull,
        created = made,
        updated = (args["updated"] as? JsonObject).orEmpty().keys,
        destroyed = (args["destroyed"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet(),
        failed = errors(args, "notCreated") + errors(args, "notUpdated") + errors(args, "notDestroyed"),
    )
}

/** `CalendarEvent/parse` of an uploaded or message-attached `.ics` blob: the events in it, each with the iTIP `method` it came with. */
suspend fun JmapClient.parseCalendarBlob(session: JmapSession, auth: JmapAuth, blobId: String): List<JmapObject> {
    val type = JmapType.CALENDAR_EVENT
    val accountId = session.accountIdFor(type.capability) ?: return emptyList()
    val args = methodResponseArgs(
        postJmap(session, auth, request(type, Triple("CalendarEvent/parse", "p", buildJsonObject {
            put("accountId", accountId); put("blobIds", JsonArray(listOf(JsonPrimitive(blobId))))
        }))),
        "CalendarEvent/parse",
    )
    val events = ((args["parsed"] as? JsonObject)?.get(blobId) as? JsonArray).orEmpty()
    return events.map { val o = it.jsonObject; JmapObject(o["id"]?.jsonPrimitive?.contentOrNull ?: "", o) }
}

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

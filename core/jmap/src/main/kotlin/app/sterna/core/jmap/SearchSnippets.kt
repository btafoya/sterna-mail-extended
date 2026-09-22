package app.sterna.core.jmap

import app.sterna.core.jmap.model.SearchQuery
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Only words have a passage to show: a sender, a date or an attachment alone matches no body text. */
internal fun asksForSnippets(query: SearchQuery): Boolean = query.text.isNotBlank() || query.subject.isNotBlank()

/**
 * `SearchSnippet/get` (RFC 8621 §5.1) over the ids of the `q0` query, as the last call of the batch:
 * `methodResponseArgs` throws on the first `error` response it meets, so a server refusing this
 * method must answer after the two calls the search reads strictly. [filter] is the very object
 * q0 sent, or the passages would be for another query.
 */
internal fun JsonArrayBuilder.addSearchSnippetCall(accountId: String, filter: JsonObject) {
    addJsonArray {
        add("SearchSnippet/get")
        addJsonObject {
            put("accountId", accountId)
            put("filter", filter)
            putJsonObject("#emailIds") {
                put("resultOf", "q0")
                put("name", "Email/query")
                put("path", "/ids")
            }
        }
        add("s0")
    }
}

/**
 * Each snippet's raw `preview` (HTML-escaped text with `<mark>` tags) by email id; a null preview is
 * no entry. Casts, never `jsonPrimitive`/`jsonArray`, which throw: a malformed answer costs the
 * passages, not the search.
 */
internal fun snippetPreviews(args: JsonObject?): Map<String, String> =
    (args?.get("list") as? JsonArray).orEmpty().mapNotNull { entry ->
        val row = entry as? JsonObject ?: return@mapNotNull null
        val id = (row["emailId"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val preview = (row["preview"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to preview
    }.toMap()

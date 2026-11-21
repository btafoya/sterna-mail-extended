package app.sterna.ui.dav

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the editor holds: the four fields in scope, one line each. Blank lines are dropped on save. */
data class ContactForm(
    val name: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val addresses: List<String> = emptyList(),
    val bookId: String = "",
)

private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

/** An address as one line: `full`, else its components' values joined. */
private fun addressLine(a: JsonObject): String = a.text("full")?.trim()?.takeIf { it.isNotEmpty() }
    ?: (a["components"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("value")?.trim()?.takeIf(String::isNotEmpty) }.joinToString(", ")

private fun JsonArray?.orEmpty() = this ?: JsonArray(emptyList())

private fun lines(card: JsonObject, key: String, field: String): List<String> =
    (card[key] as? JsonObject)?.values.orEmpty().mapNotNull { (it as? JsonObject)?.text(field)?.trim()?.takeIf(String::isNotEmpty) }

private fun lineOf(entry: JsonObject, key: String) = if (key == "addresses") addressLine(entry) else entry.text(if (key == "emails") "address" else "number").orEmpty().trim()

/** The editor's view of a stored card. */
fun formOf(card: JsonObject, fallbackBook: String = ""): ContactForm = ContactForm(
    name = cardDisplayName(card),
    emails = lines(card, "emails", "address"),
    phones = lines(card, "phones", "number"),
    addresses = (card["addresses"] as? JsonObject)?.values.orEmpty().mapNotNull { (it as? JsonObject)?.let(::addressLine)?.takeIf(String::isNotEmpty) },
    bookId = (card["addressBookIds"] as? JsonObject)?.keys?.firstOrNull() ?: fallbackBook,
)

/** `name.full`, else given + surname components: what the editor's name box starts with. */
fun cardDisplayName(card: JsonObject): String = app.sterna.core.data.dav.cardName(card).let { n ->
    // cardName falls back on the first address, which is not a name to edit.
    val name = card["name"] as? JsonObject
    if (name == null || (name.text("full").isNullOrBlank() && (name["components"] as? JsonArray).orEmpty().isEmpty())) "" else n
}

private fun entries(stored: JsonObject?, rows: List<String>, key: String, field: String, prefix: String): JsonObject {
    val old = stored?.values?.toList().orEmpty().map { it as? JsonObject ?: JsonObject(emptyMap()) }
    val oldIds = stored?.keys?.toList().orEmpty()
    val taken = oldIds.toMutableSet()
    val out = linkedMapOf<String, JsonElement>()
    rows.map(String::trim).filter(String::isNotEmpty).forEachIndexed { i, text ->
        // Row i keeps the id and the unknown properties (contexts, labels) of the entry it came from, when that entry had a line at all.
        val from = old.indices.filter { lineOf(old[it], key).isNotEmpty() }.getOrNull(i)
        val base = from?.let { old[it] }
        val id = from?.let { oldIds[it] } ?: generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { it !in taken }.also { taken += it }
        out[id] = when {
            base != null && lineOf(base, key) == text -> base
            base != null -> JsonObject(base.filterKeys { it != "components" } + (field to JsonPrimitive(text)))
            else -> JsonObject(mapOf(field to JsonPrimitive(text)))
        }
    }
    return JsonObject(out)
}

private fun fieldOf(key: String) = when (key) { "emails" -> "address"; "phones" -> "number"; else -> "full" }
private const val NAME = "name"

/** The JSContact card to create. */
fun newCard(form: ContactForm): JsonObject {
    val map = linkedMapOf<String, JsonElement>(
        "@type" to JsonPrimitive("Card"),
        "version" to JsonPrimitive("1.0"),
        "addressBookIds" to JsonObject(mapOf(form.bookId to JsonPrimitive(true))),
    )
    if (form.name.isNotBlank()) map[NAME] = JsonObject(mapOf("full" to JsonPrimitive(form.name.trim())))
    listOf("emails" to (form.emails to "e"), "phones" to (form.phones to "p"), "addresses" to (form.addresses to "a")).forEach { (key, v) ->
        val e = entries(null, v.first, key, fieldOf(key), v.second)
        if (e.isNotEmpty()) map[key] = e
    }
    return JsonObject(map)
}

/**
 * The top-level properties of [stored] that [form] changes, for `update`: a changed key carries its
 * whole new value, a removed one is JSON null, an untouched one is absent, and properties the editor
 * does not know are never mentioned. Empty when nothing changed.
 */
fun patchOf(stored: JsonObject, form: ContactForm): JsonObject {
    val patch = linkedMapOf<String, JsonElement>()
    if (form.name.trim() != cardDisplayName(stored)) {
        // The name's components would now describe a different name: only `full` is written.
        patch[NAME] = if (form.name.isBlank()) JsonNull else JsonObject(mapOf("full" to JsonPrimitive(form.name.trim())))
    }
    listOf("emails" to (form.emails to "e"), "phones" to (form.phones to "p"), "addresses" to (form.addresses to "a")).forEach { (key, v) ->
        val old = stored[key] as? JsonObject
        val next = entries(old, v.first, key, fieldOf(key), v.second)
        if (next != (old ?: JsonObject(emptyMap()))) patch[key] = if (next.isEmpty()) JsonNull else next
    }
    val oldBook = (stored["addressBookIds"] as? JsonObject)?.keys?.firstOrNull()
    if (form.bookId.isNotEmpty() && form.bookId != oldBook) patch["addressBookIds"] = JsonObject(mapOf(form.bookId to JsonPrimitive(true)))
    return JsonObject(patch)
}

/** A card matches [query] on its name or any email, phone or address line, ignoring case. */
fun matches(card: JsonObject, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val f = formOf(card)
    return (listOf(f.name) + f.emails + f.phones + f.addresses).any { it.lowercase().contains(q) }
}

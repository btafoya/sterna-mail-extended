package app.sterna.core.data.filter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The metadata comment Bulwark opens its scripts with, `/* @metadata:begin {json} @metadata:end */`.
 * Its JSON is the source of Bulwark's rules: each Bulwark save regenerates every `# Rule:` block from it.
 */
internal object BulwarkFormat {
    private const val END = "@metadata:end */"
    private const val SPAM_GUARD = "not spamtest :percent :value \"ge\" :comparator \"i;ascii-numeric\" \"50\""
    val spamGuardCapabilities = setOf("spamtestplus", "relational", "comparator-i;ascii-numeric")
    private val headerNames = mapOf("from" to "From", "to" to "To", "cc" to "Cc", "subject" to "Subject")

    /** The JSON of a metadata comment, or null when Bulwark itself would refuse it (not version 1, a malformed rule). */
    fun read(comment: String): JsonObject? {
        val end = comment.indexOf(END)
        if (!comment.startsWith(SieveScanner.BULWARK_BEGIN) || end < 0) return null
        val payload = comment.substring(SieveScanner.BULWARK_BEGIN.length, end).trim()
        val meta = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val version = meta["version"] as? JsonPrimitive
        if (version == null || version.isString || version.intOrNull != 1) return null
        val rules = meta["rules"] as? JsonArray ?: return null
        return meta.takeIf { rules.all(::isValidRule) }
    }

    fun rules(meta: JsonObject): List<JsonObject> = (meta["rules"] as JsonArray).map { it as JsonObject }

    /** [comment] with its JSON replaced by [meta]. A star-slash in a value is written `*\/`, or it would close the comment. */
    fun written(comment: String, meta: JsonObject, lineEnding: String): String {
        val trailer = comment.substring(comment.lastIndexOf(END) + END.length)
        val json = meta.toString().replace("*/", "*\\/")
        return SieveScanner.BULWARK_BEGIN + lineEnding + json + lineEnding + END + trailer
    }

    /**
     * Whether the entries Bulwark wrote a block for match the `# Rule:` blocks in the script, in order
     * AND by name. An index alone would let an edit land on another rule's entry.
     */
    fun blocksMatch(entryNames: List<String>, blockNames: List<String>): Boolean = entryNames == blockNames

    /** Bulwark writes a block for an enabled rule with at least one condition and one action, and only then. */
    fun hasBlock(entry: JsonObject): Boolean =
        entry.bool("enabled") && (entry["conditions"] as JsonArray).isNotEmpty() && (entry["actions"] as JsonArray).isNotEmpty()

    fun name(entry: JsonObject): String = entry.string("name").orEmpty()

    /**
     * The guard Bulwark puts on a move so it does not pull spam out of Junk, unless the entry has
     * `includeSpam`. Bulwark asks the server; the script's own `require` stands in for it here.
     */
    fun spamGuard(entry: JsonObject, required: Set<String>): String? {
        val moves = (entry["actions"] as JsonArray).any { (it as JsonObject).string("type") == "move" }
        val supported = "spamtestplus" in required && "relational" in required
        return SPAM_GUARD.takeIf { moves && supported && !entry.bool("includeSpam") }
    }

    /** [entry] as a form rule, or null when the form cannot show it all (it is then locked text). */
    fun simpleRule(entry: JsonObject): FilterRule? {
        val conditions = entry["conditions"] as JsonArray
        if (conditions.size != 1 || entry.bool("stopProcessing")) return null
        val condition = conditions[0] as JsonObject
        val field = when (condition.string("field")) {
            "from" -> RuleField.FROM
            "to" -> RuleField.TO
            "cc" -> RuleField.CC
            "subject" -> RuleField.SUBJECT
            else -> return null
        }
        val match = when (condition.string("comparator")) {
            "contains" -> RuleMatch.CONTAINS
            "is" -> RuleMatch.IS
            else -> return null
        }
        val value = condition.string("value") ?: return null
        var moveTo: String? = null
        var markRead = false
        var flag = false
        for (action in (entry["actions"] as JsonArray).map { it as JsonObject }) {
            when (action.string("type")) {
                "move" -> {
                    if (moveTo != null) return null
                    moveTo = action.string("value") ?: return null
                }
                "mark_read" -> {
                    if (markRead) return null
                    markRead = true
                }
                "star" -> {
                    if (flag) return null
                    flag = true
                }
                else -> return null
            }
        }
        return FilterRule(name(entry), entry.bool("enabled"), field, match, value, moveTo, markRead, flag)
    }

    /**
     * [entry] rewritten to hold [rule]. Everything else in it stays: `id`, `includeSpam`, unknown keys.
     * A move keeps its `mailboxId` only while the folder is the same, or Bulwark would follow the old id.
     */
    fun updated(entry: JsonObject, rule: FilterRule): JsonObject {
        val oldCondition = (entry["conditions"] as JsonArray).firstOrNull() as? JsonObject ?: JsonObject(emptyMap())
        val condition = JsonObject(
            oldCondition + mapOf(
                "field" to JsonPrimitive(rule.field.name.lowercase()),
                "comparator" to JsonPrimitive(if (rule.match == RuleMatch.IS) "is" else "contains"),
                "value" to JsonPrimitive(rule.value),
            ),
        )
        val old = (entry["actions"] as JsonArray).map { it as JsonObject }
        val actions = buildList {
            for (action in old) {
                when (action.string("type")) {
                    "move" -> rule.moveTo?.let { path ->
                        add(if (action.string("value") == path) action else JsonObject(action - "mailboxId" + ("value" to JsonPrimitive(path))))
                    }
                    "mark_read" -> if (rule.markRead) add(action)
                    "star" -> if (rule.flag) add(action)
                }
            }
            val kept = map { it.string("type") }
            if (rule.moveTo != null && "move" !in kept) add(action("move", rule.moveTo))
            if (rule.markRead && "mark_read" !in kept) add(action("mark_read"))
            if (rule.flag && "star" !in kept) add(action("star"))
        }
        return JsonObject(
            entry + mapOf(
                "name" to JsonPrimitive(rule.name),
                "enabled" to JsonPrimitive(rule.enabled),
                "conditions" to JsonArray(listOf(condition)),
                "actions" to JsonArray(actions),
            ),
        )
    }

    fun created(id: String, rule: FilterRule): JsonObject = updated(
        JsonObject(
            linkedMapOf(
                "id" to JsonPrimitive(id),
                "name" to JsonPrimitive(""),
                "enabled" to JsonPrimitive(true),
                "matchType" to JsonPrimitive("all"),
                "conditions" to JsonArray(emptyList()),
                "actions" to JsonArray(emptyList()),
                "stopProcessing" to JsonPrimitive(false),
            ),
        ),
        rule,
    )

    /** The `if` block Bulwark would write for a simple [entry]: `header` with a capitalised name, flags before `fileinto`. */
    fun statement(entry: JsonObject, guard: String?, indent: String, lineEnding: String): String {
        val condition = (entry["conditions"] as JsonArray)[0] as JsonObject
        val header = headerNames.getValue(condition.string("field").orEmpty())
        val test = "header :${condition.string("comparator")} \"$header\" \"${SieveCodec.escape(condition.string("value").orEmpty())}\""
        val actions = (entry["actions"] as JsonArray).map { it as JsonObject }
        val flags = actions.mapNotNull {
            when (it.string("type")) {
                "mark_read" -> "addflag \"\\\\Seen\";"
                "star" -> "addflag \"\\\\Flagged\";"
                else -> null
            }
        }
        val moves = actions.filter { it.string("type") == "move" }
            .map { "fileinto \"${SieveCodec.escape(it.string("value").orEmpty())}\";" }
        val cond = guard?.let { "allof($test, $it)" } ?: test
        return "if $cond {$lineEnding" + (flags + moves).joinToString("") { "$indent    $it$lineEnding" } + "$indent}"
    }

    private fun action(type: String, value: String? = null): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive(type))
            if (value != null) put("value", JsonPrimitive(value))
        },
    )

    private fun isValidRule(element: JsonElement): Boolean {
        val rule = element as? JsonObject ?: return false
        val conditions = rule["conditions"] as? JsonArray ?: return false
        val actions = rule["actions"] as? JsonArray ?: return false
        return rule.string("id") != null && rule.string("name") != null &&
            (rule["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null &&
            rule.string("matchType") in setOf("all", "any") &&
            (rule["stopProcessing"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null &&
            conditions.all(::isValidCondition) &&
            actions.all { (it as? JsonObject)?.string("type") != null }
    }

    private fun isValidCondition(element: JsonElement): Boolean {
        val condition = element as? JsonObject ?: return false
        if (condition.string("field") == null || condition.string("comparator") == null) return false
        val value = condition["value"]
        return (value as? JsonPrimitive)?.isString == true ||
            (value as? JsonArray)?.all { (it as? JsonPrimitive)?.isString == true } == true
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}

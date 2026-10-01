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
    private val BULWARK_FIELDS = mapOf(
        ConditionField.FROM to "from",
        ConditionField.TO to "to",
        ConditionField.CC to "cc",
        ConditionField.SUBJECT to "subject",
        ConditionField.BODY to "body",
        ConditionField.SIZE to "size",
    )
    private val BULWARK_COMPARATORS = mapOf(RuleMatch.CONTAINS to "contains", RuleMatch.IS to "is", RuleMatch.OVER to "greater_than")

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

    /**
     * [entry] as a form rule, or null when the form cannot show it all (it is then locked text). Only
     * the words of Bulwark's JSON known from its generator are read, and so written: Bulwark rewrites
     * every block from this JSON, and a word it reads otherwise would change what the rule does.
     */
    fun simpleRule(entry: JsonObject): FilterRule? {
        val mode = when (entry.string("matchType")) {
            "all" -> RuleMode.ALL
            "any" -> RuleMode.ANY
            else -> return null
        }
        val conditions = (entry["conditions"] as JsonArray).map { element ->
            val condition = element as JsonObject
            val value = condition.string("value") ?: return null
            val field = BULWARK_FIELDS.entries.firstOrNull { it.value == condition.string("field") }?.key ?: return null
            val match = BULWARK_COMPARATORS.entries.firstOrNull { it.value == condition.string("comparator") }?.key ?: return null
            if (match !in bulwarkMatches(field)) return null
            if (field == ConditionField.SIZE) Condition(field, match, (sizeBytes(value) ?: return null).toString()) else Condition(field, match, value)
        }
        if (conditions.isEmpty()) return null
        val actions = (entry["actions"] as JsonArray).map { element ->
            val action = element as JsonObject
            when (action.string("type")) {
                "move" -> Action.MoveTo(action.string("value") ?: return null)
                "mark_read" -> Action.MarkRead
                "star" -> Action.Star
                else -> return null
            }
        } + listOfNotNull(Action.Stop.takeIf { entry.bool("stopProcessing") })
        return FilterRule(name(entry), entry.bool("enabled"), mode, conditions, actions).takeIf { holds(it) }
    }

    /** Whether Bulwark's JSON can hold [rule] whole: its fields, comparators and actions, one of each action at most. */
    fun holds(rule: FilterRule): Boolean {
        val actions = rule.actions.filter { it != Action.Stop }
        return rule.conditions.isNotEmpty() &&
            rule.conditions.all { it.field in BULWARK_FIELDS && it.match in bulwarkMatches(it.field) } &&
            (rule.actions.indexOf(Action.Stop).let { it < 0 || it == rule.actions.lastIndex }) &&
            actions.all { it is Action.MoveTo || it == Action.MarkRead || it == Action.Star } &&
            actions.groupingBy { it::class }.eachCount().values.all { it == 1 }
    }

    /** What Bulwark can hold of [field]: the comparators known from its generator. */
    fun bulwarkMatches(field: ConditionField): List<RuleMatch> = when (field) {
        ConditionField.SIZE -> listOf(RuleMatch.OVER)
        ConditionField.BODY -> listOf(RuleMatch.CONTAINS)
        in BULWARK_FIELDS -> listOf(RuleMatch.CONTAINS, RuleMatch.IS)
        else -> emptyList()
    }

    /**
     * [entry] rewritten to hold [rule]. Everything else in it stays: `id`, `includeSpam`, unknown keys,
     * and in each condition and action what the form does not edit. A move keeps its `mailboxId` only
     * while the folder is the same, or Bulwark would follow the old id.
     */
    fun updated(entry: JsonObject, rule: FilterRule): JsonObject {
        val oldConditions = (entry["conditions"] as JsonArray).map { it as JsonObject }
        val pairs = pairedConditions(simpleRule(entry)?.conditions.orEmpty(), rule.conditions)
        val conditions = rule.conditions.mapIndexed { i, c ->
            val value = if (c.field == ConditionField.SIZE) sizeNumber(c.value.toLong()) else c.value
            JsonObject(
                (pairs[i]?.let { oldConditions.getOrNull(it) } ?: JsonObject(emptyMap())) + mapOf(
                    "field" to JsonPrimitive(BULWARK_FIELDS.getValue(c.field)),
                    "comparator" to JsonPrimitive(BULWARK_COMPARATORS.getValue(c.match)),
                    "value" to JsonPrimitive(value),
                ),
            )
        }
        val old = (entry["actions"] as JsonArray).map { it as JsonObject }.toMutableList()
        fun take(type: String): JsonObject? = old.firstOrNull { it.string("type") == type }?.also { old.remove(it) }
        val actions = rule.actions.mapNotNull { a ->
            when (a) {
                is Action.MoveTo -> take("move")
                    ?.let { if (it.string("value") == a.folder) it else JsonObject(it - "mailboxId" + ("value" to JsonPrimitive(a.folder))) }
                    ?: action("move", a.folder)
                Action.MarkRead -> take("mark_read") ?: action("mark_read")
                Action.Star -> take("star") ?: action("star")
                else -> null
            }
        }
        return JsonObject(
            entry + mapOf(
                "name" to JsonPrimitive(rule.name),
                "enabled" to JsonPrimitive(rule.enabled),
                "matchType" to JsonPrimitive(if (rule.mode == RuleMode.ANY) "any" else "all"),
                "conditions" to JsonArray(conditions),
                "actions" to JsonArray(actions),
                "stopProcessing" to JsonPrimitive(Action.Stop in rule.actions),
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

    /**
     * The `if` block Bulwark would write for [entry]: `header` with a capitalised name, `body` and `size`
     * as they are, flags before `fileinto`, `stop` last. The [guard] stays and with every condition.
     */
    fun statement(entry: JsonObject, guard: String?, indent: String, lineEnding: String): String {
        val tests = (entry["conditions"] as JsonArray).map { element ->
            val condition = element as JsonObject
            val field = condition.string("field").orEmpty()
            val value = condition.string("value").orEmpty()
            when (field) {
                "size" -> "size :over $value"
                "body" -> "body :${condition.string("comparator")} \"${SieveCodec.escape(value)}\""
                else -> "header :${condition.string("comparator")} \"${headerNames.getValue(field)}\" \"${SieveCodec.escape(value)}\""
            }
        }
        val any = entry.string("matchType") == "any" && tests.size > 1
        val core = if (any) listOf("anyof(${tests.joinToString(", ")})") else tests
        val cond = when {
            guard != null -> "allof(${(core + guard).joinToString(", ")})"
            core.size == 1 -> core.single()
            else -> "allof(${core.joinToString(", ")})"
        }
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
        val stop = listOfNotNull("stop;".takeIf { entry.bool("stopProcessing") })
        return "if $cond {$lineEnding" + (flags + moves + stop).joinToString("") { "$indent    $it$lineEnding" } + "$indent}"
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

/** The matches a rule written in Bulwark's JSON may use on [field]; none for a field it cannot hold. */
fun bulwarkMatches(field: ConditionField): List<RuleMatch> = BulwarkFormat.bulwarkMatches(field)

package app.sterna.core.data.filter

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

@Serializable
private data class V1Rule(
    val name: String = "",
    val enabled: Boolean = true,
    val field: RuleField = RuleField.FROM,
    val match: RuleMatch = RuleMatch.CONTAINS,
    val value: String = "",
    val moveTo: String? = null,
    val markRead: Boolean = false,
    val flag: Boolean = false,
)

private object V1RuleSerializer : KSerializer<FilterRule> {
    override val descriptor: SerialDescriptor = V1Rule.serializer().descriptor

    override fun serialize(encoder: Encoder, value: FilterRule) = encoder.encodeSerializableValue(
        V1Rule.serializer(),
        V1Rule(value.name, value.enabled, value.field, value.match, value.value, value.moveTo, value.markRead, value.flag),
    )

    override fun deserialize(decoder: Decoder): FilterRule = decoder.decodeSerializableValue(V1Rule.serializer()).let {
        FilterRule(it.name, it.enabled, it.field, it.match, it.value, it.moveTo, it.markRead, it.flag)
    }
}

/**
 * Compiles structured [FilterRule]s to a Sieve script and reads them back.
 */
object SieveCodec {
    const val SCRIPT_NAME = "sterna"

    /**
     * **The Sieve emitted below is a compatibility surface. Changing it is expensive, and bumping
     */
    internal const val MARKER = "# STERNA-RULES-V1:"
    internal const val VACATION_MARKER = "# STERNA-VACATION-V1:"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(V1RuleSerializer)
    private val vacationSerializer = SieveVacation.serializer()

    /** The Sieve script for [rules] and, only when one is handed over, [vacation], each under its marker. */
    fun generate(rules: List<FilterRule>, vacation: SieveVacation? = null): String {
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')
        if (vacation != null) {
            sb.append(VACATION_MARKER).append(' ').append(json.encodeToString(vacationSerializer, vacation)).append('\n')
        }

        val active = rules.filter { it.enabled && it.value.isNotBlank() }
        val reply = vacation?.takeIf { it.isEnabled }
        val requires = buildList {
            if (active.any { it.moveTo != null }) add("fileinto")
            if (active.any { it.markRead || it.flag }) add("imap4flags")
            if (reply != null) add("vacation")
            if (reply != null && (reply.fromDate != null || reply.toDate != null)) addAll(listOf("date", "relational"))
        }
        if (requires.isNotEmpty()) {
            sb.append("require [").append(requires.joinToString(", ") { "\"$it\"" }).append("];\n")
        }
        sb.append('\n')
        for (rule in active) sb.append(ruleToSieve(rule)).append('\n')
        if (reply != null) sb.append(vacationToSieve(reply, active)).append('\n')
        return sb.toString()
    }

    /**
     * The rules a script carries, or **null when this script cannot be read as ours**: content, but
     * content, but no `# sterna-rules-V1:` line, or a line whose JSON does not decode.
     */
    fun parseRulesOrNull(script: String): List<FilterRule>? {
        if (script.isBlank()) return emptyList()
        val line = script.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(MARKER) } ?: return null
        val payload = line.removePrefix(MARKER).trim()
        return runCatching { json.decodeFromString(serializer, payload) }.getOrNull()
    }

    /**
     * Parse the rule list out of a script's JSON metadata comment (empty if none). Only for callers
     */
    fun parseRules(script: String): List<FilterRule> = parseRulesOrNull(script) ?: emptyList()

    fun readVacation(script: String): VacationMarker {
        val line = script.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(VACATION_MARKER) } ?: return VacationMarker.Absent
        val payload = line.removePrefix(VACATION_MARKER).trim()
        val vacation = runCatching { json.decodeFromString(vacationSerializer, payload) }.getOrNull()
            ?: return VacationMarker.Unreadable
        val dates = listOfNotNull(vacation.fromDate, vacation.toDate)
        return if (dates.all { utcDate.matches(it) }) VacationMarker.Found(vacation) else VacationMarker.Unreadable
    }

    /**
     * [script] reduced to the Sieve it runs: the first line of each marker removed whole, blanks
     * normalised. The value two scripts are compared on to tell "we wrote all of this" from part.
     */
    fun sieveBody(script: String): String {
        val lines = script.replace("\r\n", "\n").replace('\r', '\n').lines()
        val marker = lines.indexOfFirst { it.trim().startsWith(MARKER) }
        val vacationMarker = lines.indexOfFirst { it.trim().startsWith(VACATION_MARKER) }
        return lines.filterIndexed { i, _ -> i != marker && i != vacationMarker }
            .joinToString("\n") { it.trimEnd(' ', '\t') }
            .trim()
    }

    private val utcDate = Regex("""(\d{4}-\d{2}-\d{2})T\d{2}:\d{2}:\d{2}(\.\d+)?Z""")

    internal fun vacationMarkerLine(vacation: SieveVacation): String =
        VACATION_MARKER + " " + json.encodeToString(vacationSerializer, vacation)

    internal fun vacationToSieve(
        vacation: SieveVacation,
        active: List<FilterRule>,
        newline: String = "\n",
        written: List<String?> = active.map { formTest(it, emptyList()) },
    ): String {
        val command = StringBuilder("vacation")
        val subject = vacation.subject
        if (!subject.isNullOrBlank()) command.append(" :subject \"").append(escape(subject)).append('"')
        // A Sieve list cannot be empty: no addresses is no `:addresses` tag, no rule into the Trash no `not anyof`.
        if (vacation.addresses.isNotEmpty()) {
            command.append(" :addresses [").append(vacation.addresses.joinToString(", ") { "\"${escape(it)}\"" }).append(']')
        }
        command.append(" \"").append(escape(vacation.textBody.orEmpty())).append("\";")
        val trashed = active.indices.filter { vacation.trashFolder != null && active[it].moveTo == vacation.trashFolder }
            .mapNotNull { written[it] }
        val conditions = listOfNotNull(
            trashed.takeIf { it.isNotEmpty() }?.let { tests -> "not anyof (${tests.joinToString(", ")})" },
            vacation.fromDate?.let { "currentdate :zone \"+0000\" :value \"ge\" \"date\" \"${utcDay(it)}\"" },
            vacation.toDate?.let { "currentdate :zone \"+0000\" :value \"le\" \"date\" \"${utcDay(it)}\"" },
        )
        return when (conditions.size) {
            0 -> command.toString()
            1 -> "if ${conditions.single()} {$newline    $command$newline}"
            else -> "if allof (${conditions.joinToString(", ")}) {$newline    $command$newline}"
        }
    }

    // Throws rather than drop a bound, which would run the auto-reply outside the chosen days.
    private fun utcDay(date: String): String =
        requireNotNull(utcDate.matchEntire(date)) { "not a UTCDate: $date" }.groupValues[1]

    private fun ruleToSieve(rule: FilterRule): String {
        val body = StringBuilder()
        // Flags must be set before fileinto/keep so the filed copy carries them.
        if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")
        if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")
        if (rule.moveTo != null) body.append("    fileinto \"${escape(rule.moveTo)}\";\n")
        return "if ${ruleTest(rule)} {\n$body}"
    }

    private fun ruleTest(rule: FilterRule): String {
        val matchTag = if (rule.match == RuleMatch.IS) ":is" else ":contains"
        val value = escape(rule.value)
        return when (rule.field) {
            RuleField.SUBJECT -> "header $matchTag \"subject\" \"$value\""
            RuleField.FROM -> "address $matchTag \"from\" \"$value\""
            RuleField.TO -> "address $matchTag \"to\" \"$value\""
            RuleField.CC -> "address $matchTag \"cc\" \"$value\""
        }
    }

    /** Escape a value for a Sieve quoted-string (backslash and double-quote). */
    internal fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

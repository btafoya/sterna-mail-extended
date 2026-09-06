package app.sterna.core.data.filter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Compiles structured [FilterRule]s to a Sieve script and reads them back.
 *
 * The round-trip trick: the full rule list is serialized to JSON on a single `# STERNA-RULES-V1:`
 * comment line at the top of the script, so reading never has to parse arbitrary Sieve. Disabled
 * rules stay in the JSON but are not emitted as Sieve.
 */
object SieveCodec {
    const val SCRIPT_NAME = "sterna"

    /**
     * **The Sieve emitted below is a compatibility surface. Changing it is expensive, and bumping
     * this marker is NOT the way out.** Reading a script compares its Sieve to the Sieve those rules
     * would produce today, so one character emitted differently tells the whole installed base its
     * script holds foreign Sieve.
     *
     * Do not "fix" that with `# STERNA-RULES-V2:`: [parseRulesOrNull] knows this one string, so a
     * bump makes every V1 script UNREADABLE rather than suspect — the worse of the two, since the
     * screen then shows ZERO rules and confirming destroys filters the user still has.
     */
    private const val MARKER = "# STERNA-RULES-V1:"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FilterRule.serializer())

    /**
     * Build the Sieve script (with embedded JSON metadata) for [rules].
     *
     * What this emits is a compatibility surface, not an implementation detail: it is compared
     * against scripts already sitting on servers. Read [MARKER] before changing a character of it,
     * [escape] included.
     */
    fun generate(rules: List<FilterRule>): String {
        val sb = StringBuilder()
        sb.append(MARKER).append(' ').append(json.encodeToString(serializer, rules)).append('\n')

        val active = rules.filter { it.enabled && it.value.isNotBlank() }
        val requires = buildList {
            if (active.any { it.moveTo != null }) add("fileinto")
            if (active.any { it.markRead || it.flag }) add("imap4flags")
        }
        if (requires.isNotEmpty()) {
            sb.append("require [").append(requires.joinToString(", ") { "\"$it\"" }).append("];\n")
        }
        sb.append('\n')
        for (rule in active) sb.append(ruleToSieve(rule)).append('\n')
        return sb.toString()
    }

    /**
     * The rules a script carries, or **null when this script cannot be read as ours**: content, but
     * no `# STERNA-RULES-V1:` line, or a line whose JSON does not decode.
     *
     * The distinction is the point. "No rules" and "I do not understand this script" collapse to
     * the same empty list, and whoever saves recompiles the WHOLE script from the list it was
     * handed — so treating the second as the first turns "add one rule" into "replace everything".
     *
     * A blank script is NOT unreadable: it says nothing and loses nothing when rewritten.
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
     * that have nothing to lose by not knowing; anything leading to a WRITE must ask
     * [parseRulesOrNull] and stop on null.
     */
    fun parseRules(script: String): List<FilterRule> = parseRulesOrNull(script) ?: emptyList()

    /**
     * [script] reduced to the Sieve it runs: its marker line removed whole, blanks normalised — the
     * value two scripts are compared on to tell "we wrote all of this" from "we wrote part of it".
     *
     * The marker line goes out entirely, JSON included: `encodeDefaults = true` writes every
     * field, so adding one to [FilterRule] would make every script already on a server differ from
     * what we write today. A SECOND marker line further down is not ours and stays in.
     * Normalisation is minimal; indentation and interior blank lines stay compared.
     */
    fun sieveBody(script: String): String {
        val lines = script.replace("\r\n", "\n").replace('\r', '\n').lines()
        val marker = lines.indexOfFirst { it.trim().startsWith(MARKER) }
        return lines.filterIndexed { i, _ -> i != marker }
            .joinToString("\n") { it.trimEnd(' ', '\t') }
            .trim()
    }

    private fun ruleToSieve(rule: FilterRule): String {
        val matchTag = if (rule.match == RuleMatch.IS) ":is" else ":contains"
        val value = escape(rule.value)
        val test = when (rule.field) {
            RuleField.SUBJECT -> "header $matchTag \"subject\" \"$value\""
            RuleField.FROM -> "address $matchTag \"from\" \"$value\""
            RuleField.TO -> "address $matchTag \"to\" \"$value\""
            RuleField.CC -> "address $matchTag \"cc\" \"$value\""
        }
        val body = StringBuilder()
        // Flags must be set before fileinto/keep so the filed copy carries them.
        if (rule.markRead) body.append("    addflag \"\\\\Seen\";\n")
        if (rule.flag) body.append("    addflag \"\\\\Flagged\";\n")
        if (rule.moveTo != null) body.append("    fileinto \"${escape(rule.moveTo)}\";\n")
        return "if $test {\n$body}"
    }

    /** Escape a value for a Sieve quoted-string (backslash and double-quote). */
    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}

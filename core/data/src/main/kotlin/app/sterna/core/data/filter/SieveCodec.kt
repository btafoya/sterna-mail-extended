package app.sterna.core.data.filter

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Compiles structured [FilterRule]s to a Sieve script and reads them back.
 *
 * The round-trip trick: the full rule list is serialized to JSON and stored on a
 * single `# STERNA-RULES-V1:` comment line at the top of the script. Generation
 * is one-way (rules → Sieve); reading just parses that JSON comment, so we never
 * parse arbitrary Sieve. Disabled rules are kept in the JSON but not emitted as
 * Sieve, so they survive a save without filtering mail.
 */
object SieveCodec {
    const val SCRIPT_NAME = "sterna"

    /**
     * ⛔ **The Sieve emitted below is a compatibility surface. Changing it is expensive, and
     * bumping this marker is NOT the way out.**
     *
     * The marker is no longer only a place to hang JSON: reading a script now compares its Sieve
     * to the Sieve those rules would produce TODAY, and a difference means somebody hand-edited
     * the script. Emit one character differently and every script written by an earlier version
     * stops matching — the whole installed base is told its filter script holds foreign Sieve, and
     * is asked to confirm an overwrite it never provoked.
     *
     * ⛔ **Do not "fix" that by writing `# STERNA-RULES-V2:` here.** [parseRulesOrNull] knows this
     * one string and no other, so a bump makes every V1 script on every server UNREADABLE instead
     * of merely suspect — and unreadable is the WORSE of the two, not the better:
     *
     *  - suspect (the marker still matches): the rules are decoded, the filters screen LISTS them,
     *    and the warning only asks before replacing. Nothing is lost by confirming.
     *  - unreadable (the marker no longer matches): the rules are not decoded, the screen shows
     *    ZERO rules under the same red line, and confirming the overwrite destroys filters the
     *    user still has. That is the data loss this whole file exists to prevent.
     *
     * So a real change to the emitted Sieve costs a reader that accepts BOTH markers and a
     * generator kept per version, so the comparison can be made against the version that wrote the
     * script. Neither exists today. Until one does, treat the emitted Sieve as frozen: the cheap
     * change is the one that emits nothing different.
     *
     * `SieveEmissionTest` pins it, golden output first and source lines second, so the edit cannot
     * pass unnoticed.
     */
    private const val MARKER = "# STERNA-RULES-V1:"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FilterRule.serializer())

    /**
     * Build the Sieve script (with embedded JSON metadata) for [rules].
     *
     * ⛔ **What this emits is a compatibility surface, not an implementation detail**: it is
     * compared against the scripts already sitting on servers, all of which an earlier build of
     * this same function wrote. Read [MARKER] before changing a character of it — including
     * [escape], which is part of what is emitted.
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
     * The rules a script carries, or **null when this script cannot be read as ours**: it has
     * content, but no `# STERNA-RULES-V1:` line, or a line whose JSON does not decode.
     *
     * The distinction is the whole point, and it is not cosmetic. "This script holds no rules"
     * and "I do not understand this script" collapse to the same empty list, and whoever saves
     * recompiles the WHOLE script from the list it was handed — so treating the second as the
     * first turns "add one rule" into "replace everything that was in there with one rule".
     * The content nobody could read is exactly the content nobody can get back.
     *
     * A blank script is NOT unreadable: it is a script that says nothing, filters nothing and
     * loses nothing when it is rewritten. Zero rules, and no alarm.
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
     * Parse the rule list out of a script's JSON metadata comment (empty if none).
     *
     * Only for callers that have nothing to lose by not knowing — counting the enabled rules of
     * a script, say. Anything that leads to a WRITE must ask [parseRulesOrNull] instead and stop
     * on null.
     */
    fun parseRules(script: String): List<FilterRule> = parseRulesOrNull(script) ?: emptyList()

    /**
     * [script] reduced to the Sieve it runs: its marker line removed whole, and blanks normalised.
     * The value two scripts are compared on to tell "we wrote all of this" from "we wrote part of
     * it" (see `holdsForeignSieve`).
     *
     * ⭐ **The marker line goes out entirely, JSON included** — the FIRST line whose trimmed text
     * starts with [MARKER], i.e. exactly the line [parseRulesOrNull] read. The JSON must not be
     * compared: `encodeDefaults = true` writes every field, so the day a field is added to
     * [FilterRule] the metadata of every script already on a server would differ from what we
     * write today, and comparing it would call the entire installed base hand-edited. A SECOND
     * marker line further down is not ours and stays in — it is content in excess like any other.
     *
     * Normalisation is the least that can be done: CRLF and lone CR to LF, trailing spaces and
     * tabs off each line, and the block's own leading and trailing blanks off. Nothing more —
     * indentation and interior blank lines are part of the script and stay compared. Measured on
     * 2026-08-12: Stalwart hands the blob back byte for byte, so this is for a less careful host.
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

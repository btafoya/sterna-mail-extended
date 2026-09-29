package app.sterna.core.data.filter

/**
 * An `if` block the rule form can edit: its [rule], the test [word] it was written with (`address` or
 * `header`), and the verbatim guard `G` of `allof(T, G)`, put back in place when the block is rewritten.
 */
internal data class FormBlock(val rule: FilterRule, val word: String, val guard: String?)

private class TestShape(val disabled: Boolean, val atom: Atom, val guard: String?)

private class Atom(val word: String, val field: RuleField, val match: RuleMatch, val value: String)

/**
 * [statement] as a form rule, or null when it is anything else. A rule has no name here: the name
 * lives in the comment above the block.
 */
internal fun recognizeForm(statement: String): FormBlock? {
    val toks = sieveTokens(statement) ?: return null
    // A comment inside the block would be lost the first time the form rewrites it.
    if (toks.any { it.kind == TokenKind.COMMENT || it.kind == TokenKind.LITERAL }) return null
    if (toks.firstOrNull()?.isWord("if") != true || toks.last().isPunct('}').not()) return null
    val open = toks.indexOfFirst { it.isPunct('{') }
    if (open < 0) return null
    val shape = testShape(toks.subList(1, open), statement) ?: return null
    val body = toks.subList(open + 1, toks.size - 1)
    var moveTo: String? = null
    var seen = false
    var flagged = false
    var i = 0
    while (i < body.size) {
        val command = body[i]
        val argument = body.getOrNull(i + 1)
        if (command.kind != TokenKind.WORD) return null
        when (command.text.lowercase()) {
            // A `stop` ends the script for this message: the form cannot show it, and rewriting the
            // block without it would let every later rule act on mail it never saw.
            "stop" -> return null
            "fileinto" -> {
                if (argument?.kind != TokenKind.STRING || moveTo != null) return null
                moveTo = argument.text
            }
            "addflag" -> when (argument?.takeIf { it.kind == TokenKind.STRING }?.text) {
                "\\Seen" -> {
                    if (seen) return null
                    seen = true
                }
                "\\Flagged" -> {
                    if (flagged) return null
                    flagged = true
                }
                else -> return null
            }
            else -> return null
        }
        if (body.getOrNull(i + 2)?.isPunct(';') != true) return null
        i += 3
    }
    val atom = shape.atom
    return FormBlock(
        rule = FilterRule(
            enabled = !shape.disabled,
            field = atom.field,
            match = atom.match,
            value = atom.value,
            moveTo = moveTo,
            markRead = seen,
            flag = flagged,
        ),
        word = atom.word,
        guard = shape.guard,
    )
}

/** The verbatim `G` of an `if allof(T, G)` whose `T` is a form test, or null. */
internal fun guardOf(statement: String): String? {
    val toks = sieveTokens(statement) ?: return null
    val open = toks.indexOfFirst { it.isPunct('{') }
    if (open < 1 || !toks[0].isWord("if")) return null
    return testShape(toks.subList(1, open), statement)?.guard
}

private fun testShape(test: List<SieveToken>, source: String): TestShape? {
    atom(test)?.let { return TestShape(disabled = false, atom = it, guard = null) }
    if (test.size < 4 || !test[0].isWord("allof") || !test[1].isPunct('(') || !test.last().isPunct(')')) return null
    val args = topLevelArguments(test.subList(2, test.size - 1)) ?: return null
    val disabled = args[0].size == 1 && args[0][0].isWord("false")
    val rest = if (disabled) args.drop(1) else args
    val atom = rest.firstOrNull()?.let(::atom) ?: return null
    val guardArgs = rest.drop(1)
    if (!disabled && guardArgs.isEmpty()) return null
    val guard = guardArgs.takeIf { it.isNotEmpty() }
        ?.let { source.substring(it.first().first().start, it.last().last().end) }
    return TestShape(disabled, atom, guard)
}

private fun atom(t: List<SieveToken>): Atom? {
    if (t.size != 4 || t[0].kind != TokenKind.WORD || t[1].kind != TokenKind.TAG) return null
    if (t[2].kind != TokenKind.STRING || t[3].kind != TokenKind.STRING) return null
    val word = t[0].text.lowercase()
    val match = when (t[1].text.lowercase()) {
        ":contains" -> RuleMatch.CONTAINS
        ":is" -> RuleMatch.IS
        else -> return null
    }
    val field = when (t[2].text.lowercase()) {
        "from" -> RuleField.FROM
        "to" -> RuleField.TO
        "cc" -> RuleField.CC
        "subject" -> RuleField.SUBJECT
        else -> return null
    }
    return when {
        word == "header" -> Atom(word, field, match, t[3].text)
        word == "address" && field != RuleField.SUBJECT -> Atom(word, field, match, t[3].text)
        else -> null
    }
}

private fun topLevelArguments(t: List<SieveToken>): List<List<SieveToken>>? {
    val args = mutableListOf<List<SieveToken>>()
    var depth = 0
    var start = 0
    for ((i, tok) in t.withIndex()) {
        when {
            tok.isPunct('(') || tok.isPunct('[') -> depth++
            tok.isPunct(')') || tok.isPunct(']') -> depth--
            tok.isPunct(',') && depth == 0 -> {
                args += t.subList(start, i)
                start = i + 1
            }
        }
    }
    args += t.subList(start, t.size)
    return args.takeIf { list -> list.none { it.isEmpty() } }
}

private const val BULWARK_EXTERNAL = "--- External rules (managed outside Bulwark) ---"
private const val NEXTCLOUD_MARKER = "### Nextcloud Mail: Filters ### DON'T EDIT ###"

internal fun isNextcloudMarker(line: String) = line.trim() == NEXTCLOUD_MARKER

internal fun isBulwarkExternalSeparator(line: String) = line.trim().trimStart('#').trim() == BULWARK_EXTERNAL

/** The header line that names the block: the last one, unless it is a separator or a marker. */
internal fun nameLine(header: List<String>): String? = header.lastOrNull()?.takeIf { line ->
    line.trim().startsWith("#") && !isBulwarkExternalSeparator(line) && !isNextcloudMarker(line) &&
        !line.trim().startsWith("# STERNA-")
}

private val roundcubeName = Regex("""^rule:\s*\[(.*)]$""", RegexOption.IGNORE_CASE)

/** The name a header gives its block: `# Rule: X` (Bulwark), `# rule:[X]` (Roundcube), or the bare comment. */
internal fun ruleName(header: List<String>): String {
    val text = nameLine(header)?.trim()?.trimStart('#')?.trim() ?: return ""
    roundcubeName.matchEntire(text)?.let { return it.groupValues[1].trim() }
    return if (text.startsWith("Rule:")) text.removePrefix("Rule:").trim() else text
}

/** The name after `# Rule:` on the last header line, the mark of a block Bulwark wrote, or null. */
internal fun bulwarkRuleName(header: List<String>): String? =
    header.lastOrNull()?.trim()?.takeIf { it.startsWith("#") }?.trimStart('#')?.trim()
        ?.takeIf { it.startsWith("Rule:") }?.removePrefix("Rule:")?.trim()

internal fun flatName(name: String): String = name.trim().replace(Regex("\\s+"), " ")

/**
 * [header] with its name line replaced by [name]. Never `# Rule:`: in a Bulwark script, a block under
 * `# Rule: <a Bulwark rule's name>` is dropped by Bulwark as its own. A Roundcube `rule:[…]` keeps its form.
 */
internal fun renamedHeader(header: List<String>, name: String, indent: String, lineEnding: String): List<String> {
    val old = nameLine(header)
    val kept = if (old != null) header.dropLast(1) else header
    if (name.isBlank()) return kept
    val roundcube = old?.trim()?.trimStart('#')?.trim()?.let { roundcubeName.matches(it) } == true
    val line = if (roundcube) "# rule:[${flatName(name)}]" else "# ${flatName(name)}"
    return kept + (indent + line + lineEnding)
}

internal fun defaultWord(field: RuleField) = if (field == RuleField.SUBJECT) "header" else "address"

/** The Sieve of [rule]'s test, `address` or `header` as [word] says; an `address` test cannot read the subject. */
internal fun formTest(rule: FilterRule, word: String): String {
    val used = if (word == "address" && rule.field == RuleField.SUBJECT) "header" else word
    val tag = if (rule.match == RuleMatch.IS) ":is" else ":contains"
    val field = rule.field.name.lowercase()
    return "$used $tag \"$field\" \"${SieveCodec.escape(rule.value)}\""
}

/** [rule] as it may be written: a blank value matches every message, so an active one is written disabled. */
internal fun asWritten(rule: FilterRule): FilterRule = if (rule.enabled && rule.value.isBlank()) rule.copy(enabled = false) else rule

/**
 * The `if` block for [rule]. A disabled rule is `allof(false, T)`, valid Sieve that never matches, so it
 * needs no hidden copy to survive. Actions keep the block's [order]; added flags go first, an added `fileinto` last.
 */
internal fun formStatement(
    rule: FilterRule,
    word: String,
    guard: String?,
    indent: String,
    lineEnding: String,
    order: List<String> = emptyList(),
): String {
    val test = formTest(rule, word)
    val condition = when {
        !asWritten(rule).enabled -> "allof(${listOfNotNull("false", test, guard).joinToString(", ")})"
        guard != null -> "allof($test, $guard)"
        else -> test
    }
    val lines = listOfNotNull(
        ("seen" to "addflag \"\\\\Seen\";").takeIf { rule.markRead },
        ("flagged" to "addflag \"\\\\Flagged\";").takeIf { rule.flag },
        rule.moveTo?.let { "fileinto" to "fileinto \"${SieveCodec.escape(it)}\";" },
    ).toMap()
    // RFC 5232: `fileinto` files the flags set so far. Reordering a block the user wrote changes what it does.
    val kept = order.filter { it in lines }
    val sequence = listOf("seen", "flagged").filter { it in lines && it !in kept } + kept +
        listOf("fileinto").filter { it in lines && it !in kept }
    return "if $condition {$lineEnding" + sequence.joinToString("") { "$indent    ${lines.getValue(it)}$lineEnding" } + "$indent}"
}

/** The form actions of an `if` [statement], in the order it runs them: `seen`, `flagged`, `fileinto`. */
internal fun actionOrder(statement: String): List<String> {
    val toks = sieveTokens(statement) ?: return emptyList()
    val open = toks.indexOfFirst { it.isPunct('{') }
    if (open < 0) return emptyList()
    return toks.drop(open + 1).withIndex().mapNotNull { (i, tok) ->
        val argument = toks.getOrNull(open + 2 + i)?.text
        when {
            tok.isWord("fileinto") -> "fileinto"
            tok.isWord("addflag") && argument == "\\Seen" -> "seen"
            tok.isWord("addflag") && argument == "\\Flagged" -> "flagged"
            else -> null
        }
    }
}

/** The capabilities a block written for [rule] uses. */
internal fun capabilitiesOf(rule: FilterRule): Set<String> = buildSet {
    if (rule.moveTo != null) add("fileinto")
    if (rule.markRead || rule.flag) add("imap4flags")
}

/** Every capability a `require` [statement] names. */
internal fun requiredCapabilities(statement: String): List<String> =
    sieveTokens(statement).orEmpty().filter { it.kind == TokenKind.STRING }.map { it.text }

/**
 * The `require` [statement] with [missing] appended to what it already names, or null when it is no
 * list nor string. Only ever adds: a capability another client's blocks use must survive our save.
 */
internal fun requireWith(statement: String, missing: List<String>): String? {
    if (missing.isEmpty()) return statement
    val toks = sieveTokens(statement) ?: return null
    val quoted = missing.joinToString(", ") { "\"${SieveCodec.escape(it)}\"" }
    val close = toks.indexOfLast { it.isPunct(']') }
    if (close > 0) {
        val separator = if (toks[close - 1].isPunct('[')) "" else ", "
        val at = toks[close].start
        return statement.substring(0, at) + separator + quoted + statement.substring(at)
    }
    val single = toks.getOrNull(1)?.takeIf { it.kind == TokenKind.STRING } ?: return null
    return statement.substring(0, single.start) + "[" + statement.substring(single.start, single.end) + ", " +
        quoted + "]" + statement.substring(single.end)
}

/** Whether [statement] is a bare `vacation` command or an `if` whose body is one. */
internal fun isVacationBlock(statement: String): Boolean {
    val toks = sieveTokens(statement) ?: return false
    if (toks.firstOrNull()?.isWord("vacation") == true) return true
    if (toks.firstOrNull()?.isWord("if") != true || !toks.last().isPunct('}')) return false
    val open = toks.indexOfFirst { it.isPunct('{') }
    if (open < 0) return false
    val body = toks.subList(open + 1, toks.size - 1)
    return body.firstOrNull()?.isWord("vacation") == true &&
        body.indexOfFirst { it.isPunct(';') } == body.size - 1
}

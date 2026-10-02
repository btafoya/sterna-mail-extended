package app.sterna.core.data.filter

/**
 * An `if` block the rule form can edit: its [rule], the test word each condition was written with
 * ([words], `address` or `header` for an address field), and the verbatim tests `G` of `allof(T…, G…)`
 * the form cannot show ([guard]), put back in place when the block is rewritten.
 */
internal data class FormBlock(val rule: FilterRule, val words: List<String>, val guard: String?)

private class TestShape(val disabled: Boolean, val mode: RuleMode, val atoms: List<Atom>, val guard: String?)

private class Atom(val word: String, val condition: Condition)

/**
 * [statement] as a form rule, or null when the form cannot show all of it. A rule has no name here:
 * the name lives in the comment above the block.
 */
internal fun recognizeForm(statement: String): FormBlock? {
    val toks = sieveTokens(statement) ?: return null
    // A comment inside the block would be lost the first time the form rewrites it.
    if (toks.any { it.kind == TokenKind.COMMENT || it.kind == TokenKind.LITERAL }) return null
    if (toks.firstOrNull()?.isWord("if") != true || toks.last().isPunct('}').not()) return null
    val open = toks.indexOfFirst { it.isPunct('{') }
    if (open < 0) return null
    val shape = testShape(toks.subList(1, open), statement) ?: return null
    val actions = formActions(toks.subList(open + 1, toks.size - 1)) ?: return null
    return FormBlock(
        rule = FilterRule(
            enabled = !shape.disabled,
            mode = shape.mode,
            conditions = shape.atoms.map { it.condition },
            actions = actions,
        ),
        words = shape.atoms.map { it.word },
        guard = shape.guard,
    )
}

/** The actions of a block body, in the order it runs them, or null when one of them is not the form's. */
private fun formActions(body: List<SieveToken>): List<Action>? {
    val actions = mutableListOf<Action>()
    var i = 0
    while (i < body.size) {
        val end = (i until body.size).firstOrNull { body[it].isPunct(';') } ?: return null
        val command = body[i]
        val args = body.subList(i + 1, end)
        if (command.kind != TokenKind.WORD) return null
        // Nothing runs after a `stop`: a command there could only be lost by a rewrite.
        if (Action.Stop in actions) return null
        val strings = args.all { it.kind == TokenKind.STRING }
        val action = when (command.text.lowercase()) {
            "stop" -> Action.Stop.takeIf { args.isEmpty() }
            "fileinto" -> when {
                args.size == 1 && strings -> Action.MoveTo(args[0].text).takeIf { actions.none { a -> a is Action.MoveTo } }
                args.size == 2 && args[0].kind == TokenKind.TAG && args[0].text.equals(":copy", ignoreCase = true) &&
                    args[1].kind == TokenKind.STRING -> Action.CopyTo(args[1].text)
                else -> null
            }
            "addflag" -> when (args.singleOrNull()?.takeIf { strings }?.text) {
                "\\Seen" -> Action.MarkRead
                "\\Flagged" -> Action.Star
                else -> null
            }?.takeIf { it !in actions }
            "redirect" -> when {
                args.size == 1 && strings -> Action.ForwardTo(args[0].text, keepCopy = false)
                args.size == 2 && args[0].kind == TokenKind.TAG && args[0].text.equals(":copy", ignoreCase = true) &&
                    args[1].kind == TokenKind.STRING -> Action.ForwardTo(args[1].text, keepCopy = true)
                else -> null
            }
            else -> null
        } ?: return null
        actions += action
        i = end + 1
    }
    return actions
}

/** The verbatim `G` of an `if allof(T…, G…)` whose `T` are form tests, or null. */
internal fun guardOf(statement: String): String? {
    val toks = sieveTokens(statement) ?: return null
    val open = toks.indexOfFirst { it.isPunct('{') }
    if (open < 1 || !toks[0].isWord("if")) return null
    return testShape(toks.subList(1, open), statement)?.guard
}

/**
 * `T`, `anyof(T…)`, `allof(T…)`, `allof(T…, G…)`, `allof(anyof(T…), G…)`, each also behind `allof(false, …)`.
 * The form's tests of an `allof` are the ones before the first it cannot show; that one and all after
 * it are the guard, in their place. An `anyof` is all form tests or nothing: dropping one of its tests
 * would widen the rule.
 */
private fun testShape(test: List<SieveToken>, source: String): TestShape? {
    atom(test)?.let { return TestShape(disabled = false, mode = RuleMode.ALL, atoms = listOf(it), guard = null) }
    anyofAtoms(test)?.let { return TestShape(disabled = false, mode = RuleMode.ANY, atoms = it, guard = null) }
    val args = call("allof", test) ?: return null
    val disabled = args[0].size == 1 && args[0][0].isWord("false")
    val rest = if (disabled) args.drop(1) else args
    val any = rest.firstOrNull()?.let(::anyofAtoms)
    val atoms = any ?: rest.map(::atom).takeWhile { it != null }.filterNotNull()
    if (atoms.isEmpty()) return null
    val guardArgs = rest.drop(if (any != null) 1 else atoms.size)
    // `allof(T)` and `allof(anyof(…))` alone would come back as `T` and `anyof(…)`.
    if (!disabled && guardArgs.isEmpty() && (any != null || atoms.size == 1)) return null
    val guard = guardArgs.takeIf { it.isNotEmpty() }
        ?.let { source.substring(it.first().first().start, it.last().last().end) }
    return TestShape(disabled, if (any != null) RuleMode.ANY else RuleMode.ALL, atoms, guard)
}

private fun anyofAtoms(test: List<SieveToken>): List<Atom>? {
    val args = call("anyof", test)?.takeIf { it.size >= 2 } ?: return null
    return args.map { atom(it) ?: return null }
}

/** The arguments of `name(…)` when [t] is that call whole, or null. */
private fun call(name: String, t: List<SieveToken>): List<List<SieveToken>>? {
    if (t.size < 4 || !t[0].isWord(name) || !t[1].isPunct('(') || !t.last().isPunct(')')) return null
    return topLevelArguments(t.subList(2, t.size - 1))
}

private fun atom(t: List<SieveToken>): Atom? {
    if (t.firstOrNull()?.isWord("not") == true) {
        val inner = atom(t.drop(1)) ?: return null
        if (inner.condition.match != RuleMatch.CONTAINS || inner.condition.field == ConditionField.SIZE) return null
        return Atom(inner.word, inner.condition.copy(match = RuleMatch.NOT_CONTAINS))
    }
    if (t.size == 3 && t[0].isWord("size") && t[1].kind == TokenKind.TAG && t[2].kind == TokenKind.NUMBER) {
        val match = when (t[1].text.lowercase()) {
            ":over" -> RuleMatch.OVER
            ":under" -> RuleMatch.UNDER
            else -> return null
        }
        val bytes = sizeBytes(t[2].text) ?: return null
        return Atom("size", Condition(ConditionField.SIZE, match, bytes.toString()))
    }
    if (t.size == 4 && t[0].isWord("body") && t[1].kind == TokenKind.TAG && t[1].text.equals(":text", ignoreCase = true) &&
        t[2].kind == TokenKind.TAG && t[2].text.equals(":contains", ignoreCase = true) && t[3].kind == TokenKind.STRING
    ) {
        return Atom("body", Condition(ConditionField.BODY, RuleMatch.CONTAINS, t[3].text))
    }
    if (t.size != 4 || t[0].kind != TokenKind.WORD || t[1].kind != TokenKind.TAG) return null
    if (t[2].kind != TokenKind.STRING || t[3].kind != TokenKind.STRING) return null
    val word = t[0].text.lowercase()
    val match = when (t[1].text.lowercase()) {
        ":contains" -> RuleMatch.CONTAINS
        ":is" -> RuleMatch.IS
        else -> return null
    }
    val field = when (t[2].text.lowercase()) {
        "from" -> ConditionField.FROM
        "to" -> ConditionField.TO
        "cc" -> ConditionField.CC
        "subject" -> ConditionField.SUBJECT
        "list-id" -> ConditionField.LIST
        else -> return null
    }
    val condition = Condition(field, match, t[3].text)
    return when {
        word == "header" -> Atom(word, condition)
        word == "address" && field in ADDRESS_FIELDS -> Atom(word, condition)
        else -> null
    }
}

private val ADDRESS_FIELDS = setOf(ConditionField.FROM, ConditionField.TO, ConditionField.CC)

private const val KB = 1024L

/**
 * The bytes of a Sieve number, `5M` included, or null when it is no whole number of kilobytes: the
 * form shows a size in KB or MB, and a value it could not show would be rewritten rounded.
 */
internal fun sizeBytes(number: String): Long? {
    val digits = number.takeWhile { it.isDigit() }
    // The most digits each quantifier takes with the product still inside a Long.
    val (unit, most) = when (number.drop(digits.length).uppercase()) {
        "" -> 1L to 18
        "K" -> KB to 15
        "M" -> KB * KB to 12
        "G" -> KB * KB * KB to 9
        else -> return null
    }
    val n = digits.takeIf { it.isNotEmpty() && it.length <= most }?.toLong() ?: return null
    return (n * unit).takeIf { it % KB == 0L }
}

/** [bytes] as Sieve writes a size: with the largest quantifier that keeps it whole. */
internal fun sizeNumber(bytes: Long): String = when {
    bytes != 0L && bytes % (KB * KB * KB) == 0L -> "${bytes / (KB * KB * KB)}G"
    bytes != 0L && bytes % (KB * KB) == 0L -> "${bytes / (KB * KB)}M"
    bytes != 0L && bytes % KB == 0L -> "${bytes / KB}K"
    else -> bytes.toString()
}

/** The matches the form offers on [field]; a condition outside them is never written. */
fun matchesFor(field: ConditionField): List<RuleMatch> = when (field) {
    ConditionField.SIZE -> listOf(RuleMatch.OVER, RuleMatch.UNDER)
    ConditionField.BODY -> listOf(RuleMatch.CONTAINS, RuleMatch.NOT_CONTAINS)
    else -> listOf(RuleMatch.CONTAINS, RuleMatch.IS, RuleMatch.NOT_CONTAINS)
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

internal fun defaultWord(field: ConditionField) = when (field) {
    in ADDRESS_FIELDS -> "address"
    ConditionField.BODY -> "body"
    ConditionField.SIZE -> "size"
    else -> "header"
}

/** The Sieve of [condition], `address` or `header` as [word] says for an address field; any other takes its own word. */
internal fun conditionTest(condition: Condition, word: String?): String {
    val field = condition.field
    val match = condition.match
    require(match in matchesFor(field)) { "$match on $field" }
    val value = SieveCodec.escape(condition.value)
    val tag = if (match == RuleMatch.IS) ":is" else ":contains"
    val test = when (field) {
        ConditionField.SIZE -> "size ${if (match == RuleMatch.OVER) ":over" else ":under"} ${sizeNumber(condition.value.toLong())}"
        ConditionField.BODY -> "body :text $tag \"$value\""
        else -> {
            val used = if (field in ADDRESS_FIELDS && (word == "address" || word == "header")) word else defaultWord(field)
            val name = if (field == ConditionField.LIST) "list-id" else field.name.lowercase()
            "$used $tag \"$name\" \"$value\""
        }
    }
    return if (match == RuleMatch.NOT_CONTAINS) "not $test" else test
}

/**
 * For each of [edited], the place in [read] of the condition it continues, or null for a new one: the
 * same condition first, then one on the same field, then the one left on each side. Never the rank
 * alone, or removing a condition hands the next one the `address` or `header` of the removed one.
 */
internal fun pairedConditions(read: List<Condition>, edited: List<Condition>): List<Int?> {
    val pairs = arrayOfNulls<Int>(edited.size)
    val used = BooleanArray(read.size)
    fun pass(same: (Condition, Condition) -> Boolean) {
        for (k in edited.indices) {
            if (pairs[k] != null) continue
            val i = read.indices.firstOrNull { !used[it] && same(read[it], edited[k]) } ?: continue
            pairs[k] = i
            used[i] = true
        }
    }
    pass { a, b -> a == b }
    pass { a, b -> a.field == b.field }
    val newLeft = edited.indices.filter { pairs[it] == null }
    val readLeft = read.indices.filter { !used[it] }
    if (newLeft.size == 1 && readLeft.size == 1) pairs[newLeft[0]] = readLeft[0]
    return pairs.toList()
}

/** The one Sieve test of [rule]'s conditions, each with the word [words] gives it, its guard left out. */
internal fun formTest(rule: FilterRule, words: List<String?>): String {
    val tests = rule.conditions.mapIndexed { i, c -> conditionTest(c, words.getOrNull(i)) }
    require(tests.isNotEmpty()) { "a rule with no condition has no test" }
    return when {
        tests.size == 1 -> tests.single()
        rule.mode == RuleMode.ALL -> "allof(${tests.joinToString(", ")})"
        else -> "anyof(${tests.joinToString(", ")})"
    }
}

/** [rule] as it may be written: a blank value matches every message, so an active one is written disabled. */
internal fun asWritten(rule: FilterRule): FilterRule = if (rule.enabled && !rule.isRunnable) rule.copy(enabled = false) else rule

/** The key of each action in a block, a kind and its rank among its kind, so an edit can keep the order it was read in. */
private fun actionKeys(actions: List<Action>): List<String> {
    val seen = HashMap<String, Int>()
    return actions.map { action ->
        val kind = when (action) {
            Action.MarkRead -> "seen"
            Action.Star -> "flagged"
            is Action.CopyTo -> "copy"
            is Action.MoveTo -> "fileinto"
            is Action.ForwardTo -> "redirect"
            Action.Stop -> "stop"
            is Action.Label -> "label"
        }
        val n = seen.getOrDefault(kind, 0)
        seen[kind] = n + 1
        "$kind#$n"
    }
}

private val NEW_ACTION_RANK = listOf("seen", "flagged", "copy", "fileinto", "redirect")

private fun actionLine(action: Action): String = when (action) {
    Action.MarkRead -> "addflag \"\\\\Seen\";"
    Action.Star -> "addflag \"\\\\Flagged\";"
    is Action.CopyTo -> "fileinto :copy \"${SieveCodec.escape(action.folder)}\";"
    is Action.MoveTo -> "fileinto \"${SieveCodec.escape(action.folder)}\";"
    is Action.ForwardTo -> "redirect ${if (action.keepCopy) ":copy " else ""}\"${SieveCodec.escape(action.address)}\";"
    Action.Stop -> "stop;"
    is Action.Label -> labelLine(action.value)
}

/** The line Bulwark's generator writes for an `add_label`: it escapes the value as [SieveCodec.escape] does. */
internal fun labelLine(value: String): String = "addflag \"\$label:${SieveCodec.escape(value)}\";"

/** [rule] with the `stop` of a block read with one: dropped by no rewrite, or every later rule would act on its mail. */
internal fun keptStop(rule: FilterRule, order: List<String>): FilterRule =
    if ("stop#0" in order && Action.Stop !in rule.actions) rule.copy(actions = rule.actions + Action.Stop) else rule

/**
 * The `if` block for [rule]. A disabled rule is `allof(false, …)`, valid Sieve that never matches, so it
 * needs no hidden copy to survive; `allof` and `anyof` only from two conditions. Actions keep the
 * block's [order]; added flags go first, other added actions after, in the order flags, copy, fileinto,
 * redirect; `stop` is always last.
 */
internal fun formStatement(
    rule: FilterRule,
    words: List<String?>,
    guard: String?,
    indent: String,
    lineEnding: String,
    order: List<String> = emptyList(),
): String {
    val tests = rule.conditions.mapIndexed { i, c -> conditionTest(c, words.getOrNull(i)) }
    require(tests.isNotEmpty()) { "a rule with no condition has no test" }
    val core = if (tests.size == 1 || rule.mode == RuleMode.ALL) tests else listOf("anyof(${tests.joinToString(", ")})")
    val condition = when {
        !asWritten(rule).enabled -> "allof(${(listOf("false") + core + listOfNotNull(guard)).joinToString(", ")})"
        guard != null -> "allof(${(core + guard).joinToString(", ")})"
        core.size == 1 -> core.single()
        else -> "allof(${core.joinToString(", ")})"
    }
    val written = keptStop(rule, order).actions
    val lines = actionKeys(written).zip(written).filter { it.second != Action.Stop }.toMap()
    // RFC 5232: `fileinto` files the flags set so far. Reordering a block the user wrote changes what it does.
    val kept = order.filter { it in lines }
    val added = lines.keys.filter { it !in kept }.sortedBy { key -> NEW_ACTION_RANK.indexOf(key.substringBefore('#')) }
    val (flags, others) = added.partition { it.startsWith("seen#") || it.startsWith("flagged#") }
    val sequence = (flags + kept + others).map { lines.getValue(it) } + listOfNotNull(Action.Stop.takeIf { it in written })
    return "if $condition {$lineEnding" + sequence.joinToString("") { "$indent    ${actionLine(it)}$lineEnding" } + "$indent}"
}

/** The action keys of an `if` [statement] the form can edit, in the order it runs them; empty for any other. */
internal fun actionOrder(statement: String): List<String> = recognizeForm(statement)?.rule?.actions?.let(::actionKeys).orEmpty()

/** The capabilities a block written for [rule] uses. */
internal fun capabilitiesOf(rule: FilterRule): Set<String> = buildSet {
    if (rule.actions.any { it is Action.MoveTo || it is Action.CopyTo }) add("fileinto")
    if (rule.actions.any { it is Action.CopyTo || (it is Action.ForwardTo && it.keepCopy) }) add("copy")
    if (rule.actions.any { it == Action.MarkRead || it == Action.Star || it is Action.Label }) add("imap4flags")
    if (rule.conditions.any { it.field == ConditionField.BODY }) add("body")
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

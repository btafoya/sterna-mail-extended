package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * "File future mail from [address] into [trashFolder], already read." A move into the Trash, never
 * `discard`: the mail stays one gesture away.
 */
fun blockRule(address: String, trashFolder: String): FilterRule = FilterRule(
    name = address,
    enabled = true,
    mode = RuleMode.ALL,
    conditions = listOf(Condition(ConditionField.FROM, RuleMatch.IS, address)),
    actions = listOf(Action.MarkRead, Action.MoveTo(trashFolder)),
)

/**
 * Whether [rules] already send [address] away: a rule whose one condition is from/IS on the same
 * address, case ignored, since that is how the screen groups. A `contains` rule, one on another field,
 * or one with more conditions is not this rule: it may or may not catch the same mail, and guessing
 * would either add a duplicate or silently refuse to add anything.
 */
fun alreadyBlocked(rules: List<FilterRule>, address: String): Boolean = rules.any { rule ->
    val only = rule.conditions.singleOrNull()
    only != null && only.field == ConditionField.FROM && only.match == RuleMatch.IS && only.value.equals(address, ignoreCase = true)
}

/**
 * Whether a block rule may target [address] at all, asked before anything the account's script
 */
fun blockableSender(address: String, ownAddresses: List<String>): Boolean {
    val target = address.trim()
    if (target.isEmpty()) return false
    return ownAddresses.none { it.trim().equals(target, ignoreCase = true) }
}

/** What [addBlockRule] did. */
enum class BlockOutcome {
    /** The script now carries the rule, on top of everything it already carried. */
    ADDED,

    /** A from/IS rule for this address was already there; nothing was written. */
    ALREADY_PRESENT,

    /** The rules could not be read, or the save was refused. Nothing was written. */
    FAILED,
}

/**
 * Add the block rule for [address] to the script the filters open: read it, then write it back under
 * its name with one block more, the rest byte for byte, through [blockWrite].
 */
suspend fun addBlockRule(
    address: String,
    trashFolder: String,
    load: suspend () -> FilterRulesState,
    save: suspend (FilterScriptWrite) -> Unit,
): BlockOutcome {
    val state = runCatching { load() }.getOrNull()
    if (state !is FilterRulesState.Loaded) return BlockOutcome.FAILED
    // The duplicate check answers before the refusal, and the order is the message.
    // Neither branch writes, so they differ only in what the screen says: on an address the script
    // already handles, nothing went wrong.
    if (alreadyBlocked(state.rules, address)) return BlockOutcome.ALREADY_PRESENT
    if (state.foreignActiveScript) return BlockOutcome.FAILED
    return runCatching { save(blockWrite(state, blockRule(address, trashFolder))) }
        .fold(onSuccess = { BlockOutcome.ADDED }, onFailure = { BlockOutcome.FAILED })
}

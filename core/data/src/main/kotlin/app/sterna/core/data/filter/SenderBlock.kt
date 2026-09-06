package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * "File future mail from [address] into [trashFolder], already read."
 *
 * [RuleMatch.IS] and the WHOLE address, not `CONTAINS`: `a+news@` and `a+facture@` are two
 * different subscriptions, and Sieve's `address :is "from"` tests the address the screen grouped on.
 *
 * Trash and marked read, nothing else — never a Sieve `discard` or `reject`. [SieveCodec] can
 * emit neither, and it must stay that way: a one-finger gesture that destroys server-side mail with
 * no undo is not a feature this app offers.
 */
fun blockRule(address: String, trashFolder: String): FilterRule = FilterRule(
    name = address,
    enabled = true,
    field = RuleField.FROM,
    match = RuleMatch.IS,
    value = address,
    moveTo = trashFolder,
    markRead = true,
)

/**
 * Whether [rules] already send [address] away — a FROM/IS rule on the same address, case ignored,
 * since that is how the screen groups. A `CONTAINS` rule, or one on another field, is not this
 * rule: guessing would add a duplicate or silently refuse to add anything.
 */
fun alreadyBlocked(rules: List<FilterRule>, address: String): Boolean = rules.any {
    it.field == RuleField.FROM && it.match == RuleMatch.IS && it.value.equals(address, ignoreCase = true)
}

/**
 * Whether a block rule may target [address] at all — asked before anything the account's script
 * says, because these two noes are about the ADDRESS.
 *
 * - one's own address: `FROM :is <me> → Trash, marked read` files one's OWN mail away.
 * - no address at all: a `From:` carrying only a display name maps to `email = ""`, and
 *    `FROM :is ""` matches no mail and sits in the script for ever.
 *
 * [ownAddresses] is the account's send-as identities plus its login, compared as [alreadyBlocked].
 */
fun blockableSender(address: String, ownAddresses: List<String>): Boolean {
    val target = address.trim()
    if (target.isEmpty()) return false
    return ownAddresses.none { it.trim().equals(target, ignoreCase = true) }
}

/**
 * [loaded] plus the new rule — ADDED, never substituted. `MailRepository.saveFilterRules`
 * recompiles and reactivates the WHOLE script, so saving `listOf(newRule)` does not "add a filter",
 * it deletes every filter the account had. Returns [loaded] untouched when the address is already
 * handled.
 */
fun withBlockRule(loaded: List<FilterRule>, address: String, trashFolder: String): List<FilterRule> =
    if (alreadyBlocked(loaded, address)) loaded else loaded + blockRule(address, trashFolder)

/** What [addBlockRule] did. */
enum class BlockOutcome {
    /** The script now carries the rule, on top of everything it already carried. */
    ADDED,

    /** A FROM/IS rule for this address was already there; nothing was written. */
    ALREADY_PRESENT,

    /** The rules could not be read, or the save was refused. NOTHING was written. */
    FAILED,
}

/**
 * Add the block rule for [address] to the account's server-side script: read the rules, then write
 * them back with one more. [load] and [save] are passed in so the ORDER — and what happens when the
 * read fails — can be executed by a JVM test.
 *
 * `save` rewrites the entire script, so a save built on anything but a successful, complete read
 * wipes the account's filters: a read that throws, or answers anything but
 * [FilterRulesState.Loaded], ends at [BlockOutcome.FAILED] with no write. And it refuses outright
 * on [FilterRulesState.Loaded.foreignActiveScript] — the guard lives here, not only in the screen.
 */
suspend fun addBlockRule(
    address: String,
    trashFolder: String,
    load: suspend () -> FilterRulesState,
    save: suspend (List<FilterRule>) -> Unit,
): BlockOutcome {
    val state = runCatching { load() }.getOrNull()
    if (state !is FilterRulesState.Loaded) return BlockOutcome.FAILED
    // The duplicate check answers BEFORE the foreign-script refusal, and the order is the message.
    // Neither branch writes, so they differ only in what the screen says: on an address the script
    // already handles, nothing went wrong.
    if (alreadyBlocked(state.rules, address)) return BlockOutcome.ALREADY_PRESENT
    if (state.foreignActiveScript) return BlockOutcome.FAILED
    return runCatching { save(withBlockRule(state.rules, address, trashFolder)) }
        .fold(onSuccess = { BlockOutcome.ADDED }, onFailure = { BlockOutcome.FAILED })
}

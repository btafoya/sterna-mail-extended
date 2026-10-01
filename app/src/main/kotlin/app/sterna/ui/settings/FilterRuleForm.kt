package app.sterna.ui.settings

import app.sterna.core.data.filter.Action
import app.sterna.core.data.filter.Condition
import app.sterna.core.data.filter.ConditionField
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.RuleMode
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.bulwarkMatches
import app.sterna.core.data.filter.matchesFor
import app.sterna.util.isValidEmail

/** What "+ Add action" offers. [DELETE] is a move into the account's Trash, never a `discard`. */
internal enum class ActionKind { MOVE, COPY, DELETE, MARK_READ, STAR, FORWARD, STOP }

internal enum class Availability { OFFERED, ALREADY_THERE, NOT_BY_SERVER, NOT_IN_BULWARK }

private fun extensionsOf(kind: ActionKind): Set<String> = when (kind) {
    ActionKind.MOVE, ActionKind.DELETE -> setOf("fileinto")
    ActionKind.COPY -> setOf("fileinto", "copy")
    ActionKind.MARK_READ, ActionKind.STAR -> setOf("imap4flags")
    ActionKind.FORWARD -> setOf("copy")
    ActionKind.STOP -> emptySet()
}

private fun extensionsOf(field: ConditionField): Set<String> = if (field == ConditionField.BODY) setOf("body") else emptySet()

/** A server that announced nothing offers everything: it decides at Save. */
private fun announces(offered: List<String>?, needs: Set<String>): Boolean =
    offered == null || offered.map { it.lowercase() }.containsAll(needs)

private val BULWARK_ACTIONS = setOf(ActionKind.MOVE, ActionKind.DELETE, ActionKind.MARK_READ, ActionKind.STAR, ActionKind.STOP)

internal fun kindOf(action: Action, trashFolder: String?): ActionKind = when (action) {
    is Action.MoveTo -> if (trashFolder != null && action.folder == trashFolder) ActionKind.DELETE else ActionKind.MOVE
    is Action.CopyTo -> ActionKind.COPY
    Action.MarkRead -> ActionKind.MARK_READ
    Action.Star -> ActionKind.STAR
    is Action.ForwardTo -> ActionKind.FORWARD
    Action.Stop -> ActionKind.STOP
}

/** Whether "+ Add action" may add [kind] to [rule]. A rule moves once: a Delete is its move. */
internal fun actionAvailability(kind: ActionKind, rule: FilterRule, offered: List<String>?, bulwark: Boolean): Availability {
    val present = when (kind) {
        ActionKind.MOVE, ActionKind.DELETE -> rule.actions.any { it is Action.MoveTo }
        ActionKind.MARK_READ -> Action.MarkRead in rule.actions
        ActionKind.STAR -> Action.Star in rule.actions
        ActionKind.STOP -> Action.Stop in rule.actions
        ActionKind.COPY, ActionKind.FORWARD -> false
    }
    return when {
        present -> Availability.ALREADY_THERE
        bulwark && kind !in BULWARK_ACTIONS -> Availability.NOT_IN_BULWARK
        !announces(offered, extensionsOf(kind)) -> Availability.NOT_BY_SERVER
        else -> Availability.OFFERED
    }
}

internal fun fieldAvailability(field: ConditionField, offered: List<String>?, bulwark: Boolean): Availability = when {
    bulwark && bulwarkMatches(field).isEmpty() -> Availability.NOT_IN_BULWARK
    !announces(offered, extensionsOf(field)) -> Availability.NOT_BY_SERVER
    else -> Availability.OFFERED
}

/** The matches offered on [field]; for a rule held in Bulwark's JSON, only those it can hold. */
internal fun offeredMatches(field: ConditionField, bulwark: Boolean): List<RuleMatch> =
    if (bulwark) matchesFor(field).filter { it in bulwarkMatches(field) } else matchesFor(field)

/** [condition] tested on [field]: the value stays while it still means something there, the match when it is offered there. */
internal fun withField(condition: Condition, field: ConditionField, bulwark: Boolean): Condition {
    val matches = offeredMatches(field, bulwark)
    val sizeChange = (field == ConditionField.SIZE) != (condition.field == ConditionField.SIZE)
    return Condition(
        field = field,
        match = condition.match.takeIf { it in matches } ?: matches.firstOrNull() ?: matchesFor(field).first(),
        value = if (sizeChange) "" else condition.value,
    )
}

/** The action "+ Add action" adds for [kind]; a folder starts on the first offered, an address empty. */
internal fun newAction(kind: ActionKind, folders: List<String>, trashFolder: String?): Action = when (kind) {
    ActionKind.MOVE -> Action.MoveTo(folders.firstOrNull { it != trashFolder } ?: folders.firstOrNull().orEmpty())
    ActionKind.COPY -> Action.CopyTo(folders.firstOrNull().orEmpty())
    ActionKind.DELETE -> Action.MoveTo(requireNotNull(trashFolder) { "no Trash to delete into" })
    ActionKind.MARK_READ -> Action.MarkRead
    ActionKind.STAR -> Action.Star
    ActionKind.FORWARD -> Action.ForwardTo("")
    ActionKind.STOP -> Action.Stop
}

/** [rule] with [action] added: a `stop` stays last, whatever is added after it. */
internal fun withAction(rule: FilterRule, action: Action): FilterRule {
    val stop = Action.Stop in rule.actions
    val others = rule.actions.filter { it != Action.Stop }
    return rule.copy(actions = if (action == Action.Stop) others + action else others + action + listOfNotNull(Action.Stop.takeIf { stop }))
}

/** One condition fewer; "All" or "Any" means nothing under two, so it goes back to the one the reader gives. */
internal fun withoutCondition(rule: FilterRule, index: Int): FilterRule {
    val left = rule.conditions.filterIndexed { i, _ -> i != index }
    return rule.copy(conditions = left, mode = if (left.size < 2) RuleMode.ALL else rule.mode)
}

/** Whether the row of [action] may be removed: a `stop` the rule was read with is never dropped by the form. */
internal fun actionRemovable(action: Action, original: FilterRule?): Boolean =
    !(action == Action.Stop && original != null && Action.Stop in original.actions)

private const val KILOBYTE = 1024L

internal enum class SizeUnit(val bytes: Long) { KB(1024L), MB(1024L * 1024L) }

/** The unit a size of [bytes] shows in: MB when it is a whole number of them. */
internal fun sizeUnitOf(bytes: String): SizeUnit =
    bytes.toLongOrNull()?.takeIf { it != 0L && it % SizeUnit.MB.bytes == 0L }?.let { SizeUnit.MB } ?: SizeUnit.KB

/** [bytes] as a number of [unit], or "" when it is none. */
internal fun sizeInUnit(bytes: String, unit: SizeUnit): String =
    bytes.toLongOrNull()?.takeIf { it % unit.bytes == 0L }?.let { (it / unit.bytes).toString() }.orEmpty()

/** The bytes of [number] [unit], "" while the field holds no whole number. */
internal fun sizeValue(number: String, unit: SizeUnit): String {
    val n = number.trim().takeIf { it.isNotEmpty() && it.length <= 9 && it.all(Char::isDigit) }?.toLong() ?: return ""
    return (n * unit.bytes).toString()
}

/** Whether every line of [rule] can be written: a condition to test, a size that is a number, a folder, a valid address. */
internal fun ruleComplete(rule: FilterRule): Boolean =
    rule.conditions.isNotEmpty() &&
        rule.conditions.all { it.field != ConditionField.SIZE || (it.value.toLongOrNull()?.let { b -> b % KILOBYTE == 0L } == true) } &&
        rule.actions.all { action ->
            when (action) {
                is Action.MoveTo -> action.folder.isNotBlank()
                is Action.CopyTo -> action.folder.isNotBlank()
                is Action.ForwardTo -> isValidEmail(action.address)
                else -> true
            }
        }

/** The form block open in the editor, as the list read it; null for a rule not written yet or gone. */
internal fun ruleTargetForm(state: FiltersUiState, target: RuleTarget): ScriptRule.Form? {
    val original = target.original ?: return null
    val id = FiltersChange.PutRule(original, target.start, target.rank).targetIn(state.scriptRules) ?: return null
    return state.scriptRules.firstOrNull { it.id == id } as? ScriptRule.Form
}

/** Whether the rule in the editor is written in Bulwark's JSON: an existing Bulwark rule, or a new one in a Bulwark script. */
internal fun ruleInBulwark(state: FiltersUiState, target: RuleTarget): Boolean =
    if (target.original == null) state.bulwark else ruleTargetForm(state, target)?.bulwark == true

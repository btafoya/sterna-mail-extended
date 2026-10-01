package app.sterna.core.data.filter

import kotlinx.serialization.Serializable

@Serializable
enum class RuleField { FROM, TO, CC, SUBJECT }

/** [OVER] and [UNDER] compare a [ConditionField.SIZE] alone. */
@Serializable
enum class RuleMatch { CONTAINS, IS, NOT_CONTAINS, OVER, UNDER }

/** [LIST] tests the `List-Id` header; the value of a [SIZE] is in bytes. */
enum class ConditionField { FROM, TO, CC, SUBJECT, LIST, BODY, SIZE }

enum class RuleMode { ALL, ANY }

data class Condition(
    val field: ConditionField = ConditionField.FROM,
    val match: RuleMatch = RuleMatch.CONTAINS,
    val value: String = "",
)

/** The screen's "Delete" is a [MoveTo] the account's Trash, never `discard`. */
sealed interface Action {
    data class MoveTo(val folder: String) : Action

    data class CopyTo(val folder: String) : Action

    data object MarkRead : Action

    data object Star : Action

    /** [keepCopy] is `redirect :copy`, what a new forward writes; a plain `redirect` read stays plain. */
    data class ForwardTo(val address: String, val keepCopy: Boolean = true) : Action

    data object Stop : Action
}

data class FilterRule(
    val name: String = "",
    val enabled: Boolean = true,
    val mode: RuleMode = RuleMode.ALL,
    val conditions: List<Condition>,
    val actions: List<Action>,
) {
    // V1 fields: `conditions` and `actions` have no default above, so `FilterRule()` resolves here.
    constructor(
        name: String = "",
        enabled: Boolean = true,
        field: RuleField = RuleField.FROM,
        match: RuleMatch = RuleMatch.CONTAINS,
        value: String = "",
        moveTo: String? = null,
        markRead: Boolean = false,
        flag: Boolean = false,
    ) : this(
        name = name,
        enabled = enabled,
        mode = RuleMode.ALL,
        conditions = listOf(Condition(field.condition, match, value)),
        actions = v1Actions(moveTo, markRead, flag),
    )

    /** Nothing entered: such a rule can neither filter nor be recognised, so it is never written. */
    val isEmpty: Boolean
        get() = name.isBlank() && conditions.all { it.value.isBlank() } && actions.isEmpty()

    /** A blank value matches every message: such a rule, or one with no condition, is only ever written disabled. */
    val isRunnable: Boolean
        get() = enabled && conditions.isNotEmpty() && conditions.all { it.value.isNotBlank() }

    // The V1 view, what the V1 codec reads: `field` throws for a first condition off the four V1 fields.
    val field: RuleField get() = RuleField.valueOf(conditions.first().field.name)

    val match: RuleMatch get() = conditions.firstOrNull()?.match ?: RuleMatch.CONTAINS

    val value: String get() = conditions.firstOrNull()?.value.orEmpty()

    // Stored, not a getter: the V1 codec smart-casts it.
    val moveTo: String? = actions.firstNotNullOfOrNull { (it as? Action.MoveTo)?.folder }

    val markRead: Boolean get() = Action.MarkRead in actions

    val flag: Boolean get() = Action.Star in actions
}

val RuleField.condition: ConditionField get() = ConditionField.valueOf(name)

private fun v1Actions(moveTo: String?, markRead: Boolean, flag: Boolean): List<Action> = listOfNotNull(
    Action.MarkRead.takeIf { markRead },
    Action.Star.takeIf { flag },
    moveTo?.let { Action.MoveTo(it) },
)

/**
 * [this] with its V1 view changed, only reached by a call naming a V1 field. Other conditions and actions
 * stay in place; an added flag goes first, an added move before a closing [Action.Stop].
 */
fun FilterRule.copy(
    name: String = this.name,
    enabled: Boolean = this.enabled,
    field: RuleField = this.field,
    match: RuleMatch = this.match,
    value: String = this.value,
    moveTo: String? = this.moveTo,
    markRead: Boolean = this.markRead,
    flag: Boolean = this.flag,
): FilterRule {
    var moved = false
    val kept = actions.mapNotNull { action ->
        when (action) {
            is Action.MoveTo -> if (moved || moveTo == null) null else Action.MoveTo(moveTo).also { moved = true }
            Action.MarkRead -> action.takeIf { markRead }
            Action.Star -> action.takeIf { flag }
            else -> action
        }
    }
    val flags = listOfNotNull(
        Action.MarkRead.takeIf { markRead && Action.MarkRead !in kept },
        Action.Star.takeIf { flag && Action.Star !in kept },
    )
    val stop = kept.lastOrNull() == Action.Stop
    val body = flags + (if (stop) kept.dropLast(1) else kept)
    val move = listOfNotNull(moveTo?.takeIf { !moved }?.let { Action.MoveTo(it) })
    return FilterRule(
        name = name,
        enabled = enabled,
        mode = mode,
        conditions = listOf(Condition(field.condition, match, value)) + conditions.drop(1),
        actions = body + move + listOfNotNull(Action.Stop.takeIf { stop }),
    )
}

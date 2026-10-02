package app.sterna.ui.settings

import app.sterna.core.data.filter.Action
import app.sterna.core.data.filter.Condition
import app.sterna.core.data.filter.ConditionField
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptWrite
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.RuleMode
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.SieveScript
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.ui.components.movedBlock

/**
 * One write the filters screen asks for. A block is named by what it held when it was read and its
 * [rank] among the blocks holding the same, not by its id: ids are numbered per read, so after a
 * script changed elsewhere is read again the same id can name another block.
 */
sealed interface FiltersChange {
    /** The form rule read as [was] written as [rule]; [was] null adds [rule]. */
    data class PutRule(val was: FilterRule?, val rule: FilterRule, val rank: Int = 0) : FiltersChange

    data class DeleteRule(val was: FilterRule, val rank: Int = 0) : FiltersChange

    /** The editable text block [name] read as [was] replaced by [text]. */
    data class PutText(val name: String, val was: String, val text: String, val rank: Int = 0) : FiltersChange

    /** A text block, Bulwark's included, whose text can be empty: found by its name as well. */
    data class DeleteText(val name: String, val was: String, val rank: Int = 0) : FiltersChange

    /** The script written back as read: the one gesture that restarts rules the server holds but does not run. */
    data object Rewrite : FiltersChange

    /** The rule [moved] placed just before [before], or after the last rule when [before] is null. */
    data class MoveRule(val moved: BlockRef, val before: BlockRef?) : FiltersChange
}

/** A block of the list as a change names it, form rule or text, Bulwark's included. */
sealed interface BlockRef {
    val rank: Int

    data class Form(val rule: FilterRule, override val rank: Int = 0) : BlockRef

    data class Text(val name: String, val text: String, override val rank: Int = 0) : BlockRef
}

private fun BlockRef.matcher(): (ScriptRule) -> Boolean = when (this) {
    is BlockRef.Form -> sameRule(rule)
    is BlockRef.Text -> sameText(name, text, editableOnly = false)
}

internal fun BlockRef.idIn(rules: List<ScriptRule>): Int? = rules.filter(matcher()).getOrNull(rank)?.id

/** The block at [index] of [rules], ranked among the blocks a change could not tell from it. */
internal fun blockRef(rules: List<ScriptRule>, index: Int): BlockRef {
    val unranked = when (val r = rules[index]) {
        is ScriptRule.Form -> BlockRef.Form(r.rule)
        is ScriptRule.Text -> BlockRef.Text(r.name, r.text)
    }
    val rank = rules.take(index).count(unranked.matcher())
    return when (unranked) {
        is BlockRef.Form -> unranked.copy(rank = rank)
        is BlockRef.Text -> unranked.copy(rank = rank)
    }
}

/** A row of the reorder mode, keyed by what its block holds: ids are given by position at each read. */
internal data class ReorderRow(val key: String, val rule: ScriptRule)

internal fun filtersReorderRows(rules: List<ScriptRule>): List<ReorderRow> =
    rules.indices.map { ReorderRow(blockRef(rules, it).toString(), rules[it]) }

/**
 * The move a drop of the row [from] at [to] writes over [script], whose rules are [rules] as listed,
 * `to` being the row's index once moved; null when [SieveScript.move] refuses that place.
 */
internal fun filtersDrop(rules: List<ScriptRule>, script: SieveScript, from: Int, to: Int): FiltersChange.MoveRule? {
    if (from !in rules.indices || to !in rules.indices || from == to) return null
    val before = rules.movedBlock(from, 1, to).getOrNull(to + 1)
    script.move(rules[from].id, before?.id) ?: return null
    return FiltersChange.MoveRule(blockRef(rules, from), before?.let { blockRef(rules, rules.indexOf(it)) })
}

private fun sameRule(w: FilterRule) = { r: ScriptRule -> r is ScriptRule.Form && r.rule == w }

private fun sameText(name: String, text: String, editableOnly: Boolean) =
    { r: ScriptRule -> r is ScriptRule.Text && (!editableOnly || !r.lockedByBulwark) && r.name == name && r.text == text }

/** The id of the block [this] was made on, in [rules]; null when it is gone, or the change names none. */
internal fun FiltersChange.targetIn(rules: List<ScriptRule>): Int? = when (this) {
    is FiltersChange.PutRule -> was?.let { w -> rules.filter(sameRule(w)).getOrNull(rank)?.id }
    is FiltersChange.DeleteRule -> rules.filter(sameRule(was)).getOrNull(rank)?.id
    is FiltersChange.PutText -> rules.filter(sameText(name, was, editableOnly = true)).getOrNull(rank)?.id
    is FiltersChange.DeleteText -> rules.filter(sameText(name, was, editableOnly = false)).getOrNull(rank)?.id
    is FiltersChange.MoveRule -> moved.idIn(rules)
    FiltersChange.Rewrite -> null
}

/** Whether [this] needs a block of the script at all: an added rule and a rewrite do not. */
private val FiltersChange.namesABlock: Boolean
    get() = this !is FiltersChange.Rewrite && !(this is FiltersChange.PutRule && was == null)

internal sealed interface FiltersWritePlan {
    data class Write(val write: FilterScriptWrite) : FiltersWritePlan

    /** An edited text leaves a bracket, a string or a comment open: nothing can be written. */
    data object TextUncut : FiltersWritePlan

    /** The block the change was made on is no longer in the script opened. */
    data object BlockGone : FiltersWritePlan
}

/** What saving [change] writes over the script [opened], under its name. What the change does not name stays byte for byte. */
internal fun filtersWrite(opened: FilterRulesState.Loaded, change: FiltersChange): FiltersWritePlan {
    val script = opened.scriptToEdit
    val at = change.targetIn(opened.scriptRules)
    if (change.namesABlock && at == null) return FiltersWritePlan.BlockGone
    val edited = when (change) {
        is FiltersChange.PutRule -> if (at == null) script.add(change.rule) else script.update(at, change.rule)
        is FiltersChange.DeleteRule -> script.remove(at!!)
        is FiltersChange.PutText -> script.replaceText(at!!, change.text) ?: return FiltersWritePlan.TextUncut
        is FiltersChange.DeleteText -> script.remove(at!!)
        // Refused over this script although allowed over the list it was dropped on: that list is
        // no longer the script, and it is read again like one changed elsewhere.
        is FiltersChange.MoveRule -> script.move(at!!, change.before?.let { it.idIn(opened.scriptRules) ?: return FiltersWritePlan.BlockGone })
            ?: return FiltersWritePlan.BlockGone
        FiltersChange.Rewrite -> script
    }
    return FiltersWritePlan.Write(FilterScriptWrite(opened.snapshot, edited))
}

internal sealed interface FiltersReplay {
    /** Asked again over the script read anew, through the same door as the first time. */
    data class Again(val change: FiltersChange) : FiltersReplay

    /** Offered back in its editor, so what was typed is not lost. */
    data class Propose(val change: FiltersChange) : FiltersReplay

    /** Nothing left to apply it to. */
    data object Dropped : FiltersReplay
}

/**
 * What becomes of [change] once a write of it was refused because the script changed on the server,
 * and the script was read again as [reread]. Replayed once, and only when the write cannot stop the
 * server's auto-reply ([warns]): that write is the user's to confirm, never a replay's.
 */
internal fun filtersReplay(reread: FilterRulesState.Loaded, change: FiltersChange, replayedOnce: Boolean, warns: Boolean): FiltersReplay {
    val found = change.targetIn(reread.scriptRules) != null
    if (!replayedOnce && !warns && (found || !change.namesABlock)) return FiltersReplay.Again(change)
    return when (change) {
        is FiltersChange.PutRule -> FiltersReplay.Propose(if (found) change else change.copy(was = null, rank = 0))
        is FiltersChange.PutText -> {
            if (found) return FiltersReplay.Propose(change)
            // Offered over the block of that name as it reads now: its old text is gone, so a Save
            // against it could only fail again.
            val now = reread.scriptRules.firstOrNull { it is ScriptRule.Text && !it.lockedByBulwark && it.name == change.name }
            if (now is ScriptRule.Text) FiltersReplay.Propose(change.copy(was = now.text, rank = 0)) else FiltersReplay.Dropped
        }
        else -> FiltersReplay.Dropped
    }
}

/** A form rule open in the editor: [original] as the server holds it, null for a rule not written yet. */
internal data class RuleTarget(val original: FilterRule?, val start: FilterRule, val rank: Int = 0)

/** A text block open in the editor, [start] being what the editor shows first. */
internal data class TextTarget(val name: String, val was: String, val start: String, val rank: Int = 0)

/** A rule as a bundle can hold it, so an editor survives a rotation and the death of the process. */
internal fun savedRule(rule: FilterRule): ArrayList<Any?> = arrayListOf(
    rule.name,
    rule.enabled,
    rule.mode.name,
    ArrayList(rule.conditions.map { arrayListOf(it.field.name, it.match.name, it.value) }),
    ArrayList(rule.actions.map(::savedAction)),
)

private fun savedAction(action: Action): ArrayList<String> = when (action) {
    is Action.MoveTo -> arrayListOf("move", action.folder)
    is Action.CopyTo -> arrayListOf("copy", action.folder)
    Action.MarkRead -> arrayListOf("read")
    Action.Star -> arrayListOf("star")
    is Action.ForwardTo -> arrayListOf("forward", action.address, if (action.keepCopy) "copy" else "plain")
    Action.Stop -> arrayListOf("stop")
    is Action.Label -> arrayListOf("label", action.value)
}

private fun restoredAction(saved: List<String>): Action = when (saved[0]) {
    "move" -> Action.MoveTo(saved[1])
    "copy" -> Action.CopyTo(saved[1])
    "read" -> Action.MarkRead
    "star" -> Action.Star
    "forward" -> Action.ForwardTo(saved[1], keepCopy = saved.getOrNull(2) != "plain")
    "label" -> Action.Label(saved[1])
    else -> Action.Stop
}

@Suppress("UNCHECKED_CAST")
internal fun restoredRule(saved: List<Any?>): FilterRule = FilterRule(
    name = saved[0] as String,
    enabled = saved[1] as Boolean,
    mode = RuleMode.valueOf(saved[2] as String),
    conditions = (saved[3] as List<List<String>>).map { Condition(ConditionField.valueOf(it[0]), RuleMatch.valueOf(it[1]), it[2]) },
    actions = (saved[4] as List<List<String>>).map(::restoredAction),
)

internal fun savedRuleTarget(target: RuleTarget): ArrayList<Any?> =
    arrayListOf(target.original?.let(::savedRule), savedRule(target.start), target.rank)

@Suppress("UNCHECKED_CAST")
internal fun restoredRuleTarget(saved: List<Any?>): RuleTarget = RuleTarget(
    original = (saved[0] as List<Any?>?)?.let(::restoredRule),
    start = restoredRule(saved[1] as List<Any?>),
    rank = saved[2] as Int,
)

internal fun savedTextTarget(target: TextTarget): ArrayList<Any?> = arrayListOf(target.name, target.was, target.start, target.rank)

internal fun restoredTextTarget(saved: List<Any?>): TextTarget =
    TextTarget(saved[0] as String, saved[1] as String, saved[2] as String, saved[3] as Int)

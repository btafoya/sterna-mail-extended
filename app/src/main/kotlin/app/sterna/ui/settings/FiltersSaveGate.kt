package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.foreignScriptNotice
import app.sterna.core.data.mail.FilterRulesState

internal enum class FiltersSaveStep { CONFIRM_OVERWRITE, WRITE }

/** Over a script that cannot be cut into blocks, any write replaces the whole script unread: ask first. */
internal fun filtersSaveStep(scriptUnreadable: Boolean): FiltersSaveStep =
    if (scriptUnreadable) FiltersSaveStep.CONFIRM_OVERWRITE else FiltersSaveStep.WRITE

/** The one door of every write of the filters screen: [state] once [change] is asked for, and the
 *  change to write now, null while the overwrite is asked or nothing may be written. */
internal fun filtersSaveAsked(state: FiltersUiState, change: FiltersChange, fromUser: Boolean = true): Pair<FiltersUiState, FiltersChange?> {
    if (filtersBusy(state) || state.overwritePending != null) return state to null
    val asked = if (fromUser) state.copy(replayedOnce = false) else state
    return when (filtersSaveStep(scriptUnreadable = asked.scriptUnreadable)) {
        FiltersSaveStep.CONFIRM_OVERWRITE -> asked.copy(overwritePending = change) to null
        FiltersSaveStep.WRITE -> asked to change
    }
}

internal fun filtersOverwriteConfirmed(state: FiltersUiState): Pair<FiltersUiState, FiltersChange?> {
    val pending = state.overwritePending ?: return state to null
    val next = state.copy(overwritePending = null)
    if (filtersBusy(next)) return next to null
    return next to pending
}

/** A write landed: [written] is the event the screen consumes to close its editor, once. */
internal fun filtersWriteLanded(state: FiltersUiState, saved: FilterRulesState.Loaded, written: Int): FiltersUiState =
    state.withScript(saved).copy(saving = false, landed = written, retry = null)

internal fun filtersWriteFailed(state: FiltersUiState, change: FiltersChange, failure: Throwable): FiltersUiState =
    if (reloadsAfterFailedSave(failure)) state.copy(saving = false, retry = change) else filtersSaveFailed(state, failure)

internal fun filtersLoadFailed(state: FiltersUiState, failure: Throwable): FiltersUiState =
    filtersFailedState(state.copy(loading = false, retry = null), FiltersError.LOAD, failure)

internal fun filtersLoadedBase(
    previous: FiltersUiState,
    accountLabel: String,
    folders: List<String>,
    trashFolder: String? = null,
): FiltersUiState =
    FiltersUiState(
        loading = false,
        supported = true,
        accountLabel = accountLabel,
        folders = folders,
        trashFolder = trashFolder,
        retry = previous.retry,
        replayedOnce = previous.replayedOnce,
    )

internal fun filtersReread(state: FiltersUiState, reread: FilterRulesState.Loaded): Pair<FiltersUiState, FiltersChange?> {
    val change = state.retry ?: return state to null
    val cleared = state.copy(retry = null)
    return when (val replay = filtersReplay(reread, change, state.replayedOnce, filtersWarnsBeforeWrite(state))) {
        is FiltersReplay.Again -> cleared.copy(replayedOnce = true) to replay.change
        is FiltersReplay.Propose -> cleared.copy(proposal = replay.change, errorKind = FiltersError.CHANGED) to null
        FiltersReplay.Dropped -> cleared.copy(errorKind = FiltersError.CHANGED) to null
    }
}

/** A write here would stop the script running now, the server's auto-reply included: the line says so. */
internal fun filtersWarnsBeforeWrite(state: FiltersUiState): Boolean = foreignScriptNotice(
    scriptUnreadable = false,
    foreignActive = state.foreignActive,
    vacationScriptActive = state.vacationScriptActive,
) != null

internal fun ruleEdited(original: FilterRule?, rule: FilterRule): Boolean = rule != (original ?: FilterRule())

/** An empty rule can neither filter nor be recognised in the list, so it is never written; nor is a line that cannot be. */
internal fun ruleSaveEnabled(original: FilterRule?, rule: FilterRule, busy: Boolean, present: Boolean): Boolean =
    !busy && present && ruleEdited(original, rule) && !rule.isEmpty && ruleComplete(rule)

/** A blank text would delete the block without Delete's question. */
internal fun textSaveEnabled(original: String, text: String, busy: Boolean, present: Boolean): Boolean =
    !busy && present && text != original && text.isNotBlank()

internal fun ruleTargetPresent(state: FiltersUiState, target: RuleTarget): Boolean =
    target.original == null || FiltersChange.PutRule(target.original, target.start, target.rank).targetIn(state.scriptRules) != null

internal fun textTargetPresent(state: FiltersUiState, target: TextTarget): Boolean =
    FiltersChange.PutText(target.name, target.was, target.start, target.rank).targetIn(state.scriptRules) != null

internal enum class EditorLeave { ASK, LEAVE, STAY }

/** Leaving during a write cancels it midway: the screen stays until the server answered. */
internal fun editorLeaveStep(changed: Boolean, saving: Boolean): EditorLeave = when {
    saving -> EditorLeave.STAY
    changed -> EditorLeave.ASK
    else -> EditorLeave.LEAVE
}

internal fun filtersMayLeave(state: FiltersUiState): Boolean = !state.saving

internal fun filtersMayReorder(state: FiltersUiState): Boolean =
    !state.loading && !state.noAccount && state.supported && state.errorKind != FiltersError.LOAD && state.scriptRules.size > 1

/** A landed write not yet consumed counts: until the editor closes, its Save must not come back on. */
internal fun filtersBusy(state: FiltersUiState): Boolean =
    !filtersAcceptEdits(state) || state.saving || state.landed != null

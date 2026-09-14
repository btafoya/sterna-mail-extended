package app.sterna.ui.settings

/**
 * Whether a Save on the responder screen may write straight away, or must ask first. On IMAP the
 * auto-reply is written into the `sterna` script and that script activated, so what is at stake is
 * the filters screen's question, and its [FiltersSaveStep] answers it.
 */
internal fun vacationSaveStep(foreignActiveScript: Boolean): FiltersSaveStep =
    if (foreignActiveScript) FiltersSaveStep.CONFIRM_OVERWRITE else FiltersSaveStep.WRITE

/** [state] once the server has taken a save: the active script is Sterna's own, nothing is left to confirm. */
internal fun vacationSavedState(state: VacationUiState): VacationUiState =
    state.copy(saving = false, savedTick = state.savedTick + 1, dirty = false, foreignActiveScript = false)

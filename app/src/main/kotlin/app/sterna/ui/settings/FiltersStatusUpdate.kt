package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.data.filter.rulesAreNotRunning

/**
 * Fold a status read into the filters screen's state.
 * Two round trips, not one: three facts come from [status] (`SieveScript/get`), the two
 * `*FromRulesRead` flags from the earlier rules read, which may describe a slightly different
 * server. They are OR-ed, never merged, so the answer leans towards the alarm.
 * A null [status] is a read that failed, not an all-clear: it changes nothing, so the screen
 * keeps what it had rather than blanking warnings that just became true.
 * @param foreignFromRulesRead what the rules read concluded, both causes folded: another active
 *   script, or a `sterna` script it could not parse. The second is invisible in the script list,
 *   so it is OR-ed in and never cleared by a status read.
 * @param unreadableFromRulesRead the second cause alone, so the screen can say which one it is.
 */
internal fun filtersStateWithStatus(
    state: FiltersUiState,
    status: FilterScriptStatus?,
    foreignFromRulesRead: Boolean = false,
    unreadableFromRulesRead: Boolean = false,
): FiltersUiState {
    val next = if (status == null) {
        state
    } else {
        state.copy(
            // responderEnabled = null: this screen never loads the responder, so only the
            // statement of fact is read out of the verdict, not the prediction.
            rulesNotRunning = rulesAreNotRunning(
                refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
            ),
            foreignActive = status.foreignActive,
            vacationScriptActive = status.vacationScriptActive,
            // A successful read supersedes whatever the previous rules read concluded; only this
            // call's argument may raise it again, one line below.
            scriptUnreadable = false,
        )
    }
    return next.copy(
        foreignActive = next.foreignActive || foreignFromRulesRead,
        scriptUnreadable = next.scriptUnreadable || unreadableFromRulesRead,
    )
}

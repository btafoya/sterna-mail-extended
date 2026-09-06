package app.sterna.ui.settings

/** Leaving a form that still holds unwritten edits (#34): Cancel / Discard / Save on the way out. */

/** What a screen that chose "Save" on the way out must do with the state it is now looking at. */
internal enum class PendingExit {
    /** The save is still in flight (or has not started): stay put and keep watching. */
    WAIT,

    /** The save landed: nothing is unwritten any more, leave the screen. */
    LEAVE,

    /** The server refused: stay on the screen so the error is in front of the user, not behind. */
    STAY,
}

/**
 * Decide the next step for a "save, then leave" that is under way. [failed] wins over [dirty]: the
 * edits survive a refusal, so leaving would drop them. None of the three set means the request has
 * not reached the view model yet, so the screen keeps waiting rather than leaving on a state that
 * predates its own request.
 * @param saving a write is in flight.
 * @param dirty the edits still differ from what the server confirmed — the same flag that gates the
 *   screen's Save button, so undoing an edit by hand counts as nothing left to save.
 * @param failed the last attempt reported an error (refused by the server, invalid dates…).
 */
internal fun pendingExitStep(saving: Boolean, dirty: Boolean, failed: Boolean): PendingExit = when {
    saving -> PendingExit.WAIT
    failed -> PendingExit.STAY
    dirty -> PendingExit.WAIT
    else -> PendingExit.LEAVE
}

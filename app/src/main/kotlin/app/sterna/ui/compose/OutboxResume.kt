package app.sterna.ui.compose

import kotlinx.coroutines.Job

/**
 * Reattach a queued row to a composer rebuilt after a process death: AFTER the startup recovery, never
 * before.
 *
 * The death left the row EDITING, and `parkInterruptedOutboxEdits` parks every EDITING row FAILED so
 * nothing re-arms it. That sweep races this composer: [take] before it either hits an EDITING row
 * `canEdit` refuses, or marks the row EDITING again for the sweep to park FAILED under an open editor.
 * So `join()` first — and it returns on a failed sweep too. [take] answers `null` for a row that is
 * gone or not reopenable, and the caller then keeps the composer detached.
 */
internal suspend fun <T> resumeOutboxRow(id: Long, startupRecovery: Job, take: suspend (Long) -> T?): T? {
    startupRecovery.join()
    return take(id)
}

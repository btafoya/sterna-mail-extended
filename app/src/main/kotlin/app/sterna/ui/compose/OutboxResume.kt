package app.sterna.ui.compose

import kotlinx.coroutines.Job

/**
 * Reattach a queued row to a composer rebuilt after a process death: AFTER the startup recovery,
 * never before — the sequence, as a function a JVM test EXECUTES (`OutboxResumeTest`).
 *
 * Why the `join()` comes first. The row was left EDITING by the death, and the startup recovery
 * (`AppContainer.outboxRecovery` → `MailRepository.parkInterruptedOutboxEdits`) parks every EDITING
 * row FAILED so that nothing re-arms it. That recovery runs in `appScope` while Android is still
 * restoring the back stack, so this composer and the sweep race:
 *
 *  - [take] BEFORE the sweep: the row is still EDITING, which `OutboxLogic.canEdit` refuses — or,
 *    had it been parked a moment earlier, the take marks it EDITING again and the sweep, running
 *    next, parks it FAILED under the open composer. The composer believes it holds the row, Send
 *    believes it consumes it, and the Outbox shows a red line under an open editor.
 *  - [take] AFTER the sweep: the row is FAILED, `canEdit` accepts it, and it comes back EDITING with
 *    nobody left to park it — exactly the state Outbox → Edit leaves it in.
 *
 * `join()` returns on a FAILED Job as on a completed one: a sweep that crashed does not hold the
 * composer hostage, it merely leaves the row EDITING, which the take then refuses, and the composer
 * stays detached (nothing leaves on its own either way — the re-arm never books an EDITING row).
 *
 * [take] answers `null` for a row that is gone or not reopenable (SENDING, encrypted, an
 * unreadable attachment); the caller then keeps the composer detached, as it was before this
 * function existed.
 */
internal suspend fun <T> resumeOutboxRow(id: Long, startupRecovery: Job, take: suspend (Long) -> T?): T? {
    startupRecovery.join()
    return take(id)
}

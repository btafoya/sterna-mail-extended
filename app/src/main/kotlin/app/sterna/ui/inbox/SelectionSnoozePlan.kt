package app.sterna.ui.inbox

/**
 * How a bulk snooze splits: what can actually be [snooze]d, and what is [refused] — written
 * nowhere, because writing it would arm a reminder that cannot ring.
 */
internal data class SelectionSnoozePlan(
    val snooze: List<SelectionTarget>,
    val refused: List<SelectionTarget>,
)

/**
 * Who can be snoozed, decided from one thing only: whether the message has a local row.
 *
 * A snooze is not a server write. It inserts into the local `snoozed` table and arms a worker, and
 * `SnoozeWorker` resolves the message back through the local `emails` table at the due date. A row
 * that only ever existed on screen — a search hit with no cached row, hence
 * [SelectionTarget.folderTrusted] false — is not in that table: the reminder is written, the worker
 * fires, nothing is found, nothing rings, and the `snoozed` row is deleted on the way, so the
 * message also disappears from "Snoozed" (bench, 2026-08-08).
 *
 * Such a target is therefore [refused]: nothing is written for it at all — no `snooze` row, no
 * enqueued work — and the caller counts it as an attempt AND as a failure, so the screen says the
 * action did not go through instead of implying it did. Teaching the worker to fetch the message
 * itself would be the other repair; it is not this one (no schema change, no worker change).
 *
 * Returns plain data rather than doing the work, so this decision runs in a JVM test —
 * `InboxViewModel` is an `AndroidViewModel` and cannot be instantiated in one.
 */
internal fun planSelectionSnooze(targets: List<SelectionTarget>): SelectionSnoozePlan {
    val snooze = mutableListOf<SelectionTarget>()
    val refused = mutableListOf<SelectionTarget>()
    for (target in targets) {
        if (target.folderTrusted) snooze += target else refused += target
    }
    return SelectionSnoozePlan(snooze, refused)
}

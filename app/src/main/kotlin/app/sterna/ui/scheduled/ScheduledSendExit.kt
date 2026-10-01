package app.sterna.ui.scheduled

import androidx.annotation.StringRes
import app.sterna.R

/** Where the one scheduled send's screen goes once "Cancel send" has an answer. */
sealed interface ScheduledCancelExit {
    /** Filed in Drafts: the list, on the Drafts of the account the row belonged to. */
    data class ToDrafts(val accountId: String) : ScheduledCancelExit

    /** Nothing was filed and nothing is scheduled any more: back to the Scheduled list. */
    data object ToListAccountGone : ScheduledCancelExit

    /** The row is still the only copy and still scheduled, so the screen stays on it. */
    data object StayNotCancelled : ScheduledCancelExit

    /** The row was gone before the cancellation read it. Not "sent": the worker also drops a row
     *  it hands to the Outbox, one whose account is gone, and one out of attempts. */
    data object NoLongerScheduled : ScheduledCancelExit
}

/** [rowAccountId] is the cancelled row's own account, never the list's current one: the Scheduled
 *  screen spans every account, and another account's Drafts would not hold the message. */
fun scheduledCancelExit(report: ScheduledCancelReport?, rowAccountId: String): ScheduledCancelExit = when (report) {
    null -> ScheduledCancelExit.NoLongerScheduled
    ScheduledCancelReport.RETURNED_TO_DRAFTS -> ScheduledCancelExit.ToDrafts(rowAccountId)
    ScheduledCancelReport.ACCOUNT_GONE -> ScheduledCancelExit.ToListAccountGone
    ScheduledCancelReport.NOT_CANCELLED -> ScheduledCancelExit.StayNotCancelled
}

@StringRes
fun scheduledCancelExitMessage(exit: ScheduledCancelExit): Int = when (exit) {
    is ScheduledCancelExit.ToDrafts -> R.string.scheduled_cancelled_to_drafts
    ScheduledCancelExit.ToListAccountGone -> R.string.scheduled_cancelled_no_account
    ScheduledCancelExit.StayNotCancelled -> R.string.scheduled_cancel_failed
    ScheduledCancelExit.NoLongerScheduled -> R.string.scheduled_no_longer_scheduled
}

/** Whether the screen closes on its row's disappearance. Not while a cancellation of this row runs
 *  or its answer waits: a successful one deletes the row before it answers, and says more. */
fun scheduledSendClosesAsGone(rowGone: Boolean, cancelling: Boolean, answerPending: Boolean): Boolean =
    rowGone && !cancelling && !answerPending

/** The account to make current before the list lands on its Drafts, or null to leave it. */
fun draftsLandingAccountSwitch(target: String, current: String, known: Collection<String>): String? =
    target.takeIf { it != current && it in known }

package app.sterna.ui.outbox

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.db.OutboxState

/**
 * What an Outbox row SAYS about itself, and in which colour — as pure functions a JVM test can
 * execute (`OutboxScreen` is a composable, this module has no Robolectric and no instrumented
 * tests, so a `when` written inside the composable is unreachable from every test we have).
 *
 * ⛔ The three answers are separate on purpose, and collapsing any two of them back into one is the
 * defect this file was extracted for. `OutboxScreen` used to carry a single `val failed =
 * item.state == OutboxState.FAILED` that decided the wording, the substituted reason AND the tint
 * at once, under an `else ->` that swept every other state into "waiting". A state that is neither
 * failed nor merely waiting — a message saved as a draft on the phone (#95) — then had no honest
 * answer available: whichever branch it fell into said something untrue.
 */

/**
 * The line the row prints for [state]. Exhaustive, with NO `else`: a new state must be given words
 * of its own here rather than silently inheriting another state's sentence.
 *
 * [R.string.outbox_state_kept_as_draft] is a WHOLE SENTENCE, not a fragment: it is printed as it
 * stands. It used to be substituted into "Failed: %1$s", which read "Failed: saved as a draft on
 * this device, not sent" — an announcement of failure over a save that worked.
 */
@StringRes
fun outboxStateLabel(state: OutboxState): Int = when (state) {
    OutboxState.SENDING -> R.string.outbox_state_sending
    OutboxState.FAILED -> R.string.outbox_state_failed
    OutboxState.KEPT_AS_DRAFT -> R.string.outbox_state_kept_as_draft
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING -> R.string.outbox_state_waiting
}

/**
 * Whether [outboxStateLabel]'s string takes the row's `lastError` as its one argument — true for
 * the single state whose wording is "Failed: <reason>".
 *
 * ⚠ It is asked separately from [outboxStateIsError] even though both answer "FAILED" today: one is
 * about the string's format arguments, the other about a colour. A row that carries a `lastError`
 * from a real send failure BEFORE it was reopened and saved as a draft (#95 × #70) keeps that text
 * on the row — deliberately, it is the cause of a thing that really happened — and must not have it
 * printed under a state that is not about it.
 */
fun outboxStateShowsReason(state: OutboxState): Boolean = when (state) {
    OutboxState.FAILED -> true
    OutboxState.SENDING, OutboxState.KEPT_AS_DRAFT,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> false
}

/**
 * Whether the line is painted in the error colour.
 *
 * ⛔ [OutboxState.KEPT_AS_DRAFT] is not an error (#95): the user asked for a draft and got one. Red
 * here would say the save went wrong, and nothing would ever repaint it — there is no deferred
 * upload whose success could take the colour back. Same reasoning as
 * [app.sterna.core.data.db.OutboxLogic.needsFailureBanner] one screen up.
 */
fun outboxStateIsError(state: OutboxState): Boolean = when (state) {
    OutboxState.FAILED -> true
    OutboxState.SENDING, OutboxState.KEPT_AS_DRAFT,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> false
}

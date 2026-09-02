package app.sterna.ui.outbox

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.db.OutboxLogic
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
 *
 * [R.string.outbox_state_interrupted] is a whole sentence for the same reason, and it is careful
 * about what it claims: the delivery stopped part-way and the message MAY already be out, so the
 * line says so and never says the send failed.
 */
@StringRes
fun outboxStateLabel(state: OutboxState): Int = when (state) {
    OutboxState.SENDING -> R.string.outbox_state_sending
    OutboxState.FAILED -> R.string.outbox_state_failed
    OutboxState.KEPT_AS_DRAFT -> R.string.outbox_state_kept_as_draft
    OutboxState.INTERRUPTED -> R.string.outbox_state_interrupted
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
    OutboxState.SENDING, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> false
}

/**
 * The reason a FAILED row substitutes into "Failed: %1$s" — words of ours to translate, or text to
 * print as it is.
 */
sealed class OutboxFailureReason {
    /** A sentence of the app's own, resolved in the user's language at display time. */
    data class Words(@StringRes val id: Int) : OutboxFailureReason()

    /** Text stored on the row and printed verbatim — the server's own words (#183). */
    data class Text(val text: String) : OutboxFailureReason()
}

/**
 * What a FAILED row's `lastError` means on screen.
 *
 * A row parked because its EDIT WAS INTERRUPTED — closed over a lost body, see
 * `MailRepository.parkInterruptedOutboxEdit` — carries the SENTINEL [OutboxLogic.EDIT_INTERRUPTED],
 * not a sentence: a localized sentence written into the row would stay in the language of the day
 * it was written, on a row that may sit in the Outbox for weeks. So the sentinel is stored and the
 * words are chosen here, at display time, in the current locale.
 *
 * ⛔ The server's words (#183) are of ANOTHER nature and unchanged: they are what the server said,
 * they were never ours to translate, and they are printed as they came. A null reason keeps the
 * fallback it always had.
 */
fun outboxFailureReason(lastError: String?): OutboxFailureReason = when (lastError) {
    null -> OutboxFailureReason.Words(R.string.outbox_state_failed_unknown)
    OutboxLogic.EDIT_INTERRUPTED -> OutboxFailureReason.Words(R.string.outbox_error_edit_interrupted)
    else -> OutboxFailureReason.Text(lastError)
}

/**
 * Whether the line is painted in the error colour.
 *
 * ⛔ [OutboxState.KEPT_AS_DRAFT] is not an error (#95): the user asked for a draft and got one. Red
 * here would say the save went wrong, and nothing would ever repaint it — there is no deferred
 * upload whose success could take the colour back. Same reasoning as
 * [app.sterna.core.data.db.OutboxLogic.needsFailureBanner] one screen up.
 *
 * ⛔ [OutboxState.INTERRUPTED] is not an error either: red says the send went wrong, and this one
 * may have gone through — the row is parked precisely because the phone cannot tell.
 */
fun outboxStateIsError(state: OutboxState): Boolean = when (state) {
    OutboxState.FAILED -> true
    OutboxState.SENDING, OutboxState.KEPT_AS_DRAFT, OutboxState.INTERRUPTED,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> false
}

/**
 * The body of the delete confirmation for a row in [state] — the sentence read at the one moment
 * the only copy of a message is about to be destroyed.
 *
 * ⛔ [R.string.outbox_delete_body] opens with "It hasn't been sent", and over
 * [OutboxState.INTERRUPTED] that is a claim this phone cannot make: the row is parked precisely
 * because the delivery may have reached the server before the run was killed. Told the message
 * never left, the reader deletes it — and if it did leave, she has just destroyed her only trace of
 * a message the recipient has. Hence a body of its own, which says it may already be gone and still
 * says what deleting costs.
 *
 * ⚠ Seen and LEFT: [OutboxState.SENDING] and [OutboxState.KEPT_AS_DRAFT] take the default body too,
 * and neither is quite true of them either (a send is in flight; a draft copy does exist on the
 * phone). That wording is older than this fix and out of its scope — it is named here so the next
 * reader knows it was looked at, not missed.
 */
@StringRes
fun outboxDeleteBody(state: OutboxState): Int = when (state) {
    OutboxState.INTERRUPTED -> R.string.outbox_delete_body_interrupted
    OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT,
    OutboxState.HELD, OutboxState.QUEUED, OutboxState.EDITING,
    -> R.string.outbox_delete_body
}

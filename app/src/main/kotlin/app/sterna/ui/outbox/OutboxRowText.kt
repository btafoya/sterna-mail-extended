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
 * Whether a row that is WAITING prints its `lastError` as an EXTRA line, under its state sentence.
 *
 * Not the same question as [outboxStateShowsReason], which asks whether the state's own string
 * substitutes the reason into "Failed: %1$s". This one adds a second line and changes neither the
 * state's words nor its colour: a row that waits is not a row that failed.
 *
 * ⛔ `!lastError.isNullOrBlank()` is THE GUARD OF THE NORMAL CASE, not a null-check for tidiness.
 * Offline — the majority of what is ever seen in this screen — the `NetworkType.CONNECTED`
 * constraint (`app/src/main/kotlin/app/sterna/send/Outbox.kt`) means no attempt runs at all, so a
 * QUEUED row carries NO reason. Without the guard every waiting row would grow an empty
 * complement, or "sending couldn't be scheduled" under a message nothing has yet tried to send.
 *
 * ⛔ BLANK counts as no reason, and that is the same guard on a value the producer really writes:
 * `OutboxWorker` stores `t.message ?: t.javaClass.simpleName`, and a Throwable's message can be
 * the empty string — `MailRepository.unarmedNote` protects itself from exactly that with
 * `takeIf { it.isNotBlank() }`. On `null` alone, such a row prints the frame of
 * [outboxWaitingReasonLabel] followed by nothing.
 *
 * ⛔ [OutboxState.KEPT_AS_DRAFT] is excluded ON PURPOSE. A row that carried the reason of a real
 * send failure BEFORE it was reopened and saved as a draft (#95 × #70) keeps that text, and
 * printing it under a save that did exactly what was asked hands the reason to a state that is not
 * its own.
 *
 * ⛔ [OutboxState.INTERRUPTED] and [OutboxState.SENDING] are excluded for the same family of
 * reason: the first has a whole sentence of its own that is careful about what it claims (the
 * delivery may already have got through), the second has a delivery in flight. [OutboxState.FAILED]
 * is excluded because it already prints its reason, inside its own line.
 */
fun outboxShowsWaitingReason(state: OutboxState, lastError: String?): Boolean = when (state) {
    OutboxState.QUEUED, OutboxState.HELD -> !lastError.isNullOrBlank()
    OutboxState.SENDING, OutboxState.FAILED, OutboxState.KEPT_AS_DRAFT,
    OutboxState.INTERRUPTED, OutboxState.EDITING,
    -> false
}

/**
 * The string a WAITING row's complement is FRAMED with — "Last error: %1$s", the reason substituted
 * into it.
 *
 * ⛔ The frame exists because `lastError` is the LAST reason recorded on the row, not necessarily
 * the reason it is waiting now. Nothing clears it: a re-arm that SUCCEEDS — at app start
 * (`SternaApplication`) or when an edit is released (`MailRepository.releaseOutboxEdit`) — leaves
 * the old text in place, and an offline row can carry the reason of an online failure for days.
 * Printed bare, that text reads as the present cause; framed as history it is true in both cases,
 * and the server's own words from attempts 1 to 4 (#183) are still shown, which is what the screen
 * is for.
 *
 * ⛔ It does NOT say "Failed": the row has not failed, it is queued and waiting. That word, and the
 * error colour, belong to [OutboxState.FAILED] alone. And it promises nothing about when the
 * message leaves — see [app.sterna.core.data.mail.unarmedNote] for why nobody at this level can.
 *
 * Named here rather than in the composable for [outboxStateLabel]'s reason: which resource is
 * printed is a decision, and a decision belongs where a JVM test can execute it.
 */
@StringRes
fun outboxWaitingReasonLabel(): Int = R.string.outbox_waiting_reason

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
 * What a row's `lastError` means on screen — for the reason a FAILED row substitutes into its own
 * sentence, and for the complement a WAITING row prints under it ([outboxShowsWaitingReason]).
 *
 * A row parked because its EDIT WAS INTERRUPTED — closed over a lost body, see
 * `MailRepository.parkInterruptedOutboxEdit` — carries the SENTINEL [OutboxLogic.EDIT_INTERRUPTED],
 * not a sentence: a localized sentence written into the row would stay in the language of the day
 * it was written, on a row that may sit in the Outbox for weeks. So the sentinel is stored and the
 * words are chosen here, at display time, in the current locale.
 *
 * [OutboxLogic.NOT_ARMED] is the same kind of sentinel, written when the scheduler could not be
 * armed (`MailRepository.enqueueSend` → `armOrNote`): the row is durable and QUEUED or HELD, and
 * this says why nothing has moved. No FAILED row ever carries it, since the send worker overwrites
 * `lastError` with the fresh error at every delivery failure.
 *
 * All three [Words] are FRAGMENTS of the same shape, substituted into a frame by whoever prints
 * them: "Failed: %1$s" on a failed row, [outboxWaitingReasonLabel]'s "Last error: %1$s" on the
 * complement of a waiting one. None of them is ever printed on its own.
 *
 * ⛔ [OutboxLogic.NOT_ARMED_LEGACY_PREFIX] is the SAME EVENT under the words 1.5.2 and 1.5.3 wrote:
 * those versions stored `unarmedNote`'s whole English sentence on the row instead of the sentinel.
 * No migration rewrites those rows — a display defect does not earn an irreversible write — so a
 * row still QUEUED on a phone that ran either version is decoded HERE, by its opening, and comes
 * out as the same words as the sentinel. Unrecognised, it prints English under a translated
 * "Waiting to send" in eight locales.
 *
 * ⛔ The server's words (#183) are of ANOTHER nature and unchanged: they are what the server said,
 * they were never ours to translate, and they are printed as they came — including a reason that
 * merely starts with the same first word. A null reason keeps the fallback it always had.
 */
fun outboxFailureReason(lastError: String?): OutboxFailureReason = when {
    lastError == null -> OutboxFailureReason.Words(R.string.outbox_state_failed_unknown)
    lastError == OutboxLogic.EDIT_INTERRUPTED ->
        OutboxFailureReason.Words(R.string.outbox_error_edit_interrupted)
    lastError == OutboxLogic.NOT_ARMED ->
        OutboxFailureReason.Words(R.string.outbox_error_not_armed)
    lastError.startsWith(OutboxLogic.NOT_ARMED_LEGACY_PREFIX) ->
        OutboxFailureReason.Words(R.string.outbox_error_not_armed)
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

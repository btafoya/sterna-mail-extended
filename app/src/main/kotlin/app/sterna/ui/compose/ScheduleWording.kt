package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.data.pgp.PgpMode
import app.sterna.core.data.pgp.isSmime
import app.sterna.util.isValidEmail

/** Why a schedule is refused, or null when it may go ahead. Asked by the menu entry and again by
 *  `ComposeViewModel.scheduleSend`, the choke point. The OpenPGP refusal comes first: fixing the
 *  addresses would not lift it. An empty To is refused alone, the row sends to To; the addresses
 *  are judged like the send button's, every token of the three fields, since a bad one would only
 *  fail at the hour, in the worker, with nobody at the screen. */
internal fun scheduleRefusal(to: String, cc: String, bcc: String, pgpMode: PgpMode): Int? = when {
    !scheduleSendAllowed(pgpMode) -> if (pgpMode.isSmime) R.string.compose_smime_no_schedule else R.string.compose_pgp_no_schedule
    recipientTokens(to).isEmpty() -> R.string.compose_schedule_needs_recipient
    !listOf(to, cc, bcc).flatMap { recipientTokens(it) }.all(::isValidEmail) -> R.string.compose_schedule_invalid_address
    else -> null
}

/** The banner's sentence for [error]: a failed schedule is not a failed send, nothing went out
 *  and nothing is queued. */
internal fun composeErrorWording(error: ComposeState.Error): Int = when {
    error.whileSaving -> R.string.compose_could_not_save
    error.whileScheduling -> R.string.compose_could_not_schedule
    else -> R.string.compose_could_not_send
}

/** A preset's label: the absolute entries name the hour of their own instant, in the device's time
 *  format ([time]); [words] resolves a string, with its one argument when there is one. */
internal fun schedulePresetLabel(
    preset: SchedulePreset,
    millis: Long,
    time: (Long) -> String,
    dateTime: (Long) -> String,
    words: (res: Int, arg: String?) -> String,
): String = when (preset) {
    SchedulePreset.IN_1_HOUR -> words(R.string.schedule_in_1_hour, null)
    SchedulePreset.THIS_EVENING -> words(R.string.schedule_this_evening, time(millis))
    SchedulePreset.TOMORROW_MORNING -> words(R.string.schedule_tomorrow_morning, time(millis))
    SchedulePreset.TOMORROW_EVENING -> words(R.string.schedule_tomorrow_evening, time(millis))
    SchedulePreset.LAST_PICKED -> words(R.string.schedule_last_used, dateTime(millis))
}

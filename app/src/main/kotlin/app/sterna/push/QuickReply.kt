package app.sterna.push

import app.sterna.core.jmap.model.Email
import app.sterna.ui.compose.replyRecipient

/**
 * What is to become of the text typed into a new-mail notification's reply field. That text exists
 * in exactly one place — the broadcast intent carrying it — so there are only two honest ends,
 * [Send] and [HandBack], plus "there was no text". Anything else is a message the app ate.
 */
sealed interface QuickReplyOutcome {
    /** Nothing was typed: nothing to queue, nothing to give back, and the notification stays. */
    data object Ignore : QuickReplyOutcome

    /** Queue it in the Outbox — durable, retried, visible, editable. */
    data class Send(val to: String, val subject: String, val body: String) : QuickReplyOutcome

    /** Nobody to answer: give the text back to the user instead of dropping it. */
    data class HandBack(val subject: String, val body: String) : QuickReplyOutcome
}

sealed interface QuickReplyAct {
    data object DoNothing : QuickReplyAct

    data object Dismiss : QuickReplyAct

    data class HandBack(val subject: String, val body: String) : QuickReplyAct
}

/**
 * The decision AFTER the attempt, and the one that has to be a function: which way round these two
 * ends go is the whole bug, and no source lint can hold it — swapped by hand, the wiring still reads
 * correct while a successful send raises a false "not sent" and a failed one wipes the text.
 *
 * [queued] means the Outbox accepted the text, so it is durable from that moment and only then may
 * the notification be taken down. Not queued means the text exists nowhere else, so it is handed
 * back AND the notification stays — it is the second copy.
 */
fun quickReplyAct(outcome: QuickReplyOutcome, queued: Boolean): QuickReplyAct = when (outcome) {
    QuickReplyOutcome.Ignore -> QuickReplyAct.DoNothing
    is QuickReplyOutcome.HandBack -> QuickReplyAct.HandBack(outcome.subject, outcome.body)
    is QuickReplyOutcome.Send ->
        if (queued) QuickReplyAct.Dismiss else QuickReplyAct.HandBack(outcome.subject, outcome.body)
}

/**
 * The decision, pure: no network, no Android, no repository. [cached] is null when the local cache
 * holds no row for the message, which is a case to HANDLE — a notification does not imply a cached
 * message, and one that offers Reply does not imply a sender either.
 *
 * The recipient is chosen by [replyRecipient], the composer's own function, so the change coming to
 * it must be made once.
 */
fun quickReplyOutcome(text: String?, cached: Email?): QuickReplyOutcome {
    if (text.isNullOrBlank()) return QuickReplyOutcome.Ignore
    val subject = replySubject(cached?.subject)
    // Trimmed like `enqueueSend` trims before its require(): an address that is only whitespace is
    // no address, and finding that out inside the send is how the text got lost.
    val to = cached?.let { replyRecipient(it) }.orEmpty().trim()
    if (to.isEmpty()) return QuickReplyOutcome.HandBack(subject, text)
    return QuickReplyOutcome.Send(to, subject, text)
}

private fun replySubject(subject: String?): String =
    subject.orEmpty().let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" }

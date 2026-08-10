package app.sterna.push

import app.sterna.core.jmap.model.Email
import app.sterna.ui.compose.replyRecipient

/**
 * What is to become of the text typed into a new-mail notification's reply field.
 *
 * That text exists in exactly one place — the broadcast intent carrying it — and in no draft, no
 * autosave, no database. So there are only two honest ends for it, [Send] and [HandBack], plus
 * "there was no text" ([Ignore]). Anything else is a message the user wrote and the app ate.
 */
sealed interface QuickReplyOutcome {
    /** Nothing was typed: nothing to queue, nothing to give back, and the notification stays. */
    data object Ignore : QuickReplyOutcome

    /** Queue it in the Outbox — durable, retried, visible, editable. */
    data class Send(val to: String, val subject: String, val body: String) : QuickReplyOutcome

    /** Nobody to answer: give the text back to the user instead of dropping it. */
    data class HandBack(val subject: String, val body: String) : QuickReplyOutcome
}

/** What is left to do once the Outbox has been asked — or has not been asked at all. */
sealed interface QuickReplyAct {
    data object DoNothing : QuickReplyAct

    data object Dismiss : QuickReplyAct

    data class HandBack(val subject: String, val body: String) : QuickReplyAct
}

/**
 * The decision AFTER the attempt, and the one that has to be a function: which way round these two
 * ends go is the whole bug, and no source lint can hold it — a lint sees a dismissal line and a
 * hand-back line and is equally happy whichever branch each sits in. Swapped by hand, the wiring
 * still reads correct while a successful send raises a false "not sent" and a failed one wipes the
 * text. So it is decided here and executed by QuickReplyOutcomeTest.
 *
 * [queued] means the Outbox accepted the text: it is durable from that moment (retried with
 * backoff, and parked as FAILED with the text intact if it never goes), and only then may the
 * notification be taken down. Not queued means the text still exists nowhere else, so it is handed
 * back AND the notification stays — it is the second copy.
 */
fun quickReplyAct(outcome: QuickReplyOutcome, queued: Boolean): QuickReplyAct = when (outcome) {
    QuickReplyOutcome.Ignore -> QuickReplyAct.DoNothing
    is QuickReplyOutcome.HandBack -> QuickReplyAct.HandBack(outcome.subject, outcome.body)
    is QuickReplyOutcome.Send ->
        if (queued) QuickReplyAct.Dismiss else QuickReplyAct.HandBack(outcome.subject, outcome.body)
}

/**
 * The decision, pure: no network, no Android, no repository. [cached] is the row the local cache
 * holds for the message being answered — null when it holds none, which is a case to HANDLE and
 * not an impossibility: a notification does not imply a cached message (the cache can be trimmed,
 * or the account signed out), and a notification that offers Reply does not imply a sender either
 * (Notifications.actionsFor offers it on a mail that has a subject and no From at all).
 *
 * The recipient is chosen by [replyRecipient] — the composer's own function — rather than by a
 * second copy of the same rule: the change coming to it (Reply-To) must then be made once.
 */
fun quickReplyOutcome(text: String?, cached: Email?): QuickReplyOutcome {
    if (text.isNullOrBlank()) return QuickReplyOutcome.Ignore
    val subject = replySubject(cached?.subject)
    // Trimmed like MailRepository.enqueueSend trims before its require(): an address that is only
    // whitespace is no address, and finding that out inside the send is how the text got lost.
    val to = cached?.let { replyRecipient(it) }.orEmpty().trim()
    if (to.isEmpty()) return QuickReplyOutcome.HandBack(subject, text)
    return QuickReplyOutcome.Send(to, subject, text)
}

/** The reply's subject, exactly as this receiver has always built it. */
private fun replySubject(subject: String?): String =
    subject.orEmpty().let { if (it.startsWith("Re:", ignoreCase = true)) it else "Re: $it" }

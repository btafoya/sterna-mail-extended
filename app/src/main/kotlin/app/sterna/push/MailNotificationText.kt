package app.sterna.push

import app.sterna.core.data.settings.NotificationContent

/**
 * What a mail notification is allowed to put on screen, per the notification-content
 * privacy setting (Codeberg #25). Pure and resource-free — the strings are resolved by
 * the caller — so the "what does this reveal?" rule is unit-testable on its own.
 *
 * Every path that posts a mail notification goes through here: live arrivals and the
 * fallback fetch ([NewMailNotifier]) as well as a snooze waking up (Codeberg #84), which
 * used to post with the defaults and so showed sender and subject even when the user had
 * asked for neither.
 */
object MailNotificationText {

    /**
     * [title] is always shown; [text] is the collapsed line (null shows nothing);
     * [bigText] is the expanded (shade / lock-screen) line (null keeps the notification
     * unexpanded). It carries a body preview at one position only, the one that asks for it by
     * name; everywhere else it is the subject or nothing (Codeberg #57).
     */
    data class Reveal(val title: String, val text: String?, val bigText: String?)

    /**
     * [sender] and [subject] are the mail's own strings, already defaulted by the caller;
     * [generic] is the identity-free stand-in ("New message"); [preview] is the opening of the
     * message body when the caller happens to have it, and null the rest of the time — nothing
     * here goes and fetches one.
     *
     *  - BODY_PREVIEW: sender as title, subject collapsed, subject THEN the preview expanded;
     *  - SENDER_AND_SUBJECT: sender as title, subject collapsed and expanded;
     *  - SENDER_ONLY: sender as title, the generic line instead of the subject;
     *  - NONE: the generic line as title and nothing else — nothing identifying.
     *
     * [preview] is read at BODY_PREVIEW and nowhere else: the three positions that predate it are
     * what a reader who has never opened the setting gets, and they must not change because a
     * caller started passing a preview along.
     *
     * WHAT A PRODUCER MAY HAND OVER as [preview]: anything. Raw body text, wrapped at 72 columns,
     * with tabs and soft breaks in it, of any length, or null. The shaping happens HERE and is
     * [PREVIEW_MAX_CHARS] wide — see [shapePreview] — because the preview will come from more than
     * one protocol and this pure function is the only crossing they share. A cap left to each
     * producer is a cap one of them forgets, and an uncapped body travels whole in the
     * notification's extras, where any authorised NotificationListenerService can read it.
     */
    fun resolve(
        content: NotificationContent,
        sender: String,
        subject: String,
        generic: String,
        preview: String? = null,
    ): Reveal = Reveal(
        // A `when` rather than a test against NONE: with an `if`, a position added later takes the
        // sender silently, and the compiler has nothing to say about it. The behaviour is the same
        // one as before — NONE alone hides the sender.
        title = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            NotificationContent.SENDER_ONLY,
            -> sender
            NotificationContent.NONE -> generic
        },
        // The collapsed line NEVER carries the preview, at any position. Notifications.kt rebuilds
        // the account's group summary by copying EXTRA_TITLE/EXTRA_TEXT off the live children, so a
        // preview put here would come back on its own in the summary — lock screen included, in a
        // line the reader cannot collapse.
        text = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            -> subject
            NotificationContent.SENDER_ONLY -> generic
            NotificationContent.NONE -> null
        },
        // The expanded line is the only place the body may show. With no usable preview it is the
        // subject alone: never an empty line, never a separator with nothing after it. The newline
        // between the two is the SEPARATOR, not blank from the body — shapePreview flattens the
        // preview, never this.
        bigText = when (content) {
            NotificationContent.BODY_PREVIEW ->
                shapePreview(preview)?.let { "$subject\n$it" } ?: subject
            NotificationContent.SENDER_AND_SUBJECT -> subject
            NotificationContent.SENDER_ONLY, NotificationContent.NONE -> null
        },
    )

    /**
     * The line a send-failure banner may put under its title ([Notifications.notifySendFailed]),
     * or null when it may show none.
     *
     * That banner was the one notification in the app that never asked this question. It posted
     * the subject collapsed AND expanded, at IMPORTANCE_HIGH: reply from the shade with the
     * setting on "Sender only", let the send fail, and "Re: <the real subject>" arrived on the
     * lock screen — precisely what the user had asked never to appear there.
     *
     * Read on what is DISPLAYED, exactly as [resolve] and [Notifications.actionsFor] are: the two
     * positions that already show a subject may show this one, the two that hide it show nothing.
     * A `when` and not a test against NONE, so a position added later stops the compiler instead
     * of quietly taking the subject.
     *
     * Null is not a loss: the title ("Message not sent") already says what happened, and the
     * notification stays tappable — its `mailto:` still carries the real subject and the salvaged
     * text into the composer. A PendingIntent shows nobody its Intent; a banner does.
     *
     * [noSubject] is the stand-in for a message with no subject at all, passed in because nothing
     * here resolves resources — same reason [generic] is passed to [resolve].
     */
    fun sendFailureLine(
        content: NotificationContent,
        subject: String,
        noSubject: String,
    ): String? = when (content) {
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        -> subject.ifBlank { noSubject }
        NotificationContent.SENDER_ONLY, NotificationContent.NONE -> null
    }

    /**
     * How much of a body a notification may carry: 256 characters, which is the length of the
     * preview the JMAP server already returns, so two accounts on two protocols look alike side by
     * side rather than one of them being visibly more talkative.
     */
    const val PREVIEW_MAX_CHARS = 256

    /** Every run of space, tab, newline or carriage return, as one. */
    private val BLANK_RUN = Regex("""\s+""")

    /**
     * A raw body opening, made fit for one expanded notification line, in this order:
     *
     *  1. every run of blank becomes a single space — a plain-text part wrapped at 72 columns, or
     *     quoted-printable soft breaks, otherwise reach the shade as a ragged column, and any
     *     newline of the body would be indistinguishable from the separator above it;
     *  2. the ends are trimmed;
     *  3. what is left is cut to [PREVIEW_MAX_CHARS]. Nothing is appended — no ellipsis, no marker:
     *     the expanded line is already a fragment and says so by ending mid-sentence.
     *
     * Flatten THEN cap, never the other way round: a body that starts with forty blank lines would
     * otherwise spend its whole allowance on nothing.
     *
     * Null when there is nothing left to show — no preview at all, or a body that was only blank.
     * The caller then leaves the subject on its own rather than adding an empty line.
     */
    fun shapePreview(preview: String?): String? =
        preview?.replace(BLANK_RUN, " ")?.trim()?.take(PREVIEW_MAX_CHARS)?.takeIf { it.isNotEmpty() }
}

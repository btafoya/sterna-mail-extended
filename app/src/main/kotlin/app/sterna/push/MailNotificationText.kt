package app.sterna.push

import app.sterna.core.data.settings.NotificationContent

/** What a mail notification is allowed to put on screen, per the notification-content setting (#25).
 *  Pure and resource-free, so the rule is unit-testable. Every path that posts a mail notification
 *  goes through here, the snooze wake-up (#84) included — it used to post with the defaults and
 *  showed sender and subject to a user who asked for neither. */
object MailNotificationText {

    /** [title] is always shown; [text] is the collapsed line, [bigText] the expanded one, and null
     *  draws neither. [bigText] carries a body preview at one position only (#57). */
    data class Reveal(val title: String, val text: String?, val bigText: String?)

    /**
     * [sender] and [subject] are the mail's own strings, already defaulted by the caller; [generic]
     * is the identity-free stand-in; [preview] is the body opening when the caller has one.
     *
     *  - BODY_PREVIEW: sender, subject collapsed, subject THEN the preview expanded;
     *  - SENDER_AND_SUBJECT: sender, subject collapsed and expanded;
     *  - SENDER_ONLY: sender, the generic line instead of the subject;
     *  - NONE: the generic line and nothing else.
     *
     * [preview] is read at BODY_PREVIEW and nowhere else, and a producer may hand over anything
     * because the shaping happens HERE: a cap left to each producer is a cap one of them forgets,
     * and an uncapped body travels whole in the notification's extras.
     */
    fun resolve(
        content: NotificationContent,
        sender: String,
        subject: String,
        generic: String,
        preview: String? = null,
    ): Reveal = Reveal(
        // A `when` rather than a test against NONE: with an `if`, a position added later takes the
        // sender silently.
        title = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            NotificationContent.SENDER_ONLY,
            -> sender
            NotificationContent.NONE -> generic
        },
        // The collapsed line NEVER carries the preview: Notifications.kt rebuilds the group summary
        // by copying EXTRA_TITLE/EXTRA_TEXT off the live children, so it would come back on its own
        // — lock screen included, in a line nobody can collapse.
        text = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            -> subject
            NotificationContent.SENDER_ONLY -> generic
            NotificationContent.NONE -> null
        },
        // The expanded line is the only place the body may show; with no usable preview it is the
        // subject alone. The newline is the SEPARATOR — shapePreview flattens the preview, not this.
        bigText = when (content) {
            NotificationContent.BODY_PREVIEW ->
                shapePreview(preview)?.let { "$subject\n$it" } ?: subject
            NotificationContent.SENDER_AND_SUBJECT -> subject
            NotificationContent.SENDER_ONLY, NotificationContent.NONE -> null
        },
    )

    /**
     * The line a send-failure banner may put under its title, or null. That banner was the one
     * notification in the app that never asked this question: it posted the subject at
     * IMPORTANCE_HIGH, so a failed reply put "Re: <the real subject>" on a "Sender only" lock screen.
     * Null is not a loss — the title already says what happened, and the banner's `mailto:` still
     * carries the subject into the composer.
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
     * One row of the latest-mail widget. Two lines and not three — there is no expanded state on a
     * home screen. [namesSender] is whether this row NAMES the person who wrote, and exists because
     * the widget draws a badge whose initial is a letter of somebody's name. Decided HERE: the app
     * lock folds the effective position onto NONE inside [widgetRow], the only place that knows it.
     */
    data class WidgetRow(val primary: String, val secondary: String?, val namesSender: Boolean)

    /**
     * What one row of the latest-mail widget may say, per the same setting and the app lock — no
     * setting of its own, the constraint being built into how the widget is drawn. The result is the
     * (title, text) pair of [resolve]: a row never says more than that mail's collapsed notification,
     * nor more than the lock allows. It does NOT decide which mails get a row — that is the "Sync
     * new mail" switch, which stops FETCHING and not display.
     *
     * The first two positions are identical deliberately: BODY_PREVIEW draws no body here, the
     * preview column being filled as a matter of course on JMAP and only on request on IMAP. There
     * is no [preview] parameter for the same reason, pinned by `MailNotificationWidgetRowTest`
     * reading this signature back by reflection: no test can pass an argument that does not exist.
     *
     * [appLockEnabled] is the lock being ENABLED, NOT AppLock.locked, which dies with the process.
     * The lock folds the position onto NONE and lets the same exhaustive `when` decide.
     * [WidgetRow.namesSender] rides out on that decision.
     */
    fun widgetRow(
        content: NotificationContent,
        appLockEnabled: Boolean,
        sender: String,
        subject: String,
        generic: String,
    ): WidgetRow {
        val effective = if (appLockEnabled) NotificationContent.NONE else content
        return WidgetRow(
            primary = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                NotificationContent.SENDER_ONLY,
                -> sender
                NotificationContent.NONE -> generic
            },
            secondary = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                -> subject
                NotificationContent.SENDER_ONLY -> generic
                NotificationContent.NONE -> null
            },
            // The same exhaustive `when` a third time, and NOT `effective != NONE`: a position added
            // later would be given the sender's initial silently, and a name on a home screen cannot
            // be taken back.
            namesSender = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                NotificationContent.SENDER_ONLY,
                -> true
                NotificationContent.NONE -> false
            },
        )
    }

    /** How much of a body a notification may carry: the length of the preview the JMAP server
     *  already returns, so two accounts on two protocols look alike side by side. */
    const val PREVIEW_MAX_CHARS = 256

    /** Every run of space, tab, newline or carriage return, as one. */
    private val BLANK_RUN = Regex("""\s+""")

    /** A raw body opening made fit for one expanded notification line: every run of blank becomes one
     *  space, the ends are trimmed, and the rest is cut to [PREVIEW_MAX_CHARS] with nothing appended.
     * Flatten THEN cap: a body starting with forty blank lines would otherwise spend its whole
     *  allowance on nothing. */
    fun shapePreview(preview: String?): String? =
        preview?.replace(BLANK_RUN, " ")?.trim()?.take(PREVIEW_MAX_CHARS)?.takeIf { it.isNotEmpty() }
}

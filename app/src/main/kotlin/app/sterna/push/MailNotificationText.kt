package app.sterna.push

import app.sterna.core.data.settings.NotificationContent

sealed interface MailSubText {
    data object None : MailSubText
    data class Account(val account: String) : MailSubText
    data class Folder(val folder: String) : MailSubText
    data class AccountAndFolder(val account: String, val folder: String) : MailSubText
}

/** What a mail notification is allowed to put on screen, per the notification-content setting (#25):
 *  its texts, the header line under them, and the headline of the summary above them. Pure and
 *  resource-free, so the rule is unit-testable. Every path that posts goes through here, the snooze
 *  wake-up (#84) included: posted with the defaults, it shows what its reader asked to hide. */
object MailNotificationText {

    /** [title] is always shown; [text] is the collapsed line, [bigText] the expanded one, and null
     *  draws neither. [bigText] carries a body preview at one position only (#57). */
    data class Reveal(val title: String, val text: String?, val bigText: String?)

    /**
     * [sender] and [subject] are the mail's own strings, already defaulted by the caller; [generic]
     */
    fun resolve(
        content: NotificationContent,
        sender: String,
        subject: String,
        generic: String,
        preview: String? = null,
    ): Reveal = Reveal(
        // A `when` rather than a test against none: with an `if`, a position added later takes the
        // sender silently.
        title = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            NotificationContent.SENDER_ONLY,
            -> sender
            NotificationContent.NONE -> generic
        },
        // The collapsed line never carries the preview: Notifications.kt rebuilds the group summary
        // by copying EXTRA_TITLE/EXTRA_TEXT off the live children, so it would come back on its own
        // on the lock screen too, in a line nobody can collapse.
        text = when (content) {
            NotificationContent.BODY_PREVIEW,
            NotificationContent.SENDER_AND_SUBJECT,
            -> subject
            NotificationContent.SENDER_ONLY -> generic
            NotificationContent.NONE -> null
        },
        // The expanded line is the only place the body may show; with no usable preview it is the
        // subject alone. The newline is the separator, shapePreview flattens the preview, not this.
        bigText = when (content) {
            NotificationContent.BODY_PREVIEW ->
                shapePreview(preview)?.let { "$subject\n$it" } ?: subject
            NotificationContent.SENDER_AND_SUBJECT -> subject
            NotificationContent.SENDER_ONLY, NotificationContent.NONE -> null
        },
    )

    /** The line a send-failure banner may put under its title, or null. */
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

    /** The header line of a banner: the address it came in on (#243) whatever the count, else the
     *  account (#193); then the folder (#16); what is left to say who writes about what once texts do not. */
    fun subTextFor(
        content: NotificationContent,
        accountCount: Int,
        accountLabel: String?,
        arrivalAddress: String?,
        folderName: String?,
    ): MailSubText = when (content) {
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        NotificationContent.SENDER_ONLY,
        -> {
            val account = arrivalAddress ?: accountLabel?.takeIf { accountCount > 1 }
            when {
                account != null && folderName != null -> MailSubText.AccountAndFolder(account, folderName)
                account != null -> MailSubText.Account(account)
                folderName != null -> MailSubText.Folder(folderName)
                else -> MailSubText.None
            }
        }
        NotificationContent.NONE -> MailSubText.None
    }

    /** The account a group summary may headline: the last place a label survives its banners. */
    fun summaryAccountFor(content: NotificationContent, accountLabel: String): String? = when (content) {
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        NotificationContent.SENDER_ONLY,
        -> accountLabel
        NotificationContent.NONE -> null
    }

    /** Decided on the setting, not the banners: one posted before a switch to NONE keeps its subject. */
    fun summaryLinesFor(content: NotificationContent, lines: List<String>): List<String> = when (content) {
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        NotificationContent.SENDER_ONLY,
        -> lines
        NotificationContent.NONE -> emptyList()
    }

    /** One row of the latest-mail widget. */
    data class WidgetRow(val primary: String, val secondary: String?, val namesSender: Boolean)

    /**
     * What one row of the latest-mail widget may say, per the same setting and the app lock, no
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
            // The same exhaustive `when` a third time, and not `effective != none`: a position added
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
     * Flatten then cap: a body starting with forty blank lines would otherwise spend its whole
     *  allowance on nothing. */
    fun shapePreview(preview: String?): String? =
        preview?.replace(BLANK_RUN, " ")?.trim()?.take(PREVIEW_MAX_CHARS)?.takeIf { it.isNotEmpty() }
}

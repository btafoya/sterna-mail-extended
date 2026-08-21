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
     * One row of the latest-mail home-screen widget: [primary] is always drawn, [secondary] is the
     * line under it and null means nothing is drawn there.
     *
     * Two lines and not three: there is no expanded state on a home screen, so there is no place
     * for the one line [Reveal] keeps a body preview in.
     *
     * [namesSender] is whether this row NAMES the person who wrote — i.e. whether [primary] is the
     * sender rather than the identity-free stand-in. It exists because the widget draws a monogram
     * badge in front of the row, and A BADGE'S INITIAL IS A LETTER OF SOMEBODY'S NAME: drawing one
     * beside a row that was supposed to name nobody breaks the same promise the text keeps.
     *
     * ⛔ AND IT IS DECIDED HERE, not at the drawing site. The app lock folds the effective position
     * onto NONE inside [widgetRow], so this is the only place that knows the position actually in
     * force; anywhere else the question would be answered from the RAW setting, and a locked home
     * screen would wear the sender's initial while `SECURITY.md` promises the cell "names neither
     * sender nor subject".
     */
    data class WidgetRow(val primary: String, val secondary: String?, val namesSender: Boolean)

    /**
     * What one row of the latest-mail widget may say, per the same notification-content setting
     * ([resolve]) and the app lock. No setting of its own: the constraint is built into how the
     * widget is drawn rather than bolted on afterwards, which is what was promised when the widget
     * was asked for.
     *
     *  - BODY_PREVIEW: sender, subject under it;
     *  - SENDER_AND_SUBJECT: sender, subject under it;
     *  - SENDER_ONLY: sender, the generic line instead of the subject;
     *  - NONE: the generic line alone — nothing identifying, and no second line.
     *
     * That is exactly the (title, text) pair of [resolve]: a widget row never says more about a
     * mail than the SAME mail's notification says while collapsed. A launcher draws this outside
     * the app's window, in front of whoever is holding the phone, and it stays there — it is not a
     * banner that goes away.
     *
     * WHY THE FIRST TWO POSITIONS ARE IDENTICAL, deliberately: BODY_PREVIEW draws no body here. The
     * preview column is populated as a matter of course on JMAP, while on IMAP it is only
     * pre-fetched when this very position asked for it ([NotificationPreviews.gather] returns
     * nothing at the other three), so drawing it would make the widget visibly more talkative on
     * one protocol than on the other — on top of leaving the body of a message on a home screen,
     * which no position of this setting ever asked for. This duplication is the rule, not an
     * oversight to be tidied away. There is no [preview] parameter for the same reason: a body
     * cannot physically travel this far, whatever a later reader tries.
     *
     * ⛔ THAT ABSENCE IS PINNED BY A TEST, not by this paragraph — `MailNotificationWidgetRowTest`
     * reads this function's signature back by reflection and fails on a sixth parameter, and on a
     * default value for any of the five. Prose alone was the whole guard until a review pointed out
     * that no test can pass an argument that does not exist, so nothing went red when one was
     * added: `resolve` is one line away, it already knows how to append a shaped body, and the
     * failure would be a message's body sitting on a home screen with the suite still green.
     *
     * [appLockEnabled] is the lock being ENABLED (AccountStore.appLockEnabled()), NOT AppLock.locked
     * — that one is a process-scoped StateFlow which dies with the process, so the widget would be
     * talkative while the app happens to be running and mute afterwards, with nothing on screen to
     * explain the switch. SECURITY.md promises the lock keeps "someone who picks up your unlocked
     * phone" from reading your mail, and a home screen is read by exactly that person: with the
     * lock on, the mail is not readable without authenticating, whatever the live process is doing.
     *
     * The lock folds the effective position onto NONE and then lets the same exhaustive `when`
     * decide, rather than short-circuiting the function: a position added later must stop the
     * compiler instead of quietly taking the sender — the invariant [resolve] and
     * [Notifications.actionsFor] already carry.
     *
     * ⭐ [WidgetRow.namesSender] rides out on the same decision, for the monogram badge the widget
     * draws in front of a row: an initial is a letter of the sender's name, so it is allowed
     * exactly where the sender's name itself is. Asking that question anywhere else would ask it
     * of the raw setting, and the lock would stop governing the badge.
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
            // The same exhaustive `when` a third time, and NOT `effective != NONE`: with an `if`,
            // a position added later would be given the sender's initial silently, which is this
            // rule's one irreversible mistake — a name on a home screen cannot be taken back.
            namesSender = when (effective) {
                NotificationContent.BODY_PREVIEW,
                NotificationContent.SENDER_AND_SUBJECT,
                NotificationContent.SENDER_ONLY,
                -> true
                NotificationContent.NONE -> false
            },
        )
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

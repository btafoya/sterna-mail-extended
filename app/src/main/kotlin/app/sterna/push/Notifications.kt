package app.sterna.push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import app.sterna.MainActivity
import app.sterna.R
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.jmap.model.Email

/** The buttons a new-mail notification can carry, in the order they are shown. */
enum class MailNotificationAction { REPLY, MARK_READ, DELETE }

/** Notification channels + helpers. No telemetry, no third-party push. */
object Notifications {
    const val CHANNEL_MAIL = "new_mail"
    const val CHANNEL_SERVICE = "push_service"
    const val SERVICE_ID = 1

    /** New-mail notifications are grouped per account under this key prefix. */
    private const val GROUP_PREFIX = "mail:"

    /**
     * Below this many live per-message notifications an account gets NO group summary: Android
     * shows a lone child by itself, so a summary over it would be an empty duplicate. Both the
     * summary's own posting rule ([updateGroupSummary]) and the "who is allowed to make noise"
     * rule ([summaryShownFor]) read this one number, so the two can never disagree.
     */
    private const val SUMMARY_MIN_CHILDREN = 2

    /**
     * Whether the account's group summary will be on screen beside a per-message notification
     * posted now, given how many per-message notifications the account will have once the batch
     * is posted ([liveChildIdsAfter] counts them).
     *
     * This is the whole of Codeberg #56. A group where both the summary and its children alert
     * announces the same mail twice: two heads-up banners, two sounds, a status icon that
     * appears and goes. Only one of the two may speak — the summary when there is one. But
     * silencing the children unconditionally would make a SINGLE arrival mute, because a single
     * message gets no summary at all (see above): there would be nothing left to announce it.
     * So the child speaks exactly when it stands alone.
     */
    fun summaryShownFor(liveChildren: Int): Boolean = liveChildren >= SUMMARY_MIN_CHILDREN

    /** Reply · Mark as read · Delete, in the order they are shown. */
    private val ALL_ACTIONS = listOf(
        MailNotificationAction.REPLY,
        MailNotificationAction.MARK_READ,
        MailNotificationAction.DELETE,
    )

    /**
     * The buttons a new-mail notification carries (Codeberg #57). Pure and resource-free — the
     * labels and the intents are built by the caller — so the "may the user act on this?" rule
     * is unit-testable on its own, next to the "what does this reveal?" rule of
     * [MailNotificationText].
     *
     * The question is not which setting is on, it is whether the notification on screen NAMES
     * the message: a Delete over something the user cannot identify loses mail, and Reply and
     * Mark as read are acted on just as blindly. So the two informative positions are read
     * together with what the mail actually carries, exactly as [MailNotificationText.resolve]
     * reads them:
     *
     *  - SENDER_AND_SUBJECT shows both, so either one naming the message is enough;
     *  - BODY_PREVIEW shows both as well and is read identically. Its extra text counts for
     *    nothing here, deliberately: an opening line says what was written, never by whom or about
     *    what ("see you at 6" names nothing), so a preview must not turn a mail with no From and no
     *    subject into one the reader is invited to Delete;
     *  - SENDER_ONLY shows only the sender — a subject the notification never displays cannot
     *    help, and a message with no From header (daemon mail, a malformed header) then reads
     *    "New message / New message" and gets no buttons;
     *  - NONE shows the generic line alone: never any button, whatever the mail carries.
     *
     * [hasSender] and [hasSubject] mean "the mail has its own, non-blank one" — the caller's
     * stand-ins ("New message", "(no subject)") do not count.
     */
    fun actionsFor(
        content: NotificationContent,
        hasSender: Boolean,
        hasSubject: Boolean,
    ): List<MailNotificationAction> = when (content) {
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        -> if (hasSender || hasSubject) ALL_ACTIONS else emptyList()
        NotificationContent.SENDER_ONLY -> if (hasSender) ALL_ACTIONS else emptyList()
        NotificationContent.NONE -> emptyList()
    }

    /**
     * The per-message notification ids the account is left with once a notification pass is
     * done: what was live ([active]), minus the messages whose notification the pass clears
     * ([cleared], read elsewhere), plus the ones it posts ([added]).
     *
     * Counted rather than re-read from the system afterwards, because the decision has to be
     * made BEFORE the first child is posted — that is when the choice of who announces the
     * batch is fixed.
     */
    fun liveChildIdsAfter(
        active: Set<Int>,
        accountId: String,
        cleared: Collection<String>,
        added: Collection<String>,
    ): Set<Int> {
        val live = active.toMutableSet()
        cleared.forEach { live -= childId(accountId, it); live -= legacyChildId(it) }
        added.forEach { live += childId(accountId, it) }
        return live
    }

    /**
     * Which of [departedIds] a notification pass may unpost: the messages the SERVER said left
     * the folder that still have a live per-message notification, minus anything the folder still
     * holds ([stillHeld]).
     *
     * CANDIDATES, not a verdict. A delta cannot tell a departure from a keyword change, so what
     * this returns is put to the server before any banner comes down — see
     * `MailRepository.confirmDepartures`. The narrowing happens HERE first so that the ordinary
     * pass, which has no banner among the departures, asks the server nothing at all.
     *
     * Codeberg #134: "when deleting an email with a different client, e.g. Thunderbird-Desktop,
     * the notifications for sterna-mail are still present." Nothing anywhere cancelled a banner
     * from a server-side disappearance — the one cancel that came from the server needed the
     * message to still be cached, with `isSeen` set, and a deleted message is not cached at all.
     *
     * ⛔ [departedIds] may only ever be fed from a signal that is AUTHORITATIVE about the message:
     * a delta eviction (reorders subtracted, the recently-mutated spare honoured) or an existence
     * sweep's `notFound`. A CACHE eviction is not one — a full-query reconcile drops what fell
     * outside the page, the retention window drops what the user chose not to keep offline, and
     * both of those messages are alive on the server. The two errors are not symmetrical: one
     * banner too many is a banner the user dismisses, one banner too few is unread mail that is
     * never announced at all.
     *
     * [stillHeld] is the folder's content AFTER the sync that named the departures: an id that is
     * in both is not gone, whatever the delta said, and it keeps its banner. Same rule, and same
     * direction of doubt, as the sync's own "an unanswered question is not a no".
     *
     * Liveness is tested with [isChildActive], so BOTH id forms count (a banner posted before #92
     * carries the bare id) and the account qualifies the id: two accounts on one Stalwart are
     * routinely handed the same message id, and an unqualified match would cancel the other
     * account's banner.
     */
    fun departuresToCancel(
        active: Set<Int>,
        accountId: String,
        departedIds: Collection<String>,
        stillHeld: Collection<String>,
    ): List<String> {
        if (departedIds.isEmpty()) return emptyList()
        val held = stillHeld.toSet()
        return departedIds.distinct().filter { it !in held && isChildActive(active, accountId, it) }
    }

    /**
     * The notification id of one message. JMAP ids are assigned PER ACCOUNT, so two accounts on
     * the same server routinely hand us the same id: keying on the id alone made account B's
     * notification replace account A's and — because the action intents are built with
     * FLAG_UPDATE_CURRENT under the same request codes — rewrote A's Mark read / Delete / Reply
     * buttons to carry B's message. A notification reading as A's then acted on B's mail (#92).
     */
    fun childId(accountId: String, emailId: String): Int = "$accountId $emailId".hashCode()

    /**
     * The id the same message got before [childId] existed. Notifications posted by an older
     * build are still on screen after an update; without this they could no longer be matched
     * or cancelled and would sit there forever.
     */
    private fun legacyChildId(emailId: String): Int = emailId.hashCode()

    /** Whether [emailId] of [accountId] has a live notification among [active] ids, either form. */
    fun isChildActive(active: Set<Int>, accountId: String, emailId: String): Boolean =
        childId(accountId, emailId) in active || legacyChildId(emailId) in active

    /** Request code for an action button's broadcast, unique per account + message + action. */
    private fun actionRequestCode(accountId: String, emailId: String, action: String): Int =
        "$accountId $emailId $action".hashCode()

    /**
     * The new-mail channel, and ONLY it — every function that posts a banner calls this first,
     * because nothing else guarantees the channel exists: on an install whose push was never
     * armed in direct mode [PushService] never starts, and Android drops each banner in silence.
     *
     * ⛔ It must not create [CHANNEL_SERVICE] as well. That channel says "Keeps a live connection
     * to your mail server for instant mail", and on exactly the installs this repairs there is no
     * such connection — showing it in the system settings would be a lie the user can read.
     *
     * Creating a channel that already exists is a no-op for everything the user has retuned:
     * `createNotificationChannel` lowers an existing importance only when the user has not
     * altered the channel, and ignores every other field. Silence, a lowered importance and a
     * channel switched off all survive being re-ensured on every banner.
     */
    fun ensureMailChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_MAIL,
                context.getString(R.string.notif_channel_mail),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /**
     * Both channels — the mail one and the foreground-service one. ⛔ Called by [PushService] and
     * by nothing else: the service channel belongs to a service that is actually running.
     */
    fun ensureChannels(context: Context) {
        ensureMailChannel(context)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notif_channel_sync),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.notif_channel_sync_desc) },
        )
    }

    /**
     * A one-off notification that a scheduled/queued message couldn't be sent after retries, so a
     * permanent failure isn't dropped silently. Tapping it opens the app.
     *
     * [body] is text the app is holding on the user's behalf and cannot deliver on its own — today,
     * a quick reply typed into a notification whose message has nobody to answer. Tapping then
     * opens the COMPOSER carrying that text, through the very `mailto:` route an external link
     * already uses (#15), so giving the text back costs no new screen and no new string.
     * Deliberately no `to=` beside the body: the recipient is precisely what is missing here, and
     * MailTo.parse hands back an address with an empty body when both are given.
     *
     * [content] is the notification-content setting, and is deliberately WITHOUT a default — for
     * the same reason [notifyNewMail]'s is. This banner used to name the message unconditionally,
     * so a user on "Sender only" whose quick reply failed got "Re: <the real subject>" on the lock
     * screen at IMPORTANCE_HIGH. What it may show is decided by
     * [MailNotificationText.sendFailureLine]; when that is null the title stands alone.
     *
     * ⚠ [subject] itself keeps travelling in the notification's KEY and in the `mailto:` — those
     * are identity and salvage, not display: a PendingIntent does not show its Intent, and cutting
     * them would lose the subject the user gets back in the composer. Closing a leak by opening a
     * loss of text is not a fix.
     */
    fun notifySendFailed(context: Context, subject: String, content: NotificationContent, body: String = "") {
        ensureMailChannel(context)
        // Keyed on the body as well, or a second salvaged text would land on the first one's id
        // and replace it — losing exactly what this is here to save. Unchanged, to the character,
        // when there is no body: the send-failure notifications keep their old identity.
        val key = if (body.isBlank()) "sendfail:$subject" else "sendfail:$subject:$body"
        val target = if (body.isBlank()) {
            Intent(context, MainActivity::class.java)
        } else {
            Intent(Intent.ACTION_VIEW, draftUri(subject, body), context, MainActivity::class.java)
        }
        val open = PendingIntent.getActivity(
            context,
            if (body.isBlank()) "sendfail".hashCode() else key.hashCode(),
            target.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val noSubject = context.getString(R.string.message_no_subject)
        val line = MailNotificationText.sendFailureLine(content, subject, noSubject)
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_send_failed_title))
            // Nothing at all when the setting hides subjects: no collapsed line and no expanded
            // one, rather than an empty one. "Message not sent" is the whole message then, and it
            // needs no new string to be true.
            .apply {
                if (line != null) {
                    setContentText(line)
                    setStyle(NotificationCompat.BigTextStyle().bigText(line))
                }
            }
            // Auto-cancel only when there is nothing to lose. The mailto: above is consumed by the
            // NavHost's LaunchedEffect, which is composed only under a signed-in state, so a
            // hand-back raised for a signed-out account (the very case that produced it) opens
            // nothing on tap — and an auto-cancelled notification would then be the second and
            // final loss of the text. Persisting, it can still be tapped once a session is open.
            .setAutoCancel(body.isBlank())
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(key.hashCode(), notification)
    }

    /** A recipient-less `mailto:`, carrying a subject and a body for the composer to prefill. */
    private fun draftUri(subject: String, body: String): Uri =
        Uri.parse("mailto:?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")

    /** The ongoing notification required for the foreground service. */
    fun serviceNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(context.getString(R.string.notif_watching))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * [folderName] marks non-inbox mail (multi-folder watch, issue #16); null for the inbox.
     * [mailboxId] is the same folder's id, carried in the tap intent so opening the notification
     * puts the list on the folder the message actually lives in (issue #91) — null when the
     * caller has none to give. Unlike [folderName] it is NOT null for the inbox: the list has to
     * be able to switch back TO the inbox from another folder just as much as away from it.
     * [silent] (quiet hours) and [content] (how much of the mail shows on the lock screen,
     * Codeberg #25, and with it which action buttons it may carry — see [actionsFor]) are
     * deliberately WITHOUT defaults: they are user settings, and the defaults let the snooze
     * wake-up post loudly with sender and subject against the user's explicit choice
     * (Codeberg #84). Read them via [NewMailNotifier.options].
     * [summarised] says whether a group summary will announce this batch, in which case this
     * notification stays quiet underneath it — with its actions and its content intact, only
     * its sound and its banner suppressed (Codeberg #56). Pass false when the notification is
     * on its own, or it makes no noise at all: see [summaryShownFor].
     */
    fun notifyNewMail(
        context: Context,
        email: Email,
        accountId: String,
        silent: Boolean,
        folderName: String?,
        mailboxId: String?,
        content: NotificationContent,
        summarised: Boolean,
    ) {
        ensureMailChannel(context)
        val generic = context.getString(R.string.notif_new_message)
        // Kept apart from the stand-ins below: whether the mail names ITSELF is what decides the
        // action buttons, and "New message" / "(no subject)" name nothing (Codeberg #57).
        val fromName = email.from.firstOrNull()?.display()
        val realSubject = email.subject?.takeIf { it.isNotBlank() }
        val sender = fromName ?: generic
        val subject = realSubject ?: context.getString(R.string.message_no_subject)
        // What the notification reveals, per the privacy setting — the rule itself lives in
        // [MailNotificationText] so it is testable, and so no caller can post with the
        // defaults and leak what the user asked to hide (Codeberg #84). The expanded state shows
        // the full subject, and the opening of the body at the ONE position that asks for it by
        // name — never at the three that predate it, which is what was published to the reporter
        // of #57 and what a reader who never opened the setting still gets.
        //
        // [Email.preview] is where that opening comes from, whoever filled it: the JMAP server
        // hands one back with the envelope, and on IMAP the notification pre-pass reads it and
        // puts it on the message ([NotificationPreviews]). Null is the ordinary answer — no
        // preview, offline, a message that is one PDF — and the expanded line is the subject
        // alone. Nothing is fetched from here: this function posts, it does not go to the network.
        val (title, text, bigText) = MailNotificationText.resolve(content, sender, subject, generic, email.preview)
        // Which buttons it may carry, read from the same facts: a notification that names
        // nothing offers nothing to act on — at NONE always, and in the two informative
        // positions when the mail itself has nothing to show there (Codeberg #57).
        val actions = actionsFor(
            content,
            hasSender = !fromName.isNullOrBlank(),
            hasSubject = realSubject != null,
        )
        val notifId = childId(accountId, email.id)
        // Carry the message identity so a tap opens THAT email, not just the inbox — even when
        // the app is already running (singleTask → onNewIntent routes it). Codeberg #17 follow-up.
        // Its account (#31) and its folder (#91) travel along so the list underneath ends up
        // where the message is, and Back lands in a list that holds it.
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_EMAIL_ID, email.id)
            .putExtra(MainActivity.EXTRA_OPEN_ACCOUNT_ID, accountId)
            .apply { if (mailboxId != null) putExtra(MainActivity.EXTRA_OPEN_MAILBOX_ID, mailboxId) }
        val pending = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(title)
            .apply {
                if (text != null) setContentText(text)
                if (bigText != null) setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            }
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setGroup(GROUP_PREFIX + accountId)
            .setGroupAlertBehavior(
                if (summarised) {
                    NotificationCompat.GROUP_ALERT_SUMMARY
                } else {
                    NotificationCompat.GROUP_ALERT_ALL
                },
            )
            .setSilent(silent)
            .apply { if (folderName != null) setSubText(folderName) }
            .apply {
                actions.forEach { action ->
                    addAction(
                        when (action) {
                            MailNotificationAction.REPLY ->
                                replyAction(context, email.id, accountId, notifId)
                            MailNotificationAction.MARK_READ -> simpleAction(
                                context,
                                context.getString(R.string.notif_mark_read),
                                NotificationActionReceiver.ACTION_MARK_READ,
                                email.id,
                                accountId,
                                notifId,
                            )
                            MailNotificationAction.DELETE -> simpleAction(
                                context,
                                context.getString(R.string.notif_delete),
                                NotificationActionReceiver.ACTION_DELETE,
                                email.id,
                                accountId,
                                notifId,
                            )
                        },
                    )
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(notifId, notification)
    }

    /**
     * Rebuild the account's group summary from the currently ACTIVE child
     * notifications, so successive per-folder diff passes accumulate instead of the
     * last one overwriting the account's count and lines.
     */
    fun updateGroupSummary(context: Context, accountId: String, accountLabel: String, silent: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = GROUP_PREFIX + accountId
        val summaryId = ("summary:" + accountId).hashCode()
        val children = manager.activeNotifications.filter { sbn ->
            sbn.id != summaryId && sbn.notification.group == group &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
        }.sortedByDescending { it.postTime }
        if (!summaryShownFor(children.size)) {
            // The system shows a lone child by itself; drop any stale summary.
            manager.cancel(summaryId)
            return
        }
        val lines = children.map { sbn ->
            val sender = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: context.getString(R.string.notif_new_message)
            val subject = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: context.getString(R.string.message_no_subject)
            context.getString(R.string.notif_group_line, sender, subject)
        }
        notifyGroupSummary(context, accountId, accountLabel, children.size, lines, silent)
    }

    /**
     * The active child (per-message) notification ids for [accountId]'s group, excluding the
     * group summary. Lets a read message be matched to a live notification to dismiss (#19).
     */
    fun activeChildIds(context: Context, accountId: String): Set<Int> {
        val manager = context.getSystemService(NotificationManager::class.java)
        val group = GROUP_PREFIX + accountId
        val summaryId = ("summary:" + accountId).hashCode()
        return manager.activeNotifications
            .filter { sbn ->
                sbn.id != summaryId && sbn.notification.group == group &&
                    (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .map { it.id }
            .toSet()
    }

    /**
     * Among [active] — one `(notification id, group key)` pair per live notification — the ids
     * that belong to [accountId]'s group: its per-message children AND its group summary.
     *
     * Pure, so the "whose banner is this?" question is executed by a test rather than read.
     *
     * ⭐ It matches on the GROUP and not on ids, deliberately: both the child ([notifyNewMail])
     * and the summary ([notifyGroupSummary]) are posted with `.setGroup(GROUP_PREFIX + accountId)`
     * and are the only two notifications in the app that carry a group at all — `notifySendFailed`
     * carries none. So one filter takes the whole group, it also catches a child posted under an
     * id shape a previous version used, and it spares this file a fourth copy of the summary id.
     *
     * ⛔ And it does NOT skip the summary the way [activeChildIds] does: a sign-out that left the
     * summary standing would leave "3 new messages — Alex" on the shade for an account that no
     * longer exists, over the lines of messages whose own banners just went.
     */
    fun accountGroupIds(active: List<Pair<Int, String?>>, accountId: String): List<Int> {
        val group = GROUP_PREFIX + accountId
        return active.filter { (_, notificationGroup) -> notificationGroup == group }.map { it.first }
    }

    /**
     * Take down every banner of [accountId] — children and group summary alike.
     *
     * For a sign-out and for nothing else: this account is about to have no credentials, so no
     * background pass will ever look at it again (`AccountStore.allCredentials()` drops it), and
     * `NewMailNotifier.clear` only forgets its baselines. Whatever is left on the shade at that
     * moment would stay there for ever.
     */
    fun cancelAccount(context: Context, accountId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.map { it.id to it.notification.group }
        accountGroupIds(active, accountId).forEach { manager.cancel(it) }
    }

    /** Cancel the per-message notification for [emailId] without touching the group summary. */
    fun cancelChild(context: Context, accountId: String, emailId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(childId(accountId, emailId))
        // Also the pre-update form, otherwise a notification posted by the previous build
        // survives every dismissal attempt. Cancelling an id with no live notification is a no-op.
        manager.cancel(legacyChildId(emailId))
    }

    /**
     * Dismiss the new-mail notifications for [emailIds] that just became read — in the app or
     * on another device — and refresh the account's group summary. A no-op for ids with no
     * live notification, so reading already-seen mail costs nothing (Codeberg #19).
     */
    fun dismiss(context: Context, accountId: String, accountLabel: String, emailIds: Collection<String>) {
        val active = activeChildIds(context, accountId)
        val hit = emailIds.filter { isChildActive(active, accountId, it) }
        if (hit.isEmpty()) return
        hit.forEach { cancelChild(context, accountId, it) }
        updateGroupSummary(context, accountId, accountLabel, silent = true)
    }

    /**
     * Posts the per-account group summary that bundles the account's individual
     * new-mail notifications (Android collapses them under one expandable entry).
     * [lines] are "sender — subject" strings for the latest batch.
     */
    fun notifyGroupSummary(
        context: Context,
        accountId: String,
        accountLabel: String,
        count: Int,
        lines: List<String>,
        silent: Boolean = false,
    ) {
        ensureMailChannel(context)
        val title = context.getString(R.string.notif_group_count, count, accountLabel)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.setSummaryText("+${lines.size - 6}")
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            ("summary:" + accountId).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_MAIL)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle(accountLabel)
            .setContentText(title)
            .setStyle(style)
            .setGroup(GROUP_PREFIX + accountId)
            .setGroupSummary(true)
            // The summary is the one that speaks for the group (Codeberg #56): its children are
            // posted quiet under it. Stated here too so the pair reads as one decision.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(("summary:" + accountId).hashCode(), notification)
    }

    private fun actionIntent(context: Context, action: String, emailId: String, accountId: String, notifId: Int) =
        Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(NotificationActionReceiver.EXTRA_EMAIL_ID, emailId)
            putExtra(NotificationActionReceiver.EXTRA_ACCOUNT_ID, accountId)
            putExtra(NotificationActionReceiver.EXTRA_NOTIF_ID, notifId)
        }

    private fun simpleAction(
        context: Context,
        label: String,
        action: String,
        emailId: String,
        accountId: String,
        notifId: Int,
    ): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            actionRequestCode(accountId, emailId, action),
            actionIntent(context, action, emailId, accountId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, pending).build()
    }

    private fun replyAction(context: Context, emailId: String, accountId: String, notifId: Int): NotificationCompat.Action {
        val pending = PendingIntent.getBroadcast(
            context,
            actionRequestCode(accountId, emailId, NotificationActionReceiver.ACTION_REPLY),
            actionIntent(context, NotificationActionReceiver.ACTION_REPLY, emailId, accountId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.notif_reply_hint))
            .build()
        return NotificationCompat.Action.Builder(0, context.getString(R.string.notif_reply), pending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
    }
}

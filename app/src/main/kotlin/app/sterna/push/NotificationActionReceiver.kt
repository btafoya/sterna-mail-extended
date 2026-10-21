package app.sterna.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.sterna.SternaApplication
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStore
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OriginalMark
import app.sterna.core.data.mail.ReplyMark
import app.sterna.core.data.settings.NotificationContent
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Handles new-mail notification quick actions (reply / mark read / delete) in the background. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val emailId = intent.getStringExtra(EXTRA_EMAIL_ID) ?: return
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        val replyText = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()

        val appContext = context.applicationContext
        val container = (appContext as SternaApplication).container
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val credentials = container.accountStore.credentials(accountId)
                // Reply leaves the common path before the dismissal below: falling through to it,
                // a reply that sent nothing would still clear the notification and the text.
                if (action == ACTION_REPLY) {
                    reply(
                        appContext, container.mailRepository, container.settingsRepository,
                        container.accountStore, credentials, accountId, emailId, notifId, replyText,
                    )
                    return@launch
                }
                // Delete can find there is nothing to do, and the banner must then survive it.
                var mayDismiss = true
                if (credentials != null) {
                    when (action) {
                        ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)
                        ACTION_DELETE -> mayDismiss = deleteFromBanner(
                            container.mailRepository, credentials, accountId, emailId,
                        )
                    }
                    if (mayDismiss) dismiss(appContext, accountId, credentials, emailId, notifId)
                }
            } catch (e: Throwable) {
                // The banner stays for a retry.
                Log.w(TAG, "Notification action $action failed: ${notificationActionFailureLabel(e)}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The Delete button of a banner, and the question it never asked: would this delete MOVE
     */
    private suspend fun deleteFromBanner(
        repo: MailRepository,
        credentials: AccountCredentials,
        accountId: String,
        emailId: String,
    ): Boolean {
        val cached = runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()
        val role = runCatching { repo.mailboxRole(accountId, cached?.mailboxId) }.getOrNull()
        val hasTrash = runCatching { repo.hasCachedTrash(accountId) }.getOrDefault(true)
        return deleteHoldingItsRow(
            act = bannerDeleteAct(role, hasTrash),
            begin = { repo.beginRemoval(accountId, emailId) },
            delete = { repo.delete(credentials, emailId) },
            end = { flight, restore -> repo.endRemoval(flight, restore) },
        )
    }

    /** The Reply path, whose only asset is the text the user just typed. Cache first, exactly like
     *  the composer, so this never waits on a socket to learn where the reply is going. Three ends,
     *  none of them silent, queued, handed back, or nothing was typed. */
    private suspend fun reply(
        context: Context,
        repo: MailRepository,
        settings: SettingsRepository,
        store: AccountStore,
        credentials: AccountCredentials?,
        accountId: String,
        emailId: String,
        notifId: Int,
        text: String?,
    ) {
        val cached = credentials?.let {
            runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()
        }
        val outcome = quickReplyOutcome(text, cached)
        val queued = outcome is QuickReplyOutcome.Send && credentials != null &&
            runCatching { send(repo, store, credentials, accountId, emailId, cached, outcome) }.isSuccess
        when (val act = quickReplyAct(outcome, queued)) {
            QuickReplyAct.DoNothing -> Unit
            QuickReplyAct.Dismiss -> dismiss(context, accountId, credentials, emailId, notifId)
            // Read here and not before: the setting is only needed on the end that posts a banner.
            // Guarded: a corrupt settings file reads as the defaults, notification at its quietest;
            // another read failure may still throw, and onReceive's catch would drop the typed text.
            is QuickReplyAct.HandBack -> {
                val content = runCatching { settings.notificationContent.first() }
                    .getOrDefault(NotificationContent.NONE)
                Notifications.notifySendFailed(context, act.subject, content, act.body)
            }
        }
    }

    /**
     * Hand the reply to the Outbox, which is what makes the text durable: sent when there is a
     * network, retried with backoff, and parked as FAILED with its text intact if it never goes.
     */
    private suspend fun send(
        repo: MailRepository,
        store: AccountStore,
        credentials: AccountCredentials,
        accountId: String,
        emailId: String,
        cached: Email?,
        outcome: QuickReplyOutcome.Send,
    ) {
        val identities = store.identities(accountId)
        val from = quickReplyFrom(accountId, identities, store.defaultIdentityId(accountId), cached)
        val fetched = fetchWithinBudget(repo, credentials, emailId)
        repo.sending.enqueueSend(
            credentials = credentials,
            to = listOf(outcome.to),
            subject = outcome.subject,
            body = outcome.body,
            fromName = from?.name,
            fromEmail = from?.email,
            inReplyTo = fetched?.messageId.orEmpty(),
            references = fetched?.references.orEmpty() + fetched?.messageId.orEmpty(),
            // A quick reply is typed in the shade and replaces no saved draft.
            draftUidValidity = null,
            originalMark = OriginalMark(accountId, emailId, ReplyMark.ANSWERED),
        )
    }

    /**
     * The original's threading headers, or null, abandoned after [FETCH_BUDGET_MS].
     */
    private suspend fun fetchWithinBudget(
        repo: MailRepository,
        credentials: AccountCredentials,
        emailId: String,
    ): Email? {
        val fetching = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            .async { repo.fetchEmail(credentials, emailId) }
        val fetched = runCatching { withTimeoutOrNull(FETCH_BUDGET_MS) { fetching.await() } }.getOrNull()
        fetching.cancel()
        return fetched
    }

    private suspend fun dismiss(
        context: Context,
        accountId: String,
        credentials: AccountCredentials?,
        emailId: String,
        notifId: Int,
    ) {
        // The shade is read before the cancel below: `cancel` returns once the system has queued
        // the work and `getActiveNotifications` does not drain that queue, so a later read still
        // shows the banner just taken down and the summary would put it back (#134).
        val active = Notifications.activeChildIds(context, accountId)
        NotificationManagerCompat.from(context).cancel(notifId)
        if (credentials != null) {
            // Re-posting the summary owes the setting the same reading, and the same guard, as the
            // banner it stands over (#84); the account is named as its banners name it (#193).
            val container = (context.applicationContext as SternaApplication).container
            val content = readContentWithin(container.settingsRepository.notificationContent, CONTENT_BUDGET_MS)
            Notifications.updateGroupSummary(
                context, accountId, container.accountStore.account(accountId)?.label(), content,
                silent = true,
                expectedChildIds = Notifications.liveChildIdsAfter(active, accountId, listOf(emailId), emptyList()),
                cancelledChildIds = Notifications.childIdsOf(accountId, listOf(emailId)),
            )
        }
    }

    companion object {
        private const val TAG = "NotificationAction"
        const val ACTION_MARK_READ = "app.sterna.action.MARK_READ"
        const val ACTION_DELETE = "app.sterna.action.DELETE"
        const val ACTION_REPLY = "app.sterna.action.REPLY"
        const val EXTRA_EMAIL_ID = "email_id"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "key_reply"

        /** The leash on the threading fetch, well under goAsync's budget, deliberately. */
        private const val FETCH_BUDGET_MS = 5_000L
        internal const val CONTENT_BUDGET_MS = 2_000L
    }
}

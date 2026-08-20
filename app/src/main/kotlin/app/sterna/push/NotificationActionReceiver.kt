package app.sterna.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.sterna.SternaApplication
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
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

/**
 * Handles new-mail notification quick actions (reply / mark read / delete) in the background.
 * Reply uses inline RemoteInput.
 *
 * Mark as read dismisses the notification unconditionally, as it always has: what it changes is
 * the server's copy of the message, and the same action is one tap away in the list if it failed.
 *
 * Delete takes the banner down only when it MOVED something ([bannerDeleteAct]). Asked to delete a
 * message the local caches say is already in Trash, or one on an account whose synced folders name
 * no Trash at all, it does nothing whatever and leaves the banner standing: a banner that does not
 * fall is the statement that nothing was done, and the same gesture waits one tap away in the list.
 * It never destroys either way — what it performs is a move to Trash, and permanent deletion lives
 * in the list behind its cancelable Undo window (Codeberg #23).
 *
 * ⚠ That refusal owns the BUTTON, not the banner's lifetime. Nothing on this path takes the banner
 * down — but a background pass will, on its own: once a sync reports the message gone from the
 * watched folder and the server confirms where it went, NewMailNotifier takes the banner down
 * (Codeberg #134). For a message the user had already deleted in the app, that lands within a
 * minute of this refusal, and on a woken process within seconds of it — near enough to read as if
 * the button had done it. Measured on the bench, 2026-08-18. That pass is not this file's to fix.
 *
 * ⚠ One end contradicts that and is left as it was: a signed-out account has no credentials, so
 * NONE of the three actions runs and the banner is taken down all the same. Same function, other
 * cause, and it is written down where it belongs rather than half-fixed here.
 *
 * Reply is like neither. The typed text lives in the broadcast intent and nowhere else, so on that
 * path the notification comes down only once the Outbox has accepted the text, and every other end
 * of it hands the text back to the user.
 */
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
                // Reply leaves the common path BEFORE the dismissal below. It used to run inside
                // the `when` and fall through to it, so a reply that sent nothing — no sender to
                // answer, an exception, a killed receiver — still cleared the notification and the
                // text with it.
                if (action == ACTION_REPLY) {
                    reply(
                        appContext, container.mailRepository, container.settingsRepository,
                        credentials, accountId, emailId, notifId, replyText,
                    )
                    return@launch
                }
                // Delete is the one action here that can find there is nothing to do, and then the
                // banner must survive it: see [deleteFromBanner].
                var mayDismiss = true
                if (credentials != null) {
                    when (action) {
                        ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)
                        ACTION_DELETE -> mayDismiss = deleteFromBanner(
                            container.mailRepository, credentials, accountId, emailId,
                        )
                    }
                }
                if (mayDismiss) dismiss(appContext, accountId, credentials, notifId)
            } catch (_: Throwable) {
                // Best-effort; leave the notification so the user can retry from the app.
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The Delete button of a banner, and the question it never asked: would this delete MOVE
     * anything? Returns whether the banner may come down — false means nothing happened at all.
     *
     * The list asks the same thing through `MailRepository.deleteWouldDestroy`, which resolves the
     * Trash over the SOCKET. Forbidden here: this receiver has ~10 s of goAsync for everything it
     * does, and against a reachable-but-silent server a role lookup spends all of it. So the three
     * reads below are local (cache, folder cache, folder cache) and the decision is
     * [bannerDeleteAct], where the reasoning and its one accepted hole are written out.
     *
     * All three are GUARDED, and each failure falls to the ORDINARY path: a database that will not
     * answer is not evidence that a delete would destroy something, and turning a read error into
     * a silently refused Delete would be a second defect on top of the one this closes.
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
        when (bannerDeleteAct(role, hasTrash)) {
            BannerDeleteAct.DoNothing -> return false
            BannerDeleteAct.MoveToTrash -> repo.delete(credentials, emailId)
        }
        return true
    }

    /**
     * The Reply path, whose only asset is the text the user just typed.
     *
     * Cache FIRST, exactly like the composer (ComposeViewModel): the recipient and the subject come
     * from the cached row, so this never waits on a socket to learn where the reply is going. Three
     * ends, none of them silent — queued, handed back, or nothing was typed.
     */
    private suspend fun reply(
        context: Context,
        repo: MailRepository,
        settings: SettingsRepository,
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
            runCatching { send(repo, credentials, emailId, outcome) }.isSuccess
        when (val act = quickReplyAct(outcome, queued)) {
            QuickReplyAct.DoNothing -> Unit
            QuickReplyAct.Dismiss -> dismiss(context, accountId, credentials, notifId)
            // Read here and not before: the setting is only needed on the end that posts a banner,
            // and it decides how much of the message that banner may name (the subject used to go
            // out whatever the user had chosen).
            //
            // ⚠ GUARDED, and that is not decoration: this read is the first thing on this arm that
            // can throw. The DataStore is declared with no corruption handler (SettingsRepository),
            // so a truncated preferences file surfaces as a throw from `first()` — which onReceive's
            // `catch (_: Throwable)` then swallows, leaving the banner unposted. The text the user
            // typed lives in the broadcast intent and NOWHERE else: closing a leak by dropping it
            // would be the worse bug. An unreadable setting falls back to the QUIETEST position, so
            // the failure mode is a banner that says too little, never one that says too much.
            is QuickReplyAct.HandBack -> {
                val content = runCatching { settings.notificationContent.first() }
                    .getOrDefault(NotificationContent.NONE)
                Notifications.notifySendFailed(context, act.subject, content, act.body)
            }
        }
    }

    /**
     * Hand the reply to the Outbox — which is what makes the text durable: it is sent when there is
     * a network, retried with backoff, and parked as FAILED (text intact) if it never goes.
     *
     * The network is worth at most the threading headers, and never the text: the fetch below is
     * abandoned on a budget, and whatever it did not bring back is simply not put on the message. A
     * reply that goes out with no In-Reply-To beats a reply that never goes out.
     *
     * ⚠ Known and accepted: the cached row carries neither Cc nor Message-Id/References — the
     * columns do not exist (EmailEntity/EmailMapper) — so a quick reply lands OUTSIDE its thread
     * whenever the server does not answer within the budget. Adding those columns is a schema
     * migration and belongs to another change.
     */
    private suspend fun send(
        repo: MailRepository,
        credentials: AccountCredentials,
        emailId: String,
        outcome: QuickReplyOutcome.Send,
    ) {
        val fetched = fetchWithinBudget(repo, credentials, emailId)
        repo.enqueueSend(
            credentials = credentials,
            to = listOf(outcome.to),
            subject = outcome.subject,
            body = outcome.body,
            inReplyTo = fetched?.messageId.orEmpty(),
            references = fetched?.references.orEmpty() + fetched?.messageId.orEmpty(),
            // A quick reply is typed in the shade and replaces no saved draft: nothing to freeze,
            // and nothing destroyed when it lands.
            draftUidValidity = null,
        )
    }

    /**
     * The original's threading headers, or null — ABANDONED after [FETCH_BUDGET_MS] rather than
     * waited for.
     *
     * A `withTimeoutOrNull` wrapped around the call itself is not a leash at all: coroutine
     * cancellation is cooperative and is only observed at a suspension point, while fetchEmail
     * BLOCKS its thread all the way down (IMAP opens the session with timeout = 0 — a connect the
     * OS gives up on after minutes — and retries once; JMAP does an OkHttp execute() with a 20 s
     * connect and a 30 s read). Nothing resumes until the socket returns, so against a reachable
     * but silent server — captive portal, dropped VPN, stale pooled connection — the timeout fired
     * into the void, goAsync's ~10 s ran out, and the enqueue that comes AFTER this never ran. The
     * typed text died with the process: that is the original bug, not a variant of it.
     *
     * So the call goes into a job of its OWN scope — never a child, which would hold this coroutine
     * to the very socket the budget exists to escape — and the budget is spent on await(), which IS
     * a suspension point. On expiry the job is cancelled and we go and queue the message.
     *
     * ⚠ Accepted: cancelling does not close the socket, so the orphaned thread may block until the
     * OS gives up on it. A leaked thread in a receiver that is about to be killed anyway costs less
     * than a lost message.
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

    /** Take the notification down, keeping the account's group summary in step (#56, #92). */
    private fun dismiss(context: Context, accountId: String, credentials: AccountCredentials?, notifId: Int) {
        NotificationManagerCompat.from(context).cancel(notifId)
        credentials?.let { Notifications.updateGroupSummary(context, accountId, it.username, silent = true) }
    }

    companion object {
        const val ACTION_MARK_READ = "app.sterna.action.MARK_READ"
        const val ACTION_DELETE = "app.sterna.action.DELETE"
        const val ACTION_REPLY = "app.sterna.action.REPLY"
        const val EXTRA_EMAIL_ID = "email_id"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_NOTIF_ID = "notif_id"
        const val KEY_REPLY = "key_reply"

        /** The leash on the threading fetch — well under goAsync's budget, deliberately. */
        private const val FETCH_BUDGET_MS = 5_000L
    }
}

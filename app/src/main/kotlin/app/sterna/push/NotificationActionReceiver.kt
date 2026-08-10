package app.sterna.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import app.sterna.SternaApplication
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles new-mail notification quick actions (reply / mark read / delete) in the background.
 * Reply uses inline RemoteInput.
 *
 * Mark as read and Delete dismiss the notification unconditionally, as they always have: what they
 * change is the server's copy of the message, and the same action is one tap away in the list if it
 * failed. Reply is not like them. The typed text lives in the broadcast intent and nowhere else, so
 * on that path the notification comes down only once the Outbox has accepted the text, and every
 * other end of it hands the text back to the user.
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
                        appContext, container.mailRepository, credentials,
                        accountId, emailId, notifId, replyText,
                    )
                    return@launch
                }
                if (credentials != null) {
                    when (action) {
                        ACTION_MARK_READ -> container.mailRepository.setRead(credentials, emailId, true)
                        ACTION_DELETE -> container.mailRepository.delete(credentials, emailId)
                    }
                }
                dismiss(appContext, accountId, credentials, notifId)
            } catch (_: Throwable) {
                // Best-effort; leave the notification so the user can retry from the app.
            } finally {
                pending.finish()
            }
        }
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
            is QuickReplyAct.HandBack -> Notifications.notifySendFailed(context, act.subject, act.body)
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

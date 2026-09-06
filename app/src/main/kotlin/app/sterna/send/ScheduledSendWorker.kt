package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.mail.accountDepartureIsProven
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.Notifications
import kotlinx.coroutines.flow.first

/**
 * Fires a message scheduled for a future time. WorkManager persists and fires this (surviving app
 * death/reboot); the message lives in Room so we only carry its row id. At fire time it deposits
 * the message into the persistent outbox (one reliable downstream path) and drops the scheduled
 * row — actual delivery and retry are then handled by [OutboxWorker].
 */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val row = repo.scheduledSend(id) ?: return Result.success() // already fired or cancelled
        val credentials = container.accountStore.credentials(row.accountId) ?: run {
            // A `null` here is NOT proof that the account left, and this row is the ONLY copy of
            // the message: `ComposeViewModel.scheduleSend` writes no draft beside it. `credentials`
            // also answers null for a secret that will not decode (a regenerated Keystore key), for
            // a linked sub-account whose `pw_` slot was never written, and for a slot withdrawn on
            // an account that is very much alive — and an unreadable blob makes `accounts()` empty,
            // which is exactly what `accountsUnreadable()` exists to say.
            // So the departure is proved first, in the one place a JVM test can execute the rule.
            val departed = accountDepartureIsProven(
                accountsUnreadable = container.accountStore.accountsUnreadable(),
                accountStillListed = container.accountStore.accounts().any { it.id == row.accountId },
            )
            if (departed) {
                // Nothing can ever send this message again: the row goes, as it always has.
                repo.deleteScheduledSend(id)
                return Result.success()
            }
            // Unproven ⇒ nothing is destroyed and nothing is written. retry() and not success():
            // the "Scheduled" screen is still announcing this send, so success() would leave the row
            // on screen with no job behind it, for good. retry() keeps it armed, and it leaves on
            // its own the moment the account reads again.
            // …and it is NOT free: a retry bumps WorkManager's runAttemptCount, which is the
            // very counter the catch below reads against MAX_ATTEMPTS. So an account that stays
            // unreadable for a few minutes spends the give-up budget without a single delivery
            // having been attempted, and the first throw from enqueueSend afterwards lands
            // straight in the give-up arm. That arm notifies before it clears the row, so nothing
            // goes silently — which is what makes this the cheaper end of the trade. Buying the
            // two apart needs an attempt column on the row, and this is not that change.
            return Result.retry()
        }
        return try {
            repo.enqueueSend(
                credentials = credentials,
                to = row.recipients.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                subject = row.subject,
                body = row.textBody,
                inReplyTo = row.inReplyTo?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                references = row.references?.split(" ")?.filter { it.isNotBlank() } ?: emptyList(),
                htmlBody = row.htmlBody,
                fromName = row.fromName,
                fromEmail = row.fromEmail,
                cc = row.cc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                bcc = row.bcc?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
                // A scheduled send of an edited draft (#63) still replaces the stored draft. The
                // row cannot carry the composer's fidelity verdict (no column), so the id IS the
                // verdict: ComposeViewModel.scheduleSend writes draftEmailId only when the body
                // verdict is clean. `false` here hands that proven half on — it may say so ONLY
                // because of that gate — and enqueueSend still reads the addressing half at fire
                // time, where an unknown keeps the original.
                draftEmailId = row.draftEmailId,
                // …and destroys it under the numbering the composer read it in, not one read at
                // delivery (#99). This hand-over is where a number held only on the scheduled row
                // would be dropped: the send would then destroy blind, hours later, on a Drafts
                // folder the server may have renumbered meanwhile.
                draftUidValidity = row.draftUidValidity,
                bodyIsLossy = false,
                // …and one the user asked a read receipt for still asks: this hand-over is where a
                // request that only lived on the scheduled row would be dropped.
                requestReceipt = row.requestReceipt,
            )
            repo.deleteScheduledSend(id)
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                // Give up after retries — but notify instead of dropping it silently, and clear the
                // now-overdue scheduled row so it doesn't linger. The banner names the message only
                // as much as the notification-content setting allows: a scheduled send that failed
                // leaked the subject to the lock screen exactly as a quick reply did.
                //
                // GUARDED: this read sits INSIDE the catch, and the DataStore is declared with no
                // corruption handler — a throw here would escape doWork() as a failure, skipping
                // both the banner and the deleteScheduledSend below, and leaving the overdue row
                // the comment above exists to clear. An unreadable setting falls back to the
                // quietest position, exactly as the quick-reply path does.
                val content = runCatching { container.settingsRepository.notificationContent.first() }
                    .getOrDefault(NotificationContent.NONE)
                Notifications.notifySendFailed(applicationContext, row.subject, content)
                repo.deleteScheduledSend(id)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_ID = "scheduled_send_id"
        private const val MAX_ATTEMPTS = 3
    }
}

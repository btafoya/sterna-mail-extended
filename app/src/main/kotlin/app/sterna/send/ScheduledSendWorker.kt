package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.mail.ScheduledSendRetry
import app.sterna.core.data.mail.accountDepartureIsProven
import app.sterna.core.data.settings.NotificationContent
import app.sterna.push.Notifications
import kotlinx.coroutines.flow.first

/**
 * Fires a message scheduled for a future time. WorkManager persists and fires this; the message
 */
class ScheduledSendWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val row = repo.sending.scheduledSend(id) ?: return Result.success() // already fired or cancelled
        val credentials = container.accountStore.credentials(row.accountId) ?: run {
            // A `null` here is not proof that the account left, and this row is the only copy of
            // the message: `credentials` also answers null for a secret that will not decode, and an
            // unreadable blob makes `accounts()` empty. So the departure is proved first, in the one
            // place a JVM test can execute the rule.
            val departed = accountDepartureIsProven(
                accountsUnreadable = container.accountStore.accountsUnreadable(),
                accountStillListed = container.accountStore.accounts().any { it.id == row.accountId },
            )
            if (departed) {
                // Nothing can ever send this message again: the row goes, as it always has.
                repo.sending.deleteScheduledSend(id)
                return Result.success()
            }
            // Unproven, so nothing is destroyed and nothing is written. retry() and not success():
            // the "Scheduled" screen is still announcing this send, so success() would leave the row
            return Result.retry()
        }
        return try {
            repo.sending.enqueueSend(
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
                // A scheduled send of an edited draft (#63) still replaces the stored draft. The row
                // carries no fidelity verdict, so the id IS the verdict: `scheduleSend` writes
                // draftEmailId only when the body verdict is clean, and `false` here may say so only
                // because of that gate.
                draftEmailId = row.draftEmailId,
                // …and destroys it under the numbering the composer read it in, not one read at
                // delivery (#99): dropped here, the send would destroy blind, hours later, on a
                // folder the server may have renumbered.
                draftUidValidity = row.draftUidValidity,
                bodyIsLossy = false,
                // …and one the user asked a read receipt for still asks: dropped here, a request
                // that only lived on the scheduled row would be lost.
                requestReceipt = row.requestReceipt,
            )
            repo.sending.deleteScheduledSend(id)
            Result.success()
        } catch (_: Throwable) {
            if (ScheduledSendRetry.shouldRetry(row.failedAttempts)) {
                // Retried uncounted if this throws: a refused write must never open the destroying arm.
                runCatching { repo.sending.setScheduledSendFailedAttempts(id, row.failedAttempts + 1) }
                Result.retry()
            } else {
                // Out of retries: notify, never drop silently, then clear the row. The banner names
                // the message only as much as the notification-content setting allows.
                //
                // Guarded: a corrupt settings file reads as defaults, notification at its quietest;
                // another read failure may still throw, escape doWork() and skip both the banner and
                // the delete. A read that throws falls back to the quietest position as well.
                val content = runCatching { container.settingsRepository.notificationContent.first() }
                    .getOrDefault(NotificationContent.NONE)
                Notifications.notifySendFailed(applicationContext, row.subject, content)
                repo.sending.deleteScheduledSend(id)
                Result.success()
            }
        }
    }

    companion object {
        const val KEY_ID = "scheduled_send_id"
    }
}

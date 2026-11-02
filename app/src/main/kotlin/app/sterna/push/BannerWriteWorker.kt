package app.sterna.push

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import app.sterna.AppContainer
import app.sterna.container
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.settings.NotificationContent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * The server half of a banner's Mark as read or Delete, whose banner the receiver has already
 * taken down. Off the broadcast because a cold process held by `goAsync` alone can be frozen or
 * killed before a cold server session answers.
 */
class BannerWriteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val emailId = inputData.getString(KEY_EMAIL_ID) ?: return Result.success()
        val write = BannerWrite.ofKey(inputData.getString(KEY_WRITE)) ?: return Result.success()
        val container = (applicationContext as Application).container
        val store = container.accountStore
        val credentials = store.credentials(accountId)
            ?: return leaveUnwritten(bannerUnwrittenWithoutCredentials(accountKnown = store.account(accountId) != null), container, accountId, emailId)
        val repo = container.mailRepository
        if (write == BannerWrite.DELETE) {
            // Decided again from the cache: a message that reached the Trash since the tap must
            // not be handed to `delete`, which evicts it while the server keeps it (#171).
            val reads = bannerDeleteReadsFromCache(repo, accountId, emailId)
            queuedDeleteUnwritten(reads.role, reads.hasTrash)?.let { return leaveUnwritten(it, container, accountId, emailId) }
        }
        val outcome = attemptBannerWrite(
            attempt = runAttemptCount,
            write = {
                when (write) {
                    BannerWrite.MARK_READ -> repo.setRead(credentials, emailId, true)
                    // `queuedDeleteUnwritten` returned above on every other answer.
                    BannerWrite.DELETE -> deleteHoldingItsRow(
                        act = BannerDeleteAct.MoveToTrash,
                        begin = { repo.beginRemoval(accountId, emailId) },
                        delete = { repo.delete(credentials, emailId) },
                        end = { flight, restore -> repo.endRemoval(flight, restore) },
                    )
                }
            },
            failed = { e -> Log.w(TAG, "Notification action ${write.action} failed: ${notificationActionFailureLabel(e)}") },
            giveUp = {
                Log.w(TAG, "Notification action ${write.action} given up after $BANNER_WRITE_ATTEMPTS attempts")
                runCatching { repostBanner(container, accountId, emailId) }
            },
        )
        return when (outcome) {
            BannerWriteOutcome.Done -> Result.success()
            BannerWriteOutcome.Retry -> Result.retry()
            BannerWriteOutcome.GaveUp -> Result.failure()
        }
    }

    private suspend fun leaveUnwritten(
        unwritten: BannerUnwritten,
        container: AppContainer,
        accountId: String,
        emailId: String,
    ): Result {
        if (unwritten == BannerUnwritten.GiveBannerBack) runCatching { repostBanner(container, accountId, emailId) }
        return Result.success()
    }

    /**
     * The banner back, after a write that never went: the user's only hold on a message the list
     * shows again. Silent, it announces nothing new. Nothing when the cache no longer has the row:
     * the message, or the account, is gone.
     */
    private suspend fun repostBanner(container: AppContainer, accountId: String, emailId: String) {
        val email = container.mailRepository.cachedEmail(accountId, emailId) ?: return
        val content = runCatching { container.settingsRepository.notificationContent.first() }
            .getOrDefault(NotificationContent.NONE)
        val accountLabel = container.accountStore.account(accountId)?.label()
        val active = Notifications.activeChildIds(applicationContext, accountId)
        val live = Notifications.liveChildIdsAfter(active, accountId, emptyList(), listOf(emailId))
        Notifications.notifyNewMail(
            applicationContext,
            email,
            accountId,
            silent = true,
            accountCount = container.accountStore.accounts().size,
            accountLabel = accountLabel,
            folderName = null,
            mailboxId = email.mailboxId,
            content = content,
            summarised = Notifications.summaryShownFor(live.size),
            senderNames = container.senderNames.current(),
        )
        Notifications.updateGroupSummary(
            applicationContext, accountId, accountLabel, content,
            silent = true,
            expectedChildIds = live,
            cancelledChildIds = emptySet(),
        )
    }

    companion object {
        private const val TAG = "NotificationAction"
        private const val KEY_ACCOUNT_ID = "accountId"
        private const val KEY_EMAIL_ID = "emailId"
        private const val KEY_WRITE = "write"

        /** Keep: the banner is gone after the first tap, so a second request is a duplicate, and
         *  replacing would cancel a write already under way. Returns once WorkManager has stored
         *  the request, so that the broadcast does not end on a write still in memory. */
        suspend fun enqueue(context: Context, accountId: String, emailId: String, write: BannerWrite) {
            val builder = OneTimeWorkRequestBuilder<BannerWriteWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId, KEY_EMAIL_ID to emailId, KEY_WRITE to write.key))
            // Expedited work below Android 12 runs as a foreground service and needs a
            // getForegroundInfo() that CoroutineWorker does not provide.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                bannerWriteWorkName(accountId, emailId, write), ExistingWorkPolicy.KEEP, builder.build(),
            ).await()
        }
    }
}

/** Whether a banner's Delete would move the message, see [bannerDeleteReadsFromCache]. */
internal suspend fun bannerDeleteActFromCache(repo: MailRepository, accountId: String, emailId: String): BannerDeleteAct {
    val reads = bannerDeleteReadsFromCache(repo, accountId, emailId)
    return bannerDeleteAct(reads.role, reads.hasTrash)
}

/** The message's folder role and whether its account has a Trash. */
internal class BannerDeleteReads(val role: String?, val hasTrash: Boolean)

/** Read from the local caches alone: no socket is opened to answer it, a broadcast's window
 *  cannot afford one. */
internal suspend fun bannerDeleteReadsFromCache(repo: MailRepository, accountId: String, emailId: String): BannerDeleteReads {
    val cached = runCatching { repo.cachedEmail(accountId, emailId) }.getOrNull()
    val role = runCatching { repo.mailboxRole(accountId, cached?.mailboxId) }.getOrNull()
    val hasTrash = runCatching { repo.hasCachedTrash(accountId) }.getOrDefault(true)
    return BannerDeleteReads(role, hasTrash)
}

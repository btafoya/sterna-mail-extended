package app.sterna.push

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.sterna.container

/**
 * The download ahead after a push-triggered fetch (#157). Work of its own, not a step of
 * [PushFetchWorker]: that worker's unique name is enqueued with `KEEP`, so a push arriving during a
 * pass of tens of seconds would be dropped and its notification wait for the next one.
 */
class DownloadAheadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getString(KEY_ACCOUNT_ID) ?: return Result.success()
        val container = (applicationContext as Application).container
        val credentials = container.accountStore.allCredentials().firstOrNull { it.id == accountId }
            ?: return Result.success()
        container.mailRepository.downloadInboxesAhead(listOf(credentials))
        return Result.success()
    }

    companion object {
        private const val KEY_ACCOUNT_ID = "accountId"

        /** Keep: a pass already queued or running resumes where the cache stops, and covers this one. */
        fun enqueue(context: Context, accountId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadAheadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("download-ahead-$accountId", ExistingWorkPolicy.KEEP, request)
        }
    }
}

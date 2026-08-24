package app.sterna.send

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Enqueues/cancels the WorkManager job that delivers one persistent outbox item. One unique work
 * per item id; a network constraint defers it until connectivity is back, and exponential backoff
 * spaces out transient retries. Mirrors [ScheduledSends].
 */
object Outbox {
    /**
     * One unique work item per outbox id, so everything here books under ONE name and the policy
     * decides what happens to whatever was booked before.
     *
     * ⭐ [keepExisting] is the whole reason this takes a policy instead of always replacing.
     * `true` means "be the safety net, never the replacement": the startup re-arm cannot know
     * whether WorkManager has already restored this send's job, and that job may be delivering
     * right now. [ExistingWorkPolicy.REPLACE] would CANCEL it, the cancellation propagates into the
     * worker's suspending writes so nothing gets written down, and the row stays SENDING — which
     * the next pickup then parks as "interrupted, may already have been sent" on a send nothing
     * interrupted. It also cancels the exponential backoff WorkManager had armed, so an offline
     * send restarts its wait from zero at every launch.
     *
     * ⛔ `false` is the default and that is NOT timidity, it is the trap of this seam.
     * [OutboxWorker] re-books ITSELF for a HELD row (undo window, scheduled send) while it is
     * running, i.e. while the unique work is RUNNING: a global KEEP would see that and throw the
     * re-booking away, so no scheduled send would ever leave and the undo window would never close.
     * Same for `mailRepository.outboxScheduler`, the seam every human gesture goes through — a send
     * or a Retry carries the newest instant there is and must replace whatever is still lying there.
     */
    fun enqueue(
        context: Context,
        id: Long,
        initialDelayMillis: Long = 0,
        keepExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>()
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(OutboxWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    private fun workName(id: Long) = "outbox-send-$id"
}

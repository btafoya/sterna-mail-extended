package app.sterna.send

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Enqueues/cancels the WorkManager job that fires a scheduled send at its time. */
object ScheduledSends {
    /**
     * One unique work item per scheduled-send id, so everything here books under ONE name and the
     * policy decides what happens to whatever was booked before.
     *
     * ⭐ [keepExisting] is the whole reason this takes a policy instead of always replacing.
     * `true` means "be the safety net, never the replacement": the startup re-arm cannot know
     * whether WorkManager has already restored this row's job, and that job may be FIRING right
     * now. [ScheduledSendWorker] makes TWO writes that are not one transaction — it deposits the
     * message in the outbox, then deletes the scheduled row. [ExistingWorkPolicy.REPLACE] cancels
     * the running coroutine between the two: the outbox row is already durable, the deletion never
     * runs (the worker's `catch (_: Throwable)` swallows the `CancellationException`), so the
     * scheduled row survives — and the replacement job, booked with a delay of zero because the
     * hour is now past, deposits the same message a SECOND time. The message goes out twice.
     *
     * ⛔ `false` is the default and that is NOT timidity: only the startup re-arm may keep. Every
     * other caller is a human gesture carrying the instant the user has just picked, and it must
     * replace whatever is still booked under this id, or the message leaves at the old hour.
     *
     * ⛔ [initialDelayMillis] has NO default, and that is a departure from [Outbox.enqueue] on
     * purpose. An outbox item booked with no delay means "deliver now", which is what the outbox is
     * for; a scheduled send booked with no delay means "send AT ONCE" — the one thing the user did
     * not ask for, on the queue whose row is the only copy of the message. Made optional, the
     * cheapest mutation of this file is dropping the argument at the composer's call site: it
     * compiles, it reads fine, and every message scheduled for later leaves immediately.
     *
     * It is coerced up to zero as the belt behind the composer's braces: the
     * schedule pickers refuse a lapsed instant, and four KDocs there state in so many words that
     * one reaching this call "coerces a negative delay to zero", i.e. sends AT ONCE. That sentence
     * has to stay true, and it is also what makes an overdue row re-armed at startup go out.
     */
    fun enqueue(
        context: Context,
        id: Long,
        initialDelayMillis: Long,
        keepExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<ScheduledSendWorker>()
            .setInitialDelay(initialDelayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ScheduledSendWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), policy, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    private fun workName(id: Long) = "scheduled-send-$id"
}

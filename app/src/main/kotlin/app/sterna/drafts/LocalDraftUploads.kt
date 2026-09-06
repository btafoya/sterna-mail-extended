package app.sterna.drafts

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
 * Books the WorkManager job that puts ONE draft kept on the phone (#95) onto the server. Modelled on
 * `Outbox`, which solves the same problem for sends: a network constraint defers the job until
 * connectivity is back, so a draft saved in a lift leaves on its own when the phone comes out.
 *
 * **There is no `cancel` here, and that is deliberate.** The outbox has one because a send can be
 * called off; a draft cannot. Nothing but an upload that went through ends a draft's wait, and the
 * repository is what consumes the row when it does.
 */
object LocalDraftUploads {
    const val KEY_ACCOUNT = "local_draft_account"
    const val KEY_ID = "local_draft_id"

    /**
     * One work item per (account, draft). Both halves are in the key: the row is keyed on both
     * (#31), and a name built on the draft alone would let two accounts' work replace each other.
     *
     * [BackoffPolicy.EXPONENTIAL] covers the case the row's own backoff cannot: a worker the system
     * stops mid-run never reaches the point where it could write anything down, and WorkManager
     * reschedules that run ITSELF. The backoff a FAILED upload arms lives on the row
     * (`LocalDraftEntity.notBeforeMillis`), where it survives a reboot and the loss of every work item.
     *
     * [keepExisting] is what stops the two from fighting, and it is the whole reason this takes a
     * policy rather than always replacing. Everything here books under ONE unique name, so
     * [ExistingWorkPolicy.REPLACE] cancels whatever was booked under it — including the retry
     * WorkManager just armed for a run it stopped. `true` (the stop path of
     * `uploadLocalDraftRebooking`) therefore means "be the safety net": if the system already has
     * something for this draft, leave it alone, since ours would only be later. `false` — the
     * default, and every other caller — means "the instant I carry is newer than whatever was
     * booked", which is exactly what a fresh backoff on the row is.
     *
     * Neither value ever CANCELS a draft's work outright: a draft has no deadline, and only an
     * upload that went through ends its wait.
     */
    fun enqueue(
        context: Context,
        accountId: String,
        id: String,
        delayMillis: Long = 0,
        keepExisting: Boolean = false,
    ) {
        val request = OneTimeWorkRequestBuilder<LocalDraftUploadWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ACCOUNT to accountId, KEY_ID to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (keepExisting) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(accountId, id), policy, request)
    }

    private fun workName(accountId: String, id: String) = "local-draft-upload-$accountId-$id"
}

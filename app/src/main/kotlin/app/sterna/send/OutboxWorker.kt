package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.mail.accountDepartureIsProven

/**
 * Delivers one persistent outbox item. WorkManager persists and fires this (surviving app
 * death/reboot) and gates it on connectivity, so a send queued offline goes out automatically
 * when the network returns. A HELD item (undo window / scheduled time) reschedules until due.
 * Transient failures retry with exponential backoff up to [OutboxLogic.MAX_ATTEMPTS], after which
 * the item is parked as FAILED — it stays in the outbox for manual retry/edit, never silently lost.
 * A failure the SERVER ruled on (a refused recipient, #183) skips the retries entirely and is
 * parked at once: see [OutboxLogic.stateAfterFailure].
 *
 * ⛔ A run that is KILLED mid-delivery is the other case, and it is not a failure: the row was left
 * saying SENDING and the message may or may not have reached the server. Such a row is parked by
 * [OutboxLogic.parkOnPickup] the moment the worker reads it, and never delivered again on its own —
 * a duplicate cannot be recalled, a parked row is one Retry away.
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val item = repo.outboxItem(id) ?: return Result.success() // sent, cancelled or deleted

        // A delivery this row was already in the middle of, whose run never came back: PARK it,
        // do not start it again. The row is written back with its own attemptCount and lastError —
        // nothing was attempted here, and an earlier failure's reason is still on the row.
        // ⛔ This runs on the row AS READ, ahead of every other question. Below isReadyToSend it is
        // dead code; below the delivery it fires on a message that has just been sent twice.
        OutboxLogic.parkOnPickup(item.state)?.let { parked ->
            repo.updateOutboxState(id, parked, item.attemptCount, item.lastError)
            return Result.success()
        }

        // Still inside the undo/scheduled window: try again once it has elapsed.
        if (!OutboxLogic.isReadyToSend(item.state, item.notBeforeMillis, System.currentTimeMillis())) {
            if (item.state == OutboxState.HELD) {
                Outbox.enqueue(applicationContext, id, item.notBeforeMillis - System.currentTimeMillis())
            }
            return Result.success()
        }
        if (item.state == OutboxState.FAILED) return Result.success() // no auto-retry once parked

        val credentials = container.accountStore.credentials(item.accountId) ?: run {
            // ⛔ A `null` here is NOT proof that the account left, and once queued this row is the
            // ONLY copy of the message. `credentials` also answers null for a stored secret that
            // will not decode (a regenerated Keystore key), for a linked sub-account whose `pw_`
            // slot was never written, and for a slot withdrawn on an account that is very much
            // alive — and an unreadable blob makes `accounts()` empty, which is exactly what
            // `accountsUnreadable()` exists to say (`AccountStore.kt:304`). So the departure is
            // proved first, in the one place a JVM test can execute the rule.
            val departed = accountDepartureIsProven(
                accountsUnreadable = container.accountStore.accountsUnreadable(),
                accountStillListed = container.accountStore.accounts().any { it.id == item.accountId },
            )
            if (departed) {
                // Nothing can ever deliver this message again: the row goes, as it always has.
                repo.deleteOutbox(id)
                return Result.success()
            }
            // Unproven ⇒ nothing is destroyed, and NOTHING IS WRITTEN ON THE ROW: no state, no
            // attemptCount, no lastError. Same reasoning as parkOnPickup above — nothing was
            // attempted here, so a bump would spend a retry the user never used and an overwrite
            // would erase the reason of an earlier, genuine failure still printed on the row.
            // ⛔ And a bare retry(): [OutboxLogic.MAX_ATTEMPTS] counts DELIVERY attempts, and this
            // message has not left the phone. Nothing bounds this arm but the account itself — it
            // reads again and the send goes out, or its departure is proved and the row goes then.
            return Result.retry()
        }

        repo.updateOutboxState(id, OutboxState.SENDING, item.attemptCount, item.lastError)
        return try {
            repo.performSend(credentials, item)
            repo.deleteOutbox(id)
            Result.success()
        } catch (t: Throwable) {
            val attempts = OutboxLogic.attemptsAfterFailure(item.attemptCount, t)
            val error = t.message ?: t.javaClass.simpleName
            // ⛔ The failure itself decides, not the count alone: a delivery the SERVER refused is
            // parked at once (#183), because the message is already in Sent and each auto-retry
            // would add a copy there — and, on a partial refusal, deliver it again to the address
            // that was valid all along. OutboxLogic.stateAfterFailure is where that rule is
            // executed by a JVM test; the worker only reads its answer.
            val next = OutboxLogic.stateAfterFailure(attempts, t)
            repo.updateOutboxState(id, next, attempts, error)
            if (next == OutboxState.QUEUED) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "outbox_id"
    }
}

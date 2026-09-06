package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The two fields the outbox badge needs, so counting never loads message bodies.
 * See [OutboxLogic.badgeCount] for why the deadline, not the state, decides silence.
 */
data class OutboxBadgeItem(
    val state: OutboxState,
    val notBeforeMillis: Long,
)

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entity: OutboxEntity): Long

    @Update
    suspend fun update(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: Long): OutboxEntity?

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis ASC")
    suspend fun all(): List<OutboxEntity>

    @Query("SELECT * FROM outbox ORDER BY createdAtMillis ASC")
    fun observeAll(): Flow<List<OutboxEntity>>

    /**
     * Items still in flight (not parked), e.g. to re-arm workers at startup.
     *
     * `'SENDING'` stays in this list ON PURPOSE, and removing it is a defect however wrong it
     * looks: a SENDING row is a delivery whose WorkManager job may be gone (reboot, restore, a job
     * WorkManager dropped). Re-arming it is what lets [app.sterna.send.OutboxWorker] come and PARK
     * it as [OutboxState.INTERRUPTED] ([OutboxLogic.parkOnPickup]) — the worker re-delivers nothing
     * it finds in SENDING. Taken out of this query, such a row would read "Sending…" on the Outbox
     * screen forever, with nobody ever coming to look at it again.
     *
     * And [OutboxState.INTERRUPTED] is NOT here: it is already parked and has already been
     * looked at. Adding it would call the worker back on a row that has nothing left to do but
     * wait for the user's Retry.
     *
     * [OutboxState.KEPT_AS_DRAFT] is out for the older reason (#95): re-arming it delivers the
     * PRE-EDIT message the user asked to keep as a draft.
     */
    @Query("SELECT * FROM outbox WHERE state IN ('HELD', 'QUEUED', 'SENDING') ORDER BY createdAtMillis ASC")
    suspend fun unfinished(): List<OutboxEntity>

    /**
     * Badge inputs: the state and the hold deadline of every row. The count itself is computed in
     * [OutboxLogic.badgeCount] rather than in SQL, because a row parked as HELD offline never
     * changes state — a `state != 'HELD'` count stayed at zero while the message sat in the
     * Outbox (#70), and SQL can't re-emit on the mere passing of the undo window anyway.
     */
    @Query("SELECT state, notBeforeMillis FROM outbox")
    fun observeBadgeItems(): Flow<List<OutboxBadgeItem>>

    /** Flip one row's state — used to take an item out for editing and to give it back (#70). */
    @Query("UPDATE outbox SET state = :state WHERE id = :id")
    suspend fun setState(id: Long, state: OutboxState)

    /**
     * Park mid-edit rows whose auto-retry was already exhausted back as FAILED (#70 regression): a
     * FAILED item reopened then closed untouched must not silently rejoin the queue and send itself.
     * Run BEFORE [parkAllEditingAsInterrupted] at startup so it claims the exhausted rows first and
     * they keep their own `lastError`; the rest are parked with the interrupted-edit reason.
     * [maxAttempts] is [OutboxLogic.MAX_ATTEMPTS], the single retry cap.
     */
    @Query("UPDATE outbox SET state = 'FAILED' WHERE state = 'EDITING' AND attemptCount >= :maxAttempts")
    suspend fun revertEditingExhaustedToFailed(maxAttempts: Int)

    /**
     * Park every row left mid-edit (#70): FAILED, at the retry cap, with [OutboxLogic.EDIT_INTERRUPTED]
     * as its reason — the same SET clause as [parkEditingAsInterrupted], one write, two doors: by id
     * when a composer closes over a lost body, by state here at startup.
     *
     * An EDITING row at startup is one whose composer was open when the process died. The message
     * must not be stranded, and it is not: parked, it is listed in the Outbox in red with its reason,
     * it reopens, and Retry sends it. What is closed is the send WITHOUT a gesture — this used to
     * put the row back to QUEUED, the re-arm that follows booked it at 0, and it left on its own;
     * the composer Android restores afterwards, with the same text, could then send it a second
     * time. A duplicate cannot be taken back from the recipient; a parked row loses nothing.
     *
     * Runs AFTER [revertEditingExhaustedToFailed], so an exhausted row keeps the reason of its own
     * failure instead of this one. Executed on a real SQLite in `EditInterruptedRowTest`.
     */
    @Query("UPDATE outbox SET state = 'FAILED', attemptCount = :maxAttempts, lastError = :lastError WHERE state = 'EDITING'")
    suspend fun parkAllEditingAsInterrupted(maxAttempts: Int, lastError: String)

    /**
     * Park the row a composer closed over a LOST body: FAILED, at the retry cap, with
     * [OutboxLogic.EDIT_INTERRUPTED] as its reason. One write, so the three columns cannot land
     * separately — a row read between two of them would be FAILED with the reason of an earlier
     * failure, or at the cap with no reason at all.
     *
     * `WHERE state = 'EDITING'` is the same guard [MailRepository.releaseOutboxEdit] applies in
     * Kotlin, here in the statement itself: only an EDITING row still belongs to the composer, and
     * a row a Retry has already put back on its way, or the startup sweep has already parked, is
     * not this close's to park. [maxAttempts] is [OutboxLogic.MAX_ATTEMPTS]; the cap is what keeps
     * the row parked through the next reopen-and-close ([OutboxLogic.stateAfterEdit]). Executed on
     * a real SQLite in `EditInterruptedRowTest`.
     */
    @Query("UPDATE outbox SET state = 'FAILED', attemptCount = :maxAttempts, lastError = :lastError WHERE id = :id AND state = 'EDITING'")
    suspend fun parkEditingAsInterrupted(id: Long, maxAttempts: Int, lastError: String)

    @Query("UPDATE outbox SET state = :state, attemptCount = :attemptCount, lastError = :lastError, lastAttemptMillis = :lastAttemptMillis WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: OutboxState,
        attemptCount: Int,
        lastError: String?,
        lastAttemptMillis: Long?,
    )

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)
}

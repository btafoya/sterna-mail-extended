package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The drafts written on this phone and not yet on the server ([LocalDraftEntity]).
 *
 * ⛔ Every statement that reads or writes a DRAFT is scoped by `accountId`. Ids collide between two
 * accounts of the same server (#31), and a background write that outlived the account it belonged
 * to is exactly what #121 was: an unscoped `DELETE` here would throw away another account's unsent
 * text, and an unscoped `UPDATE` would hand a composer's open row to the upload worker. The scope
 * is in the SQL rather than in a caller's filter so no future call site can forget it.
 *
 * ⚠ One statement is deliberately not scoped, and it is the only one: [accountsWithDrafts], whose
 * whole purpose is to say WHICH accounts have work. It returns account ids and nothing else — no
 * draft, no subject, no body — so a caller cannot read across accounts through it.
 */
@Dao
interface LocalDraftDao {
    /**
     * Write a draft, replacing the row with the same `(accountId, id)`. An upsert rather than an
     * insert/update pair because saving is idempotent by design: the composer re-saves the same
     * draft on every autosave, and the second save must land on the first, not beside it.
     */
    @Upsert
    suspend fun upsert(draft: LocalDraftEntity)

    @Query("SELECT * FROM local_drafts WHERE accountId = :accountId AND id = :id")
    suspend fun byId(accountId: String, id: String): LocalDraftEntity?

    /**
     * The row this account already keeps for the SERVER draft [replacesEmailId], if any.
     *
     * ⭐ The second key a draft is found by, and the one the reachable re-save needs. A draft
     * reopened from the server carries the server's id, which is not this table's `id`, so a lookup
     * by `id` alone always misses — and every re-save minted another row, another `Message-ID`,
     * another upload and another attempt to destroy the same original.
     *
     * Newest first with a `LIMIT 1` rather than a unique index: an index would have to be added by a
     * migration over rows that may already be doubled, and would then fail the upgrade rather than
     * settle it. This picks the row a re-save should land on and leaves any older duplicate for the
     * upload volet to carry out normally.
     */
    @Query(
        "SELECT * FROM local_drafts WHERE accountId = :accountId AND replacesEmailId = :replacesEmailId " +
            "ORDER BY updatedAtMillis DESC LIMIT 1",
    )
    suspend fun forServerDraft(accountId: String, replacesEmailId: String): LocalDraftEntity?

    /** Every local draft of one account, newest edit first — what the Drafts list merges in. */
    @Query("SELECT * FROM local_drafts WHERE accountId = :accountId ORDER BY updatedAtMillis DESC")
    fun observeForAccount(accountId: String): Flow<List<LocalDraftEntity>>

    /**
     * The rows an upload worker has to pick up at [nowMillis]: everything not currently open in the
     * composer whose backoff has run out, soonest-due first.
     *
     * `UPLOADING` is included on purpose. It is the state a row is left in by a process that died
     * mid-upload, and leaving it out would strand exactly the draft whose upload we are least sure
     * about. `EDITING` is excluded for the reason [LocalDraftState.EDITING] gives, and
     * ⛔ [LocalDraftState.STAGING] for the reason that one gives: such a row's `attachmentsJson` is
     * still "[]" while its files are being copied, so picking it up sends the draft amputated.
     *
     * ⛔ [nowMillis] is a PARAMETER, not a `strftime`/`CURRENT_TIMESTAMP` in the statement. A query
     * that reads the clock itself cannot be tested at a chosen instant, and this one decides whether
     * a draft that just failed is retried now or in ten minutes — [LocalDraftEntity.notBeforeMillis]
     * is written by [recordAttempt] and would otherwise be a column nobody reads, i.e. a failing
     * upload retried as fast as the worker can loop.
     */
    @Query(
        "SELECT * FROM local_drafts WHERE accountId = :accountId AND state IN ('PENDING', 'UPLOADING') " +
            "AND notBeforeMillis <= :nowMillis ORDER BY notBeforeMillis ASC, createdAtMillis ASC",
    )
    suspend fun pending(accountId: String, nowMillis: Long): List<LocalDraftEntity>

    /**
     * The accounts holding at least one draft that is not on the server yet — so a startup re-arm
     * can find its work without any query reading across accounts. Ids of accounts, never mail.
     */
    @Query("SELECT DISTINCT accountId FROM local_drafts")
    suspend fun accountsWithDrafts(): List<String>

    /** Take a row out for editing and give it back — see [LocalDraftState.EDITING]. */
    @Query("UPDATE local_drafts SET state = :state WHERE accountId = :accountId AND id = :id")
    suspend fun setState(accountId: String, id: String, state: LocalDraftState)

    /**
     * Bring back every row of ONE account left mid-edit or mid-upload by a process that died (#70's
     * rule for the outbox, applied here): the edit is unrecoverable, the draft is not.
     *
     * ⛔ Scoped, like every other write here. Unscoped, a re-arm triggered by one account signing in
     * would flip another account's `EDITING` row back to `PENDING` while its composer is open — and
     * the upload worker would take the text away from under the user, which is the one thing
     * [LocalDraftState.EDITING] exists to prevent. [accountsWithDrafts] is how a startup finds the
     * accounts to call this for.
     */
    @Query(
        "UPDATE local_drafts SET state = 'PENDING' " +
            "WHERE accountId = :accountId AND state IN ('EDITING', 'UPLOADING')",
    )
    suspend fun revertUnfinishedToPending(accountId: String)

    /**
     * Record a failed upload: what went wrong, when, and when to try again. ⚠ No state change to a
     * terminal one and no cap — [LocalDraftEntity.attemptCount] only feeds the backoff.
     */
    @Query(
        "UPDATE local_drafts SET state = :state, attemptCount = :attemptCount, lastError = :lastError, " +
            "lastAttemptMillis = :lastAttemptMillis, notBeforeMillis = :notBeforeMillis " +
            "WHERE accountId = :accountId AND id = :id",
    )
    suspend fun recordAttempt(
        accountId: String,
        id: String,
        state: LocalDraftState,
        attemptCount: Int,
        lastError: String?,
        lastAttemptMillis: Long?,
        notBeforeMillis: Long,
    )

    /** Consume one row — the draft has reached the server (or the user threw it away). */
    @Query("DELETE FROM local_drafts WHERE accountId = :accountId AND id = :id")
    suspend fun deleteById(accountId: String, id: String)

    /** Everything one account holds, for a removed account (#121). Scoped, like the rest. */
    @Query("DELETE FROM local_drafts WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

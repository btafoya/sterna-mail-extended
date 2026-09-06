package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The drafts written on this phone and not yet on the server ([LocalDraftEntity]).
 *
 * Every statement that reads or writes a DRAFT is scoped by `accountId`. Ids collide between two
 * accounts of the same server (#31), and a background write that outlived the account it belonged
 * to is exactly what #121 was: an unscoped `DELETE` here would throw away another account's unsent
 * text, and an unscoped `UPDATE` would hand a composer's open row to the upload worker. The scope
 * is in the SQL rather than in a caller's filter so no future call site can forget it.
 *
 * One statement is deliberately not scoped, and it is the only one: [accountsWithDrafts], whose
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
     * The second key a draft is found by, and the one the reachable re-save needs. A draft
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
     * [LocalDraftState.STAGING] for its own: such a row's `attachmentsJson` is still "[]" while
     * its files are being copied, so picking it up sends the draft amputated. That exclusion
     * STAYS — a row whose copy is genuinely in flight is not the worker's. What keeps it from being
     * a dead end is [revertStagedToPendingLossy], which at the next start hands a `STAGING` row
     * that outlived its process back as `PENDING`, flagged so it cannot destroy an original.
     *
     * [nowMillis] is a PARAMETER, not a `strftime`/`CURRENT_TIMESTAMP` in the statement. A query
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
     *
     * EVERY row counts, whatever its state, and a `WHERE` here would be the quietest way to undo
     * the re-arm: this is its FIRST link, and an account left out is never asked about again. An
     * account whose only row is [LocalDraftState.STAGING] is precisely the one that needs it.
     * `LocalDraftDaoSqlTest` executes this statement rather than trusting the call site.
     */
    @Query("SELECT DISTINCT accountId FROM local_drafts")
    suspend fun accountsWithDrafts(): List<String>

    /** Take a row out for editing and give it back — see [LocalDraftState.EDITING]. */
    @Query("UPDATE local_drafts SET state = :state WHERE accountId = :accountId AND id = :id")
    suspend fun setState(accountId: String, id: String, state: LocalDraftState)

    /**
     * Bring back every row of ONE account left mid-upload by a process that died (#70's rule for the
     * outbox, applied here): the attempt is unrecoverable, the draft is not.
     *
     * **Why an [LocalDraftState.UPLOADING] row must come back, and why it is safe to bring it
     * back blind.** It is what a process killed mid-upload leaves behind, so nobody is looking at it
     * any more: the worker that owned it died with the process, and no composer can have taken it in
     * the meantime — `localDraftMayBeTakenForEdit` refuses that state outright. Left alone the row
     * is frozen for ever: `pending` counts it as due, but nothing ever books a work item for it
     * again, so the draft never reaches the server and not a word is said.
     *
     * **[LocalDraftState.EDITING] is NOT here, and that is the decision this statement carries.**
     * The state means a composer HOLDS the row; flipping it hands the text being typed to the upload
     * worker, which on a successful upload calls `discardLocalDraft` — the row AND its attachment
     * directory deleted under the user, her "Save" landing on a fresh row, and two drafts on the
     * server. And a cold start cannot tell the two cases apart: this runs from `appScope` while
     * Android is still restoring the back stack, i.e. BEFORE the composer killed with a draft open
     * has read its row back (`localDraftMayBeTakenForEdit` accepts `EDITING` for exactly that), so
     * no id, no lease and no timing can be consulted here. Left alone, the row is still `EDITING`
     * when the restored screen reads it, and `localDraftUploadIsDue` answers not-due on that state —
     * so neither the work items booked at startup nor the ones WorkManager replays can consume it.
     * The sweep has no race left to win because it no longer enters one.
     *
     * **What that does NOT say**: it does not say nothing else ever writes over `EDITING`. The
     * upload worker reads a row and then calls `setState(UPLOADING)` unconditionally
     * (`MailRepository.uploadLocalDraft`), so a composer taking the row between those two steps is
     * still overwritten — a narrow read-then-write window this statement neither opens nor closes,
     * and which predates it (`findings/le-televerseur-ecrase-un-etat-qu-il-a-lu-avant.md`). What is
     * claimed here is only this: THIS sweep no longer touches the state.
     *
     * [LocalDraftState.STAGING] is not here either — it has its own statement,
     * [revertStagedToPendingLossy], because it cannot come back on the same terms. A row rescued
     * here is WHOLE: its `attachmentsJson` already describes files that exist, so it may replace
     * the server draft it names and let the original be destroyed. Merged into one statement, a
     * staged row would inherit that permission, and merging them back is the data loss that
     * statement's KDoc describes.
     *
     * Scoped, like every other write here. Unscoped, a re-arm triggered by one account signing in
     * would reach another account's row — and that account's upload may be genuinely in flight in
     * this very process, in which case the state is not a leftover at all. [accountsWithDrafts] is
     * how a startup finds the accounts to call this for.
     */
    @Query("UPDATE local_drafts SET state = 'PENDING' WHERE accountId = :accountId AND state = 'UPLOADING'")
    suspend fun revertUploadingToPending(accountId: String)

    /**
     * Bring ONE account's rows back from a process death mid-STAGING — to [LocalDraftState.PENDING]
     * and **flagged [LocalDraftEntity.bodyIsLossy]**.
     *
     * Why such a row is re-armed at all: nothing else reaches it. [pending] skips it, the
     * composer refuses it (`localDraftMayBeTakenForEdit`), and no gesture in the Drafts list
     * touches a local row — so it sat in the list for ever, drawn but unopenable, hiding the server
     * draft it names behind it, and the only way out was signing the account out, which deletes the
     * text. The state is FLIPPED, nothing is replayed: an orphan `STAGING` row is always a FIRST
     * save (a re-save copies `existing.state`, see `localDraftStagingRow`), so it carries
     * `attachmentsJson` "[]", and the identity of what was attached — source path, type, `cid`,
     * disposition — lived only in the composer's memory and died with the process. No column holds
     * it, so it cannot be re-staged.
     *
     * **And why it comes back LOSSY, which is the half that keeps this from destroying mail.**
     * Whether those attachments are lost depends on the row:
     * - a draft written from scratch — the bytes are gone whatever we do, the typed text is not,
     *   and an empty descriptor list is an attachment ABSENT, not a truncated one;
     * - a draft that REPLACES a server one ([LocalDraftEntity.replacesEmailId]) — the attachments
     *   are ON THE SERVER, in the original. Uploaded as it stands, "[]" yields 0 parts, so
     *   `draftReplacementIsFaithful` compares 0 to 0, calls the replacement faithful, and
     *   `finishDraftSave` DESTROYS the original that held the files.
     *
     * [LocalDraftEntity.bodyIsLossy] is the column that exists to say "this replacement cannot
     * reproduce the original" (`draftReplacementIsFaithful` is `!bodyIsLossy && …`). Set here, the
     * upload keeps the original and answers `ORIGINAL_KEPT`: the user finds two drafts and deletes
     * one. A duplicate is deletable, a destroyed original is not — so the flag is set on every
     * rescued row, including the from-scratch ones where it costs nothing, rather than on a
     * condition this statement would have to get right for ever.
     *
     * Scoped like the rest: another account's `STAGING` row may be a save running right now.
     */
    @Query(
        "UPDATE local_drafts SET state = 'PENDING', bodyIsLossy = 1 " +
            "WHERE accountId = :accountId AND state = 'STAGING'",
    )
    suspend fun revertStagedToPendingLossy(accountId: String)

    /**
     * Record a failed upload: what went wrong, when, and when to try again. No state change to a
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

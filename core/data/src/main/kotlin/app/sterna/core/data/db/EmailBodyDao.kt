package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** `like … escape '\'`, not a subquery: body rows must go regardless of list rows; caller
 *  escapes `%`, `_`, `\`. */
const val BODY_CACHE_ID_PREFIX_SQL: String =
    "DELETE FROM email_bodies WHERE accountId = :accountId AND id LIKE :idPrefix ESCAPE '\\'"

/** DELETE for [EmailBodyDao.deleteAll], shared with its JVM test. */
const val BODY_CACHE_CLEAR_SQL: String = "DELETE FROM email_bodies"

/** `local_drafts` holds the only copy of a draft typed here, so its id keeps its body. */
const val ORPHAN_BODIES_SQL: String =
    "DELETE FROM email_bodies WHERE accountId = :accountId " +
        "AND id NOT IN (SELECT id FROM emails WHERE accountId = :accountId) " +
        "AND id NOT IN (SELECT id FROM local_drafts WHERE accountId = :accountId) " +
        "AND id NOT IN (:spareIds)"

const val DOWNLOAD_AHEAD_SKIP_SQL: String =
    "INSERT OR IGNORE INTO download_ahead_skips (accountId, id) VALUES (:accountId, :id)"

const val DOWNLOAD_AHEAD_SKIPS_PRUNE_SQL: String =
    "DELETE FROM download_ahead_skips WHERE accountId = :accountId " +
        "AND id NOT IN (SELECT id FROM emails WHERE accountId = :accountId)"

const val DOWNLOAD_AHEAD_SKIPS_ID_PREFIX_SQL: String =
    "DELETE FROM download_ahead_skips WHERE accountId = :accountId AND id LIKE :idPrefix ESCAPE '\\'"

const val DOWNLOAD_AHEAD_SKIPS_ACCOUNT_SQL: String = "DELETE FROM download_ahead_skips WHERE accountId = :accountId"

@Dao
interface EmailBodyDao {

    @Upsert
    suspend fun upsert(body: EmailBodyEntity)

    // Keyed (accountId, id) (#31): an id alone could match another account's cached body.
    @Query("SELECT * FROM email_bodies WHERE accountId = :accountId AND id = :id LIMIT 1")
    suspend fun byId(accountId: String, id: String): EmailBodyEntity?

    @Query("SELECT id FROM email_bodies WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun cachedIds(accountId: String, ids: List<String>): List<String>

    @Query(DOWNLOAD_AHEAD_SKIP_SQL)
    suspend fun skipDownloadAhead(accountId: String, id: String)

    @Query(DOWNLOAD_AHEAD_SKIPS_PRUNE_SQL)
    suspend fun pruneDownloadAheadSkips(accountId: String)

    @Query(DOWNLOAD_AHEAD_SKIPS_ID_PREFIX_SQL)
    suspend fun deleteDownloadAheadSkipsForIdPrefix(accountId: String, idPrefix: String)

    @Query(DOWNLOAD_AHEAD_SKIPS_ACCOUNT_SQL)
    suspend fun deleteDownloadAheadSkipsForAccount(accountId: String)

    @Query("SELECT DISTINCT accountId FROM download_ahead_skips")
    suspend fun downloadAheadSkipAccountIds(): List<String>

    @Query("SELECT COUNT(*) FROM email_bodies WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Query(ORPHAN_BODIES_SQL)
    suspend fun deleteOrphans(accountId: String, spareIds: List<String>)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId AND id = :id")
    suspend fun deleteById(accountId: String, id: String)

    /** Drops cached bodies whose id starts with [idPrefix] (one IMAP folder). Does not
     *  self-heal: a stale body under a renumbered UID could render under the wrong text (#99). */
    @Query(BODY_CACHE_ID_PREFIX_SQL)
    suspend fun deleteForIdPrefix(accountId: String, idPrefix: String)

    @Query("DELETE FROM email_bodies WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    /** Every account id with a cached body here, for the orphan sweep
     *  ([app.sterna.core.data.storage.StorageRepository.purgeOrphanedAccounts]). */
    @Query("SELECT DISTINCT accountId FROM email_bodies")
    suspend fun accountIds(): List<String>

    /** Drops every cached body for every account, once per upgrade. Scope pinned to this table
     *  only, widening it would silently wipe messages/attachments/search too. */
    @Query(BODY_CACHE_CLEAR_SQL)
    suspend fun deleteAll()
}

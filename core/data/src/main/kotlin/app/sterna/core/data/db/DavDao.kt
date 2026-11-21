package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * One table for every DAV-style type (Calendar, CalendarEvent, AddressBook, ContactCard, FileNode):
 * the server's JSON verbatim plus the few columns a screen filters or sorts on.
 * ponytail: five tables would each hold id + json + one or two columns; split when a type needs an index of its own.
 */

/** [groupId]: calendar id, address book id or parent folder id, "" when none. [startMs]/[endMs]: UTC
 *  millis, null when the object has no time. [recurring]: [endMs] is then null and the rules are in [json]. */
@Entity(tableName = "dav_objects", primaryKeys = ["accountId", "type", "id"])
data class DavObjectEntity(
    val accountId: String,
    val type: String,
    val id: String,
    val groupId: String = "",
    val name: String = "",
    val startMs: Long? = null,
    val endMs: Long? = null,
    val recurring: Boolean = false,
    val isTask: Boolean = false,
    val json: String,
)

/** The state a type of an account was read at. A row exists only while the server lists the
 *  capability, so it is also "this account supports it"; "" is supported with no state to resume from. */
@Entity(tableName = "dav_state", primaryKeys = ["accountId", "type"])
data class DavStateEntity(val accountId: String, val type: String, val state: String)

/** A write made on the phone that the server has not confirmed. [objectId] is a "local-" id for a create. */
@Entity(tableName = "dav_pending")
data class DavPendingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val type: String,
    /** "create", "update" or "destroy". */
    val op: String,
    val objectId: String,
    /** The JSON-pointer patch (a whole object for a create); "{}" for a destroy. */
    val patchJson: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
    /** The server refused it for good, or it conflicted and was dropped: kept for the user to see. */
    val failed: Boolean = false,
)

@Dao
interface DavDao {
    @Query("SELECT * FROM dav_objects WHERE accountId = :accountId AND type = :type ORDER BY name COLLATE NOCASE")
    fun watch(accountId: String, type: String): Flow<List<DavObjectEntity>>

    @Query("SELECT * FROM dav_objects WHERE accountId = :accountId AND type = :type")
    suspend fun all(accountId: String, type: String): List<DavObjectEntity>

    @Query("SELECT * FROM dav_objects WHERE accountId = :accountId AND type = :type AND groupId = :groupId ORDER BY isTask ASC, name COLLATE NOCASE")
    fun watchGroup(accountId: String, type: String, groupId: String): Flow<List<DavObjectEntity>>

    @Query("SELECT * FROM dav_objects WHERE accountId = :accountId AND type = :type AND id = :id")
    suspend fun get(accountId: String, type: String, id: String): DavObjectEntity?

    /** Events and tasks a window can show: one-off ones overlapping it, and every recurring one that has begun by its end. */
    @Query(
        "SELECT * FROM dav_objects WHERE accountId = :accountId AND type = 'CalendarEvent' AND isTask = :tasks " +
            "AND ((recurring = 0 AND startMs IS NOT NULL AND startMs < :toMs AND COALESCE(endMs, startMs) >= :fromMs) " +
            "OR (recurring = 1 AND startMs < :toMs))",
    )
    suspend fun inWindow(accountId: String, tasks: Boolean, fromMs: Long, toMs: Long): List<DavObjectEntity>

    @Query("SELECT * FROM dav_objects WHERE accountId = :accountId AND type = 'CalendarEvent' AND isTask = 1")
    fun watchTasks(accountId: String): Flow<List<DavObjectEntity>>

    @Query("SELECT state FROM dav_state WHERE accountId = :accountId AND type = :type")
    suspend fun state(accountId: String, type: String): String?

    @Query("SELECT type FROM dav_state WHERE accountId = :accountId")
    fun watchSupported(accountId: String): Flow<List<String>>

    @Upsert suspend fun upsertObjects(rows: List<DavObjectEntity>)
    @Upsert suspend fun upsertState(row: DavStateEntity)

    @Query("DELETE FROM dav_objects WHERE accountId = :accountId AND type = :type")
    suspend fun deleteType(accountId: String, type: String)

    @Query("DELETE FROM dav_state WHERE accountId = :accountId AND type = :type")
    suspend fun deleteState(accountId: String, type: String)

    @Query("DELETE FROM dav_objects WHERE accountId = :accountId AND type = :type AND id IN (:ids)")
    suspend fun deleteIds(accountId: String, type: String, ids: List<String>)

    @Query("UPDATE dav_objects SET id = :newId WHERE accountId = :accountId AND type = :type AND id = :oldId")
    suspend fun renameId(accountId: String, type: String, oldId: String, newId: String)

    @Transaction
    suspend fun replaceAll(accountId: String, type: String, rows: List<DavObjectEntity>, state: String?) {
        deleteType(accountId, type)
        upsertObjects(rows)
        upsertState(DavStateEntity(accountId, type, state.orEmpty()))
    }

    /** Chunked: older SQLite caps the variables of one statement at 999. */
    @Transaction
    suspend fun applyChanges(accountId: String, type: String, rows: List<DavObjectEntity>, destroyed: List<String>, state: String?) {
        destroyed.chunked(500).forEach { deleteIds(accountId, type, it) }
        upsertObjects(rows)
        upsertState(DavStateEntity(accountId, type, state.orEmpty()))
    }

    @Transaction
    suspend fun forgetType(accountId: String, type: String) {
        deleteType(accountId, type)
        deleteState(accountId, type)
    }

    @Query("DELETE FROM dav_objects WHERE accountId = :accountId")
    suspend fun deleteObjectsOf(accountId: String)

    @Query("DELETE FROM dav_state WHERE accountId = :accountId")
    suspend fun deleteStatesOf(accountId: String)

    @Query("DELETE FROM dav_pending WHERE accountId = :accountId")
    suspend fun deletePendingOf(accountId: String)

    @Transaction
    suspend fun deleteForAccount(accountId: String) {
        deleteObjectsOf(accountId)
        deleteStatesOf(accountId)
        deletePendingOf(accountId)
    }

    // ---- the write queue ----

    @Insert suspend fun insertPending(row: DavPendingEntity): Long

    @Query("SELECT * FROM dav_pending WHERE accountId = :accountId AND failed = 0 ORDER BY id")
    suspend fun pending(accountId: String): List<DavPendingEntity>

    @Query("SELECT * FROM dav_pending WHERE accountId = :accountId AND type = :type AND objectId = :objectId AND failed = 0 ORDER BY id")
    suspend fun pendingFor(accountId: String, type: String, objectId: String): List<DavPendingEntity>

    @Query("SELECT accountId FROM dav_pending WHERE failed = 0 GROUP BY accountId")
    suspend fun accountsWithPending(): List<String>

    @Query("SELECT COUNT(*) FROM dav_pending WHERE accountId = :accountId AND failed = 0")
    fun watchPendingCount(accountId: String): Flow<Int>

    @Query("SELECT * FROM dav_pending WHERE accountId = :accountId AND failed = 1 ORDER BY id")
    fun watchFailed(accountId: String): Flow<List<DavPendingEntity>>

    @Query("DELETE FROM dav_pending WHERE id = :id")
    suspend fun deletePending(id: Long)

    @Query("UPDATE dav_pending SET failed = 1, lastError = :error WHERE id = :id")
    suspend fun failPending(id: Long, error: String)

    @Query("UPDATE dav_pending SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun retryPending(id: Long, error: String)

    @Query("UPDATE dav_pending SET objectId = :newId WHERE accountId = :accountId AND type = :type AND objectId = :oldId")
    suspend fun renamePendingTarget(accountId: String, type: String, oldId: String, newId: String)
}

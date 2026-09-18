package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One UID Sterna set out to flag `\Deleted` in an IMAP folder without UIDPLUS and has not seen erased
 * (`app.sterna.core.data.mail.PendingExpungeStore`). [uidValidity] is part of the key: a UID is
 * only unique under one numbering, and after a renumbering the same number names another message.
 */
@Entity(tableName = "pending_expunge", primaryKeys = ["accountId", "mailboxId", "uidValidity", "uid"])
data class PendingExpungeEntity(
    val accountId: String,
    val mailboxId: String,
    val uidValidity: Long,
    val uid: Long,
)

@Dao
interface PendingExpungeDao {
    @Query(
        "SELECT uid FROM pending_expunge " +
            "WHERE accountId = :accountId AND mailboxId = :mailboxId AND uidValidity = :uidValidity",
    )
    suspend fun remembered(accountId: String, mailboxId: String, uidValidity: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun remember(rows: List<PendingExpungeEntity>)

    @Query(
        "DELETE FROM pending_expunge WHERE accountId = :accountId AND mailboxId = :mailboxId " +
            "AND uidValidity = :uidValidity AND uid IN (:uids)",
    )
    suspend fun forget(accountId: String, mailboxId: String, uidValidity: Long, uids: List<Long>)

    @Query("DELETE FROM pending_expunge WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun forgetMailbox(accountId: String, mailboxId: String)

    @Query("DELETE FROM pending_expunge WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)
}

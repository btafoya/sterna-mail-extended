package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The UIDVALIDITY last seen for one IMAP folder — whether cached UIDs still mean what they
 * meant (RFC 3501 §2.3.1.1, #99).
 *
 * A separate table, not a `mailboxes` column: the folder list is replaced wholesale on every
 * refresh, which would reset a column there several times an hour. This row is written only by
 * code that actually observed the value.
 *
 * Keyed `(accountId, mailboxId)`: two accounts on one server share folder names (#31).
 */
@Entity(tableName = "mailbox_uidvalidity", primaryKeys = ["accountId", "mailboxId"])
data class MailboxUidValidityEntity(
    val accountId: String,
    val mailboxId: String,
    val uidValidity: Long,
)

@Dao
interface MailboxUidValidityDao {
    @Query("SELECT uidValidity FROM mailbox_uidvalidity WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun recorded(accountId: String, mailboxId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun record(row: MailboxUidValidityEntity)

    /** Sign-out / account pruning: a removed account's folder numbering goes with it. */
    @Query("DELETE FROM mailbox_uidvalidity WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM mailbox_uidvalidity")
    suspend fun deleteAll()
}

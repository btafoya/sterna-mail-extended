package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query

/**
 * The UIDVALIDITY last seen for one IMAP folder, whether cached UIDs still mean what they
 */
@Entity(tableName = "mailbox_uidvalidity", primaryKeys = ["accountId", "mailboxId"])
data class MailboxUidValidityEntity(
    val accountId: String,
    val mailboxId: String,
    val uidValidity: Long,
    val highestModSeq: Long? = null,
)

@Dao
interface MailboxUidValidityDao {
    @Query("SELECT uidValidity FROM mailbox_uidvalidity WHERE accountId = :accountId AND mailboxId = :mailboxId")
    suspend fun recorded(accountId: String, mailboxId: String): Long?

    @Query(MAILBOX_UIDVALIDITY_FIRST_SIGHT_SQL)
    suspend fun recordIfAbsent(accountId: String, mailboxId: String, uidValidity: Long)

    @Query(MAILBOX_UIDVALIDITY_RENUMBER_SQL)
    suspend fun renumber(accountId: String, mailboxId: String, uidValidity: Long)

    @Query(MAILBOX_MODSEQ_READ_SQL)
    suspend fun highestModSeq(accountId: String, mailboxId: String): Long?

    @Query(MAILBOX_MODSEQ_WRITE_SQL)
    suspend fun recordHighestModSeq(accountId: String, mailboxId: String, uidValidity: Long, highestModSeq: Long)

    /** Sign-out / account pruning: a removed account's folder numbering goes with it. */
    @Query("DELETE FROM mailbox_uidvalidity WHERE accountId = :accountId")
    suspend fun deleteForAccount(accountId: String)

    @Query("DELETE FROM mailbox_uidvalidity")
    suspend fun deleteAll()
}

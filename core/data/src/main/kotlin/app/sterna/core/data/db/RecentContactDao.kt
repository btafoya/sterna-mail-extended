package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface RecentContactDao {
    /** Rows changed, 0 when [email] was never written to. A null [name] keeps the known one. */
    @Query(
        "UPDATE recent_contacts SET sendCount = sendCount + 1, lastSeen = :at, name = COALESCE(:name, name) " +
            "WHERE email = :email",
    )
    suspend fun countAnotherSend(email: String, name: String?, at: Long): Int

    @Query("INSERT INTO recent_contacts (email, name, lastSeen, sendCount) VALUES (:email, :name, :at, 1)")
    suspend fun insertFirstSend(email: String, name: String?, at: Long)

    /**
     * One more send to [email], which the caller has lowercased.
     *
     * An `update`, then an `insert` when it moved no row: the SQL upsert clause (`INSERT … DO UPDATE`) needs SQLite
     * 3.24, and minSdk 26 ships 3.18, where that statement does not parse. An `@Upsert` is no
     * substitute either, it rewrites the whole row and loses both the count and a known name.
     */
    @Transaction
    suspend fun recordSend(email: String, name: String?, at: Long) {
        if (countAnotherSend(email, name, at) == 0) insertFirstSend(email, name, at)
    }

    @Query("SELECT email, name, sendCount, lastSeen FROM recent_contacts")
    suspend fun sentContacts(): List<SentContactRow>
}

/** A name/email pair returned by contact-suggestion queries. */
data class ContactRow(val email: String, val name: String?)

/** One row of [RecentContactDao.sentContacts]; [SentContactRow.lastSeen] is the date of the last send. */
data class SentContactRow(val email: String, val name: String?, val sendCount: Int, val lastSeen: Long)

/** One sender of [EmailDao.receivedFrom]: [email] lowercased, [name] from the newest message. */
data class ReceivedContactRow(val email: String, val name: String?, val received: Int, val latest: Long)

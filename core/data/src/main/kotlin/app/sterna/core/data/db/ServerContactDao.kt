package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** One address of one card of an account's server address book (JMAP `ContactCard`), read-only
 *  copy; a card with three addresses is three rows. */
@Entity(tableName = "server_contacts", primaryKeys = ["accountId", "cardId", "email"])
data class ServerContactEntity(
    val accountId: String,
    val cardId: String,
    /** "" when the card carries no name, never null. */
    val name: String,
    val email: String,
)

/** The `ContactCard` state [ServerContactEntity]'s rows of [accountId] were read at; no row means
 *  the next pass reads the whole address book. */
@Entity(tableName = "server_contacts_state")
data class ServerContactStateEntity(
    @PrimaryKey val accountId: String,
    val state: String,
)

@Dao
interface ServerContactDao {
    @Query("SELECT state FROM server_contacts_state WHERE accountId = :accountId")
    suspend fun state(accountId: String): String?

    @Query("SELECT email, name FROM server_contacts WHERE accountId = :accountId")
    suspend fun book(accountId: String): List<ContactRow>

    @Query("SELECT accountId FROM server_contacts UNION SELECT accountId FROM server_contacts_state")
    suspend fun accountIds(): List<String>

    @Upsert
    suspend fun upsertAll(rows: List<ServerContactEntity>)

    @Upsert
    suspend fun upsertState(state: ServerContactStateEntity)

    @Query("DELETE FROM server_contacts WHERE accountId = :accountId")
    suspend fun deleteRowsOfAccount(accountId: String)

    @Query("DELETE FROM server_contacts WHERE accountId = :accountId AND cardId IN (:cardIds)")
    suspend fun deleteCards(accountId: String, cardIds: List<String>)

    @Query("DELETE FROM server_contacts_state WHERE accountId = :accountId")
    suspend fun deleteState(accountId: String)

    /** Every row of [accountId] becomes [rows]; the state is written in the same transaction, so a
     *  pass that dies half way leaves the old state beside the old rows. */
    @Transaction
    suspend fun replaceAll(accountId: String, rows: List<ServerContactEntity>, state: String?) {
        deleteRowsOfAccount(accountId)
        upsertAll(rows)
        writeState(accountId, state)
    }

    /** A card in [changedCardIds] loses every row before [rows] brings its current addresses back,
     *  so an address removed from a card leaves the copy too. */
    @Transaction
    suspend fun applyChanges(
        accountId: String,
        changedCardIds: List<String>,
        destroyedCardIds: List<String>,
        rows: List<ServerContactEntity>,
        state: String?,
    ) {
        // Chunked: SQLite caps the bound variables of one statement at 999 on older Android.
        (changedCardIds + destroyedCardIds).distinct().chunked(500).forEach { deleteCards(accountId, it) }
        upsertAll(rows)
        writeState(accountId, state)
    }

    @Transaction
    suspend fun deleteForAccount(accountId: String) {
        deleteRowsOfAccount(accountId)
        deleteState(accountId)
    }

    /** A null state is dropped rather than kept: resuming from a stale one would miss changes. */
    suspend fun writeState(accountId: String, state: String?) {
        if (state == null) deleteState(accountId) else upsertState(ServerContactStateEntity(accountId, state))
    }
}

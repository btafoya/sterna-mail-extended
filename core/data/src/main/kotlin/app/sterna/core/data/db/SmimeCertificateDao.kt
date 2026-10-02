package app.sterna.core.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import app.sterna.core.data.pgp.SmimeCertificates
import app.sterna.core.data.pgp.smimeCertificateValidAt

/** A correspondent's S/MIME certificate: [address] lowercased, [certificate] as DER, [messageDate] the server's
 *  date for the message it came with, in epoch millis. No account column: see [SmimeCertificates]. */
@Entity(tableName = "smime_certificates")
class SmimeCertificateEntity(
    @PrimaryKey val address: String,
    val certificate: ByteArray,
    val messageDate: Long,
)

/** One row of [SmimeCertificateDao.storedFor]. */
class StoredSmimeCertificate(val address: String, val certificate: ByteArray)

@Dao
interface SmimeCertificateDao : SmimeCertificates {
    @Query(
        "INSERT OR IGNORE INTO smime_certificates (address, certificate, messageDate) " +
            "VALUES (:address, :certificate, :messageDate)",
    )
    suspend fun insertIfAbsent(address: String, certificate: ByteArray, messageDate: Long)

    @Query(
        "UPDATE smime_certificates SET certificate = :certificate, messageDate = :messageDate " +
            "WHERE address = :address AND messageDate < :messageDate",
    )
    suspend fun replaceIfOlder(address: String, certificate: ByteArray, messageDate: Long)

    @Query("SELECT address, certificate FROM smime_certificates WHERE address IN (:addresses)")
    suspend fun storedFor(addresses: List<String>): List<StoredSmimeCertificate>

    /** An insert that yields to an existing row, then an update that only an older row lets through: the
     *  upsert clause needs SQLite 3.24, and minSdk 26 ships 3.18. A message of the same date replaces nothing. */
    @Transaction
    override suspend fun retain(address: String, certificate: ByteArray, messageDate: Long) {
        val key = address.lowercase()
        insertIfAbsent(key, certificate, messageDate)
        replaceIfOlder(key, certificate, messageDate)
    }

    override suspend fun certificatesFor(addresses: Collection<String>, now: Long): Map<String, ByteArray> {
        // Under SQLite's 999 bound variables, however many recipients a message has.
        val stored = addresses.map { it.lowercase() }.distinct().chunked(500)
            .flatMap { storedFor(it) }
            .associate { it.address to it.certificate }
        return addresses.mapNotNull { address ->
            stored[address.lowercase()]?.takeIf { smimeCertificateValidAt(it, now) }?.let { address to it }
        }.toMap()
    }
}

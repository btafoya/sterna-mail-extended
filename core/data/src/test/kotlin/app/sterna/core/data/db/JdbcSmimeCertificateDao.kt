package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.mail.DaoQuerySource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * [SmimeCertificateDao] over an in-memory SQLite whose table the registered 32 → 33 step made. Only the `@Query`
 * functions are implemented, with the SQL read out of the shipped DAO; [SmimeCertificateDao.retain] and
 * [SmimeCertificateDao.certificatesFor] are the shipped bodies.
 */
internal class JdbcSmimeCertificateDao : SmimeCertificateDao, AutoCloseable {
    val db: Connection = Class.forName("org.sqlite.JDBC").let { DriverManager.getConnection("jdbc:sqlite::memory:") }

    init {
        SternaDatabase.ALL_MIGRATIONS.first { it.startVersion == 32 }.migrate(
            Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                check(method.name == "execSQL" && args?.size == 1) { "Unexpected ${method.name} in a migration" }
                db.createStatement().use { it.executeUpdate(args!![0] as String) }
                null
            } as SupportSQLiteDatabase,
        )
    }

    override fun close() = db.close()

    private fun prepared(function: String, args: Map<String, Any?>, lists: Map<String, Int> = emptyMap()) =
        DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("SmimeCertificateDao", function), lists).let { (sql, order) ->
            db.prepareStatement(sql).also { ps ->
                val cursors = mutableMapOf<String, Int>()
                order.forEachIndexed { i, name ->
                    check(name in args) { "nothing to bind for :$name in $sql" }
                    val value = args[name]
                    ps.setObject(i + 1, if (value is List<*>) value[cursors.merge(name, 1, Int::plus)!! - 1] else value)
                }
            }
        }

    override suspend fun insertIfAbsent(address: String, certificate: ByteArray, messageDate: Long) {
        prepared("insertIfAbsent", mapOf("address" to address, "certificate" to certificate, "messageDate" to messageDate))
            .use { it.executeUpdate() }
    }

    override suspend fun replaceIfOlder(address: String, certificate: ByteArray, messageDate: Long) {
        prepared("replaceIfOlder", mapOf("address" to address, "certificate" to certificate, "messageDate" to messageDate))
            .use { it.executeUpdate() }
    }

    override suspend fun storedFor(addresses: List<String>): List<StoredSmimeCertificate> =
        prepared("storedFor", mapOf("addresses" to addresses), mapOf("addresses" to addresses.size)).use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(StoredSmimeCertificate(rs.getString("address"), rs.getBytes("certificate"))) }
            }
        }

    /** Every row, address order: address, certificate octets, rank. */
    fun rows(): List<Triple<String, List<Byte>, Long>> = db.createStatement().use { st ->
        st.executeQuery("SELECT address, certificate, messageDate FROM smime_certificates ORDER BY address").use { rs ->
            buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getBytes(2).toList(), rs.getLong(3))) }
        }
    }
}

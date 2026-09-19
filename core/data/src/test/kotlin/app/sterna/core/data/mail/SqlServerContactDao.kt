package app.sterna.core.data.mail

import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.SERVER_CONTACTS_CREATE_SQL
import app.sterna.core.data.db.SERVER_CONTACTS_STATE_CREATE_SQL
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.ServerContactEntity
import app.sterna.core.data.db.ServerContactStateEntity
import java.sql.Connection
import java.sql.DriverManager

/**
 * [ServerContactDao] over a real SQLite, its `@Query` functions running the statements read out of
 * the shipped DAO and its `@Upsert`s the `INSERT OR REPLACE` Room generates for them. The
 * transactional compositions (`replaceAll`, `applyChanges`, `deleteForAccount`) are NOT overridden:
 * the shipped bodies run, over these primitives. Their atomicity is Room's and is not exercised.
 *
 * [writes] names every write reached, so "nothing was written" is measured, not inferred.
 */
internal class SqlServerContactDao : ServerContactDao {
    val db: Connection = run {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").also { c ->
            c.createStatement().use {
                it.executeUpdate(SERVER_CONTACTS_CREATE_SQL)
                it.executeUpdate(SERVER_CONTACTS_STATE_CREATE_SQL)
            }
        }
    }

    val writes = mutableListOf<String>()

    private fun prepared(function: String, args: Map<String, Any>, lists: Map<String, List<String>> = emptyMap()) =
        DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("ServerContactDao", function),
            lists.mapValues { it.value.size },
        ).let { (sql, order) ->
            val ps = db.prepareStatement(sql)
            val cursors = lists.mapValues { it.value.iterator() }
            order.forEachIndexed { i, name ->
                val value = cursors[name]?.next() ?: args[name] ?: error("nothing to bind for :$name in $sql")
                ps.setObject(i + 1, value)
            }
            ps
        }

    private fun write(function: String, args: Map<String, Any>, lists: Map<String, List<String>> = emptyMap()) {
        writes += function
        prepared(function, args, lists).use { it.executeUpdate() }
    }

    override suspend fun state(accountId: String): String? =
        prepared("state", mapOf("accountId" to accountId)).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    override suspend fun search(accountId: String, q: String, limit: Int): List<ContactRow> =
        prepared("search", mapOf("accountId" to accountId, "q" to q, "limit" to limit)).use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ContactRow(rs.getString("email"), rs.getString("name"))) }
            }
        }

    override suspend fun accountIds(): List<String> =
        prepared("accountIds", emptyMap()).use { ps ->
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    override suspend fun upsertAll(rows: List<ServerContactEntity>) {
        writes += "upsertAll"
        rows.forEach { row ->
            db.prepareStatement(
                "INSERT OR REPLACE INTO `server_contacts` (`accountId`, `cardId`, `name`, `email`) VALUES (?, ?, ?, ?)",
            ).use {
                it.setString(1, row.accountId); it.setString(2, row.cardId)
                it.setString(3, row.name); it.setString(4, row.email)
                it.executeUpdate()
            }
        }
    }

    override suspend fun upsertState(state: ServerContactStateEntity) {
        writes += "upsertState"
        db.prepareStatement("INSERT OR REPLACE INTO `server_contacts_state` (`accountId`, `state`) VALUES (?, ?)").use {
            it.setString(1, state.accountId); it.setString(2, state.state)
            it.executeUpdate()
        }
    }

    override suspend fun deleteRowsOfAccount(accountId: String) =
        write("deleteRowsOfAccount", mapOf("accountId" to accountId))

    override suspend fun deleteCards(accountId: String, cardIds: List<String>) =
        write("deleteCards", mapOf("accountId" to accountId), mapOf("cardIds" to cardIds))

    override suspend fun deleteState(accountId: String) =
        write("deleteState", mapOf("accountId" to accountId))

    /** Every row, as `accountId|cardId|name|email`, sorted. */
    fun rows(): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT accountId, cardId, name, email FROM server_contacts").use { rs ->
            buildList { while (rs.next()) add((1..4).joinToString("|") { rs.getString(it) }) }
        }
    }.sorted()

    /** Every stored state, as `accountId=state`, sorted. */
    fun states(): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT accountId, state FROM server_contacts_state").use { rs ->
            buildList { while (rs.next()) add("${rs.getString(1)}=${rs.getString(2)}") }
        }
    }.sorted()
}

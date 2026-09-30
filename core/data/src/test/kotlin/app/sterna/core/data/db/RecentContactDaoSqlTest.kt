package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.mail.DaoQuerySource
import app.sterna.core.data.mail.sendsToRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * [RecentContactDao] over a real SQLite: its `@Query` statements are read out of the shipped DAO,
 * and [RecentContactDao.recordSend] is NOT overridden, so the shipped UPDATE-then-INSERT runs. The
 * table is the v31 one carried through the registered step to v32.
 */
class RecentContactDaoSqlTest {
    private lateinit var db: Connection

    private val dao = object : RecentContactDao {
        private fun prepared(function: String, args: Map<String, Any?>) =
            DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("RecentContactDao", function)).let { (sql, order) ->
                db.prepareStatement(sql).also { ps ->
                    order.forEachIndexed { i, name ->
                        check(name in args) { "nothing to bind for :$name in $sql" }
                        ps.setObject(i + 1, args[name])
                    }
                }
            }

        override suspend fun countAnotherSend(email: String, name: String?, at: Long): Int =
            prepared("countAnotherSend", mapOf("email" to email, "name" to name, "at" to at)).use { it.executeUpdate() }

        override suspend fun insertFirstSend(email: String, name: String?, at: Long) {
            prepared("insertFirstSend", mapOf("email" to email, "name" to name, "at" to at)).use { it.executeUpdate() }
        }

        override suspend fun sentContacts(): List<SentContactRow> =
            prepared("sentContacts", emptyMap()).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(SentContactRow(rs.getString("email"), rs.getString("name"), rs.getInt("sendCount"), rs.getLong("lastSeen")))
                        }
                    }
                }
            }
    }

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE `recent_contacts` (`email` TEXT NOT NULL PRIMARY KEY, `name` TEXT, `lastSeen` INTEGER NOT NULL)")
        }
        val step = SternaDatabase.ALL_MIGRATIONS.first { it.startVersion == 31 }
        step.migrate(
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

    @After fun tearDown() = db.close()

    private fun sent() = runBlocking { dao.sentContacts() }.sortedBy { it.email }

    @Test fun `two sends count two, dated at the second`() = runBlocking {
        dao.recordSend("bob@example.org", "Bob", 100)
        dao.recordSend("bob@example.org", "Bob", 200)

        assertEquals(listOf(SentContactRow("bob@example.org", "Bob", 2, 200)), sent())
    }

    @Test fun `a send without a name keeps the name already known`() = runBlocking {
        dao.recordSend("bob@example.org", "Bob Martin", 100)
        dao.recordSend("bob@example.org", null, 200)

        assertEquals(listOf(SentContactRow("bob@example.org", "Bob Martin", 2, 200)), sent())
    }

    @Test fun `a first send starts at one, and leaves the other rows alone`() = runBlocking {
        dao.recordSend("bob@example.org", "Bob", 100)
        dao.recordSend("ann@example.org", null, 300)

        assertEquals(
            listOf(SentContactRow("ann@example.org", null, 1, 300), SentContactRow("bob@example.org", "Bob", 1, 100)),
            sent(),
        )
    }

    @Test fun `one message to the same person twice is one send, under the name it gave`() {
        assertEquals(
            listOf("bob@example.org" to "Bob", "ann@example.org" to null),
            sendsToRecord(listOf("bob@example.org", "Bob <Bob@Example.org>", " ann@example.org ", "not an address")),
        )
    }
}

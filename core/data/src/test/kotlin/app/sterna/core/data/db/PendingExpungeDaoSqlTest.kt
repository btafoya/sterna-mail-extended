package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [PendingExpungeDao]'s statements, read out of the shipped DAO and run against the table the
 * shipped migration creates. What they must keep apart is the whole safety of the purge: a UID
 * remembered under one UIDVALIDITY must never come back under another.
 */
class PendingExpungeDaoSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(PENDING_EXPUNGE_CREATE_SQL) }
        insert("acc", "Trash", 9, 1, 2, 3)
        insert("acc", "Trash", 10, 7)
        insert("acc", "Inbox", 9, 1)
        insert("other", "Trash", 9, 1)
    }

    @After fun tearDown() = db.close()

    private fun insert(accountId: String, mailboxId: String, uidValidity: Long, vararg uids: Long) {
        uids.forEach { uid ->
            db.prepareStatement("INSERT INTO pending_expunge VALUES (?, ?, ?, ?)").use {
                it.setString(1, accountId); it.setString(2, mailboxId); it.setLong(3, uidValidity); it.setLong(4, uid)
                it.executeUpdate()
            }
        }
    }

    private fun bound(function: String, args: Map<String, Any>, lists: Map<String, List<Long>> = emptyMap()) =
        DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("PendingExpungeEntity", function),
            listParams = lists.mapValues { it.value.size },
        ).let { (statement, order) ->
            val cursor = lists.mapValues { 0 }.toMutableMap()
            db.prepareStatement(statement).also { ps ->
                order.forEachIndexed { i, name ->
                    val value: Any = lists[name]?.let { list -> list[cursor.getValue(name)].also { cursor[name] = cursor.getValue(name) + 1 } }
                        ?: args[name] ?: error("nothing to bind for :$name in $statement")
                    ps.setObject(i + 1, value)
                }
            }
        }

    private fun remembered(accountId: String, mailboxId: String, uidValidity: Long): Set<Long> =
        bound("remembered", mapOf("accountId" to accountId, "mailboxId" to mailboxId, "uidValidity" to uidValidity))
            .use { ps -> ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getLong(1)) } } }

    private fun all(): Set<List<Any>> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT accountId, mailboxId, uidValidity, uid FROM pending_expunge").use { rs ->
                buildSet { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4))) }
            }
        }

    @Test fun `remembered answers one folder under one numbering, and nothing kept under another`() {
        assertEquals(setOf(1L, 2L, 3L), remembered("acc", "Trash", 9))
        assertEquals(
            "UID 7 was remembered under numbering 10: under 9 it names another message",
            setOf(7L),
            remembered("acc", "Trash", 10),
        )
        assertEquals(emptySet<Long>(), remembered("acc", "Trash", 11))
    }

    @Test fun `forget takes the named UIDs of that folder and numbering only`() {
        bound(
            "forget",
            mapOf("accountId" to "acc", "mailboxId" to "Trash", "uidValidity" to 9L),
            lists = mapOf("uids" to listOf(1L, 7L)),
        ).use { it.executeUpdate() }

        assertEquals(setOf(2L, 3L), remembered("acc", "Trash", 9))
        assertEquals("7 is remembered under 10, not under 9", setOf(7L), remembered("acc", "Trash", 10))
        assertEquals(setOf(1L), remembered("acc", "Inbox", 9))
        assertEquals(setOf(1L), remembered("other", "Trash", 9))
    }

    @Test fun `forgetMailbox takes every numbering of that one folder`() {
        bound("forgetMailbox", mapOf("accountId" to "acc", "mailboxId" to "Trash")).use { it.executeUpdate() }

        assertEquals(setOf(listOf<Any>("acc", "Inbox", 9L, 1L), listOf<Any>("other", "Trash", 9L, 1L)), all())
    }

    @Test fun `deleteForAccount takes that account only`() {
        bound("deleteForAccount", mapOf("accountId" to "acc")).use { it.executeUpdate() }

        assertEquals(setOf(listOf<Any>("other", "Trash", 9L, 1L)), all())
    }
}

package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `EmailDao.setSeen`, the statement read out of the shipped DAO, over the columns it touches of
 * `emails`. The unread counters move on the row count it answers, so that count is what is pinned:
 * 1 for a row really changed, 0 for a row absent or already at the value asked.
 */
class SetSeenSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE `emails` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, " +
                    "`seen` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun mail(account: String, id: String, seen: Boolean) {
        db.prepareStatement("INSERT INTO emails VALUES (?, ?, ?)").use {
            it.setString(1, account); it.setString(2, id); it.setInt(3, if (seen) 1 else 0)
            it.executeUpdate()
        }
    }

    private fun setSeen(account: String, id: String, seen: Boolean): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("setSeen"))
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> st.setString(i + 1, account)
                    "id" -> st.setString(i + 1, id)
                    "seen" -> st.setInt(i + 1, if (seen) 1 else 0)
                    else -> error("setSeen binds an unexpected parameter :$name")
                }
            }
            st.executeUpdate()
        }
    }

    private fun seenOf(account: String, id: String): Boolean? =
        db.prepareStatement("SELECT seen FROM emails WHERE accountId = ? AND id = ?").use { st ->
            st.setString(1, account); st.setString(2, id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) == 1 else null }
        }

    @Test fun `an unread row marked read is one row changed, and is read`() {
        mail("A", "m1", seen = false)

        assertEquals(1, setSeen("A", "m1", seen = true))
        assertEquals(true, seenOf("A", "m1"))
    }

    @Test fun `marking it read a second time changes nothing`() {
        mail("A", "m1", seen = false)
        setSeen("A", "m1", seen = true)

        assertEquals(
            "the second of two racing marks must answer 0, or the folder's unread drops twice",
            0,
            setSeen("A", "m1", seen = true),
        )
        assertEquals(true, seenOf("A", "m1"))
    }

    @Test fun `one id on two accounts is marked on its own account only`() {
        mail("A", "shared", seen = false)
        mail("B", "shared", seen = false)

        assertEquals(1, setSeen("A", "shared", seen = true))
        assertEquals(true, seenOf("A", "shared"))
        assertEquals("two accounts of one server share message ids (#31)", false, seenOf("B", "shared"))
    }

    @Test fun `a row absent from the cache changes nothing`() {
        mail("A", "m1", seen = false)

        assertEquals(0, setSeen("A", "missing", seen = true))
        assertEquals(false, seenOf("A", "m1"))
    }

    @Test fun `a read row marked unread is one row changed, and is unread`() {
        mail("A", "m1", seen = true)

        assertEquals(1, setSeen("A", "m1", seen = false))
        assertEquals(false, seenOf("A", "m1"))
    }
}

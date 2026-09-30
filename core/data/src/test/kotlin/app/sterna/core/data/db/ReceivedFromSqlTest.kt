package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `EmailDao.receivedFrom`, the statement read out of the shipped DAO, over the columns it reads of
 * `emails` and `mailboxes`: which folders count as mail received from someone, for every account.
 */
class ReceivedFromSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE `mailboxes` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `role` TEXT, " +
                    "PRIMARY KEY(`accountId`, `id`))",
            )
            it.executeUpdate(
                "CREATE TABLE `emails` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
                    "`fromName` TEXT, `fromEmail` TEXT, `sortKey` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))",
            )
        }
        folder("A", "in", "inbox")
        folder("A", "plain", null)
        folder("A", "spam", "junk")
        folder("A", "bin", "Trash")
        folder("A", "lost", "~trash")
        folder("A", "lostjunk", "~junk")
        folder("A", "lostspam", "~Spam")
        folder("A", "out", "sent")
        folder("A", "draft", "drafts")
        folder("A", "sp2", "spam")
        folder("B", "in", "inbox")
        folder("B", "bin", "trash")
        // One id, two accounts: A's Trash, B's Inbox.
        folder("A", "shared", "trash")
        folder("B", "shared", "inbox")
    }

    @After fun tearDown() = db.close()

    private var next = 0

    private fun folder(account: String, id: String, role: String?) {
        db.prepareStatement("INSERT INTO mailboxes VALUES (?, ?, ?)").use {
            it.setString(1, account); it.setString(2, id); it.setString(3, role); it.executeUpdate()
        }
    }

    private fun mail(account: String, folder: String, from: String?, name: String? = null, at: Long = 1) {
        db.prepareStatement("INSERT INTO emails VALUES (?, ?, ?, ?, ?, ?)").use {
            it.setString(1, account); it.setString(2, "m${next++}"); it.setString(3, folder)
            it.setString(4, name); it.setString(5, from); it.setLong(6, at)
            it.executeUpdate()
        }
    }

    private fun received(): List<ReceivedContactRow> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("receivedFrom"))
        check(order.isEmpty()) { "receivedFrom binds nothing, yet asks for $order" }
        return db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(ReceivedContactRow(rs.getString("email"), rs.getString("name"), rs.getInt("received"), rs.getLong("latest")))
                    }
                }
            }
        }.sortedBy { it.email }
    }

    @Test fun `spam, junk, trash under a lost claim, sent and drafts are not received mail`() {
        mail("A", "spam", "junk@x.org")
        mail("A", "sp2", "spam@x.org")
        mail("A", "bin", "trash@x.org")
        mail("A", "lost", "lost@x.org")
        mail("A", "lostjunk", "lostjunk@x.org")
        mail("A", "lostspam", "lostspam@x.org")
        mail("A", "out", "me@x.org")
        mail("A", "draft", "me@x.org")
        mail("B", "bin", "btrash@x.org")

        assertEquals(emptyList<ReceivedContactRow>(), received())
    }

    @Test fun `a folder without a role counts, and so does every account`() {
        mail("A", "plain", "plain@x.org")
        mail("A", "in", "ann@x.org")
        mail("B", "in", "bob@x.org")

        assertEquals(listOf("ann@x.org", "bob@x.org", "plain@x.org"), received().map { it.email })
    }

    @Test fun `a folder id is read under its own account`() {
        mail("A", "shared", "trashed@x.org")
        mail("B", "shared", "kept@x.org")

        assertEquals(listOf("kept@x.org"), received().map { it.email })
    }

    @Test fun `one line per address whatever its case, counted, dated and named by the newest`() {
        mail("A", "in", "Ann@X.org", "Ann old", at = 10)
        mail("B", "in", "ann@x.org", "Ann New", at = 30)
        mail("A", "in", "ann@x.org", null, at = 20)
        mail("A", "spam", "ann@x.org", "Spam Ann", at = 40)
        mail("A", "in", "", "Nobody", at = 50)
        mail("A", "in", null, "Nobody", at = 50)

        assertEquals(listOf(ReceivedContactRow("ann@x.org", "Ann New", 3, 30)), received())
    }
}

package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * `EmailDao.numberingOfRows`' shipped statement, RUN against a real SQLite engine — the read a
 * permanent delete freezes with the user's confirmation and opposes to the server minutes later
 * (Codeberg #99).
 *
 * ⛔ Why this file exists at all: every other test of that read hands the numbering over as a map
 * built in Kotlin, so the SQL itself was executed by NOTHING. Room generates the implementation, so
 * nobody reads it either. A statement that answered the folder's LATEST numbering instead of each
 * row's own — one correlated sub-select, and it still compiles, still returns both columns of
 * `RowNumbering`, still passes every source lint — brings #99 back whole: UIDs read under 42 would
 * be handed 77, the SELECT would compare 77 with 77, and `UID EXPUNGE` would fall on live mail. The
 * statement is read out of the shipped DAO ([DaoQuerySource.emailDaoQuery]), never copied here, so
 * this test cannot drift from what ships.
 *
 * The bench is deliberately built around the one thing the column exists for: TWO rows of ONE
 * folder, read under two different numberings. That is not a corner case — it is what a folder
 * looks like after a background pass met a renumbering, since nothing prunes the old rows on IMAP.
 */
class NumberingOfRowsSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    uidValidity INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    /**
     * ⭐ THE ONE THAT MATTERS. One folder, two numberings — the state a folder is left in when a
     * background pass finds it renumbered and writes the new page beside the old rows. Each row
     * must answer ITS OWN number: answer them both the folder's latest and the older ids go out
     * under a numbering they do not belong to, which is the accident.
     */
    @Test fun `two rows of one folder read under two numberings each answer their own`() {
        insert("imap:acc:INBOX:7", "INBOX", uidValidity = 42)
        insert("imap:acc:INBOX:8", "INBOX", uidValidity = 42)
        insert("imap:acc:INBOX:5", "INBOX", uidValidity = 77)

        assertEquals(
            "the older rows must still say 42 with a 77 row sitting in the same folder — the " +
                "numbering belongs to the ROW, not to the folder's latest sighting",
            mapOf(
                "imap:acc:INBOX:7" to 42L,
                "imap:acc:INBOX:8" to 42L,
                "imap:acc:INBOX:5" to 77L,
            ),
            numberingOfRows("acc", listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8", "imap:acc:INBOX:5")),
        )
    }

    /** A row cached before schema v25, or by JMAP: nothing to oppose, which destroys nothing. */
    @Test fun `a row with no numbering answers none, even beside a row that has one`() {
        insert("imap:acc:INBOX:7", "INBOX", uidValidity = null)
        insert("imap:acc:INBOX:8", "INBOX", uidValidity = 42)

        assertEquals(
            "an unstamped row must not borrow its neighbour's number",
            mapOf("imap:acc:INBOX:7" to null, "imap:acc:INBOX:8" to 42L),
            numberingOfRows("acc", listOf("imap:acc:INBOX:7", "imap:acc:INBOX:8")),
        )
    }

    /** An id the cache no longer holds comes back ABSENT, which the caller reads as "nothing to
     *  oppose" — a row already evicted cannot say what its UID means. */
    @Test fun `an id with no row is absent from the answer`() {
        insert("imap:acc:INBOX:7", "INBOX", uidValidity = 42)

        assertEquals(
            mapOf("imap:acc:INBOX:7" to 42L),
            numberingOfRows("acc", listOf("imap:acc:INBOX:7", "imap:acc:INBOX:9")),
        )
    }

    /**
     * The account scope, which is not decoration here: two accounts of one server mint the same
     * ids (#31), and the composite primary key lets both rows exist. Reading across the scope
     * would hand one account's numbering to the other's destroy.
     */
    @Test fun `another account's row of the same id is never read`() {
        insert("imap:acc:INBOX:7", "INBOX", uidValidity = 42, accountId = "acc")
        insert("imap:acc:INBOX:7", "INBOX", uidValidity = 77, accountId = "other")

        assertEquals(mapOf("imap:acc:INBOX:7" to 42L), numberingOfRows("acc", listOf("imap:acc:INBOX:7")))
        assertEquals(mapOf("imap:acc:INBOX:7" to 77L), numberingOfRows("other", listOf("imap:acc:INBOX:7")))
    }

    // -- the bench ---------------------------------------------------------------------------

    private fun insert(id: String, mailbox: String, uidValidity: Long?, accountId: String = "acc") {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'subj', 'prev', '', 'A', 'a@example.org', " +
                "0, 0, 0, 100, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            if (uidValidity == null) ps.setNull(4, java.sql.Types.INTEGER) else ps.setLong(4, uidValidity)
            ps.executeUpdate()
        }
    }

    /** The SHIPPED statement, bound and executed — never a copy of it. */
    private fun numberingOfRows(accountId: String, ids: List<String>): Map<String, Long?> {
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.emailDaoQuery("numberingOfRows"),
            listParams = mapOf("ids" to ids.size),
        )
        check(order.toSet() == setOf("accountId", "ids")) {
            "EmailDao.numberingOfRows binds $order — this test offers accountId and ids"
        }
        // `order` repeats "ids" once per expanded `?`, in position — so walk it and consume the
        // list one id at a time rather than splicing it in wholesale at the first occurrence.
        val remaining = ids.iterator()
        val values = order.map { if (it == "accountId") accountId else remaining.next() }
        return db.prepareStatement(sql).use { ps ->
            values.forEachIndexed { i, v -> ps.setString(i + 1, v) }
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        val numbering = rs.getLong("uidValidity").takeUnless { rs.wasNull() }
                        put(rs.getString("id"), numbering)
                    }
                }
            }
        }
    }
}

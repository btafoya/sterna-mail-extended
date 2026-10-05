package app.sterna.core.data.mail

import org.junit.After
import app.sterna.core.data.db.CATCH_UP_OFF_WALK
import app.sterna.core.data.db.CATCH_UP_SILENT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Verifies, against in-memory SQLite, the anchor selection of
 * [app.sterna.core.data.db.EmailDao.oldestRepresentativeEmailId] and its companion counts:
 * the fetch-older page is anchored on the oldest row the list walked that is its thread's newest
 * among them within the folder.
 */
class RepresentativeAnchorSqlTest {
    private lateinit var db: Connection

    private val anchorSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("oldestRepresentativeEmailId"))

    private val countSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("representativeCountForMailbox"))

    private val walkedSql by lazy { DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("walkedCountForMailbox")) }

    private val everyRowSql = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("countForMailbox"))

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    catchUp INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, threadId: String?, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc", catchUp: Int = 0,
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', 0, 0, 0, ?, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setLong(5, sortKey)
            ps.setInt(6, catchUp)
            ps.executeUpdate()
        }
    }

    /** Prepare one of the shipped statements, its `?` filled in the order [DaoQuerySource] gives. */
    private fun prepare(
        query: Pair<String, List<String>>,
        accountId: String,
        mailbox: String,
    ): PreparedStatement {
        val (sql, order) = query
        val ps = db.prepareStatement(sql)
        order.forEachIndexed { i, name ->
            ps.setString(
                i + 1,
                when (name) {
                    "accountId" -> accountId
                    "mailboxId" -> mailbox
                    else -> error("Unexpected parameter ':$name' in the representative queries")
                },
            )
        }
        return ps
    }

    private fun anchor(accountId: String = "acc", mailbox: String = "inbox"): String? =
        prepare(anchorSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun representativeCount(accountId: String = "acc", mailbox: String = "inbox"): Int =
        prepare(countSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun walkedCount(accountId: String = "acc", mailbox: String = "inbox"): Int =
        prepare(walkedSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun everyRowCount(accountId: String = "acc", mailbox: String = "inbox"): Int =
        prepare(everyRowSql, accountId, mailbox).use { ps ->
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test fun aCaughtUpRowOlderThanEverythingIsNeitherAnchorNorCounted() {
        insert("caught-up", threadId = null, sortKey = 10, catchUp = CATCH_UP_OFF_WALK)
        insert("caught-up-silent", threadId = "T-old", sortKey = 20, catchUp = CATCH_UP_OFF_WALK or CATCH_UP_SILENT)
        insert("edge", threadId = null, sortKey = 500)
        insert("newest", threadId = "T-new", sortKey = 900)

        assertEquals("the page after the list's edge, not after the caught-up row", "edge", anchor())
        assertEquals(2, walkedCount())
        assertEquals(2, representativeCount())
        assertEquals("the IMAP offset still counts every cached row", 4, everyRowCount())
    }

    @Test fun aSilentRowTheListWalkedIsAnOrdinaryRow() {
        insert("walked-silent", threadId = null, sortKey = 10, catchUp = CATCH_UP_SILENT)
        insert("newest", threadId = null, sortKey = 900)

        assertEquals("walked-silent", anchor())
        assertEquals(2, walkedCount())
        assertEquals(2, representativeCount())
    }

    @Test fun aCaughtUpRowDoesNotStandInForTheWalkedRowsOfItsThread() {
        insert("old-caught-up", threadId = "T1", sortKey = 10, catchUp = CATCH_UP_OFF_WALK)
        insert("old-walked", threadId = "T1", sortKey = 500)
        insert("new-walked", threadId = "T2", sortKey = 600)
        insert("new-caught-up", threadId = "T2", sortKey = 700, catchUp = CATCH_UP_OFF_WALK)

        assertEquals("old-walked", anchor())
        assertEquals(2, walkedCount())
        assertEquals("T1 and T2, each by its walked member", 2, representativeCount())
    }

    @Test fun aNewerCaughtUpRowOfItsThreadDoesNotUnseatTheOnlyWalkedRow() {
        insert("walked", threadId = "T1", sortKey = 600)
        insert("caught-up", threadId = "T1", sortKey = 700, catchUp = CATCH_UP_OFF_WALK)

        assertEquals("walked", anchor())
        assertEquals(1, walkedCount())
        assertEquals(1, representativeCount())
    }

    @Test fun aCaughtUpRowDatedLikeTheWalkedRowOfItsThreadIsStillLeftOut() {
        insert("caught-up", threadId = "T1", sortKey = 500, catchUp = CATCH_UP_OFF_WALK)
        insert("walked", threadId = "T1", sortKey = 500)

        assertEquals("walked", anchor())
        assertEquals(1, walkedCount())
        assertEquals(1, representativeCount())
    }

    @Test fun aFolderHoldingOnlyCaughtUpRowsHasNoAnchorAndCountsNothing() {
        insert("a", threadId = null, sortKey = 10, catchUp = CATCH_UP_OFF_WALK)
        insert("b", threadId = "T1", sortKey = 20, catchUp = CATCH_UP_OFF_WALK or CATCH_UP_SILENT)
        insert("c", threadId = "T1", sortKey = 30, catchUp = CATCH_UP_OFF_WALK)

        assertEquals(null, anchor())
        assertEquals(0, walkedCount())
        assertEquals(0, representativeCount())
        assertEquals(3, everyRowCount())
    }

    @Test fun walkedCountScopesToAccountAndMailbox() {
        insert("in", threadId = null, sortKey = 100)
        insert("sent", threadId = null, sortKey = 200, mailbox = "sent")
        insert("other", threadId = null, sortKey = 300, accountId = "accB")

        assertEquals(1, walkedCount())
    }

    @Test fun theMediatorsJmapBranchReadsOnlyWhatTheListWalkedInItsSourceText() {
        val mediator = DaoQuerySource.mailFunctionBody("MailRepository", "folderMediator").lines()
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        val jmapFrom = mediator.indexOf("val ctx = jmapSession(credentials)")
        val jmapTo = mediator.indexOf("} catch (t: Throwable) {")
        assertTrue("the JMAP branch is no longer where this test reads it", jmapFrom in 1 until jmapTo)
        fun cacheReads(lines: List<String>) = lines.filter { "emailDao." in it && "upsertAll(" !in it && "markWalked(" !in it }

        assertEquals(
            listOf(
                "val offset = emailDao.countForMailbox(credentials.id, mailboxId)",
                "Triple(entities.size, exists, emailDao.countForMailbox(credentials.id, mailboxId))",
            ),
            cacheReads(mediator.subList(0, jmapFrom)),
        )
        val jmap = mediator.subList(jmapFrom, jmapTo)
        assertEquals(
            listOf(
                "val anchorId = emailDao.oldestRepresentativeEmailId(credentials.id, mailboxId)",
                "val before = emailDao.walkedCountForMailbox(credentials.id, mailboxId)",
                "emailDao.representativeCountForMailbox(credentials.id, mailboxId)",
                "val cachedNow = emailDao.walkedCountForMailbox(credentials.id, mailboxId)",
                "emailDao.representativeCountForMailbox(credentials.id, mailboxId) - repsBefore",
                "Triple(page.emails.size, page.total, emailDao.walkedCountForMailbox(credentials.id, mailboxId))",
            ),
            cacheReads(jmap),
        )
        assertEquals(
            listOf("anchorId = anchorId,", "anchorId = page.emails.last().id,"),
            jmap.filter { it.startsWith("anchorId = ") },
        )
        assertEquals(listOf("position = before, calculateTotal = true,"), jmap.filter { it.startsWith("position = ") })
        assertEquals(
            listOf(
                "val reachedEnd = added == 0 || (total != null && cached >= total)",
                "MediatorResult.Success(endOfPaginationReached = reachedEnd)",
            ),
            mediator.subList(jmapTo - 2, jmapTo),
        )
    }

    @Test fun skipsOlderThreadMemberAndAnchorsOnItsRepresentative() {
        insert("m1", threadId = "T1", sortKey = 100) // oldest row, but not T1's newest
        insert("m2", threadId = "T1", sortKey = 300) // T1's representative
        insert("solo", threadId = null, sortKey = 200) // its own representative
        // The raw oldest row (m1) must not be the anchor: the collapsed query never lists it.
        assertEquals("solo", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun threadlessOldestRowIsItsOwnAnchor() {
        insert("a", threadId = null, sortKey = 100)
        insert("b", threadId = null, sortKey = 200)
        assertEquals("a", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun scopesToAccountAndMailbox() {
        insert("in1", threadId = "T1", sortKey = 100)
        insert("sent1", threadId = "T1", sortKey = 300, mailbox = "sent") // newer, other folder
        // The sibling account's member is newer than in1 (issue #31: same-server accounts share
        insert("other1", threadId = "T1", sortKey = 500, accountId = "accB")
        // Within (acc, inbox), in1 is T1's newest, neither the Sent reply nor the sibling
        // account's newer member demotes it.
        assertEquals("in1", anchor())
        assertEquals(1, representativeCount())
    }

    @Test fun aSiblingAccountsIdenticalCopyIsNeitherAnchorNorCounted() {
        // The case [scopesToAccountAndMailbox] cannot see, and the reason the outer
        // `accountId = :accountId and mailboxId = :mailboxId` has to stay: both parameters survive
        //
        // Unless they tie. Two sub-accounts of one server (issue #31) receive the same message,
        // same receivedAt, so the same sortKey, and the same server thread id, and the sibling's
        insert("B-dup", threadId = "T-dup", sortKey = 100, accountId = "accB")
        insert("A-dup", threadId = "T-dup", sortKey = 100)
        insert("A-new", threadId = "T-a", sortKey = 300)

        assertEquals("A-dup", anchor())
        assertEquals(2, representativeCount())
    }

    @Test fun emptyMailboxYieldsNoAnchor() {
        assertEquals(null, anchor())
        assertEquals(0, representativeCount())
    }
}

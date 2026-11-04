package app.sterna.core.data.mail

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * What the "latest messages" home-screen widget is allowed to read, run against real SQLite.
 */
class RecentUnifiedInboxSqlTest {
    private lateinit var db: Connection

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
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, PRIMARY KEY(accountId, emailId))")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String,
        seen: Int = 1,
        mailbox: String = "inbox",
        accountId: String = "acc1",
        sortKey: Long = 1,
    ) {
        db.prepareStatement(
            "INSERT INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, NULL, 'subj-' || ?, 'SECRET BODY', '2026-08-18T10:00:00Z', 'N', 'e@x', ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, id); ps.setInt(5, seen); ps.setLong(6, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, until: Long, accountId: String = "acc1") = db.createStatement().use {
        it.executeUpdate("INSERT INTO snoozed VALUES('$id', '$accountId', $until)")
    }

    /**
     * Room binds a query by handing it a statement to write itself into; this records what it
     * writes, in the positions it writes them. Nothing here decides the order, the query does.
     */
    private class RecordingStatement : SupportSQLiteProgram {
        val bound = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { bound[index] = null }
        override fun bindLong(index: Int, value: Long) { bound[index] = value }
        override fun bindDouble(index: Int, value: Double) { bound[index] = value }
        override fun bindString(index: Int, value: String) { bound[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bound[index] = value }
        override fun clearBindings() = bound.clear()
        override fun close() = Unit
    }

    /**
     * The widget's read as the repository builds it, statement and binds together.
     */
    private fun widget(scopes: List<Pair<String, String>>, limit: Int) =
        recentUnifiedInboxFrom(scopes) { s -> run(recentUnifiedQuery(s, limit, ListFilter.NONE)) }

    private fun run(query: SimpleSQLiteQuery): List<RecentEmailRow> {
        val recorded = RecordingStatement().also { query.bindTo(it) }
        assertEquals(
            "the query declares an argument count its own bindTo does not fill",
            query.argCount,
            recorded.bound.size,
        )
        assertEquals(
            "every placeholder in the statement must get exactly one argument",
            query.sql.count { it == '?' },
            recorded.bound.size,
        )
        return db.prepareStatement(query.sql).use { ps ->
            recorded.bound.forEach { (i, v) ->
                when (v) {
                    is Long -> ps.setLong(i, v)
                    is String -> ps.setString(i, v)
                    else -> error("the widget query bound something unexpected: $v")
                }
            }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            RecentEmailRow(
                                id = rs.getString("id"),
                                accountId = rs.getString("accountId"),
                                mailboxId = rs.getString("mailboxId"),
                                subject = rs.getString("subject"),
                                fromName = rs.getString("fromName"),
                                fromEmail = rs.getString("fromEmail"),
                                seen = rs.getInt("seen") != 0,
                                sortKey = rs.getLong("sortKey"),
                                receivedAt = rs.getString("receivedAt"),
                            )
                        )
                    }
                }
            }
        }
    }

    /** The ids the app's own flat list draws, in its own order, bound the way `pagingQuery` binds. */
    private fun listed(scopes: List<Pair<String, String>>): List<String> =
        db.prepareStatement(pagingSql(scopes.size, SortOrder.DATE_DESC, ListFilter.NONE)).use { ps ->
            scopes.flatMap { listOf(it.first, it.second) }
                .forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    private val one = listOf("acc1" to "inbox")
    private val both = listOf("acc1" to "inbox", "acc2" to "inbox")

    @Test fun `the widget holds exactly the first rows the app's own list draws`() {
        // Distinct sort keys, so the list's order is the one SQLite must produce, not a tie.
        insert("m5", sortKey = 500, seen = 0)
        insert("m1", sortKey = 100)
        insert("m4", sortKey = 400, accountId = "acc2")
        insert("m2", sortKey = 200, seen = 0, accountId = "acc2")
        insert("m3", sortKey = 300)
        insert("elsewhere", sortKey = 900, mailbox = "archive")
        insert("snoozed", sortKey = 800)
        snooze("snoozed", System.currentTimeMillis() + 3_600_000)

        val list = listed(both)
        assertEquals(listOf("m5", "m4", "m3", "m2", "m1"), list)
        assertEquals(list.take(3), widget(both, limit = 3).rows.map { it.id })
        assertEquals(list, widget(both, limit = 50).rows.map { it.id })
    }

    @Test fun `a sibling account's identically numbered inbox stays out`() {
        // Servers number mailboxes per account (#121/#31): "inbox" in acc2 is not acc1's inbox.
        insert("mine", sortKey = 200)
        insert("theirs", sortKey = 300, accountId = "acc2")

        assertEquals(listOf("mine"), widget(one, limit = 10).rows.map { it.id })
        assertEquals(listOf("theirs", "mine"), widget(both, limit = 10).rows.map { it.id })
    }

    @Test fun `a snoozed message is off the widget, exactly as it is off the list`() {
        insert("here", sortKey = 100)
        insert("later", sortKey = 900)
        snooze("later", System.currentTimeMillis() + 3_600_000)

        assertEquals(listOf("here"), widget(one, limit = 10).rows.map { it.id })
        assertEquals(listed(one), widget(one, limit = 10).rows.map { it.id })
    }

    @Test fun `a lapsed snooze is back on the widget`() {
        insert("here", sortKey = 100)
        insert("lapsed", sortKey = 900)
        snooze("lapsed", System.currentTimeMillis() - 1_000)

        assertEquals(listOf("lapsed", "here"), widget(one, limit = 10).rows.map { it.id })
    }

    @Test fun `messages sharing a sort key come out in a stable, determined order`() {
        insert("aaa", sortKey = 700)
        insert("ccc", sortKey = 700)
        insert("bbb", sortKey = 700)

        val once = widget(one, limit = 10).rows.map { it.id }
        assertEquals(listOf("ccc", "bbb", "aaa"), once)
        // Stable across redraws, and stable when the widget is bounded: the row that "wins" a tie
        // must not depend on how many rows were asked for.
        assertEquals(once, widget(one, limit = 10).rows.map { it.id })
        assertEquals(once.take(2), widget(one, limit = 2).rows.map { it.id })
    }

    @Test fun `no readable account is not the same answer as an empty inbox`() {
        insert("mine", sortKey = 100)

        val unknown = widget(scopes = emptyList(), limit = 10)
        assertEquals(false, unknown.configured)
        assertEquals(emptyList<String>(), unknown.rows.map { it.id })

        // Accounts, but nothing cached in their inbox: "empty", and it must be tellable apart.
        val empty = widget(listOf("acc9" to "inbox"), limit = 10)
        assertTrue("accounts exist, so the widget may say the inbox is empty", empty.configured)
        assertEquals(emptyList<String>(), empty.rows.map { it.id })
    }

    @Test fun `the row bound is honoured`() {
        (1..10).forEach { insert("m$it", sortKey = it.toLong()) }

        assertEquals(4, widget(one, limit = 4).rows.size)
        assertEquals(listOf("m10", "m9", "m8", "m7"), widget(one, limit = 4).rows.map { it.id })
        assertEquals(10, widget(one, limit = 25).rows.size)
    }

    /**
     * The body never leaves the database, asserted on the statement the repository ships, by
     * running it and reading back which columns it actually selected.
     */
    @Test fun `the widget's statement cannot carry the body of a message`() {
        insert("m1", sortKey = 100)

        db.prepareStatement(recentUnifiedSql(1, ListFilter.NONE)).use { ps ->
            ps.setString(1, "acc1"); ps.setString(2, "inbox"); ps.setInt(3, 10)
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val columns = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
                assertEquals(
                    "the widget selects exactly the columns a line may draw or open",
                    listOf(
                        "id", "accountid", "mailboxid", "subject",
                        "fromname", "fromemail", "seen", "sortkey", "receivedat",
                    ),
                    columns,
                )
                assertTrue(
                    "preview reached the widget's query: the body of a message is one draw away " +
                        "from an unlocked home screen",
                    columns.none { it == "preview" },
                )
            }
        }
    }

    /**
     * What a line actually CARRIES, every drawn field compared to a value, not just counted.
     */
    @Test fun `a line carries the fields it draws, and never the body under another name`() {
        insert("m1", sortKey = 100, seen = 0)

        val row = widget(one, limit = 10).rows.single()
        assertEquals("m1", row.id)
        assertEquals("acc1", row.accountId)
        assertEquals("inbox", row.mailboxId)
        assertEquals("subj-m1", row.subject)
        assertEquals("N", row.fromName)
        assertEquals("e@x", row.fromEmail)
        assertEquals(false, row.seen)
        assertEquals("2026-08-18T10:00:00Z", row.receivedAt)
        assertEquals(100L, row.sortKey)
        assertTrue(
            "the body of the message reached a field the widget draws",
            listOfNotNull(row.subject, row.fromName, row.fromEmail, row.receivedAt)
                .none { "SECRET" in it },
        )
    }

    /**
     * A bound that is not a bound.
     */
    @Test fun `a non-positive row bound is not a way to read everything, nor to claim emptiness`() {
        (1..5).forEach { insert("m$it", sortKey = it.toLong()) }

        assertEquals(1, widget(one, limit = 0).rows.size)
        assertEquals(1, widget(one, limit = -1).rows.size)
        assertEquals(1, widget(one, limit = Int.MIN_VALUE).rows.size)
    }

    @Test fun `the latest-messages statement is unchanged, word for word`() {
        assertEquals(
            "SELECT id, accountId, mailboxId, subject, fromName, fromEmail, seen, sortKey, receivedAt FROM emails " +
                "WHERE ((emails.accountId = ? AND emails.mailboxId = ?) OR (emails.accountId = ? AND emails.mailboxId = ?)) " +
                "AND NOT EXISTS (SELECT 1 FROM snoozed WHERE snoozed.emailId = emails.id " +
                "AND snoozed.accountId = emails.accountId AND snoozed.until > " +
                "(CAST(strftime('%s','now') AS INTEGER) * 1000)) " +
                "ORDER BY sortKey DESC, id DESC LIMIT ?",
            recentUnifiedSql(2, ListFilter.NONE),
        )
    }

    @Test fun `the unread widget's statement is the unread view's predicate, sorted and bounded as the other`() {
        val query = recentUnreadQuery(listOf("acc1" to "inbox", "acc2" to "archive"), limit = 500)
        assertEquals(
            "The unread widget must read through listRowsWhereSql with the unread criterion, the " +
                "predicate of the list it opens: a 'seen = 0' of its own drifts from it.",
            "SELECT id, accountId, mailboxId, subject, fromName, fromEmail, seen, sortKey, receivedAt FROM emails " +
                "WHERE ${listRowsWhereSql(2, ListFilter(unreadOnly = true))} " +
                "ORDER BY sortKey DESC, id DESC LIMIT ?",
            query.sql,
        )
        val recorded = RecordingStatement().also { query.bindTo(it) }
        assertEquals(
            listOf("acc1", "inbox", "acc2", "archive", RECENT_ROWS_MAX.toLong()),
            recorded.bound.values.toList(),
        )
    }

    private fun unread(scopes: List<Pair<String, String>>?, limit: Int = 10) =
        recentUnreadFrom(scopes) { s -> recentUnreadInLots(s, limit) { q -> run(q) } }

    @Test fun `only unread mail reaches the unread widget, newest first, snoozes out, bounded`() {
        insert("read", seen = 1, sortKey = 900)
        insert("u1", seen = 0, sortKey = 100)
        insert("u2", seen = 0, sortKey = 300)
        insert("u3", seen = 0, sortKey = 200)
        insert("later", seen = 0, sortKey = 950)
        snooze("later", System.currentTimeMillis() + 3_600_000)

        assertEquals(listOf("u2", "u3", "u1"), unread(one).rows.map { it.id })
        assertEquals(listOf("u2", "u3"), unread(one, limit = 2).rows.map { it.id })
    }

    private val manyFoldersWithA2AcrossTwoLots = (0 until 500).map { i -> "a${i / 150}" to "f${i % 150}" }

    private fun fillPastAnyBoundInFirstLotWithSortKeyTiesAcrossLots() {
        manyFoldersWithA2AcrossTwoLots.forEachIndexed { i, (account, folder) ->
            if (i % 7 == 0) insert("u$i", seen = 0, mailbox = folder, accountId = account, sortKey = (i % 40).toLong())
            if (i % 11 == 0) insert("r$i", seen = 1, mailbox = folder, accountId = account, sortKey = 500)
        }
        insert("t4-lot2", seen = 0, mailbox = "f10", accountId = "a3", sortKey = 100)
        insert("t3-lot1", seen = 0, mailbox = "f3", accountId = "a0", sortKey = 100)
        insert("t2-lot2", seen = 0, mailbox = "f120", accountId = "a2", sortKey = 100)
        insert("t1-lot1", seen = 0, mailbox = "f3", accountId = "a1", sortKey = 100)
        insert("a2-lot1", seen = 0, mailbox = "f50", accountId = "a2", sortKey = 90)
        insert("a2-lot2", seen = 0, mailbox = "f130", accountId = "a2", sortKey = 95)
    }

    private fun singleStatementSqliteJdbcAccepts(scopes: List<Pair<String, String>>, limit: Int) =
        run(recentUnreadQuery(scopes, limit))

    @Test fun `no unread statement binds more than SQLite's 999 below Android 12`() {
        val queries = recentUnreadQueries(manyFoldersWithA2AcrossTwoLots, limit = 10)
        assertTrue(
            "one unread read over 500 folders bound ${queries.map { it.argCount }} variables",
            queries.all { it.argCount <= 999 },
        )
        assertEquals(
            "every (account, folder) pair is bound once, in order, across the lots",
            manyFoldersWithA2AcrossTwoLots,
            queries.flatMap { q -> RecordingStatement().also { q.bindTo(it) }.bound.values.toList().dropLast(1) }
                .chunked(2).map { (a, m) -> a as String to m as String },
        )
    }

    @Test fun `one lot is the single statement, bound alike`() {
        val scopes = (0 until 400).map { "a" to "f$it" }
        val lot = recentUnreadQueries(scopes, limit = 10).single()
        val single = recentUnreadQuery(scopes, limit = 10)
        assertEquals(single.sql, lot.sql)
        assertEquals(
            RecordingStatement().also { single.bindTo(it) }.bound,
            RecordingStatement().also { lot.bindTo(it) }.bound,
        )
    }

    @Test fun `the unread rows read in lots are the rows of the single statement`() {
        fillPastAnyBoundInFirstLotWithSortKeyTiesAcrossLots()
        assertEquals(
            "the fixture's ties across lots",
            listOf("t4-lot2", "t3-lot1", "t2-lot2", "t1-lot1", "a2-lot2", "a2-lot1"),
            singleStatementSqliteJdbcAccepts(manyFoldersWithA2AcrossTwoLots, limit = 6).map { it.id },
        )
        for (limit in listOf(1, 5, 10, 30, RECENT_ROWS_MAX)) {
            assertEquals(
                "limit $limit",
                singleStatementSqliteJdbcAccepts(manyFoldersWithA2AcrossTwoLots, limit),
                unread(manyFoldersWithA2AcrossTwoLots, limit).rows,
            )
        }
    }

    @Test fun `the observed unread rows in lots are the rows of the single statement`() = runTest {
        fillPastAnyBoundInFirstLotWithSortKeyTiesAcrossLots()
        val ticks = MutableStateFlow(0)
        val seen = mutableListOf<List<RecentEmailRow>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            recentUnreadRows(flowOf(manyFoldersWithA2AcrossTwoLots)) { s ->
                observeRecentUnreadInLots(s, 10) { q -> ticks.map { run(q) } }
            }.map { it.rows }.toList(seen)
        }
        val before = singleStatementSqliteJdbcAccepts(manyFoldersWithA2AcrossTwoLots, 10)

        db.createStatement().use { it.executeUpdate("UPDATE emails SET seen = 1 WHERE id IN ('t4-lot2', 't3-lot1')") }
        ticks.value = 1

        assertEquals(before, seen.first())
        assertEquals(singleStatementSqliteJdbcAccepts(manyFoldersWithA2AcrossTwoLots, 10), seen.last())
        assertTrue("the read rows left the observed unread widget", seen.last().none { it.id in setOf("t4-lot2", "t3-lot1") })
    }

    @Test fun `no folder list known is not the same answer as nothing unread`() {
        insert("u1", seen = 0, sortKey = 100)

        assertEquals(RecentInbox(configured = false, rows = emptyList()), unread(null))
        assertEquals(
            "Folders known, none of them in the view: a true 'nothing unread', and no statement " +
                "is built for it.",
            RecentInbox(configured = true, rows = emptyList()),
            recentUnreadFrom(emptyList()) { error("an empty scope list was read") },
        )
    }

    private fun observed(scopes: Flow<List<Pair<String, String>>?>, roomInvalidations: Flow<Int>) =
        recentUnreadRows(scopes) { s -> observeRecentUnreadInLots(s, 10) { q -> roomInvalidations.map { run(q) } } }
            .map { inbox -> inbox.rows.map { "${it.accountId}/${it.mailboxId}/${it.id}" } }

    @Test fun `a message read leaves the unread widget at the next emission`() = runTest {
        insert("u1", seen = 0, sortKey = 200)
        insert("u2", seen = 0, sortKey = 100)
        val ticks = MutableStateFlow(0)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observed(flowOf(one), ticks).toList(seen)
        }

        db.createStatement().use { it.executeUpdate("UPDATE emails SET seen = 1 WHERE id = 'u1'") }
        ticks.value = 1

        assertEquals(
            listOf(listOf("acc1/inbox/u1", "acc1/inbox/u2"), listOf("acc1/inbox/u2")),
            seen,
        )
    }

    @Test fun `an unread message outside the inbox enters once its folder is in the scopes`() = runTest {
        insert("inbox-mail", seen = 0, sortKey = 100)
        insert("archived", seen = 0, mailbox = "archive", sortKey = 300)
        insert("binned", seen = 0, mailbox = "trash", sortKey = 500)
        val scopes = MutableStateFlow<List<Pair<String, String>>?>(one)
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observed(scopes, flowOf(0)).toList(seen)
        }

        scopes.value = listOf("acc1" to "inbox", "acc1" to "archive")

        assertEquals(
            listOf(
                listOf("acc1/inbox/inbox-mail"),
                listOf("acc1/archive/archived", "acc1/inbox/inbox-mail"),
            ),
            seen,
        )
    }

    @Test fun `two accounts of one server sharing folder ids stay apart in the unread widget`() = runTest {
        insert("mine", seen = 0, mailbox = "7", sortKey = 100)
        insert("theirs", seen = 0, mailbox = "7", accountId = "acc2", sortKey = 200)
        insert("their-other", seen = 0, mailbox = "8", accountId = "acc2", sortKey = 300)
        val scopes = MutableStateFlow<List<Pair<String, String>>?>(listOf("acc1" to "7", "acc2" to "8"))
        val seen = mutableListOf<List<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observed(scopes, flowOf(0)).toList(seen)
        }

        assertEquals(
            "acc2's '7' is not acc1's '7' (#121): a bare folder id reads the sibling account's mail.",
            listOf(listOf("acc2/8/their-other", "acc1/7/mine")),
            seen,
        )
    }
}

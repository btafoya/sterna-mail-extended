package app.sterna.core.data.mail

import androidx.sqlite.db.SupportSQLiteProgram
import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The starred and attachment criteria of [ListFilter], run against real SQLite through the three
 * readers that must agree: the flat list, the conversation list, and what "Select all" takes.
 */
class ListFilterSqlTest {
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
        threadId: String? = null,
        seen: Int = 1,
        flagged: Int = 0,
        clip: Int = 0,
        sortKey: Long = 1,
        mailbox: String = "inbox",
        accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', ?, ?, ?, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setInt(5, seen); ps.setInt(6, flagged); ps.setInt(7, clip); ps.setLong(8, sortKey)
            ps.executeUpdate()
        }
    }

    private val inbox = listOf("acc" to "inbox")

    private fun ids(sql: String, binds: List<String>): List<String> =
        db.prepareStatement(sql).use { ps ->
            binds.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }.sorted()

    private fun pairs(scopes: List<Pair<String, String>>) = scopes.flatMap { listOf(it.first, it.second) }

    private fun flat(filter: ListFilter, scopes: List<Pair<String, String>> = inbox) =
        ids(pagingSql(scopes.size, SortOrder.DATE_DESC, filter), pairs(scopes))

    /** The representatives the conversation list draws, bound as [conversationQuery] binds it
     *  with no Sent folder: the scope pairs three times. */
    private fun threads(filter: ListFilter, scopes: List<Pair<String, String>> = inbox) =
        ids(conversationSql(scopes.size, SortOrder.DATE_DESC, filter), (1..3).flatMap { pairs(scopes) })

    /** Records what a query binds, in the positions it binds them, as Room hands it a statement. */
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

    /** What "Select all" takes, through the shipped [selectionIdsQuery], its SQL and its binds:
     *  this test only pours them into a JDBC statement. */
    private fun selected(filter: ListFilter, conversationView: Boolean, scopes: List<Pair<String, String>> = inbox): List<String> {
        val query = selectionIdsQuery(scopes, filter, conversationView)
        val recorded = RecordingStatement().also { query.bindTo(it) }
        assertEquals("every placeholder must get exactly one argument", query.sql.count { it == '?' }, recorded.bound.size)
        assertEquals("the declared argument count must be the one bound", query.argCount, recorded.bound.size)
        return ids(query.sql, recorded.bound.values.map { it as String })
    }

    private fun snooze(id: String, accountId: String = "acc") = db.createStatement().use {
        it.executeUpdate("INSERT INTO snoozed VALUES('$id', '$accountId', ${System.currentTimeMillis() + 3_600_000})")
    }

    private val allFilters = listOf(false, true).flatMap { u ->
        listOf(false, true).flatMap { f -> listOf(false, true).map { a -> ListFilter(u, f, a) } }
    }

    // -- flat list -------------------------------------------------------------------------------

    @Test fun `flat - the starred filter keeps the starred rows only`() {
        insert("plain")
        insert("star", flagged = 1)
        insert("clip", clip = 1)

        assertEquals(listOf("star"), flat(ListFilter(flaggedOnly = true)))
    }

    @Test fun `flat - the attachment filter keeps the rows with a paperclip only`() {
        insert("plain")
        insert("star", flagged = 1)
        insert("clip", clip = 1)

        assertEquals(listOf("clip"), flat(ListFilter(withAttachment = true)))
    }

    @Test fun `flat - the criteria combine with AND, the unread one included`() {
        insert("star", flagged = 1)
        insert("clip", clip = 1)
        insert("both-read", flagged = 1, clip = 1)
        insert("both-unread", flagged = 1, clip = 1, seen = 0)
        insert("star-unread", flagged = 1, seen = 0)

        assertEquals(listOf("both-read", "both-unread"), flat(ListFilter(flaggedOnly = true, withAttachment = true)))
        assertEquals(listOf("both-unread"), flat(ListFilter(true, true, true)))
        assertEquals(listOf("both-unread", "star-unread"), flat(ListFilter(unreadOnly = true, flaggedOnly = true)))
        assertEquals(
            "no criterion is the whole folder",
            listOf("both-read", "both-unread", "clip", "star", "star-unread"),
            flat(ListFilter.NONE),
        )
    }

    @Test fun `flat - select all takes exactly the rows on screen, in every filter state`() {
        insert("a")
        insert("b", flagged = 1)
        insert("c", clip = 1, seen = 0)
        insert("d", flagged = 1, clip = 1)
        insert("e", flagged = 1, clip = 1, seen = 0)
        insert("f", flagged = 1, mailbox = "archive")

        allFilters.forEach { filter ->
            assertEquals("list and Select all differ under $filter", flat(filter), selected(filter, conversationView = false))
        }
    }

    // -- conversation list -----------------------------------------------------------------------

    @Test fun `conversation - a thread passes the starred filter when the row it draws wears the star`() {
        // T1: the newest message, the one the row is drawn from, is starred.
        insert("t1-old", threadId = "T1", sortKey = 10)
        insert("t1-new", threadId = "T1", flagged = 1, sortKey = 20)
        // T2: only an older member is starred, so the row shows an empty star.
        insert("t2-old", threadId = "T2", flagged = 1, sortKey = 11)
        insert("t2-new", threadId = "T2", sortKey = 21)

        assertEquals(listOf("t1-new"), threads(ListFilter(flaggedOnly = true)))
        assertEquals(listOf("t1-new", "t2-new"), threads(ListFilter.NONE))
    }

    @Test fun `conversation - a thread passes the attachment filter when the row it draws wears the paperclip`() {
        insert("t1-old", threadId = "T1", sortKey = 10)
        insert("t1-new", threadId = "T1", clip = 1, sortKey = 20)
        insert("t2-old", threadId = "T2", clip = 1, sortKey = 11)
        insert("t2-new", threadId = "T2", sortKey = 21)

        assertEquals(listOf("t1-new"), threads(ListFilter(withAttachment = true)))
    }

    @Test fun `conversation - the criteria combine with AND on the drawn row, unread on the thread`() {
        // T1: row starred and clipped, thread read.
        insert("t1", threadId = "T1", flagged = 1, clip = 1, sortKey = 20)
        // T2: row starred, the paperclip is on an older member only.
        insert("t2-old", threadId = "T2", clip = 1, sortKey = 11)
        insert("t2-new", threadId = "T2", flagged = 1, sortKey = 21)
        // T3: row starred and clipped, an older member unread.
        insert("t3-old", threadId = "T3", seen = 0, sortKey = 12)
        insert("t3-new", threadId = "T3", flagged = 1, clip = 1, sortKey = 22)

        assertEquals(listOf("t1", "t3-new"), threads(ListFilter(flaggedOnly = true, withAttachment = true)))
        assertEquals(listOf("t3-new"), threads(ListFilter(true, true, true)))
        assertEquals(listOf("t1", "t2-new", "t3-new"), threads(ListFilter(flaggedOnly = true)))
        assertEquals(listOf("t3-new"), threads(ListFilter(unreadOnly = true, flaggedOnly = true)))
    }

    @Test fun `conversation - a starred member of a thread drawn unstarred is not selected`() {
        // The destructive case: under "Starred", Select all then Delete would trash t2-old, which
        // sits in a thread the list is not drawing.
        insert("t1-new", threadId = "T1", flagged = 1, sortKey = 20)
        insert("t2-old", threadId = "T2", flagged = 1, clip = 1, sortKey = 11)
        insert("t2-new", threadId = "T2", sortKey = 21)

        assertEquals(listOf("t1-new"), selected(ListFilter(flaggedOnly = true), conversationView = true))
        assertEquals(emptyList<String>(), selected(ListFilter(withAttachment = true), conversationView = true))
    }

    @Test fun `conversation - select all reaches no thread the list does not draw, in every filter state`() {
        insert("t1-a", threadId = "T1", flagged = 1, sortKey = 10)
        insert("t1-b", threadId = "T1", clip = 1, seen = 0, sortKey = 20)
        insert("t2-a", threadId = "T2", flagged = 1, clip = 1, seen = 0, sortKey = 11)
        insert("t2-b", threadId = "T2", flagged = 1, clip = 1, sortKey = 21)
        insert("t3-a", threadId = "T3", flagged = 1, sortKey = 12)
        insert("t3-b", threadId = "T3", flagged = 1, sortKey = 22, mailbox = "archive")
        insert("s1", flagged = 1, clip = 1, seen = 0, sortKey = 13)
        insert("s2", clip = 1, sortKey = 14)

        val threadOf = mapOf(
            "t1-a" to "T1", "t1-b" to "T1", "t2-a" to "T2", "t2-b" to "T2",
            "t3-a" to "T3", "t3-b" to "T3", "s1" to "s1", "s2" to "s2",
        )
        val expected = mapOf(
            ListFilter.NONE to listOf("s1", "s2", "t1-a", "t1-b", "t2-a", "t2-b", "t3-a"),
            ListFilter(unreadOnly = true) to listOf("s1", "t1-b", "t2-a"),
            // t1-a is starred, but T1 is drawn from t1-b, which is not.
            ListFilter(flaggedOnly = true) to listOf("s1", "t2-a", "t2-b", "t3-a"),
            ListFilter(withAttachment = true) to listOf("s1", "s2", "t1-b", "t2-a", "t2-b"),
            ListFilter(unreadOnly = true, flaggedOnly = true) to listOf("s1", "t2-a"),
            ListFilter(unreadOnly = true, withAttachment = true) to listOf("s1", "t1-b", "t2-a"),
            ListFilter(flaggedOnly = true, withAttachment = true) to listOf("s1", "t2-a", "t2-b"),
            ListFilter(true, true, true) to listOf("s1", "t2-a"),
        )
        assertEquals(allFilters.toSet(), expected.keys)
        allFilters.forEach { filter ->
            val selection = selected(filter, conversationView = true)
            val drawn = threads(filter).map { threadOf.getValue(it) }.toSet()
            val reached = selection.map { threadOf.getValue(it) }.toSet()
            assertTrue("under $filter Select all reaches ${reached - drawn}, never drawn (drawn: $drawn)", drawn.containsAll(reached))
            assertEquals("Select all under $filter", expected.getValue(filter), selection)
        }
    }

    @Test fun `conversation - a starred member snoozed out of the list does not make its thread selectable`() {
        // The row is drawn from c, the newest member not snoozed, and c is not starred. b is newer
        // and starred, but snoozed: it is not the row, so a does not ride on it.
        insert("a", threadId = "T", flagged = 1, sortKey = 10)
        insert("c", threadId = "T", sortKey = 20)
        insert("b", threadId = "T", flagged = 1, sortKey = 30)
        snooze("b")
        insert("s1", flagged = 1, sortKey = 5)

        assertEquals(listOf("s1"), threads(ListFilter(flaggedOnly = true)))
        assertEquals(listOf("s1"), selected(ListFilter(flaggedOnly = true), conversationView = true))
    }

    @Test fun `conversation - a starred member filed in another folder does not make its thread selectable`() {
        // The Inbox row is drawn from c, unstarred; the thread's newest, starred b, sits in Archive.
        insert("a", threadId = "T", flagged = 1, sortKey = 10)
        insert("c", threadId = "T", sortKey = 20)
        insert("b", threadId = "T", flagged = 1, sortKey = 30, mailbox = "archive")
        insert("s1", flagged = 1, sortKey = 5)

        assertEquals(listOf("s1"), threads(ListFilter(flaggedOnly = true)))
        assertEquals(listOf("s1"), selected(ListFilter(flaggedOnly = true), conversationView = true))
    }

    @Test fun `conversation - two accounts sharing a thread id do not lend each other a star`() {
        // Thread ids are per account (#31). acc's T is drawn from its unstarred newest; other's T is
        // drawn starred. acc's starred older member must not be selected on other's star.
        insert("acc-old", threadId = "T", flagged = 1, sortKey = 10)
        insert("acc-new", threadId = "T", sortKey = 30)
        insert("other-new", threadId = "T", flagged = 1, sortKey = 40, accountId = "other")
        val unified = listOf("acc" to "inbox", "other" to "inbox")

        assertEquals(listOf("other-new"), threads(ListFilter(flaggedOnly = true), unified))
        assertEquals(listOf("other-new"), selected(ListFilter(flaggedOnly = true), conversationView = true, scopes = unified))
    }
}

package app.sterna.core.data.mail

import app.sterna.core.data.db.OutboxState
import app.sterna.core.jmap.model.Email
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The marks as shown (#259): the `emails` column, or an outbox row in a state that still lights
 * its original. [outboxRowLightsOriginalMark] is executed, and the shipped statements built on it,
 * [shownReplyMarksOfSql] and [withShownReplyMarksSql], run over SQLite.
 *
 * What this does not cover: that Room re-runs these statements when `outbox` changes, which rests
 * on the `observedEntities` of `EmailDao` and is pinned as text in `ShownReplyMarksWiringTest`; that
 * Room stores an [OutboxState] as its name, which the statements assume, as `OutboxDao.unfinished`
 * already does; and the Room paging wrapper around [withShownReplyMarksSql].
 */
class ShownReplyMarksTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE emails(accountId TEXT NOT NULL, id TEXT NOT NULL, mailboxId TEXT NOT NULL, " +
                    "sortKey INTEGER NOT NULL, answered INTEGER NOT NULL DEFAULT 0, forwarded INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(accountId, id))",
            )
            it.executeUpdate(
                "CREATE TABLE outbox(id INTEGER PRIMARY KEY AUTOINCREMENT, state TEXT NOT NULL, " +
                    "originalAccountId TEXT, originalEmailId TEXT, originalMark TEXT)",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun cached(accountId: String, id: String, sortKey: Long = 0, answered: Boolean = false, forwarded: Boolean = false) =
        db.prepareStatement("INSERT INTO emails VALUES (?, ?, 'inbox', ?, ?, ?)").use {
            it.setString(1, accountId); it.setString(2, id); it.setLong(3, sortKey)
            it.setInt(4, if (answered) 1 else 0); it.setInt(5, if (forwarded) 1 else 0)
            it.executeUpdate()
        }

    private fun queued(state: OutboxState, accountId: String?, emailId: String?, mark: String?) =
        db.prepareStatement("INSERT INTO outbox(state, originalAccountId, originalEmailId, originalMark) VALUES (?, ?, ?, ?)").use {
            it.setString(1, state.name); it.setString(2, accountId); it.setString(3, emailId); it.setString(4, mark)
            it.executeUpdate()
        }

    /** What the open message and the search results are drawn from, through the shipped query. */
    private fun shown(vararg keys: Pair<String, String>): Map<Pair<String, String>, ShownReplyMarks> {
        val query = shownReplyMarksOfQuery(keys.toList())
        val bound = mutableListOf<String>()
        query.bindTo(RecordingProgram(bound))
        return db.prepareStatement(query.sql).use { st ->
            bound.forEachIndexed { i, v -> st.setString(i + 1, v) }
            st.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("accountId") to rs.getString("id"), ShownReplyMarks(rs.getBoolean("answered"), rs.getBoolean("forwarded")))
                }
            }
        }
    }

    private class RecordingProgram(val bound: MutableList<String>) : androidx.sqlite.db.SupportSQLiteProgram {
        override fun bindNull(index: Int) { error("no null is bound here") }
        override fun bindLong(index: Int, value: Long) { error("no number is bound here") }
        override fun bindDouble(index: Int, value: Double) { error("no number is bound here") }
        override fun bindString(index: Int, value: String) { bound += value }
        override fun bindBlob(index: Int, value: ByteArray) { error("no blob is bound here") }
        override fun clearBindings() = bound.clear()
        override fun close() = Unit
    }

    private val none = ShownReplyMarks(answered = false, forwarded = false)
    private val answered = ShownReplyMarks(answered = true, forwarded = false)

    // ---- the rule

    @Test fun `a row lights its original while the message may still leave, and not once it will not`() {
        assertEquals(
            mapOf(
                OutboxState.HELD to true,
                OutboxState.QUEUED to true,
                OutboxState.SENDING to true,
                OutboxState.EDITING to true,
                OutboxState.INTERRUPTED to true,
                OutboxState.FAILED to false,
                OutboxState.KEPT_AS_DRAFT to false,
            ),
            OutboxState.entries.associateWith(::outboxRowLightsOriginalMark),
        )
    }

    // ---- the statement

    @Test fun `a reply in the undo window lights the original's reply mark, and that one alone`() {
        cached("A", "M1")
        queued(OutboxState.HELD, "A", "M1", "ANSWERED")

        assertEquals(mapOf(("A" to "M1") to answered), shown("A" to "M1"))
    }

    @Test fun `a forward lights the forward mark`() {
        cached("A", "M1")
        queued(OutboxState.QUEUED, "A", "M1", "FORWARDED")

        assertEquals(ShownReplyMarks(answered = false, forwarded = true), shown("A" to "M1").getValue("A" to "M1"))
    }

    @Test fun `a refused send and a reply kept as a draft light nothing`() {
        cached("A", "M1")
        cached("A", "M2")
        queued(OutboxState.FAILED, "A", "M1", "ANSWERED")
        queued(OutboxState.KEPT_AS_DRAFT, "A", "M2", "ANSWERED")

        assertEquals(mapOf(("A" to "M1") to none, ("A" to "M2") to none), shown("A" to "M1", "A" to "M2"))
    }

    @Test fun `an interrupted delivery and a reply reopened for editing keep the mark lit`() {
        cached("A", "M1")
        cached("A", "M2")
        queued(OutboxState.INTERRUPTED, "A", "M1", "ANSWERED")
        queued(OutboxState.EDITING, "A", "M2", "ANSWERED")

        assertEquals(mapOf(("A" to "M1") to answered, ("A" to "M2") to answered), shown("A" to "M1", "A" to "M2"))
    }

    /** Two accounts of one server number their messages alike. */
    @Test fun `a reply to another account's message of the same id lights nothing here`() {
        cached("A", "M1")
        cached("B", "M1")
        queued(OutboxState.HELD, "B", "M1", "ANSWERED")

        assertEquals(mapOf(("A" to "M1") to none, ("B" to "M1") to answered), shown("A" to "M1", "B" to "M1"))
    }

    @Test fun `a cancelled reply, its row gone, lights nothing`() {
        cached("A", "M1")

        assertEquals(none, shown("A" to "M1").getValue("A" to "M1"))
    }

    @Test fun `a delivered reply is shown by the column alone`() {
        cached("A", "M1", answered = true)

        assertEquals(answered, shown("A" to "M1").getValue("A" to "M1"))
    }

    /** Opened from a server search, the original may have no cached row at all. */
    @Test fun `an original the cache does not hold is still lit by its outbox row`() {
        queued(OutboxState.SENDING, "A", "M9", "ANSWERED")

        assertEquals(mapOf(("A" to "M9") to answered, ("A" to "M8") to none), shown("A" to "M9", "A" to "M8"))
    }

    // ---- the paged list

    @Test fun `the paged list reads the shown marks beside each row, in the list's own order`() {
        cached("A", "M1", sortKey = 3)
        cached("A", "M2", sortKey = 2, forwarded = true)
        cached("B", "M1", sortKey = 1)
        queued(OutboxState.HELD, "A", "M1", "ANSWERED")
        queued(OutboxState.FAILED, "A", "M2", "ANSWERED")

        val rows = db.createStatement().use { st ->
            st.executeQuery(withShownReplyMarksSql("SELECT * FROM emails ORDER BY sortKey DESC")).use { rs ->
                buildList {
                    while (rs.next()) {
                        add(listOf(rs.getString("accountId"), rs.getString("id"), rs.getBoolean("answered"), rs.getBoolean("shownAnswered"), rs.getBoolean("shownForwarded")))
                    }
                }
            }
        }

        assertEquals(
            listOf(
                listOf("A", "M1", false, true, false),
                listOf("A", "M2", false, false, true),
                listOf("B", "M1", false, false, false),
            ),
            rows,
        )
    }

    // ---- what a header and a search result draw

    @Test fun `a shown mark is added to the message, under its own account only`() {
        val shown = mapOf(EmailKey("A", "M1") to answered)

        assertEquals(true, Email(id = "M1", accountId = "A").withShownReplyMarks(shown).isAnswered)
        assertEquals(false, Email(id = "M1", accountId = "A").withShownReplyMarks(shown).isForwarded)
        assertEquals(false, Email(id = "M1", accountId = "B").withShownReplyMarks(shown).isAnswered)
        assertEquals(false, Email(id = "M1", accountId = null).withShownReplyMarks(shown).isAnswered)
    }

    /** The server's keyword is the only witness of a mark set elsewhere on a message not cached. */
    @Test fun `a mark the message already carries is never taken away`() {
        val fromServer = Email(id = "M1", accountId = "A", keywords = mapOf("\$forwarded" to true))

        assertEquals(true, fromServer.withShownReplyMarks(mapOf(EmailKey("A", "M1") to none)).isForwarded)
    }
}

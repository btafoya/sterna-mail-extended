package app.sterna.core.data.mail

import app.sterna.core.data.db.OUTBOX_CREATE_SQL
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Retry on an Outbox row, run through [requeueForRetry] with the shipped `OutboxDao.requeueForRetry`
 * statement executed on a real SQLite engine. `MailSender` cannot be built here, so its one-line
 * `retryOutbox` is pinned by a source lint below, arguments included.
 */
class RetryOutboxTest {

    private lateinit var db: Connection

    private class Armed(val id: Long, val delay: Long)

    private val armed = mutableListOf<Armed>()
    private val scheduler = OutboxScheduler { id, delay -> armed += Armed(id, delay) }

    /** Distinct from every value a fixture row carries, so a write that skipped a column shows. */
    private val retriedAt = 777_000L

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(OUTBOX_CREATE_SQL) }
    }

    @After fun tearDown() = db.close()

    @Test fun `a row the worker is delivering is left as it is and nothing is armed`() = runTest {
        val sending = insert(OutboxState.SENDING, attemptCount = 2, lastError = "timeout")

        requeueForRetry(sending, retriedAt, { id, now -> requeue(id, now) }, scheduler)

        assertEquals(
            "a SENDING row must not be re-queued: the worker that owns it is mid-delivery, and a " +
                "queued copy is picked up by a fresh worker and delivered a second time",
            Row(OutboxState.SENDING, 2, "timeout", ROW_DEADLINE),
            read(sending),
        )
        assertEquals("no worker may be armed for a row Retry did not take", 0, armed.size)
    }

    @Test fun `a row open in the composer is left as it is and nothing is armed`() = runTest {
        val editing = insert(OutboxState.EDITING, attemptCount = 1, lastError = null)

        requeueForRetry(editing, retriedAt, { id, now -> requeue(id, now) }, scheduler)

        assertEquals(
            "an EDITING row belongs to the composer: re-queued, it leaves while being edited, " +
                "then again when the edit is sent",
            Row(OutboxState.EDITING, 1, null, ROW_DEADLINE),
            read(editing),
        )
        assertEquals("no worker may be armed for a row Retry did not take", 0, armed.size)
    }

    @Test fun `a failed row goes back to the queue from scratch and is armed once, now`() = runTest {
        val failed = insert(OutboxState.FAILED, attemptCount = OutboxLogic.MAX_ATTEMPTS, lastError = "550 refused")

        requeueForRetry(failed, retriedAt, { id, now -> requeue(id, now) }, scheduler)

        assertEquals(
            "Retry puts the row back QUEUED with a fresh count, no reason, and its deadline at the " +
                "instant of the tap (the badge's anchor)",
            Row(OutboxState.QUEUED, 0, null, retriedAt),
            read(failed),
        )
        assertEquals("exactly one arming", 1, armed.size)
        assertEquals("the id armed must be the row's own", failed, armed[0].id)
        assertEquals("Retry is immediate", 0L, armed[0].delay)
    }

    /**
     * The statement's excluded states against [OutboxLogic.canRetry], state by state: the button
     * and the write must refuse the same rows.
     */
    @Test fun `the write takes a row exactly when canRetry offers the button`() = runTest {
        val taken = OutboxState.entries.associateWith { state ->
            val id = insert(state, attemptCount = 1, lastError = "x")
            requeue(id, retriedAt) > 0
        }

        assertEquals(
            "the SQL guard of OutboxDao.requeueForRetry and OutboxLogic.canRetry disagree",
            OutboxState.entries.associateWith { OutboxLogic.canRetry(it) },
            taken,
        )
    }

    @Test fun `only the row named is written`() = runTest {
        val retried = insert(OutboxState.FAILED, attemptCount = 3, lastError = "a")
        val other = insert(OutboxState.FAILED, attemptCount = 3, lastError = "b")

        requeueForRetry(retried, retriedAt, { id, now -> requeue(id, now) }, scheduler)

        assertEquals(Row(OutboxState.FAILED, 3, "b", ROW_DEADLINE), read(other))
    }

    /** Source lint: `retryOutbox` hands [requeueForRetry] the tap's clock, the DAO write and the scheduler. */
    @Test fun `retryOutbox goes through requeueForRetry with the shipped write and scheduler`() {
        assertEquals(
            "retryOutbox must be exactly this call: a read-then-write in its place reopens the race " +
                "with the worker, and a scheduler other than outboxScheduler arms nothing",
            listOf(
                "suspend fun retryOutbox(id: Long) =",
                "requeueForRetry(id, System.currentTimeMillis(), { row, now -> outboxDao.requeueForRetry(row, now) }, outboxScheduler)",
            ),
            codeLines(retryOutboxSource()),
        )
    }

    /**
     * Source lint on `OutboxScreen`, which nothing here can render: the Retry button sits directly
     * under the `canRetry` guard, asked about the row's own state.
     */
    @Test fun `the Retry button is offered only under canRetry`() {
        val lines = codeLines(locate("app/src/main/kotlin/app/sterna/ui/outbox/OutboxScreen.kt").readText())
        val button = lines.indexOf("TextButton(onClick = { viewModel.retry(item.id) }) {")
        assertTrue("the Retry button line must be there, whole", button > 0)
        assertEquals(
            "the line above the Retry button must be the whole guard: a literal or another row's " +
                "state in its place offers Retry on a row being delivered",
            "if (OutboxLogic.canRetry(item.state)) {",
            lines[button - 1],
        )
        assertEquals(
            "and the guard must close right after the button's label, around nothing else",
            listOf("Text(stringResource(R.string.outbox_retry))", "}", "}"),
            lines.subList(button + 1, button + 4),
        )
    }

    // -- the statement, as shipped -----------------------------------------------------------------

    /** Runs the shipped `OutboxDao.requeueForRetry`, bound by name, and answers the rows written. */
    private fun requeue(id: Long, now: Long): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("OutboxDao", "requeueForRetry"))
        assertEquals("requeueForRetry must bind exactly the row and the clock", setOf("id", "now"), order.toSet())
        return db.prepareStatement(sql).use { st ->
            order.forEachIndexed { i, name -> st.setLong(i + 1, if (name == "id") id else now) }
            st.executeUpdate()
        }
    }

    // -- the fixture -------------------------------------------------------------------------------

    private data class Row(val state: OutboxState, val attemptCount: Int, val lastError: String?, val notBefore: Long)

    private var rows = 0L

    private fun insert(state: OutboxState, attemptCount: Int, lastError: String?): Long {
        rows++
        db.prepareStatement(
            "INSERT INTO `outbox`(accountId, recipients, subject, textBody, attachmentsJson, " +
                "createdAtMillis, notBeforeMillis, state, attemptCount, lastError) " +
                "VALUES ('a', 'to@example.org', 's', 'b', '[]', ?, ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, rows)
            st.setLong(2, ROW_DEADLINE)
            st.setString(3, state.name)
            st.setInt(4, attemptCount)
            st.setString(5, lastError)
            st.executeUpdate()
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    private fun read(id: Long): Row = db.prepareStatement(
        "SELECT state, attemptCount, lastError, notBeforeMillis FROM `outbox` WHERE id = ?",
    ).use { st ->
        st.setLong(1, id)
        st.executeQuery().use { rs ->
            rs.next()
            Row(OutboxState.valueOf(rs.getString(1)), rs.getInt(2), rs.getString(3), rs.getLong(4))
        }
    }

    // -- source ------------------------------------------------------------------------------------

    private fun retryOutboxSource(): String {
        val lines = DaoQuerySource.mailSource("MailSender").lines()
        val start = lines.indexOfFirst { it.trim().startsWith("suspend fun retryOutbox(") }
        check(start >= 0) { "retryOutbox is gone from MailSender" }
        return lines.drop(start).takeWhile { it.isNotBlank() }.joinToString("\n")
    }

    /** Every code line of [text], comments and blanks dropped, whitespace normalised. */
    private fun codeLines(text: String): List<String> =
        text.lines()
            .filterNot { it.trim().startsWith("//") || it.trim().startsWith("*") || it.trim().startsWith("/*") }
            .map { it.substringBefore("//").replace(Regex("""\s+"""), " ").trim() }
            .filter { it.isNotEmpty() }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val ROW_DEADLINE = 1_000L
    }
}

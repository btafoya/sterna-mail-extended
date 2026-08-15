package app.sterna.core.data.db

import app.sterna.core.data.mail.DaoQuerySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * [LocalDraftDao]'s statements, read out of the shipped DAO and RUN against the table
 * [LOCAL_DRAFTS_CREATE_SQL] builds — not against a copy of either.
 *
 * ⛔ What this is for: the account scope. Ids collide between two accounts of one server (#31), and
 * a background write that outlived its account is what #121 was. Here the victim would be text the
 * user typed and that exists nowhere else, so every statement is executed with a second account's
 * row sitting next to the first, and that row has to be there afterwards.
 */
class LocalDraftDaoSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate(LOCAL_DRAFTS_CREATE_SQL) }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        accountId: String,
        id: String,
        state: LocalDraftState = LocalDraftState.PENDING,
        updatedAtMillis: Long = 1,
        createdAtMillis: Long = 1,
        notBeforeMillis: Long = 0,
        textBody: String = "text of $id",
    ) {
        db.prepareStatement(
            "INSERT INTO local_drafts (accountId, id, messageId, toAddresses, subject, textBody, " +
                "attachmentsJson, bodyIsLossy, requestReceipt, createdAtMillis, updatedAtMillis, " +
                "notBeforeMillis, attemptCount, state) " +
                "VALUES (?, ?, 'mid@example.org', 'bob@example.org', 'Half written', ?, '[]', 0, 0, " +
                "?, ?, ?, 0, ?)",
        ).use {
            it.setString(1, accountId); it.setString(2, id); it.setString(3, textBody)
            it.setLong(4, createdAtMillis); it.setLong(5, updatedAtMillis)
            it.setLong(6, notBeforeMillis); it.setString(7, state.name)
            it.executeUpdate()
        }
    }

    /** One shipped `@Query` of [LocalDraftDao], bound from [args] and executed. */
    private fun run(function: String, args: Map<String, Any> = emptyMap()): Int {
        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", function))
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setObject(i + 1, args[name] ?: error("nothing to bind for :$name in $statement"))
            }
            ps.executeUpdate()
        }
    }

    /** The same, for a statement that returns rows: the first column of each. */
    private fun read(function: String, args: Map<String, Any> = emptyMap()): List<String> {
        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", function))
        return db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setObject(i + 1, args[name] ?: error("nothing to bind for :$name in $statement"))
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    private fun rows(): List<Pair<String, String>> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT accountId, id FROM local_drafts ORDER BY accountId, id").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }

    private fun stateOf(accountId: String, id: String): String =
        db.prepareStatement("SELECT state FROM local_drafts WHERE accountId = ? AND id = ?").use { ps ->
            ps.setString(1, accountId); ps.setString(2, id)
            ps.executeQuery().use { it.next(); it.getString(1) }
        }

    @Test fun `reading one draft never crosses into the other account's row`() {
        insert("accA", "local-draft:same", textBody = "the first account's sentence")
        insert("accB", "local-draft:same", textBody = "the second account's sentence")

        val (statement, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery("LocalDraftDao", "byId"))
        val text = db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setString(i + 1, if (name == "accountId") "accA" else "local-draft:same")
            }
            ps.executeQuery().use { it.next(); it.getString("textBody") }
        }

        assertEquals("the first account's sentence", text)
    }

    @Test fun `deleting one draft leaves the sibling account's draft of the same id`() {
        insert("accA", "local-draft:same")
        insert("accB", "local-draft:same")

        assertEquals(1, run("deleteById", mapOf("accountId" to "accA", "id" to "local-draft:same")))

        assertEquals(listOf("accB" to "local-draft:same"), rows())
    }

    @Test fun `emptying one account leaves every other account's drafts`() {
        insert("accA", "local-draft:1")
        insert("accA", "local-draft:2")
        insert("accB", "local-draft:3")

        assertEquals(2, run("deleteForAccount", mapOf("accountId" to "accA")))

        assertEquals(listOf("accB" to "local-draft:3"), rows())
    }

    @Test fun `the upload picks up what is waiting and what died mid-upload, never what is open`() {
        insert("accA", "local-draft:waiting", state = LocalDraftState.PENDING)
        insert("accA", "local-draft:interrupted", state = LocalDraftState.UPLOADING)
        insert("accA", "local-draft:being-typed", state = LocalDraftState.EDITING)
        insert("accB", "local-draft:elsewhere", state = LocalDraftState.PENDING)

        // UPLOADING is in: it is what a process killed mid-upload leaves behind, and leaving it out
        // would strand exactly the draft whose fate is least certain. EDITING is out: the composer
        // is holding it (#70's rule). And the other account's is out because the query is scoped.
        assertEquals(
            listOf("local-draft:interrupted", "local-draft:waiting"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to 1_000L)).sorted(),
        )
    }

    @Test fun `a draft whose backoff has not elapsed is not handed to the upload yet`() {
        // The backoff is WRITTEN by recordAttempt and has to be HONOURED here, or every failure is
        // retried immediately: `notBeforeMillis` would be a column nobody reads, and a draft whose
        // server keeps refusing it would be re-uploaded as fast as the worker can loop.
        insert("accA", "local-draft:ready", createdAtMillis = 50, notBeforeMillis = 100)
        insert("accA", "local-draft:backed-off", createdAtMillis = 10, notBeforeMillis = 5_000)

        assertEquals(
            listOf("local-draft:ready"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to 1_000L)),
        )
    }

    @Test fun `a startup can ask for every waiting row, backed off or not`() {
        // How `MailRepository.localDraftsAwaitingUpload` reads the queue: at an instant no backoff
        // can be past. A startup re-arm is the safety net for the work items a reboot lost, so it
        // has to see the rows that are NOT due yet — asked at the current instant, a draft that
        // failed ten minutes before the reboot gets no work item at all and waits for a save that
        // may never come.
        insert("accA", "local-draft:ready", notBeforeMillis = 100)
        insert("accA", "local-draft:backed-off", notBeforeMillis = 4_000_000_000_000)
        insert("accA", "local-draft:being-typed", state = LocalDraftState.EDITING, notBeforeMillis = 0)

        assertEquals(
            "and STILL not the one the composer is holding: the instant is all that is relaxed",
            listOf("local-draft:ready", "local-draft:backed-off"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to Long.MAX_VALUE)),
        )
    }

    @Test fun `the queue comes out in the order the drafts may next be tried`() {
        // ⛔ And the instant comes from the CALLER: a query that reads the clock itself cannot be
        // tested, and this one is bound with a fixed 1000 above and below.
        insert("accA", "local-draft:soonest", createdAtMillis = 900, notBeforeMillis = 10)
        insert("accA", "local-draft:later", createdAtMillis = 100, notBeforeMillis = 20)

        assertEquals(
            listOf("local-draft:soonest", "local-draft:later"),
            read("pending", mapOf("accountId" to "accA", "nowMillis" to 1_000L)),
        )
    }

    @Test fun `startup re-arms the account it was asked about, and leaves the others mid-edit`() {
        // ⛔ The one that matters: a re-arm of account A must not touch account B's EDITING row.
        // That row is a composer the user has open (#70's rule); flipping it to PENDING hands the
        // text she is typing to the upload worker underneath her.
        insert("accA", "local-draft:open", state = LocalDraftState.EDITING)
        insert("accA", "local-draft:interrupted", state = LocalDraftState.UPLOADING)
        insert("accB", "local-draft:being-typed", state = LocalDraftState.EDITING)

        assertEquals(2, run("revertUnfinishedToPending", mapOf("accountId" to "accA")))

        assertEquals("PENDING", stateOf("accA", "local-draft:open"))
        assertEquals("PENDING", stateOf("accA", "local-draft:interrupted"))
        assertEquals("EDITING", stateOf("accB", "local-draft:being-typed"))
        // ⛔ Nothing was deleted on the way: an interrupted draft is requeued, never dropped.
        assertEquals(3, rows().size)
    }

    @Test fun `a failed attempt records its backoff and keeps the draft exactly where it was`() {
        insert("accA", "local-draft:1")
        insert("accB", "local-draft:1")

        val (statement, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("LocalDraftDao", "recordAttempt"),
        )
        db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, "accA")
                    "id" -> ps.setString(i + 1, "local-draft:1")
                    "state" -> ps.setString(i + 1, LocalDraftState.PENDING.name)
                    "attemptCount" -> ps.setInt(i + 1, 9)
                    "lastError" -> ps.setString(i + 1, "no route to host")
                    "lastAttemptMillis" -> ps.setLong(i + 1, 4242)
                    "notBeforeMillis" -> ps.setLong(i + 1, 9999)
                    else -> error("recordAttempt now binds :$name, which this test does not know")
                }
            }
            assertEquals(1, ps.executeUpdate())
        }

        // ⛔ Nine failures and the draft is still PENDING, still there, still whole. There is no
        // attempt cap in this store and no state to be parked in — see [LocalDraftState].
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT state, attemptCount, lastError, notBeforeMillis, textBody FROM local_drafts " +
                    "WHERE accountId = 'accA' AND id = 'local-draft:1'",
            ).use {
                it.next()
                assertEquals("PENDING", it.getString(1))
                assertEquals(9, it.getInt(2))
                assertEquals("no route to host", it.getString(3))
                assertEquals(9999, it.getLong(4))
                assertEquals("text of local-draft:1", it.getString(5))
            }
        }
        assertEquals(0, run("deleteById", mapOf("accountId" to "accC", "id" to "local-draft:1")))
        assertEquals(2, rows().size)
    }
}

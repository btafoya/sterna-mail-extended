package app.sterna.core.data.db

import app.sterna.core.data.mail.DraftSaveOutcome
import app.sterna.core.data.mail.DaoQuerySource
import app.sterna.core.data.mail.draftSaveParksTheQueuedRowAs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * ⭐ The row a "save as a draft" left behind, run against a REAL SQLite engine through the
 * statements the app ships (#95 × #70).
 *
 * The state itself is decided by a pure function ([draftSaveParksTheQueuedRowAs], executed in
 * `LocalDraftSaveTest`) and the screen rules are pure too. What neither can see is SQL: the three
 * outbox statements that run WITHOUT anyone looking — at every launch, before any screen exists —
 * are `@Query` annotations, and an annotation is not callable from a JVM test. They are therefore
 * read out of the shipped [OutboxDao] by [DaoQuerySource] and EXECUTED here, so "the worker never
 * takes this row" is a fact about the statement the phone runs and not about a copy of it.
 *
 * ⛔ The failure this guards is silent and irreversible: the message the user asked to keep as a
 * draft goes out on the next launch, with no undo window, in the version they had BEFORE the edit.
 */
class KeptAsDraftRowTest {

    private lateinit var db: Connection

    /** The state the shipped rule parks a kept-on-device save in — asked, never assumed. */
    private val parked = draftSaveParksTheQueuedRowAs(DraftSaveOutcome.KEPT_ON_DEVICE)
        ?: error("a save kept on the device parks nothing at all — see LocalDraftSaveTest")

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(OUTBOX_CREATE_SQL)
            // The columns later migrations added, so the shipped statements run against the table
            // as it is today rather than as it was at v10.
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpMode` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `pgpEntityPath` TEXT")
            st.executeUpdate("ALTER TABLE `outbox` ADD COLUMN `draftEmailId` TEXT")
        }
    }

    @After fun tearDown() = db.close()

    /**
     * The startup re-arm ([OutboxDao.unfinished], read by `SternaApplication` at every launch and
     * fed straight to the send worker) must not hand this row back.
     *
     * The whole set is asserted, not just the absence of the parked row: `unfinished()` is what
     * puts a message back on the wire, so both directions matter — a state wrongly added here is a
     * send nobody asked for, one wrongly removed is a message that never leaves.
     */
    @Test fun `the startup re-arm never picks up a row saved as a draft`() {
        OutboxState.entries.forEach { insert(it) }

        assertEquals(
            "⛔ only the three states of a message that is still on its way may be re-armed at " +
                "launch. $parked in this set is the reported defect itself: the user tapped 'save " +
                "as a draft' offline, and the next launch delivers the message they had before " +
                "they edited it",
            setOf(OutboxState.HELD, OutboxState.QUEUED, OutboxState.SENDING),
            statesOf(DaoQuerySource.daoQuery("OutboxDao", "unfinished")),
        )
    }

    /**
     * The other startup path: the mid-edit recovery. Both statements only ever name `EDITING`, and
     * that is exactly why parking at SAVE time (rather than leaving the row EDITING and hoping)
     * is what closes the hole — a parked row is no longer EDITING, so neither statement can see it.
     */
    @Test fun `the mid-edit recovery leaves a row saved as a draft exactly where it is`() {
        insert(parked, attemptCount = 0)
        insert(parked, attemptCount = OutboxLogic.MAX_ATTEMPTS)
        insert(OutboxState.EDITING, attemptCount = 0)

        val (exhausted, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("OutboxDao", "revertEditingExhaustedToFailed"),
        )
        assertEquals(listOf("maxAttempts"), order)
        db.prepareStatement(exhausted).use {
            it.setInt(1, OutboxLogic.MAX_ATTEMPTS)
            it.executeUpdate()
        }
        db.createStatement().use { it.executeUpdate(DaoQuerySource.daoQuery("OutboxDao", "revertEditingToQueued")) }

        assertEquals(
            "⛔ startup recovery must touch nothing but the rows a process death stranded in " +
                "EDITING. A $parked row swept into QUEUED — or into FAILED, whatever its attempt " +
                "count — is the pre-edit message sent behind the user's back, or a permanent " +
                "failure banner over a save that worked",
            listOf(parked, parked, OutboxState.QUEUED),
            statesInOrder(),
        )
    }

    // -- the fixture -----------------------------------------------------------------------------

    private var rows = 0L

    private fun insert(state: OutboxState, attemptCount: Int = 0) {
        rows++
        db.prepareStatement(
            "INSERT INTO `outbox`(accountId, recipients, subject, textBody, attachmentsJson, " +
                "createdAtMillis, notBeforeMillis, state, attemptCount) " +
                "VALUES ('a', 'to@example.org', 's', 'b', '[]', ?, ?, ?, ?)",
        ).use { st ->
            st.setLong(1, rows)
            st.setLong(2, rows)
            st.setString(3, state.name)
            st.setInt(4, attemptCount)
            st.executeUpdate()
        }
    }

    /** The distinct states [sql] hands back. */
    private fun statesOf(sql: String): Set<OutboxState> {
        val out = mutableSetOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                while (rs.next()) out += OutboxState.valueOf(rs.getString("state"))
            }
        }
        return out
    }

    /** Every row's state, in insertion order — what the table holds after the recovery has run. */
    private fun statesInOrder(): List<OutboxState> {
        val out = mutableListOf<OutboxState>()
        db.createStatement().use { st ->
            st.executeQuery("SELECT state FROM `outbox` ORDER BY createdAtMillis ASC").use { rs ->
                while (rs.next()) out += OutboxState.valueOf(rs.getString("state"))
            }
        }
        return out
    }
}

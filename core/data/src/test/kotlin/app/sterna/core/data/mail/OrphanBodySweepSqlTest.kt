package app.sterna.core.data.mail

import app.sterna.core.data.db.EMAILS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_BODIES_CREATE_SQL
import app.sterna.core.data.db.LOCAL_DRAFTS_CREATE_SQL
import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * With no cap on the body cache (#157), a body leaves with the last list row of its message in the
 * account, whichever path took the row. The sweep that does it is run here on a real SQLite engine,
 * with the shipped statement and the shipped choice of what it spares.
 */
class OrphanBodySweepSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAIL_BODIES_CREATE_SQL)
            st.executeUpdate(LOCAL_DRAFTS_CREATE_SQL)
        }
    }

    @After fun tearDown() = db.close()

    @Test fun `a body whose message left every list goes, and nothing still listed or about to come back`() {
        // An IMAP move: the message has a new id in Archive, its old id has no row left.
        body("imap:acc:INBOX:7")
        row("imap:acc:Archive:3", ARCHIVE)
        body("imap:acc:Archive:3")
        // A JMAP move: same id, filed under another mailbox.
        row("m-moved", ARCHIVE)
        body("m-moved")
        // Deleted for good on another client, then reconciled away.
        body("m-deleted")
        // A swipe whose undo will write the row back, and an id this phone just changed.
        body("m-swiped")
        body("m-mutated")
        // A draft typed here, whose only copy is `local_drafts`.
        val draftId = LOCAL_DRAFT_ID_PREFIX + "d1"
        localDraft(draftId)
        body(draftId)
        // The same orphan shape in another account.
        body("m-deleted", accountId = OTHER_ACCOUNT)

        sweep(orphanBodySweepSpares(recentlyMutated = listOf("m-mutated"), inFlight = listOf("m-swiped"))!!)

        assertEquals(
            listOf("imap:acc:Archive:3", draftId, "m-moved", "m-mutated", "m-swiped"),
            bodies(),
        )
        assertEquals("another account's bodies are not this sweep's", listOf("m-deleted"), bodies(OTHER_ACCOUNT))
    }

    @Test fun `with nothing to spare the sweep still runs`() {
        row("m-listed", INBOX)
        body("m-listed")
        body("m-deleted")

        sweep(orphanBodySweepSpares(recentlyMutated = emptyList(), inFlight = emptyList())!!)

        assertEquals(listOf("m-listed"), bodies())
    }

    @Test fun `the spares are both kinds once each, and too many skip the sweep`() {
        assertEquals(listOf("a", "b"), orphanBodySweepSpares(listOf("a", "b"), listOf("b"), max = 2))
        assertNull(orphanBodySweepSpares(listOf("a", "b"), listOf("c"), max = 2))
    }

    @Test fun `both refreshes sweep, with the account's own spares`() {
        // Source text as the last resort: `refresh` needs a live server session on either protocol.
        assertEquals(listOf("sweepOrphanBodies(credentials.id)"), linesCalling("refresh", "sweepOrphanBodies("))
        assertEquals(listOf("sweepOrphanBodies(credentials.id)"), linesCalling("refreshImap", "sweepOrphanBodies("))
        val sweep = DaoQuerySource.mailFunctionBody("MailRepository", "sweepOrphanBodies").lines().map { it.trim() }
        listOf(
            "recentlyMutated = recentlyMutatedIds(accountId),",
            "inFlight = inFlightRemovals.values.filter { it.accountId == accountId }.map { it.id },",
            ") ?: return",
            "runCatching { emailBodyDao.deleteOrphans(accountId, spared) }.getOrElseUnlessCancelled {",
        ).forEach { assertEquals("sweepOrphanBodies no longer has the line '$it'", 1, sweep.count { line -> line == it }) }
    }

    // ---- the harness -----------------------------------------------------------------------------

    private fun linesCalling(function: String, call: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function).lines().map { it.trim() }
            .filter { call in it && !it.startsWith("//") }

    private fun row(id: String, mailboxId: String, accountId: String = ACCOUNT) {
        db.prepareStatement(
            "INSERT INTO emails (id, accountId, mailboxId, subject, seen, flagged, hasAttachment, sortKey) " +
                "VALUES (?, ?, ?, 'subject', 1, 0, 0, 1)",
        ).use { it.setString(1, id); it.setString(2, accountId); it.setString(3, mailboxId); it.executeUpdate() }
    }

    private fun body(id: String, accountId: String = ACCOUNT) {
        db.prepareStatement("INSERT INTO email_bodies VALUES (?, ?, '{}', '{}', 1)").use {
            it.setString(1, id); it.setString(2, accountId); it.executeUpdate()
        }
    }

    private fun localDraft(id: String) {
        db.prepareStatement(
            "INSERT INTO local_drafts (accountId, id, messageId, toAddresses, subject, textBody, " +
                "attachmentsJson, bodyIsLossy, requestReceipt, createdAtMillis, updatedAtMillis, " +
                "notBeforeMillis, attemptCount, state) " +
                "VALUES (?, ?, 'mid@example.org', 'bob@example.org', 'Half written', 'six o''clock', '[]', 0, 0, 1, 1, 0, 0, 'PENDING')",
        ).use { it.setString(1, ACCOUNT); it.setString(2, id); it.executeUpdate() }
    }

    private fun bodies(accountId: String = ACCOUNT): List<String> =
        db.prepareStatement("SELECT id FROM email_bodies WHERE accountId = ? ORDER BY id").use { ps ->
            ps.setString(1, accountId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }

    /** `EmailBodyDao.deleteOrphans`'s shipped statement, bound the way Room expands it. */
    private fun sweep(spareIds: List<String>) {
        val (statement, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.daoQuery("EmailBodyDao", "deleteOrphans"),
            mapOf("spareIds" to spareIds.size),
        )
        var spare = 0
        db.prepareStatement(statement).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, ACCOUNT)
                    "spareIds" -> ps.setString(i + 1, spareIds[spare++])
                    else -> error("unexpected parameter :$name")
                }
            }
            ps.executeUpdate()
        }
    }

    private companion object {
        const val ACCOUNT = "acc"
        const val OTHER_ACCOUNT = "acc2"
        const val INBOX = "mb-inbox"
        const val ARCHIVE = "mb-archive"
    }
}

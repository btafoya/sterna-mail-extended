package app.sterna.core.data.mail

import app.sterna.core.data.db.EmailEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.sql.Connection
import java.sql.DriverManager

/**
 * #255, the open side: a search hit opened before the server search removed it. [imapEnvelope]
 * runs for real, and its purge goes through the shipped `EmailFtsDao.deleteById` and
 * `EmailBodyDao.deleteById` statements on in-memory SQLite.
 */
class OpeningAGoneMessageForgetsItSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE VIRTUAL TABLE email_fts USING fts4(" +
                    "emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                    "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey, " +
                    "notindexed=emailId, notindexed=accountId, notindexed=mailboxId, " +
                    "notindexed=threadId, notindexed=preview, notindexed=receivedAt, " +
                    "notindexed=fromName, notindexed=fromEmail, notindexed=seen, " +
                    "notindexed=flagged, notindexed=hasAttachment, notindexed=sortKey, " +
                    "tokenize=unicode61 `remove_diacritics=1`)",
            )
            st.executeUpdate(
                "CREATE TABLE email_bodies(id TEXT NOT NULL, accountId TEXT NOT NULL, bodyJson TEXT NOT NULL, " +
                    "inlineImagesJson TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(accountId, id))",
            )
        }
        // The same id under two accounts (#31), and a neighbour of the opened one.
        listOf("acc" to GONE, "other" to GONE, "acc" to NEIGHBOUR).forEach { (account, id) ->
            index(account, id)
            body(account, id)
        }
    }

    @After fun tearDown() = db.close()

    private fun index(account: String, emailId: String) {
        db.prepareStatement(
            "INSERT INTO email_fts(emailId, accountId, mailboxId, threadId, subject, sender, body, preview, " +
                "receivedAt, fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, 'Archive', NULL, 'subject', 'sender', '', '', '', NULL, NULL, 1, 1, 0, 1)",
        ).use { it.setString(1, emailId); it.setString(2, account); it.executeUpdate() }
    }

    private fun body(account: String, emailId: String) {
        db.prepareStatement("INSERT INTO email_bodies VALUES(?, ?, '{}', '{}', 1)").use {
            it.setString(1, emailId); it.setString(2, account); it.executeUpdate()
        }
    }

    private fun keys(table: String, idColumn: String): List<String> =
        db.createStatement().use { st ->
            st.executeQuery("SELECT accountId, $idColumn FROM $table ORDER BY accountId, $idColumn").use { rs ->
                buildList { while (rs.next()) add("${rs.getString(1)}/${rs.getString(2)}") }
            }
        }

    private fun indexKeys() = keys("email_fts", "emailId")
    private fun bodyKeys() = keys("email_bodies", "id")

    private fun run(daoName: String, functionName: String, accountId: String, emailId: String) {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.daoQuery(daoName, functionName))
        db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (name) {
                    "accountId" -> ps.setString(i + 1, accountId)
                    "emailId", "id" -> ps.setString(i + 1, emailId)
                    else -> error("Unexpected parameter ':$name' in $daoName.$functionName")
                }
            }
            ps.executeUpdate()
        }
    }

    /** What `MailRepository` hands [imapEnvelope] as its purge, statement for statement. */
    private suspend fun forget(accountId: String, emailId: String) {
        forgotten += "$accountId/$emailId"
        run("EmailFtsDao", "deleteById", accountId, emailId)
        run("EmailBodyDao", "deleteById", accountId, emailId)
    }

    private val forgotten = mutableListOf<String>()

    private val untouched = listOf("acc/$GONE", "acc/$NEIGHBOUR", "other/$GONE")

    private suspend fun open(lookUp: suspend () -> ImapLookup, cached: app.sterna.core.jmap.model.Email? = null) =
        imapEnvelope("acc", GONE, cached, lookUp, ::forget)

    private suspend fun unavailable(lookUp: suspend () -> ImapLookup): MessageUnavailableException =
        try {
            open(lookUp)
            fail("an open with nothing to show must raise MessageUnavailableException")
            error("unreachable")
        } catch (thrown: MessageUnavailableException) {
            thrown
        }

    @Test fun aMessageTheServerProvesGoneLeavesTheIndexAndTheBodyCacheOfThisAccountOnly() = runTest {
        val thrown = unavailable { ImapLookup.Gone }

        assertTrue("the open says the server has no such message", thrown.serverHasNoSuchMessage)
        assertEquals("the purge names this account and this id", listOf("acc/$GONE"), forgotten)
        assertEquals(
            "the opened id leaves this account's index; the same id under another account and " +
                "this account's neighbour stay",
            listOf("acc/$NEIGHBOUR", "other/$GONE"),
            indexKeys(),
        )
        assertEquals("and the body cache, by the same key", listOf("acc/$NEIGHBOUR", "other/$GONE"), bodyKeys())
    }

    @Test fun aFetchThatFailedForgetsNothing() = runTest {
        val cut = IOException("connection reset")
        val thrown = unavailable { throw cut }

        assertSame("the failure stays the cause", cut, thrown.cause)
        assertFalse("a failed fetch proves nothing about the message", thrown.serverHasNoSuchMessage)
        assertEquals("no purge is asked for", emptyList<String>(), forgotten)
        assertEquals("a network cut leaves the index alone", untouched, indexKeys())
        assertEquals("and the body cache", untouched, bodyKeys())
    }

    @Test fun anAbsenceTheNumberingCannotVouchForForgetsNothing() = runTest {
        val thrown = unavailable { ImapLookup.Unproven }

        assertFalse(thrown.serverHasNoSuchMessage)
        assertEquals(emptyList<String>(), forgotten)
        assertEquals(untouched, indexKeys())
        assertEquals(untouched, bodyKeys())
    }

    @Test fun aPurgeThatFailsStillRaisesTheUnavailableMessage() = runTest {
        val thrown = try {
            imapEnvelope("acc", GONE, null, { ImapLookup.Gone }) { _, _ -> throw IllegalStateException("database locked") }
            error("unreachable")
        } catch (thrown: MessageUnavailableException) {
            thrown
        }
        assertTrue(thrown.serverHasNoSuchMessage)
    }

    @Test fun aPurgeCancelledMidwayIsRethrownAsItIs() = runTest {
        val cancelled = CancellationException("account removed")
        try {
            imapEnvelope("acc", GONE, null, { ImapLookup.Gone }) { _, _ -> throw cancelled }
            fail("the cancellation must propagate, not the unavailable message")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
    }

    @Test fun aCancelledFetchIsRethrownAsItIsAndForgetsNothing() = runTest {
        val cancelled = CancellationException("left the screen")
        try {
            open({ throw cancelled })
            fail("the cancellation must propagate")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
        assertEquals(emptyList<String>(), forgotten)
        assertEquals(untouched, indexKeys())
    }

    @Test fun aCachedEnvelopeIsAnsweredWithoutAskingTheServer() = runTest {
        val cachedEmail = row().toEmail()
        var asked = false
        val envelope = open({ asked = true; ImapLookup.Gone }, cached = cachedEmail)

        assertSame(cachedEmail, envelope)
        assertFalse("the server is the fallback for a cache miss only", asked)
        assertEquals(untouched, indexKeys())
    }

    @Test fun aFoundEnvelopeIsShownAndForgetsNothing() = runTest {
        val envelope = open({ ImapLookup.Found(row()) })

        assertEquals(GONE, envelope.id)
        assertEquals("Quarterly figures", envelope.subject)
        assertEquals(emptyList<String>(), forgotten)
        assertEquals(untouched, indexKeys())
    }

    private fun row() = EmailEntity(
        id = GONE, accountId = "acc", mailboxId = "Archive", threadId = null, subject = "Quarterly figures",
        preview = null, receivedAt = null, fromName = null, fromEmail = "dana@example.org",
        seen = true, flagged = true, hasAttachment = false, sortKey = 1L,
    )

    private companion object {
        const val GONE = "imap:acc:Archive:4242"
        const val NEIGHBOUR = "imap:acc:Archive:4243"
    }
}

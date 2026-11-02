package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A server that goes mute in the middle of reading a message (#157): `fetchSource` names no budget,
 * so what brings it back is `ImapClient`'s read floor, injected short here, and what the reader then
 * gets is the replay on a fresh connection, or `MessageUnavailableException`.
 */
class ImapMuteServerReadFloorTest {

    @Test(timeout = 30_000)
    fun `a source read nobody bounded comes back from a mute server, after one replay on a fresh connection`() {
        ScriptedImapServer(serving { "" }).use { server ->
            val service = service()
            val outcome = onItsOwnThread { service.fetchSource(credentials(server), INBOX, UID) }

            val back = outcome.done.await(10, TimeUnit.SECONDS)
            assertTrue(
                "fetchSource names no budget; against a server that never answers its UID FETCH it " +
                    "must still come back, in the ${FLOOR_MS}ms read floor (twice, with the replay). " +
                    "With soTimeout 0 it waits until TCP gives up, the account mutex held. Commands: ${server.issued()}",
                back,
            )
            assertTrue("as a timeout, got: ${outcome.thrown}", outcome.thrown is SocketTimeoutException)
            assertEquals(
                "the mute session is dropped and the read replayed once, on a connection of its own",
                2,
                server.connections.get(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a pooled session gone mute is replaced, and the read succeeds on the fresh connection`() {
        val fetches = AtomicInteger(0)
        // The first fetch pools the session; the second, on that same session, meets silence; the
        // third is the replay, on a new connection, and is answered.
        ScriptedImapServer(serving { answer -> if (fetches.incrementAndGet() == 2) "" else answer }).use { server ->
            val service = service()
            runBlocking { service.fetchSource(credentials(server), INBOX, UID) }
            assertEquals("precondition: one connection, now pooled", 1, server.connections.get())

            val outcome = onItsOwnThread { service.fetchSource(credentials(server), INBOX, UID) }

            val back = outcome.done.await(10, TimeUnit.SECONDS)
            assertTrue(
                "the pooled session went mute under a read nobody bounded: the read floor must end " +
                    "the wait so the replay can run. Commands: ${server.issued()}",
                back,
            )
            assertEquals("the replay's answer, not a failure", null, outcome.thrown)
            assertTrue("the source the fresh connection served, got: ${outcome.value}", SOURCE == outcome.value)
            assertEquals("the mute session was replaced by a fresh connection", 2, server.connections.get())
        }
    }

    @Test(timeout = 30_000)
    fun `a source read that expires opens as unavailable, not as the socket's text`() {
        // Under an ambient budget, so what is tested is the translation alone, whichever bound
        // expires: both arrive as the SocketTimeoutException `runWithRetry` rethrows.
        ScriptedImapServer(serving { "" }).use { server ->
            val repository = repository()
            val outcome = onItsOwnThread(ImapReadBudget(BUDGET_MS)) {
                repository.fetchEmail(credentials(server), ID)
            }

            val back = outcome.done.await(10, TimeUnit.SECONDS)
            assertTrue("the open must come back. Commands: ${server.issued()}", back)
            val thrown = outcome.thrown
            assertTrue(
                "an open whose source read expired must raise MessageUnavailableException, the type " +
                    "the reader turns into its `unavailable` sentence; anything else shows its own " +
                    "message on screen. Got: $thrown",
                thrown is MessageUnavailableException,
            )
            assertTrue("with the expiry as its cause, for the log. Got: ${thrown?.cause}", thrown?.cause is SocketTimeoutException)
            assertEquals("the server keeps the message: nothing to forget", false, (thrown as MessageUnavailableException).serverHasNoSuchMessage)
        }
    }

    // ---- the harness --------------------------------------------------------------------------------

    private class Outcome(val done: CountDownLatch) {
        @Volatile var thrown: Throwable? = null
        @Volatile var value: Any? = null
    }

    /** On a daemon thread: a call that never comes back must fail the assertion, not park the suite. */
    private fun onItsOwnThread(context: CoroutineContext = EmptyCoroutineContext, call: suspend () -> Any?): Outcome {
        val outcome = Outcome(CountDownLatch(1))
        Thread {
            runCatching { runBlocking { withContext(context) { call() } } }
                .onSuccess { outcome.value = it }
                .onFailure { outcome.thrown = it }
            outcome.done.countDown()
        }.apply { isDaemon = true }.start()
        return outcome
    }

    /** A server that serves [SOURCE] for [UID], each `UID FETCH` answer passed through [fetch]. */
    private fun serving(fetch: (answer: String) -> String): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line == "UID FETCH $UID (BODY.PEEK[])" ->
                fetch("* 1 FETCH (UID $UID BODY[] {${SOURCE.length}}\r\n$SOURCE)\r\n$tag OK fetch completed\r\n")
            else -> "$tag OK done\r\n"
        }
    }

    private fun service() = ImapMailService(ImapClient(readFloorMs = FLOOR_MS), SmtpClient(), unusedTokenRefresher())

    private val row = EmailEntity(
        id = ID,
        accountId = ACCOUNT,
        mailboxId = INBOX,
        threadId = "t-1",
        subject = "Six o'clock",
        preview = "",
        receivedAt = "2026-09-30T10:00:00Z",
        fromName = null,
        fromEmail = "bob@example.org",
        seen = true,
        flagged = false,
        hasAttachment = false,
        sortKey = 1L,
    )

    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "emailsByIds" -> listOf(row)
            "toString" -> "scripted EmailDao"
            else -> error("EmailDao.${method.name} was not expected on the read path.")
        }
    } as EmailDao

    private fun repository() = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = unusableDao<MailboxDao>(),
        imap = service(),
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        snoozedDao = unusableDao<SnoozedDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        accountStore = RecordingAccountStorage(),
        outboxDao = unusableDao<OutboxDao>(),
        purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        serverContactDao = unusableDao<ServerContactDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
        logWarn = { _, _, _ -> },
    )

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val ACCOUNT = "acc"
        const val INBOX = "INBOX"
        const val UID = 7L
        val ID = ImapMailService.emailId(ACCOUNT, INBOX, UID)
        const val SOURCE = "From: bob@example.org\r\nSubject: Six o'clock\r\n\r\nI will be there at six\r\n"

        /** Short on purpose: the shipped floor is a minute, waited twice with the replay. */
        const val FLOOR_MS = 400
        const val BUDGET_MS = 500

        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

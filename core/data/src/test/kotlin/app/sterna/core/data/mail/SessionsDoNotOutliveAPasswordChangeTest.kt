package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The server sees no more of an account's sessions than it has lanes in use (#157): neither a session
 * under an old password, nor a refused LOGIN, nor one answered after the connect bound may stay open.
 */
class SessionsDoNotOutliveAPasswordChangeTest {

    @Test(timeout = 30_000)
    fun `a refused password and its correction leave at most one open connection per lane`() {
        ScriptedImapServer.perConnection { _, tag, line -> scripted(tag, line) }.use { server ->
            val service = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())
            val good = credentials(server, GOOD)
            val refused = credentials(server, "not-the-password")
            runBlocking {
                service.testConnection(good)
                service.fetchSource(good, INBOX, UID)
            }
            assertEquals("precondition: one session per lane", 2, server.openSockets.get())

            val refusedOpen = runCatching { runBlocking { service.fetchSource(refused, INBOX, UID) } }
            val refusedSync = runCatching { runBlocking { service.testConnection(refused) } }
            assertTrue("precondition: the open under the refused password fails", refusedOpen.isFailure)
            assertTrue("precondition: the sync under the refused password fails", refusedSync.isFailure)

            val source = runBlocking { service.fetchSource(good, INBOX, UID) }
            assertEquals("precondition: the open under the corrected password reads", SOURCE, source)

            // One: the foreground session the corrected open made. The shared lane's last call was the
            // refused sync, so it holds nothing, and its session under the old password was closed.
            assertOpenSettlesTo(1, server, "the foreground session alone")
        }
    }

    @Test(timeout = 30_000)
    fun `a LOGIN answered after the connect bound leaves no connection open`() {
        val release = CountDownLatch(1)
        ScriptedImapServer.perConnection { _, tag, line ->
            if (line.startsWith("LOGIN")) release.await(10, TimeUnit.SECONDS)
            scripted(tag, line)
        }.use { server ->
            val config = MailServerConfig(server.host, server.port, MailSecurity.NONE, "alex@example.org", GOOD)
            val outcome = runCatching { runBlocking { ImapClient().connect(config, connectTimeoutMs = 300) } }
            release.countDown()
            assertTrue("precondition: the connect timed out, got $outcome", outcome.exceptionOrNull() is SocketTimeoutException)
            assertOpenSettlesTo(0, server, "none: the client gave up on that LOGIN")
        }
    }

    private fun assertOpenSettlesTo(expected: Int, server: ScriptedImapServer, what: String) {
        val settledBy = System.currentTimeMillis() + SETTLE_MS
        while (server.openSockets.get() > expected && System.currentTimeMillis() < settledBy) Thread.sleep(20)
        assertEquals(
            "connections still open on the server after ${server.connections.get()} accepted, expected $what. " +
                "Commands by connection: ${server.byConnection.toList()}",
            expected,
            server.openSockets.get(),
        )
    }

    private fun scripted(tag: String, line: String): String = when {
        line.startsWith("LOGIN") ->
            if (line.contains("\"$GOOD\"")) "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            else "$tag NO [AUTHENTICATIONFAILED] Authentication failed.\r\n"
        line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
        line.startsWith("LIST") -> "* LIST (\\HasNoChildren) \"/\" INBOX\r\n$tag OK done\r\n"
        line.startsWith("SELECT") ->
            "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
        line.startsWith("UID FETCH $UID (BODY.PEEK[") ->
            "* 1 FETCH (UID $UID BODY[] {${SOURCE.length}}\r\n$SOURCE)\r\n$tag OK fetch completed\r\n"
        else -> "$tag OK done\r\n"
    }

    private fun credentials(server: ScriptedImapServer, password: String) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = password,
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val GOOD = "secret"
        const val INBOX = "INBOX"
        const val UID = 7L
        const val SOURCE = "From: bob@example.org\r\nSubject: Six o'clock\r\n\r\nI will be there at six\r\n"

        /** Covers the LOGOUT answered by a server thread that has not yet returned, and [closeAside]'s thread. */
        const val SETTLE_MS = 2_000L

        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

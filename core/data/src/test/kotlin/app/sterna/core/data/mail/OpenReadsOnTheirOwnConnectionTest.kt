package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The reads a message being opened waits on run on a foreground connection of their own (#157): a
 * folder walk holds the shared one for as long as it reads, and the reader would queue behind it.
 */
class OpenReadsOnTheirOwnConnectionTest {

    @Test(timeout = 30_000)
    fun `a message opened during a folder walk is read on a second connection, while the walk still reads`() {
        val walkStarted = CountDownLatch(1)
        val walkHeld = CountDownLatch(1)
        ScriptedImapServer.perConnection { _, tag, line ->
            if (line.startsWith("FETCH ")) {
                walkStarted.countDown()
                walkHeld.await(20, TimeUnit.SECONDS)
            }
            scripted(tag, line)
        }.use { server ->
            val service = service()
            try {
                onItsOwnThread {
                    service.loadFolder(credentials(server), INBOX, 50, onPage = {}, cachedPreviewsFor = null)
                }
                val walking = walkStarted.await(10, TimeUnit.SECONDS)
                assertTrue("precondition: the walk reached its FETCH. Commands: ${server.byConnection.toList()}", walking)

                val open = onItsOwnThread { service.fetchSource(credentials(server), INBOX, UID) }
                val back = open.done.await(5, TimeUnit.SECONDS)

                assertTrue(
                    "the open of a message queued behind the folder walk, which holds its connection " +
                        "until its last page. Commands by connection: ${server.byConnection.toList()}",
                    back,
                )
                assertEquals("the source, read while the walk was still held", SOURCE, open.value)
                assertEquals(
                    "the open's SELECT and FETCH, on a second connection accepted during the walk",
                    listOf(true, true),
                    server.issuedOn(2).let { listOf(it.any { c -> c.startsWith("SELECT") }, FETCH_SOURCE in it) },
                )
            } finally {
                walkHeld.countDown()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a foreground session gone silent fails its NOOP under the short bound and is replaced`() {
        val firstSourceServed = AtomicBoolean(false)
        ScriptedImapServer.perConnection { connection, tag, line ->
            if (connection == 1 && firstSourceServed.get()) {
                ""
            } else {
                scripted(tag, line).also { if (connection == 1 && line == FETCH_SOURCE) firstSourceServed.set(true) }
            }
        }.use { server ->
            val service = service(probeMs = PROBE_MS)
            runBlocking { service.fetchSource(credentials(server), INBOX, UID) }
            assertEquals("precondition: one connection, now pooled", 1, server.connections.get())

            val open = onItsOwnThread { service.fetchSource(credentials(server), INBOX, UID) }
            val back = open.done.await(PROBE_MS + 2_500L, TimeUnit.MILLISECONDS)

            assertTrue(
                "a pooled session that stopped answering must be found out by a NOOP bounded at " +
                    "${PROBE_MS}ms and dropped, its LOGOUT off the open's path, well under the shipped " +
                    "${ImapMailService.FOREGROUND_PROBE_MS}ms. Commands by connection: ${server.byConnection.toList()}",
                back,
            )
            assertEquals("the source the fresh connection served, got ${open.thrown}", SOURCE, open.value)
            assertEquals("the silent session was probed with a NOOP", true, "NOOP" in server.issuedOn(1))
            assertEquals("and replaced by a fresh connection", 2, server.connections.get())
        }
    }

    @Test(timeout = 30_000)
    fun `disconnect closes the foreground connection as well as the shared one`() {
        ScriptedImapServer.perConnection { _, tag, line -> scripted(tag, line) }.use { server ->
            val service = service()
            runBlocking {
                service.fetchSource(credentials(server), INBOX, UID)
                service.testConnection(credentials(server))
                service.disconnect(ACCOUNT)
            }

            assertEquals(
                "a LOGOUT on each of the account's two connections. Commands by connection: ${server.byConnection.toList()}",
                listOf(1, 2),
                server.byConnection.toList().filter { it.second.startsWith("LOGOUT") }.map { it.first }.sorted(),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a write stays on the shared connection, not on the open's`() {
        ScriptedImapServer.perConnection { _, tag, line -> scripted(tag, line) }.use { server ->
            val service = service()
            runBlocking {
                service.fetchSource(credentials(server), INBOX, UID)
                service.setFlag(credentials(server), INBOX, UID, "\\Flagged", true)
            }

            assertEquals(
                "the source on the open's connection, the STORE on another. Commands by connection: " +
                    "${server.byConnection.toList()}",
                listOf(listOf(FETCH_SOURCE), listOf(STORE)),
                listOf(server.issuedOn(1).filter { it.startsWith("UID ") }, server.issuedOn(2).filter { it.startsWith("UID ") }),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `the envelope, attachment and source reads of an open all leave the shared connection alone`() {
        ScriptedImapServer.perConnection { _, tag, line -> scripted(tag, line) }.use { server ->
            val service = service()
            runBlocking {
                service.setFlag(credentials(server), INBOX, UID, "\\Flagged", true)
                service.fetchById(credentials(server), ImapMailService.emailId(ACCOUNT, INBOX, UID))
                service.fetchAttachment(credentials(server), INBOX, UID, "1", null)
                service.fetchSource(credentials(server), INBOX, UID)
            }

            assertEquals(
                "the write alone on the shared connection. Commands by connection: ${server.byConnection.toList()}",
                listOf(STORE),
                server.issuedOn(1).filter { it.startsWith("UID ") },
            )
            assertEquals(
                "the three reads on the foreground one, in order",
                listOf("UID FETCH $UID (UID FLAGS", "UID FETCH $UID (BODY.PEEK[1])", FETCH_SOURCE),
                server.issuedOn(2).filter { it.startsWith("UID ") }.map { it.substringBefore(" INTERNALDATE") },
            )
        }
    }

    // ---- the harness --------------------------------------------------------------------------------

    private class Outcome(val done: CountDownLatch) {
        @Volatile var thrown: Throwable? = null
        @Volatile var value: Any? = null
    }

    private fun onItsOwnThread(call: suspend () -> Any?): Outcome {
        val outcome = Outcome(CountDownLatch(1))
        Thread {
            runCatching { runBlocking { call() } }
                .onSuccess { outcome.value = it }
                .onFailure { outcome.thrown = it }
            outcome.done.countDown()
        }.apply { isDaemon = true }.start()
        return outcome
    }

    private fun scripted(tag: String, line: String): String = when {
        line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
        line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
        line.startsWith("LIST") -> "* LIST (\\HasNoChildren) \"/\" INBOX\r\n$tag OK done\r\n"
        line.startsWith("SELECT") ->
            "* 1 EXISTS\r\n* OK [UIDVALIDITY 9] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
        line.startsWith("SEARCH") -> "* SEARCH\r\n$tag OK done\r\n"
        line.startsWith("UID FETCH $UID (BODY.PEEK[") ->
            "* 1 FETCH (UID $UID BODY[] {${SOURCE.length}}\r\n$SOURCE)\r\n$tag OK fetch completed\r\n"
        else -> "$tag OK done\r\n"
    }

    private fun service(probeMs: Int = ImapMailService.FOREGROUND_PROBE_MS) =
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), foregroundProbeMs = probeMs)

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
        const val FETCH_SOURCE = "UID FETCH $UID (BODY.PEEK[])"
        const val STORE = "UID STORE $UID +FLAGS (\\Flagged)"
        const val SOURCE = "From: bob@example.org\r\nSubject: Six o'clock\r\n\r\nI will be there at six\r\n"

        /** Short so the test is quick; the shipped read floor stays a minute, so only the probe can end the wait. */
        const val PROBE_MS = 300

        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

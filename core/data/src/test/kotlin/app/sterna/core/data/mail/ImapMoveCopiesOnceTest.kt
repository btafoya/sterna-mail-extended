package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapMoved
import app.sterna.core.imap.ImapWriteNotReplayable
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a real [ImapMailService] puts on the wire when a move takes the `UID COPY` fallback and
 * then fails: the copy is not idempotent, so the reconnect-and-replay of `runWithRetry` would land a
 * second copy in the destination. Everything before the first `UID COPY` keeps its one replay.
 */
class ImapMoveCopiesOnceTest {

    @Test(timeout = 30_000)
    fun `a refused STORE after the copy fails the move without a second copy`() {
        fallbackServer { tag, line ->
            if (line.startsWith("UID STORE")) "$tag NO [SERVERBUG] cannot flag\r\n" else null
        }.use { server ->
            val outcome = runCatching {
                runBlocking { service().move(credentials(server), INBOX, 7L, ARCHIVE, NUMBERING) }
            }
            assertEquals(
                "one move, one UID COPY: a replay after the copy is a second copy in $ARCHIVE. " +
                    "Commands were: ${server.issued()}",
                1, server.issued("UID COPY").size,
            )
            assertTrue("the move still fails for its caller", outcome.isFailure)
            assertTrue(
                "and says what failed, as before: ${outcome.exceptionOrNull()}",
                outcome.exceptionOrNull()?.message.orEmpty().contains("UID STORE"),
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a refused STORE after the copy fails a batch move without a second copy`() {
        fallbackServer { tag, line ->
            if (line.startsWith("UID STORE")) "$tag NO [SERVERBUG] cannot flag\r\n" else null
        }.use { server ->
            val outcome = runCatching {
                runBlocking {
                    service().moveBatch(credentials(server), INBOX, listOf(7L, 8L, 9L), ARCHIVE, NUMBERING)
                }
            }
            assertEquals(
                "Commands were: ${server.issued()}",
                1, server.issued("UID COPY").size,
            )
            assertTrue(outcome.isFailure)
        }
    }

    @Test(timeout = 30_000)
    fun `a later chunk failing before its own copy does not replay the chunk already copied`() {
        // 250 UIDs is two chunks of the 200-UID cap. The first chunk is copied and flagged; the
        // second dies on its UID MOVE, before any copy of its own.
        val moves = AtomicInteger(0)
        fallbackServer { tag, line ->
            if (line.startsWith("UID MOVE")) {
                if (moves.incrementAndGet() == 2) ScriptedImapServer.HANG_UP else "$tag NO [CANNOT] no move\r\n"
            } else {
                null
            }
        }.use { server ->
            val outcome = runCatching {
                runBlocking {
                    service().moveBatch(credentials(server), INBOX, (1L..250L).toList(), ARCHIVE, NUMBERING)
                }
            }
            assertEquals(
                "the first chunk is copied once and the second never: Commands were: ${server.issued()}",
                listOf("UID COPY 1:200 \"$ARCHIVE\""), server.issued("UID COPY"),
            )
            assertEquals(
                "the second chunk was reached, so the failure is the one after a copy",
                listOf("UID MOVE 1:200 \"$ARCHIVE\"", "UID MOVE 201:250 \"$ARCHIVE\""),
                server.issued("UID MOVE"),
            )
            assertTrue(outcome.isFailure)
        }
    }

    @Test(timeout = 30_000)
    fun `a copy whose answer is lost is not sent again`() {
        // A hang-up right after the command is what a lost answer looks like to the client: the
        // server may have performed the copy.
        fallbackServer { _, line ->
            if (line.startsWith("UID COPY")) ScriptedImapServer.HANG_UP else null
        }.use { server ->
            val outcome = runCatching {
                runBlocking { service().move(credentials(server), INBOX, 7L, ARCHIVE, NUMBERING) }
            }
            assertEquals(
                "Commands were: ${server.issued()}",
                1, server.issued("UID COPY").size,
            )
            assertTrue(outcome.isFailure)
        }
    }

    @Test(timeout = 30_000)
    fun `a connection reset after the copy, a failure that is not an ImapException, is not replayed either`() {
        fallbackServer { _, line ->
            if (line.startsWith("UID STORE")) ScriptedImapServer.RESET else null
        }.use { server ->
            val outcome = runCatching {
                runBlocking { service().move(credentials(server), INBOX, 7L, ARCHIVE, NUMBERING) }
            }
            assertEquals(
                "runWithRetry replays any Throwable, so the SocketException of a reset must be caught as well: " +
                    "Commands were: ${server.issued()}",
                1, server.issued("UID COPY").size,
            )
            val failure = outcome.exceptionOrNull()
            assertTrue("the move fails as not replayable: $failure", failure is ImapWriteNotReplayable)
            assertTrue(
                "over a SocketException, the case a budget timeout cannot stand for, since it spends the " +
                    "deadline and runWithRetry then refuses to reconnect: " +
                    generateSequence(failure) { it.cause }.toList(),
                generateSequence(failure) { it.cause }.any { it is SocketException },
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a session dead before the SELECT is still reconnected and the move goes through`() {
        val selects = AtomicInteger(0)
        fallbackServer { _, line ->
            if (line.startsWith("SELECT") && selects.incrementAndGet() == 1) ScriptedImapServer.HANG_UP else null
        }.use { server ->
            val moved: ImapMoved = runBlocking {
                service().move(credentials(server), INBOX, 7L, ARCHIVE, NUMBERING)
            }
            assertEquals("replayed on a second connection", 2, server.connections.get())
            assertEquals(
                "and copied once, on it. Commands were: ${server.issued()}",
                1, server.issued("UID COPY").size,
            )
            assertEquals(mapOf(7L to 70L), moved.uids)
        }
    }

    @Test(timeout = 30_000)
    fun `a connection lost on the UID MOVE, before any copy, is still replayed`() {
        val moves = AtomicInteger(0)
        fallbackServer { tag, line ->
            if (line.startsWith("UID MOVE")) {
                if (moves.incrementAndGet() == 1) ScriptedImapServer.HANG_UP else "$tag OK [COPYUID 5 7 70] moved\r\n"
            } else {
                null
            }
        }.use { server ->
            val moved: ImapMoved = runBlocking {
                service().move(credentials(server), INBOX, 7L, ARCHIVE, NUMBERING)
            }
            assertEquals("Commands were: ${server.issued()}", 2, server.issued("UID MOVE").size)
            assertEquals("no copy on either connection", 0, server.issued("UID COPY").size)
            assertEquals(mapOf(7L to 70L), moved.uids)
        }
    }

    // ---- the harness --------------------------------------------------------------------------------

    /**
     * A server advertising neither MOVE nor UIDPLUS that refuses `UID MOVE` with a tagged NO: the two
     * conditions of the copy fallback. [override] answers first; `null` falls through.
     */
    private fun fallbackServer(override: (tag: String, line: String) -> String?) = ScriptedImapServer { tag, line ->
        override(tag, line) ?: when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 300 EXISTS\r\n* OK [UIDVALIDITY $NUMBERING] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID MOVE") -> "$tag NO [CANNOT] no move\r\n"
            line.startsWith("UID COPY 7 ") -> "$tag OK [COPYUID 5 7 70] copied\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private fun service() = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher())

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val INBOX = "INBOX"
        const val ARCHIVE = "Archive"
        const val NUMBERING = 9L

        /** The same never-built [OAuthTokenRefresher] as `ImapDraftAppendGoesOutOnceTest`'s. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

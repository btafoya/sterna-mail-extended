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
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A purge on a server without UIDPLUS that stopped between its `UID STORE` and its `EXPUNGE` must not
 * leave the folder unpurgeable for good: what Sterna flagged earlier is remembered, by a real
 * [ImapMailService], and counts as its own in the next purge of that folder, under that numbering
 * only (#99). Each call below builds a fresh service, a new connection with nothing in memory but
 * the [PendingExpungeStore], which is what a restarted process has.
 */
class PurgeWithoutUidPlusResumesTest {

    private val pending = InMemoryPendingExpunges()

    @Test(timeout = 30_000)
    fun `a purge killed before its EXPUNGE is finished by the next move out of the folder`() {
        noUidPlusServer().use { server ->
            server.searchHangsUp.set(true)
            val killed = runCatching { once { deleteBatch(credentials(server), TRASH, listOf(1L, 2L, 3L), NUMBERING) } }
            assertTrue("the first purge must die before its EXPUNGE, or this measures nothing", killed.isFailure)
            assertEquals(setOf(1L, 2L, 3L), server.flagged())
            assertEquals("the UIDs were written down before the STORE", setOf(1L, 2L, 3L), pending.under(ACCOUNT, TRASH, NUMBERING))
            server.searchHangsUp.set(false)

            once { moveBatch(credentials(server), TRASH, listOf(10L, 11L), ARCHIVE, NUMBERING) }

            assertEquals("exactly one EXPUNGE: ${server.issued()}", 1, server.bareExpunges())
            assertEquals("both purges erased", emptySet<Long>(), server.flagged())
            assertEquals("and nothing left to purge later", emptySet<Long>(), pending.under(ACCOUNT, TRASH, NUMBERING))
        }
    }

    @Test(timeout = 30_000)
    fun `a purge refused for a foreign flag is finished once that flag is gone`() {
        noUidPlusServer(flaggedByOthers = setOf(9_000L)).use { server ->
            once { deleteBatch(credentials(server), TRASH, listOf(1L, 2L), NUMBERING) }
            assertEquals("refused, 9000 is not ours: ${server.issued()}", 0, server.bareExpunges())
            assertEquals("no EXPUNGE, so the record stays", setOf(1L, 2L), pending.under(ACCOUNT, TRASH, NUMBERING))

            server.unflag(9_000L)
            once { deleteBatch(credentials(server), TRASH, listOf(3L), NUMBERING) }

            assertEquals("1 and 2 are ours from the earlier purge: ${server.issued()}", 1, server.bareExpunges())
            assertEquals(emptySet<Long>(), server.flagged())
            assertEquals(emptySet<Long>(), pending.under(ACCOUNT, TRASH, NUMBERING))
        }
    }

    /** The #99 counter-witness: remembering our own flags must not make somebody else's ours. */
    @Test(timeout = 30_000)
    fun `a message flagged by another client still stops the purge when earlier flags of ours are remembered`() {
        noUidPlusServer(flaggedByOthers = setOf(9_000L)).use { server ->
            server.searchHangsUp.set(true)
            runCatching { once { deleteBatch(credentials(server), TRASH, listOf(1L, 2L), NUMBERING) } }
            server.searchHangsUp.set(false)

            once { moveBatch(credentials(server), TRASH, listOf(10L), ARCHIVE, NUMBERING) }

            assertEquals("a bare EXPUNGE would have destroyed 9000: ${server.issued()}", 0, server.bareExpunges())
            assertEquals(setOf(1L, 2L, 10L, 9_000L), server.flagged())
            assertEquals(setOf(1L, 2L, 10L), pending.under(ACCOUNT, TRASH, NUMBERING))
        }
    }

    /** After a renumbering, the UIDs remembered under the old numbering name other messages. */
    @Test(timeout = 30_000)
    fun `UIDs remembered under another numbering do not count as ours`() {
        noUidPlusServer().use { server ->
            server.searchHangsUp.set(true)
            runCatching { once { deleteBatch(credentials(server), TRASH, listOf(1L, 2L, 3L), NUMBERING) } }
            server.searchHangsUp.set(false)
            assertEquals(setOf(1L, 2L, 3L), pending.under(ACCOUNT, TRASH, NUMBERING))
            // Renumbered: 1, 2 and 3 are now other messages, flagged by somebody else.
            server.numbering.set(RENUMBERED)

            once { moveBatch(credentials(server), TRASH, listOf(10L, 11L), ARCHIVE, RENUMBERED) }

            assertEquals("the old record counted under the new numbering: ${server.issued()}", 0, server.bareExpunges())
            assertEquals(setOf(1L, 2L, 3L, 10L, 11L), server.flagged())
            assertEquals(setOf(10L, 11L), pending.under(ACCOUNT, TRASH, RENUMBERED))
        }
    }

    @Test fun `a renumbering forgets what the folder held for a later purge, and only that folder`() {
        runBlocking {
            pending.remember(ACCOUNT, TRASH, NUMBERING, listOf(1L, 2L))
            pending.remember(ACCOUNT, ARCHIVE, NUMBERING, listOf(5L))
            MailboxUidValidityStore(permissive(), permissive(), permissive(), pending)
                .invalidate(ACCOUNT, TRASH, RENUMBERED)
        }

        assertEquals(emptySet<Long>(), pending.under(ACCOUNT, TRASH, NUMBERING))
        assertEquals(setOf(5L), pending.under(ACCOUNT, ARCHIVE, NUMBERING))
    }

    // ---- the harness --------------------------------------------------------------------------------

    /** A DAO that accepts every call and answers nothing: only the store under test is observed. */
    private inline fun <reified T : Any> permissive(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> Unit } as T

    /**
     * Neither UIDPLUS nor MOVE, so a move takes the copy fallback, and a server that keeps its
     * `\Deleted` flags across connections, as a real one does.
     */
    private class NoUidPlusServer(flaggedByOthers: Set<Long>) {
        val flaggedOnServer: MutableSet<Long> = Collections.synchronizedSet(sortedSetOf<Long>().apply { addAll(flaggedByOthers) })
        val searchHangsUp = AtomicBoolean(false)
        val numbering = AtomicLong(NUMBERING)
        lateinit var wire: ScriptedImapServer

        fun flagged(): Set<Long> = flaggedOnServer.toSet()
        fun unflag(uid: Long) { flaggedOnServer.remove(uid) }
        fun issued() = wire.issued()
        fun bareExpunges() = wire.issued().count { it == "EXPUNGE" }
        fun use(block: (NoUidPlusServer) -> Unit) = wire.use { block(this) }
    }

    private fun noUidPlusServer(flaggedByOthers: Set<Long> = emptySet()): NoUidPlusServer {
        val state = NoUidPlusServer(flaggedByOthers)
        state.wire = ScriptedImapServer { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1\r\n$tag OK done\r\n"
                line.startsWith("SELECT") ->
                    "* 300 EXISTS\r\n* OK [UIDVALIDITY ${state.numbering.get()}] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID STORE") -> {
                    if (line.contains("+FLAGS (\\Deleted)")) state.flaggedOnServer += expand(line.removePrefix("UID STORE ").substringBefore(' '))
                    "$tag OK done\r\n"
                }
                line.startsWith("UID SEARCH DELETED") ->
                    if (state.searchHangsUp.get()) ScriptedImapServer.HANG_UP
                    else "* SEARCH ${state.flagged().joinToString(" ")}\r\n$tag OK search completed\r\n"
                line == "EXPUNGE" -> { state.flaggedOnServer.clear(); "$tag OK done\r\n" }
                line.startsWith("UID EXPUNGE") -> "$tag BAD unknown command\r\n"
                line.startsWith("UID MOVE") -> "$tag NO [CANNOT] no move\r\n"
                line.startsWith("UID COPY") -> {
                    val set = line.removePrefix("UID COPY ").substringBefore(' ')
                    "$tag OK [COPYUID 5 $set $set] copied\r\n"
                }
                else -> "$tag OK done\r\n"
            }
        }
        return state
    }

    private fun expand(set: String): List<Long> = set.split(',').flatMap { part ->
        val (from, to) = if (':' in part) part.split(':').map { it.toLong() } else listOf(part.toLong(), part.toLong())
        (minOf(from, to)..maxOf(from, to)).toList()
    }

    private fun service() = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), pendingExpunges = pending)

    /** One call on a fresh service, whose connection is then closed: the scripted server serves one at a time. */
    private fun <T> once(call: suspend ImapMailService.() -> T): T = runBlocking {
        val service = service()
        try {
            service.call()
        } finally {
            service.disconnect(ACCOUNT)
        }
    }

    private fun credentials(server: NoUidPlusServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.wire.host, server.wire.port, ConnectionSecurity.NONE),
    )

    private companion object {
        const val ACCOUNT = "acc"
        const val TRASH = "Trash"
        const val ARCHIVE = "Archive"
        const val NUMBERING = 9L
        const val RENUMBERED = 10L

        /** The same never-built [OAuthTokenRefresher] as `ImapMoveCopiesOnceTest`'s. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

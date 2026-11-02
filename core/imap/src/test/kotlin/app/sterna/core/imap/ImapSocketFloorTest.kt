package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The read and connect floors of [ImapClient] (#157): a wait nobody named is bounded, a wait somebody
 * named is theirs, shorter or longer than the floor alike. Without them a peer gone mute (NAT entry
 * dropped, network changed) holds the read, and the account's IMAP mutex, until TCP gives up, and a
 * cancellation does not end a blocking read. Never `min` in [timeoutOrFloor]: a caller's long budget
 * is a decision too. The read floor is per read, not a total, so a large `FETCH` that keeps delivering
 * octets is never cut. Floors are injected short here; the shipped minute is never waited out.
 */
class ImapSocketFloorTest {

    @Test fun `a named bound wins over the floor, shorter or longer, and only none named gets the floor`() {
        assertEquals("a caller's short budget is not lengthened", 500, timeoutOrFloor(500, 60_000))
        assertEquals("a caller's long budget is not shortened", 90_000, timeoutOrFloor(90_000, 60_000))
        assertEquals("no budget named: the floor, not a blocking socket", 60_000, timeoutOrFloor(0, 60_000))
        assertEquals("a spent or negative value is no budget either", 30_000, timeoutOrFloor(-1, 30_000))
    }

    @Test fun `the shipped floors are a minute per read and half a minute per connect`() {
        assertEquals(60_000, IMAP_READ_FLOOR_MS)
        assertEquals(30_000, IMAP_CONNECT_FLOOR_MS)
    }

    @Test(timeout = 30_000)
    fun `a client and a session built with the defaults arm the shipped floors`() {
        // Every other test here injects its floor, so this is the one that sees a default of 0
        // slip into `ImapClient` or `ImapSession`: soTimeout is read, nothing is waited out.
        FakeImapServer { tag, _ -> ok(tag) }.use { server ->
            val session = server.session()
            val socket = ImapSession::class.java.getDeclaredField("socket").apply { isAccessible = true }
            assertEquals(
                "ImapClient() then openSession: a read nobody bounded must be armed at the shipped floor",
                IMAP_READ_FLOOR_MS,
                session.withReadTimeout(0) { (socket.get(session) as Socket).soTimeout },
            )
        }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { plain ->
                assertEquals(
                    "ImapSession(socket) alone: the same floor by default",
                    IMAP_READ_FLOOR_MS,
                    ImapSession(plain).withReadTimeout(0) { plain.soTimeout },
                )
            }
        }
        val connectFloor = ImapClient::class.java.getDeclaredField("connectFloorMs").apply { isAccessible = true }
        assertEquals(
            "ImapClient(): the connect floor it hands `timeoutOrFloor` by default",
            IMAP_CONNECT_FLOOR_MS,
            connectFloor.get(ImapClient()),
        )
    }

    @Test(timeout = 30_000)
    fun `a read under no named bound comes back from a mute server in the floor`() {
        FakeImapServer { tag, line -> if (line.startsWith("SELECT")) "" else ok(tag) }.use { server ->
            val session = ImapClient(readFloorMs = FLOOR_MS).openSession(server.config)
            val outcome = onItsOwnThread { session.withReadTimeout(0) { session.select("INBOX") } }

            assertTrue(
                "a SELECT nobody bounded, to a server that never answers, must come back on its own " +
                    "within the ${FLOOR_MS}ms floor: soTimeout 0 holds the read until TCP gives up",
                outcome.done.await(5, TimeUnit.SECONDS),
            )
            assertTrue("as a timeout, got: ${outcome.thrown}", outcome.thrown is SocketTimeoutException)
        }
    }

    @Test(timeout = 30_000)
    fun `a named bound shorter than the floor is not lengthened to it`() {
        FakeImapServer { tag, line -> if (line.startsWith("SELECT")) "" else ok(tag) }.use { server ->
            val session = ImapClient(readFloorMs = 20_000).openSession(server.config)
            val outcome = onItsOwnThread { session.withReadTimeout(300) { session.select("INBOX") } }

            assertTrue(
                "300ms named, a 20s floor: the call must be back long before the floor",
                outcome.done.await(5, TimeUnit.SECONDS),
            )
            assertTrue("as a timeout, got: ${outcome.thrown}", outcome.thrown is SocketTimeoutException)
        }
    }

    @Test(timeout = 30_000)
    fun `a named bound longer than the floor is not shortened to it`() {
        FakeImapServer { tag, line ->
            if (line.startsWith("SELECT")) { Thread.sleep(700); selectResponse(tag, exists = 3) } else ok(tag)
        }.use { server ->
            val session = ImapClient(readFloorMs = 200).openSession(server.config)

            assertEquals(
                "a 700ms answer under a named 5s bound must be waited for, whatever the 200ms floor",
                3,
                session.withReadTimeout(5_000) { session.select("Trash") }.exists,
            )
        }
    }

    @Test(timeout = 30_000)
    fun `a connect under no named bound gives up in the connect floor`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val fillers = mutableListOf<Socket>()
            try {
                // Nobody accepts, so the kernel's accept queue fills and from then on it drops the
                // SYN: a peer that never answers the connect, the state the floor exists for.
                var dropping = false
                while (!dropping && fillers.size < 16) {
                    val probe = Socket()
                    try {
                        probe.connect(listener.localSocketAddress, 300)
                        fillers += probe
                    } catch (_: SocketTimeoutException) {
                        probe.close()
                        dropping = true
                    }
                }
                assertTrue("precondition: the listener never started dropping connects", dropping)

                val config = MailServerConfig(
                    host = listener.inetAddress.hostAddress,
                    port = listener.localPort,
                    security = MailSecurity.NONE,
                    username = "tester",
                    password = "secret",
                )
                val outcome = onItsOwnThread { ImapClient(connectFloorMs = FLOOR_MS).openSession(config, 0) }

                assertTrue(
                    "a connect nobody bounded, to a peer that drops the SYN, must give up within the " +
                        "${FLOOR_MS}ms connect floor, not after the kernel's SYN retries",
                    outcome.done.await(5, TimeUnit.SECONDS),
                )
                assertTrue("as a timeout, got: ${outcome.thrown}", outcome.thrown is SocketTimeoutException)
            } finally {
                fillers.forEach { runCatching { it.close() } }
            }
        }
    }

    private class Outcome(val done: CountDownLatch) {
        @Volatile var thrown: Throwable? = null
    }

    /** On a daemon thread: what is under test is whether the call comes back, and one that does
     *  not must fail the assertion, not park the suite. */
    private fun onItsOwnThread(call: () -> Unit): Outcome {
        val outcome = Outcome(CountDownLatch(1))
        Thread {
            outcome.thrown = runCatching { call() }.exceptionOrNull()
            outcome.done.countDown()
        }.apply { isDaemon = true }.start()
        return outcome
    }

    private companion object {
        const val FLOOR_MS = 400
    }
}

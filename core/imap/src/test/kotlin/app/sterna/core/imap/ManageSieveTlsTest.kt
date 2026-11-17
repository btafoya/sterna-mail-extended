package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

/**
 * The handshaking transport against [FakeSieveServer] doing TLS for real. Octets left in the clear
 * between the `OK` to `STARTTLS` and the handshake break only a real handshake, which reads them as
 * a TLS record.
 */
class ManageSieveTlsTest {

    private val scripts = mapOf("sterna" to "keep;", "vacation" to "keep;")
    private val listed = listOf(SieveScriptInfo("sterna", false), SieveScriptInfo("vacation", true))

    @Test
    fun `capabilities written in the clear after the OK to STARTTLS, in a later segment, are dropped before the handshake`() {
        FakeSieveServer(
            scripts = scripts,
            active = "vacation",
            tls = true,
            reissueAfterStartTls = false,
            clearAfterStartTls = listOf(100L to FakeSieveServer.TLS_CAPABILITIES + "OK \"TLS negotiation\""),
        ).use { server ->
            assertEquals(listed, server.tlsSession { listScripts() })
            assertEquals(listOf("STARTTLS", "CAPABILITY", "NOOP", "AUTHENTICATE", "LISTSCRIPTS", "LOGOUT"), server.verbs())
        }
    }

    @Test
    fun `a clear response whose end comes after the first-octet delay is still read whole`() {
        FakeSieveServer(
            scripts = scripts,
            active = "vacation",
            tls = true,
            reissueAfterStartTls = false,
            clearAfterStartTls = listOf(
                100L to FakeSieveServer.TLS_CAPABILITIES,
                3L * MANAGESIEVE_CLEAR_AFTER_STARTTLS_MS to listOf("OK \"TLS negotiation\""),
            ),
        ).use { server ->
            assertEquals(listed, server.tlsSession(commandTimeoutMs = 5_000) { listScripts() })
        }
    }

    @Test
    fun `a server that writes nothing in the clear and re-issues over TLS opens the session`() {
        FakeSieveServer(scripts = scripts, active = "vacation", tls = true).use { server ->
            assertEquals(listed, server.tlsSession { listScripts() })
            assertEquals(listOf("STARTTLS", "CAPABILITY", "NOOP", "AUTHENTICATE", "LISTSCRIPTS", "LOGOUT"), server.verbs())
        }
    }

    @Test
    fun `a server that writes nothing in the clear is waited for the first-octet delay only, not the command timeout`() {
        FakeSieveServer(scripts = scripts, active = "vacation", tls = true).use { server ->
            // A third of what a wait on `commandTimeout` would cost, and still well
            // over a loaded machine's first TLS handshake in this JVM.
            val boundMs = MANAGESIEVE_COMMAND_TIMEOUT_MS / 3
            val started = System.nanoTime()
            val result = server.tlsSession(commandTimeoutMs = MANAGESIEVE_COMMAND_TIMEOUT_MS) { listScripts() }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            assertEquals(listed, result)
            assertTrue("the session took $elapsedMs ms, bound $boundMs ms", elapsedMs < boundMs)
        }
    }

    @Test
    fun `after the first-octet delay, an encrypted answer slower than that delay is still waited for`() {
        val slowMs = 2L * MANAGESIEVE_CLEAR_AFTER_STARTTLS_MS
        FakeSieveServer(
            scripts = scripts,
            active = "vacation",
            tls = true,
            respond = { if (it.verb == "LISTSCRIPTS") null.also { Thread.sleep(slowMs) } else null },
        ).use { server ->
            assertEquals(listed, server.tlsSession(commandTimeoutMs = 5_000) { listScripts() })
        }
    }

    @Test
    fun `the public constructor's factory does not trust the test certificate, and the handshake it refuses is unreachable`() {
        FakeSieveServer(scripts = scripts, tls = true).use { server ->
            val error = assertThrows(ManageSieveException::class.java) {
                runBlocking {
                    ManageSieveClient().session(server.host, server.port, FakeSieveServer.USER, FakeSieveServer.PASSWORD) { listScripts() }
                }
            }

            assertEquals(ManageSieveException.Kind.UNREACHABLE, error.kind)
            assertTrue("not a refused handshake: ${error.cause}", error.cause is SSLHandshakeException)
            assertEquals(listOf("STARTTLS"), server.verbs())
        }
    }
}

package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapException
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The APPEND of a cross-account move on a real [ImapMailService] session, read off the socket: a
 * folder that refuses user keywords still receives the message, with the system flags alone, and
 * nothing else is ever sent twice.
 */
class AppendFallsBackWithoutKeywordsTest {

    private val message = "Subject: moved\r\n\r\nbody\r\n".toByteArray(Charsets.US_ASCII)
    private val labelled = ImapAppendFlags(listOf("\\Seen"), listOf("\$Forwarded", "\$label:red"))
    private val withLabels = "APPEND \"Archive\" (\\Seen \$Forwarded \$label:red) {${message.size}}"
    private val systemOnly = "APPEND \"Archive\" (\\Seen) {${message.size}}"

    private class Outcome(val appends: List<String>, val literals: List<String>, val connections: Int, val result: Result<Long?>)

    /** [complete] answers the n-th APPEND (from 1) once its literal is in; [goAhead] its announcement. */
    private fun appendAgainst(
        flags: ImapAppendFlags,
        goAhead: (n: Int, tag: String) -> String = { _, _ -> "+ go ahead\r\n" },
        complete: (n: Int, tag: String) -> String,
    ): Outcome {
        var announced = 0
        var completed = 0
        val server = ScriptedImapServer(
            goAhead = { tag, _ -> goAhead(++announced, tag) },
        ) { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
                line.startsWith("APPEND") -> complete(++completed, tag)
                else -> "$tag OK done\r\n"
            }
        }
        return server.use {
            val service = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), RecordingAccountStorage()))
            val result = runCatching { runBlocking { service.appendBytes(credentials(it), "Archive", message, flags, null) } }
            Outcome(it.issued("APPEND"), it.literals.toList(), it.connections.get(), result)
        }
    }

    private fun credentials(imap: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(imap.host, imap.port, ConnectionSecurity.NONE),
    )

    private fun uid(n: Long) = { _: Int, tag: String -> "$tag OK [APPENDUID 9 $n] done\r\n" }

    @Test(timeout = 30_000)
    fun `the labels and the forwarded mark go out after the system flags`() {
        val outcome = appendAgainst(labelled, complete = uid(77))
        assertEquals(listOf(withLabels), outcome.appends)
        assertEquals(77L, outcome.result.getOrThrow())
    }

    @Test(timeout = 30_000)
    fun `a tagged NO on completion is followed by one APPEND with the system flags, in the same session`() {
        val outcome = appendAgainst(labelled) { n, tag ->
            if (n == 1) "$tag NO [CANNOT] keywords not permitted\r\n" else "$tag OK [APPENDUID 9 78] done\r\n"
        }
        assertEquals(listOf(withLabels, systemOnly), outcome.appends)
        assertEquals(List(2) { String(message, Charsets.US_ASCII) }, outcome.literals)
        assertEquals(1, outcome.connections)
        assertEquals(78L, outcome.result.getOrThrow())
    }

    @Test(timeout = 30_000)
    fun `a tagged BAD on completion falls back the same way`() {
        val outcome = appendAgainst(labelled) { n, tag ->
            if (n == 1) "$tag BAD invalid flag list\r\n" else "$tag OK [APPENDUID 9 79] done\r\n"
        }
        assertEquals(listOf(withLabels, systemOnly), outcome.appends)
        assertEquals(79L, outcome.result.getOrThrow())
    }

    @Test(timeout = 30_000)
    fun `a tagged NO in place of the go-ahead falls back too, nothing was sent`() {
        val outcome = appendAgainst(
            labelled,
            goAhead = { n, tag -> if (n == 1) "$tag NO [CANNOT] keywords not permitted\r\n" else "+ go ahead\r\n" },
            complete = uid(80),
        )
        assertEquals(listOf(withLabels, systemOnly), outcome.appends)
        assertEquals(1, outcome.literals.size)
        assertEquals(80L, outcome.result.getOrThrow())
    }

    @Test(timeout = 30_000)
    fun `the fallback refused too is the answer, and there is no third APPEND`() {
        val outcome = appendAgainst(labelled) { n, tag -> "$tag NO [OVERQUOTA] refusal $n\r\n" }
        assertEquals(listOf(withLabels, systemOnly), outcome.appends)
        val thrown = outcome.result.exceptionOrNull()
        assertTrue("got $thrown", thrown is ImapException && "refusal 2" in thrown.message.orEmpty())
    }

    @Test(timeout = 30_000)
    fun `with no keyword a NO is the answer, and the APPEND is not sent again`() {
        val outcome = appendAgainst(ImapAppendFlags(listOf("\\Seen"), emptyList())) { _, tag ->
            "$tag NO [OVERQUOTA] mailbox full\r\n"
        }
        assertEquals(listOf(systemOnly), outcome.appends)
        assertTrue("got ${outcome.result}", outcome.result.exceptionOrNull() is ImapException)
    }

    @Test(timeout = 30_000)
    fun `a connection cut after the literal is the answer, the copy may be on B already`() {
        val outcome = appendAgainst(labelled) { _, _ -> ScriptedImapServer.HANG_UP }
        assertEquals(listOf(withLabels), outcome.appends)
        assertEquals(1, outcome.connections)
        assertNotNull(outcome.result.exceptionOrNull())
    }

    /** The connection stays open after the `* BYE`, so a second APPEND in the session would land. */
    @Test(timeout = 30_000)
    fun `an untagged BYE in place of the go-ahead is the answer, nothing is sent again`() {
        val outcome = appendAgainst(
            labelled,
            goAhead = { n, _ -> if (n == 1) "* BYE going away\r\n" else "+ go ahead\r\n" },
            complete = uid(81),
        )
        assertEquals(listOf(withLabels), outcome.appends)
        assertEquals(1, outcome.connections)
        assertTrue("got ${outcome.result}", outcome.result.exceptionOrNull() is ImapException)
    }

    /** An untagged `* NO` is a warning, not the server refusing this APPEND (RFC 3501 §7.1). */
    @Test(timeout = 30_000)
    fun `an untagged NO in place of the go-ahead is the answer, nothing is sent again`() {
        val outcome = appendAgainst(
            labelled,
            goAhead = { n, _ -> if (n == 1) "* NO [ALERT] x\r\n" else "+ go ahead\r\n" },
            complete = uid(82),
        )
        assertEquals(listOf(withLabels), outcome.appends)
        assertEquals(1, outcome.connections)
        assertTrue("got ${outcome.result}", outcome.result.exceptionOrNull() is ImapException)
    }

    @Test(timeout = 30_000)
    fun `a tagged lowercase no on completion falls back like NO`() {
        val outcome = appendAgainst(labelled) { n, tag ->
            if (n == 1) "$tag no keywords not permitted\r\n" else "$tag OK [APPENDUID 9 83] done\r\n"
        }
        assertEquals(listOf(withLabels, systemOnly), outcome.appends)
        assertEquals(83L, outcome.result.getOrThrow())
    }
}

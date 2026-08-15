package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * ⭐ **That the budget installed on the COROUTINE actually reaches the socket** (#95) — a real
 * [ImapMailService], a real `APPEND`, and a scripted server that takes the message and then says
 * nothing at all.
 *
 * ⛔ This is the only test of the volet that proves anything about the mechanism. `ImapBudgetTest`
 * executes the arithmetic and the wiring tests read the source; between the two there is a whole
 * chain — the element surviving `withContext(Dispatchers.IO)`, `runWithRetry` reading it, the
 * deadline reaching `withReadTimeoutSuspending`, and that setting `soTimeout` — that no assertion
 * on a pure function can see. Every link of it can be broken while both stay green.
 *
 * The server is the exact shape of the reported failure (gesture B9): it accepts the connection,
 * accepts the literal, and never sends the tagged response. That is `read()` on a live socket with
 * nothing to read — the case a coroutine cancellation does NOT end, which is why the bound has to
 * be on the socket and not on the job.
 */
class ImapAmbientBudgetReachesTheSocketTest {

    @Test(timeout = 30_000)
    fun `an APPEND under an ambient budget gives up on a mute server, in the budget`() {
        muteServer().use { server ->
            // ⚠ On a thread of its own, exactly as the witness below, and for the same reason: what
            // is under test is whether this call COMES BACK. Made on the test's own thread, a
            // budget that never reached the socket would park the suite on the socket too and be
            // reported, minutes later, as a timeout rather than as the assertion it is.
            val outcome = appendOnItsOwnThread(server) { ImapReadBudget(BUDGET_MS) }

            assertTrue(
                "⛔ an APPEND to a mute server must come back on its own, and quickly: the budget " +
                    "was ${BUDGET_MS}ms. Bounding only the connect, or losing the element between " +
                    "withContext and soTimeout, leaves this call exactly as endless as it was — an " +
                    "APPEND's whole wait IS the tagged response that never comes",
                outcome.done.await(2, TimeUnit.SECONDS),
            )
            assertTrue(
                "⛔ and it must come back as a TIMEOUT — every caller reads a " +
                    "SocketTimeoutException as `could not be established`, while a " +
                    "CancellationException would be indistinguishable from the user giving up. " +
                    "Got: ${outcome.thrown}",
                outcome.thrown is SocketTimeoutException,
            )
            assertTrue(
                "the message really did go out — this is a read that expired, not a call that " +
                    "never left. Commands: ${server.issued()}",
                server.literals.size == 1,
            )
        }
    }

    @Test(timeout = 30_000)
    fun `the witness - without the element the same call never comes back`() {
        // ⛔ THE witness, and without it the test above proves nothing: a server that answered
        // quickly, an APPEND that failed for another reason, or a budget that was never consulted
        // would all produce the same green. So: the same server, the same call, no context element
        // — and it must NOT return.
        //
        // ⚠ Bounded on the TEST side only, and it has to be: there is no bound on the app side,
        // which is the whole point. The call is made on a daemon thread that is knowingly left
        // parked on the socket for the rest of the JVM's life — it cannot be interrupted, a
        // `withTimeout` around it would cancel the job without the thread ever coming off the
        // read (which is the defect itself), and a daemon thread holds nothing up at exit.
        muteServer().use { server ->
            val outcome = appendOnItsOwnThread(server) { EmptyCoroutineContext }

            assertFalse(
                "⛔ with no ImapReadBudget on the coroutine, an APPEND to a mute server must still " +
                    "be endless — soTimeout 0. If this ever comes back on its own, something else " +
                    "is bounding it and the test above is measuring that something else",
                outcome.done.await(2, TimeUnit.SECONDS),
            )
        }
    }

    // ---- the harness --------------------------------------------------------------------------------

    /** A call still running, or the throwable it ended with. */
    private class Outcome(val done: CountDownLatch) {
        @Volatile var thrown: Throwable? = null
    }

    /**
     * `appendDraft` against [server] under [context], on a DAEMON thread.
     *
     * ⚠ The thread is knowingly abandoned when the call does not come back — the witness case, and
     * the defect itself. It cannot be interrupted (a blocking `read()` is not interruptible), and a
     * `withTimeout` around it would cancel the job while the thread stayed on the socket, which is
     * precisely what this whole volet is about. A daemon thread holds nothing up at JVM exit; a
     * non-daemon one would hang the Gradle worker at the end of the suite.
     */
    private fun appendOnItsOwnThread(
        server: ScriptedImapServer,
        context: () -> CoroutineContext,
    ): Outcome {
        val outcome = Outcome(CountDownLatch(1))
        Thread {
            outcome.thrown = runCatching {
                runBlocking {
                    // ⭐ NOTHING is passed to appendDraft: it names no budget, and it cannot — the
                    // same call serves the interactive save. The bound, when there is one, is on
                    // the CONTEXT, exactly as MailRepository.uploadLocalDraft installs it.
                    withContext(context()) { service().appendDraft(credentials(server), DRAFTS, message()) }
                }
            }.exceptionOrNull()
            outcome.done.countDown()
        }.apply { isDaemon = true }.start()
        return outcome
    }

    /**
     * A server that greets, logs in, takes the `APPEND` literal — and then answers NOTHING.
     *
     * `ScriptedImapServer` writes whatever the responder returns and goes straight back to reading
     * the next command, so an empty answer is a peer that is alive, connected, and mute. That is
     * the link cut mid-`APPEND` of the bench measurement, minus the TCP reset that would have made
     * it easy.
     */
    private fun muteServer() = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("APPEND") -> ""
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

    private fun message() = OutgoingMessage(
        from = "alex@example.org",
        to = listOf("bob@example.org"),
        subject = "Half written",
        body = "I will be there at six",
        messageId = "abc-123@example.org",
        dateMillis = 1_760_000_000_000L,
    )

    private companion object {
        const val DRAFTS = "Drafts"

        /**
         * Short on purpose: the shipped value is a minute ([LOCAL_DRAFT_UPLOAD_BUDGET_MS]) and a
         * test that waited it out would be a minute of bench time per run. What is under test is
         * that the number reaches the socket, not which number it is.
         */
        const val BUDGET_MS = 500

        /** `ImapDraftAppendGoesOutOnceTest`'s, verbatim and for its reason: the real service needs
         *  a refresher, a refresher needs an Android `Context`, and a password account never reads
         *  one field of it. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

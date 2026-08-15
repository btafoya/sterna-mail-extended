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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ⭐ **That the WAY OUT of a session is bounded too** (#95) — the half the ambient budget cannot
 * reach.
 *
 * The bench measurement (gesture B9, emulator, release, IMAP, link cut 2.1 s into the `APPEND`):
 * the tagged read of the `APPEND` expired on its ambient budget exactly as
 * `ImapAmbientBudgetReachesTheSocketTest` proves — and the thread then re-blocked for 9 minutes,
 * with `ImapSession.close()` on the stack. The reason is structural rather than accidental:
 * `withReadTimeoutSuspending` restores the socket's previous `soTimeout` (0) in its `finally`, and
 * `ImapMailService.runWithRetry` closes the session from its `catch`, i.e. AFTER the bounded block
 * has been left. `close()` sends `LOGOUT` and reads its completion, so that read is unbounded.
 *
 * ⚠ **The peer here is MUTE, not closed.** A peer that closes is already handled — `ImapParser`
 * throws on end-of-stream, which is Codeberg #156 and `ImapCutConnectionTest`'s A8. A peer that
 * simply disappears (airplane mode, no FIN) sends nothing at all, and `recvfrom` on a live socket
 * with nothing to read is what this reproduces.
 *
 * ⛔ And the guard, asserted below: the `LOGOUT` still goes out and its answer is still awaited.
 * Making this test pass by not saying goodbye would leave the session open on the server until its
 * own idle timeout, which is how an account with a small connection limit starts refusing the next
 * upload.
 */
class ImapSessionExitIsBoundedTest {

    @Test(timeout = 30_000)
    fun `an APPEND to a peer that goes mute comes back, teardown included`() {
        muteAfterLoginServer().use { server ->
            // ⚠ On a daemon thread, exactly as the sibling test and for its reason: what is under
            // test is whether the call COMES BACK. Before the fix it does not — and a blocking
            // `read()` cannot be interrupted, so a call made on the test's own thread would park the
            // suite and be reported as a JUnit timeout instead of as this assertion.
            val outcome = appendOnItsOwnThread(server)
            // ⚠ Waited FIRST, into a val. `assertTrue(message, condition)` builds its message before
            // it evaluates the condition, so an `await` inline in the call would have every
            // `server.issued()` in these messages read the list as it was at t=0, i.e. empty.
            val cameBack = outcome.done.await(LOGOUT_BUDGET_MS + MARGIN_MS, TimeUnit.MILLISECONDS)

            assertTrue(
                "⛔ appendDraft never came back within ${(LOGOUT_BUDGET_MS + MARGIN_MS)}ms. The tagged " +
                    "read expired on its ${BUDGET_MS}ms ambient budget; what is still endless is the " +
                    "read AFTER it — ImapSession.close() writing LOGOUT and waiting for an answer a " +
                    "mute peer will never send, on a socket whose soTimeout the budget's `finally` " +
                    "has already put back to 0. Commands the server got: ${server.issued()}",
                cameBack,
            )
            assertTrue(
                "⛔ the session left without saying LOGOUT. Bounding this read is the fix; skipping " +
                    "the goodbye is not — the server would hold the connection open until its own " +
                    "idle timeout, and an account with few simultaneous connections then refuses the " +
                    "next upload. Commands: ${server.issued()}",
                server.issued().any { it.startsWith("LOGOUT") },
            )
            assertTrue(
                "the message really did go out — this is a teardown that expired, not a call that " +
                    "never left. Literals: ${server.literals.size}, commands: ${server.issued()}",
                server.literals.size == 1,
            )
        }
    }

    /**
     * ⭐ **That the answer to the `LOGOUT` is READ, and that the budget for reading it is real.**
     *
     * The test above only sees the goodbye LEAVE: its server answers `LOGOUT` with nothing, so
     * there is never anything to read, and `close()` rewritten as a bare
     * `output.write("z LOGOUT\r\n"); flush()` passes it. This one gives the server a voice and makes
     * it SLOW: the answer comes [LOGOUT_DELAY_MS] after the goodbye arrives.
     *
     * The assertion is therefore a bound FROM BELOW on a measured duration: a call that waits for
     * that answer cannot come back before the ambient budget the mute `APPEND` spends PLUS the
     * server's own delay. Two ways of failing it, and both are the point — a `close()` that does
     * not read comes back on the budget alone, and a `LOGOUT_READ_BUDGET_MS` set below the delay
     * expires and comes back on the budget alone too.
     */
    @Test(timeout = 30_000)
    fun `the goodbye is not written and forgotten - its answer is waited for`() {
        slowGoodbyeServer().use { server ->
            // ⚠ Started before the thread, so the connect and the LOGIN are counted IN. They can
            // only make the measurement longer, i.e. they can never turn a call that skipped the
            // read into one that looks like it waited.
            val startedAt = System.nanoTime()
            val outcome = appendOnItsOwnThread(server)
            val cameBack = outcome.done.await(
                LOGOUT_BUDGET_MS + LOGOUT_DELAY_MS + MARGIN_MS,
                TimeUnit.MILLISECONDS,
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertTrue(
                "⛔ appendDraft never came back at all, against a server that DOES answer its " +
                    "LOGOUT ${LOGOUT_DELAY_MS}ms late. Commands the server got: ${server.issued()}",
                cameBack,
            )
            assertTrue(
                "⛔ appendDraft came back after ${elapsedMs}ms, and nothing that waited for the " +
                    "answer to its LOGOUT can come back before ${MIN_ELAPSED_MS}ms: the mute " +
                    "APPEND spends the whole ${BUDGET_MS}ms ambient budget, and only then does the " +
                    "goodbye go out, to a server that sleeps ${LOGOUT_DELAY_MS}ms before answering " +
                    "it. So either close() wrote LOGOUT without reading the reply, or its own read " +
                    "budget expired before the reply arrived. Commands: ${server.issued()}",
                elapsedMs >= MIN_ELAPSED_MS,
            )
        }
    }

    // ---- the harness --------------------------------------------------------------------------

    /** A call still running, or the throwable it ended with. */
    private class Outcome(val done: CountDownLatch) {
        @Volatile var thrown: Throwable? = null
    }

    /**
     * `appendDraft` against [server] under a short [ImapReadBudget], on a DAEMON thread.
     *
     * ⚠ The thread is knowingly abandoned when the call does not come back — which is the defect
     * itself. A `withTimeout` around it would cancel the job while the thread stayed on the socket,
     * i.e. it would hide precisely what is being measured. A daemon thread holds nothing up at JVM
     * exit; a non-daemon one would hang the Gradle worker at the end of the suite.
     */
    private fun appendOnItsOwnThread(server: ScriptedImapServer): Outcome {
        val outcome = Outcome(CountDownLatch(1))
        Thread {
            outcome.thrown = runCatching {
                runBlocking {
                    withContext(ImapReadBudget(BUDGET_MS)) {
                        service().appendDraft(credentials(server), DRAFTS, message())
                    }
                }
            }.exceptionOrNull()
            outcome.done.countDown()
        }.apply { isDaemon = true }.start()
        return outcome
    }

    /**
     * A server that greets, logs in, takes the `APPEND` literal — and then answers nothing ever
     * again, to the `APPEND` and to the `LOGOUT` alike, **without closing the connection**.
     *
     * ⛔ The empty answer to `LOGOUT` is the whole fixture. `ScriptedImapServer` only ends a
     * connection on a `LOGOUT` it answered (see the comment there); an empty answer therefore leaves
     * the socket open and the client reading a peer that will never speak — the airplane-mode cut,
     * minus the FIN that would have made it easy.
     */
    private fun muteAfterLoginServer() = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("APPEND") -> ""
            line.startsWith("LOGOUT") -> ""
            else -> "$tag OK done\r\n"
        }
    }

    /**
     * The same mute `APPEND` as above — but a server that DOES say goodbye back, [LOGOUT_DELAY_MS]
     * late.
     *
     * ⚠ The sleep is the responder's own, on the server's thread: `ScriptedImapServer` needs no new
     * parameter for it. And because that answer is not empty, the harness ends the connection on it
     * exactly as a real server would, so this thread is not left parked.
     */
    private fun slowGoodbyeServer() = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK logged in\r\n"
            line.startsWith("APPEND") -> ""
            line.startsWith("LOGOUT") -> {
                Thread.sleep(LOGOUT_DELAY_MS)
                "$tag OK bye\r\n"
            }
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

        /** Short on purpose, as the sibling test's: what is under test is the read AFTER this one. */
        const val BUDGET_MS = 500

        /**
         * `LOGOUT_READ_BUDGET_MS` of `ImapClient.kt`, restated — it is file-private there, and this
         * module cannot see it. ⚠ Restated as a BOUND ON ELAPSED TIME, not as a decision: the test
         * measures how long a real call takes, so raising the shipped number above this makes the
         * call miss the deadline and this test go red.
         */
        const val LOGOUT_BUDGET_MS = 5_000L

        /** Room for the connect, the LOGIN, the 500 ms tagged read and a slow CI worker. */
        const val MARGIN_MS = 2_000L

        /** How long the slow server sits on the answer to a `LOGOUT` before sending it. Long
         *  enough to be unmistakable next to [BUDGET_MS], short enough to cost the suite nothing. */
        const val LOGOUT_DELAY_MS = 400L

        /**
         * The floor a call that really waited for its goodbye cannot go under: the ambient budget
         * the mute `APPEND` burns, then the server's delay — minus a few milliseconds, because a
         * `SO_RCVTIMEO` is not promised to the millisecond. ⚠ A `close()` that skips the read comes
         * back on [BUDGET_MS] alone, i.e. a third of a second below this, so the slack cannot hide
         * the mutation this bound exists to kill.
         */
        const val MIN_ELAPSED_MS = BUDGET_MS + LOGOUT_DELAY_MS - 50L

        /** `ImapAmbientBudgetReachesTheSocketTest`'s, verbatim and for its reason: the real service
         *  needs a refresher, a refresher needs an Android `Context`, and a password account never
         *  reads one field of it. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

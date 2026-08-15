package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference

/**
 * A submission against a server that ACCEPTS and then makes the client wait.
 *
 * Nothing of the SMTP dialogue used to be bounded in time: no `soTimeout` existed anywhere in
 * `SmtpClient`, so a server that took the connection and then said nothing left `send()` blocked on
 * a read for good — the outbox row stuck on "sending", with no error, nothing to cancel and nothing
 * to edit. What is asserted here is that `send()` COMES BACK, and that the one read which is
 * legitimately slow (the 250 that answers the body) still does not.
 *
 * ⚠ The wait bounds below are far under the shipped defaults (20 s connect / 30 s command), so a
 * client that ignored the arguments and used its defaults would fail these just as an unbounded one
 * does: what is pinned is the value that was PASSED, not merely that some timeout exists.
 */
class SmtpTimeoutTest {

    /**
     * How long a bounded send is given to come back: ten times the bound handed to the client, a
     * sixth of the 20 s connect default and of the 30 s command default.
     *
     * Wide on purpose. It only bounds the FAILING cases, so widening it costs a green suite nothing,
     * and the container is capped at four cores with other Gradle workers on them: a 500 ms margin
     * here would turn one GC pause into a red verdict on correct code.
     *
     * What it still pins is WHICH value the client used — a client that ignored the argument, or
     * took the wrong one of the two, lands on a default far above this and hangs.
     */
    private val waitMs = 5_000L

    /**
     * ⚠ Short bound on the COMMANDS only; the connect keeps its shipped default and is never passed.
     * That is what tells the two apart: on loopback the connect is instantaneous, so a `send()` that
     * bounded its command reads with `connectTimeoutMs` would read at 20 s and blow [waitMs] — where
     * passing a short connect timeout too would have let that confusion pass unseen.
     */
    private val commandTimeoutMs = 500

    /**
     * Run [block] on a daemon thread and give it [millis] to return; the throwable it ended with,
     * or null when it returned normally.
     *
     * A thread and not `@Test(timeout = …)`: without the fix these sends never return at all, and
     * the thread left behind must not be one that keeps the Gradle test worker alive. The `check`
     * message IS the red witness of the defect.
     */
    private fun sendWithin(millis: Long, block: () -> Unit): Throwable? {
        val outcome = AtomicReference<Result<Unit>>()
        val thread = Thread({ outcome.set(runCatching(block)) }, "smtp-under-test")
            .apply { isDaemon = true; start() }
        thread.join(millis)
        check(!thread.isAlive) {
            "send() had not returned after ${millis}ms — the dialogue is not bounded in time. " +
                "That is the outbox row frozen on 'sending', reproduced."
        }
        return outcome.get().exceptionOrNull()
    }

    private fun sendBlocking(server: FakeSmtpServer, subject: String = "hello there") = runBlocking {
        SmtpClient().send(server.config, outgoingFixture(subject), commandTimeoutMs = commandTimeoutMs)
    }

    /** B-J5 — the connection is accepted and the greeting never comes. The first read of all. */
    @Test
    fun `a server that accepts and never greets fails inside the command timeout`() {
        FakeSmtpServer(greeting = { null }).use { server ->
            val error = sendWithin(waitMs) { sendBlocking(server) }

            assertTrue(
                "expected the read of the greeting to time out, got ${error?.javaClass?.name}: ${error?.message}",
                error is SocketTimeoutException,
            )
            // Nothing was written at a server that never said hello: EHLO waits on the 220. Behind
            // awaitDone like its neighbour below — a list still being written by the serving thread
            // can be empty for a moment for reasons that have nothing to do with the claim.
            server.awaitDone()
            assertEquals(emptyList<String>(), server.issued())
        }
    }

    /** B-J6 — the server goes quiet in the MIDDLE of the dialogue, connection still open. */
    @Test
    fun `a server that goes silent after MAIL FROM fails inside the command timeout`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            // An empty reply: not a byte written, and the connection stays up. The client is left
            // reading a socket nobody will ever write to again.
            respond = { line -> if (line.startsWith("MAIL FROM")) emptyList() else null },
        ).use { server ->
            val error = sendWithin(waitMs) { sendBlocking(server) }

            assertTrue(
                "expected the read after MAIL FROM to time out, got ${error?.javaClass?.name}: ${error?.message}",
                error is SocketTimeoutException,
            )
            server.awaitDone()
            assertEquals("MAIL FROM:<tester@example.org>", server.issued().last())
        }
    }

    /**
     * B-J8 ⭐ — the acknowledgement of the BODY takes far longer than the command timeout, and the
     * send must succeed anyway.
     *
     * This is the witness that the two regimes exist. The server behind that read queues, signs
     * (DKIM) and scans the message; minutes are normal. A short bound here would fail a submission
     * the server went on to accept, `OutboxWorker` would retry it, and the message would go out
     * TWICE — which no later fix can take back.
     */
    @Test
    fun `a slow acknowledgement of the body is waited for, past the command timeout`() {
        val ackDelayMs = 4L * commandTimeoutMs
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) {
                    Thread.sleep(ackDelayMs)
                    listOf("250 2.0.0 queued as FAKE1")
                } else {
                    null
                }
            },
        ).use { server ->
            val error = sendWithin(ackDelayMs * 3) { sendBlocking(server, subject = "slow queue") }

            assertNull(
                "the send was cut while the server was still queueing the message; " +
                    "OutboxWorker would send it a second time. Got: $error",
                error,
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: slow queue"),
            )
        }
    }

    /**
     * B-J9 — a send that FAILS closes its connection.
     *
     * Proven by the server, which is the only party that can see it: its read returns EOF and its
     * serving thread ends, which is what [FakeSmtpServer.awaitDone] waits for. Before the fix
     * `socket.close()` was reached only after a successful send, so every rejected recipient — and
     * every timeout added by this change — leaked a descriptor.
     */
    @Test
    fun `a rejected recipient still closes the connection`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line -> if (line.startsWith("RCPT TO")) listOf("550 5.1.1 no such user") else null },
        ).use { server ->
            val error = sendWithin(waitMs) { sendBlocking(server) }

            assertTrue(
                "expected the refusal to surface as an SmtpException, got ${error?.javaClass?.name}",
                error is SmtpException,
            )
            assertEquals("RCPT TO: 550 5.1.1 no such user", error?.message)
            // The claim: the server saw the connection go. A client that merely dropped the object
            // leaves this thread parked on a read, and awaitDone says so.
            server.awaitDone(3_000)
        }
    }

    /** The nominal path, with the shipped defaults and no argument at all: unchanged. */
    @Test
    fun `a plain send still goes through with no timeout argument`() {
        FakeSmtpServer(authLine = "AUTH PLAIN LOGIN").use { server ->
            runBlocking { SmtpClient().send(server.config, outgoingFixture("nominal")) }

            assertEquals(
                listOf("EHLO [127.0.0.1]", "MAIL FROM:<tester@example.org>", "RCPT TO:<dest@example.org>", "DATA"),
                server.issued().filterNot { it.startsWith("AUTH ") },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: nominal"),
            )
        }
    }
}

/**
 * ⚠ SOURCE LINT, and it is a lint and not a measurement: `FakeSmtpServer` speaks no TLS, so
 * neither the implicit-TLS handshake nor the STARTTLS upgrade is reachable by a behaviour test.
 * `startHandshake()` reads from the socket, so it hangs on a mute server exactly as a command read
 * does — and a bound armed AFTER it would not cover it. Only the order of two lines states that.
 *
 * The shipped default VALUES are the second thing no behaviour test can state (a test that waited
 * 20 s to observe a 20 s bound would be a test nobody runs), so they are pinned on the declaration.
 *
 * Whole lines, never `contains`: a substring check is blind to every mutation that LENGTHENS the
 * line it looks at.
 */
class SmtpTimeoutLintTest {

    /**
     * The STATEMENTS of `send()`. Comment lines are dropped, and only they: what is asserted below
     * is the order in which things happen, and a comment that names `startHandshake()` is not a
     * handshake. Nothing else is filtered — a mutation that lengthens a line still shows up whole.
     */
    private val body = ImapSource.functionBody("SmtpClient", "send")
        .filterNot { Regex("""^(//|/?\*)""").containsMatchIn(it) }

    private fun onlyIndexOf(line: String): Int {
        val hits = body.indices.filter { body[it] == line }
        assertEquals("expected exactly one `$line` in send():\n" + body.joinToString("\n"), 1, hits.size)
        return hits.single()
    }

    @Test
    fun `the shipped bounds are the JMAP submission ones, and they are the defaults`() {
        // core/jmap JmapClient.defaultHttpClient(): connectTimeout(20, SECONDS) / readTimeout(30, SECONDS).
        assertEquals(20_000, SMTP_CONNECT_TIMEOUT_MS)
        assertEquals(30_000, SMTP_COMMAND_TIMEOUT_MS)
        // ⛔ 0 is the socket's own default and means "no deadline of ours".
        assertEquals(0, SMTP_BLOCKING_READ)
        assertEquals(
            "send() no longer takes its two bounds as parameters defaulting to the shipped values:\n" +
                ImapSource.functionSignature("SmtpClient", "send").joinToString("\n"),
            listOf(
                "fun send(",
                "config: MailServerConfig,",
                "message: OutgoingMessage,",
                "connectTimeoutMs: Int = SMTP_CONNECT_TIMEOUT_MS,",
                "commandTimeoutMs: Int = SMTP_COMMAND_TIMEOUT_MS,",
                ")",
            ),
            ImapSource.functionSignature("SmtpClient", "send"),
        )
    }

    @Test
    fun `the connect is the form that takes a deadline, and it is given one`() {
        // ⛔ `Socket(host, port)` connects in its constructor, with no timeout at all: minutes at
        // the OS's discretion. The two-step form is the only one that can be bounded.
        assertEquals(
            "the connect no longer passes the connect timeout:\n" + body.joinToString("\n"),
            listOf("plain.connect(InetSocketAddress(config.host, config.port), connectTimeout)"),
            body.filter { "connect(" in it && "InetSocketAddress" in it },
        )
        onlyIndexOf("val connectTimeout = connectTimeoutMs.coerceAtLeast(0)")
    }

    /**
     * The hostname check, on both TLS sockets.
     *
     * ⛔ This volet is what put `.verifyingHostname()` on a line of its own (it used to be welded to
     * `…​.apply { startHandshake() }`), so it is now a one-line deletion, between two lines this
     * class pins — which would make the area LOOK covered. Nothing else in the repo names it. Drop
     * it and any certificate from any CA is accepted for any host: the submission can be
     * intercepted, and the password goes out in base64 three commands later.
     */
    @Test
    fun `every TLS socket verifies the hostname before it shakes hands`() {
        assertEquals(
            "the hostname check is gone from a TLS socket — an intercepted submission would hand " +
                "over the password:\n" + body.joinToString("\n"),
            listOf(".verifyingHostname()", ".verifyingHostname()"),
            body.filter { "verifyingHostname" in it },
        )
        assertEquals(
            "send() no longer builds exactly two TLS sockets; a third one would not be covered by " +
                "the rule below:\n" + body.joinToString("\n"),
            2,
            body.count { "tlsFactory.createSocket" in it },
        )
        body.indices.filter { "startHandshake()" in body[it] }.forEach { at ->
            assertEquals(
                "the handshake at line $at shakes hands with a socket that never got the hostname " +
                    "check:\n" + body.joinToString("\n"),
                ".verifyingHostname()",
                body[at - 2],
            )
        }
    }

    @Test
    fun `every TLS handshake is armed on the line before it`() {
        val handshakes = body.indices.filter { "startHandshake()" in body[it] }
        assertEquals(
            "send() no longer performs exactly two handshakes (implicit TLS, then STARTTLS):\n" +
                body.joinToString("\n"),
            listOf("secure.startHandshake()", "secure.startHandshake()"),
            handshakes.map { body[it] },
        )
        handshakes.forEach { at ->
            assertEquals(
                "the handshake at line $at is not preceded by the line that bounds its reads; a bound " +
                    "armed after it leaves a handshake against a mute server waiting for ever:\n" +
                    body.joinToString("\n"),
                "secure.soTimeout = commandTimeout",
                body[at - 1],
            )
        }
    }

    /**
     * ⭐ Every read bound `send()` sets, in order, whole line — what is armed AND what is disarmed.
     *
     * "A disarm exists" is not the claim: the claim is WHICH sockets it puts back to blocking. Two
     * were armed (the plain one at the connect, the TLS one layered over it), so two are disarmed.
     * A disarm of only the current socket would rely on `SSLSocket.setSoTimeout` reaching the socket
     * it wraps; where that does not hold, the 250 that answers the body stays bounded at 30 s over
     * TLS and a slow-but-successful send is cut, retried, and delivered twice.
     *
     * ⚠ A lint, not a measurement: `FakeSmtpServer` speaks no TLS, so no test here can watch a
     * submission survive a slow acknowledgement over TLS.
     */
    @Test
    fun `every read bound is armed and put back, on every socket that was armed`() {
        assertEquals(
            "the read bounds of send() are no longer these, in this order:\n" + body.joinToString("\n"),
            listOf(
                "plain.soTimeout = commandTimeout",
                "secure.soTimeout = commandTimeout",
                "secure.soTimeout = commandTimeout",
                "plain.soTimeout = SMTP_BLOCKING_READ",
                "socket.soTimeout = SMTP_BLOCKING_READ",
            ),
            body.filter { "soTimeout" in it },
        )
        // ⚠ The line moved from `expect("250", "message body")` to a dedicated reader, because a
        // reply CUT after its first line is an accepted message here (SmtpCutConnectionTest J2).
        // The claim below is unchanged: whatever reads the body's acknowledgement, there is one of
        // it and it comes straight after the two statements that unbound the socket.
        val ack = onlyIndexOf("val ack = readBodyAck()")
        assertEquals(
            "the body's acknowledgement is no longer read right after the two statements that " +
                "unbound it:\n" + body.joinToString("\n"),
            listOf("plain.soTimeout = SMTP_BLOCKING_READ", "socket.soTimeout = SMTP_BLOCKING_READ"),
            listOf(body[ack - 2], body[ack - 1]),
        )
    }

    @Test
    fun `the connection is closed on every path out`() {
        val close = onlyIndexOf("runCatching { socket.close() }")
        assertEquals(
            "the close is no longer the body of a finally — a failed send would leak its socket:\n" +
                body.joinToString("\n"),
            "} finally {",
            body[close - 1],
        )
    }
}

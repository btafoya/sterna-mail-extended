package app.sterna.core.imap

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A submission that dies in the MIDDLE of a multi-line response.
 *
 * `read()` used to treat the two halves of one response differently: no first line at all threw,
 * but a connection cut after a `250-` continuation quietly returned that continuation. `expect()`
 * only compares a prefix, so `"250-AUTH PLAIN".startsWith("250")` made a truncated response pass
 * for a successful one — the EHLO capabilities were then read off a PARTIAL list, and the failure
 * surfaced much later on a step that was not the cause.
 *
 * ONE read is the exception, and it is the one whose cut is irreversible: the acknowledgement of
 * the message BODY, where a `250-` already means the server took the message (J2 below).
 *
 * The server here ANNOUNCES and CUTS; the client is the shipped one, unchanged for the tests. What
 * is asserted is the exception the caller gets and the commands that actually left the client.
 */
class SmtpCutConnectionTest {

    private fun send(server: FakeSmtpServer, subject: String = "hello there") =
        runBlocking { SmtpClient().send(server.config, outgoingFixture(subject)) }

    /** J1 — the cut lands between two lines of the EHLO response, the one that is always multi-line. */
    @Test
    fun `an EHLO cut after a continuation line fails at the EHLO, not three steps later`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line ->
                // One continuation line, then nothing: the "250 " final line never comes, and
                // neither does the AUTH capability that was going to be on it.
                if (line.startsWith("EHLO")) listOf("250-fake.local greets you") else null
            },
        ).use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }
            server.awaitDone()

            assertEquals("Connection closed mid-response", error.message)
            // The whole point: the session stops at the EHLO. Before the fix the client accepted the
            // truncated response, computed its capabilities from a partial list, and went on to
            // write an AUTH command at a server that had already gone.
            assertEquals(
                "the client kept talking after a response that never finished",
                listOf("EHLO [127.0.0.1]"),
                server.issued(),
            )
        }
    }

    /**
     * J2 — the cut lands inside the 250 that acknowledges the message BODY, after a continuation
     * line. THE OTHER WAY ROUND from every case above, and this test used to assert the opposite.
     *
     * RFC 5321 § 4.2.1: every line of a reply carries the same code, so a server that has written
     * `250-…` has already accepted the message. Failing here would leave `OutboxWorker` to retry a
     * send the server went on to queue, and the recipient would get it TWICE — the one outcome
     * nothing later can take back, and the one `SMTP_BLOCKING_READ` exists to prevent.
     *
     * `delivered` is deliberately not asserted: a cut on the dot is taken before the fixture
     * records the body. What is asserted is the normal return, and that the dialogue reached the
     * body at all — so this really is the acknowledgement that was cut.
     */
    @Test
    fun `a cut inside the 250 that acknowledges the body is a successful send`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) listOf("250-2.0.0 accepted") else null
            },
        ).use { server ->
            send(server)
            server.awaitDone()

            assertEquals("DATA", server.issued().last())
        }
    }

    /**
     * J2-witness — the same two-line acknowledgement, complete this time.
     *
     * Without it the green above proves nothing: a `send()` that swallowed every failure, or that
     * never reached the body at all, would pass J2 just as well. Here the dot really was written
     * and the server really took the message, which only `delivered` can state.
     *
     * What it does NOT witness, and what nothing here can: that the client read the
     * acknowledgement to its LAST line. `delivered` is set by the fixture before it answers
     * (`FakeSmtpServer`), and the connection ends on this reply — a client that gave up after the
     * first line would leave the second one unread, with nobody left to trip over it. That claim is
     * only pinned on the reads that have a next command behind them (J4, J5, and the multi-line
     * `354` of DATA).
     */
    @Test
    fun `a complete multi-line acknowledgement of the body queues the message`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) {
                    listOf("250-2.0.0 accepted", "250 2.0.0 queued as FAKE1")
                } else {
                    null
                }
            },
        ).use { server ->
            send(server, subject = "acknowledged whole")

            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: acknowledged whole"),
            )
        }
    }

    /**
     * J2b — the cut lands BEFORE a single line of the body's acknowledgement.
     *
     * The tolerance above stops here: nothing was said, so nothing was accepted. Reading that as a
     * success would mark as sent a message the server never took, and it would be lost — as
     * irreversible as sending it twice, in the other direction.
     */
    @Test
    fun `a cut before any line of the body acknowledgement is still a failed send`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line -> if (line == FakeSmtpServer.END_OF_DATA) emptyList() else null },
        ).use { server ->
            val error = assertThrows(
                "send() returned normally: a message nobody ever acknowledged was reported as sent",
                SmtpException::class.java,
            ) { send(server) }
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertEquals("DATA", server.issued().last())
        }
    }

    /**
     * J2c — a cut acknowledgement whose code is a REFUSAL. The code decides, not the fact that a
     * line was read: `421-…` is a server closing the channel, and a send it never queued must not
     * be reported as delivered.
     */
    @Test
    fun `a cut 421 answering the body is a failed send, quoting the line`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line ->
                if (line == FakeSmtpServer.END_OF_DATA) {
                    listOf("421-4.3.2 service closing transmission channel")
                } else {
                    null
                }
            },
        ).use { server ->
            val error = assertThrows(
                "a refusal spread over two lines was reported as a successful send",
                SmtpException::class.java,
            ) { send(server) }
            server.awaitDone()

            assertEquals(
                "message body: 421-4.3.2 service closing transmission channel",
                error.message,
            )
        }
    }

    /** J3 — the case that already worked: not a single byte of the response. Must not change. */
    @Test
    fun `a cut before the first line of a response still says Connection closed`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            cutAfter = { line -> if (line.startsWith("MAIL FROM")) emptyList() else null },
        ).use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }
            server.awaitDone()

            assertEquals("Connection closed", error.message)
            assertEquals("MAIL FROM:<tester@example.org>", server.issued().last())
        }
    }

    /** J4 — the nominal multi-line response, complete. The final line is returned, `expect` is happy. */
    @Test
    fun `a complete multi-line response is still read whole and still sends`() {
        FakeSmtpServer(authLine = "AUTH PLAIN LOGIN", multiline = true).use { server ->
            send(server, subject = "complete multiline")

            val issued = server.issued()
            // AUTH PLAIN was announced on a CONTINUATION line of the EHLO response: choosing it
            // proves every line was accumulated. Reaching MAIL FROM proves only that the reader is
            // not DESYNCHRONISED — every continuation was consumed. WHICH line read() hands back is
            // a different claim, and it is pinned by the multi-line error case below, where the
            // lines of one response differ by more than their code.
            assertEquals(
                "expected exactly one AUTH command between EHLO and MAIL FROM, got $issued",
                1,
                issued.count { it.startsWith("AUTH ") },
            )
            assertTrue(
                "PLAIN was advertised on a continuation line but the client wrote: $issued",
                issued.any { it.startsWith("AUTH PLAIN ") },
            )
            assertEquals(
                listOf("EHLO [127.0.0.1]", "MAIL FROM:<tester@example.org>", "RCPT TO:<dest@example.org>", "DATA"),
                issued.filterNot { it.startsWith("AUTH ") },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: complete multiline"),
            )
        }
    }

    /**
     * J5 — a REAL EHLO, eight continuation lines before the final one. Gmail and Stalwart answer
     * with six to ten; every other fixture in this suite stops at three, so a loop that gave up
     * after a couple of lines would hand back a `250-…` continuation as the response — the original
     * bug, on every real server — without a single test noticing.
     *
     * `AUTH PLAIN` sits on the FIFTH line on purpose: it is only reachable by a loop that ran to
     * the end.
     */
    @Test
    fun `an eight-line EHLO is read to its last line, capability and all`() {
        val longEhlo = listOf(
            "250-fake.local greets you",
            "250-PIPELINING",
            "250-SIZE 35882577",
            "250-8BITMIME",
            "250-AUTH PLAIN",
            "250-ENHANCEDSTATUSCODES",
            "250-CHUNKING",
            "250-SMTPUTF8",
            "250 HELP",
        )
        FakeSmtpServer(
            respond = { line -> if (line.startsWith("EHLO")) longEhlo else null },
        ).use { server ->
            send(server, subject = "long ehlo")

            val issued = server.issued()
            // The capability was on line five: choosing PLAIN is only possible if lines four to
            // eight were accumulated too.
            assertEquals(
                "the fifth line of the EHLO was never accumulated; the client wrote $issued",
                1,
                issued.count { it.startsWith("AUTH PLAIN ") },
            )
            // And reaching the envelope means the loop stopped on the REAL final line, "250 HELP",
            // leaving nothing of the EHLO in the buffer for MAIL FROM to trip over.
            assertEquals(
                listOf("EHLO [127.0.0.1]", "MAIL FROM:<tester@example.org>", "RCPT TO:<dest@example.org>", "DATA"),
                issued.filterNot { it.startsWith("AUTH ") },
            )
            assertTrue(
                "the message never reached DATA; delivered = ${server.delivered}",
                server.delivered.orEmpty().contains("Subject: long ehlo"),
            )
        }
    }

    /**
     * J6 — the FINAL line, not merely "a line of the response".
     *
     * Every line of an SMTP response carries the same code, so a `read()` that returned the FIRST
     * line instead of the last would desynchronise nothing and pass every prefix test in this file.
     * The only thing that tells them apart is the TEXT, and the only place the text reaches the
     * caller is an error message. Hence a refusal spread over two lines whose codes are identical
     * and whose texts are not.
     */
    @Test
    fun `the line handed back is the last one, which is the one the error message quotes`() {
        FakeSmtpServer(
            authLine = "AUTH PLAIN",
            respond = { line ->
                if (line.startsWith("AUTH PLAIN")) {
                    listOf(
                        "535-5.7.8 first line, which is not the verdict",
                        "535 5.7.8 last line, which is the verdict",
                    )
                } else {
                    null
                }
            },
        ).use { server ->
            val error = assertThrows(SmtpException::class.java) { send(server) }

            assertEquals(
                "AUTH PLAIN: 535 5.7.8 last line, which is the verdict",
                error.message,
            )
        }
    }
}

/**
 * SOURCE LINT (J11), the belt to the braces of the four tests above: the `break` that swallowed a
 * cut connection must not come back. Whole lines, never `contains`: a substring check is blind to
 * every mutation that LENGTHENS the line.
 */
class SmtpCutLintTest {

    @Test
    fun `every readLine in read either yields a line or throws`() {
        val body = ImapSource.functionBody("SmtpClient", "read")
        assertEquals(
            "read() no longer reads exactly two ways, and both of them throwing is the rule:\n" +
                body.joinToString("\n"),
            listOf(
                "var line = reader.readLine() ?: throw SmtpException(\"Connection closed\")",
                "line = reader.readLine() ?: throw SmtpException(\"Connection closed mid-response\")",
            ),
            body.filter { "reader.readLine()" in it },
        )
    }

    /**
     * The same belt for `readBodyAck`, whose two reads are NOT the same rule: the first throws like
     * everywhere else, the second gives the line back. Which of the two is tolerant decides between
     * a message sent twice and a message lost, and moving the `?: return line` up one line is a
     * one-token edit that only J2b would notice.
     */
    @Test
    fun `readBodyAck throws on the first line and yields on a later one`() {
        // Comment lines dropped, and only they — the KDoc there quotes the wrong form of the very
        // line being pinned, and a comment naming a read is not a read. Nothing else is filtered: a
        // mutation that lengthens a statement still shows up whole.
        val body = ImapSource.functionBody("SmtpClient", "readBodyAck")
            .filterNot { Regex("""^(//|/?\*)""").containsMatchIn(it) }
        assertEquals(
            "readBodyAck no longer reads exactly two ways, the first refusing and the second " +
                "yielding:\n" + body.joinToString("\n"),
            listOf(
                "var line = reader.readLine() ?: throw SmtpException(\"Connection closed\")",
                "val next = reader.readLine() ?: return line",
            ),
            body.filter { "reader.readLine()" in it },
        )
    }
}

package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What a session hands its [ImapCommandSink], driven end to end against [FakeImapServer]: every
 * expectation is the whole list the sink received, so a line too many (a literal, a continuation)
 * fails as surely as a line that says too much.
 */
class CommandTraceOnTheWireTest {

    private class Recorded : ImapCommandSink {
        val lines = mutableListOf<String>()
        override fun sent(tag: String, command: String) { lines += "> $tag $command" }
        override fun completed(tag: String, status: String) { lines += "< $tag $status" }
    }

    private fun FakeImapServer.traced(sink: ImapCommandSink, config: MailServerConfig = this.config): ImapSession =
        ImapClient(trace = sink).openSession(config)

    @Test fun `a SELECT and a UID FETCH are reported as they left, each with its completion`() {
        val sink = Recorded()
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("SELECT") -> selectResponse(tag)
                line.startsWith("UID FETCH") -> fetchResponse(tag, uidsOf(line)) { false }
                else -> ok(tag)
            }
        }
        val onTheWire = server.use {
            it.traced(sink).use { session ->
                session.select("INBOX")
                session.fetchUids(listOf(12, 14))
            }
            it.commands.toList()
        }

        val fetch = onTheWire.single { it.startsWith("UID FETCH") }
        assertTrue("the fixture must have seen the fetch of both uids, saw: $fetch", fetch.startsWith("UID FETCH 12,14 ("))
        assertEquals(
            listOf(
                "> a1 LOGIN", "< a1 OK",
                "> a2 CAPABILITY", "< a2 OK",
                "> a3 SELECT \"INBOX\"", "< a3 OK",
                "> a4 $fetch", "< a4 OK",
                "> a5 LOGOUT", "< a5 OK",
            ),
            sink.lines,
        )
    }

    @Test fun `a refused command is reported with its NO`() {
        val sink = Recorded()
        val server = FakeImapServer { tag, line ->
            if (line.startsWith("SELECT")) "$tag NO [NONEXISTENT] no such mailbox\r\n" else ok(tag)
        }
        server.use {
            it.traced(sink).use { session ->
                try {
                    session.select("Gone")
                    fail("the SELECT was answered NO and must throw")
                } catch (_: ImapException) {
                }
            }
        }

        assertEquals(
            listOf(
                "> a1 LOGIN", "< a1 OK",
                "> a2 CAPABILITY", "< a2 OK",
                "> a3 SELECT \"Gone\"", "< a3 NO",
                "> a4 LOGOUT", "< a4 OK",
            ),
            sink.lines,
        )
    }

    @Test fun `LOGIN is reported as its verb alone`() {
        val sink = Recorded()
        val server = FakeImapServer { tag, _ -> ok(tag) }
        val onTheWire = server.use {
            it.traced(sink, it.config.copy(username = "alex.rivera", password = "hunter2 \"quoted\"")).close()
            it.commands.toList()
        }

        assertEquals(
            "the fixture must have received the credentials, or their absence below proves nothing",
            "LOGIN \"alex.rivera\" \"hunter2 \\\"quoted\\\"\"",
            onTheWire.first(),
        )
        assertEquals(
            listOf("> a1 LOGIN", "< a1 OK", "> a2 CAPABILITY", "< a2 OK", "> a3 LOGOUT", "< a3 OK"),
            sink.lines,
        )
    }

    @Test fun `AUTHENTICATE is reported as its verb alone, and the answer to the challenge not at all`() {
        val sink = Recorded()
        var authTag = ""
        val server = FakeImapServer { tag, line ->
            when {
                line.startsWith("AUTHENTICATE") -> { authTag = tag; "+ eyJzdGF0dXMiOiI0MDAifQ==\r\n" }
                // The empty client response to the challenge: the fixture reads it as a command.
                line.isEmpty() -> "$authTag NO [AUTHENTICATIONFAILED] invalid token\r\n"
                else -> ok(tag)
            }
        }
        val onTheWire = server.use {
            try {
                it.traced(sink, it.config.copy(username = "alex@outlook.com", accessToken = "AT-token-123"))
                fail("the token was refused and the session must not open")
            } catch (_: ImapException) {
            }
            it.commands.toList()
        }

        assertEquals(
            "the fixture must have received the token and the answer to its challenge",
            listOf("AUTHENTICATE XOAUTH2 ${xoauth2Payload("alex@outlook.com", "AT-token-123")}", ""),
            onTheWire,
        )
        assertEquals(listOf("> a1 AUTHENTICATE", "< a1 NO"), sink.lines)
    }

    @Test fun `an APPEND is reported as its command line, without the literal`() {
        val sink = Recorded()
        val body = "Subject: quarterly figures\r\n\r\nNot for the log.\r\n".toByteArray(Charsets.UTF_8)
        val server = FakeImapServer { tag, line ->
            if (line.startsWith("APPEND")) "+ go ahead\r\n" else ok(tag)
        }
        val literals = server.use {
            it.traced(sink).use { session -> session.append("INBOX", body, "\\Seen") }
            it.literals.toList()
        }

        assertEquals("the fixture must have received the body", 1, literals.size)
        assertEquals(
            listOf(
                "> a1 LOGIN", "< a1 OK",
                "> a2 CAPABILITY", "< a2 OK",
                "> a3 APPEND \"INBOX\" (\\Seen) {${body.size}}", "< a3 OK",
                "> a4 LOGOUT", "< a4 OK",
            ),
            sink.lines,
        )
    }

    @Test fun `a client given no sink reports to the one installed when the session opens`() {
        val sink = Recorded()
        val server = FakeImapServer { tag, _ -> ok(tag) }
        val before = ImapCommandTap.installed
        ImapCommandTap.installed = sink
        try {
            server.use { it.session().close() }
        } finally {
            ImapCommandTap.installed = before
        }

        assertEquals(
            listOf("> a1 LOGIN", "< a1 OK", "> a2 CAPABILITY", "< a2 OK", "> a3 LOGOUT", "< a3 OK"),
            sink.lines,
        )
    }
}

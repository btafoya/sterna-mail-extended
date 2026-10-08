package app.sterna.core.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * How many writes a session hands its socket, and what is in each. The stream is not buffered, so
 * a write is a segment: a `DONE` split from its CRLF is a different conversation on the wire, and
 * [FakeImapServer], which reads lines, cannot tell the two apart. Hence a socket that answers from
 * a script and keeps every write as it was made.
 */
class SessionWritesOnTheSocketTest {

    private class ScriptedSocket(script: String) : Socket() {
        val writes = mutableListOf<String>()
        private val scripted = ByteArrayInputStream(script.toByteArray(Charsets.ISO_8859_1))
        private var timeout = 0
        private val recording = object : OutputStream() {
            override fun write(b: Int) { writes += String(byteArrayOf(b.toByte()), Charsets.ISO_8859_1) }
            override fun write(b: ByteArray, off: Int, len: Int) { writes += String(b, off, len, Charsets.ISO_8859_1) }
        }

        override fun getInputStream(): InputStream = scripted
        override fun getOutputStream(): OutputStream = recording
        override fun getSoTimeout(): Int = timeout
        override fun setSoTimeout(millis: Int) { timeout = millis }
    }

    private class Recorded : ImapCommandSink {
        val lines = mutableListOf<String>()
        override fun sent(tag: String, command: String) { lines += "> $tag $command" }
        override fun completed(tag: String, status: String) { lines += "< $tag $status" }
    }

    @Test fun `the DONE of an IDLE leaves in one write with its CRLF`() {
        val socket = ScriptedSocket("+ idling\r\n* 4 EXISTS\r\na1 OK IDLE terminated\r\n")

        assertTrue(ImapSession(socket).idle(timeoutMs = 1_000))

        assertEquals(listOf("a1 IDLE\r\n", "DONE\r\n"), socket.writes)
    }

    @Test fun `the answer to a refused XOAUTH2 token is one bare CRLF`() {
        val socket = ScriptedSocket("+ eyJzdGF0dXMiOiI0MDAifQ==\r\na1 NO [AUTHENTICATIONFAILED] invalid token\r\n")

        try {
            ImapSession(socket).authenticateXoauth2("alex@outlook.com", "AT-token-123")
            fail("the token was refused")
        } catch (_: ImapException) {
        }

        assertEquals(
            listOf("a1 AUTHENTICATE XOAUTH2 ${xoauth2Payload("alex@outlook.com", "AT-token-123")}\r\n", "\r\n"),
            socket.writes,
        )
    }

    @Test fun `an APPEND writes its line, then the octets untouched, then the closing CRLF`() {
        val socket = ScriptedSocket("+ go ahead\r\na1 OK APPEND completed\r\n")

        ImapSession(socket).append("INBOX", "abc".toByteArray(Charsets.UTF_8), "\\Seen")

        assertEquals(listOf("a1 APPEND \"INBOX\" (\\Seen) {3}\r\n", "abc", "\r\n"), socket.writes)
    }

    @Test fun `a tagged line that states no status is not reported as one`() {
        val sink = Recorded()
        val socket = ScriptedSocket("a1\r\n")

        try {
            ImapSession(socket, sink).command("NOOP")
            fail("a completion without a status is not an OK")
        } catch (_: ImapException) {
        }

        assertEquals(listOf("> a1 NOOP"), sink.lines)
    }
}

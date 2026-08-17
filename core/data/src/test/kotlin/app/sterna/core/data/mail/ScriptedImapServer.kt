package app.sterna.core.data.mail

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A scripted IMAP server on loopback, for `core/data`.
 *
 * `core/imap`'s own `FakeImapServer` is internal to that module's test source set and invisible
 * here, and the question this module has to answer is what a REAL [ImapMailService] puts on the
 * wire — which no stand-in for the service could ever show. So: a socket, a thread, and a
 * [responder] handed the tag and the command line (tag stripped) that returns the whole raw
 * response, tagged line included. Every line received is recorded in [commands], in order.
 *
 * ⭐ **It serves connections one after another, not one and then nothing.** `withSession`'s reply to
 * a failed block is to close the connection and open a fresh one, so a server that stops listening
 * after the first hides exactly the behaviour a test of that retry has to see: the second attempt
 * would fail on "connection refused" instead of reaching the server, and a replayed command would
 * read as no command at all.
 *
 * ⭐ **It also answers a literal**, i.e. a command line ending in `{n}`: `+ go ahead`, then n
 * characters of payload, then the tagged line the [responder] gives for the command. Without it an
 * `APPEND`'s MIME would come back through [commands] as a dozen invented commands. ⚠ The count is
 * IMAP's byte count read as characters — true for the ASCII messages these tests append, and not a
 * general-purpose IMAP server.
 */
internal class ScriptedImapServer(
    private val responder: (tag: String, line: String) -> String,
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, tag stripped, in order — across every connection. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** The payload of every literal received, in order — an APPEND's MIME, verbatim. */
    val literals: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /**
     * How many connections have been ACCEPTED, over the life of this server.
     *
     * ⭐ [issued] cannot answer "did this call dial at all?": it filters the `LOGIN` line out (its
     * arguments are credentials), so a caller that connects, logs in and then asks nothing looks
     * exactly like one that never opened a socket. A refusal that is supposed to cost NOTHING is
     * read here, and only here. Incremented before the greeting is written, so any client that got
     * past `connect` has already been counted.
     */
    val connections = AtomicInteger(0)

    val host: String = server.inetAddress.hostAddress ?: "127.0.0.1"
    val port: Int = server.localPort

    private val thread = Thread(::serve, "scripted-imap").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            while (true) {
                server.accept().use { socket ->
                    connections.incrementAndGet()
                    converse(socket)
                }
            }
        }
    }

    private fun converse(socket: Socket) {
        val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)
        writer.write("* OK scripted IMAP ready\r\n")
        writer.flush()
        while (true) {
            val raw = reader.readLine() ?: return
            val tag = raw.substringBefore(' ')
            val line = raw.substringAfter(' ')
            commands.add(line)
            LITERAL.find(line)?.let { match ->
                writer.write("+ go ahead\r\n")
                writer.flush()
                literals.add(read(reader, match.groupValues[1].toInt()))
                reader.readLine()
            }
            val answer = responder(tag, line)
            writer.write(answer)
            writer.flush()
            // ⭐ A LOGOUT the responder ANSWERED ends the connection, as a real server's does. One it
            // left UNANSWERED must not: returning would close the socket under `.use`, the client
            // would read end-of-stream and `ImapParser` would throw at once — so a `close()` with no
            // bound on its read would still come back, and a test of that bound would be green
            // without it. A peer that goes mute without sending FIN is the case being reproduced.
            // ⚠ The cost is that this thread then stays parked on `readLine` until the client closes
            // its own socket (or for the life of the JVM if it never does). It is a daemon thread,
            // and the only responder that leaves a LOGOUT unanswered is the one testing exactly that.
            // ⛔ And the cost lands on the NEXT connection, because [serve] is sequential — one
            // connection at a time. A responder written with `else -> ""` (the banal way to say "I
            // ignore this command") therefore parks this thread for good, and the following attempt
            // dies on "connection refused": a test of a retry then silently tests something else.
            if (line.startsWith("LOGOUT") && answer.isNotEmpty()) return
        }
    }

    private fun read(reader: BufferedReader, size: Int): String {
        val buffer = CharArray(size)
        var got = 0
        while (got < size) {
            val n = reader.read(buffer, got, size - got)
            if (n < 0) break
            got += n
        }
        return String(buffer, 0, got)
    }

    /** Command lines received so far, excluding the LOGIN (whose arguments are credentials). */
    fun issued(): List<String> = commands.toList().filterNot { it.startsWith("LOGIN") }

    /** The received lines that start with [prefix] — what an assertion about a destroy reads. */
    fun issued(prefix: String): List<String> = issued().filter { it.startsWith(prefix) }

    override fun close() {
        runCatching { server.close() }
        thread.interrupt()
    }

    private companion object {
        /** The trailing `{123}` of a command that announces a literal. */
        val LITERAL = Regex("\\{(\\d+)}$")
    }
}

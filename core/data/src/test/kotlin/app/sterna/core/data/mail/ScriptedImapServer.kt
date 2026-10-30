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
 */
internal class ScriptedImapServer(
    private val goAhead: (tag: String, line: String) -> String,
    private val responder: (tag: String, line: String) -> String,
) : Closeable {

    constructor(responder: (tag: String, line: String) -> String) : this({ _, _ -> "+ go ahead\r\n" }, responder)

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, tag stripped, in order, across every connection. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /** The payload of every literal received, in order, an APPEND's MIME, verbatim. */
    val literals: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    /**
     * How many connections have been accepted, over the life of this server.
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
            val literal = LITERAL.find(line)
            if (literal != null) {
                val reply = goAhead(tag, line)
                writer.write(reply)
                writer.flush()
                if (!reply.startsWith("+")) continue
                literals.add(read(reader, literal.groupValues[1].toInt()))
                reader.readLine()
            }
            val answer = responder(tag, line)
            if (answer == HANG_UP) return
            if (answer == RESET) {
                socket.setSoLinger(true, 0)
                return
            }
            writer.write(answer)
            writer.flush()
            // A LOGOUT the responder ANSWERED ends the connection, as a real server's does. One it
            // left unanswered must not: returning would close the socket under `.use`, the client
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

    /** The received lines that start with [prefix], what an assertion about a destroy reads. */
    fun issued(prefix: String): List<String> = issued().filter { it.startsWith(prefix) }

    override fun close() {
        runCatching { server.close() }
        thread.interrupt()
    }

    companion object {
        const val HANG_UP = "\u0000hang up"
        const val RESET = "\u0000reset"

        /** The trailing `{123}` of a command that announces a literal. */
        private val LITERAL = Regex("\\{(\\d+)}$")
    }
}

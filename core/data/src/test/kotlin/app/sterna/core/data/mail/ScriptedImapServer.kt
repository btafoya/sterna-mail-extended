package app.sterna.core.data.mail

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A one-connection, scripted IMAP server on loopback, for `core/data`.
 *
 * `core/imap`'s own `FakeImapServer` is internal to that module's test source set and invisible
 * here, and the question this module has to answer is what a REAL [ImapMailService] puts on the
 * wire — which no stand-in for the service could ever show. So: a socket, a thread, and a
 * [responder] handed the tag and the command line (tag stripped) that returns the whole raw
 * response, tagged line included. Every line received is recorded in [commands], in order.
 */
internal class ScriptedImapServer(
    private val responder: (tag: String, line: String) -> String,
) : Closeable {

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    /** Every command line received, tag stripped, in order. */
    val commands: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

    val host: String = server.inetAddress.hostAddress ?: "127.0.0.1"
    val port: Int = server.localPort

    private val thread = Thread(::serve, "scripted-imap").apply { isDaemon = true; start() }

    private fun serve() {
        runCatching {
            server.accept().use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                val writer = OutputStreamWriter(socket.outputStream, Charsets.ISO_8859_1)
                writer.write("* OK scripted IMAP ready\r\n")
                writer.flush()
                while (true) {
                    val raw = reader.readLine() ?: break
                    val tag = raw.substringBefore(' ')
                    val line = raw.substringAfter(' ')
                    commands.add(line)
                    writer.write(responder(tag, line))
                    writer.flush()
                    if (line.startsWith("LOGOUT")) break
                }
            }
        }
    }

    /** Command lines received so far, excluding the LOGIN (whose arguments are credentials). */
    fun issued(): List<String> = commands.toList().filterNot { it.startsWith("LOGIN") }

    /** The received lines that start with [prefix] — what an assertion about a destroy reads. */
    fun issued(prefix: String): List<String> = issued().filter { it.startsWith(prefix) }

    override fun close() {
        runCatching { server.close() }
        thread.interrupt()
    }
}

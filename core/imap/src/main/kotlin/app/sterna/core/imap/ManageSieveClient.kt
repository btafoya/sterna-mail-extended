package app.sterna.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The ManageSieve port (RFC 5804 §1.8), and the only one Sterna tries: there is no setting for it. */
const val MANAGESIEVE_PORT = 4190

/** Short on purpose: a filtered port answers nothing, and the filters screen waits on this. */
internal const val MANAGESIEVE_CONNECT_TIMEOUT_MS = 5_000

/** One command's answer. The slowest is `PUTSCRIPT`, where the server compiles the script. */
internal const val MANAGESIEVE_COMMAND_TIMEOUT_MS = 15_000

/**
 * How long the first octet of a response written in the clear after the `OK` to `STARTTLS` is
 * waited for, as Apache ManageSieve writes one. Every handshake on a server that writes nothing pays
 * this once.
 */
internal const val MANAGESIEVE_CLEAR_AFTER_STARTTLS_MS = 300

/**
 * Above this a literal is not a Sieve script but a server (or something else on port 4190) about to
 * exhaust the heap. Servers cap scripts far lower, Dovecot at 1 MiB by default.
 */
internal const val MANAGESIEVE_MAX_LITERAL_OCTETS = 8 * 1024 * 1024

class ManageSieveException(
    message: String,
    val kind: Kind,
    /** The human-readable string of the server's `NO` / `BYE`, when it gave one. */
    val serverText: String? = null,
    /** The response code of a `NO` / `BYE`, read from between its parentheses. */
    val responseCode: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Each kind is a different sentence on the filters screen. */
    enum class Kind {
        /** Refused, timed out, unknown host, cut mid-dialogue or mid-handshake, a failed TLS handshake, or not speaking ManageSieve at all. */
        UNREACHABLE,

        /** No `STARTTLS` offered, or no `PLAIN` offered once encrypted. */
        INSECURE,

        /** The server said `NO` or `BYE`, authentication included. */
        REFUSED,
    }
}

/** One entry of `LISTSCRIPTS`. */
data class SieveScriptInfo(val name: String, val isActive: Boolean)

/** What the server advertised once encrypted (RFC 5804 §1.7). Names are upper-cased. */
class SieveCapabilities internal constructor(private val entries: Map<String, String?>) {
    val startTls: Boolean get() = "STARTTLS" in entries

    val saslMechanisms: List<String>
        get() = entries["SASL"].orEmpty().split(' ', '\t').filter { it.isNotBlank() }.map { it.uppercase() }

    val offersPlain: Boolean get() = "PLAIN" in saslMechanisms

    /** Present only on RFC 5804 servers, and what `CHECKSCRIPT` requires. */
    val version: String? get() = entries["VERSION"]

    /** The Sieve extensions the server's interpreter accepts, as listed, e.g. `fileinto`. */
    val sieveExtensions: List<String>
        get() = entries["SIEVE"].orEmpty().split(' ', '\t').filter { it.isNotBlank() }

    override fun toString(): String = "SieveCapabilities($entries)"
}

internal enum class SieveTransport {
    STARTTLS,

    /**
     * Tests only, and unreachable from outside this module. `STARTTLS` is still required and still
     * sent; only the handshake is skipped, so a loopback server with no certificate sees the exact
     * dialogue the shipped client writes.
     */
    STARTTLS_WITHOUT_HANDSHAKE_FOR_TESTS,
}

/** A ManageSieve (RFC 5804) client. One connection per [session] call, closed before it returns. */
class ManageSieveClient internal constructor(
    internal val transport: SieveTransport,
    /** A test seam: a factory that trusts the test certificate. `internal`, so no other module can pass one. */
    internal val tlsFactory: SSLSocketFactory = platformTlsFactory,
) {

    constructor() : this(SieveTransport.STARTTLS)

    /**
     * Connect to [host]:[port], upgrade with STARTTLS, authenticate with SASL PLAIN, run [block],
     * then `LOGOUT`. A failure of the connection or of the dialogue is a [ManageSieveException]. A
     * [port] out of range is an `IllegalArgumentException`. What [block] throws passes through,
     * except an `IOException`, which is classified like a failure of the connection.
     */
    suspend fun <T> session(
        host: String,
        port: Int,
        username: String,
        password: String,
        connectTimeoutMs: Int = MANAGESIEVE_CONNECT_TIMEOUT_MS,
        commandTimeoutMs: Int = MANAGESIEVE_COMMAND_TIMEOUT_MS,
        block: ManageSieveSession.() -> T,
    ): T = withContext(Dispatchers.IO) {
        converse(host, port, username, password, connectTimeoutMs.coerceAtLeast(0), commandTimeoutMs.coerceAtLeast(0), block)
    }

    /**
     * Credentials leave only over TLS, and only to a server that offered `PLAIN` over TLS. The
     * capabilities read in the clear are used for one thing, whether `STARTTLS` exists, and are then
     * dropped: those that decide what happens next are read again over TLS, since anything a
     * man-in-the-middle stripped or added before the handshake must not decide it.
     */
    private fun <T> converse(
        host: String,
        port: Int,
        username: String,
        password: String,
        connectTimeout: Int,
        commandTimeout: Int,
        block: ManageSieveSession.() -> T,
    ): T {
        val plain = Socket()
        var socket: Socket = plain
        try {
            plain.connect(InetSocketAddress(host, port), connectTimeout)
            plain.soTimeout = commandTimeout
            var wire = SieveWire(plain)
            val clearCapabilities = readCapabilities(wire)
            if (!clearCapabilities.startTls) throw insecure("the server does not offer STARTTLS")
            wire.command("STARTTLS").requireOk("STARTTLS")
            if (transport == SieveTransport.STARTTLS) {
                wire.discardClearResponse(MANAGESIEVE_CLEAR_AFTER_STARTTLS_MS, commandTimeout)
                val secure = tlsFactory.createSocket(plain, host, port, true) as SSLSocket
                secure.verifyingHostname()
                // A socket option does not travel to the wrapping socket, and the handshake reads.
                secure.soTimeout = commandTimeout
                secure.startHandshake()
                socket = secure
                wire = SieveWire(secure)
            }
            val tlsCapabilities = requestCapabilities(wire)
            if (!tlsCapabilities.offersPlain) throw insecure("the server does not offer PLAIN over TLS")
            wire.authenticatePlain(username, password)
            val session = ManageSieveSession(wire, tlsCapabilities)
            val result = session.block()
            runCatching { session.logout() }
            return result
        } catch (e: IOException) {
            throw ManageSieveException("ManageSieve connection failed: $e", manageSieveFailureKind(e), cause = e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readCapabilities(wire: SieveWire): SieveCapabilities =
        parseSieveCapabilities(wire.readResponse().requireOk("capabilities").lines)

    /**
     * RFC 5804 §2.2 has the server re-issue its capabilities after the handshake; Apache ManageSieve
     * stays silent until the next command. So both `CAPABILITY` and a tagged `NOOP` go out, and the
     * responses are read up to the `NOOP`'s. Without that end marker, a server that both re-issues
     * and answers `CAPABILITY` leaves a second `OK` in the stream, which `AUTHENTICATE` would read as
     * its own success. A `NO` also ends the wait: a server without `NOOP` refuses it, and nothing
     * refuses `CAPABILITY`. The capabilities kept are the last block that carries any; the answer to
     * `NOOP` carries none.
     */
    private fun requestCapabilities(wire: SieveWire): SieveCapabilities {
        wire.send(commandLine("CAPABILITY"), commandLine("NOOP", sieveQuoted(RESUME_TAG)))
        var latest: List<List<SieveToken>> = emptyList()
        repeat(MAX_RESUME_RESPONSES) {
            val response = wire.readResponse()
            if (response.status == "BYE") throw protocolError("BYE while waiting for the capabilities after STARTTLS")
            if (response.status == "OK" && response.code == "TAG $RESUME_TAG") return parseSieveCapabilities(latest)
            if (response.status == "NO") return parseSieveCapabilities(latest)
            if (response.lines.isNotEmpty()) latest = response.lines
        }
        throw protocolError("no answer to NOOP after $MAX_RESUME_RESPONSES responses")
    }

    private fun insecure(message: String) = ManageSieveException(message, ManageSieveException.Kind.INSECURE)

    private companion object {
        val platformTlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory

        /**
         * Fixed rather than drawn per connection: the responses read after STARTTLS come only from
         * the encrypted stream, and the sole thing the tag must not collide with is another answer
         * of that same exchange.
         */
        const val RESUME_TAG = "sterna"

        /** A server that re-issues, then answers `CAPABILITY` and `NOOP`: three responses. */
        const val MAX_RESUME_RESPONSES = 3
    }
}

/** An authenticated ManageSieve connection, valid only inside [ManageSieveClient.session]. */
class ManageSieveSession internal constructor(
    private val wire: SieveWire,
    val capabilities: SieveCapabilities,
) {
    fun listScripts(): List<SieveScriptInfo> =
        wire.command("LISTSCRIPTS").requireOk("LISTSCRIPTS").lines.map { line ->
            val name = (line.first() as? SieveToken.Text)?.text
                ?: throw protocolError("LISTSCRIPTS line without a script name: $line")
            SieveScriptInfo(name, line.drop(1).any { it is SieveToken.Atom && it.text.equals("ACTIVE", ignoreCase = true) })
        }

    fun getScript(name: String): String {
        val response = wire.command("GETSCRIPT", sieveString(name)).requireOk("GETSCRIPT")
        return (response.lines.firstOrNull()?.firstOrNull() as? SieveToken.Text)?.text
            ?: throw protocolError("GETSCRIPT answered OK without a script")
    }

    /** `false` when not asked: without `VERSION` the server has no `CHECKSCRIPT`, and `PUTSCRIPT` judges alone. */
    fun checkScript(script: String): Boolean {
        if (capabilities.version == null) return false
        wire.command("CHECKSCRIPT", sieveLiteral(script)).requireOk("CHECKSCRIPT")
        return true
    }

    fun putScript(name: String, script: String) {
        wire.command("PUTSCRIPT", sieveString(name), sieveLiteral(script)).requireOk("PUTSCRIPT")
    }

    /** An empty [name] deactivates every script (RFC 5804 §2.8). */
    fun setActive(name: String) {
        wire.command("SETACTIVE", sieveString(name)).requireOk("SETACTIVE")
    }

    internal fun logout() {
        wire.command("LOGOUT")
    }
}

internal sealed interface SieveToken {
    data class Atom(val text: String) : SieveToken

    /** A quoted string or a literal, already decoded: the grammar makes them interchangeable. */
    data class Text(val text: String) : SieveToken

    data object Open : SieveToken

    data object Close : SieveToken
}

internal class SieveResponse(
    /** The lines before the status, each tokenised; empty lines dropped. */
    val lines: List<List<SieveToken>>,
    /** `OK`, `NO` or `BYE`, upper-cased. */
    val status: String,
    val code: String?,
    val text: String?,
) {
    /** [what] names the command and never quotes it: an `AUTHENTICATE` carries the password. */
    fun requireOk(what: String): SieveResponse {
        if (status == "OK") return this
        val detail = listOfNotNull(status, code?.let { "($it)" }, text).joinToString(" ")
        throw ManageSieveException("$what: $detail", ManageSieveException.Kind.REFUSED, serverText = text, responseCode = code)
    }
}

internal class SieveWire(private val socket: Socket) {
    private val reader = SieveReader(BufferedInputStream(socket.inputStream))
    private val output: OutputStream = socket.outputStream

    fun command(verb: String, vararg arguments: ByteArray): SieveResponse {
        send(commandLine(verb, *arguments))
        return readResponse()
    }

    /** Every line in one write and one flush; the caller reads the responses. */
    fun send(vararg lines: ByteArray) {
        lines.forEach(output::write)
        output.flush()
    }

    fun authenticatePlain(username: String, password: String) {
        command("AUTHENTICATE", sieveQuoted("PLAIN"), sieveQuoted(saslPlainPayload(username, password))).requireOk("AUTHENTICATE")
    }

    fun readResponse(): SieveResponse = reader.readResponse()

    /**
     * Reads and drops at most one response, so that its octets are not taken for the server's
     * handshake. It is never interpreted: it arrives in the clear, where anyone on the path can
     * write it. Only its first octet waits [firstOctetMs]; the rest of it waits [commandTimeout], so
     * a response cut across two segments is read whole. Whatever this leaves, the socket is back on
     * [commandTimeout].
     */
    fun discardClearResponse(firstOctetMs: Int, commandTimeout: Int) {
        socket.soTimeout = firstOctetMs
        val arrived = reader.octetArrives()
        socket.soTimeout = commandTimeout
        if (arrived) reader.readResponse()
    }
}

internal fun commandLine(verb: String, vararg arguments: ByteArray): ByteArray {
    val line = ByteArrayOutputStream()
    line.write(verb.toByteArray(Charsets.US_ASCII))
    for (argument in arguments) {
        line.write(' '.code)
        line.write(argument)
    }
    line.write('\r'.code)
    line.write('\n'.code)
    return line.toByteArray()
}

/**
 * Reads responses as octets. A literal's length is a count of octets, so no layer here decodes
 * characters or splits lines before the literal has been consumed: a script line reading `OK` must
 * stay inside the script.
 */
internal class SieveReader(private val input: InputStream) {
    private var pending = -1

    fun readResponse(): SieveResponse {
        val lines = mutableListOf<List<SieveToken>>()
        while (true) {
            val tokens = readLine()
            if (tokens.isEmpty()) continue
            val status = (tokens.first() as? SieveToken.Atom)?.text?.uppercase()
            if (status != null && status in FINAL_STATUSES) return finalResponse(status, tokens.drop(1), lines)
            lines += tokens
        }
    }

    private fun finalResponse(status: String, rest: List<SieveToken>, lines: List<List<SieveToken>>): SieveResponse {
        var i = 0
        var code: String? = null
        if (rest.firstOrNull() == SieveToken.Open) {
            val parts = mutableListOf<String>()
            var depth = 0
            while (i < rest.size) {
                val token = rest[i++]
                when (token) {
                    SieveToken.Open -> if (depth++ > 0) parts += "("
                    SieveToken.Close -> if (--depth == 0) break else parts += ")"
                    is SieveToken.Atom -> parts += token.text
                    is SieveToken.Text -> parts += token.text
                }
            }
            code = parts.joinToString(" ")
        }
        val text = rest.drop(i).firstNotNullOfOrNull { (it as? SieveToken.Text)?.text }
        return SieveResponse(lines, status, code, text)
    }

    /**
     * Whether an octet comes within the socket's read delay. One that comes is kept for the next
     * read. A `SocketTimeoutException` on a socket's read consumes nothing, so the stream stays
     * usable after a `false`.
     */
    fun octetArrives(): Boolean {
        if (pending >= 0) return true
        val b = try {
            input.read()
        } catch (e: SocketTimeoutException) {
            return false
        }
        if (b < 0) throw EOFException("connection closed")
        pending = b
        return true
    }

    fun readLine(): List<SieveToken> {
        val tokens = mutableListOf<SieveToken>()
        while (true) {
            when (val b = next()) {
                SP, TAB -> Unit
                CR -> {
                    if (next() != LF) throw protocolError("CR not followed by LF")
                    return tokens
                }
                LF -> return tokens
                DQUOTE -> tokens += SieveToken.Text(quoted())
                LBRACE -> tokens += SieveToken.Text(literal())
                LPAREN -> tokens += SieveToken.Open
                RPAREN -> tokens += SieveToken.Close
                else -> tokens += SieveToken.Atom(atom(b))
            }
        }
    }

    private fun quoted(): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            when (val b = next()) {
                DQUOTE -> return bytes.toString(Charsets.UTF_8.name())
                BACKSLASH -> bytes.write(next())
                CR, LF -> throw protocolError("line break inside a quoted string")
                else -> bytes.write(b)
            }
        }
    }

    private fun literal(): String {
        var length = 0L
        var b = next()
        if (b !in DIGITS) throw protocolError("literal without a length")
        while (b in DIGITS) {
            length = length * 10 + (b - '0'.code)
            if (length > MANAGESIEVE_MAX_LITERAL_OCTETS) throw protocolError("literal of more than $MANAGESIEVE_MAX_LITERAL_OCTETS octets")
            b = next()
        }
        if (b == PLUS) b = next()
        if (b != RBRACE || next() != CR || next() != LF) throw protocolError("malformed literal header")
        val octets = ByteArray(length.toInt())
        var read = 0
        while (read < octets.size) {
            val n = input.read(octets, read, octets.size - read)
            if (n < 0) throw EOFException("connection closed inside a literal")
            read += n
        }
        return String(octets, Charsets.UTF_8)
    }

    private fun atom(first: Int): String {
        val bytes = ByteArrayOutputStream()
        bytes.write(first)
        while (true) {
            val b = next()
            if (b == SP || b == TAB || b == CR || b == LF || b == LPAREN || b == RPAREN) {
                pending = b
                return bytes.toString(Charsets.UTF_8.name())
            }
            bytes.write(b)
        }
    }

    private fun next(): Int {
        if (pending >= 0) return pending.also { pending = -1 }
        val b = input.read()
        if (b < 0) throw EOFException("connection closed")
        return b
    }

    private companion object {
        const val SP = ' '.code
        const val TAB = '\t'.code
        const val CR = '\r'.code
        const val LF = '\n'.code
        const val DQUOTE = '"'.code
        const val BACKSLASH = '\\'.code
        const val LBRACE = '{'.code
        const val RBRACE = '}'.code
        const val LPAREN = '('.code
        const val RPAREN = ')'.code
        const val PLUS = '+'.code
        val DIGITS = '0'.code..'9'.code
        val FINAL_STATUSES = setOf("OK", "NO", "BYE")
    }
}

/** The capability lines of a greeting or of the post-STARTTLS re-issue, names upper-cased. */
internal fun parseSieveCapabilities(lines: List<List<SieveToken>>): SieveCapabilities =
    SieveCapabilities(
        lines.associate { line ->
            val name = (line.first() as? SieveToken.Text)?.text
                ?: throw protocolError("capability line without a name: $line")
            name.uppercase() to (line.getOrNull(1) as? SieveToken.Text)?.text
        },
    )

/**
 * A client-to-server literal (RFC 5804 §4, `{n+}`, the only form a client sends). The length is the
 * count of UTF-8 octets that follow: counted in characters, a folder name like `项目` makes the
 * server stop reading mid-script and parse the rest as a command.
 */
internal fun sieveLiteral(text: String): ByteArray {
    val octets = text.toByteArray(Charsets.UTF_8)
    return "{${octets.size}+}\r\n".toByteArray(Charsets.US_ASCII) + octets
}

internal fun sieveQuoted(text: String): ByteArray =
    ("\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toByteArray(Charsets.UTF_8)

/**
 * Every I/O failure is [ManageSieveException.Kind.UNREACHABLE], a failed TLS handshake included. The
 * JDK raises `SSLHandshakeException` for a connection cut mid-handshake as well as for a bad
 * certificate, and `INSECURE` there would blame the server for the network. `INSECURE` stays for the
 * two facts read on the wire: no `STARTTLS` advertised, no `PLAIN` after the handshake.
 */
@Suppress("UNUSED_PARAMETER")
internal fun manageSieveFailureKind(error: IOException): ManageSieveException.Kind = ManageSieveException.Kind.UNREACHABLE

/** A quoted string when the grammar allows one (no CR, LF or NUL, at most 1024 octets), else a literal. */
internal fun sieveString(text: String): ByteArray =
    if (text.any { it == '\r' || it == '\n' || it == Char(0) } || text.toByteArray(Charsets.UTF_8).size > 1024) {
        sieveLiteral(text)
    } else {
        sieveQuoted(text)
    }

private fun protocolError(message: String) =
    ManageSieveException("not a ManageSieve response: $message", ManageSieveException.Kind.UNREACHABLE)

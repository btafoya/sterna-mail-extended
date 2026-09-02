package app.sterna.core.imap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SmtpException(message: String) : Exception(message)

/** The password-based SASL mechanisms this client can speak, in order of preference. */
internal enum class SmtpAuthMechanism { PLAIN, LOGIN }

/**
 * The SASL mechanism names advertised by an EHLO response, upper-cased, in the order the server
 * listed them; empty when the response carries no `AUTH` capability at all.
 *
 * [ehloLines] is the complete response (every `250-` continuation plus the final `250 ` line), each
 * one as it came off the wire. Two real spellings exist and both are handled: the RFC 4954 form
 * `250-AUTH PLAIN LOGIN` and the historical form `250-AUTH=PLAIN LOGIN`. Comparison downstream is
 * on whole tokens, which is why the list is tokenised here: a server offering `PLAIN-MD5` must not
 * read as offering `PLAIN`.
 */
internal fun advertisedAuthMechanisms(ehloLines: List<String>): List<String> {
    val mechanisms = mutableListOf<String>()
    for (raw in ehloLines) {
        // Drop the reply code and its separator ("250-" / "250 ") when there is one.
        val hasCode = raw.length > 4 && raw.take(3).all { it.isDigit() } && (raw[3] == '-' || raw[3] == ' ')
        val capability = (if (hasCode) raw.substring(4) else raw).trim()
        val keyword = capability.substringBefore(' ')
        if (!keyword.equals("AUTH", ignoreCase = true) && !keyword.startsWith("AUTH=", ignoreCase = true)) continue
        // "AUTH=PLAIN" packs the first mechanism into the keyword itself.
        val listed = keyword.substringAfter('=', "") + " " + capability.substringAfter(' ', "")
        mechanisms += listed.split(' ', '\t').filter { it.isNotBlank() }.map { it.uppercase() }
    }
    return mechanisms
}

/**
 * Which mechanism to authenticate with, given what the server [advertised].
 *
 * `PLAIN` first (autistici/inventati's submission service rejects `LOGIN` outright, Codeberg #145),
 * then `LOGIN`. A server that advertises **nothing** gets `LOGIN` tried at it exactly as this client
 * always has: that is what makes mail keep flowing for servers whose EHLO is silent about AUTH.
 * `null` means the server did say what it offers and none of it can be spoken here.
 */
internal fun chooseAuthMechanism(advertised: List<String>): SmtpAuthMechanism? = when {
    advertised.isEmpty() -> SmtpAuthMechanism.LOGIN
    advertised.any { it.equals("PLAIN", ignoreCase = true) } -> SmtpAuthMechanism.PLAIN
    advertised.any { it.equals("LOGIN", ignoreCase = true) } -> SmtpAuthMechanism.LOGIN
    else -> null
}

/**
 * The SASL PLAIN initial client response (base64), RFC 4616: `base64(NUL + user + NUL + password)`.
 * ONE base64 blob, not the two separate ones AUTH LOGIN sends.
 *
 * `Base64.getEncoder()` on purpose, the same choice [xoauth2Payload] makes: `getMimeEncoder()` (what
 * `OutgoingMime` uses for message bodies) wraps at 76 characters and would break the payload of a
 * long password on the wire.
 */
internal fun saslPlainPayload(username: String, password: String): String {
    val nul = Char(0) // SASL PLAIN field separator (NUL)
    return Base64.getEncoder().encodeToString("$nul$username$nul$password".toByteArray(Charsets.UTF_8))
}

/** A file or inline attachment to include in an outgoing message. */
data class OutgoingAttachment(
    val name: String,
    val type: String,
    val bytes: ByteArray,
    /** Content-ID (no angle brackets) for an inline part referenced by `cid:` in the HTML body. */
    val cid: String? = null,
    /** When true the part is emitted inline (multipart/related) rather than as a file attachment. */
    val inline: Boolean = false,
)

/**
 * Whether a built message writes its blind copies as a `Bcc:` header.
 *
 * ⛔ **A type, and not a `Boolean` — that IS the guard.** `build(m, true)` compiled and named
 * nothing: a positional `true` puts the header into a message while the word "Bcc" appears nowhere
 * on the line, so a rule stated over source text cannot see it, and the one call site that must
 * never ask for it (the JMAP pre-built path, submitted verbatim) is written across several lines
 * where a call-line pin cannot reach it either. [WRITTEN] cannot be passed without spelling itself
 * out, positionally or not.
 */
enum class BlindCopies {
    /** No `Bcc:` header at all. The default, and what every byte that LEAVES the phone gets. */
    OMITTED,

    /** Write the blind recipients as a `Bcc:` header. Stored copies only — see [OutgoingMime.build]. */
    WRITTEN,
}

/** A message to submit over SMTP. Addresses are bare "name <addr>" or "addr" strings. */
data class OutgoingMessage(
    val from: String,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    /**
     * Blind-copy recipients: in the SMTP envelope (RCPT TO) and, on the wire, nowhere else.
     * A `Bcc:` header exists in exactly one place — the copy APPENDed to the Drafts folder,
     * which is read back and by nobody else ([OutgoingMime.build] asked for [BlindCopies.WRITTEN]).
     */
    val bcc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    /** Optional HTML body; when set the message is sent as text/html. */
    val html: String? = null,
    val inReplyTo: String? = null,
    val references: String? = null,
    /** RFC 5322 Message-ID (without angle brackets); generated by the caller. */
    val messageId: String,
    /** Epoch millis for the Date header. */
    val dateMillis: Long,
    val attachments: List<OutgoingAttachment> = emptyList(),
    /**
     * A complete pre-built MIME entity (its own Content-Type headers + body) that
     * REPLACES the normal body/attachments. [body]/[html]/[attachments] are ignored when set.
     *
     * ⭐ NOT a PGP field, despite where it came from. It is the seam through which any payload
     * this builder cannot express reaches the wire byte for byte: PGP/MIME (multipart/signed,
     * multipart/encrypted — attachments already inside, signature computed over these very
     * bytes) and RFC 8098's `multipart/report` read receipt ([app.sterna.core.data.mail]'s
     * `readReceiptEntity`), which [buildBodyEntity] cannot build either — it knows `text/plain`,
     * `text/html`, `multipart/mixed` and `multipart/related`, and nothing else.
     *
     * It used to be called `pgpEntity`, and the outbox refused to store one unless a PGP mode
     * came with it — so a caller handing an entity and no mode had it dropped in silence and the
     * message left as an empty shell. The name is the only thing that made that look sane.
     */
    val prebuiltEntity: String? = null,
    /**
     * Ask the recipient's client for a read receipt: [OutgoingMime.build] then writes
     * `Disposition-Notification-To:` (RFC 8098) naming THIS message's [from].
     *
     * A flag and not an address, deliberately. The receipt has to come back to the mailbox the
     * correspondent can see, which is the `From` — including a delegated "on behalf" send, where
     * the submitting identity is somebody else's. Deriving it here means no second address exists
     * to drift from the visible one, and none can reach the MIME without the callers'
     * single-line check and the [headerSafe] below, which is the whole CRLF-injection guard.
     */
    val requestReceipt: Boolean = false,
)

/**
 * How long to wait for the TCP connection, and for the answer to one command of the dialogue.
 *
 * The values JMAP submission already uses (`JmapClient.defaultHttpClient()`: `connectTimeout(20,
 * SECONDS)` / `readTimeout(30, SECONDS)`). One convention for the whole app, and no setting: there
 * is no user-facing timeout and there will not be one.
 */
internal const val SMTP_CONNECT_TIMEOUT_MS = 20_000
internal const val SMTP_COMMAND_TIMEOUT_MS = 30_000

/**
 * `0` is the socket's own default and means "block, with no deadline of ours" — the regime kept by
 * the ONE read of a submission that is legitimately long: the 250 that answers the dot ending the
 * body, behind which the server queues, signs (DKIM) and scans the message. Minutes are normal
 * there, and cutting a send the server went on to accept would leave `OutboxWorker` to retry it —
 * the message would go out TWICE, which nothing later can take back.
 */
internal const val SMTP_BLOCKING_READ = 0

/** Minimal SMTP submission client (EHLO, optional STARTTLS, AUTH, MAIL/RCPT/DATA). */
class SmtpClient {
    /**
     * Submit [message] through [config]'s server.
     *
     * [connectTimeoutMs] bounds the TCP connect (name resolution is not covered by it — no socket
     * option is). [commandTimeoutMs] bounds every read of the dialogue — greeting, EHLO, STARTTLS,
     * the EHLO after it, each step of AUTH, MAIL FROM, RCPT TO, DATA — and both TLS handshakes,
     * armed before them. Without either, a server that accepted the connection and then went quiet
     * left this blocked on a read for good: an outbox row stuck on "sending", with no error, nothing
     * to cancel and nothing to edit.
     *
     * ⛔ Neither bounds a WRITE: Java exposes no write timeout, and `soTimeout` is a read option. The
     * body — up to 200 MB of MIME with attachments — is therefore neither threatened nor protected
     * by these, and no value here should be chosen with it in mind. The read that FOLLOWS it is
     * unbounded on purpose, see [SMTP_BLOCKING_READ].
     *
     * Parameters with the shipped values as defaults, as `ImapClient.openSession` already does, so a
     * test can shorten them. ⛔ Not fields: this client is one injected instance shared by every
     * account, and a field would mix two of them.
     */
    suspend fun send(
        config: MailServerConfig,
        message: OutgoingMessage,
        connectTimeoutMs: Int = SMTP_CONNECT_TIMEOUT_MS,
        commandTimeoutMs: Int = SMTP_COMMAND_TIMEOUT_MS,
    ) = withContext(Dispatchers.IO) {
        val connectTimeout = connectTimeoutMs.coerceAtLeast(0)
        val commandTimeout = commandTimeoutMs.coerceAtLeast(0)
        val plain = Socket()
        // ⛔ What the finally closes, and STARTTLS reassigns it. Every way out of the dialogue has to
        // close the connection: the close used to be reachable only after a successful send, so a
        // rejected recipient leaked its socket — and so would every timeout added above.
        var socket: Socket = plain
        try {
            plain.connect(InetSocketAddress(config.host, config.port), connectTimeout)
            // ⚠ Armed BEFORE the handshake, and it has to be: startHandshake() READS from this
            // socket, so a server that accepts and then says nothing hangs it exactly as it hangs a
            // command read, and a bound set afterwards would never cover it.
            plain.soTimeout = commandTimeout
            if (config.security == MailSecurity.TLS) {
                val secure = (tlsFactory.createSocket(plain, config.host, config.port, true) as SSLSocket)
                    .verifyingHostname()
                secure.soTimeout = commandTimeout
                secure.startHandshake()
                socket = secure
            }
            var reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            var out: OutputStream = socket.outputStream

            /**
             * The lines of the response [read] last returned, continuations included — the EHLO
             * capabilities, which [read] itself throws away. Local to this send: a field would mix
             * the capabilities of two accounts sharing one client.
             */
            val lastResponse = mutableListOf<String>()

            fun read(): String {
                lastResponse.clear()
                var line = reader.readLine() ?: throw SmtpException("Connection closed")
                lastResponse += line
                // Skip continuation lines ("250-..."), keep reading until "250 ...".
                // ⚠ The RETURN VALUE is the final line and nothing else: `expect` below compares a
                // prefix on it, fifteen times per send. The accumulator only observes.
                while (line.length >= 4 && line[3] == '-') {
                    // ⛔ A response that stops between two of its lines is NOT a response: the final
                    // line never arrived, so the server never finished saying anything. Handing the
                    // last continuation back instead would pass `expect`'s prefix test — a cut EHLO
                    // would read as a success, its capabilities taken from a partial list, and the
                    // failure would surface far later on a step that is not the cause. The first line
                    // has always thrown here; so do the ones after it.
                    line = reader.readLine() ?: throw SmtpException("Connection closed mid-response")
                    lastResponse += line
                }
                return line
            }

            /**
             * The acknowledgement of the message BODY, and that read alone: the same reply reader
             * as [read], with the one tolerance a cut connection makes mandatory THERE and nowhere
             * else.
             *
             * RFC 5321 § 4.2.1: every line of a reply carries the same code, so a server that has
             * written `250-…` has already taken the message. Refusing a reply cut after that line
             * would surface as a failed send, `OutboxWorker` would retry it (`OutboxLogic
             * .shouldRetry` exempts nothing), and the recipient would get the message TWICE —
             * which nothing later can take back. So a cut after a continuation hands the last line
             * read back, and the CODE decides at the call site exactly as it does for a complete
             * reply: a cut `421-service closing` is still a failure.
             *
             * ⛔ A cut BEFORE any line still throws, like everywhere else: an acknowledgement the
             * server never began writing is not an acceptance, and counting it as one would lose
             * the message — irreversible the other way round.
             *
             * ⛔ Why [read] keeps its refusal: there a truncated reply must stay a failure. A cut
             * EHLO returned as a success has its capabilities read off a PARTIAL list, and the
             * failure surfaces far later on a step that is not the cause.
             */
            fun readBodyAck(): String {
                var line = reader.readLine() ?: throw SmtpException("Connection closed")
                while (line.length >= 4 && line[3] == '-') {
                    // ⭐ The tolerant exit, and the only one in this client. Its own `val` on
                    // purpose: `line = reader.readLine() ?: return line` hands back the OLD value
                    // while reading as if it handed back the new one, and this decision — send
                    // once, or send twice — has to be legible at a glance.
                    val next = reader.readLine() ?: return line
                    line = next
                }
                return line
            }

            fun expect(prefix: String, what: String) {
                val resp = read()
                if (!resp.startsWith(prefix)) throw SmtpException("$what: $resp")
            }

            fun write(line: String) {
                out.write((line + "\r\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }

            // Greeting + EHLO
            expect("220", "greeting")
            write("EHLO ${localHost()}")
            expect("250", "EHLO")
            var advertisedAuth = advertisedAuthMechanisms(lastResponse)

            if (config.security == MailSecurity.STARTTLS) {
                write("STARTTLS")
                expect("220", "STARTTLS")
                val secure = (tlsFactory.createSocket(socket, config.host, config.port, true) as SSLSocket)
                    .verifyingHostname()
                // The upgrade replaces the socket, and a socket option does not travel: this one is
                // armed on the new one, before its handshake, like the implicit-TLS one above.
                secure.soTimeout = commandTimeout
                secure.startHandshake()
                socket = secure
                reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                out = socket.outputStream
                write("EHLO ${localHost()}")
                expect("250", "EHLO (TLS)")
                // RFC 3207: what was advertised in the clear is void. Only this EHLO counts, and it is
                // exactly where a server that offered no AUTH before encryption finally lists one.
                advertisedAuth = advertisedAuthMechanisms(lastResponse)
            }

            // AUTH: XOAUTH2 (OAuth bearer) when a token is present, else the best password mechanism
            // the server actually offers (Codeberg #145 — inventati.org refuses LOGIN outright).
            if (config.accessToken != null) {
                write("AUTH XOAUTH2 ${xoauth2Payload(config.username, config.accessToken)}")
                val resp = read()
                if (!resp.startsWith("235")) {
                    // A rejected token comes back as "334 <base64 error>"; reply with an empty
                    // line so the server can finish (with its 535), then report the failure.
                    if (resp.startsWith("334")) { write(""); read() }
                    throw SmtpException("AUTH XOAUTH2: $resp")
                }
            } else {
                when (chooseAuthMechanism(advertisedAuth)) {
                    SmtpAuthMechanism.PLAIN -> {
                        // One command, initial response included; the server answers 235 or refuses.
                        write("AUTH PLAIN ${saslPlainPayload(config.username, config.password)}")
                        expect("235", "AUTH PLAIN")
                    }
                    SmtpAuthMechanism.LOGIN -> {
                        write("AUTH LOGIN")
                        expect("334", "AUTH")
                        write(base64(config.username))
                        expect("334", "AUTH user")
                        write(base64(config.password))
                        expect("235", "AUTH password")
                    }
                    // ⛔ The offered list, never the command: an exception is stored in the outbox and
                    // shown on screen, so a base64 credential in it would leak twice over.
                    null -> throw SmtpException(
                        "AUTH: no supported mechanism, server offers ${advertisedAuth.joinToString(" ")}",
                    )
                }
            }

            // Envelope
            write("MAIL FROM:<${OutgoingMime.envelopeAddress(message.from)}>")
            expect("250", "MAIL FROM")
            (message.to + message.cc + message.bcc).forEach { recipient ->
                write("RCPT TO:<${OutgoingMime.envelopeAddress(recipient)}>")
                expect("250", "RCPT TO")
            }

            // Data
            write("DATA")
            expect("354", "DATA")
            val mime = buildMime(message)
            mime.split("\r\n").forEach { line ->
                // Dot-stuffing: a line starting with '.' must be doubled.
                out.write(((if (line.startsWith(".")) ".$line" else line) + "\r\n").toByteArray(Charsets.UTF_8))
            }
            out.write(".\r\n".toByteArray(Charsets.UTF_8))
            out.flush()
            // ⭐ The regime changes here, and here only: what answers the body is the server's
            // queue, its DKIM signing and its virus scan. Minutes are normal, a short bound would
            // fail sends the server accepted, and OutboxWorker would send them a second time.
            //
            // ⚠ BOTH sockets, not just the current one. `plain` was armed right after the connect
            // and the TLS socket layered on top of it was armed again: what is armed twice is
            // disarmed twice. Whether `SSLSocket.setSoTimeout` reaches the socket it wraps is an
            // implementation question, and betting on it costs, when the bet is lost, exactly what
            // this whole change exists to prevent — a 30 s bound left standing on the 250 that
            // answers the body over TLS, the send cut, OutboxWorker retrying, the message sent
            // TWICE. A line of code is cheaper than the hypothesis.
            plain.soTimeout = SMTP_BLOCKING_READ
            socket.soTimeout = SMTP_BLOCKING_READ
            // ⛔ Not `expect`: a reply cut after a `250-` is an ACCEPTED message here, and only
            // here — see [readBodyAck]. The prefix test stays, so the code still decides.
            val ack = readBodyAck()
            if (!ack.startsWith("250")) throw SmtpException("message body: $ack")

            runCatching { write("QUIT") }
            Unit
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildMime(m: OutgoingMessage): String = OutgoingMime.build(m)

    private fun localHost(): String = "[127.0.0.1]"

    private fun base64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private companion object {
        val tlsFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
    }
}

/** Builds the RFC 5322 message for both SMTP submission and IMAP APPEND. */
object OutgoingMime {
    /**
     * [blindCopies] says whether the blind-copy recipients are written as a `Bcc:` header.
     *
     * ⛔ **[BlindCopies.OMITTED] by default, and that default IS the blind copy.** What this
     * function returns is fed to SMTP's `DATA` line by line, with no header filter of any kind
     * between here and the socket, so a `Bcc:` written for a submission hands every hidden
     * recipient to every visible one — the one mistake a mail client cannot take back. Over SMTP
     * the blind copies travel as `RCPT TO` envelope commands, and as nothing else.
     *
     * ⭐ The ONE caller that asks for [BlindCopies.WRITTEN] is the Drafts-folder APPEND
     * (ImapMailService's `appendDraft`), whose bytes are transmitted to no recipient at all: they
     * are stored, and that header is the only place a Cci can survive until the draft is reopened.
     *
     * ⚠ [BlindCopies] and not a `Boolean` since 2026-08-25: the parameter was a flag, and a
     * positional `true` — which names neither the flag nor the header — put the disclosure back
     * into a submitted message with every guard still green. See the enum.
     */
    fun build(m: OutgoingMessage, blindCopies: BlindCopies = BlindCopies.OMITTED): String {
        val date = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(m.dateMillis),
            java.time.ZoneOffset.UTC,
        ).format(DateTimeFormatter.RFC_1123_DATE_TIME)
        return buildString {
            append("From: ${headerSafe(m.from)}\r\n")
            // A draft may have no recipient yet (#69); an empty "To:" header is malformed, so omit
            // it entirely. A real send always has recipients, so this only affects saved drafts.
            if (m.to.isNotEmpty()) append("To: ${m.to.joinToString(", ") { headerSafe(it) }}\r\n")
            if (m.cc.isNotEmpty()) append("Cc: ${m.cc.joinToString(", ") { headerSafe(it) }}\r\n")
            // Stored copies only — see [blindCopies]. Without that test this line is a disclosure,
            // not a header: the same bytes are what a submission writes into DATA.
            if (blindCopies == BlindCopies.WRITTEN && m.bcc.isNotEmpty()) append("Bcc: ${m.bcc.joinToString(", ") { headerSafe(it) }}\r\n")
            append("Subject: ${encodeHeader(m.subject)}\r\n")
            append("Date: $date\r\n")
            append("Message-ID: <${headerSafe(m.messageId.trim('<', '>'))}>\r\n")
            m.inReplyTo?.let { append("In-Reply-To: <${headerSafe(it.trim('<', '>'))}>\r\n") }
            m.references?.let { append("References: ${headerSafe(it)}\r\n") }
            // Where a read receipt should be sent, if the recipient's client makes one at all
            // (RFC 8098). The address is the From, character for character — one mailbox, and the
            // one the correspondent can see. ⭐ Written HERE, above the body, so it is carried by
            // the PGP/MIME branch below too: that entity replaces the body, and a receipt emitted
            // inside it would leave every signed and every encrypted message asking for nothing.
            if (m.requestReceipt) append("Disposition-Notification-To: ${headerSafe(m.from)}\r\n")
            append("MIME-Version: 1.0\r\n")
            // A pre-built entity (PGP/MIME, or a multipart/report receipt) replaces the whole
            // body construction — see [OutgoingMessage.prebuiltEntity].
            if (m.prebuiltEntity != null) {
                append(m.prebuiltEntity)
            } else {
                append(buildBodyEntity(m))
            }
        }
    }

    /**
     * The message's MIME entity WITHOUT the top-level message headers: starts at
     * its own Content-Type line. Split out so the PGP path can build the exact
     * inner entity to sign/encrypt with the same code that normal sends use.
     */
    fun buildBodyEntity(m: OutgoingMessage): String {
        return buildString {
            val bodyContent = m.html ?: m.body
            val bodyType = if (m.html != null) "text/html" else "text/plain"
            val inlineParts = m.attachments.filter { it.inline }
            val fileParts = m.attachments.filter { !it.inline }
            when {
                // Plain body, nothing carried — single part (unchanged).
                inlineParts.isEmpty() && fileParts.isEmpty() -> {
                    append("Content-Type: $bodyType; charset=utf-8\r\n")
                    append("Content-Transfer-Encoding: base64\r\n")
                    append("\r\n")
                    append(base64(bodyContent.toByteArray(Charsets.UTF_8)))
                }
                // File attachments only — multipart/mixed (unchanged).
                inlineParts.isEmpty() -> {
                    val boundary = boundary(m, "mixed")
                    append("Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n\r\n")
                    append("--$boundary\r\n")
                    appendBodyPart(bodyType, bodyContent)
                    for (att in fileParts) {
                        append("--$boundary\r\n")
                        appendAttachmentPart(att)
                    }
                    append("--$boundary--\r\n")
                }
                // Inline images, no files — the message body is a multipart/related.
                fileParts.isEmpty() -> {
                    val related = boundary(m, "related")
                    append("Content-Type: multipart/related; type=\"$bodyType\"; boundary=\"$related\"\r\n\r\n")
                    appendRelatedParts(related, bodyType, bodyContent, inlineParts)
                }
                // Inline images AND files — multipart/mixed( multipart/related(html, images), files ).
                else -> {
                    val mixed = boundary(m, "mixed")
                    val related = boundary(m, "related")
                    append("Content-Type: multipart/mixed; boundary=\"$mixed\"\r\n\r\n")
                    append("--$mixed\r\n")
                    append("Content-Type: multipart/related; type=\"$bodyType\"; boundary=\"$related\"\r\n\r\n")
                    appendRelatedParts(related, bodyType, bodyContent, inlineParts)
                    for (att in fileParts) {
                        append("--$mixed\r\n")
                        appendAttachmentPart(att)
                    }
                    append("--$mixed--\r\n")
                }
            }
        }
    }

    /** Unique-per-message boundary, tagged so a nested mixed/related pair never collide. */
    private fun boundary(m: OutgoingMessage, tag: String): String =
        "----sterna_${tag}_${m.messageId.filter { it.isLetterOrDigit() }.take(24)}"

    /** The text/html (or text/plain) body part of a multipart entity. */
    private fun StringBuilder.appendBodyPart(bodyType: String, bodyContent: String) {
        append("Content-Type: $bodyType; charset=utf-8\r\n")
        append("Content-Transfer-Encoding: base64\r\n\r\n")
        append(base64(bodyContent.toByteArray(Charsets.UTF_8)))
        append("\r\n")
    }

    /** A file attachment part (Content-Disposition: attachment). */
    private fun StringBuilder.appendAttachmentPart(att: OutgoingAttachment) {
        val safeName = headerSafe(att.name).replace("\"", "")
        val safeType = headerSafe(att.type).replace("\"", "")
        append("Content-Type: $safeType; name=\"$safeName\"\r\n")
        append("Content-Transfer-Encoding: base64\r\n")
        append("Content-Disposition: attachment; filename=\"$safeName\"\r\n\r\n")
        append(base64(att.bytes))
        append("\r\n")
    }

    /** The body part plus each inline image (Content-ID + Content-Disposition: inline) of a related entity. */
    private fun StringBuilder.appendRelatedParts(
        related: String,
        bodyType: String,
        bodyContent: String,
        inlineParts: List<OutgoingAttachment>,
    ) {
        append("--$related\r\n")
        appendBodyPart(bodyType, bodyContent)
        for (att in inlineParts) {
            val safeName = headerSafe(att.name).replace("\"", "")
            val safeType = headerSafe(att.type).replace("\"", "")
            val safeCid = headerSafe(att.cid ?: "").trim().trim('<', '>').replace("\"", "")
            append("--$related\r\n")
            append("Content-Type: $safeType; name=\"$safeName\"\r\n")
            append("Content-Transfer-Encoding: base64\r\n")
            append("Content-ID: <$safeCid>\r\n")
            append("Content-Disposition: inline; filename=\"$safeName\"\r\n\r\n")
            append(base64(att.bytes))
            append("\r\n")
        }
        append("--$related--\r\n")
    }

    /**
     * MIME base64: 76-column lines separated by CRLF, which is what `Base64.getMimeEncoder()`
     * already produces on its own (`getMimeEncoder()` == `getMimeEncoder(76, "\r\n")`).
     *
     * ⛔ NOT `getMimeEncoder().encodeToString(…).replace("\n", "\r\n")`. That replace runs over
     * separators that are ALREADY `\r\n` and turns every one of them into `\r\r\n` — a bare CR
     * inside the body, which a DATA payload may not contain (RFC 5321 § 2.3.8). Parsers shrug at
     * it, so it goes unnoticed on a plain message; on a SIGNED one the bare CR is part of the
     * signed bytes, and a conforming relay that straightens the line changes the message, so the
     * recipient reads "invalid signature". At 57 bytes of input or less the encoder emits a single
     * 76-column line with no separator at all, so nothing breaks — which is why it hid for so long,
     * and why a witness has to feed it 58 bytes or more to see anything.
     */
    private fun base64(bytes: ByteArray): String =
        Base64.getMimeEncoder().encodeToString(bytes)

    /**
     * Strip CR/LF (and other control chars) from a structured header value so an
     * attacker-influenced address/Message-ID/References cannot smuggle extra headers
     * (e.g. a hidden Bcc:) or split the message. Reply/forward fields can echo a parsed
     * Message-ID/References straight from a hostile incoming message, so this is the sink
     * that must be clean.
     *
     * ⭐ Public, and ONE of it. A pre-built entity ([OutgoingMessage.prebuiltEntity]) is assembled
     * outside this file and can carry header-shaped lines of its own — RFC 8098's
     * `message/disposition-notification` part is nothing but fields, `Original-Message-ID` among
     * them, copied from a message a stranger wrote. That builder needs this exact filter, and a
     * second copy of it is how the two drift until one of them stops stripping something.
     */
    fun headerSafe(value: String): String =
        value.filterNot { it == '\r' || it == '\n' || it.code < 32 }

    /**
     * The addr-spec to put between the angle brackets of a `MAIL FROM:`/`RCPT TO:` command.
     * Uses the LAST '<' so a display name that itself contains '<' (e.g. a quoted phrase)
     * can't hijack the envelope address; the real addr-spec is the final <...>.
     *
     * The SMTP envelope is a line-oriented command dialogue, so it is a CR/LF sink exactly
     * like the message headers: an address with no angle brackets used to reach the wire
     * verbatim, and a `\r\n` in it appended a second envelope command (a hidden `RCPT TO`,
     * or a session-breaking garbage line). [headerSafe] is applied for the same reason it
     * guards the headers. Addresses reaching this point are already rejected upstream
     * (MailRepository); the filter here is the sink that must be clean regardless of caller.
     */
    internal fun envelopeAddress(address: String): String {
        val lt = address.lastIndexOf('<')
        val addr = if (lt >= 0) address.substring(lt + 1).substringBefore('>') else address
        return headerSafe(addr).trim()
    }

    /** RFC 2047-encode a header value if it contains non-ASCII OR control chars (CR/LF). */
    private fun encodeHeader(value: String): String =
        if (value.all { it.code in 32..126 }) {
            value
        } else {
            "=?utf-8?B?${Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))}?="
        }

    /** RFC 5322 "specials": their presence in a display name forces a quoted-string. */
    private val ADDRESS_SPECIALS = "()<>[]:;@\\,.\"".toSet()

    /**
     * Build a header-correct RFC 5322 mailbox from a display name + address: the bare
     * [address] when there is no name, otherwise `phrase <address>`. The phrase is emitted
     * as a bare atom when it is plain ASCII with no specials, as a quoted-string when it
     * holds ASCII specials or edge whitespace (escaping `\` first, then `"`), or as an
     * RFC 2047 encoded-word when it is non-ASCII. The result is already quoted/encoded as
     * needed, so callers must NOT quote or RFC 2047-encode it again (no double-encoding);
     * running [headerSafe] over the whole value stays fine as encoded-words and
     * quoted-strings are plain ASCII.
     */
    fun formatAddress(name: String?, address: String): String {
        if (name.isNullOrBlank()) return address
        return "${encodePhrase(name)} <$address>"
    }

    /** Encode a display-name phrase as atom / quoted-string / RFC 2047 encoded-word. */
    private fun encodePhrase(phrase: String): String = when {
        // Non-ASCII (or control chars) -> RFC 2047 encoded-word, itself a valid atom.
        phrase.any { it.code !in 32..126 } -> encodeHeader(phrase)
        // ASCII specials or leading/trailing space -> quoted-string (escape \ then ").
        phrase.any { it in ADDRESS_SPECIALS } || phrase != phrase.trim() ->
            "\"" + phrase.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // Plain atom(s) -> unchanged.
        else -> phrase
    }
}

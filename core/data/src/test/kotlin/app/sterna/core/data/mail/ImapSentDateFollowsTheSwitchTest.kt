package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.settings.OutgoingDateZones
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.time.ZoneId
import java.util.Collections

/**
 * The quoted-dates-in-UTC switch (#120) on the three IMAP writes of a date, read off the sockets:
 * the SMTP submission, the Sent copy's APPEND, a draft's APPEND. The device zone is injected at
 * +05:30, never the machine's, which is UTC in the build container and would make "ignored the
 * switch" and "obeyed it" the same bytes.
 */
class ImapSentDateFollowsTheSwitchTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val switch = MutableStateFlow(false)
    private fun service() = ImapMailService(
        ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), RecordingAccountStorage()),
        outgoingDates = OutgoingDateZones(switch, deviceZone = { kolkata }),
    )

    @Test(timeout = 30_000)
    fun `switch on, the submission and the sent copy are dated in UTC`() {
        switch.value = true
        val (submitted, appended) = send(service())
        assertEquals(UTC_DATE, dateLineOf(submitted))
        assertEquals(UTC_DATE, dateLineOf(appended))
    }

    @Test(timeout = 30_000)
    fun `switch off, the submission and the sent copy are dated in the device zone`() {
        switch.value = false
        val (submitted, appended) = send(service())
        assertEquals(DEVICE_DATE, dateLineOf(submitted))
        assertEquals(DEVICE_DATE, dateLineOf(appended))
    }

    @Test(timeout = 30_000)
    fun `switch on, a draft is dated in UTC`() {
        switch.value = true
        assertEquals(UTC_DATE, dateLineOf(appendDraft(service())))
    }

    @Test(timeout = 30_000)
    fun `switch off, a draft is dated in the device zone`() {
        switch.value = false
        assertEquals(DEVICE_DATE, dateLineOf(appendDraft(service())))
    }

    /**
     * The switch as it stands when the bytes are built. One service, turned off after it was made
     * with the switch on and between two sends: a zone taken at construction, or kept from the
     * first send, dates the second one in UTC.
     */
    @Test(timeout = 30_000)
    fun `the switch is read when the message goes out, not before`() {
        switch.value = true
        val service = service()
        switch.value = false
        assertEquals(DEVICE_DATE, dateLineOf(send(service).first))
        switch.value = true
        assertEquals(UTC_DATE, dateLineOf(send(service).first))
        switch.value = false
        assertEquals(DEVICE_DATE, dateLineOf(appendDraft(service)))
    }

    // ---- the harness --------------------------------------------------------------------------------

    /** The bytes the SMTP server accepted, and those appended into Sent. */
    private fun send(service: ImapMailService): Pair<String, String> =
        TinySmtpServer().use { smtp ->
            imapServer().use { imap ->
                runBlocking { service.send(credentials(imap, smtp), message(), SENT) }
                smtp.delivered.single() to imap.literals.single()
            }
        }

    private fun appendDraft(service: ImapMailService): String =
        imapServer().use { imap ->
            runBlocking { service.appendDraft(credentials(imap, smtp = null), DRAFTS, message()) }
            imap.literals.single()
        }

    private fun imapServer() = ScriptedImapServer { tag, line ->
        if (line.startsWith("LOGIN")) "$tag OK logged in\r\n" else "$tag OK done\r\n"
    }

    private fun dateLineOf(mime: String): String = mime.lines().single { it.startsWith("Date:") }

    private fun credentials(imap: ScriptedImapServer, smtp: TinySmtpServer?) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(imap.host, imap.port, ConnectionSecurity.NONE),
        smtp = smtp?.let { MailEndpoint(it.host, it.port, ConnectionSecurity.NONE) },
    )

    private fun message() = OutgoingMessage(
        from = "alex@example.org",
        to = listOf("bob@example.org"),
        subject = "Six o'clock",
        body = "I will be there",
        messageId = "date-zone@example.org",
        dateMillis = 1_760_000_000_000L,
    )

    /** One submission on loopback: advertises AUTH PLAIN, accepts everything, keeps the DATA. */
    private class TinySmtpServer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val host: String = server.inetAddress.hostAddress ?: "127.0.0.1"
        val port: Int = server.localPort
        val delivered: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())

        private val thread = Thread(::serve, "tiny-smtp").apply { isDaemon = true; start() }

        private fun serve() {
            runCatching {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                    val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
                    fun say(line: String) { writer.write(line + "\r\n"); writer.flush() }
                    say("220 tiny ready")
                    while (true) {
                        val line = reader.readLine() ?: return
                        when {
                            line.startsWith("EHLO") -> { say("250-tiny"); say("250 AUTH PLAIN") }
                            line.startsWith("AUTH") -> say("235 ok")
                            line.startsWith("DATA") -> {
                                say("354 go ahead")
                                val body = StringBuilder()
                                while (true) {
                                    val data = reader.readLine() ?: return
                                    if (data == ".") break
                                    body.append(data).append("\r\n")
                                }
                                delivered += body.toString()
                                say("250 queued")
                            }
                            line.startsWith("QUIT") -> { say("221 bye"); return }
                            else -> say("250 ok")
                        }
                    }
                }
            }
        }

        override fun close() {
            runCatching { server.close() }
            thread.interrupt()
        }
    }

    private companion object {
        const val SENT = "Sent"
        const val DRAFTS = "Drafts"
        const val UTC_DATE = "Date: Thu, 9 Oct 2025 08:53:20 GMT"
        const val DEVICE_DATE = "Date: Thu, 9 Oct 2025 14:23:20 +0530"
    }
}

package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ SOURCE LINT on the ONE call of `SmtpClient.send` in the repo.
 *
 * `send()` bounds its connect and its command reads with two parameters that DEFAULT to the shipped
 * values, and `core:imap` pins those defaults on the declaration. That pins nothing about what the
 * app passes: `send(config, message, commandTimeoutMs = 0)` here restores the original defect whole
 * — a submission that hangs for ever on a server gone quiet, an outbox row frozen on "sending" —
 * and every test in the four modules stays green, because nothing exercises `ImapMailService.send`
 * (it needs a real account, a real socket and a token refresher).
 *
 * So the call site is pinned, whole line at a time. `contains` on a fragment would be blind to the
 * one mutation that matters here, which LENGTHENS the line by adding an argument.
 */
class SmtpSubmissionCallSiteTest {

    private val body = DaoQuerySource.mailFunctionBody("ImapMailService", "send")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test
    fun `the submission passes no timeout, so it gets the shipped bounds`() {
        val at = body.indexOfFirst { it.startsWith("smtpClient.send(") }
        assertTrue(
            "ImapMailService.send no longer calls smtpClient.send( — if the call moved, this lint " +
                "moved with it or the app has no bounded submission left:\n" + body.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "the submission call site changed. Any argument added here overrides a bound the whole " +
                "of fix/smtp-coupure-et-attente exists to set; `commandTimeoutMs = 0` puts the " +
                "frozen outbox row back, silently:\n" + body.joinToString("\n"),
            listOf(
                "smtpClient.send(",
                "MailServerConfig(smtp.host, smtp.port, smtp.security.toMailSecurity(), " +
                    "credentials.username, credentials.password, token),",
                "message,",
                ")",
            ),
            body.drop(at).take(4),
        )
    }
}

package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Which login the SMTP `AUTH` carries (#240), executed on [resolveSmtpLogin] and [smtpSecretEdit]. */
class SmtpLoginTest {

    private fun resolve(
        smtpUsername: String,
        smtpPassword: String = "",
        authType: AuthType = AuthType.BASIC,
    ) = resolveSmtpLogin(
        authType = authType,
        imapUsername = "reader@migadu.example",
        imapPassword = "imap-secret",
        smtpUsername = smtpUsername,
        smtpPassword = smtpPassword,
    )

    @Test fun `a blank SMTP username sends with the IMAP login, as before`() {
        assertEquals(SmtpLogin("reader@migadu.example", "imap-secret"), resolve(""))
        assertEquals(SmtpLogin("reader@migadu.example", "imap-secret"), resolve("   ", "stale-smtp-secret"))
    }

    @Test fun `a SMTP username with its own secret sends with both`() {
        assertEquals(
            "the reporter's case: another host's identifier and password, and the IMAP ones went out",
            SmtpLogin("sender@origin.example", "smtp-secret"),
            resolve("sender@origin.example", "smtp-secret"),
        )
    }

    @Test fun `a SMTP username without a secret sends the IMAP password`() {
        assertEquals(
            "same server, other identifier: the password typed once for the account must serve, " +
                "a blank one is refused at AUTH",
            SmtpLogin("sender@origin.example", "imap-secret"),
            resolve("sender@origin.example"),
        )
    }

    @Test fun `the SMTP username is trimmed`() {
        assertEquals("sender@origin.example", resolve("  sender@origin.example ", "s").username)
    }

    @Test fun `an OAuth account ignores a leftover SMTP username`() {
        assertEquals(
            "XOAUTH2 names the mailbox the token was granted for: another user= is refused",
            SmtpLogin("reader@migadu.example", "imap-secret"),
            resolve("sender@origin.example", "smtp-secret", AuthType.OAUTH),
        )
    }

    @Test fun `credentials carrying OAuth tokens resolve as OAuth even if their auth type says BASIC`() {
        val oauth = OAuthCredentials("access", "refresh", 0, "https://t.example", "client")
        val credentials = AccountCredentials(
            server = "",
            username = "reader@migadu.example",
            password = "",
            oauth = oauth,
            smtpUsername = "sender@origin.example",
            smtpPassword = "smtp-secret",
        )
        assertEquals(SmtpLogin("reader@migadu.example", ""), credentials.smtpLogin())
    }

    @Test fun `credentials resolve through the same rule`() {
        val credentials = AccountCredentials(
            server = "",
            username = "reader@migadu.example",
            password = "imap-secret",
            protocol = MailProtocol.IMAP,
            smtpUsername = "sender@origin.example",
            smtpPassword = "smtp-secret",
        )
        assertEquals(SmtpLogin("sender@origin.example", "smtp-secret"), credentials.smtpLogin())
    }

    @Test fun `neither the credentials nor the login print a SMTP secret`() {
        val credentials = AccountCredentials(
            server = "",
            username = "reader@migadu.example",
            password = "imap-secret",
            smtpUsername = "sender@origin.example",
            smtpPassword = "smtp-secret",
        )
        assertFalse(credentials.toString().contains("smtp-secret"))
        assertFalse(SmtpLogin("sender@origin.example", "smtp-secret").toString().contains("smtp-secret"))
        assertFalse(SmtpSecretEdit.Write("smtp-secret").toString().contains("smtp-secret"))
    }

    @Test fun `clearing the SMTP username clears its secret, wherever it is edited`() {
        assertEquals(SmtpSecretEdit.Clear, smtpSecretEdit("", "typed", blankKeeps = true))
        assertEquals(SmtpSecretEdit.Clear, smtpSecretEdit(" ", "", blankKeeps = true))
        assertEquals(SmtpSecretEdit.Clear, smtpSecretEdit("", "typed", blankKeeps = false))
    }

    @Test fun `a typed SMTP password is written`() {
        assertEquals(SmtpSecretEdit.Write("typed"), smtpSecretEdit("sender@origin.example", "typed", blankKeeps = true))
        assertEquals(SmtpSecretEdit.Write("typed"), smtpSecretEdit("sender@origin.example", "typed", blankKeeps = false))
    }

    @Test fun `a blank SMTP password keeps the secret in the editor, and clears it at an add`() {
        assertEquals(
            "the editor's blank means unchanged, like the IMAP password beside it",
            SmtpSecretEdit.Keep,
            smtpSecretEdit("sender@origin.example", "", blankKeeps = true),
        )
        assertEquals(
            "an add states the whole login: a secret left from an earlier add would override the " +
                "IMAP password this add asked for",
            SmtpSecretEdit.Clear,
            smtpSecretEdit("sender@origin.example", "", blankKeeps = false),
        )
    }
}

package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint on where the SMTP login of #240 is typed, same instrument and disclaimer as
 * [IdentityCreationWiringTest]: it reads text, proves nothing about what is drawn. The rule the
 * fields feed is executed in `core:data` (`SmtpLoginTest`).
 *
 * What it does not see: a second call of `connectImap` added elsewhere, and whether the section
 * is visible on screen, only that it sits inside the guard.
 */
class SmtpLoginWiringTest {

    @Test fun `the account editor draws the SMTP login under the OAuth guard, after the SMTP block`() {
        val lines = codeLines(SETTINGS_SCREEN)
        val warning = lines.indexOf("PlaintextSecurityWarning(smtpSecurity)")
        assertEquals(
            "the SMTP login fields no longer open with the OAuth guard right after the SMTP block. " +
                "Shown on an OAuth account, a Save writes a login the XOAUTH2 token does not " +
                "name, or a secret nobody reads.",
            "if (account.authType != AuthType.OAUTH) {",
            lines.getOrNull(warning + 1),
        )
        val username = lines.indexOf("label = stringResource(R.string.settings_smtp_username_label),")
        val password = lines.indexOf("label = stringResource(R.string.settings_smtp_password_label),")
        val uploadSent = lines.indexOf("title = stringResource(R.string.settings_upload_sent_title),")
        assertTrue("SMTP username at $username, password at $password, guard at ${warning + 1}", warning + 1 < username && username < password && password < uploadSent)
    }

    @Test fun `the editor saves the SMTP login only for a password account`() {
        val lines = codeLines(SETTINGS_SCREEN)
        assertEquals(
            1,
            lines.count { it == "smtpUsername = if (account.authType == AuthType.OAUTH) null else smtpUsername," },
        )
        assertEquals(1, lines.count { it == "smtpPassword = smtpPassword," })
    }

    @Test fun `the manual add passes the SMTP login, the discovered handover passes none`() {
        val lines = codeLines(CONNECT_SCREEN)
        val manual = lines.indexOf("preset.smtpHost, preset.smtpPort.toInt(), preset.smtpSecurity,")
        assertEquals(
            "the manual IMAP route no longer hands its SMTP login to connectImap: the fields are " +
                "typed and the account is added with the IMAP login",
            "smtpUsername = sentSmtpUsername, smtpPassword = sentSmtpPassword,",
            lines.getOrNull(manual + 1),
        )
        assertEquals(
            "a folded SMTP section must send no SMTP login: values typed, then hidden, would " +
                "otherwise add the account with a login its owner no longer sees",
            1,
            lines.count { it == "val (sentSmtpUsername, sentSmtpPassword) = if (showSmtpLogin) smtpUsername to smtpPassword else \"\" to \"\"" },
        )
        val handover = lines.indexOf("endpoints.outgoing.host, endpoints.outgoing.port, endpoints.outgoing.security,")
        assertEquals(
            "the call after autoconfig shows no SMTP section: it must not pick up values typed " +
                "for another address in the manual form",
            ")",
            lines.getOrNull(handover + 1),
        )
    }

    @Test fun `the reconnection's first password field moves on to the SMTP one when it is shown`() {
        val lines = codeLines(CONNECT_SCREEN)
        assertEquals(
            "with the SMTP password field shown, OK on the first field must move to it, not submit " +
                "with the SMTP password still blank",
            1,
            lines.count { it == "imeAction = if (asksSmtpPassword) ImeAction.Next else ImeAction.Done," },
        )
    }

    @Test fun `the SMTP section keeps its own saveable state, outside the preset form`() {
        val lines = codeLines(CONNECT_SCREEN)
        for (line in listOf(
            "var showSmtpLogin by rememberSaveable { mutableStateOf(false) }",
            "var smtpUsername by rememberSaveable { mutableStateOf(\"\") }",
            "var smtpPassword by rememberSaveable { mutableStateOf(\"\") }",
        )) {
            assertEquals("$line must appear once in $CONNECT_SCREEN", 1, lines.count { it == line })
        }
    }

    private fun codeLines(relative: String): List<String> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, relative).isFile }
            ?: error("cannot locate $relative from ${File("").absolutePath}")
        return File(root, relative).readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
    }

    private companion object {
        const val SETTINGS_SCREEN = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"
        const val CONNECT_SCREEN = "app/src/main/kotlin/app/sterna/ui/connect/ConnectScreen.kt"
    }
}

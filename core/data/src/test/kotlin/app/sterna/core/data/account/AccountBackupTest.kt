package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which accounts a settings backup carries, executed on [backupSnapshotOf]. The Backup & restore
 * screen, `FEATURES.md` and `PRIVACY.md` name the two kinds left out; if the filter moves, those
 * three texts are wrong and this test is where it shows.
 */
class AccountBackupTest {

    @Test fun `only password accounts standing on their own login go in the backup`() {
        val password = StoredAccount(id = "basic", server = "imap.example.test", username = "a@example.test")
        val microsoft = password.copy(id = "oauth", username = "b@example.test", authType = AuthType.OAUTH)
        val token = password.copy(id = "token", username = "d@example.test", authType = AuthType.API_TOKEN)
        val shared = password.copy(id = "linked", username = "c@example.test", loginId = "basic")

        assertEquals(
            "the backup must hold the password account alone: an account that signs in with " +
                "Microsoft or with an API token, or a shared account riding on another's login, " +
                "got in, and the Backup & restore screen says they are not in the file.",
            listOf("basic"),
            backupSnapshotOf(listOf(password, microsoft, token, shared)).map { it.id },
        )
    }

    @Test fun `the SMTP username survives a backup, and no SMTP password is in the file`() {
        val account = StoredAccount(
            id = "basic",
            server = "",
            username = "reader@migadu.example",
            protocol = MailProtocol.IMAP,
            imapHost = "imap.migadu.example",
            smtpHost = "smtp.origin.example",
            smtpUsername = "sender@origin.example",
        )
        val text = app.sterna.core.data.settings.SettingsBackupCodec.encode(
            app.sterna.core.data.settings.SettingsBackup(accounts = backupSnapshotOf(listOf(account))),
        )
        val back = app.sterna.core.data.settings.SettingsBackupCodec.decode(text)?.accounts.orEmpty()
        assertEquals(
            "a restored account must still say which login it sends with, or its reconnection " +
                "never asks for the SMTP password",
            listOf("sender@origin.example"),
            back.map { importedCopy(it, "new-id").smtpUsername },
        )
        val keys = Regex("\"([A-Za-z_]+)\"\\s*:").findAll(text).map { it.groupValues[1].lowercase() }.toSet()
        assertEquals(
            "a key naming a password or a secret reached the backup file: PRIVACY.md says it holds none",
            emptySet<String>(),
            keys.filter { "password" in it || "secret" in it || "smtppw" in it }.toSet(),
        )
    }

    private val withAlias = StoredAccount(
        id = "basic",
        server = "imap.example.test",
        username = "a@example.test",
        smimeCertAlias = "keychain-alias-of-this-phone",
    )

    @Test fun `the S-MIME certificate chosen on this phone does not leave in a backup`() {
        assertEquals(
            "the alias names a key in this phone's keystore; on another it names nothing, or someone else's",
            listOf(""),
            backupSnapshotOf(listOf(withAlias)).map { it.smimeCertAlias },
        )
        val text = app.sterna.core.data.settings.SettingsBackupCodec.encode(
            app.sterna.core.data.settings.SettingsBackup(accounts = backupSnapshotOf(listOf(withAlias))),
        )
        assertEquals("the backup file names no alias", false, "keychain-alias-of-this-phone" in text)
    }

    @Test fun `an S-MIME certificate alias in a backup file does not come back from it`() {
        assertEquals(
            "a file written with the alias in it must not bring it back",
            "",
            importedCopy(withAlias, "new-id").smimeCertAlias,
        )
    }
}

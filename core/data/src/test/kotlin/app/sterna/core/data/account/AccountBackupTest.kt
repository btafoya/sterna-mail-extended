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
}

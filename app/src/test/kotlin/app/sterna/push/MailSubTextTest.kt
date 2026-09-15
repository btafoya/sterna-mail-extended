package app.sterna.push

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codeberg #193: with two accounts or more, a per-message notification says which one it came from.
 */
class MailSubTextTest {

    @Test fun `one account, inbox, names nothing`() {
        assertEquals(MailSubText.None, Notifications.subTextFor(1, PERSO, folderName = null))
    }

    @Test fun `one account, another folder, names the folder alone`() {
        assertEquals(MailSubText.Folder(folder = ARCHIVE), Notifications.subTextFor(1, PERSO, ARCHIVE))
    }

    @Test fun `two accounts, inbox, names the account alone`() {
        assertEquals(MailSubText.Account(account = PERSO), Notifications.subTextFor(2, PERSO, folderName = null))
    }

    @Test fun `two accounts, another folder, names the account then the folder`() {
        assertEquals(MailSubText.AccountAndFolder(account = PERSO, folder = ARCHIVE), Notifications.subTextFor(2, PERSO, ARCHIVE))
        assertEquals(MailSubText.AccountAndFolder(account = PERSO, folder = ARCHIVE), Notifications.subTextFor(5, PERSO, ARCHIVE))
    }

    @Test fun `no account counted reads as one`() {
        assertEquals(MailSubText.None, Notifications.subTextFor(0, PERSO, folderName = null))
        assertEquals(MailSubText.Folder(folder = ARCHIVE), Notifications.subTextFor(0, PERSO, ARCHIVE))
    }

    @Test fun `an account signed out mid-pass has no name to show`() {
        assertEquals(MailSubText.None, Notifications.subTextFor(2, accountLabel = null, folderName = null))
        assertEquals(MailSubText.Folder(folder = ARCHIVE), Notifications.subTextFor(2, accountLabel = null, ARCHIVE))
    }

    @Test fun `the account name is the drawer's, falling back to the address when left blank`() {
        val named = StoredAccount(id = "a", server = "s", username = "me@example.test", accountName = PERSO)
        val unnamed = StoredAccount(id = "b", server = "s", username = "me@example.test", accountName = " ")
        assertEquals(PERSO, named.label())
        assertEquals("me@example.test", unnamed.label())
    }

    private companion object {
        const val PERSO = "Perso"
        const val ARCHIVE = "Archive"
    }
}

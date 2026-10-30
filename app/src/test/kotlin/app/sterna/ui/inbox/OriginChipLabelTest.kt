package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The origin chip of a list row (#243): [originChipLabel] composes it, [rowArrival] finds its address. */
class OriginChipLabelTest {

    @Test fun `a folder of one account names the address alone, and nothing without one`() {
        assertEquals(PRO, originChipLabel(accountLabel = null, folderLabel = null, arrival = PRO, banner = null))
        assertNull(originChipLabel(accountLabel = null, folderLabel = null, arrival = null, banner = null))
    }

    @Test fun `where rows name their account, the address takes the account's place`() {
        assertEquals(PRO, originChipLabel(accountLabel = "Perso", folderLabel = null, arrival = PRO, banner = null))
        assertEquals("Perso", originChipLabel(accountLabel = "Perso", folderLabel = null, arrival = null, banner = null))
    }

    @Test fun `in the unread view of one account the folder keeps its place, before the address`() {
        assertEquals("Boîte de réception · $PRO", originChipLabel(accountLabel = null, folderLabel = "Boîte de réception", arrival = PRO, banner = null))
        assertEquals("Boîte de réception", originChipLabel(accountLabel = null, folderLabel = "Boîte de réception", arrival = null, banner = null))
    }

    @Test fun `the account still wins over the folder, with an address or without`() {
        assertEquals(PRO, originChipLabel(accountLabel = "Perso", folderLabel = "Archive", arrival = PRO, banner = null))
        assertEquals("Perso", originChipLabel(accountLabel = "Perso", folderLabel = "Archive", arrival = null, banner = null))
    }

    @Test fun `an arrival the top bar already writes counts as none`() {
        assertNull(originChipLabel(accountLabel = null, folderLabel = null, arrival = PRO, banner = PRO))
        assertEquals("Boîte de réception", originChipLabel(accountLabel = null, folderLabel = "Boîte de réception", arrival = PRO, banner = PRO))
        assertEquals("Perso", originChipLabel(accountLabel = "Perso", folderLabel = null, arrival = PRO, banner = PRO))
    }

    @Test fun `the top bar's address is matched trimmed and whatever its case`() {
        assertNull(originChipLabel(accountLabel = null, folderLabel = null, arrival = " Pro@Exemple.ORG", banner = "pro@exemple.org "))
        assertNull(originChipLabel(accountLabel = null, folderLabel = null, arrival = PRO, banner = "  PRO@EXEMPLE.ORG"))
    }

    @Test fun `an alias other than the top bar's address still shows`() {
        assertEquals(PRO, originChipLabel(accountLabel = null, folderLabel = null, arrival = PRO, banner = "moi@exemple.org"))
        assertEquals("Archive · $PRO", originChipLabel(accountLabel = null, folderLabel = "Archive", arrival = PRO, banner = "moi@exemple.org"))
    }

    private fun bar(
        accountName: String = PRO,
        unified: Boolean = false,
        unreadAllAccounts: Boolean = false,
        searching: Boolean = false,
    ) = MailUi(
        accountName = accountName,
        mailboxName = "m",
        unreadCount = 0,
        selectedMailboxId = "i",
        unified = unified,
        unreadAllAccounts = unreadAllAccounts,
        searching = searching,
        mailboxes = emptyList(),
        refreshing = false,
        error = null,
    )

    @Test fun `the top bar of one account writes its name`() {
        assertEquals(PRO, bannerAddress(bar()))
        assertEquals(" Pro@Exemple.org ", bannerAddress(bar(accountName = " Pro@Exemple.org ")))
    }

    @Test fun `no address is written in the unified inbox, the every-account unread view, search, or without a name`() {
        assertNull(bannerAddress(bar(unified = true)))
        assertNull(bannerAddress(bar(unreadAllAccounts = true)))
        assertNull(bannerAddress(bar(searching = true)))
        assertNull(bannerAddress(bar(accountName = "")))
        assertNull(bannerAddress(bar(accountName = "   ")))
    }

    private fun identity(address: String) = StoredIdentity(id = address, name = "", email = address)

    private fun account(id: String, login: String, vararg addresses: String) =
        StoredAccount(id = id, server = "https://ex.test", username = login, identities = addresses.map(::identity))

    private fun mail(account: String, to: String) = Email(
        id = "m",
        accountId = account,
        from = listOf(EmailAddress(email = "tiers@ailleurs.test")),
        to = listOf(EmailAddress(email = to)),
    )

    private val accounts = listOf(
        account("a", "login-a@ex.test", "moi@ex.test", PRO),
        account("b", "seule@autre.test", "seule@autre.test"),
        account("c", "login-c@ex.test", "moi@c.test"),
    )

    @Test fun `the row is judged on its own account's identities`() {
        assertEquals(PRO, rowArrival(mail("a", PRO), accounts, showRecipients = false))
        assertNull(rowArrival(mail("b", PRO), accounts, showRecipients = false))
        assertNull(rowArrival(mail("inconnu", PRO), accounts, showRecipients = false))
    }

    @Test fun `a single-address account names nothing, its login counted for nothing`() {
        assertNull(rowArrival(mail("b", "seule@autre.test"), accounts, showRecipients = false))
        assertNull(rowArrival(mail("c", "moi@c.test"), accounts, showRecipients = false))
        assertNull(rowArrival(mail("c", "login-c@ex.test"), accounts, showRecipients = false))
    }

    @Test fun `a row shown by its recipients is handed over as such`() {
        assertNull(rowArrival(mail("a", PRO), accounts, showRecipients = true))
    }

    @Test fun `a linked account with no identities of its own does not borrow its login's`() {
        val login = account("login", "login@ex.test", "moi@ex.test", PRO)
        val shared = StoredAccount(id = "partagee", server = "https://ex.test", username = "login@ex.test", loginId = "login", accountName = "Équipe")
        val both = listOf(login, shared)
        assertNull(rowArrival(mail("partagee", PRO), both, showRecipients = false))
        assertEquals(PRO, rowArrival(mail("login", PRO), both, showRecipients = false))
    }

    @Test fun `outside search the row's own showRecipients decides, whoever wrote the message`() {
        assertFalse(chipOwnMessage(fromSearch = false, selfAuthored = true, showRecipients = false))
        assertFalse(chipOwnMessage(fromSearch = false, selfAuthored = false, showRecipients = false))
        assertTrue(chipOwnMessage(fromSearch = false, selfAuthored = true, showRecipients = true))
        assertTrue(chipOwnMessage(fromSearch = false, selfAuthored = false, showRecipients = true))
    }

    @Test fun `in search, where the folder is not trusted, a message of one's own has no arrival`() {
        assertTrue(chipOwnMessage(fromSearch = true, selfAuthored = true, showRecipients = false))
        assertTrue(chipOwnMessage(fromSearch = true, selfAuthored = true, showRecipients = true))
    }

    @Test fun `in search a message from someone else names its arrival`() {
        assertFalse(chipOwnMessage(fromSearch = true, selfAuthored = false, showRecipients = false))
    }

    private companion object {
        const val PRO = "pro@exemple.org"
    }
}

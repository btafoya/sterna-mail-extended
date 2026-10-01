package app.sterna.push

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.settings.NotificationContent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codeberg #193: with two accounts or more, a per-message notification says which one it came from.
 * And the setting that may take that away again (#84): "No sender or subject" promises just that
 * new mail arrived, and an account label with a folder name beside it is neither just that nor
 * nothing: it says who writes and about what.
 */
class MailSubTextTest {

    /** The three settings that reveal something, so each case below is stated for all of them. */
    private val telling = listOf(
        NotificationContent.BODY_PREVIEW,
        NotificationContent.SENDER_AND_SUBJECT,
        NotificationContent.SENDER_ONLY,
    )

    private fun eachTelling(
        expected: MailSubText,
        accountCount: Int,
        label: String?,
        folder: String?,
        address: String? = null,
    ) = telling.forEach { content ->
        assertEquals("$content", expected, MailNotificationText.subTextFor(content, accountCount, label, address, folder))
    }

    @Test fun `one account, inbox, names nothing`() {
        eachTelling(MailSubText.None, 1, PERSO, folder = null)
    }

    @Test fun `one account, another folder, names the folder alone`() {
        eachTelling(MailSubText.Folder(folder = ARCHIVE), 1, PERSO, ARCHIVE)
    }

    @Test fun `two accounts, inbox, names the account alone`() {
        eachTelling(MailSubText.Account(account = PERSO), 2, PERSO, folder = null)
    }

    @Test fun `two accounts, another folder, names the account then the folder`() {
        eachTelling(MailSubText.AccountAndFolder(account = PERSO, folder = ARCHIVE), 2, PERSO, ARCHIVE)
        eachTelling(MailSubText.AccountAndFolder(account = PERSO, folder = ARCHIVE), 5, PERSO, ARCHIVE)
    }

    @Test fun `no account counted reads as one`() {
        eachTelling(MailSubText.None, 0, PERSO, folder = null)
        eachTelling(MailSubText.Folder(folder = ARCHIVE), 0, PERSO, ARCHIVE)
    }

    @Test fun `an account signed out mid-pass has no name to show`() {
        eachTelling(MailSubText.None, 2, label = null, folder = null)
        eachTelling(MailSubText.Folder(folder = ARCHIVE), 2, label = null, ARCHIVE)
    }

    // -- the setting that names nothing ----------------------------------------------------------

    @Test fun `no sender or subject names neither the account nor the folder`() {
        assertEquals(
            MailSubText.None,
            MailNotificationText.subTextFor(NotificationContent.NONE, 2, PERSO, null, ARCHIVE),
        )
    }

    @Test fun `no sender or subject names nothing whatever the count and the folder`() {
        val cases = listOf(
            Triple(2, PERSO, ARCHIVE),
            Triple(2, PERSO, null),
            Triple(1, PERSO, ARCHIVE),
            Triple(1, PERSO, null),
            Triple(5, PERSO, ARCHIVE),
            Triple(2, null, ARCHIVE),
        )
        cases.forEach { (count, label, folder) ->
            assertEquals(
                "count=$count label=$label folder=$folder",
                MailSubText.None,
                MailNotificationText.subTextFor(NotificationContent.NONE, count, label, null, folder),
            )
        }
    }

    /** The header line, position by position, for the one arrangement where every setting has
     *  something to hide: two accounts and a folder that is not the inbox. */
    @Test fun `what the header line may name, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to MailSubText.AccountAndFolder(PERSO, ARCHIVE),
            NotificationContent.SENDER_AND_SUBJECT to MailSubText.AccountAndFolder(PERSO, ARCHIVE),
            NotificationContent.SENDER_ONLY to MailSubText.AccountAndFolder(PERSO, ARCHIVE),
            NotificationContent.NONE to MailSubText.None,
        )
        assertEquals(
            "a position was added to the setting without deciding what the header line may name there",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(
            expected,
            NotificationContent.entries.associateWith {
                MailNotificationText.subTextFor(
                    it,
                    accountCount = 2,
                    accountLabel = PERSO,
                    arrivalAddress = null,
                    folderName = ARCHIVE,
                )
            },
        )
    }

    // -- the address the message came in on (#243) ------------------------------------------------

    @Test fun `a known address, one account, inbox, names the address`() {
        eachTelling(MailSubText.Account(account = PRO), 1, PERSO, folder = null, address = PRO)
    }

    @Test fun `a known address, one account, another folder, names the address then the folder`() {
        eachTelling(MailSubText.AccountAndFolder(account = PRO, folder = ARCHIVE), 1, PERSO, ARCHIVE, address = PRO)
    }

    @Test fun `a known address, two accounts, inbox, names the address and not the account`() {
        eachTelling(MailSubText.Account(account = PRO), 2, PERSO, folder = null, address = PRO)
        eachTelling(MailSubText.Account(account = PRO), 5, PERSO, folder = null, address = PRO)
    }

    @Test fun `a known address, two accounts, another folder, names the address then the folder`() {
        eachTelling(MailSubText.AccountAndFolder(account = PRO, folder = ARCHIVE), 2, PERSO, ARCHIVE, address = PRO)
    }

    @Test fun `a known address with no label left still names the address`() {
        eachTelling(MailSubText.Account(account = PRO), 2, label = null, folder = null, address = PRO)
    }

    @Test fun `no sender or subject names no address either`() {
        listOf(1, 2, 5).forEach { count ->
            listOf(ARCHIVE, null).forEach { folder ->
                assertEquals(
                    "count=$count folder=$folder",
                    MailSubText.None,
                    MailNotificationText.subTextFor(NotificationContent.NONE, count, PERSO, PRO, folder),
                )
            }
        }
    }

    @Test fun `what the header line may name with a known address, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to MailSubText.AccountAndFolder(PRO, ARCHIVE),
            NotificationContent.SENDER_AND_SUBJECT to MailSubText.AccountAndFolder(PRO, ARCHIVE),
            NotificationContent.SENDER_ONLY to MailSubText.AccountAndFolder(PRO, ARCHIVE),
            NotificationContent.NONE to MailSubText.None,
        )
        assertEquals(NotificationContent.entries.toSet(), expected.keys)
        assertEquals(
            expected,
            NotificationContent.entries.associateWith {
                MailNotificationText.subTextFor(
                    it,
                    accountCount = 1,
                    accountLabel = PERSO,
                    arrivalAddress = PRO,
                    folderName = ARCHIVE,
                )
            },
        )
    }

    // -- the group summary's headline ------------------------------------------------------------

    @Test fun `the summary names the account wherever the setting reveals anything`() {
        telling.forEach { content ->
            assertEquals("$content", PERSO, MailNotificationText.summaryAccountFor(content, PERSO))
        }
    }

    @Test fun `the summary names no account under no sender or subject`() {
        assertEquals(
            "the summary is the last place the label survives once its children say nothing",
            null,
            MailNotificationText.summaryAccountFor(NotificationContent.NONE, PERSO),
        )
    }

    @Test fun `what the summary headline may name, position by position`() {
        val expected = mapOf(
            NotificationContent.BODY_PREVIEW to PERSO,
            NotificationContent.SENDER_AND_SUBJECT to PERSO,
            NotificationContent.SENDER_ONLY to PERSO,
            NotificationContent.NONE to null,
        )
        assertEquals(
            "a position was added to the setting without deciding what the summary may name there",
            NotificationContent.entries.toSet(),
            expected.keys,
        )
        assertEquals(
            expected,
            NotificationContent.entries.associateWith { MailNotificationText.summaryAccountFor(it, PERSO) },
        )
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
        const val PRO = "pro@exemple.org"
    }
}

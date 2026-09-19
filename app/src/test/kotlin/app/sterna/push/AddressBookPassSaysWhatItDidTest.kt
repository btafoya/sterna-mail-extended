package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.ServerContactsSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lines the background pass logs for each account's address book. Every way out that leaves an
 * account without a copy names itself and the account, so a logcat with none of them means the step
 * never ran.
 */
class AddressBookPassSaysWhatItDidTest {

    @Test fun `a linked sub-account says it was left out on purpose`() {
        assertEquals(
            "Address book not read for account acc-2: a linked sub-account is never given its login's book",
            serverContactsLine("acc-2", ServerContactsSync.Linked),
        )
    }

    @Test fun `a session without a contacts account says which half is missing`() {
        assertEquals(
            "Address book not read for account acc-1: the session does not list urn:ietf:params:jmap:contacts",
            serverContactsLine("acc-1", ServerContactsSync.NoAddressBook(capabilityListed = false)),
        )
        assertEquals(
            "Address book not read for account acc-1: the session lists urn:ietf:params:jmap:contacts " +
                "but names no account for it",
            serverContactsLine("acc-1", ServerContactsSync.NoAddressBook(capabilityListed = true)),
        )
    }

    @Test fun `a read says how many cards it brought and whether the state was kept`() {
        assertEquals(
            "Address book of account acc-1: whole book read, 4 cards, 0 addresses written, state kept",
            serverContactsLine(
                "acc-1",
                ServerContactsSync.Refreshed(whole = true, cards = 4, destroyed = 0, addresses = 0, stateKept = true),
            ),
        )
        assertEquals(
            "Address book of account acc-1: whole book read, 0 cards, 0 addresses written, no state kept, " +
                "the next pass rereads it",
            serverContactsLine(
                "acc-1",
                ServerContactsSync.Refreshed(whole = true, cards = 0, destroyed = 0, addresses = 0, stateKept = false),
            ),
        )
        assertEquals(
            "Address book of account acc-1: 2 cards changed, 1 removed, 3 addresses written, state kept",
            serverContactsLine(
                "acc-1",
                ServerContactsSync.Refreshed(whole = false, cards = 2, destroyed = 1, addresses = 3, stateKept = true),
            ),
        )
    }

    @Test fun `an IMAP account, which has no server book, logs nothing`() {
        assertNull(serverContactsLine("acc-1", ServerContactsSync.Imap))
    }

    @Test fun `an account whose credentials do not read is named, IMAP excepted, and only then is there a line`() {
        fun stored(id: String, protocol: MailProtocol = MailProtocol.JMAP) =
            StoredAccount(id = id, server = "unused", username = "u", protocol = protocol)
        assertEquals(
            "Address book not read for account(s) acc-2, acc-3: their credentials could not be read",
            addressBookLeftOutLine(
                stored = listOf(stored("acc-1"), stored("acc-2"), stored("acc-3"), stored("imap-1", MailProtocol.IMAP)),
                readable = listOf("acc-1"),
            ),
        )
        assertNull(addressBookLeftOutLine(stored = listOf(stored("acc-1")), readable = listOf("acc-1")))
        assertNull(
            "an IMAP account has no server book: its unreadable credentials are not this step's to report",
            addressBookLeftOutLine(stored = listOf(stored("imap-1", MailProtocol.IMAP)), readable = emptyList()),
        )
    }
}

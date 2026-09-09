package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The eleven optional arguments of [AccountStorage] moved off [AccountStore] when the interface
 * was extracted, because Kotlin refuses to let an override restate a default. Moving one by hand
 * is how a port silently changes a stored account: a wrong `imapPort` here reaches a real server.
 *
 * Every value below is asserted as the implementation received it, from a call that omits it.
 */
class AccountStorageDefaultsTest {

    private val storage = RecordingAccountStorage()

    @Test
    fun addingAnOAuthAccountDefaultsToJmapOverTheStandardImapAndSmtpPorts() {
        val store: AccountStorage = storage
        store.addOAuth(
            server = "https://mail.example.test",
            username = "alex",
            accountName = "Alex",
            accessToken = "access",
            refreshToken = "refresh",
            accessExpiresAtMillis = 0L,
            tokenEndpoint = "https://token.example.test",
            clientId = "client",
        )

        val received = storage.args("addOAuth")
        assertEquals(MailProtocol.JMAP, received["protocol"])
        assertEquals("", received["imapHost"])
        assertEquals(993, received["imapPort"])
        assertEquals(ConnectionSecurity.TLS, received["imapSecurity"])
        assertEquals("", received["smtpHost"])
        assertEquals(587, received["smtpPort"])
        assertEquals(ConnectionSecurity.STARTTLS, received["smtpSecurity"])
        assertEquals("", received["documentHost"])
    }

    /** Both folder re-keyings are called without a delimiter on the IMAP rename path. */
    @Test
    fun aFolderRekeyDefaultsToTheSlashDelimiter() {
        val store: AccountStorage = storage
        store.replaceCollapsedFolder("account", "Old", "New")
        store.replaceWatchedFolder("account", "Old", "New")

        assertEquals("/", storage.args("replaceCollapsedFolder")["delimiter"])
        assertEquals("/", storage.args("replaceWatchedFolder")["delimiter"])
    }

    /** Granting OAuth to an imported account leaves its username alone unless one is named. */
    @Test
    fun attachingOAuthWithoutAUsernameCorrectsNothing() {
        val store: AccountStorage = storage
        store.attachOAuth(
            id = "account",
            accessToken = "access",
            refreshToken = "refresh",
            accessExpiresAtMillis = 0L,
            tokenEndpoint = "https://token.example.test",
            clientId = "client",
        )

        assertNull(storage.args("attachOAuth")["username"])
    }
}

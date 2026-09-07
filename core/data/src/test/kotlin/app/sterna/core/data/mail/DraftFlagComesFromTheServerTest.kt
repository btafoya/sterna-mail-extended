package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ImapMailService.fetchIsDraft` RUN against a real service on a scripted socket: the flag comes
 * back from the SERVER, and the three answers stay three.
 */
class DraftFlagComesFromTheServerTest {

    @Test fun `the flag the server states is the one that comes back`() {
        assertEquals(true, readWith(flags = "\\Seen \\Draft"))
        assertEquals(false, readWith(flags = "\\Seen"))
    }

    @Test fun `a server that answers nothing readable is unknown, not a no`() {
        // No FETCH line at all, then a FETCH line carrying every item but FLAGS: neither may come
        // back as `false`, which the caller reads as a licence to destroy.
        assertNull(readWith(fetch = { tag -> "$tag OK fetch completed\r\n" }))
        assertNull(readWith(fetch = { tag ->
            "* 1 FETCH (UID 7 INTERNALDATE \"01-Jun-2026 10:00:00 +0000\")\r\n$tag OK fetch completed\r\n"
        }))
    }

    @Test fun `the fetch that goes out asks for the flags and for nothing else`() {
        ScriptedImapServer(stating(77L) { tag -> flagsAnswer(tag, "\\Draft") }).use { server ->
            val read = runBlocking { service(store(77L)).fetchIsDraft(credentials(server), INBOX, 7L) }

            assertEquals(true, read)
            assertEquals(listOf("UID FETCH 7 (FLAGS)"), server.issued("UID FETCH"))
        }
    }

    /**
     * The read is opposed to the numbering the uid was frozen under (#99, #189): the caller of this
     * is about to decide whether a message may be destroyed, on a folder that may have renumbered.
     */
    @Test fun `a renumbered folder is not read at all`() {
        var refusal: Throwable? = null
        var read: Boolean? = null
        ScriptedImapServer(stating(77L) { tag -> flagsAnswer(tag, "\\Draft") }).use { server ->
            runBlocking {
                refusal = runCatching {
                    read = service(store(77L)).fetchIsDraft(credentials(server), INBOX, 7L, 42L)
                }.exceptionOrNull()
            }

            assertEquals(emptyList<String>(), server.issued("UID FETCH"))
        }
        assertNull(read)
        assertTrue("the renumbering must reach the caller, got $refusal", refusal is ImapUidValidityChanged)
    }

    // ---- fixtures -----------------------------------------------------------------------------

    private fun readWith(
        flags: String = "\\Seen",
        fetch: (String) -> String = { tag -> flagsAnswer(tag, flags) },
    ): Boolean? = ScriptedImapServer(stating(77L, fetch)).use { server ->
        runBlocking { service(store(77L)).fetchIsDraft(credentials(server), INBOX, 7L) }
    }

    private fun flagsAnswer(tag: String, flags: String) =
        "* 1 FETCH (UID 7 FLAGS ($flags))\r\n$tag OK fetch completed\r\n"

    /** A server announcing [announce] as its numbering on every SELECT, and answering [fetch]. */
    private fun stating(announce: Long, fetch: (String) -> String): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY $announce] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID FETCH") -> fetch(tag)
            else -> "$tag OK done\r\n"
        }
    }

    private fun store(recorded: Long) =
        RememberedNumbering().apply { numbers["$ACCOUNT/$INBOX"] = recorded }

    private fun service(store: UidValidityStore) =
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), store)

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "tester",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    /** See the twin in [ReadUnderTheNumberingItWasTickedUnderTest]; duplicated rather than shared,
     *  so that neither test's fixture can be loosened for both at once. */
    private class RememberedNumbering : UidValidityStore {
        val numbers = mutableMapOf<String, Long>()
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? =
            numbers["$accountId/$mailboxId"]
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
            if (uidValidity <= 0L) return
            numbers["$accountId/$mailboxId"] = uidValidity
        }
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
            record(accountId, mailboxId, uidValidity)
        }
    }

    private companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"

        /** `ImapMailService` requires an `OAuthTokenRefresher`, which requires an Android Context;
         *  a password account never reads a field of it. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

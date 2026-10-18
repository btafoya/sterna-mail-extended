package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapSearchCriteria
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #255 through `ImapMailService.search`: the claims handed in reach the walk under the recorded
 * numbering, and the probe's answer comes back as index ids. INBOX states 42 and lists nothing as
 * starred; of the claims 5 and 6, the probe finds 6 only, read but no longer starred.
 */
class SearchWalkFeedsTheIndexVerdictTest {

    private fun server() = ScriptedImapServer { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY 42] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID SEARCH UID") -> "* SEARCH 6\r\n$tag OK search completed\r\n"
            line.startsWith("UID SEARCH") -> "* SEARCH\r\n$tag OK search completed\r\n"
            line.startsWith("UID FETCH") && line.endsWith("(UID FLAGS)") ->
                "* 1 FETCH (UID 6 FLAGS (\\Seen))\r\n$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

    private fun search(server: ScriptedImapServer, store: UidValidityStore): ImapSearchHits = runBlocking {
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), store).search(
            AccountCredentials(
                server = "scripted",
                username = "tester",
                password = "secret",
                id = ACCOUNT,
                protocol = MailProtocol.IMAP,
                imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
            ),
            listOf("INBOX"),
            ImapSearchCriteria(flagged = true),
            requireAttachment = false,
            limit = 50,
            indexClaims = mapOf("INBOX" to setOf(5L, 6L)),
        )
    }

    @Test fun `claims under the recorded numbering come back as the ids the probe settled`() {
        val store = RememberedNumbering().apply { numbers["$ACCOUNT/INBOX"] = 42L }
        server().use { server ->
            val hits = search(server, store)

            assertEquals(listOf("UID SEARCH UID 5:6"), server.issued("UID SEARCH UID"))
            assertEquals(listOf("imap:$ACCOUNT:INBOX:5"), hits.indexGone)
            assertEquals(listOf(IndexFlags("imap:$ACCOUNT:INBOX:6", seen = true, flagged = false)), hits.indexFlags)
        }
    }

    @Test fun `a folder with no numbering recorded is not probed and settles nothing`() {
        server().use { server ->
            val hits = search(server, RememberedNumbering())

            assertEquals(emptyList<String>(), server.issued("UID SEARCH UID"))
            assertEquals(emptyList<String>(), hits.indexGone)
            assertEquals(emptyList<IndexFlags>(), hits.indexFlags)
        }
    }

    /** The store minus Room, keeping `record`'s invariant that a value of 0 or less is never written. */
    private class RememberedNumbering : UidValidityStore {
        val numbers = mutableMapOf<String, Long>()
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? = numbers["$accountId/$mailboxId"]
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
            if (uidValidity <= 0L) return
            numbers["$accountId/$mailboxId"] = uidValidity
        }
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) =
            record(accountId, mailboxId, uidValidity)
    }

    private companion object {
        const val ACCOUNT = "acc1"

        /** An [OAuthTokenRefresher] that is never used and cannot be built. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

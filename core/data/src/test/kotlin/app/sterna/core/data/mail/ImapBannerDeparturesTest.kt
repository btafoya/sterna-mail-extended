package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.imap.expandUidSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Codeberg #134 on IMAP: a message deleted, moved or flagged `\Deleted` from another client left
 * its banner up. The server is asked which announced UIDs it still holds; only its answer takes a
 * banner down, and an unanswered question takes none.
 */
class ImapBannerDeparturesTest {

    // ---- which ids are put to this folder ------------------------------------------------------

    @Test fun `an id of this folder is asked by its UID`() {
        assertEquals(
            mapOf(7L to id(INBOX, 7), 9L to id(INBOX, 9)),
            imapDepartureUids(listOf(id(INBOX, 7), id(INBOX, 9)), ACCOUNT, INBOX),
        )
    }

    @Test fun `an id of another folder is never asked`() {
        // UID 7 of Archive is another message than UID 7 of the Inbox: asked there, its answer would
        // take down the banner of a message that never left.
        assertEquals(
            mapOf(7L to id(INBOX, 7)),
            imapDepartureUids(listOf(id(INBOX, 7), id("Archive", 8), id("INBOX/Sub", 9)), ACCOUNT, INBOX),
        )
        assertEquals(
            "a folder whose name holds the separator is still told apart",
            mapOf(5L to id("a:b", 5)),
            imapDepartureUids(listOf(id("a", 4), id("a:b", 5)), ACCOUNT, "a:b"),
        )
    }

    @Test fun `an id of another account, or not an IMAP id, is never asked`() {
        assertEquals(
            emptyMap<Long, String>(),
            imapDepartureUids(
                listOf(ImapMailService.emailId("other", INBOX, 7), "M1", "imap:broken"),
                ACCOUNT,
                INBOX,
            ),
        )
    }

    // ---- the question, on the wire -------------------------------------------------------------

    @Test fun `a UID the folder no longer holds is gone, one it holds is kept`() {
        ScriptedImapServer(folder(held = setOf(7L, 9L))).use { server ->
            assertEquals(listOf(8L), ask(server, listOf(7L, 8L, 9L)))
        }
    }

    @Test fun `a UID still there but flagged deleted is gone`() {
        ScriptedImapServer(folder(held = setOf(7L, 8L), flaggedDeleted = setOf(8L))).use { server ->
            assertEquals(listOf(8L), ask(server, listOf(7L, 8L)))
            assertEquals(listOf("UID SEARCH UID 7:8 UNDELETED"), server.issued("UID SEARCH"))
        }
    }

    @Test fun `an answer split over several lines is read whole`() {
        ScriptedImapServer(folder(held = setOf(7L, 8L, 9L), splitAnswer = true)).use { server ->
            assertEquals(
                "a UID on the second SEARCH line is still held: dropped, its banner would fall",
                emptyList<Long>(),
                ask(server, listOf(7L, 8L, 9L)),
            )
        }
    }

    @Test fun `the question goes out in batches of 200`() {
        ScriptedImapServer(folder(held = emptySet())).use { server ->
            assertEquals((1L..450L).toList(), ask(server, (1L..450L).toList()))
            assertEquals(
                listOf(
                    "UID SEARCH UID 1:200 UNDELETED",
                    "UID SEARCH UID 201:400 UNDELETED",
                    "UID SEARCH UID 401:450 UNDELETED",
                ),
                server.issued("UID SEARCH"),
            )
        }
    }

    @Test fun `a refused question confirms nothing and does not throw`() {
        for (status in listOf("NO", "BAD")) {
            ScriptedImapServer(folder(held = emptySet(), refuse = status)).use { server ->
                assertEquals("refused with $status", emptyList<Long>(), ask(server, listOf(7L, 8L)))
            }
        }
    }

    @Test fun `a batch refused after another was answered confirms nothing at all`() {
        ScriptedImapServer(folder(held = emptySet(), refuse = "NO", refuseFrom = 2)).use { server ->
            assertEquals(emptyList<Long>(), ask(server, (1L..450L).toList()))
        }
    }

    @Test fun `a connection cut on the question confirms nothing and does not throw`() {
        ScriptedImapServer(folder(held = emptySet(), hangUp = true)).use { server ->
            assertEquals(emptyList<Long>(), ask(server, listOf(7L, 8L)))
        }
    }

    @Test fun `a renumbered folder confirms nothing and is not asked`() {
        val store = RememberedNumbering().apply { numbers["$ACCOUNT/$INBOX"] = 42L }
        ScriptedImapServer(folder(held = emptySet(), uidValidity = 43L)).use { server ->
            assertEquals(emptyList<Long>(), ask(server, listOf(7L, 8L), store))
            assertEquals(
                "a UID of the old numbering names nothing in the new one",
                emptyList<String>(),
                server.issued("UID SEARCH"),
            )
        }
    }

    @Test fun `nothing to ask opens no connection`() {
        ScriptedImapServer(folder(held = emptySet())).use { server ->
            assertEquals(emptyList<Long>(), ask(server, emptyList()))
            assertEquals(0, server.connections.get())
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private fun ask(
        server: ScriptedImapServer,
        uids: List<Long>,
        store: UidValidityStore = RememberedNumbering(),
    ): List<Long> = runBlocking {
        ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), store)
            .confirmedAbsent(credentials(server), INBOX, uids)
    }

    /**
     * An Inbox holding [held], of which [flaggedDeleted] await their EXPUNGE. A `UID SEARCH` from
     * the [refuseFrom]th on is answered [refuse] when set; [hangUp] drops every one.
     */
    private fun folder(
        held: Set<Long>,
        flaggedDeleted: Set<Long> = emptySet(),
        splitAnswer: Boolean = false,
        refuse: String? = null,
        refuseFrom: Int = 1,
        hangUp: Boolean = false,
        uidValidity: Long = 42L,
    ): (String, String) -> String {
        var searches = 0
        return { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS] logged in\r\n"
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("SELECT") ->
                    "* ${held.size} EXISTS\r\n* OK [UIDVALIDITY $uidValidity] ok\r\n" +
                        "* OK [UIDNEXT 1000] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("UID SEARCH UID ") -> when {
                    hangUp -> ScriptedImapServer.HANG_UP
                    refuse != null && ++searches >= refuseFrom -> "$tag $refuse search refused\r\n"
                    else -> {
                        val words = line.removePrefix("UID SEARCH UID ").split(' ')
                        val live = expandUidSet(words[0])
                            .filter { it in held && !("UNDELETED" in words && it in flaggedDeleted) }
                        val lines = if (splitAnswer && live.size > 1) {
                            listOf(live.take(1), live.drop(1))
                        } else {
                            listOf(live)
                        }
                        lines.joinToString("") { "* SEARCH ${it.joinToString(" ")}\r\n" } +
                            "$tag OK search completed\r\n"
                    }
                }
                else -> "$tag OK done\r\n"
            }
        }
    }

    private fun credentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "tester",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private fun id(mailbox: String, uid: Long) = ImapMailService.emailId(ACCOUNT, mailbox, uid)

    private class RememberedNumbering : UidValidityStore {
        val numbers = mutableMapOf<String, Long>()
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? =
            numbers["$accountId/$mailboxId"]
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
            if (uidValidity > 0L) numbers["$accountId/$mailboxId"] = uidValidity
        }
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) =
            record(accountId, mailboxId, uidValidity)
    }

    private companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"

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

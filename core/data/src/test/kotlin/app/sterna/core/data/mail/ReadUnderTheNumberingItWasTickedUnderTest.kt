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
 * The READ half of the numbering guard, RUN against a real [ImapMailService] on a scripted socket
 * — the twin of [MoveNeedsTheNumberingStatedNowTest], which does the same for the move (#99, #189).
 *
 * THE SITUATION IT REPRODUCES, and it is the whole point of the volet. The folder is renumbered
 * while the "move to another account" loop is running. The FIRST iteration meets the renumbering,
 * `onMailbox` invalidates, and the folder's RECORDED number is realigned to the server's. From
 * then on the record and the server agree — so every path that re-reads the record opposes the new
 * number to itself, concludes SAME, and sails through. The only fact that still disagrees is the
 * number each row carried WHEN IT WAS TICKED, and that number has to travel all the way to the
 * wire or it decides nothing.
 *
 * So every case below is scripted with the RECORD ALREADY REALIGNED (77) and the server stating
 * that same 77. A read that opposes the record cannot fail here; only one that opposes the stamp
 * it was handed can. That is what makes this a test of the ARGUMENT and not of the call.
 */
class ReadUnderTheNumberingItWasTickedUnderTest {

    /**
     * NOT ONE OCTET. The refusal has to land here, on the read, because [crossAccountMove] runs
     * the read BEFORE the write on the other account: refused here the move is a `Failed` and
     * nothing at all was written. Refused only at the Trash — which is what happens when the read
     * opposes nothing — the copy is already appended on B under an APPEND that is never replayed,
     * and the user is left with a permanent duplicate of a message she never pointed at, with no
     * Undo on this path.
     */
    @Test fun `the numbering of the tick is what is opposed, and a renumbered folder is not read`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        var read: String? = null
        ScriptedImapServer(stating(77L)).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                // What a background pass wrote after it met the renumbering: the folder's record
                // now says 77, which is also what the server says.
                store.record(ACCOUNT, INBOX, 77L)
                // The row was ticked under 42.
                refusal = runCatching { read = service.fetchSource(credentials, INBOX, 7L, 42L) }.exceptionOrNull()
            }

            assertEquals(
                "the SELECT must have gone out — the refusal is the server's answer to it, not a " +
                    "decision taken without asking",
                1, server.issued("SELECT").size,
            )
            assertEquals(
                "and NOT ONE OCTET may be fetched: this uid belonged to the numbering the row was " +
                    "ticked under, and the folder no longer carries it. Read under the FOLDER's " +
                    "record instead — 77 against 77 — the fetch goes out and the octets of " +
                    "whatever holds uid 7 now are the ones copied to the other account",
                emptyList<String>(), server.issued("UID FETCH"),
            )
            assertEquals(
                "the refusal must cost exactly ONE connection: ImapUidValidityChanged is not an " +
                    "ImapException, so runWithRetry re-throws it instead of reconnecting",
                1, server.connections.get(),
            )
        }
        assertNull("and nothing may come back to be written on the other account", read)
        assertTrue(
            "the refusal must reach the caller as the renumbering it is — crossAccountMove turns " +
                "it into a Failed, which is the PARTLY_FAILED banner. Got $refusal",
            refusal is ImapUidValidityChanged,
        )
        val renumbered = refusal as ImapUidValidityChanged
        assertEquals(INBOX, renumbered.mailbox)
        assertEquals(
            "and it must name the number that was OPPOSED — the stamp of the tick, 42. Naming 77 " +
                "would mean the folder's own record was opposed to itself and the refusal came " +
                "from somewhere else entirely",
            42L, renumbered.expected,
        )
        assertEquals(77L, renumbered.observed)
        assertEquals(
            "and the refusal must not rewrite the record with the tick's number: the server's 77 " +
                "is the truth about the folder, and the next ordinary read must still work",
            mapOf("$ACCOUNT/$INBOX" to 77L), store.numbers,
        )
    }

    /**
     * THE INVERSE WITNESS. Without it, "refuse every read that carries a stamp" satisfies the
     * case above — and every cross-account move on every healthy server would fail, for ever.
     */
    @Test fun `the numbering the folder still carries reads the message`() {
        val store = RememberedNumbering()
        var read: String? = null
        ScriptedImapServer(stating(77L)).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                store.record(ACCOUNT, INBOX, 77L)
                read = service.fetchSource(credentials, INBOX, 7L, 77L)
            }

            assertEquals(
                "nothing was renumbered: the row was ticked under 77 and the server states 77. A " +
                    "refusal here is the guard firing at a healthy server, i.e. an outage",
                listOf("UID FETCH 7 (BODY.PEEK[])"), server.issued("UID FETCH"),
            )
        }
        assertEquals("and the octets must be the message's own", SOURCE, read)
    }

    /**
     * And a read that carries NOTHING keeps the behaviour every ordinary reader depends on: the
     * fallback on the folder's recorded number, inside [ImapMailService] `onMailbox`.
     *
     * This is why the stamp is carried as `Long?` DOWN HERE and not made mandatory. Opening a
     * message, viewing its headers, saving it as `.eml` and retrying an OpenPGP interaction all
     * read the source with nothing frozen — a row cached before the numbering column existed, or
     * one evicted and re-cached, carries no stamp at all — and refusing them would be "this app can
     * no longer show mail".
     *
     * AND A "FROZEN BUT ABSENT" NEVER ARRIVES HERE (#189). Since the move between accounts
     * carries a `FrozenNumbering`, `MailRepository.fetchRawSource` refuses `Frozen(null)` upstream
     * of this layer and hands `ImapMailService` a number only when there is one to hand. So the
     * `null` this test drives is exclusively "no tick froze anything", the fallback below is
     * exclusively that case's, and this test no longer certifies the defect it used to sit beside.
     * `ImapMailService` and `core/imap` are unchanged: the refusal is posted before them, never
     * inside `onMailbox`, where a throw would be replayed by `withSession` and confused with
     * `ImapUidValidityChanged` — which invalidates the folder.
     */
    @Test fun `a read that freezes nothing falls back on the record, exactly as before`() {
        val store = RememberedNumbering()
        var read: String? = null
        ScriptedImapServer(stating(77L)).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                store.record(ACCOUNT, INBOX, 77L)
                read = service.fetchSource(credentials, INBOX, 7L)
            }

            assertEquals(listOf("UID FETCH 7 (BODY.PEEK[])"), server.issued("UID FETCH"))
        }
        assertEquals(SOURCE, read)
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** A server whose every SELECT announces [announce], and whose only FETCH answers [SOURCE] as
     *  a literal — the shape `ImapClient.fetchSource` reads through `bodyItem()`. */
    private fun stating(announce: Long): (String, String) -> String = { tag, line ->
        when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY $announce] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            line.startsWith("UID FETCH") ->
                "* 1 FETCH (UID 7 BODY[] {${SOURCE.length}}\r\n$SOURCE)\r\n$tag OK fetch completed\r\n"
            else -> "$tag OK done\r\n"
        }
    }

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

    /** See the twin in [MoveNeedsTheNumberingStatedNowTest]; duplicated rather than shared, so
     *  that neither test's fixture can be loosened for both at once. */
    private class RememberedNumbering : UidValidityStore {
        val numbers = mutableMapOf<String, Long>()
        val invalidated = mutableListOf<String>()
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? =
            numbers["$accountId/$mailboxId"]
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) {
            if (uidValidity <= 0L) return
            numbers["$accountId/$mailboxId"] = uidValidity
        }
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
            invalidated += "$accountId/$mailboxId"
            record(accountId, mailboxId, uidValidity)
        }
    }

    private companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"
        const val SOURCE = "Subject: Quarterly figures\r\n\r\nHello.\r\n"

        /** See [MoveNeedsTheNumberingStatedNowTest]'s twin — `ImapMailService` requires an
         *  `OAuthTokenRefresher`, which requires an Android `Context`, and a password account
         *  never reads a field of it. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

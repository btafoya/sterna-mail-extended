package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A permanent delete opposes the numbering ITS ROWS WERE READ UNDER, not the folder's current
 * record (Codeberg #99).
 *
 * The hole this closes, in the order it happens:
 *
 * 1. a background pass (multi-folder push: [ImapMailService.loadWatchedFolders]) selects the folder
 *    and finds it renumbered. `reconcileNumbering` writes the NEW number into `mailbox_uidvalidity`
 *    and drops what is keyed by UID — but the `emails` rows of the OLD UIDs stay: nothing prunes
 *    them on IMAP (`MailRepository.refreshAccountFolders`, "No departures on IMAP");
 * 2. the user taps delete on one of those rows. The freeze read the FOLDER's numbering, i.e. the
 *    new one, and the SELECT that follows compares it with itself: SAME, no refusal, and
 *    `UID STORE +FLAGS (\Deleted)` + `UID EXPUNGE` go out on UIDs that now name OTHER, live
 *    messages. On an account with no Trash that folder is the INBOX.
 *
 * The fix is an appariement, not a check: the row carries the numbering of the SELECT that
 * enumerated it ([EmailEntity.uidValidity]), so ids and number come out of the same server
 * response and cannot drift apart. The same thing `TrashPurge.snapshotUidValidity` already does
 * for an "Empty trash".
 *
 * ⭐ Everything below RUNS: a real [ImapMailService] on a scripted socket, and the witness is the
 * command list that actually left the client. What the whole chain cannot run in a JVM is the
 * ViewModel and the worker in the middle (Application, WorkManager, Room) — those are pinned as
 * source in `HeldBackDestroyCarriesItsFolderTest`, and the routing they hand over is EXECUTED in
 * [UidValidityDestroyRoutesTest].
 */
class DestroyUnderTheNumberingTheRowWasReadUnderTest {

    /**
     * THE DEFECT, end to end: 42, then a background pass that meets 77, then a destroy built from
     * the rows read under 42. Not one command naming a UID may leave.
     */
    @Test fun `rows read under 42 destroy nothing once the folder is renumbered to 77`() {
        val store = RememberedNumbering()
        var refusal: Throwable? = null
        ScriptedImapServer(scripted { select -> if (select == 1) 42L else 77L }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                val stale = watchedPass(service, credentials)
                assertEquals(
                    "the rows a load writes must carry the numbering of the SELECT that enumerated " +
                        "them — unstamped, a destroy has only the folder's record to go on, and that " +
                        "record is exactly what the pass below realigns",
                    listOf(42L, 42L, 42L),
                    stale.map { it.uidValidity },
                )

                val fresh = watchedPass(service, credentials)
                assertEquals(
                    "the background pass must have seen the renumbering and invalidated the folder",
                    listOf("$ACCOUNT/$INBOX"),
                    store.invalidated,
                )
                assertEquals(
                    "and its own rows belong to the new numbering",
                    listOf(77L, 77L, 77L),
                    fresh.map { it.uidValidity },
                )
                assertEquals(
                    "the folder's record now says 77 — this is the value the old freeze read, and " +
                        "the reason it compared the server with itself",
                    77L,
                    service.recordedUidValidity(ACCOUNT, INBOX),
                )

                // The user taps delete on the rows STILL ON SCREEN, i.e. the stale ones.
                val route = routeFor(stale)
                assertEquals("the destroy must oppose what the rows were read under", 42L, route.uidValidity)
                refusal = runCatching {
                    service.deleteBatch(credentials, INBOX, uidsOf(route), route.uidValidity)
                }.exceptionOrNull()
            }

            assertEquals(
                "not one message may be flagged \\Deleted: these UIDs belong to a numbering the " +
                    "folder no longer has, so each of them now names another, live message",
                emptyList<String>(),
                server.issued("UID STORE"),
            )
            assertEquals("and nothing may be erased", emptyList<String>(), server.issued("UID EXPUNGE"))
        }
        assertTrue(
            "the refusal must reach the caller — swallowed, the UI reports a destroy that never " +
                "happened, got $refusal",
            refusal is ImapUidValidityChanged,
        )
    }

    /**
     * ⭐ THE INVERSE WITNESS, and it is not optional: without it "stamp nothing / refuse always"
     * satisfies the test above, and that is a total regression — no IMAP permanent delete would
     * ever go through again, the message would leave the list, come back at the next refresh, and
     * never die.
     */
    @Test fun `rows read under 42 on a folder still numbered 42 are destroyed exactly as before`() {
        val store = RememberedNumbering()
        ScriptedImapServer(scripted { 42L }).use { server ->
            val service = service(store)
            val credentials = credentials(server)
            runBlocking {
                val rows = watchedPass(service, credentials)
                assertEquals(listOf(42L, 42L, 42L), rows.map { it.uidValidity })
                // A second pass, exactly as above — only the server does not renumber.
                watchedPass(service, credentials)
                assertEquals(
                    "nothing was renumbered, so nothing may be invalidated",
                    emptyList<String>(),
                    store.invalidated,
                )

                val route = routeFor(rows)
                service.deleteBatch(credentials, INBOX, uidsOf(route), route.uidValidity)
            }

            assertEquals(listOf("UID STORE 7:9 +FLAGS (\\Deleted)"), server.issued("UID STORE"))
            assertEquals(listOf("UID EXPUNGE 7:9"), server.issued("UID EXPUNGE"))
        }
    }

    // ---- fixtures -----------------------------------------------------------------------------

    /** One background push pass over the inbox — the pass that MEETS the renumbering, records it
     *  and writes rows anyway. Returns the rows it cached. */
    private suspend fun watchedPass(service: ImapMailService, credentials: AccountCredentials): List<EmailEntity> =
        service.loadWatchedFolders(credentials, extraPaths = emptySet(), includeInbox = true, limit = 10)
            .first.single().messages

    /** The one route a held-back destroy of [rows] produces — the very function `InboxViewModel`
     *  hands to `MessageDestroyWorker.FolderDestroy`, run here on the rows the server produced. */
    private fun routeFor(rows: List<EmailEntity>): UidValidity.ImapDestroyRoute {
        val numbering = rows.associate { it.id to it.uidValidity }
        return UidValidity.imapDestroyRoutes(rows.map { it.id to INBOX }) { numbering[it] }.single()
    }

    private fun uidsOf(route: UidValidity.ImapDestroyRoute): List<Long> =
        route.emailIds.mapNotNull { ImapMailService.targetOf(it)?.second }

    /**
     * A server whose Nth SELECT announces [announce] `(n)` as its UIDVALIDITY, holding three
     * messages with UIDs 7, 8 and 9 — the same three [DestroyNeedsTheNumberingStatedNowTest]
     * destroys, so the commands a destroy puts on the wire read identically in both files.
     */
    private fun scripted(announce: (Int) -> Long): (String, String) -> String {
        var selects = 0
        return { tag, line ->
            when {
                line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS] logged in\r\n"
                line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS\r\n$tag OK done\r\n"
                line.startsWith("LIST") -> "* LIST (\\HasNoChildren) \".\" \"$INBOX\"\r\n$tag OK listed\r\n"
                line.startsWith("SELECT") ->
                    "* 3 EXISTS\r\n* OK [UIDVALIDITY ${announce(++selects)}] ok\r\n" +
                        "* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
                line.startsWith("FETCH") -> UIDS.joinToString("") { envelope(it) } + "$tag OK fetched\r\n"
                else -> "$tag OK done\r\n"
            }
        }
    }

    private fun envelope(uid: Long) =
        "* $uid FETCH (UID $uid FLAGS () INTERNALDATE \"01-Jun-2026 10:00:00 +0000\" " +
            "ENVELOPE (\"Mon, 1 Jun 2026 10:00:00 +0000\" \"Message $uid\" " +
            "((\"Alex\" NIL \"alex\" \"example.org\")) NIL NIL " +
            "((\"Tester\" NIL \"tester\" \"example.org\")) NIL NIL NIL \"<$uid@example.org>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

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

    /** The store as the app really has it, minus Room — the same stand-in
     *  [DestroyNeedsTheNumberingStatedNowTest] uses, and it keeps `record`'s invariant: a value at
     *  or below 0 is never written, which is what makes "recorded > 0" mean "the server stated a
     *  numbering at some point". */
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
        val UIDS = listOf(7L, 8L, 9L)

        /**
         * An [OAuthTokenRefresher] that is never used and cannot be built — see
         * [DestroyNeedsTheNumberingStatedNowTest], which explains why an allocated-but-not
         * -constructed instance is enough and why it keeps the test driving the REAL service.
         */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

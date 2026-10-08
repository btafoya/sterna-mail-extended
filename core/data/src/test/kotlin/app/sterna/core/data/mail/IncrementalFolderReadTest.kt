package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapException
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * The list refresh of an IMAP folder, end to end: the shipped `ImapMailService.loadFolder` on a
 * socket, the shipped write-through around it with the arguments `MailRepository.imapWriteThrough`
 * passes ([ImapWindowWiringTest] pins them), the shipped [ImapFolderWrites] between the two, and a
 * cache on SQLite written through the shipped statements. What is read here is what went on the
 * wire and what the tables hold afterwards. `inFlight` stands for the rows a swipe is holding.
 */
class IncrementalFolderReadTest {

    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private val cache = JdbcMailCache()
    private val service = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), cache.store())
    private val servers = mutableListOf<ScriptedImapServer>()

    @After fun tearDown() {
        runBlocking { listOf(ACCOUNT, OTHER).forEach { runCatching { service.disconnect(it) } } }
        servers.forEach { it.close() }
        cache.close()
    }

    private fun serving(folder: CondstoreFolder) = ScriptedImapServer(folder::respond).also { servers += it }

    private fun id(uid: Long, account: String = ACCOUNT) = "imap:$account:$INBOX:$uid"

    private fun credentials(server: ScriptedImapServer, account: String) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = account,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    private fun refresh(
        server: ScriptedImapServer,
        account: String = ACCOUNT,
        limit: Int = 50,
        held: Set<String> = emptySet(),
        wantsPreviews: Boolean = true,
        inFlight: Set<String> = emptySet(),
    ): ImapFolderLoad = runBlocking {
        val credentials = credentials(server, account)
        val cachedPreviews: suspend (List<String>, Long?) -> Map<String, String> =
            { ids, numbering -> cache.cachedPreviews(account, ids, numbering) }
        val writes = ImapFolderWrites(
            wantsPreviews = wantsPreviews,
            idsForMailbox = { mailboxId -> cache.idsForMailbox(account, mailboxId) },
            idsWithPreviewForMailbox = { mailboxId -> cache.idsWithPreviewForMailbox(account, mailboxId) },
            stillConfigured = {},
            heldLocally = { held },
            setReadAndStar = { ids, seen, flagged -> cache.setReadAndStar(account, ids, seen, flagged) },
            sparingInFlight = { rows -> rows.filterNot { it.id in inFlight } },
            upsertAll = { rows -> cache.upsert(rows) },
        )
        fullQueryWriteThrough<List<EmailEntity>, ImapFolderLoad>(
            folderIds = { cache.idsForMailbox(account, INBOX).toSet() },
            walk = { onPage ->
                service.loadFolder(
                    credentials, INBOX, limit, onlySubscribed = false, onPage = onPage,
                    cachedPreviewsFor = cachedPreviews.takeIf { wantsPreviews }, delta = writes.delta,
                )
            },
            writePage = { page -> writes.writePage(page) },
            keepIds = { load -> reconcilableIds(load, account) },
            spareIds = { emptyList() },
            reconcile = { load, keepIds, spare, evictable ->
                cache.reconcile(account, load.targetMailboxId, keepIds, spare, evictable)
                service.rememberModSeq(account, load, writes.everyRowWritten)
            },
        )
    }

    private fun modSeq(account: String = ACCOUNT): Long? = cache.numbering()["$account/$INBOX"]?.second

    /** A folder read once in full, its commands forgotten: where every delta below starts from. */
    private fun walkedOnce(folder: CondstoreFolder, server: ScriptedImapServer): List<Map<String, String?>> {
        refresh(server)
        assertEquals("the first read did not walk the folder", 1, server.sequenceFetches().size)
        assertEquals("the first read kept no mod-sequence", folder.highest, modSeq())
        server.commands.clear()
        return cache.rows(ACCOUNT)
    }

    private fun assertNoWalk(server: ScriptedImapServer) = assertEquals(
        "the folder was walked by sequence range again",
        emptyList<String>(),
        server.sequenceFetches(),
    )

    private fun ids() = cache.rows(ACCOUNT).map { it["id"] }

    // -- the two readings --------------------------------------------------------------------------

    @Test fun `the first read walks the folder and keeps the SELECT's mod-sequence, the second is a delta`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        val stated = folder.highest

        refresh(server)

        assertEquals(listOf("SELECT \"INBOX\" (CONDSTORE)"), server.selects())
        assertTrue(server.sequenceFetches().single().startsWith("FETCH 1:5 "))
        assertEquals(mapOf("$ACCOUNT/$INBOX" to (9L to stated)), cache.numbering())
        val walked = cache.rows(ACCOUNT)
        assertEquals((101L..105L).map { id(it) }, walked.map { it["id"] })
        assertEquals((101L..105L).map { "Opening of $it" }, walked.map { it["preview"]?.trim() })
        server.commands.clear()

        refresh(server)

        assertNoWalk(server)
        assertEquals("a delta with nothing new fetched a message whole", emptyList<String>(), server.envelopeFetchesByUid())
        val issued = server.issued()
        assertEquals(
            "the live set must be the first command after the SELECT: an EXPUNGE handed to any other is lost",
            "UID SEARCH 1:* UNDELETED",
            issued[issued.indexOf("SELECT \"INBOX\" (CONDSTORE)") + 1],
        )
        assertTrue(issued.toString(), "UID FETCH 101:* (UID FLAGS) (CHANGEDSINCE $stated)" in issued)
        assertEquals("nothing changed at the server, and the cache did", walked, cache.rows(ACCOUNT))
        assertEquals(stated, modSeq())
    }

    @Test fun `the delta lists the window the account asked for`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)

        refresh(server, limit = 3)
        assertTrue(server.sequenceFetches().single().startsWith("FETCH 3:5 "))
        server.commands.clear()
        refresh(server, limit = 3)

        assertNoWalk(server)
        assertTrue(server.issued().toString(), "UID SEARCH 3:* UNDELETED" in server.issued())
        assertEquals((103L..105L).map { id(it) }, ids())
    }

    @Test fun `a flag set elsewhere changes its column and nothing else of the row`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        val before = walkedOnce(folder, server)
        folder.flag(103, "\\Flagged")
        folder.unflag(102, "\\Seen")

        refresh(server)

        assertNoWalk(server)
        assertEquals(emptyList<String>(), server.envelopeFetchesByUid())
        val after = cache.rows(ACCOUNT)
        assertEquals("1" to "1", cache.row(ACCOUNT, id(103))!!.let { it["seen"] to it["flagged"] })
        assertEquals("0" to "0", cache.row(ACCOUNT, id(102))!!.let { it["seen"] to it["flagged"] })
        assertEquals(
            "a flag write touched another column: the preview and the rest of the row are not the delta's to write",
            before.map { it - "seen" - "flagged" },
            after.map { it - "seen" - "flagged" },
        )
        assertEquals(listOf("1", "0", "1", "1", "1"), after.map { it["seen"] })
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `a message that arrived is the only one fetched whole, and the window stays`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        val before = walkedOnce(folder, server)
        folder.arrive(106)

        refresh(server)

        assertNoWalk(server)
        assertTrue(server.envelopeFetchesByUid().toString(), server.envelopeFetchesByUid().single().startsWith("UID FETCH 106 "))
        val arrived = cache.row(ACCOUNT, id(106))
        assertEquals("Message 106", arrived?.get("subject"))
        assertEquals("Opening of 106", arrived?.get("preview")?.trim())
        assertEquals(
            "rows the delta did not fetch were evicted or rewritten: its walk must name the whole live window",
            before,
            cache.rows(ACCOUNT).filter { it["id"] != id(106) },
        )
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `a message expunged at the server leaves the cache`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        folder.expunge(102)

        refresh(server)

        assertNoWalk(server)
        assertEquals(listOf(101L, 103L, 104L, 105L).map { id(it) }, ids())
    }

    @Test fun `a message flagged deleted and not expunged leaves the cache too`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        folder.flag(104, "\\Deleted")

        refresh(server)

        assertNoWalk(server)
        assertEquals(listOf(101L, 102L, 103L, 105L).map { id(it) }, ids())
    }

    // -- what the delta may leave as it is ---------------------------------------------------------

    @Test fun `a flag the user has just changed here is not put back, and the mod-sequence waits for it`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        folder.unflag(102, "\\Seen")

        refresh(server, held = setOf(id(102)))

        assertNoWalk(server)
        assertEquals("1", cache.row(ACCOUNT, id(102))!!["seen"])
        assertEquals("the mod-sequence moved past a change that was not written", kept, modSeq())

        refresh(server)

        assertEquals("0", cache.row(ACCOUNT, id(102))!!["seen"])
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `a cached row with no opening line is fetched again when the list wants one`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        cache.update("UPDATE emails SET preview = NULL WHERE id = :id", mapOf("id" to id(103)))

        refresh(server, wantsPreviews = false)

        assertEquals(emptyList<String>(), server.envelopeFetchesByUid())
        assertNull(cache.row(ACCOUNT, id(103))!!["preview"])
        server.commands.clear()

        refresh(server)

        assertNoWalk(server)
        assertTrue(server.envelopeFetchesByUid().toString(), server.envelopeFetchesByUid().single().startsWith("UID FETCH 103 "))
        assertEquals("Opening of 103", cache.row(ACCOUNT, id(103))!!["preview"]?.trim())
    }

    @Test fun `a walk that left out a row a swipe is holding keeps no mod-sequence`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)

        refresh(server, inFlight = setOf(id(103)))

        assertEquals(listOf(101L, 102L, 104L, 105L).map { id(it) }, ids())
        assertEquals(
            "a walk kept a mod-sequence although one of its pages lost a row to a swipe in flight",
            mapOf("$ACCOUNT/$INBOX" to (9L to null)),
            cache.numbering(),
        )
        server.commands.clear()

        refresh(server)

        assertEquals(1, server.sequenceFetches().size)
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `a change to a row a swipe is holding is read again once the row is put back as it was`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        cache.update("CREATE TABLE held AS SELECT * FROM emails WHERE id LIKE '%103'")
        cache.update("DELETE FROM emails WHERE id LIKE '%103'")
        folder.flag(103, "\\Flagged")

        refresh(server, inFlight = setOf(id(103)))

        assertNoWalk(server)
        assertNull("the delta wrote back a row a swipe was holding", cache.row(ACCOUNT, id(103)))
        assertEquals("the mod-sequence moved past a change that was not written", kept, modSeq())

        cache.update("INSERT INTO emails SELECT * FROM held")
        refresh(server)

        assertEquals("the flag set while the row was held never reached it", "1", cache.row(ACCOUNT, id(103))!!["flagged"])
        assertEquals(folder.highest, modSeq())
    }

    // -- falling back on the walk ------------------------------------------------------------------

    @Test fun `an EXPUNGE announced during the delta falls back on a walk from a fresh SELECT, and keeps no mod-sequence`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        folder.flag(105, "\\Flagged")
        folder.slipIn["UID SEARCH"] = "* 2 EXPUNGE\r\n"

        refresh(server)

        assertEquals(2, server.selects().size)
        assertEquals(1, server.sequenceFetches().size)
        assertTrue(
            "the walk started from the SELECT the delta had already spent",
            server.issued().indexOf(server.sequenceFetches().single()) > server.issued().lastIndexOf("SELECT \"INBOX\" (CONDSTORE)"),
        )
        assertEquals("the fallback opened another connection", 1, server.connections.get())
        assertEquals("1", cache.row(ACCOUNT, id(105))!!["flagged"])
        assertEquals(5, ids().size)
        assertEquals("a pass whose delta gave up kept a mod-sequence", kept, modSeq())
        server.commands.clear()

        refresh(server)

        assertEquals("a folder that moved once under its delta is never read as a delta again", emptyList<String>(), server.sequenceFetches())
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `an EXPUNGE announced with the changed flags falls back on the walk, and keeps no mod-sequence`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        folder.flag(105, "\\Flagged")
        folder.slipIn["UID FETCH 101:* (UID FLAGS)"] = "* 2 EXPUNGE\r\n"

        refresh(server)

        assertEquals("the delta went on past an EXPUNGE handed to its flag read", 1, server.sequenceFetches().size)
        assertEquals(2, server.selects().size)
        assertEquals("1", cache.row(ACCOUNT, id(105))!!["flagged"])
        assertEquals("a pass whose delta gave up kept a mod-sequence", kept, modSeq())
    }

    @Test fun `a folder that grew while the absent messages were fetched falls back on the walk, and keeps no mod-sequence`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        folder.arrive(106)
        folder.slipIn["UID FETCH 106 "] = "* 7 EXISTS\r\n"

        refresh(server)

        assertEquals("the delta went on past an EXISTS that was not the SELECT's", 1, server.sequenceFetches().size)
        assertEquals(2, server.selects().size)
        assertEquals(6, ids().size)
        assertEquals("a pass whose delta gave up kept a mod-sequence", kept, modSeq())
    }

    @Test fun `a command the server refuses falls back on the walk, on the same connection`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        folder.flag(105, "\\Flagged")
        folder.refuseOnce += "UID FETCH 101:* (UID FLAGS)"

        refresh(server)

        assertEquals(1, server.sequenceFetches().size)
        assertEquals(1, server.connections.get())
        assertEquals("1", cache.row(ACCOUNT, id(105))!!["flagged"])
        assertTrue("the server's mod-sequence did not move, so the next line would prove nothing", folder.highest > kept!!)
        assertEquals("a pass whose delta was refused took the mod-sequence of the walk that followed", kept, modSeq())
    }

    private fun refusedDeltaIsNotAskedAgain(refusedCommand: String) {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        folder.flag(105, "\\Flagged")
        folder.refuseAlways += refusedCommand

        refresh(server)
        refresh(server)
        refresh(server)

        assertEquals(
            "a delta the server refused was attempted again on the same folder",
            1,
            server.issued().count { it.startsWith("UID SEARCH") },
        )
        assertEquals(3, server.sequenceFetches().size)
        assertEquals(1, server.connections.get())
        assertEquals("a walk that follows no delta of its own keeps its mark", folder.highest, modSeq())
    }

    @Test fun `a folder whose flag read the server refuses is walked from then on, the delta not asked again`() =
        refusedDeltaIsNotAskedAgain("UID FETCH 101:* (UID FLAGS)")

    @Test fun `a folder whose live set the server refuses is walked from then on, the delta not asked again`() =
        refusedDeltaIsNotAskedAgain("UID SEARCH")

    @Test fun `a delta that dies with its connection is retried as a walk, once`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        val kept = modSeq()
        folder.flag(105, "\\Flagged")
        folder.hangUpOnce += "UID FETCH 101:* (UID FLAGS)"

        refresh(server)

        assertEquals(2, server.connections.get())
        assertEquals(1, server.sequenceFetches().size)
        assertEquals(1, server.issued().count { it.startsWith("UID SEARCH") })
        assertEquals("1", cache.row(ACCOUNT, id(105))!!["flagged"])
        assertTrue("the server's mod-sequence did not move, so the next line would prove nothing", folder.highest > kept!!)
        assertEquals("a pass whose delta died took the mod-sequence of the walk that followed", kept, modSeq())
        server.commands.clear()

        refresh(server)

        assertNoWalk(server)
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `a mod-sequence that went backwards is not asked since, the folder is walked and the lower value kept`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        folder.flag(103, "\\Flagged")
        folder.rebuildIndex()

        refresh(server)

        assertEquals(
            "a delta was asked since a mod-sequence the server no longer reaches",
            emptyList<String>(),
            server.issued().filter { it.startsWith("UID SEARCH") },
        )
        assertEquals(1, server.sequenceFetches().size)
        assertEquals("1", cache.row(ACCOUNT, id(103))!!["flagged"])
        assertEquals(5L, modSeq())
    }

    @Test fun `a live set that comes back empty from a folder holding messages is not believed`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        val before = walkedOnce(folder, server)
        folder.searchAnswersNothing = true

        refresh(server)

        assertEquals("the delta took an empty answer for an empty window", 1, server.sequenceFetches().size)
        assertEquals(before, cache.rows(ACCOUNT))
    }

    // -- when no mod-sequence is kept --------------------------------------------------------------

    @Test fun `a walk the reconcile refuses keeps no mod-sequence, and the next read walks again`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        folder.slipIn["FETCH 1:5"] = "* 1 EXPUNGE\r\n"

        refresh(server)

        assertEquals(mapOf("$ACCOUNT/$INBOX" to (9L to null)), cache.numbering())
        assertEquals(5, ids().size)
        server.commands.clear()

        refresh(server)

        assertEquals(1, server.sequenceFetches().size)
        assertEquals(folder.highest, modSeq())
    }

    @Test fun `a renumbered folder is walked, and its mod-sequence is gone until a walk is reconciled`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        walkedOnce(folder, server)
        folder.uidValidity = 10L
        folder.slipIn["FETCH 1:5"] = "* 1 EXPUNGE\r\n"

        refresh(server)

        assertEquals(1, server.sequenceFetches().size)
        assertEquals(emptyList<String>(), server.issued().filter { it.startsWith("UID SEARCH") })
        assertEquals(mapOf("$ACCOUNT/$INBOX" to (10L to null)), cache.numbering())
    }

    @Test fun `a read that fails writes nothing`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)
        val before = walkedOnce(folder, server)
        val numbering = cache.numbering()
        folder.flag(103, "\\Flagged")
        folder.down = true

        val thrown = runCatching { refresh(server) }.exceptionOrNull()

        assertTrue("the read of a server that hangs up succeeded", thrown != null)
        assertEquals(before, cache.rows(ACCOUNT))
        assertEquals(numbering, cache.numbering())
    }

    @Test fun `a server without CONDSTORE is walked every time, by the plain SELECT`() {
        val folder = CondstoreFolder(5).apply { capabilities = "IMAP4rev1 UIDPLUS" }
        val server = serving(folder)

        refresh(server)
        refresh(server)

        assertEquals(listOf("SELECT \"INBOX\"", "SELECT \"INBOX\""), server.selects())
        assertEquals(2, server.sequenceFetches().size)
        assertEquals(mapOf("$ACCOUNT/$INBOX" to (9L to null)), cache.numbering())
    }

    @Test fun `a server that announces CONDSTORE and refuses it on SELECT is walked from the plain SELECT`() {
        val folder = CondstoreFolder(5).apply { refuseAlways += "SELECT \"INBOX\" (CONDSTORE)" }
        val server = serving(folder)

        refresh(server)

        assertEquals(listOf("SELECT \"INBOX\" (CONDSTORE)", "SELECT \"INBOX\""), server.selects())
        assertEquals("the refusal cost a connection", 1, server.connections.get())
        assertEquals(1, server.sequenceFetches().size)
        assertEquals(5, ids().size)
        assertEquals(mapOf("$ACCOUNT/$INBOX" to (9L to null)), cache.numbering())
    }

    @Test fun `a folder the plain SELECT refuses too fails with the refusal of the plain SELECT`() {
        val folder = CondstoreFolder(5).apply { refuseAlways += "SELECT" }
        val server = serving(folder)

        val thrown = runCatching { refresh(server) }.exceptionOrNull()

        assertTrue(
            "the refusal that climbed out is not the plain SELECT's: $thrown",
            thrown is ImapException && thrown.message.orEmpty().startsWith("SELECT \"INBOX\" failed"),
        )
        assertEquals(emptyList<Map<String, String?>>(), cache.rows(ACCOUNT))
    }

    @Test fun `a caller that hands no delta gets the plain SELECT and no mark, even from a CONDSTORE server`() {
        val folder = CondstoreFolder(5)
        val server = serving(folder)

        val load = runBlocking {
            service.loadFolder(credentials(server, ACCOUNT), INBOX, 50, onPage = {}, cachedPreviewsFor = null)
        }

        assertEquals(listOf("SELECT \"INBOX\""), server.selects())
        assertNull(load.modSeq)
        assertEquals(listOf(105L, 104L, 103L, 102L, 101L), load.walk.uids)
    }

    // -- two accounts ------------------------------------------------------------------------------

    @Test fun `each account asks for the changes since its own mod-sequence, and a removed account walks again`() {
        val mine = CondstoreFolder(5)
        val theirs = CondstoreFolder(3).apply { highest = 500L }
        val myServer = serving(mine)
        val theirServer = serving(theirs)
        refresh(myServer)
        refresh(theirServer, account = OTHER)
        assertEquals(mapOf("$ACCOUNT/$INBOX" to (9L to 105L), "$OTHER/$INBOX" to (9L to 500L)), cache.numbering())
        myServer.commands.clear()
        theirServer.commands.clear()

        refresh(myServer)
        refresh(theirServer, account = OTHER)

        assertTrue(myServer.issued().toString(), "UID FETCH 101:* (UID FLAGS) (CHANGEDSINCE 105)" in myServer.issued())
        assertTrue(theirServer.issued().toString(), "UID FETCH 101:* (UID FLAGS) (CHANGEDSINCE 500)" in theirServer.issued())
        myServer.commands.clear()
        theirServer.commands.clear()

        runBlocking { cache.uidValidityDao.deleteForAccount(ACCOUNT) }
        refresh(myServer)
        refresh(theirServer, account = OTHER)

        assertEquals("the removed account's folder was read as a delta", 1, myServer.sequenceFetches().size)
        assertEquals(emptyList<String>(), theirServer.sequenceFetches())
    }

    private companion object {
        const val ACCOUNT = "acc-1"
        const val OTHER = "acc-2"
        const val INBOX = "INBOX"

        /** [MoveNeedsTheNumberingStatedNowTest]'s: nothing here refreshes a token. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            return allocate.invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

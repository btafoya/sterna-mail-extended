package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.CATCH_UP_OFF_WALK
import app.sterna.core.data.db.CATCH_UP_SILENT
import app.sterna.core.data.db.CatchUpMark
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailKeyRow
import app.sterna.core.data.db.RowNumbering
import app.sterna.core.data.db.upsertKeepingCatchUp
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapException
import app.sterna.core.imap.ImapUidValidityChanged
import app.sterna.core.imap.SmtpClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The folders of one IMAP account behind a [ScriptedImapServer], answering what the unread
 * catch-up sends: `SELECT`, `UID SEARCH UNSEEN` and a `UID FETCH` of envelopes by UID set. Every
 * message carries the same date, [RECEIVED].
 */
internal class ScriptedUnreadFolders {

    class Folder(@Volatile var uidValidity: Long) {
        val flags = TreeMap<Long, Set<String>>()

        /** UIDs the search names and no fetch answers for: expunged between the two commands. */
        val vanished: MutableSet<Long> = ConcurrentHashMap.newKeySet()

        fun unread(vararg uids: Long) = apply { synchronized(flags) { uids.forEach { flags[it] = emptySet() } } }

        fun read(vararg uids: Long) = apply { synchronized(flags) { uids.forEach { flags[it] = setOf("\\Seen") } } }
    }

    private val folders = ConcurrentHashMap<String, Folder>()

    /** Commands, by prefix, answered `NO` every time. */
    val refuse: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var selected: Folder? = null

    fun folder(path: String, uidValidity: Long = 9L): Folder = folders.getOrPut(path) { Folder(uidValidity) }

    /** Never silent: a responder that threw would leave the client waiting for ever. */
    fun respond(tag: String, line: String): String =
        try {
            answer(tag, line)
        } catch (failed: Throwable) {
            "$tag BAD the scripted folders failed on '$line': $failed\r\n"
        }

    private fun answer(tag: String, line: String): String {
        if (refuse.any { line.startsWith(it) }) return "$tag NO refused\r\n"
        val ok = "$tag OK done\r\n"
        return when {
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1\r\n$ok"
            line.startsWith("SELECT ") -> {
                val folder = folders[line.substringAfter('"').substringBeforeLast('"')]
                    ?: return "$tag NO no such mailbox\r\n"
                selected = folder
                val exists = synchronized(folder.flags) { folder.flags.size }
                "* $exists EXISTS\r\n* OK [UIDVALIDITY ${folder.uidValidity}] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            }
            line == "UID SEARCH UNSEEN" -> {
                val folder = selected ?: return "$tag BAD nothing selected\r\n"
                val unseen = synchronized(folder.flags) { folder.flags.filterValues { "\\Seen" !in it }.keys.toList() }
                "* SEARCH ${(unseen + folder.vanished).joinToString(" ")}\r\n$ok"
            }
            line.startsWith("UID FETCH ") && "ENVELOPE" in line -> {
                val folder = selected ?: return "$tag BAD nothing selected\r\n"
                val asked = line.removePrefix("UID FETCH ").substringBefore(' ').split(',').flatMap { part ->
                    val low = part.substringBefore(':').toLong()
                    (low..part.substringAfter(':').toLong()).toList()
                }.toSet()
                synchronized(folder.flags) {
                    val uids = folder.flags.keys.toList()
                    uids.filter { it in asked }.joinToString("") { envelope(uids.indexOf(it) + 1, it, folder.flags.getValue(it)) }
                } + ok
            }
            else -> ok
        }
    }

    private fun envelope(seq: Int, uid: Long, flags: Set<String>): String =
        "* $seq FETCH (UID $uid FLAGS (${flags.joinToString(" ")}) " +
            "INTERNALDATE \"01-Jun-2021 10:00:00 +0000\" " +
            "ENVELOPE (\"Tue, 1 Jun 2021 10:00:00 +0000\" \"Message $uid\" " +
            "((\"Dana\" NIL \"dana\" \"example.org\")) NIL NIL " +
            "((\"Alex\" NIL \"alex\" \"example.org\")) NIL NIL NIL \"<m$uid@example.org>\") " +
            "BODYSTRUCTURE (\"text\" \"plain\" (\"charset\" \"utf-8\") NIL NIL \"7bit\" 12 1))\r\n"

    companion object {
        val RECEIVED: Long = Instant.parse("2021-06-01T10:00:00Z").toEpochMilli()
    }
}

/**
 * The unread catch-up of an IMAP account, end to end: the shipped [ImapUnreadBackfill] over the
 * shipped `ImapMailService.onUnseenOf` on a socket, and the shipped [imapUnreadCache] over a DAO
 * whose eight calls run the shipped statements on SQLite ([JdbcMailCache]). What is read is what
 * went on the wire and what the table holds afterwards. `held` stands for the rows a gesture has
 * just written, `inFlight` for those a swipe is holding.
 *
 * What this does not cover:
 * - That Room turns `insertAllIgnoring` into `INSERT OR IGNORE`: [JdbcMailCache.insertIgnoring]
 *   states it, and `UnreadBackfillTest` reads the annotation as text.
 * - The marks `MailRepository` hands over as `heldLocally` and `sparingInFlight`. The last test
 *   pins those arguments as text, with the four holes of such a lint: a name says nothing of what
 *   it is bound to, nor of the expression around the call, nor of its place in a control
 *   structure, and nothing here holds what a screen collects.
 * - The list's mediator, which no JVM test runs: the count it reads is run here as a statement,
 *   and the lines that call it are pinned in `RepresentativeAnchorSqlTest`.
 */
class ImapUnreadBackfillTest {

    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    private val cache = JdbcMailCache()
    private val service = ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher(), cache.store())
    private val folders = ScriptedUnreadFolders()
    private val server = ScriptedImapServer(folders::respond)

    private val held = mutableSetOf<String>()
    private val inFlight = mutableSetOf<String>()
    private val inserted = mutableListOf<List<String>>()
    private val markedUnread = mutableListOf<List<String>>()
    private val markReads = mutableListOf<List<String>>()
    private val walkedWrites = mutableListOf<List<String>>()
    @Volatile private var configured = true
    @Volatile private var beforeInsert: () -> Unit = {}

    @After fun tearDown() {
        runBlocking { listOf(ACCOUNT, OTHER).forEach { runCatching { service.disconnect(it) } } }
        server.close()
        cache.close()
    }

    private fun id(uid: Long, mailbox: String = INBOX, account: String = ACCOUNT) = ImapMailService.emailId(account, mailbox, uid)

    private fun credentials(account: String) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = account,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    @Suppress("UNCHECKED_CAST")
    private fun dao(): EmailDao =
        Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, args ->
            val passed = args.orEmpty().dropLast(1)
            when (method.name) {
                "idsForMailbox" -> cache.idsForMailbox(passed[0] as String, passed[1] as String)
                "numberingOfRows" -> cache.numberingOfRows(passed[0] as String, passed[1] as List<String>)
                "unreadKeys" -> cache.unreadIds(passed[0] as String, passed[1] as String, passed[2] as Int)
                    .map { EmailKeyRow(passed[0] as String, it) }
                "catchUpOf" -> cache.catchUpOf(passed[0] as String, passed[1] as List<String>)
                "insertAllIgnoring" -> {
                    val rows = passed[0] as List<EmailEntity>
                    inserted += rows.map { it.id }
                    cache.insertIgnoring(rows)
                }
                "evictFromCacheKeepingIndex" -> cache.evict(passed[0] as String, passed[1] as List<String>)
                "readAmong" -> cache.readAmong(passed[0] as String, passed[1] as List<String>)
                "markUnread" -> {
                    markedUnread += passed[1] as List<String>
                    cache.markUnread(passed[0] as String, passed[1] as List<String>)
                }
                "toString" -> "the cache of ImapUnreadBackfillTest"
                else -> error("EmailDao.${method.name} was called by a pass that reads five things, inserts, evicts and marks unread.")
            }
        } as EmailDao

    private fun backfill(
        vararg scopes: Pair<String, String>,
        budget: Int = 10_000,
        now: Long = ScriptedUnreadFolders.RECEIVED + HOUR_MS,
        account: String = ACCOUNT,
    ) = runBlocking {
        ImapUnreadBackfill(
            accountId = account,
            cache = imapUnreadCache(
                emailDao = dao(),
                accountId = account,
                unreadCap = 10_000,
                stillConfigured = { if (!configured) throw AccountGoneException(account) },
                heldLocally = { it in held },
                sparingInFlight = { rows ->
                    beforeInsert()
                    rows.filterNot { it.id in inFlight }
                },
            ),
            onFolder = { mailboxId, block -> service.onUnseenOf(credentials(account), mailboxId, block) },
            budget = budget,
            now = { now },
        ).run(scopes.toList())
    }

    private fun cached(
        uid: Long,
        mailbox: String = INBOX,
        account: String = ACCOUNT,
        seen: Boolean = false,
        flagged: Boolean = false,
        catchUp: Int = 0,
        uidValidity: Long? = 9L,
        preview: String? = "As cached",
    ): Map<String, String?> {
        val row = EmailEntity(
            id = id(uid, mailbox, account), accountId = account, mailboxId = mailbox, threadId = null,
            subject = "Cached $uid", preview = preview, receivedAt = null, fromName = null, fromEmail = null,
            seen = seen, flagged = flagged, hasAttachment = false, sortKey = uid, uidValidity = uidValidity, catchUp = catchUp,
        )
        cache.upsert(listOf(row))
        return cache.row(account, row.id) ?: error("the row just written is not in the cache")
    }

    private fun row(uid: Long, mailbox: String = INBOX, account: String = ACCOUNT) = cache.row(account, id(uid, mailbox, account))

    private fun ids(account: String = ACCOUNT) = cache.rows(account).map { it["id"] }

    private fun envelopeFetches() = server.issued("UID FETCH ").map { it.removePrefix("UID FETCH ").substringBefore(' ') }

    // -- what is cached ------------------------------------------------------------------------------

    @Test fun `an unread message outside the window is cached off the walk, under the SELECT's numbering, with no opening line`() {
        folders.folder(INBOX).read(101L, 102L).unread(107L)
        cached(102L, seen = true)

        backfill(ACCOUNT to INBOX)

        val row = row(107L) ?: return fail("the unread message was not cached: ${ids()}")
        assertEquals(INBOX, row["mailboxId"])
        assertEquals("Message 107", row["subject"])
        assertEquals("0", row["seen"])
        assertEquals("the message is an hour old: off the walk, and not silenced", "$CATCH_UP_OFF_WALK", row["catchUp"])
        assertEquals("9", row["uidValidity"])
        assertNull(row["preview"])
        assertEquals(
            listOf("SELECT \"INBOX\"", "UID SEARCH UNSEEN", "UID FETCH 107 "),
            server.issued().filter { it.startsWith("SELECT") || it.startsWith("UID ") }
                .map { if (it.startsWith("UID FETCH")) it.substringBefore('(') else it },
        )
    }

    @Test fun `the absent messages are asked for newest first, as one compressed set, with no command judging movement`() {
        folders.folder(INBOX).unread(101L, 102L, 103L, 107L)

        backfill(ACCOUNT to INBOX)

        assertEquals(listOf("101:103,107"), envelopeFetches())
        assertEquals(listOf(listOf(id(107L), id(103L), id(102L), id(101L))), inserted.toList())
        assertEquals("a NOOP asks whether the folder moved, which this pass does not judge", emptyList<String>(), server.issued("NOOP"))
    }

    @Test fun `nothing is written to the server`() {
        folders.folder(INBOX).read(90L).unread(107L)
        cached(90L, catchUp = CATCH_UP_OFF_WALK)

        backfill(ACCOUNT to INBOX)

        assertEquals(
            emptyList<String>(),
            server.issued().filter { command -> listOf("STORE", "UID STORE", "EXPUNGE", "UID EXPUNGE", "APPEND", "UID MOVE", "UID COPY").any { command.startsWith(it) } },
        )
    }

    @Test fun `a caught-up message older than a day carries the silent bit, and no notification pass reads it`() {
        folders.folder(INBOX).unread(107L)
        cached(102L)

        backfill(ACCOUNT to INBOX, now = ScriptedUnreadFolders.RECEIVED + 48 * HOUR_MS)

        assertEquals("${CATCH_UP_OFF_WALK or CATCH_UP_SILENT}", row(107L)?.get("catchUp"))
        val candidates = cache.query(
            DaoQuerySource.emailDaoQuery("receivedSince"),
            mapOf("accountId" to ACCOUNT, "mailboxId" to INBOX, "since" to 0L, "limit" to 100),
        ) { it.getString("id") }
        assertEquals("the shipped read of a notification pass", listOf(id(102L)), candidates)
    }

    @Test fun `a row already cached is left whole, opening line and mark`() {
        folders.folder(INBOX).unread(104L, 105L, 107L)
        val walked = cached(105L, preview = "As cached", catchUp = 0)
        val caught = cached(104L, preview = "Read once", catchUp = CATCH_UP_OFF_WALK or CATCH_UP_SILENT)

        backfill(ACCOUNT to INBOX)

        assertEquals("the pass ran: its other message is cached", "Message 107", row(107L)?.get("subject"))
        assertEquals(walked, row(105L))
        assertEquals(caught, row(104L))
        assertEquals("a cached UID is not fetched again", listOf("107"), envelopeFetches())
    }

    @Test fun `a row the folder's own read writes during the pass wins over the one the pass fetched`() {
        folders.folder(INBOX).unread(107L)
        var written: Map<String, String?>? = null
        beforeInsert = { written = cached(107L, preview = "From the walk", catchUp = 0) }

        backfill(ACCOUNT to INBOX)

        assertEquals(listOf(listOf(id(107L))), inserted.toList())
        assertEquals("an upsert would have blanked the opening line and marked the row off the walk", written, row(107L))
    }

    // -- what is taken back out ----------------------------------------------------------------------

    @Test fun `a caught-up row the server no longer holds unread is taken out of the cache`() {
        folders.folder(INBOX).read(90L).unread(107L)
        cached(90L, catchUp = CATCH_UP_OFF_WALK)
        cached(91L, catchUp = CATCH_UP_OFF_WALK or CATCH_UP_SILENT)
        val stillUnread = cached(107L, catchUp = CATCH_UP_OFF_WALK)

        backfill(ACCOUNT to INBOX)

        assertNull("read on another device since: ${ids()}", row(90L))
        assertNull("gone from the folder since: ${ids()}", row(91L))
        assertEquals(stillUnread, row(107L))
    }

    @Test fun `a row of the folder's window is never taken out, though the server no longer names it unread`() {
        folders.folder(INBOX).read(95L, 96L).unread(107L)
        val walked = cached(95L, catchUp = 0)
        val silentOnly = cached(96L, catchUp = CATCH_UP_SILENT)
        val readHere = cached(97L, seen = true, catchUp = CATCH_UP_OFF_WALK)

        backfill(ACCOUNT to INBOX)

        assertEquals("the pass ran: its message is cached", "Message 107", row(107L)?.get("subject"))
        assertEquals(walked, row(95L))
        assertEquals(silentOnly, row(96L))
        assertEquals("a row read on this phone is not an unread row gone stale", readHere, row(97L))
    }

    @Test fun `a row a local gesture holds is neither taken out nor fetched back`() {
        folders.folder(INBOX).read(90L).unread(107L, 108L, 109L)
        val markedUnreadHere = cached(90L, catchUp = CATCH_UP_OFF_WALK)
        held += id(90L)
        held += id(107L)
        inFlight += id(108L)

        backfill(ACCOUNT to INBOX)

        assertEquals(markedUnreadHere, row(90L))
        assertEquals("a message deleted here a moment ago is not asked for", listOf("108:109"), envelopeFetches())
        assertEquals("a row a swipe is holding is fetched and not written", listOf(listOf(id(109L))), inserted.toList())
        assertEquals(listOf(id(109L), id(90L)), ids())
    }

    @Test fun `another folder's rows and another account's are left alone`() {
        folders.folder(INBOX).unread(107L)
        folders.folder("Archive").read(90L)
        val otherFolder = cached(90L, mailbox = "Archive", catchUp = CATCH_UP_OFF_WALK)
        val otherAccount = cached(90L, account = OTHER, catchUp = CATCH_UP_OFF_WALK)

        backfill(ACCOUNT to INBOX, OTHER to INBOX, OTHER to "Archive")

        assertEquals("the pass ran: its message is cached", "Message 107", row(107L)?.get("subject"))
        assertEquals(otherFolder, row(90L, mailbox = "Archive"))
        assertEquals(otherAccount, row(90L, account = OTHER))
        assertEquals(listOf(id(90L, account = OTHER)), ids(OTHER))
        assertEquals("the other account's half of the scope names none of this account's folders", listOf("SELECT \"INBOX\""), server.issued("SELECT"))
    }

    // -- what is marked unread again -----------------------------------------------------------------

    @Test fun `a row the cache holds read and the server unread is marked unread, on or off the walk, and nothing else of it moves`() {
        folders.folder(INBOX).unread(80L, 81L, 82L)
        val walked = cached(80L, seen = true, flagged = true, catchUp = CATCH_UP_SILENT, preview = "Read here")
        val caught = cached(81L, seen = true, catchUp = CATCH_UP_OFF_WALK, preview = null)
        val alreadyUnread = cached(82L, flagged = true)

        backfill(ACCOUNT to INBOX)

        assertEquals(walked + ("seen" to "0"), row(80L))
        assertEquals(caught + ("seen" to "0"), row(81L))
        assertEquals(alreadyUnread, row(82L))
        assertEquals("only the rows the cache held read are written", listOf(id(80L), id(81L)), markedUnread.flatten().sorted())
        assertEquals("a cached row is not fetched again", emptyList<String>(), envelopeFetches())
    }

    @Test fun `a row just read on this phone stays read, though the server still names it unread`() {
        folders.folder(INBOX).unread(80L, 81L)
        val readHere = cached(80L, seen = true)
        cached(81L, seen = true)
        held += id(80L)

        backfill(ACCOUNT to INBOX)

        assertEquals("the pass ran: the row no gesture holds is unread", "0", row(81L)?.get("seen"))
        assertEquals(readHere, row(80L))
        assertEquals(listOf(listOf(id(81L))), markedUnread.toList())
    }

    @Test fun `a row the cache holds read stays read when the server does not name it unread`() {
        folders.folder(INBOX).read(80L).unread(81L)
        folders.folder("Work").unread(80L)
        val readBoth = cached(80L, seen = true)
        val goneFromTheFolder = cached(79L, seen = true)
        val unreadInAnotherFolder = cached(80L, mailbox = "Work", seen = true)
        val unreadInAnotherAccount = cached(81L, account = OTHER, seen = true)
        cached(81L, seen = true)

        backfill(ACCOUNT to INBOX)

        assertEquals("the pass ran: the row the server names is unread", "0", row(81L)?.get("seen"))
        assertEquals(readBoth, row(80L))
        assertEquals(goneFromTheFolder, row(79L))
        assertEquals("a folder outside the scope is not judged", unreadInAnotherFolder, row(80L, mailbox = "Work"))
        assertEquals(unreadInAnotherAccount, row(81L, account = OTHER))
        assertEquals(listOf(listOf(id(81L))), markedUnread.toList())
    }

    @Test fun `a read row of an older numbering is taken out and never marked unread`() {
        folders.folder(INBOX, uidValidity = 12L).unread(80L, 81L)
        runBlocking { cache.store().record(ACCOUNT, INBOX, 12L) }
        cached(80L, seen = true, flagged = true, uidValidity = 9L, preview = "Another message")
        cached(81L, seen = true, uidValidity = 12L)

        backfill(ACCOUNT to INBOX)

        assertEquals("the write is of the row of this numbering alone", listOf(listOf(id(81L))), markedUnread.toList())
        val row = row(80L) ?: return fail("the message that took the UID is not cached: ${ids()}")
        assertEquals("the row of the older numbering was kept and marked unread", "Message 80", row["subject"])
        assertEquals("0", row["flagged"])
        assertNull(row["preview"])
        assertEquals(listOf("80"), envelopeFetches())
    }

    @Test fun `an account signed out before the pass has no row marked unread`() {
        folders.folder(INBOX).unread(80L)
        val readHere = cached(80L, seen = true)
        configured = false

        try {
            backfill(ACCOUNT to INBOX)
            fail("the pass wrote for an account that is no longer configured")
        } catch (gone: AccountGoneException) {
            assertTrue("thrown: ${gone.message}", gone.message.orEmpty().contains(ACCOUNT))
        }

        assertEquals(readHere, row(80L))
        assertEquals(emptyList<List<String>>(), markedUnread.toList())
    }

    // -- renumbering ---------------------------------------------------------------------------------

    @Test fun `a row read under an older numbering gives its id back to the message that now holds it`() {
        folders.folder(INBOX, uidValidity = 12L).unread(105L, 106L, 107L)
        runBlocking { cache.store().record(ACCOUNT, INBOX, 12L) }
        cached(107L, seen = true, uidValidity = 9L)
        val unstamped = cached(105L, uidValidity = null)
        val heldRow = cached(106L, uidValidity = 9L)
        held += id(106L)

        backfill(ACCOUNT to INBOX)

        val row = row(107L) ?: return fail("the message that took the UID is not cached: ${ids()}")
        assertEquals("the conflict-ignoring insert kept the row of the older numbering", "Message 107", row["subject"])
        assertEquals("12", row["uidValidity"])
        assertEquals("0", row["seen"])
        assertEquals("$CATCH_UP_OFF_WALK", row["catchUp"])
        assertEquals("a row with no numbering has nothing to oppose", unstamped, row(105L))
        assertEquals(heldRow, row(106L))
        assertEquals(listOf("107"), envelopeFetches())
    }

    @Test fun `a numbering that changed under the pass is thrown and not retried, the folder before it kept`() {
        folders.folder(INBOX).unread(101L)
        folders.folder("Work", uidValidity = 12L).unread(201L)
        folders.folder("Archive").unread(301L)
        runBlocking { cache.store().record(ACCOUNT, "Work", 9L) }

        try {
            backfill(ACCOUNT to INBOX, ACCOUNT to "Work", ACCOUNT to "Archive")
            fail("the changed numbering came back as a pass with nothing to add")
        } catch (renumbered: ImapUidValidityChanged) {
            assertEquals(12L, renumbered.observed)
        }

        assertEquals(listOf(id(101L)), ids())
        assertEquals(listOf("SELECT \"INBOX\"", "SELECT \"Work\""), server.issued("SELECT"))
    }

    // -- failure, budget, sessions -------------------------------------------------------------------

    @Test fun `a folder the server refuses ends the pass, with the folder before it written and none after`() {
        folders.folder(INBOX).unread(101L)
        folders.folder("Work").unread(201L)
        folders.folder("Archive").unread(301L)
        folders.refuse += "SELECT \"Work\""

        try {
            backfill(ACCOUNT to INBOX, ACCOUNT to "Work", ACCOUNT to "Archive")
            fail("the refused folder came back as a pass with nothing to add")
        } catch (refusal: ImapException) {
            assertEquals("thrown: ${refusal.message}", "NO", refusal.taggedStatus)
        }

        assertEquals(listOf(id(101L)), ids())
        assertEquals("the folder after the refusal is never opened", emptyList<String>(), server.issued("SELECT \"Archive\""))
    }

    @Test fun `the budget is the account's, spent by the rows inserted, in the order of the scope`() {
        folders.folder(INBOX).unread(101L, 102L)
        folders.folder("Work").unread(201L, 202L)
        folders.folder("Archive").unread(301L)

        backfill(ACCOUNT to INBOX, ACCOUNT to "Work", ACCOUNT to "Archive", budget = 3)

        assertEquals(
            listOf(listOf(id(102L), id(101L)), listOf(id(202L, "Work"))),
            inserted.toList(),
        )
        assertEquals("a spent budget opens no further folder", listOf("SELECT \"INBOX\"", "SELECT \"Work\""), server.issued("SELECT"))
    }

    @Test fun `an unread UID that never answers is not a failure and costs nothing`() {
        folders.folder(INBOX).unread(101L).vanished += 150L
        folders.folder("Work").unread(201L, 202L)

        backfill(ACCOUNT to INBOX, ACCOUNT to "Work", budget = 2)

        assertEquals(listOf("101,150", "202"), envelopeFetches())
        assertEquals(listOf(id(101L), id(202L, "Work")), ids())
    }

    @Test fun `an account signed out during the pass stops writing`() {
        folders.folder(INBOX).unread(107L)
        beforeInsert = { configured = false }

        try {
            backfill(ACCOUNT to INBOX)
            fail("the pass wrote for an account that is no longer configured")
        } catch (gone: AccountGoneException) {
            assertTrue("thrown: ${gone.message}", gone.message.orEmpty().contains(ACCOUNT))
        }

        assertEquals(emptyList<String?>(), ids())
        assertEquals(emptyList<List<String>>(), inserted.toList())
    }

    @Test fun `an account signed out before the pass takes nothing out of the cache`() {
        folders.folder(INBOX).read(90L)
        val stale = cached(90L, catchUp = CATCH_UP_OFF_WALK)
        configured = false

        try {
            backfill(ACCOUNT to INBOX)
            fail("the pass evicted for an account that is no longer configured")
        } catch (gone: AccountGoneException) {
            assertTrue("thrown: ${gone.message}", gone.message.orEmpty().contains(ACCOUNT))
        }

        assertEquals(stale, row(90L))
    }

    @Test fun `each folder is read in a session block of its own, in the order of the scope`() {
        val events = mutableListOf<String>()
        fun row(mailboxId: String) = EmailEntity(
            id = id(7L, mailboxId), accountId = ACCOUNT, mailboxId = mailboxId, threadId = null, subject = null, preview = null,
            receivedAt = null, fromName = null, fromEmail = null, seen = false, flagged = false, hasAttachment = false, sortKey = 7L,
        )
        val recording = ImapUnreadCache(
            idsForMailbox = { emptyList() },
            numberingOf = { emptyList() },
            unreadIds = { emptyList() },
            marksOf = { emptyList() },
            readAmong = { emptyList() },
            stillConfigured = {},
            heldLocally = { false },
            sparingInFlight = { it },
            insertAllIgnoring = { rows -> events += rows.map { "insert ${it.mailboxId}" } },
            evict = {},
            markUnread = {},
        )

        runBlocking {
            ImapUnreadBackfill(
                accountId = ACCOUNT,
                cache = recording,
                onFolder = { mailboxId, block ->
                    events += "open $mailboxId"
                    block(ImapUnseenFolder(uidValidity = 9L, unseen = setOf(7L), fetch = { _, onPage -> onPage(listOf(row(mailboxId))) }))
                    events += "close $mailboxId"
                },
                budget = 10,
                now = { 0L },
            ).run(listOf(ACCOUNT to "Work", OTHER to "Elsewhere", ACCOUNT to INBOX, ACCOUNT to "Work"))
        }

        assertEquals(
            listOf("open Work", "insert Work", "close Work", "open INBOX", "insert INBOX", "close INBOX"),
            events,
        )
    }

    // -- the list's older page -----------------------------------------------------------------------

    @Test fun `the older page counts from what the list walked, and a page that writes a caught-up row makes it ordinary`() {
        folders.folder(INBOX).read(101L, 102L).unread(90L)
        cached(101L, seen = true)
        cached(102L, seen = true)
        backfill(ACCOUNT to INBOX, now = ScriptedUnreadFolders.RECEIVED + 48 * HOUR_MS)
        assertEquals("${CATCH_UP_OFF_WALK or CATCH_UP_SILENT}", row(90L)?.get("catchUp"))
        assertEquals("the shipped count of the older page's offset", 2, cache.walkedCount(ACCOUNT, INBOX))

        val writes = folderWrites()
        val asAPageReadsIt = EmailEntity(
            id = id(90L), accountId = ACCOUNT, mailboxId = INBOX, threadId = null, subject = "Message 90", preview = "Opening of 90",
            receivedAt = null, fromName = null, fromEmail = null, seen = false, flagged = false, hasAttachment = false,
            sortKey = ScriptedUnreadFolders.RECEIVED, uidValidity = 9L,
        )
        runBlocking { writes.writePage(listOf(asAPageReadsIt)) }

        assertEquals("the page cleared the walk bit and kept the silent one", "$CATCH_UP_SILENT", row(90L)?.get("catchUp"))
        assertEquals("Opening of 90", row(90L)?.get("preview"))
        assertEquals(3, cache.walkedCount(ACCOUNT, INBOX))
    }

    // -- the window a read vouches for ---------------------------------------------------------------

    @Test fun `a kept row still off the walk is taken off it, and one the read did not keep stays as it is`() {
        cached(90L, catchUp = CATCH_UP_OFF_WALK or CATCH_UP_SILENT)
        val notKept = cached(91L, catchUp = CATCH_UP_OFF_WALK)
        val ordinary = cached(92L, catchUp = 0)
        assertEquals(1, cache.walkedCount(ACCOUNT, INBOX))

        runBlocking { folderWrites().markKeptWalked(INBOX, setOf(id(90L), id(92L))) }

        assertEquals("the walk bit is cleared and the silent one kept", "$CATCH_UP_SILENT", row(90L)?.get("catchUp"))
        assertEquals(notKept, row(91L))
        assertEquals(ordinary, row(92L))
        assertEquals("only the marked row is written", listOf(listOf(id(90L))), walkedWrites.toList())
        assertEquals("the shipped count of the older page's offset", 2, cache.walkedCount(ACCOUNT, INBOX))
    }

    @Test fun `nothing is written when no kept row is off the walk`() {
        cached(92L, catchUp = 0)
        cached(93L, catchUp = CATCH_UP_SILENT)

        runBlocking { folderWrites().markKeptWalked(INBOX, setOf(id(92L), id(93L), id(999L))) }

        assertEquals("the marks were read", 1, markReads.size)
        assertEquals("an ordinary refresh wrote to the cache", emptyList<List<String>>(), walkedWrites.toList())
    }

    @Test fun `a read that cannot vouch for its window takes nothing off the catch-up`() {
        val caught = cached(90L, catchUp = CATCH_UP_OFF_WALK)
        val writes = folderWrites()

        runBlocking {
            fullQueryWriteThrough<List<EmailEntity>, String>(
                folderIds = { null },
                walk = { "a walk that moved" },
                writePage = {},
                keepIds = { null },
                spareIds = { emptyList() },
                reconcile = { _, keepIds, _, _ -> writes.markKeptWalked(INBOX, keepIds) },
            )
        }

        assertEquals(caught, row(90L))
        assertEquals(emptyList<List<String>>(), markReads.toList())
        assertEquals(emptyList<List<String>>(), walkedWrites.toList())
    }

    @Test fun `only a mark carrying the walk bit is off the walk`() {
        val marks = listOf(
            CatchUpMark("walked", 0),
            CatchUpMark("silent-only", CATCH_UP_SILENT),
            CatchUpMark("caught", CATCH_UP_OFF_WALK),
            CatchUpMark("caught-silent", CATCH_UP_OFF_WALK or CATCH_UP_SILENT),
        )

        assertEquals(listOf("caught", "caught-silent"), offWalkAmong(marks))
    }

    private fun folderWrites() = ImapFolderWrites(
        wantsPreviews = false,
        idsForMailbox = { cache.idsForMailbox(ACCOUNT, it) },
        idsWithPreviewForMailbox = { cache.idsWithPreviewForMailbox(ACCOUNT, it) },
        stillConfigured = {},
        heldLocally = { emptyList() },
        setReadAndStar = { _, _, _ -> },
        sparingInFlight = { it },
        upsertAll = { rows -> upsertKeepingCatchUp(rows, { accountId, ids -> cache.catchUpOf(accountId, ids) }, { cache.upsert(it) }) },
        markWalked = { mailboxId, ids ->
            walkedWrites += ids
            cache.markWalked(ACCOUNT, mailboxId, ids)
        },
        marksOf = { ids ->
            markReads += ids
            cache.catchUpOf(ACCOUNT, ids)
        },
    )

    // -- the decisions, alone ------------------------------------------------------------------------

    @Test fun `only a row marked off the walk is stale, and never one held locally`() {
        val marks = listOf(
            CatchUpMark("walked", 0),
            CatchUpMark("silent-only", CATCH_UP_SILENT),
            CatchUpMark("caught", CATCH_UP_OFF_WALK),
            CatchUpMark("caught-silent", CATCH_UP_OFF_WALK or CATCH_UP_SILENT),
            CatchUpMark("caught-held", CATCH_UP_OFF_WALK),
        )

        assertEquals(listOf("caught", "caught-silent"), staleCatchUps(marks) { it == "caught-held" })
    }

    @Test fun `a row read in the cache is unread again unless it is renumbered or held locally`() {
        assertEquals(
            listOf("read", "also-read"),
            unreadAgain(listOf("read", "renumbered", "held", "also-read"), renumbered = listOf("renumbered")) { it == "held" },
        )
        assertEquals(emptyList<String>(), unreadAgain(emptyList(), renumbered = listOf("renumbered")) { false })
    }

    @Test fun `a cached unread row is no longer unread when the server does not name its UID`() {
        assertEquals(
            listOf(id(5L)),
            noLongerUnread(listOf(id(5L), id(7L), "local-draft:1"), unseen = setOf(7L)),
        )
        assertEquals("an empty answer names every cached unread row", listOf(id(5L), id(7L)), noLongerUnread(listOf(id(5L), id(7L)), emptySet()))
    }

    @Test fun `the absent UIDs are the uncached ones, newest first, within the budget and less those held`() {
        val unseen = setOf(3L, 9L, 5L, 7L)

        assertEquals(listOf(9L, 5L, 3L), absentUnread(unseen, setOf(id(7L)), ACCOUNT, INBOX, budget = 10) { false })
        assertEquals(listOf(9L, 5L), absentUnread(unseen, setOf(id(7L)), ACCOUNT, INBOX, budget = 2) { false })
        assertEquals(listOf(5L, 3L), absentUnread(unseen, setOf(id(7L)), ACCOUNT, INBOX, budget = 10) { it == id(9L) })
        assertEquals(emptyList<Long>(), absentUnread(unseen, emptySet(), ACCOUNT, INBOX, budget = 0) { false })
        assertEquals(emptyList<Long>(), absentUnread(unseen, emptySet(), ACCOUNT, INBOX, budget = -4) { false })
        assertEquals(
            "a row cached under the same UID in another folder is not this folder's",
            listOf(7L),
            absentUnread(setOf(7L), setOf(id(7L, "Work")), ACCOUNT, INBOX, budget = 10) { false },
        )
    }

    @Test fun `a row is renumbered only when both numberings are stated and differ`() {
        val numbering = listOf(RowNumbering("same", 12L), RowNumbering("older", 9L), RowNumbering("unstamped", null))

        assertEquals(listOf("older"), renumberedRows(numbering, statedUidValidity = 12L))
        assertEquals("a SELECT that stated no numbering names nothing", emptyList<String>(), renumberedRows(numbering, statedUidValidity = 0L))
    }

    // -- the plug ------------------------------------------------------------------------------------

    /**
     * Source lint, as a last resort beside `UnreadBackfillTest`, which runs this function over a
     * socket: the marks behind `heldLocally` and `sparingInFlight` cannot be set from a test.
     */
    @Test fun `the repository hands the pass its own guards, one session block per folder, with these arguments`() {
        assertEquals(
            "MailRepository.backfillUnreadImap is no longer, line for line, what this was written against.",
            """
            {
            val stillConfigured: suspend () -> Unit = {
            checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id })
            }
            stillConfigured()
            val cache = imapUnreadCache(
            emailDao = emailDao,
            accountId = credentials.id,
            unreadCap = UNREAD_RESOLVE_MAX,
            stillConfigured = stillConfigured,
            heldLocally = { id -> isRecentlyMutated(credentials.id, id) || inFlightRemovals.containsKey(flightKey(credentials.id, id)) },
            sparingInFlight = ::sparingInFlight,
            )
            ImapUnreadBackfill(
            accountId = credentials.id,
            cache = cache,
            onFolder = { mailboxId, block -> imap.onUnseenOf(credentials, mailboxId, block) },
            budget = UNREAD_RESOLVE_MAX,
            now = System::currentTimeMillis,
            ).run(scopes)
            }
            """.trimIndent().lines().map { it.trim() }.filter { it.isNotEmpty() },
            DaoQuerySource.mailFunctionBody("MailRepository", "backfillUnreadImap").lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("//") },
        )
        val entry = DaoQuerySource.mailFunctionBody("MailRepository", "backfillUnread").lines().map { it.trim() }
        assertEquals(
            "the IMAP account no longer leaves through the catch-up of its own protocol, first thing",
            "if (credentials.protocol == MailProtocol.IMAP) return backfillUnreadImap(credentials, scopes)",
            entry.filter { it.isNotEmpty() }[1],
        )
        val mediator = DaoQuerySource.mailFunctionBody("MailRepository", "folderMediator").lines().map { it.trim() }
        assertTrue(
            "the IMAP older page is no longer written through the page write that takes its rows off the catch-up",
            "if (entities.isNotEmpty()) imapFolderWrites(credentials.id).writePage(entities)" in mediator,
        )
    }

    private companion object {
        const val ACCOUNT = "acc-1"
        const val OTHER = "acc-2"
        const val INBOX = "INBOX"
        const val HOUR_MS = 60L * 60 * 1000

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

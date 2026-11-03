package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.DOWNLOAD_AHEAD_SKIPS_CREATE_SQL
import app.sterna.core.data.db.DOWNLOAD_AHEAD_SKIPS_PRUNE_SQL
import app.sterna.core.data.db.DOWNLOAD_AHEAD_SKIP_SQL
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailBodyEntity
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.settings.DownloadAheadPeriod
import app.sterna.core.data.storage.AttachmentStore
import app.sterna.core.data.storage.storedPartKeyOf
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.JmapException
import app.sterna.core.imap.ImapException
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.MimeParser
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The download ahead (#157), run through `MailRepository.downloadInboxesAhead` against a scripted
 * IMAP server and a JMAP `MockWebServer`, over in-memory SQLite answering with the shipped
 * statements: which messages are read, on which connection, with which commands, and what lands
 * in the body cache.
 */
class DownloadAheadTest {

    private val cache = JdbcMailCache().also {
        it.update(BODIES_CREATE)
        it.update(DOWNLOAD_AHEAD_SKIPS_CREATE_SQL)
    }
    private val rows = Collections.synchronizedList(mutableListOf<EmailEntity>())
    private val daoCalls = Collections.synchronizedList(mutableListOf<String>())
    private val unexpectedBodyCalls = Collections.synchronizedList(mutableListOf<String>())
    private var jmap: MockWebServer? = null

    /** What the inbox lookup answers for the IMAP account, a folder whose role is not inbox in one test. */
    @Volatile private var inboxLookup = INBOX

    /** The UIDVALIDITY the scripted server's SELECT states. */
    @Volatile private var serverNumbering = 9L

    @Volatile private var jmapAccountRemoved = false

    /** Whether the scripted IMAP server answers the PDF's section with a tagged NO. */
    @Volatile private var refusePart = false

    /** The response code of that NO, none for a refusal of the part itself. */
    @Volatile private var refusalCode: String? = null

    /** The HTTP status of a JMAP blob download, by blob id; absent is 200 with the PDF. */
    private val blobStatus = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Blob ids whose download answers 200 with no byte. */
    private val emptyBlobs = Collections.synchronizedSet(mutableSetOf<String>())

    /** Run when the scripted server serves the PDF's section, before it answers. */
    @Volatile private var onPartServed: () -> Unit = {}

    @get:Rule val temp = TemporaryFolder()

    private val partStore by lazy { AttachmentStore(temp.newFolder("attachment-store")) }

    @After fun close() {
        cache.close()
        jmap?.shutdown()
    }

    // ---- 1. the decisions, executed ------------------------------------------------------------

    @Test fun `the cutoff is the period before now, and no setting or a switched-off one gives none`() {
        val now = 1_000_000_000_000L
        assertEquals(now - 7 * DAY, downloadAheadCutoff(DownloadAheadSetting(true, DownloadAheadPeriod.SEVEN_DAYS), now))
        assertEquals(now - 180 * DAY, downloadAheadCutoff(DownloadAheadSetting(true, DownloadAheadPeriod.SIX_MONTHS), now))
        assertNull(downloadAheadCutoff(DownloadAheadSetting(false, DownloadAheadPeriod.SEVEN_DAYS), now))
        assertNull(downloadAheadCutoff(null, now))
    }

    @Test fun `a read is refused its write only when another numbering is now recorded`() {
        assertEquals(false, renumberedSince(readUnder = 9L, recordedNow = 9L))
        assertEquals(false, renumberedSince(readUnder = 9L, recordedNow = null))
        assertEquals(true, renumberedSince(readUnder = 9L, recordedNow = 10L))
        assertEquals(true, renumberedSince(readUnder = null, recordedNow = 10L))
    }

    // ---- 2. IMAP ---------------------------------------------------------------------------------

    @Test(timeout = 30_000)
    fun `an IMAP message of the period is cached by peeking at its text, an older one is not read`() {
        file(row(RECENT, ageDays = 1), row(OLD, ageDays = 30))
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(), ON).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertEquals(
                "the period's message, header and structure then its one text section; nothing of the " +
                    "older one, and never the attachment (section 2) or the whole source",
                listOf("UID FETCH $RECENT (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH $RECENT (BODY.PEEK[1])"),
                server.issued("UID FETCH"),
            )
            assertEquals("no flag written, so nothing marked read", emptyList<String>(), server.issued().filter { "STORE" in it })
            assertEquals(listOf(idOf(RECENT)), cachedIds())
            val cached = decodeCachedEmail(cachedBody(idOf(RECENT)))
            assertEquals("I will be there at six\r\n", cached.textContent())
            assertEquals("the attachment is listed, not downloaded", listOf("a.pdf"), cached.attachments.map { it.name })
        }
    }

    @Test(timeout = 30_000)
    fun `a folder whose cached role is not the inbox is not downloaded ahead, whoever names it`() {
        file(row(RECENT, ageDays = 1).copy(id = ImapMailService.emailId(ACCOUNT, "Junk", RECENT), mailboxId = "Junk"))
        inboxLookup = "Junk"
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(), ON).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertEquals(emptyList<String>(), server.issued("UID FETCH"))
            assertEquals(emptyList<String>(), daoCalls.filter { it == "idsToDownloadAhead" })
        }
    }

    @Test(timeout = 30_000)
    fun `with the setting off, an IMAP pass reads nothing`() {
        file(row(RECENT, ageDays = 1))
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(), OFF).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertEquals(emptyList<String>(), server.issued("UID FETCH"))
            assertEquals(emptyList<String>(), cachedIds())
        }
    }

    @Test(timeout = 30_000)
    fun `with no setting at all, nothing is read either`() {
        file(row(RECENT, ageDays = 1))
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(), null).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertEquals(emptyList<String>(), server.issued("UID FETCH"))
            assertEquals(emptyList<String>(), daoCalls.filter { it == "idsToDownloadAhead" })
        }
    }

    @Test(timeout = 30_000)
    fun `a text too large is refused off its structure, and the next pass does not ask again`() {
        file(row(TOO_LARGE, ageDays = 1))
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            val repository = repository(service(), ON)
            runBlocking {
                repository.downloadInboxesAhead(listOf(imapCredentials(server)))
                repository.downloadInboxesAhead(listOf(imapCredentials(server)))
            }

            assertEquals(
                "one structure read over two passes, and never its text",
                listOf("UID FETCH $TOO_LARGE (BODYSTRUCTURE BODY.PEEK[HEADER])"),
                server.issued("UID FETCH"),
            )
            assertEquals(emptyList<String>(), cachedIds())
        }
    }

    @Test(timeout = 30_000)
    fun `a message opened while a pass reads is read on its own connection, not behind the pass`() {
        file(row(RECENT, ageDays = 1))
        val passReading = CountDownLatch(1)
        val passHeld = CountDownLatch(1)
        ScriptedImapServer.perConnection { connection, tag, line ->
            if (connection == 1 && line == "UID FETCH $RECENT (BODY.PEEK[1])") {
                passReading.countDown()
                passHeld.await(20, TimeUnit.SECONDS)
            }
            served(tag, line)
        }.use { server ->
            val service = service()
            val credentials = imapCredentials(server)
            try {
                val pass = onItsOwnThread { repository(service, ON).downloadInboxesAhead(listOf(credentials)) }
                assertTrue("precondition: the pass reached the text. Commands: ${server.byConnection.toList()}", passReading.await(10, TimeUnit.SECONDS))

                val open = onItsOwnThread { service.fetchForDisplay(credentials, INBOX, RECENT, ImapLane.FOREGROUND) }

                assertTrue(
                    "the open queued behind the download ahead, which must leave the foreground " +
                        "connection to it. Commands by connection: ${server.byConnection.toList()}",
                    open.done.await(5, TimeUnit.SECONDS),
                )
                assertEquals("the open's own read came back, got ${open.thrown}", null, open.thrown)
                passHeld.countDown()
                assertTrue(pass.done.await(10, TimeUnit.SECONDS))
            } finally {
                passHeld.countDown()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a second pass on the same account while one runs returns at once and reads nothing twice`() {
        file(row(RECENT, ageDays = 1))
        val passReading = CountDownLatch(1)
        val passHeld = CountDownLatch(1)
        ScriptedImapServer.perConnection { _, tag, line ->
            if (line == "UID FETCH $RECENT (BODY.PEEK[1])" && passReading.count > 0) {
                passReading.countDown()
                passHeld.await(20, TimeUnit.SECONDS)
            }
            served(tag, line)
        }.use { server ->
            val repository = repository(service(), ON)
            val credentials = imapCredentials(server)
            try {
                val first = onItsOwnThread { repository.downloadInboxesAhead(listOf(credentials)) }
                assertTrue("precondition: the first pass reached the text", passReading.await(10, TimeUnit.SECONDS))

                val second = onItsOwnThread { repository.downloadInboxesAhead(listOf(credentials)) }

                assertTrue("the second pass must not wait for the first", second.done.await(5, TimeUnit.SECONDS))
                passHeld.countDown()
                assertTrue(first.done.await(10, TimeUnit.SECONDS))
                assertEquals(
                    "each read once. Commands: ${server.byConnection.toList()}",
                    listOf("UID FETCH $RECENT (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH $RECENT (BODY.PEEK[1])"),
                    server.issued("UID FETCH"),
                )
                assertEquals(listOf(idOf(RECENT)), cachedIds())
            } finally {
                passHeld.countDown()
            }
        }
    }

    @Test(timeout = 30_000)
    fun `a row of the folder's previous numbering is never asked for, and purges nothing`() {
        serverNumbering = 10L
        file(row(STALE, ageDays = 0), row(RECENT, ageDays = 1).copy(uidValidity = 10L))
        val numbering = RecordedNumbering(10L)
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(numbering), ON).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertEquals(
                "only the message of the recorded numbering: the other row's SELECT would be refused, " +
                    "and the refusal purges the folder's bodies, index and notifications",
                listOf("UID FETCH $RECENT (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH $RECENT (BODY.PEEK[1])"),
                server.issued("UID FETCH"),
            )
            assertEquals("no renumbering invalidated", emptyList<String>(), numbering.invalidated)
            assertEquals(listOf(idOf(RECENT)), cachedIds())
        }
    }

    @Test(timeout = 30_000)
    fun `a signed message is skipped off its structure, its whole source never read nor its body cached`() {
        file(row(SIGNED, ageDays = 1))
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            val repository = repository(service(), ON)
            runBlocking {
                repository.downloadInboxesAhead(listOf(imapCredentials(server)))
                repository.downloadInboxesAhead(listOf(imapCredentials(server)))
            }

            assertEquals(
                "one structure read over two passes, never `BODY.PEEK[]`, which carries the attachments",
                listOf("UID FETCH $SIGNED (BODYSTRUCTURE BODY.PEEK[HEADER])"),
                server.issued("UID FETCH"),
            )
            assertEquals(emptyList<String>(), cachedIds())
            assertEquals(listOf(idOf(SIGNED)), skippedIds())
        }
    }

    // ---- 3. JMAP ---------------------------------------------------------------------------------

    @Test fun `a JMAP message of the period is cached with its body and its origin read`() {
        file(jmapRow("m1", ageDays = 1), jmapRow("m0", ageDays = 30))
        val credentials = startJmap()

        runBlocking { repository(service(), ON).downloadInboxesAhead(listOf(credentials)) }

        assertEquals(listOf("m1"), cachedIds())
        assertEquals("Hello from m1", decodeCachedEmail(cachedBody("m1")).textContent())
        assertEquals("the ids asked for, the older one never", listOf(listOf("m1"), listOf("m1")), jmapIdsAsked)
    }

    @Test fun `a period of more messages than one pass reads is cached whole over two passes, none dropped`() {
        val ids = (0 until 150).map { "m%03d".format(it) }
        file(*ids.map { jmapRow(it, ageDays = 1) }.toTypedArray())
        val credentials = startJmap()
        val repository = repository(service(), ON)

        runBlocking {
            repository.downloadInboxesAhead(listOf(credentials))
            repository.downloadInboxesAhead(listOf(credentials))
        }

        assertEquals("nothing but the writes reached the body cache", emptyList<String>(), unexpectedBodyCalls)
        assertEquals("no cap on the body cache: what a pass wrote is still there after the next", 150, cachedIds().size)
    }

    @Test fun `a JMAP account removed during the body read has no origin read, so no key imported`() {
        file(jmapRow("m1", ageDays = 1))
        val credentials = startJmap()
        jmapAccountRemoved = true

        runBlocking { repository(service(), ON).downloadInboxesAhead(listOf(credentials)) }

        assertEquals("the body read alone, not the headers read that imports the keys", listOf(listOf("m1")), jmapIdsAsked)
        assertEquals(emptyList<String>(), cachedIds())
    }

    @Test fun `with the setting off, a JMAP pass neither queries the cache nor calls the server`() {
        file(jmapRow("m1", ageDays = 1))
        val credentials = startJmap()

        runBlocking { repository(service(), OFF).downloadInboxesAhead(listOf(credentials)) }

        assertEquals(emptyList<String>(), daoCalls.filter { it == "idsToDownloadAhead" })
        assertEquals(0, jmap!!.requestCount)
        assertEquals(emptyList<String>(), cachedIds())
    }

    // ---- 4. attachments ahead (#157) -------------------------------------------------------------

    @Test fun `attachments are taken only with the download ahead on and the attachments asked for`() {
        assertTrue(downloadAheadTakesAttachments(WITH_PARTS))
        assertFalse(downloadAheadTakesAttachments(ON))
        assertFalse(downloadAheadTakesAttachments(WITH_PARTS.copy(enabled = false)))
        assertFalse(downloadAheadTakesAttachments(null))
        assertFalse("off unless asked for", DownloadAheadSetting(true, DownloadAheadPeriod.SEVEN_DAYS).attachments)
    }

    @Test fun `a part refused for good is given up, a sign-in, a busy server or a dropped connection are not`() {
        assertTrue(attachmentAheadGivesUp(ContentTooLargeException("big")))
        assertTrue(attachmentAheadGivesUp(ImapPartRefused("2", ImapException("no", taggedStatus = "NO"))))
        assertFalse("a refused LOGIN or SELECT is not the part's", attachmentAheadGivesUp(ImapException("no", taggedStatus = "NO")))
        assertFalse(attachmentAheadGivesUp(ImapException("dropped")))
        assertTrue(attachmentAheadGivesUp(JmapException("gone", httpCode = 404)))
        assertTrue(attachmentAheadGivesUp(JmapException("bad", httpCode = 400)))
        for (passing in listOf(401, 403, 408, 429, 500, 503)) {
            assertFalse("HTTP $passing", attachmentAheadGivesUp(JmapException("passing", httpCode = passing)))
        }
        assertFalse(attachmentAheadGivesUp(JmapException("no status")))
        assertFalse(attachmentAheadGivesUp(java.net.SocketTimeoutException()))
    }

    @Test fun `a refusal of the part's own fetch is final unless its code names a passing state`() {
        assertTrue(partFetchRefusedForGood(ImapException("no", taggedStatus = "NO")))
        assertTrue(partFetchRefusedForGood(ImapException("bad", taggedStatus = "BAD")))
        assertTrue(partFetchRefusedForGood(ImapException("gone", responseCode = "NONEXISTENT", taggedStatus = "NO")))
        for (code in listOf("UNAVAILABLE", "LIMIT", "ALERT", "INUSE", "SERVERBUG", "CONTACTADMIN", "OVERQUOTA")) {
            assertFalse(code, partFetchRefusedForGood(ImapException("busy", responseCode = code, taggedStatus = "NO")))
        }
        assertFalse("no tagged status is a connection that dropped", partFetchRefusedForGood(ImapException("dropped")))
    }

    @Test(timeout = 30_000)
    fun `with the attachments not asked for, an IMAP pass takes the text and never the PDF`() {
        file(row(RECENT, ageDays = 1))
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(), ON, partStore).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertEquals(
                listOf("UID FETCH $RECENT (BODYSTRUCTURE BODY.PEEK[HEADER])", "UID FETCH $RECENT (BODY.PEEK[1])"),
                server.issued("UID FETCH"),
            )
            assertEquals("nothing stored", emptyList<String>(), partStore.emailIds(ACCOUNT))
        }
    }

    @Test(timeout = 30_000)
    fun `with the attachments asked for, an IMAP pass stores the PDF once, unread, and it opens with no server`() {
        file(row(RECENT, ageDays = 1), row(OLD, ageDays = 30))
        var credentials: AccountCredentials? = null
        val repository = repository(service(), WITH_PARTS, partStore)
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            credentials = imapCredentials(server)
            runBlocking {
                repository.downloadInboxesAhead(listOf(credentials!!))
                repository.downloadInboxesAhead(listOf(credentials!!))
            }

            assertEquals(
                "the period's text, then its PDF on a peek, once over two passes; nothing of the older one",
                listOf(
                    "UID FETCH $RECENT (BODYSTRUCTURE BODY.PEEK[HEADER])",
                    "UID FETCH $RECENT (BODY.PEEK[1])",
                    "UID FETCH $RECENT (BODY.PEEK[2])",
                ),
                server.issued("UID FETCH"),
            )
            assertEquals("no flag written, so nothing marked read", emptyList<String>(), server.issued().filter { "STORE" in it })
        }
        val pdf = decodeCachedEmail(cachedBody(idOf(RECENT))).attachments.single()
        assertArrayEquals(PDF, partStore.read(ACCOUNT, idOf(RECENT), storedPartKeyOf(pdf)!!))
        assertArrayEquals(
            "opened with the server gone, the PDF comes from the store",
            PDF,
            runBlocking { repository.downloadAttachment(credentials!!, pdf, idOf(RECENT)) },
        )
    }

    @Test(timeout = 30_000)
    fun `a part the server refuses is asked for once, not on every pass`() {
        file(row(RECENT, ageDays = 1))
        refusePart = true
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            val repository = repository(service(), WITH_PARTS, partStore)
            runBlocking { repository.downloadInboxesAhead(listOf(imapCredentials(server))) }
            val afterFirst = server.issued("UID FETCH").count { it == "UID FETCH $RECENT (BODY.PEEK[2])" }
            runBlocking { repository.downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertTrue("precondition: the part was asked for", afterFirst > 0)
            assertEquals(afterFirst, server.issued("UID FETCH").count { it == "UID FETCH $RECENT (BODY.PEEK[2])" })
            assertTrue(partStore.isSettled(ACCOUNT, idOf(RECENT)))
        }
    }

    @Test(timeout = 30_000)
    fun `a part refused while the server is unavailable is asked for again at the next pass`() {
        file(row(RECENT, ageDays = 1))
        refusePart = true
        refusalCode = "UNAVAILABLE"
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            val repository = repository(service(), WITH_PARTS, partStore)
            runBlocking { repository.downloadInboxesAhead(listOf(imapCredentials(server))) }
            val afterFirst = server.issued("UID FETCH").count { it == "UID FETCH $RECENT (BODY.PEEK[2])" }
            runBlocking { repository.downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertTrue("precondition: the part was asked for", afterFirst > 0)
            assertTrue(
                "a passing refusal must not be remembered as final",
                server.issued("UID FETCH").count { it == "UID FETCH $RECENT (BODY.PEEK[2])" } > afterFirst,
            )
            assertFalse(partStore.isSkipped(ACCOUNT, idOf(RECENT), "part:2"))
            assertFalse(partStore.isSettled(ACCOUNT, idOf(RECENT)))
        }
    }

    @Test(timeout = 30_000)
    fun `a part read while the folder is renumbered is not stored under the cached body's id`() {
        file(row(RECENT, ageDays = 1))
        val numbering = FlippingNumbering(9L)
        onPartServed = { numbering.current = 10L }
        ScriptedImapServer.perConnection { _, tag, line -> served(tag, line) }.use { server ->
            runBlocking { repository(service(numbering), WITH_PARTS, partStore).downloadInboxesAhead(listOf(imapCredentials(server))) }

            assertTrue("precondition: the part was read", "UID FETCH $RECENT (BODY.PEEK[2])" in server.issued("UID FETCH"))
            assertFalse(partStore.has(ACCOUNT, idOf(RECENT), "part:2"))
            assertFalse(partStore.isSettled(ACCOUNT, idOf(RECENT)))
        }
    }

    @Test fun `a JMAP part the server says is gone is given up, and the older message's part is still taken`() {
        file(jmapRow("m1", ageDays = 1), jmapRow("m2", ageDays = 2))
        blobStatus["blob-m1"] = 404
        val credentials = startJmap()
        val repository = repository(service(), WITH_PARTS, partStore)

        runBlocking {
            repository.downloadInboxesAhead(listOf(credentials))
            repository.downloadInboxesAhead(listOf(credentials))
        }

        assertEquals(
            "the 404 asked once over two passes, the older part taken in the first",
            listOf("blob-m1", "blob-m2"),
            blobsDownloaded.map { it.removePrefix("/download/acc1/").substringBefore('/') },
        )
        assertTrue(partStore.isSkipped(JMAP_ACCOUNT, "m1", "part:2"))
        assertArrayEquals(PDF, partStore.read(JMAP_ACCOUNT, "m2", "part:2"))
    }

    @Test fun `a JMAP server failing a download ends the pass, and the part is asked for again at the next`() {
        file(jmapRow("m1", ageDays = 1), jmapRow("m2", ageDays = 2))
        blobStatus["blob-m1"] = 503
        val credentials = startJmap()
        val repository = repository(service(), WITH_PARTS, partStore)

        runBlocking {
            repository.downloadInboxesAhead(listOf(credentials))
            repository.downloadInboxesAhead(listOf(credentials))
        }

        assertEquals(
            listOf("blob-m1", "blob-m1"),
            blobsDownloaded.map { it.removePrefix("/download/acc1/").substringBefore('/') },
        )
        assertFalse(partStore.isSkipped(JMAP_ACCOUNT, "m1", "part:2"))
    }

    @Test fun `an empty download is never stored, ahead or by hand, and an empty file is never served`() {
        file(jmapRow("m1", ageDays = 1))
        emptyBlobs += "blob-m1"
        emptyBlobs += "blob-m9"
        val credentials = startJmap()
        val repository = repository(service(), WITH_PARTS, partStore)

        runBlocking { repository.downloadInboxesAhead(listOf(credentials)) }
        val part = app.sterna.core.jmap.model.EmailBodyPart(partId = "2", blobId = "blob-m9", type = "application/pdf", name = "a.pdf")
        runBlocking { repository.downloadAttachment(credentials, part, "m9") }

        assertFalse(partStore.has(JMAP_ACCOUNT, "m1", "part:2"))
        assertTrue("given up, not asked for at every pass", partStore.isSkipped(JMAP_ACCOUNT, "m1", "part:2"))
        assertNull(partStore.read(JMAP_ACCOUNT, "m9", "part:2"))
    }

    @Test fun `past a full pass of settled messages, the older ones are still taken, whatever hasAttachment says`() {
        val now = System.currentTimeMillis()
        val ids = (0 until 30).map { "m%02d".format(it) }
        file(*ids.mapIndexed { i, id -> jmapRow(id, ageDays = 0).copy(sortKey = now - DAY / 2 - i * 1000L, hasAttachment = false) }.toTypedArray())
        ids.take(25).forEach { partStore.markSettled(JMAP_ACCOUNT, it) }
        val credentials = startJmap()

        runBlocking { repository(service(), WITH_PARTS, partStore).downloadInboxesAhead(listOf(credentials)) }

        assertEquals(
            ids.drop(25).map { "blob-$it" }.toSet(),
            blobsDownloaded.map { it.removePrefix("/download/acc1/").substringBefore('/') }.toSet(),
        )
    }

    @Test fun `with the attachments asked for, a JMAP pass downloads each blob once, and marks nothing read`() {
        file(jmapRow("m1", ageDays = 1), jmapRow("m0", ageDays = 30))
        val credentials = startJmap()
        val repository = repository(service(), WITH_PARTS, partStore)

        runBlocking {
            repository.downloadInboxesAhead(listOf(credentials))
            repository.downloadInboxesAhead(listOf(credentials))
        }

        assertEquals(listOf("/download/acc1/blob-m1/a.pdf"), blobsDownloaded.map { it.substringBefore('?') })
        assertFalse("no keyword written, so nothing marked read: $jmapMethods", jmapMethods.any { it == "Email/set" })
        val pdf = decodeCachedEmail(cachedBody("m1")).attachments.single()
        assertArrayEquals(PDF, partStore.read(JMAP_ACCOUNT, "m1", storedPartKeyOf(pdf)!!))
    }

    @Test fun `a part opened by hand is read from the store before the network, and written there the first time`() {
        val credentials = startJmap()
        val repository = repository(service(), ON, partStore)
        val part = app.sterna.core.jmap.model.EmailBodyPart(partId = "2", blobId = "blob-m9", type = "application/pdf", name = "a.pdf", size = 6)

        val first = runBlocking { repository.downloadAttachment(credentials, part, "m9") }
        val requestsAfterFirst = jmap!!.requestCount
        val second = runBlocking { repository.downloadAttachment(credentials, part, "m9") }

        assertArrayEquals(PDF, first)
        assertArrayEquals(PDF, second)
        assertEquals("the second open went nowhere", requestsAfterFirst, jmap!!.requestCount)
        assertEquals(listOf("/download/acc1/blob-m9/a.pdf"), blobsDownloaded.map { it.substringBefore('?') })

        partStore.write(JMAP_ACCOUNT, "m8", storedPartKeyOf(part)!!, "kept".toByteArray())
        val before = jmap!!.requestCount
        assertArrayEquals("kept".toByteArray(), runBlocking { repository.downloadAttachment(credentials, part, "m8") })
        assertEquals("a stored part costs no request, not even the session", before, jmap!!.requestCount)
    }

    @Test fun `a stored part goes with its message's last list row, at the sweep that takes its body`() {
        file(row(RECENT, ageDays = 1))
        partStore.write(ACCOUNT, idOf(RECENT), "part:2", PDF)
        partStore.write(ACCOUNT, idOf(OLD), "part:2", PDF)
        partStore.write(OTHER_ACCOUNT, idOf(OLD), "part:2", PDF)

        runBlocking { repository(service(), ON, partStore).sweepOrphanBodies(ACCOUNT) }

        assertEquals(listOf(idOf(RECENT)), partStore.emailIds(ACCOUNT))
        assertEquals("another account's sweep is its own", listOf(idOf(OLD)), partStore.emailIds(OTHER_ACCOUNT))
    }

    // ---- the harness -----------------------------------------------------------------------------

    private class Outcome(val done: CountDownLatch) {
        @Volatile var thrown: Throwable? = null
    }

    private fun onItsOwnThread(call: suspend () -> Any?): Outcome {
        val outcome = Outcome(CountDownLatch(1))
        Thread {
            runCatching { runBlocking { call() } }.onFailure { outcome.thrown = it }
            outcome.done.countDown()
        }.apply { isDaemon = true }.start()
        return outcome
    }

    private fun file(vararg entities: EmailEntity) {
        rows += entities
        cache.upsert(entities.toList())
    }

    private fun row(uid: Long, ageDays: Long) = EmailEntity(
        id = idOf(uid), accountId = ACCOUNT, mailboxId = INBOX, threadId = "t$uid", subject = "Six o'clock",
        preview = "", receivedAt = "2026-10-01T10:00:00Z", fromName = "Bob", fromEmail = "bob@example.org",
        seen = false, flagged = false, hasAttachment = true,
        sortKey = System.currentTimeMillis() - ageDays * DAY, uidValidity = 9L,
    )

    private fun jmapRow(id: String, ageDays: Long) = row(0, ageDays).copy(id = id, accountId = JMAP_ACCOUNT, mailboxId = "inbox-1", uidValidity = null)

    private fun idOf(uid: Long) = ImapMailService.emailId(ACCOUNT, INBOX, uid)

    private fun cachedIds(): List<String> =
        cache.query("SELECT id FROM email_bodies ORDER BY id") { it.getString(1) }

    private fun skippedIds(): List<String> =
        cache.query("SELECT id FROM download_ahead_skips ORDER BY id") { it.getString(1) }

    private fun cachedBody(id: String): String =
        cache.query("SELECT bodyJson FROM email_bodies WHERE id = :id", mapOf("id" to id)) { it.getString(1) }.single()

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao = Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, args ->
        daoCalls += method.name
        when (method.name) {
            "idsToDownloadAhead" -> cache.query(
                DaoQuerySource.emailDaoQuery("idsToDownloadAhead"),
                mapOf("accountId" to args[0], "mailboxId" to args[1], "cutoff" to args[2], "uidValidity" to args[3], "limit" to args[4]),
            ) { it.getString(1) }
            "emailsByIds" -> {
                val ids = args[1] as List<String>
                rows.toList().filter { it.accountId == args[0] && it.id in ids }
            }
            "idsToDownloadAttachmentsAhead" -> cache.query(
                DaoQuerySource.emailDaoQuery("idsToDownloadAttachmentsAhead"),
                mapOf(
                    "accountId" to args[0], "mailboxId" to args[1], "cutoff" to args[2], "uidValidity" to args[3],
                    "limit" to args[4], "offset" to args[5],
                ),
            ) { it.getString(1) }
            "listedIds" -> {
                val ids = args[1] as List<String>
                rows.toList().filter { it.accountId == args[0] && it.id in ids }.map { it.id }
            }
            "toString" -> "scripted EmailDao"
            else -> error("EmailDao.${method.name} was not expected on the download ahead.")
        }
    } as EmailDao

    private fun bodyDao(): EmailBodyDao = Proxy.newProxyInstance(EmailBodyDao::class.java.classLoader, arrayOf(EmailBodyDao::class.java)) { _, method, args ->
        when (method.name) {
            "upsert" -> {
                val body = args[0] as EmailBodyEntity
                cache.update(
                    "INSERT OR REPLACE INTO email_bodies(id, accountId, bodyJson, inlineImagesJson, fetchedAt) " +
                        "VALUES(:id, :accountId, :bodyJson, :inlineImagesJson, :fetchedAt)",
                    mapOf(
                        "id" to body.id, "accountId" to body.accountId, "bodyJson" to body.bodyJson,
                        "inlineImagesJson" to body.inlineImagesJson, "fetchedAt" to body.fetchedAt,
                    ),
                )
                Unit
            }
            "skipDownloadAhead" -> {
                cache.update(DOWNLOAD_AHEAD_SKIP_SQL, mapOf("accountId" to args[0], "id" to args[1]))
                Unit
            }
            "pruneDownloadAheadSkips" -> {
                cache.update(DOWNLOAD_AHEAD_SKIPS_PRUNE_SQL, mapOf("accountId" to args[0]))
                Unit
            }
            "byId" -> cache.query(
                "SELECT id, accountId, bodyJson, inlineImagesJson, fetchedAt FROM email_bodies WHERE accountId = :accountId AND id = :id",
                mapOf("accountId" to args[0], "id" to args[1]),
            ) { EmailBodyEntity(it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5)) }.firstOrNull()
            "deleteOrphans" -> Unit
            "toString" -> "scripted EmailBodyDao"
            else -> {
                // Recorded before throwing: `persistBody` swallows what its write throws.
                unexpectedBodyCalls += method.name
                error("EmailBodyDao.${method.name} was not expected on the download ahead.")
            }
        }
    } as EmailBodyDao

    private fun mailboxDao(): MailboxDao = Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
        when (method.name) {
            "idForRole" -> if (args[1] == "inbox") (if (args[0] == ACCOUNT) inboxLookup else "inbox-1") else null
            "roleForId" -> if (args[1] == INBOX || args[1] == "inbox-1") "inbox" else "junk"
            "replaceAll", "replaceAllKeepingUnread" -> Unit
            "toString" -> "scripted MailboxDao"
            else -> error("MailboxDao.${method.name} was not expected on the download ahead.")
        }
    } as MailboxDao

    private val store = object : RecordingAccountStorage() {
        override fun accounts(): List<StoredAccount> = listOfNotNull(
            StoredAccount(id = ACCOUNT, server = "unused", username = "alex", protocol = MailProtocol.IMAP),
            StoredAccount(id = JMAP_ACCOUNT, server = "unused", username = "alex", protocol = MailProtocol.JMAP)
                .takeUnless { jmapAccountRemoved },
        )

        override fun account(id: String): StoredAccount? = accounts().firstOrNull { it.id == id }
    }

    /** A numbering a test changes under a read, as `invalidate` would on another path. */
    private class FlippingNumbering(@Volatile var current: Long) : UidValidityStore {
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? = current
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
            current = uidValidity
        }
    }

    private fun service(numbering: UidValidityStore = UidValidityStore.None) =
        ImapMailService(ImapClient(), SmtpClient(), ReadMarkRaceCountsOnceTest.unusedTokenRefresher(), numbering)

    /** A folder numbering already recorded at [recorded], and the invalidations asked of it. */
    private class RecordedNumbering(private val recorded: Long) : UidValidityStore {
        val invalidated: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        override var onRenumbered: ((String, String) -> Unit)? = null
        override suspend fun recorded(accountId: String, mailboxId: String): Long? = recorded
        override suspend fun record(accountId: String, mailboxId: String, uidValidity: Long) = Unit
        override suspend fun invalidate(accountId: String, mailboxId: String, uidValidity: Long) {
            invalidated += mailboxId
        }
    }

    private fun repository(imap: ImapMailService, setting: DownloadAheadSetting?, parts: AttachmentStore? = null) = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = bodyDao(),
        mailboxDao = mailboxDao(),
        imap = imap,
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        snoozedDao = unusableDao<SnoozedDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        accountStore = store,
        outboxDao = unusableDao<OutboxDao>(),
        purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        serverContactDao = unusableDao<ServerContactDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
        downloadAheadSetting = { setting },
        attachmentStore = parts,
    )

    private fun imapCredentials(server: ScriptedImapServer) = AccountCredentials(
        server = "scripted",
        username = "alex@example.org",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint(server.host, server.port, ConnectionSecurity.NONE),
    )

    /** The three messages of the folder: a text with a PDF beside it, and a text past the parse limit. */
    private fun served(tag: String, line: String): String {
        val headerFetch = HEADER_FETCH.matchEntire(line)
        val textFetch = TEXT_FETCH.matchEntire(line)
        return when {
            line.startsWith("LOGIN") -> "$tag OK [CAPABILITY IMAP4rev1 UIDPLUS MOVE] logged in\r\n"
            line.startsWith("CAPABILITY") -> "* CAPABILITY IMAP4rev1 UIDPLUS MOVE\r\n$tag OK done\r\n"
            line.startsWith("SELECT") ->
                "* 3 EXISTS\r\n* OK [UIDVALIDITY $serverNumbering] ok\r\n* OK [UIDNEXT 20] ok\r\n$tag OK [READ-WRITE] selected\r\n"
            headerFetch != null -> {
                val uid = headerFetch.groupValues[1].toLong()
                val structure = when (uid) {
                    TOO_LARGE -> TOO_LARGE_STRUCTURE
                    SIGNED -> SIGNED_STRUCTURE
                    else -> STRUCTURE
                }
                "* 1 FETCH (UID $uid BODYSTRUCTURE $structure BODY[HEADER] {${HEADER.length}}\r\n$HEADER)\r\n$tag OK done\r\n"
            }
            line == "UID FETCH $SIGNED (BODY.PEEK[])" ->
                "* 1 FETCH (UID $SIGNED BODY[] {${SIGNED_SOURCE.length}}\r\n$SIGNED_SOURCE)\r\n$tag OK done\r\n"
            line == "UID FETCH $RECENT (BODY.PEEK[2])" ->
                if (refusePart) {
                    "$tag NO ${refusalCode?.let { "[$it] " } ?: ""}no such part\r\n"
                } else {
                    onPartServed()
                    "* 1 FETCH (UID $RECENT BODY[2] {${PDF_BASE64.length}}\r\n$PDF_BASE64)\r\n$tag OK done\r\n"
                }
            textFetch != null -> {
                val uid = textFetch.groupValues[1].toLong()
                "* 1 FETCH (UID $uid BODY[1] {${TEXT.length}}\r\n$TEXT)\r\n$tag OK done\r\n"
            }
            else -> "$tag OK done\r\n"
        }
    }

    private val jmapIdsAsked = Collections.synchronizedList(mutableListOf<List<String>>())
    private val jmapMethods = Collections.synchronizedList(mutableListOf<String>())
    private val blobsDownloaded = Collections.synchronizedList(mutableListOf<String>())

    private fun startJmap(): AccountCredentials {
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET" && request.path!!.startsWith("/download/")) {
                    blobsDownloaded += request.path!!
                    val blobId = request.path!!.removePrefix("/download/acc1/").substringBefore('/')
                    blobStatus[blobId]?.let { return MockResponse().setResponseCode(it) }
                    if (blobId in emptyBlobs) return MockResponse().setBody("")
                    return MockResponse().setBody(okio.Buffer().write(PDF))
                }
                if (request.method == "GET") return json(sessionBody(mock))
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject.getValue("methodCalls").jsonArray[0].jsonArray
                jmapMethods += call[0].jsonPrimitive.content
                return when (call[0].jsonPrimitive.content) {
                    "Mailbox/get" -> json("""{"methodResponses":[["Mailbox/get",{"accountId":"acc1","state":"m1","list":[],"notFound":[]},"c0"]]}""")
                    "Identity/get" -> json("""{"methodResponses":[["Identity/get",{"list":[]},"i0"]]}""")
                    "Email/get" -> {
                        val ids = call[1].jsonObject.getValue("ids").jsonArray.map { it.jsonPrimitive.content }
                        jmapIdsAsked += ids
                        val headersOnly = call[1].jsonObject.getValue("properties").jsonArray.map { it.jsonPrimitive.content } == listOf("id", "headers")
                        val list = ids.joinToString(",") { id ->
                            if (headersOnly) {
                                """{"id":"$id","headers":[{"name":"Subject","value":"Hi"}]}"""
                            } else {
                                """{"id":"$id","subject":"Hi","textBody":[{"partId":"1","type":"text/plain"}],""" +
                                    """"attachments":[{"partId":"2","blobId":"blob-$id","type":"application/pdf","name":"a.pdf","size":${PDF.size}}],""" +
                                    """"bodyValues":{"1":{"value":"Hello from $id"}}}"""
                            }
                        }
                        json("""{"methodResponses":[["Email/get",{"accountId":"acc1","state":"e1","list":[$list],"notFound":[]},"g0"]]}""")
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        mock.start()
        jmap = mock
        return AccountCredentials(
            server = mock.url("/").toString().removeSuffix("/"),
            username = "alex",
            password = "secret",
            id = JMAP_ACCOUNT,
            protocol = MailProtocol.JMAP,
            authType = AuthType.BASIC,
        )
    }

    private fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun sessionBody(mock: MockWebServer): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${mock.url("/jmap/api")}","state":"sess-1",
         "downloadUrl":"${mock.url("/")}download/{accountId}/{blobId}/{name}?type={type}"}
        """.trimIndent()

    private companion object {
        const val ACCOUNT = "acc"
        const val JMAP_ACCOUNT = "jacc"
        const val INBOX = "INBOX"
        const val RECENT = 7L
        const val OLD = 8L
        const val TOO_LARGE = 9L
        const val SIGNED = 11L
        const val STALE = 12L
        const val DAY = 86_400_000L
        val ON = DownloadAheadSetting(true, DownloadAheadPeriod.SEVEN_DAYS)
        val WITH_PARTS = DownloadAheadSetting(true, DownloadAheadPeriod.SEVEN_DAYS, attachments = true)
        const val OTHER_ACCOUNT = "acc2"
        val PDF = "%PDF-1".toByteArray()
        val PDF_BASE64: String = java.util.Base64.getEncoder().encodeToString(PDF)
        val OFF = DownloadAheadSetting(false, DownloadAheadPeriod.SEVEN_DAYS)

        const val HEADER = "From: Bob <bob@example.org>\r\nSubject: Six o'clock\r\nContent-Type: multipart/mixed; boundary=b1\r\n\r\n"
        const val TEXT = "I will be there at six\r\n"
        val STRUCTURE =
            """(("TEXT" "PLAIN" ("CHARSET" "utf-8") NIL NIL "7BIT" ${TEXT.length} 1 NIL NIL NIL NIL)""" +
                """("APPLICATION" "PDF" ("NAME" "a.pdf") NIL NIL "BASE64" 4000 NIL ("ATTACHMENT" ("FILENAME" "a.pdf")) NIL NIL)""" +
                """ "MIXED" ("BOUNDARY" "b1") NIL NIL NIL)"""
        val TOO_LARGE_STRUCTURE =
            """("TEXT" "PLAIN" ("CHARSET" "utf-8") NIL NIL "7BIT" ${MimeParser.MAX_BODY_CHARS + 1} 1 NIL NIL NIL NIL)"""

        val SIGNED_STRUCTURE =
            """(("TEXT" "PLAIN" ("CHARSET" "utf-8") NIL NIL "7BIT" ${TEXT.length} 1 NIL NIL NIL NIL)""" +
                """("APPLICATION" "PGP-SIGNATURE" ("NAME" "signature.asc") NIL NIL "7BIT" 100 NIL NIL NIL NIL)""" +
                """ "SIGNED" ("BOUNDARY" "s1" "PROTOCOL" "application/pgp-signature" "MICALG" "pgp-sha256") NIL NIL NIL)"""
        val SIGNED_SOURCE =
            "From: Bob <bob@example.org>\r\nSubject: Six o'clock\r\nContent-Type: multipart/signed; boundary=s1; " +
                "protocol=\"application/pgp-signature\"; micalg=pgp-sha256\r\n\r\n--s1\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n$TEXT--s1\r\n" +
                "Content-Type: application/pgp-signature; name=signature.asc\r\n\r\n" +
                "-----BEGIN PGP SIGNATURE-----\r\n\r\niQ==\r\n-----END PGP SIGNATURE-----\r\n--s1--\r\n"

        val HEADER_FETCH = Regex("""UID FETCH (\d+) \(BODYSTRUCTURE BODY\.PEEK\[HEADER]\)""")
        val TEXT_FETCH = Regex("""UID FETCH (\d+) \(BODY\.PEEK\[1]\)""")

        const val BODIES_CREATE =
            "CREATE TABLE email_bodies(id TEXT NOT NULL, accountId TEXT NOT NULL, bodyJson TEXT NOT NULL, " +
                "inlineImagesJson TEXT NOT NULL, fetchedAt INTEGER NOT NULL, PRIMARY KEY(accountId, id))"
    }
}

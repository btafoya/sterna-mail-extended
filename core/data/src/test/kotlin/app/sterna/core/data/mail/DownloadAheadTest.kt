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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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

    private fun repository(imap: ImapMailService, setting: DownloadAheadSetting?) = MailRepository(
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
            textFetch != null -> {
                val uid = textFetch.groupValues[1].toLong()
                "* 1 FETCH (UID $uid BODY[1] {${TEXT.length}}\r\n$TEXT)\r\n$tag OK done\r\n"
            }
            else -> "$tag OK done\r\n"
        }
    }

    private val jmapIdsAsked = Collections.synchronizedList(mutableListOf<List<String>>())

    private fun startJmap(): AccountCredentials {
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return json(sessionBody(mock))
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject.getValue("methodCalls").jsonArray[0].jsonArray
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
         "username":"alex","apiUrl":"${mock.url("/jmap/api")}","state":"sess-1"}
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

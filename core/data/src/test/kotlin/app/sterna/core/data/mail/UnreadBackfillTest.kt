package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.CachedFolder
import app.sterna.core.data.db.EmailBodyDao
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
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.Email
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `MailRepository.backfillUnread` run against a JMAP server that answers and a cache that keeps
 * what it is handed: what goes over the wire and what lands in the cache are both read back.
 *
 * The cache is a map standing in for the `emails` table. It gives `insertAllIgnoring` the meaning
 * of an insert that skips an existing key and `refile` that of a folder change conditioned on the
 * folder it leaves, and refuses every call this path has no business making, `upsertAll` and every
 * delete among them. The statement of `refile` itself is run over SQLite in its own test.
 *
 * What this does not cover:
 * - That Room turns the annotation of `insertAllIgnoring` into `INSERT OR IGNORE`. The annotation is
 *   read as text, in the last test, and nothing here runs the generated statement.
 * - A row held by a swipe in flight. Holding one needs the whole removal path; the calls to
 *   `sparingInFlight` are read as text beside the annotation, and the plan's own refusal of a held
 *   id is run with a stand-in for the repository's marks.
 * - What a re-filed row does to the folder it leaves: the list of that folder loses the line until
 *   its next sync writes it back.
 * - A marker that outlives the process: the ids kept out of the notification candidates are held in
 *   memory.
 */
class UnreadBackfillTest {

    private data class Sent(val method: String, val args: JsonObject?)

    private val received = CopyOnWriteArrayList<Sent>()
    private val cache = ConcurrentHashMap<Pair<String, String>, EmailEntity>()
    private val insertCalls = CopyOnWriteArrayList<List<String>>()

    private data class Refiled(val accountId: String, val id: String, val from: String, val to: String)

    private val refileCalls = CopyOnWriteArrayList<Refiled>()
    private val cursorWrites = CopyOnWriteArrayList<String>()
    private val configured = CopyOnWriteArrayList(listOf(ACCOUNT))

    /** The server's unread messages outside the excluded folders, newest first. */
    @Volatile private var unread: List<String> = emptyList()

    /** The `Email/get` row of each message the server holds. */
    @Volatile private var rows: Map<String, String> = emptyMap()

    /** The most ids one `Email/query` answers with, whatever limit it was asked for. */
    @Volatile private var pageCap = Int.MAX_VALUE
    @Volatile private var sendTotal = true
    @Volatile private var queryError: String? = null
    @Volatile private var onEmailGet: () -> Unit = {}

    /** The folders the cache holds for each account, with their roles. */
    private val folders = mapOf(
        ACCOUNT to listOf("inbox" to "inbox", "work" to null, "archive" to "archive", "trash" to "trash"),
        OTHER_ACCOUNT to listOf("inbox" to "inbox", "archive" to "archive"),
    )

    private lateinit var server: MockWebServer

    private companion object {
        const val ACCOUNT = "account-1"
        const val OTHER_ACCOUNT = "account-2"
        const val HOUR_MS = 60L * 60 * 1000
    }

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    received += Sent("session", null)
                    return json(sessionBody())
                }
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject.getValue("methodCalls").jsonArray[0].jsonArray
                val method = call[0].jsonPrimitive.content
                val args = call[1].jsonObject
                received += Sent(method, args)
                return when (method) {
                    "Identity/get" -> json("""{"methodResponses":[["Identity/get",{"list":[]},"i0"]]}""")
                    "Mailbox/get" -> json("""{"methodResponses":[["Mailbox/get",{"list":[]},"c0"]]}""")
                    "Email/query" -> json(queryBody(args))
                    "Email/get" -> {
                        onEmailGet()
                        json(getBody(args))
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun sessionBody(): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${server.url("/jmap/api")}","state":"sess-1"}
        """.trimIndent()

    private fun queryBody(args: JsonObject): String {
        queryError?.let { return """{"methodResponses":[["error",{"type":"$it"},"q0"]]}""" }
        val position = args.getValue("position").jsonPrimitive.int
        val limit = args.getValue("limit").jsonPrimitive.int
        val ids = unread.drop(position).take(minOf(limit, pageCap)).joinToString(",") { "\"$it\"" }
        val total = if (sendTotal) ""","total":${unread.size}""" else ""
        return """{"methodResponses":[["Email/query",{"ids":[$ids],"queryState":"q1"$total},"q0"]]}"""
    }

    private fun getBody(args: JsonObject): String {
        val list = args.getValue("ids").jsonArray.mapNotNull { rows[it.jsonPrimitive.content] }.joinToString(",")
        return """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[$list],"notFound":[]},"g0"]]}"""
    }

    /** One unread message as the server states it; [filedIn] null leaves `mailboxIds` out. */
    private fun message(
        id: String,
        filedIn: Map<String, Boolean>?,
        subject: String = "Subject of $id",
        receivedAt: String? = "2026-01-05T10:00:00Z",
    ): Pair<String, String> {
        val filed = filedIn?.entries?.joinToString(",", ""","mailboxIds":{""", "}") { (k, v) -> "\"$k\":$v" }.orEmpty()
        val date = receivedAt?.let { ""","receivedAt":"$it"""" }.orEmpty()
        return id to """{"id":"$id","threadId":"t-$id","subject":"$subject"$date$filed}"""
    }

    private fun serve(vararg messages: Pair<String, String>) {
        unread = messages.map { it.first }
        rows = messages.toMap()
    }

    private fun cached(accountId: String, id: String, mailboxId: String, subject: String = "As cached"): EmailEntity =
        Email(id = id, subject = subject, receivedAt = "2026-01-04T09:00:00Z").toEntity(accountId, mailboxId)
            .also { cache[accountId to id] = it }

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao =
        Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, args ->
            val passed = args.orEmpty().dropLast(1)
            when (method.name) {
                "cachedFoldersOf" -> (passed[1] as List<String>)
                    .mapNotNull { cache[passed[0] as String to it] }
                    .map { CachedFolder(it.id, it.mailboxId) }
                "refile" -> {
                    val call = Refiled(passed[0] as String, passed[1] as String, passed[2] as String, passed[3] as String)
                    refileCalls += call
                    var changed = 0
                    cache.computeIfPresent(call.accountId to call.id) { _, row ->
                        if (row.mailboxId != call.from) row else row.copy(mailboxId = call.to).also { changed = 1 }
                    }
                    changed
                }
                "insertAllIgnoring" -> {
                    val written = passed[0] as List<EmailEntity>
                    insertCalls += written.map { it.id }
                    written.forEach { cache.putIfAbsent(it.accountId to it.id, it) }
                }
                "receivedSince" -> receivedSince(passed[0] as String, passed[1] as String, passed[2] as Long)
                    .take(passed[3] as Int)
                "idsReceivedSince" -> receivedSince(passed[0] as String, passed[1] as String, passed[2] as Long)
                    .map { it.id }
                "mailboxOf" -> cache[passed[0] as String to passed[1] as String]?.mailboxId
                "toString" -> "the cache of UnreadBackfillTest"
                else -> error("EmailDao.${method.name} was called by a path that only reads folders, inserts and re-files.")
            }
        } as EmailDao

    private fun receivedSince(accountId: String, mailboxId: String, since: Long): List<EmailEntity> =
        cache.values
            .filter { it.accountId == accountId && it.mailboxId == mailboxId && (it.sortKey >= since || it.sortKey == 0L) }
            .sortedWith(compareByDescending<EmailEntity> { it.sortKey }.thenByDescending { it.id })

    private fun mailboxDao(): MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            val passed = args.orEmpty().dropLast(1)
            when (method.name) {
                "idsForAccount" -> folders[passed[0] as String].orEmpty().map { it.first }
                "roleForId" -> folders[passed[0] as String].orEmpty().firstOrNull { it.first == passed[1] }?.second
                "toString" -> "the folders of UnreadBackfillTest"
                else -> error("MailboxDao.${method.name} was called by a path that only reads folder ids and roles.")
            }
        } as MailboxDao

    private val store = object : RecordingAccountStorage() {
        override fun accounts(): List<StoredAccount> =
            configured.map { StoredAccount(id = it, server = "unused", username = "alex") }

        override fun account(id: String): StoredAccount? = accounts().firstOrNull { it.id == id }
    }

    private val cursors = object : SyncCursorStore {
        override fun save(key: String, queryState: String, emailState: String) {
            cursorWrites += "save $key"
        }
        override fun load(key: String): Pair<String, String>? = null
        override fun remove(key: String) {
            cursorWrites += "remove $key"
        }
        override fun clear() {
            cursorWrites += "clear"
        }
        override fun keys(): Set<String> = emptySet()
    }

    private val repository: MailRepository by lazy {
        MailRepository(
            client = JmapClient(),
            emailDao = emailDao(),
            emailFtsDao = unusableDao<EmailFtsDao>(),
            emailBodyDao = unusableDao<EmailBodyDao>(),
            mailboxDao = mailboxDao(),
            imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
            scheduledSendDao = unusableDao<ScheduledSendDao>(),
            snoozedDao = unusableDao<SnoozedDao>(),
            recentContactDao = unusableDao<RecentContactDao>(),
            accountStore = store,
            outboxDao = unusableDao<OutboxDao>(),
            purgeSnapshotDao = unusableDao<PurgeSnapshotDao>(),
            localDraftDao = unusableDao<LocalDraftDao>(),
            serverContactDao = unusableDao<app.sterna.core.data.db.ServerContactDao>(),
            mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
            outboxFilesDir = File("outbox-never-read"),
            localDraftFilesDir = File("drafts-never-read"),
            syncStateStore = cursors,
        )
    }

    private fun credentials(protocol: MailProtocol = MailProtocol.JMAP): AccountCredentials = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex",
        password = "secret",
        id = ACCOUNT,
        protocol = protocol,
        authType = AuthType.BASIC,
    )

    private val inboxAndWork = listOf(ACCOUNT to "inbox", ACCOUNT to "work")

    private fun backfill(scopes: List<Pair<String, String>> = inboxAndWork) =
        runBlocking { repository.backfillUnread(credentials(), scopes) }

    private fun queries(): List<JsonObject> = received.filter { it.method == "Email/query" }.map { it.args!! }

    private fun idsAskedFor(): List<List<String>> = received.filter { it.method == "Email/get" }
        .map { sent -> sent.args!!.getValue("ids").jsonArray.map { it.jsonPrimitive.content } }

    /** The folders the query excluded, or null when its filter names none. */
    private fun excludedBy(query: JsonObject): List<String>? {
        val filter = query.getValue("filter").jsonObject
        val conditions = filter["conditions"]?.jsonArray?.map { it.jsonObject } ?: listOf(filter)
        return conditions.firstNotNullOfOrNull { it["inMailboxOtherThan"] }?.jsonArray?.map { it.jsonPrimitive.content }
    }

    private fun folderOf(id: String, accountId: String = ACCOUNT): String? = cache[accountId to id]?.mailboxId

    @Test fun `an unread message the cache never held is inserted under its folder of the scope`() {
        serve(message("m-old", mapOf("work" to true), subject = "Six o'clock"))

        backfill()

        val row = cache[ACCOUNT to "m-old"] ?: return fail("the unread message was not cached")
        assertEquals("work", row.mailboxId)
        assertEquals(ACCOUNT, row.accountId)
        assertEquals("Six o'clock", row.subject)
        assertEquals(false, row.seen)
        assertEquals("t-m-old", row.threadId)
    }

    @Test fun `a row already cached is neither rewritten nor re-filed`() {
        val before = cached(ACCOUNT, "m-kept", "inbox")
        serve(
            message("m-kept", mapOf("work" to true), subject = "From the server"),
            message("m-new", mapOf("work" to true)),
        )

        backfill()

        assertEquals("the pass ran: its other message is cached", "work", folderOf("m-new"))
        assertEquals(before, cache[ACCOUNT to "m-kept"])
        assertEquals("a cached id is not read again", listOf(listOf("m-new")), idsAskedFor())
    }

    @Test fun `a row a folder sync writes during the pass is not re-filed`() {
        serve(message("m-raced", mapOf("work" to true), subject = "From the server"), message("m-new", mapOf("work" to true)))
        var synced: EmailEntity? = null
        onEmailGet = { synced = cached(ACCOUNT, "m-raced", "inbox") }

        backfill()

        assertEquals("the pass ran: its other message is cached", "work", folderOf("m-new"))
        assertEquals(synced, cache[ACCOUNT to "m-raced"])
    }

    @Test fun `a row a folder sync left outside the scope is filed back under its folder of the scope`() {
        val before = cached(ACCOUNT, "m-twice", "trash")
        serve(message("m-twice", mapOf("work" to true, "trash" to true), subject = "From the server"))

        backfill()

        assertEquals("only the folder changes", before.copy(mailboxId = "work"), cache[ACCOUNT to "m-twice"])
        assertEquals(listOf(Refiled(ACCOUNT, "m-twice", from = "trash", to = "work")), refileCalls.toList())
        assertEquals(listOf(listOf("m-twice")), idsAskedFor())
        assertEquals("a cached row is never handed to the insert", emptyList<String>(), insertCalls.flatten())
    }

    @Test fun `a row cached outside the scope goes to the folder of the scope that ranks first`() {
        cached(ACCOUNT, "m-thrice", "archive")
        serve(message("m-thrice", mapOf("work" to true, "archive" to true, "inbox" to true)))

        backfill()

        assertEquals("inbox", folderOf("m-thrice"))
    }

    @Test fun `a row cached outside the scope stays there when the server files it in no folder of the scope`() {
        val before = cached(ACCOUNT, "m-bin", "trash")
        serve(
            message("m-bin", mapOf("trash" to true, "archive" to true, "work" to false)),
            message("m-twice", mapOf("work" to true, "trash" to true)),
        )
        cached(ACCOUNT, "m-twice", "trash")

        backfill()

        assertEquals("the pass ran: its other row is filed back", "work", folderOf("m-twice"))
        assertEquals(before, cache[ACCOUNT to "m-bin"])
        assertEquals(listOf("m-twice"), refileCalls.map { it.id })
    }

    @Test fun `a row cached outside the scope that the app has just acted on is not filed back`() {
        val before = cached(ACCOUNT, "m-touched", "trash")
        cached(ACCOUNT, "m-twice", "trash")
        serve(
            message("m-touched", mapOf("inbox" to true)),
            message("m-twice", mapOf("work" to true, "trash" to true)),
        )
        // The mark is set before the server is asked, and the refused `Email/set` leaves it in place.
        val refused = runCatching { runBlocking { repository.setFlagged(credentials(), "m-touched", true) } }
        assertTrue("the flag change was expected to be refused by this server", refused.isFailure)

        backfill()

        assertEquals("the pass ran: its other row is filed back", "work", folderOf("m-twice"))
        assertEquals(before, cache[ACCOUNT to "m-touched"])
        assertEquals(listOf(listOf("m-twice")), idsAskedFor())
    }

    @Test fun `a row a folder sync moves during the pass is left where the sync put it`() {
        cached(ACCOUNT, "m-raced", "trash")
        serve(message("m-raced", mapOf("work" to true, "trash" to true)))
        var synced: EmailEntity? = null
        onEmailGet = { synced = cached(ACCOUNT, "m-raced", "archive", subject = "As synced") }

        backfill()

        assertEquals(synced, cache[ACCOUNT to "m-raced"])
        assertEquals(
            "the write names the folder the pass read, not the one the row is under by then",
            listOf(Refiled(ACCOUNT, "m-raced", from = "trash", to = "work")),
            refileCalls.toList(),
        )
    }

    private fun plan(cachedFolders: Map<String, String>, held: Set<String> = emptySet()): UnreadBackfillPlan =
        unreadBackfillPlan(listOf("a", "b", "c", "d"), cachedFolders, inScope = setOf("inbox", "work")) { it in held }

    @Test fun `the plan inserts what the cache lacks and files back only what it holds outside the scope`() {
        assertEquals(
            UnreadBackfillPlan(absent = listOf("a", "d"), misfiled = mapOf("c" to "trash")),
            plan(mapOf("b" to "inbox", "c" to "trash")),
        )
    }

    @Test fun `the plan leaves alone an id a local gesture holds, cached or not`() {
        assertEquals(
            UnreadBackfillPlan(absent = listOf("d"), misfiled = mapOf("b" to "archive")),
            plan(mapOf("b" to "archive", "c" to "trash"), held = setOf("a", "c")),
        )
    }

    @Test fun `a message filed in no folder of the scope is not written`() {
        serve(
            message("m-elsewhere", mapOf("archive" to true)),
            message("m-unfiled", filedIn = null),
            message("m-empty", emptyMap()),
            message("m-false", mapOf("work" to false, "archive" to true)),
            message("m-both", mapOf("archive" to true, "work" to true)),
            message("m-ranked", mapOf("work" to true, "inbox" to true)),
        )

        backfill()

        assertEquals(
            mapOf("m-both" to "work", "m-ranked" to "inbox"),
            cache.values.associate { it.id to it.mailboxId },
        )
    }

    @Test fun `the query excludes the folders the account holds outside the scope`() {
        cached(OTHER_ACCOUNT, "m-shared", "archive")
        serve(message("m-shared", mapOf("archive" to true)), message("m-inbox", mapOf("inbox" to true)))

        backfill(listOf(ACCOUNT to "inbox", ACCOUNT to "work", OTHER_ACCOUNT to "archive"))

        assertEquals(
            "a folder another account put in the scope stays excluded here, the two share the id",
            listOf(listOf("archive", "trash")),
            queries().map { excludedBy(it) },
        )
        assertEquals("acc1", queries().single().getValue("accountId").jsonPrimitive.content)
        assertEquals(
            "the other account's row under the same id hides nothing, and its folder files nothing here",
            listOf(listOf("m-shared", "m-inbox")),
            idsAskedFor(),
        )
        assertEquals(null, folderOf("m-shared"))
        assertEquals("archive", folderOf("m-shared", OTHER_ACCOUNT))
        assertEquals("inbox", folderOf("m-inbox"))
    }

    @Test fun `the walk advances by the ids received and stops at the total`() {
        pageCap = 2
        cached(ACCOUNT, "u2", "inbox")
        cached(ACCOUNT, "u4", "inbox")
        serve(*(1..5).map { message("u$it", mapOf("work" to true)) }.toTypedArray())

        backfill()

        assertEquals(listOf(0, 2, 4), queries().map { it.getValue("position").jsonPrimitive.int })
        assertEquals(listOf(true, true, true), queries().map { it.getValue("calculateTotal").jsonPrimitive.boolean })
        assertEquals("only the ids the cache lacks are read", listOf(listOf("u1"), listOf("u3"), listOf("u5")), idsAskedFor())
        assertEquals("each page is written before the next is asked for", listOf(listOf("u1"), listOf("u3"), listOf("u5")), insertCalls)
        assertEquals(listOf("work", "work", "work"), listOf("u1", "u3", "u5").map { folderOf(it) })
    }

    @Test fun `without a total the walk ends on the empty page`() {
        pageCap = 2
        sendTotal = false
        serve(*(1..3).map { message("u$it", mapOf("work" to true)) }.toTypedArray())

        backfill()

        assertEquals(listOf(0, 2, 3), queries().map { it.getValue("position").jsonPrimitive.int })
        assertEquals(setOf("u1", "u2", "u3"), cache.keys.map { it.second }.toSet())
    }

    @Test fun `a message the app has just acted on is not put back`() {
        serve(message("m-touched", mapOf("work" to true)), message("m-new", mapOf("work" to true)))
        // The mark is set before the server is asked, and the refused `Email/set` leaves it in place.
        val refused = runCatching { runBlocking { repository.setFlagged(credentials(), "m-touched", true) } }
        assertTrue("the flag change was expected to be refused by this server", refused.isFailure)

        backfill()

        assertEquals("the pass ran: its other message is cached", "work", folderOf("m-new"))
        assertEquals(null, folderOf("m-touched"))
        assertEquals(listOf(listOf("m-new")), idsAskedFor())
    }

    @Test fun `an IMAP account asks nothing and writes nothing`() {
        serve(message("m-old", mapOf("work" to true)))

        runBlocking { repository.backfillUnread(credentials(MailProtocol.IMAP), inboxAndWork) }

        assertEquals(0, server.requestCount)
        assertEquals(emptyMap<Pair<String, String>, EmailEntity>(), cache.toMap())
        assertEquals(emptyList<List<String>>(), insertCalls.toList())
    }

    @Test fun `an IMAP account leaves a row cached outside the scope where it is`() {
        val before = cached(ACCOUNT, "m-twice", "trash")
        serve(message("m-twice", mapOf("work" to true, "trash" to true)))

        runBlocking { repository.backfillUnread(credentials(MailProtocol.IMAP), inboxAndWork) }

        assertEquals(0, server.requestCount)
        assertEquals(before, cache[ACCOUNT to "m-twice"])
        assertEquals(emptyList<Refiled>(), refileCalls.toList())
    }

    @Test fun `a query the server refuses is thrown, not taken for nothing to add`() {
        cached(ACCOUNT, "m-kept", "inbox")
        val before = cache.toMap()
        queryError = "unsupportedFilter"

        try {
            backfill()
            fail("the refused query came back as a pass with nothing to add")
        } catch (refusal: JmapException) {
            assertTrue("thrown: ${refusal.message}", refusal.message.orEmpty().contains("unsupportedFilter"))
        }

        assertEquals(before, cache.toMap())
        assertEquals(emptyList<String>(), cursorWrites.toList())
        assertEquals(emptyList<List<String>>(), insertCalls.toList())
    }

    @Test fun `a pass that fails keeps the pages it had written`() {
        pageCap = 1
        serve(*(1..3).map { message("u$it", mapOf("work" to true)) }.toTypedArray())
        var gets = 0
        onEmailGet = { if (++gets == 2) queryError = "serverFail" }

        try {
            backfill()
            fail("the test server was expected to refuse the third query")
        } catch (refusal: JmapException) {
            assertTrue("thrown: ${refusal.message}", refusal.message.orEmpty().contains("serverFail"))
        }

        assertEquals(listOf("work", "work", null), listOf("u1", "u2", "u3").map { folderOf(it) })
    }

    @Test fun `an account signed out during the pass gets no row and keeps its rows where they are`() {
        val before = cached(ACCOUNT, "m-twice", "trash")
        serve(message("m-old", mapOf("work" to true)), message("m-twice", mapOf("work" to true, "trash" to true)))
        onEmailGet = { configured.clear() }

        try {
            backfill()
            fail("the pass wrote for an account that is no longer configured")
        } catch (gone: AccountGoneException) {
            assertTrue("thrown: ${gone.message}", gone.message.orEmpty().contains(ACCOUNT))
        }

        assertEquals(listOf(listOf("m-old", "m-twice")), idsAskedFor())
        assertEquals(mapOf((ACCOUNT to "m-twice") to before), cache.toMap())
        assertEquals(emptyList<List<String>>(), insertCalls.toList())
        assertEquals(emptyList<Refiled>(), refileCalls.toList())
    }

    @Test fun `no cursor moves and no folder counter is touched`() {
        serve(message("m-old", mapOf("work" to true)))

        backfill()

        assertEquals("work", folderOf("m-old"))
        assertEquals(emptyList<String>(), cursorWrites.toList())
    }

    private fun receivedAgo(ageMs: Long): String = Instant.ofEpochMilli(System.currentTimeMillis() - ageMs).toString()

    private fun notificationRead(): NotifyRead = runBlocking { repository.notifyRead(ACCOUNT, "inbox") }

    @Test fun `a backfilled inbox row received within the day stays a notification candidate`() {
        serve(message("m-recent", mapOf("inbox" to true), receivedAt = receivedAgo(23 * HOUR_MS)))

        backfill()

        assertEquals("inbox", folderOf("m-recent"))
        assertEquals(listOf("m-recent"), notificationRead().emails.map { it.id })
    }

    @Test fun `a backfilled inbox row older than a day is remembered by the notification pass and never offered to it`() {
        // What a folder sync wrote: the row a pass is there to announce, and the proof the read works.
        cache[ACCOUNT to "m-arrived"] =
            Email(id = "m-arrived", receivedAt = receivedAgo(HOUR_MS)).toEntity(ACCOUNT, "inbox")
        serve(message("m-old", mapOf("inbox" to true), receivedAt = receivedAgo(25 * HOUR_MS)))

        backfill()
        val read = notificationRead()

        assertEquals("inbox", folderOf("m-old"))
        assertEquals(listOf("m-arrived"), read.emails.map { it.id })
        assertEquals(setOf("m-arrived", "m-old"), read.baselineIds.toSet())
        // A folder with no recorded pass has an empty baseline and no floor: whatever the read
        // offers is announced.
        val floor = notifyFloor(lastPassMs = 0L)
        assertEquals(listOf("m-arrived"), read.emails.filter { announceableAt(it.receivedAt, floor) }.map { it.id })
    }

    @Test fun `a backfilled inbox row with no readable date is never offered to the notification pass`() {
        serve(message("m-undated", mapOf("inbox" to true), receivedAt = null))

        backfill()
        val read = notificationRead()

        assertEquals("inbox", folderOf("m-undated"))
        assertEquals(emptyList<String>(), read.emails.map { it.id })
        assertEquals(listOf("m-undated"), read.baselineIds)
    }

    @Test fun `a row filed back under the inbox is never offered to the notification pass, whatever its age`() {
        val ages = mapOf("r-recent" to receivedAgo(2 * HOUR_MS), "r-old" to receivedAgo(25 * HOUR_MS), "r-undated" to null)
        ages.forEach { (id, at) -> cache[ACCOUNT to id] = Email(id = id, receivedAt = at).toEntity(ACCOUNT, "trash") }
        serve(*ages.map { (id, at) -> message(id, mapOf("inbox" to true, "trash" to true), receivedAt = at) }.toTypedArray())

        backfill()
        val read = notificationRead()

        assertEquals(listOf("inbox", "inbox", "inbox"), ages.keys.map { folderOf(it) })
        assertEquals(emptyList<String>(), read.emails.map { it.id })
        assertEquals(ages.keys, read.baselineIds.toSet())
    }

    @Test fun `an unread row the pass meets already cached stays a notification candidate`() {
        cache[ACCOUNT to "c-recent"] =
            Email(id = "c-recent", receivedAt = receivedAgo(2 * HOUR_MS)).toEntity(ACCOUNT, "inbox")
        cache[ACCOUNT to "c-old"] =
            Email(id = "c-old", receivedAt = receivedAgo(72 * HOUR_MS)).toEntity(ACCOUNT, "inbox")
        serve(
            message("c-recent", mapOf("inbox" to true), receivedAt = receivedAgo(2 * HOUR_MS)),
            message("c-old", mapOf("inbox" to true), receivedAt = receivedAgo(72 * HOUR_MS)),
            message("m-old", mapOf("inbox" to true), receivedAt = receivedAgo(96 * HOUR_MS)),
        )

        backfill()

        assertEquals("the pass ran and read only what it lacked", listOf(listOf("m-old")), idsAskedFor())
        assertEquals(listOf("c-recent", "c-old"), notificationRead().emails.map { it.id })
    }

    @Test fun `an account already signed out asks the server nothing`() {
        serve(message("m-old", mapOf("work" to true)))
        configured.clear()

        try {
            backfill()
            fail("the pass ran for an account that is not configured")
        } catch (gone: AccountGoneException) {
            assertTrue("thrown: ${gone.message}", gone.message.orEmpty().contains(ACCOUNT))
        }

        assertEquals(0, server.requestCount)
        assertEquals(emptyList<List<String>>(), insertCalls.toList())
    }

    /**
     * Text, as a last resort: Room is not on this classpath, so what the annotation generates
     * cannot be run, and no test here can put a row in flight.
     */
    @Test fun `the writes are the conflict-ignoring insert and the folder change, of rows spared in flight`() {
        val dao = sourceOf("core/data/src/main/kotlin/app/sterna/core/data/db/EmailDao.kt").lines().map { it.trim() }
        val declaration = dao.indexOf("suspend fun insertAllIgnoring(emails: List<EmailEntity>)")
        assertTrue("EmailDao.insertAllIgnoring is no longer declared as this test reads it", declaration > 0)
        assertEquals("@Insert(onConflict = OnConflictStrategy.IGNORE)", dao[declaration - 1])

        val body = DaoQuerySource.mailFunctionBody("MailRepository", "backfillUnread").lines().map { it.trim() }
        val spared = body.indexOf("val fresh = sparingInFlight(rows)")
        val written = body.indexOf("emailDao.insertAllIgnoring(fresh)")
        assertTrue("the rows are no longer spared in flight before they are written:\n$body", spared in 0 until written)
        val refiledSpared = body.indexOf("val refiled = sparingInFlight(misfiled)")
        val refiled = body.indexOf(
            "refiled.forEach { emailDao.refile(credentials.id, it.id, plan.misfiled.getValue(it.id), it.mailboxId) }",
        )
        assertTrue("the rows are no longer spared in flight before they are re-filed:\n$body", refiledSpared in 0 until refiled)
        assertEquals(
            "this path reads the cached folders, writes through these two calls, and calls nothing else",
            listOf("emailDao.cachedFoldersOf(", "emailDao.insertAllIgnoring(", "emailDao.refile("),
            Regex("""emailDao\.\w+\(""").findAll(body.joinToString("\n")).map { it.value }.toList(),
        )
    }

    /** `EmailDao.refile` as the shipped DAO states it, over the columns it names and two it must not write. */
    @Test fun `the folder change writes one column of one account's row, and only from the folder it was read under`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use {
                it.executeUpdate(
                    "CREATE TABLE `emails` (`accountId` TEXT NOT NULL, `id` TEXT NOT NULL, `mailboxId` TEXT NOT NULL, " +
                        "`subject` TEXT, `seen` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))",
                )
                it.executeUpdate(
                    "INSERT INTO emails VALUES ('A', 'm', 'trash', 'kept', 0), ('B', 'm', 'trash', 'sibling', 0), " +
                        "('A', 'n', 'inbox', 'elsewhere', 1)",
                )
            }
            val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("refile"))
            fun refile(accountId: String, id: String, from: String, to: String): Int {
                val bound = mapOf("accountId" to accountId, "id" to id, "fromMailboxId" to from, "toMailboxId" to to)
                return db.prepareStatement(sql).use { st ->
                    order.forEachIndexed { i, name -> st.setString(i + 1, bound.getValue(name)) }
                    st.executeUpdate()
                }
            }
            fun table(): List<String> = db.createStatement().use { st ->
                st.executeQuery("SELECT * FROM emails ORDER BY accountId, id").use { rs ->
                    buildList { while (rs.next()) add((1..5).joinToString("|") { rs.getString(it) }) }
                }
            }

            assertEquals(1, refile("A", "m", from = "trash", to = "work"))
            assertEquals(0, refile("A", "n", from = "trash", to = "work"))
            assertEquals(0, refile("A", "absent", from = "trash", to = "work"))

            assertEquals(listOf("A|m|work|kept|0", "A|n|inbox|elsewhere|1", "B|m|trash|sibling|0"), table())
        }
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, relative.substringAfter("core/data/")).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}

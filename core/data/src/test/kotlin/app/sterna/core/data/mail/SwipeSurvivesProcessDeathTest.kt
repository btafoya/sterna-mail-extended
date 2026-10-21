package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxEntity
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.PurgeSnapshotDao
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.settings.SettingsRepository
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/**
 * A swipe evicts its row before the server write goes out, and the `finally` that ends the flight
 * does not run when the process is killed in between. The next start resumes from the persisted
 * JMAP cursors (#17), and a delta only fetches what the cache holds or the server reports added:
 * the evicted message, still in its folder on the server, would never come back.
 *
 * Executed, not read. Two or three [MailRepository] instances are built in turn over one "disk":
 * the cache rows, the cursor store, the marker store and the folder counters outlive each instance,
 * and dropping an instance without `endRemoval` is the kill. The sync runs `refresh` against a
 * [MockWebServer] that answers the delta as unchanged, so only the full query can bring a row back.
 *
 * The same stores run the flights that end in this process but not cleanly: a `beginRemoval`
 * cancelled part way, and an `endRemoval` that throws. Neither may leave a key in flight, since that
 * key hides the message from every page read until the process dies.
 *
 * Not covered here: the IMAP re-read (its settle call is pinned by [ImapWindowWiringTest]), the
 * marker `forfeitInFlight` erases on a `notFound`, and [FlightMarkerPrefs] itself, which needs a
 * Context (its two write lines are pinned below).
 */
class SwipeSurvivesProcessDeathTest {

    private class MemoryCursorStore : SyncCursorStore {
        val disk = linkedMapOf<String, Pair<String, String>>()
        override fun save(key: String, queryState: String, emailState: String) { disk[key] = queryState to emailState }
        override fun load(key: String): Pair<String, String>? = disk[key]
        override fun remove(key: String) { disk.remove(key) }
        override fun clear() { disk.clear() }
        override fun keys(): Set<String> = disk.keys.toSet()
    }

    /** Records its writes into the same list as the email DAO, so their order can be read. */
    private inner class MemoryMarkerStore : FlightMarkerStore {
        val disk = linkedMapOf<String, FlightMarker>()
        var afterWrite: () -> Unit = {}
        var eraseFails = false
        val readOn = mutableListOf<Thread>()
        override fun write(markers: List<FlightMarker>) {
            calls += "writeMarker(${markers.joinToString { it.emailId }})"
            markers.forEach { disk[flightMarkerKey(it.accountId, it.emailId)] = it }
            afterWrite()
        }
        override fun erase(accountId: String, emailIds: List<String>) {
            calls += "eraseMarker(${emailIds.joinToString()})"
            check(!eraseFails) { "disk full" }
            emailIds.forEach { disk.remove(flightMarkerKey(accountId, it)) }
        }
        override fun all(): List<FlightMarker> {
            readOn += Thread.currentThread()
            return disk.values.toList()
        }

        /** A marker a previous process life left behind, written without a trace in [calls]. */
        fun leftBehind(marker: FlightMarker) {
            disk[flightMarkerKey(marker.accountId, marker.emailId)] = marker
        }
    }

    private val calls = mutableListOf<String>()
    private val wire = mutableListOf<String>()

    private val cached = linkedMapOf<String, EmailEntity>()
    private val cursors = MemoryCursorStore()
    private val markers = MemoryMarkerStore()
    private val folderCounts = mutableMapOf<String, Pair<Int, Int>>()
    private var configured = listOf("acc")

    /** The inbox on the server, newest first. */
    private val serverInbox = mutableListOf<String>()

    /** The Archive on the server, newest first. */
    private val serverArchive = mutableListOf<String>()

    /** The server lists a Trash folder, by its role. */
    private var serverHasTrash = false

    /** The `update` argument of every `Email/set` received, in order. */
    private val emailSets = mutableListOf<JsonObject>()

    /** An `Email/set` that changes a message's folders is answered `notUpdated` for each of its ids. */
    private var refuseMoves = false

    /** The full query lists the inbox's ids and its chained `Email/get` returns none of them. */
    private var muteGet = false

    /** The core capability's `maxObjectsInGet`, which sizes the full query's pages; null announces none. */
    private var maxObjectsInGet: Int? = null

    /** The first `Email/query` anchored on [first] finds [second] gone from the folder, and answers
     *  `anchorNotFound`: the walk then resumes by position. */
    private var vanishOnAnchor: Pair<String, List<String>>? = null

    /** A DAO write that lands, then holds its caller on [gate]: the cancellation of a Room call
     *  whose transaction had committed. [reached] completes once the write is in. */
    private class Stall {
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
    }

    private var stallEviction: Stall? = null
    private var stallCounts: Stall? = null

    /** [write], then the body of the suspend DAO method the proxy answers, stalled on [stall]. */
    @Suppress("UNCHECKED_CAST")
    private fun landThenStall(stall: Stall?, args: Array<out Any?>, write: () -> Unit): Any? {
        write()
        if (stall == null) return Unit
        val body: suspend () -> Any? = {
            stall.reached.complete(Unit)
            stall.gate.await()
            Unit
        }
        return body.startCoroutineUninterceptedOrReturn(args.last() as Continuation<Any?>)
    }

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    wire += "session"
                    return json(sessionBody())
                }
                val call = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray[0].jsonArray
                val method = call[0].jsonPrimitive.content
                wire += method
                return when (method) {
                    "Mailbox/get" -> json(
                        """{"methodResponses":[["Mailbox/get",{"accountId":"acc1","state":"m1","list":[""" +
                            """{"id":"inbox","name":"Inbox","totalEmails":${serverInbox.size},"unreadEmails":${serverInbox.size}},""" +
                            """{"id":"archive","name":"Archive","totalEmails":${serverArchive.size},"unreadEmails":${serverArchive.size}}""" +
                            (if (serverHasTrash) """,{"id":"trash","name":"Trash","role":"trash","totalEmails":0,"unreadEmails":0}""" else "") +
                            """],"notFound":[]},"c0"]]}""",
                    )
                    "Email/queryChanges" -> json(
                        """{"methodResponses":[["Email/queryChanges",{"accountId":"acc1","oldQueryState":"q1",""" +
                            """"newQueryState":"q1","removed":[],"added":[]},"qc0"]]}""",
                    )
                    "Email/changes" -> json(
                        """{"methodResponses":[["Email/changes",{"accountId":"acc1","oldState":"e1","newState":"e1",""" +
                            """"created":[],"updated":[],"destroyed":[],"hasMoreChanges":false},"c0"]]}""",
                    )
                    "Email/query" -> {
                        val args = call[1].jsonObject
                        val folder = if (args["filter"]!!.jsonObject["inMailbox"]!!.jsonPrimitive.content == "archive") serverArchive else serverInbox
                        val anchor = args["anchor"]?.jsonPrimitive?.content
                        val lost = vanishOnAnchor?.takeIf { it.first == anchor }
                        if (lost != null) {
                            vanishOnAnchor = null
                            folder.removeAll(lost.second)
                            return json("""{"methodResponses":[["error",{"type":"anchorNotFound"},"q0"],["error",{"type":"resultReference"},"g0"]]}""")
                        }
                        val offset = args["anchorOffset"]?.jsonPrimitive?.content?.toInt() ?: 0
                        val position = args["position"]?.jsonPrimitive?.content?.toInt() ?: 0
                        val start = if (anchor != null) folder.indexOf(anchor) + offset else position
                        val limit = args["limit"]!!.jsonPrimitive.content.toInt()
                        val page = folder.drop(start).take(limit)
                        json(
                            """{"methodResponses":[["Email/query",{"accountId":"acc1","queryState":"q2","position":$start,""" +
                                """"ids":[${page.joinToString(",") { "\"$it\"" }}]},"q0"],""" +
                                """["Email/get",{"accountId":"acc1","state":"e2","list":[${if (muteGet) "" else page.joinToString(",") { emailJson(it) }}],"notFound":[]},"g0"]]}""",
                        )
                    }
                    "Email/set" -> {
                        val update = call[1].jsonObject["update"]!!.jsonObject
                        emailSets += update
                        val updated = update.keys
                        if (refuseMoves && update.values.any { "mailboxIds" in it.jsonObject }) {
                            return json(
                                """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"e1","newState":"e1",""" +
                                    """"notUpdated":{${updated.joinToString(",") { "\"$it\":{\"type\":\"forbidden\"}" }}}},"s0"]]}""",
                            )
                        }
                        json(
                            """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"e1","newState":"e3",""" +
                                """"updated":{${updated.joinToString(",") { "\"$it\":null" }}}},"s0"]]}""",
                        )
                    }
                    "Email/get" -> {
                        val asked = call[1].jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                        val (found, missing) = asked.partition { it in serverInbox }
                        json(
                            """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"e1","list":[""" +
                                found.joinToString(",") { emailJson(it) } + """],"notFound":[""" +
                                missing.joinToString(",") { "\"$it\"" } + """]},"g0"]]}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun sessionBody(): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":{${maxObjectsInGet?.let { "\"maxObjectsInGet\":$it" }.orEmpty()}},"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${server.url("/jmap/api")}","state":"sess-1"}
        """.trimIndent()

    private fun emailJson(id: String): String =
        """{"id":"$id","threadId":"t-$id","subject":"Subject $id","preview":"Preview",""" +
            """"receivedAt":"2026-09-01T10:00:00Z","from":[{"name":"Alex","email":"alex@example.test"}],""" +
            """"keywords":{},"hasAttachment":false}"""

    private fun row(id: String, accountId: String = "acc", mailboxId: String = "inbox") = EmailEntity(
        id = id,
        accountId = accountId,
        mailboxId = mailboxId,
        threadId = "t-$id",
        subject = "Subject $id",
        preview = "Preview",
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = "Alex",
        fromEmail = "alex@example.test",
        seen = false,
        flagged = false,
        hasAttachment = false,
        sortKey = 1_756_720_800_000,
    )

    private fun cache(vararg rows: EmailEntity) = rows.forEach { cached["${it.accountId}/${it.id}"] = it }

    private fun inboxIds(accountId: String = "acc"): List<String> =
        cached.values.filter { it.accountId == accountId && it.mailboxId == "inbox" }.map { it.id }.sorted()

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "emailsByIds" -> {
                check(args.size == 3) { "only the account-scoped emailsByIds is expected here" }
                val accountId = args[0] as String
                val ids = args[1] as List<String>
                calls += "emailsByIds($ids)"
                ids.mapNotNull { cached["$accountId/$it"] }
            }
            "idsForMailbox" -> {
                val accountId = args[0] as String
                val mailboxId = args[1] as String
                cached.values.filter { it.accountId == accountId && it.mailboxId == mailboxId }.map { it.id }
            }
            "evictFromCacheKeepingIndex" -> {
                val accountId = args[0] as String
                val ids = args[1] as List<String>
                calls += "evictFromCacheKeepingIndex($ids)"
                landThenStall(stallEviction, args) { ids.forEach { cached.remove("$accountId/$it") } }
            }
            "setSeen" -> {
                val key = "${args[0]}/${args[1]}"
                val seen = args[2] as Boolean
                val before = cached[key]
                if (before == null || before.seen == seen) {
                    0
                } else {
                    cached[key] = before.copy(seen = seen)
                    1
                }
            }
            "mailboxOf" -> cached["${args[0]}/${args[1]}"]?.mailboxId
            "deleteById" -> {
                cached.remove("${args[0]}/${args[1]}")
                Unit
            }
            "deleteByIds" -> {
                val accountId = args[0] as String
                (args[1] as List<String>).forEach { cached.remove("$accountId/$it") }
                Unit
            }
            "upsertAll" -> {
                val rows = args[0] as List<EmailEntity>
                calls += "upsertAll(${rows.map { it.id }})"
                rows.forEach { cached["${it.accountId}/${it.id}"] = it }
                Unit
            }
            "markWalked" -> 0
            "reconcileMailbox" -> {
                val accountId = args[0] as String
                val mailboxId = args[1] as String
                val keep = args[2] as Set<String>
                val spare = args[3] as List<String>
                val evictable = args[4] as Set<String>?
                cached.values
                    .filter { it.accountId == accountId && it.mailboxId == mailboxId }
                    .filter { it.id !in keep && it.id !in spare && (evictable == null || it.id in evictable) }
                    .forEach { cached.remove("${it.accountId}/${it.id}") }
                Unit
            }
            else -> error("EmailDao.${method.name} was called by a path this test does not model.")
        }
    } as EmailDao

    @Suppress("UNCHECKED_CAST")
    private fun mailboxDao(): MailboxDao = Proxy.newProxyInstance(
        MailboxDao::class.java.classLoader,
        arrayOf(MailboxDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "adjustCounts" -> {
                val key = "${args[0]}/${args[1]}"
                landThenStall(stallCounts, args) {
                    val (total, unread) = folderCounts[key] ?: (0 to 0)
                    folderCounts[key] = (total + args[2] as Int) to (unread + args[3] as Int)
                }
            }
            "replaceAll" -> {
                (args[1] as List<MailboxEntity>).forEach {
                    folderCounts["${it.accountId}/${it.id}"] = it.totalEmails to it.unreadEmails
                }
                Unit
            }
            else -> error("MailboxDao.${method.name} was not expected here.")
        }
    } as MailboxDao

    private val accountStore = object : RecordingAccountStorage() {
        override fun accounts(): List<StoredAccount> =
            configured.map { StoredAccount(id = it, server = "unused", username = "alex") }

        override fun account(id: String): StoredAccount? = accounts().firstOrNull { it.id == id }
    }

    /** Answers the one write every mover starts with, withdrawing its message from a pending purge. */
    private fun purgeSnapshotDao(): PurgeSnapshotDao = Proxy.newProxyInstance(
        PurgeSnapshotDao::class.java.classLoader,
        arrayOf(PurgeSnapshotDao::class.java),
    ) { _, method, _ ->
        if (method.name != "unlistEmails") error("PurgeSnapshotDao.${method.name} was not expected here.")
        Unit
    } as PurgeSnapshotDao

    /** A [SettingsRepository] with mark-read-on-delete on. Its constructor wants a Context, so it
     *  is allocated without one and the flow `delete` reads is planted in its field. */
    private fun markingReadOnDelete(): SettingsRepository {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val settings = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, SettingsRepository::class.java) as SettingsRepository
        SettingsRepository::class.java.getDeclaredField("markReadOnDelete")
            .apply { isAccessible = true }
            .set(settings, flowOf(true))
        return settings
    }

    /** A process life: everything it holds in memory dies with it, the four stores above do not. */
    private fun repository(settings: SettingsRepository? = null): MailRepository = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = mailboxDao(),
        imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), accountStore)),
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        snoozedDao = unusableDao<SnoozedDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        accountStore = accountStore,
        outboxDao = unusableDao<OutboxDao>(),
        purgeSnapshotDao = purgeSnapshotDao(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        serverContactDao = unusableDao<app.sterna.core.data.db.ServerContactDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
        settings = settings,
        syncStateStore = cursors,
        flightMarkerStore = markers,
    )

    private fun credentials(): AccountCredentials = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.JMAP,
        authType = AuthType.BASIC,
    )

    /**
     * `refresh`, tolerating one failure only. The delta branch ends on the ghost sweep's schedule,
     * whose default clock is `SystemClock.elapsedRealtime`, a stub that throws "not mocked" here.
     * Every write of that branch (eviction, fetch, cursor) has happened by then and `refresh` still
     * writes the folder rows before rethrowing, so a delta pass is read off the stores. The full
     * query reaches no stub and must return.
     */
    private fun sync(repository: MailRepository, mailboxId: String = "inbox"): MailboxMeta? {
        val result = runBlocking { runCatching { repository.refresh(credentials(), mailboxId) } }
        result.exceptionOrNull()?.let { if ("not mocked" !in it.message.orEmpty()) throw it }
        return result.getOrNull()
    }

    /** Two messages in the inbox, both cached, both counted, and a cursor from an earlier sync. */
    private fun syncedInbox() {
        serverInbox += listOf("m0", "m1")
        cache(row("m0"), row("m1"))
        folderCounts["acc/inbox"] = 2 to 2
        cursors.save("accinbox", "q1", "e1")
    }

    @Test fun `a swipe the process died under comes back from the server on the next start`() {
        syncedInbox()
        runBlocking { repository().beginRemoval("acc", "m1") }
        assertEquals(listOf("m0"), inboxIds())
        assertEquals(1 to 1, folderCounts["acc/inbox"])

        val meta = sync(repository())

        assertEquals(
            "the message is still in the inbox on the server and nobody moved it: the next start " +
                "must bring its row back, not leave it missing for as long as the cursors live",
            listOf("m0", "m1"),
            inboxIds(),
        )
        assertEquals(
            "the cursors were dropped before the first read, so the sync was a full query",
            listOf("session", "Mailbox/get", "Email/query"),
            wire,
        )
        assertEquals("the folder counts what the server counts again", 2 to 2, folderCounts["acc/inbox"])
        assertEquals(2, meta?.unreadCount)
        assertEquals("the full read settled the marker", emptyList<FlightMarker>(), markers.all())
        assertEquals("q2" to "e2", cursors.load("accinbox"))
    }

    @Test fun `a second kill that loses the dropped cursors finds the marker still there and drops them again`() {
        syncedInbox()
        runBlocking { repository().beginRemoval("acc", "m1") }
        val beforeTheDrop = LinkedHashMap(cursors.disk)

        // Second life: it looks at the disk (a swipe on nothing cached is enough) and dies before any sync.
        runBlocking { repository().beginRemoval("acc", "not-cached") }
        assertNull("the orphan's account lost its cursors", cursors.load("accinbox"))
        assertEquals(
            "the marker must outlive the drop: the drop is an apply() and this kill can lose it",
            listOf(FlightMarker("acc", "m1", "inbox")),
            markers.all(),
        )
        cursors.disk.clear()
        cursors.disk.putAll(beforeTheDrop)

        sync(repository())

        assertEquals(listOf("m0", "m1"), inboxIds())
        assertEquals(listOf("session", "Mailbox/get", "Email/query"), wire)
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `a swipe whose write had landed stays gone, and the full read settles its marker`() {
        syncedInbox()
        runBlocking { repository().beginRemoval("acc", "m1") }
        serverInbox.remove("m1")

        sync(repository())

        assertEquals(listOf("m0"), inboxIds())
        assertEquals(listOf("session", "Mailbox/get", "Email/query"), wire)
        assertEquals(1 to 1, folderCounts["acc/inbox"])
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `an orphan drops the cursors of its own account only, not of an account its id prefixes`() {
        configured = listOf("a", "ab", "b")
        cursors.save("ainbox", "q1", "e1")
        cursors.save("abinbox", "q1", "e1")
        cursors.save("binbox", "q1", "e1")
        markers.leftBehind(FlightMarker("a", "m9", "inbox"))
        markers.leftBehind(FlightMarker("signed-out", "m8", "inbox"))

        runBlocking { repository().beginRemoval("b", "not-cached") }

        assertEquals(setOf("abinbox", "binbox"), cursors.keys())
        assertEquals(
            "an orphan of an account no longer configured has nothing to recover and is erased; " +
                "the other waits for its folder to be read whole",
            listOf(FlightMarker("a", "m9", "inbox")),
            markers.all(),
        )
    }

    @Test fun `a flight that ends either way leaves no marker, and the marker is down before the eviction`() {
        cache(row("m1"), row("m2"))
        folderCounts["acc/inbox"] = 2 to 2
        val repository = repository()

        val refused = runBlocking { repository.beginRemoval("acc", "m1") }!!
        assertEquals(
            listOf("emailsByIds([m1])", "writeMarker(m1)", "evictFromCacheKeepingIndex([m1])"),
            calls,
        )
        calls.clear()
        runBlocking { repository.endRemoval(refused, restore = true) }
        assertEquals(
            "erased after the put-back: erased first, a kill in between is the lost row again",
            listOf("upsertAll([m1])", "eraseMarker(m1)"),
            calls,
        )

        val landed = runBlocking { repository.beginRemoval("acc", "m2") }!!
        runBlocking { repository.endRemoval(landed, restore = false) }

        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `the flight of this process is no orphan, and a sync while it is out leaves its marker alone`() {
        syncedInbox()
        val repository = repository()
        runBlocking { repository.beginRemoval("acc", "m1") }

        sync(repository)

        assertEquals(
            "its own swipe must not drop the cursors",
            listOf("session", "Mailbox/get", "Email/queryChanges", "Email/changes"),
            wire,
        )
        assertEquals(listOf(FlightMarker("acc", "m1", "inbox")), markers.all())
    }

    @Test fun `a full page naming the rows a batch holds writes none of them back`() {
        syncedInbox()
        // No cursors: the next sync is a full query, the one branch that writes whole pages back.
        cursors.disk.clear()
        val repository = repository()
        runBlocking { repository.beginRemovalAll("acc", listOf("m0", "m1")) }

        sync(repository)

        assertEquals(listOf("session", "Mailbox/get", "Email/query"), wire)
        assertEquals(
            "the batch's keys live in the same map as a single swipe's, which is the only thing " +
                "`sparingInFlight` reads: held in a map of its own, the page would put all ten " +
                "lines back on screen a second after the finger took them off",
            emptyList<String>(),
            inboxIds(),
        )
        assertEquals(
            listOf(FlightMarker("acc", "m0", "inbox"), FlightMarker("acc", "m1", "inbox")),
            markers.all(),
        )
    }

    @Test fun `an orphan swiped again belongs to the new flight, and the full read leaves that marker`() {
        syncedInbox()
        cached.remove("acc/m1")
        markers.leftBehind(FlightMarker("acc", "m1", "inbox"))
        val repository = repository()
        runBlocking { repository.beginRemoval("acc", "not-cached") }
        cache(row("m1"))
        runBlocking { repository.beginRemoval("acc", "m1") }

        sync(repository)

        assertEquals(listOf("session", "Mailbox/get", "Email/query"), wire)
        assertEquals("the page naming it is spared while it flies", listOf("m0"), inboxIds())
        assertEquals(listOf(FlightMarker("acc", "m1", "inbox")), markers.all())
    }

    @Test fun `a marker key reads back as the pair that wrote it, whatever the ids hold`() {
        listOf("1" to "12:x", "12" to ":x", "acc" to "INBOX/Sub:folder:42").forEach { (account, email) ->
            assertEquals(
                FlightMarker(account, email, "f"),
                parseFlightMarker(flightMarkerKey(account, email), "f"),
            )
        }
        assertNull(parseFlightMarker("no-length-here", "f"))
        assertNull(parseFlightMarker("9:short", "f"))
    }

    @Test fun `the preferences store writes and erases with commit`() {
        // Text, for want of a Context in this module: an apply() still queued at the kill is lost,
        // and that kill is the only reason the store exists.
        val lines = DaoQuerySource.mailSource("FlightMarkerPrefs").lines().map { it.trim() }
        listOf(
            "markers.forEach { edit.putString(flightMarkerKey(it.accountId, it.emailId), it.mailboxId) }",
            "emailIds.forEach { edit.remove(flightMarkerKey(accountId, it)) }",
            "private val prefs = context.getSharedPreferences(\"swipes_in_flight\", Context.MODE_PRIVATE)",
        ).forEach { assertTrue("FlightMarkerPrefs no longer carries the line:\n  $it", it in lines) }
        assertEquals(emptyList<String>(), lines.filter { "apply()" in it && !it.startsWith("//") })
        val commits = lines.filter { "edit.commit()" in it && !it.startsWith("//") }
        // Two, one per write function: a third would be a commit inside a loop, which is the
        // synchronous disk write per message a bulk action must not pay.
        assertEquals("FlightMarkerPrefs must put a whole batch down in one commit()", 2, commits.size)
        // The write hands its verdict to the decision; the erase does not, and the asymmetry is the
        // point: a marker still on disk is an orphan the next start settles, a marker missing from
        // it is a row nothing brings back.
        assertEquals(
            "the marker write must hand its verdict to flightMarkersCommitted",
            1,
            commits.count { it.startsWith("flightMarkersCommitted(edit.commit()) {") },
        )
        assertEquals(
            "and the erase must not: a failed erase heals itself at the next start",
            1,
            commits.count { it.startsWith("if (!edit.commit())") },
        )
    }

    @Test fun `a refused marker write is raised as its own kind, and one that landed returns`() {
        // The decision the write above hands its verdict to, executed. `commit()` returning false
        // is a disk that refused the write: logged and returned from, the rows would be evicted
        // with no marker behind them and one kill would take them away for good.
        val thrown = runCatching { flightMarkersCommitted(false) { "not written: acc/m1" } }.exceptionOrNull()
        assertEquals("swipe markers not written: acc/m1", thrown?.message)
        // Its own kind, because `beginRemoval` answers this one refusal with no flight and lets
        // every other throwable out: caught by message or by `Throwable`, a database failure would
        // be swallowed with it.
        assertTrue("a refused write must be a FlightMarkersRefused, not a bare state error", thrown is FlightMarkersRefused)
        // The inverse witness: "always throw" would refuse every swipe there is.
        flightMarkersCommitted(true) { "never read" }
    }

    @Test fun `a full query that fetched none of the ids it listed leaves the orphan for a whole read`() {
        syncedInbox()
        cached.remove("acc/m1")
        markers.leftBehind(FlightMarker("acc", "m1", "inbox"))
        muteGet = true

        sync(repository())

        assertEquals(
            "a muted walk reconciled nothing and wrote no row: erasing the orphan here is the " +
                "message missing for good, since the cursors it saved send every later sync down the delta",
            listOf(FlightMarker("acc", "m1", "inbox")),
            markers.all(),
        )
        muteGet = false
        wire.clear()

        sync(repository())

        assertEquals(listOf("m0", "m1"), inboxIds())
        assertEquals(listOf("session", "Mailbox/get", "Email/query"), wire)
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    /** `beginRemoval` of m1 in a process life that is dropped before its flight ends, then the next life. */
    private fun killedMidSwipe(): MailRepository {
        runBlocking { repository().beginRemoval("acc", "m1") }
        assertTrue("the swipe evicted m1", "m1" !in inboxIds())
        wire.clear()
        return repository()
    }

    private fun emailCalls(): List<String> = wire.filter { it.startsWith("Email/") }.distinct()

    @Test fun `after a cold start, a walk that ran to its end over several pages writes the orphan back`() {
        syncedInbox()
        maxObjectsInGet = 1
        val relaunched = killedMidSwipe()

        sync(relaunched)

        assertEquals(listOf("Email/query"), emailCalls())
        assertEquals("the page that names m1 writes it", listOf("m0", "m1"), inboxIds())
        assertEquals(2 to 2, folderCounts["acc/inbox"])
        assertEquals(emptyList<FlightMarker>(), markers.all())
        wire.clear()

        sync(relaunched)

        assertEquals("the pull after it resumes from the cursors", listOf("Email/queryChanges", "Email/changes"), emailCalls())
        assertEquals(listOf("m0", "m1"), inboxIds())
    }

    @Test fun `after a cold start, a walk resumed by position writes the orphan its later page names`() {
        syncedInbox()
        serverInbox.add(0, "x0")
        cache(row("x0"))
        maxObjectsInGet = 1
        vanishOnAnchor = "x0" to listOf("x0")
        val relaunched = killedMidSwipe()

        sync(relaunched)

        assertEquals(listOf("Email/query"), emailCalls())
        assertEquals(null, vanishOnAnchor)
        assertTrue(
            "a resumed walk reconciles nothing, but the page it read after resuming still names m1 " +
                "and must write it: ${inboxIds()}",
            "m1" in inboxIds(),
        )
        assertEquals("as `Mailbox/get` counted it, before x0 left", 3 to 3, folderCounts["acc/inbox"])
        wire.clear()

        sync(relaunched)

        assertTrue("the pull after it keeps m1: ${inboxIds()}", "m1" in inboxIds())
    }

    @Test fun `after a cold start, a walk resumed past the orphan leaves the next pull of that life to write it`() {
        serverInbox += listOf("x0", "x1", "m1", "m0")
        cache(row("m0"), row("m1"))
        folderCounts["acc/inbox"] = 2 to 2
        cursors.save("accinbox", "q1", "e1")
        maxObjectsInGet = 1
        // Both rows above the anchor leave at once: resuming one row behind the count lands past m1.
        vanishOnAnchor = "x1" to listOf("x0", "x1")
        val relaunched = killedMidSwipe()

        sync(relaunched)

        assertEquals(listOf("Email/query"), emailCalls())
        assertEquals(null, vanishOnAnchor)
        assertTrue("the resumed walk skipped m1, so this pass could not write it", "m1" !in inboxIds())
        assertEquals(listOf(FlightMarker("acc", "m1", "inbox")), markers.all())
        wire.clear()

        sync(relaunched)

        assertEquals(
            "the second pull of the same process life must bring m1 back: a delta resumed from the " +
                "cursors that walk saved fetches only what the cache holds or the server reports added " +
                "(calls: ${emailCalls()})",
            listOf("m0", "m1"),
            inboxIds(),
        )
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `after a cold start, a muted walk leaves the next pull of that life to write the orphan`() {
        syncedInbox()
        val relaunched = killedMidSwipe()
        muteGet = true

        sync(relaunched)

        assertEquals(listOf("Email/query"), emailCalls())
        assertTrue("a muted walk writes nothing", "m1" !in inboxIds())
        muteGet = false
        wire.clear()

        sync(relaunched)

        assertEquals(
            "the second pull of the same process life must bring m1 back: a delta resumed from the " +
                "cursors the muted walk saved fetches only what the cache holds or the server reports added " +
                "(calls: ${emailCalls()})",
            listOf("m0", "m1"),
            inboxIds(),
        )
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `after a cold start, a first sync of another folder leaves the source folder to a full query`() {
        syncedInbox()
        serverArchive += "a0"
        cache(row("a0", mailboxId = "archive"))
        cursors.save("accarchive", "q1", "e1")
        val relaunched = killedMidSwipe()

        sync(relaunched, "archive")

        assertEquals("the settle dropped the archive's cursor too", listOf("Email/query"), emailCalls())
        assertNull("and the inbox's", cursors.load("accinbox"))
        assertEquals(listOf("m0"), inboxIds())
        assertEquals("reading Archive settles nothing of the inbox", listOf(FlightMarker("acc", "m1", "inbox")), markers.all())
        wire.clear()

        sync(relaunched)

        assertEquals(listOf("Email/query"), emailCalls())
        assertEquals(listOf("m0", "m1"), inboxIds())
        assertEquals(2 to 2, folderCounts["acc/inbox"])
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `a whole read of the inbox leaves the orphan of another folder`() {
        syncedInbox()
        markers.leftBehind(FlightMarker("acc", "m7", "archive"))

        sync(repository())

        assertEquals(listOf("session", "Mailbox/get", "Email/query"), wire)
        assertEquals(
            "reading the inbox says nothing of a row evicted from Archive",
            listOf(FlightMarker("acc", "m7", "archive")),
            markers.all(),
        )
    }

    @Test fun `a whole read of one account leaves the orphan of another account with the same folder id`() {
        configured = listOf("acc", "other")
        syncedInbox()
        markers.leftBehind(FlightMarker("other", "m1", "inbox"))
        markers.leftBehind(FlightMarker("acc", "m1", "inbox"))
        cached.remove("acc/m1")

        sync(repository())

        assertEquals(listOf("m0", "m1"), inboxIds())
        assertEquals(
            "two accounts of one server share folder ids: this read wrote no row of `other`",
            listOf(FlightMarker("other", "m1", "inbox")),
            markers.all(),
        )
    }

    @Test fun `marking a message read does not read the marker file on the caller's thread`() {
        syncedInbox()
        markers.leftBehind(FlightMarker("acc", "m9", "inbox"))
        val caller = Thread.currentThread()
        val repository = repository()

        runBlocking { repository.setReadAll(credentials(), listOf("m0"), seen = true) }

        assertEquals(
            "`setRead` and `setFlagged` are launched on the main thread, and their cursor advance " +
                "must not be what first reads, and `commit()`s, the preferences file",
            emptyList<Thread>(),
            markers.readOn.filter { it === caller },
        )
        wire.clear()
        sync(repository)
        assertEquals(
            "the orphan still drops the cursor that the read advanced",
            listOf("Email/query"),
            wire.filter { it.startsWith("Email/") },
        )
    }

    /** Both messages in the inbox and cached, m1 with a subject the server no longer gives it, so a
     *  page that writes m1 can be told from a put-back. No cursor: the next sync is a full query. */
    private fun interruptedInbox() {
        serverInbox += listOf("m0", "m1")
        cache(row("m0"), row("m1").copy(subject = "Stale"))
        folderCounts["acc/inbox"] = 2 to 2
    }

    /** `beginRemoval("acc", "m1")`, cancelled once [stall] has been reached. */
    private fun cancelledBegin(repository: MailRepository, stall: Stall) = runBlocking {
        val flight = launch { repository.beginRemoval("acc", "m1") }
        stall.reached.await()
        flight.cancel()
        stall.gate.complete(Unit)
        flight.join()
    }

    @Test fun `a swipe cancelled while its marker was written leaves no flight and evicts nothing`() {
        interruptedInbox()
        val repository = repository()

        runBlocking {
            lateinit var flight: Job
            markers.afterWrite = { flight.cancel() }
            flight = launch { repository.beginRemoval("acc", "m1") }
            flight.join()
        }
        markers.afterWrite = {}
        val atCancel = calls.toList()
        val countsAtCancel = folderCounts["acc/inbox"]
        sync(repository)

        assertEquals("the next page must write m1, not be filtered against it", "Subject m1", cached["acc/m1"]?.subject)
        assertEquals(
            "a row never evicted is not written back",
            listOf("emailsByIds([m1])", "writeMarker(m1)", "eraseMarker(m1)"),
            atCancel,
        )
        assertEquals(2 to 2, countsAtCancel)
        assertEquals(emptyList<FlightMarker>(), markers.all())
    }

    @Test fun `a swipe cancelled once its row was evicted writes the row back and leaves no flight`() {
        interruptedInbox()
        val repository = repository()
        val stall = Stall().also { stallEviction = it }

        cancelledBegin(repository, stall)
        val rowAtCancel = cached["acc/m1"]
        val countsAtCancel = folderCounts["acc/inbox"]
        val markersAtCancel = markers.all()
        sync(repository)

        assertEquals("the next page must write m1, not be filtered against it", "Subject m1", cached["acc/m1"]?.subject)
        assertEquals("the held row went back in", "Stale", rowAtCancel?.subject)
        assertEquals("a folder never decounted is not credited", 2 to 2, countsAtCancel)
        assertEquals(emptyList<FlightMarker>(), markersAtCancel)
    }

    @Test fun `a swipe cancelled while its folder was decounted credits it back and leaves no flight`() {
        interruptedInbox()
        val repository = repository()
        val stall = Stall().also { stallCounts = it }

        cancelledBegin(repository, stall)
        val rowAtCancel = cached["acc/m1"]
        val countsAtCancel = folderCounts["acc/inbox"]
        val markersAtCancel = markers.all()
        sync(repository)

        assertEquals("the next page must write m1, not be filtered against it", "Subject m1", cached["acc/m1"]?.subject)
        assertEquals("the held row went back in", "Stale", rowAtCancel?.subject)
        assertEquals("decounted, then credited back", 2 to 2, countsAtCancel)
        assertEquals(emptyList<FlightMarker>(), markersAtCancel)
    }

    @Test fun `an end whose marker cannot be erased still lifts the key`() {
        interruptedInbox()
        val repository = repository()
        val flight = runBlocking { repository.beginRemoval("acc", "m1") }!!
        markers.eraseFails = true

        val thrown = runCatching { runBlocking { repository.endRemoval(flight, restore = true) } }.exceptionOrNull()
        markers.eraseFails = false
        sync(repository)

        assertEquals("disk full", thrown?.message)
        assertEquals(
            "a key left standing filters m1 out of every page for the life of the process",
            "Subject m1",
            cached["acc/m1"]?.subject,
        )
    }

    // ---- mark-read-on-delete, carried by the move itself (#252) -----------------------------

    /** [syncedInbox], on a server with a Trash, whose folder row starts empty. */
    private fun unreadInboxAndTrash() {
        syncedInbox()
        serverHasTrash = true
        folderCounts["acc/trash"] = 0 to 0
    }

    private val m1TrashedAndSeen = buildJsonObject {
        putJsonObject("m1") {
            putJsonObject("mailboxIds") { put("trash", true) }
            put("keywords/\$seen", true)
        }
    }

    @Test fun `a delete the server refuses has sent one write and leaves the message unread`() {
        unreadInboxAndTrash()
        refuseMoves = true
        val repository = repository(markingReadOnDelete())

        val refused = runBlocking { runCatching { repository.delete(credentials(), "m1") } }

        assertTrue("the refusal reaches the caller, which keeps its banner on it", refused.isFailure)
        assertEquals(
            "the flag travels in the move's own update. Sent ahead in a write of its own it lands " +
                "whatever becomes of the move, and the message stays in its folder, read",
            listOf(m1TrashedAndSeen),
            emailSets,
        )
        assertEquals(false, cached["acc/m1"]?.seen)
        assertEquals(2 to 2, folderCounts["acc/inbox"])
        assertEquals(0 to 0, folderCounts["acc/trash"])
    }

    @Test fun `a delete from the cache takes the message and its unread off the inbox once`() {
        unreadInboxAndTrash()
        val repository = repository(markingReadOnDelete())

        runBlocking { repository.delete(credentials(), "m1") }

        assertEquals(listOf(m1TrashedAndSeen), emailSets)
        assertEquals(listOf("m0"), inboxIds())
        assertEquals(1 to 1, folderCounts["acc/inbox"])
        assertEquals("the Trash gains a message the server holds read", 1 to 0, folderCounts["acc/trash"])
    }

    @Test fun `a delete of a message already read sends the move alone and takes no unread off the inbox`() {
        unreadInboxAndTrash()
        cache(row("m1").copy(seen = true))
        folderCounts["acc/inbox"] = 2 to 1
        val repository = repository(markingReadOnDelete())

        runBlocking { repository.delete(credentials(), "m1") }

        assertEquals(
            listOf(buildJsonObject { putJsonObject("m1") { putJsonObject("mailboxIds") { put("trash", true) } } }),
            emailSets,
        )
        assertEquals("m0 is still unread there", 1 to 1, folderCounts["acc/inbox"])
        assertEquals(1 to 0, folderCounts["acc/trash"])
    }

    @Test fun `a delete of a row in flight leaves the inbox as the flight left it`() {
        unreadInboxAndTrash()
        val repository = repository(markingReadOnDelete())
        val flight = runBlocking { repository.beginRemoval("acc", "m1") }!!
        assertEquals(1 to 1, folderCounts["acc/inbox"])

        runBlocking {
            repository.delete(credentials(), "m1")
            repository.endRemoval(flight, restore = false)
        }

        assertEquals(listOf(m1TrashedAndSeen), emailSets)
        assertEquals("the flight decounted the inbox, unread included", 1 to 1, folderCounts["acc/inbox"])
        assertEquals(1 to 0, folderCounts["acc/trash"])
    }

    @Test fun `a refused delete of a row in flight is put back unread`() {
        unreadInboxAndTrash()
        refuseMoves = true
        val repository = repository(markingReadOnDelete())
        val flight = runBlocking { repository.beginRemoval("acc", "m1") }!!

        runBlocking {
            assertTrue(runCatching { repository.delete(credentials(), "m1") }.isFailure)
            repository.endRemoval(flight, restore = true)
        }

        assertEquals(listOf(m1TrashedAndSeen), emailSets)
        assertFalse("the server never marked it read, so the row put back is unread", cached["acc/m1"]!!.seen)
        assertEquals(2 to 2, folderCounts["acc/inbox"])
        assertEquals(0 to 0, folderCounts["acc/trash"])
    }
}

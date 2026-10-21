package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
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
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two read marks of one unread message, both out on the network at once (the banner's "Mark as
 * read" while the reader marks it, on a slow link), RUN with both held at the server write and
 * then let go one after the other. The folder lost one unread message and its counter may drop
 * by one: each mark read "unread" before going out, and what the counter is owed is decided by
 * the cache write that lands, not by that earlier read.
 *
 * The [EmailDao] stand-in keeps a seen state per (account, id) and answers `setSeen` with the
 * rows really changed, as the conditional UPDATE does: answering 1 to every call would let a
 * counter that ignores the answer pass.
 */
class ReadMarkRaceCountsOnceTest {

    private val cached = linkedMapOf<String, EmailEntity>()

    private val store = object : RecordingAccountStorage() {
        override fun account(id: String): StoredAccount? =
            StoredAccount(id = id, server = "unused", username = "alex", protocol = protocol)
    }

    private var protocol = MailProtocol.IMAP

    private fun row(id: String, mailbox: String) = EmailEntity(
        id = id,
        accountId = ACCOUNT,
        mailboxId = mailbox,
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

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "emailsByIds" -> {
                val accountId = args[0] as String
                (args[1] as List<String>).mapNotNull { cached["$accountId/$it"] }
            }
            "mailboxOf" -> cached["${args[0]}/${args[1]}"]?.mailboxId
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
            else -> error("EmailDao.${method.name} was called by a path this test does not model.")
        }
    } as EmailDao

    private fun mailboxDao(): MailboxDao = Proxy.newProxyInstance(
        MailboxDao::class.java.classLoader,
        arrayOf(MailboxDao::class.java),
    ) { _, method, _ ->
        if (method.name != "adjustCounts") error("MailboxDao.${method.name} was not expected here.")
        Unit
    } as MailboxDao

    private fun repository(imap: ImapMailService): MailRepository = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = mailboxDao(),
        imap = imap,
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
    )

    /** The unread deltas the account store was handed, as (mailboxId, delta), in order. */
    private fun unreadDeltas(): List<Pair<String, Int>> =
        store.calls.filter { it.first == "adjustInboxUnread" }
            .map { it.second["mailboxId"] as String to it.second["delta"] as Int }

    // ---- IMAP -------------------------------------------------------------------------------

    /** Each `setFlag` announces itself on [arrived] and waits for the gate it is handed, in arrival order. */
    private class HeldImap : ImapMailService(ImapClient(), SmtpClient(), unusedTokenRefresher()) {
        val arrived = LinkedBlockingQueue<CompletableDeferred<Unit>>()
        override suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) {
            val gate = CompletableDeferred<Unit>()
            arrived.put(gate)
            gate.await()
        }
    }

    private val imapCredentials = AccountCredentials(
        server = "unused",
        username = "alex",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint("unused.test", 143, ConnectionSecurity.NONE),
    )

    @Test fun `two IMAP read marks of one unread message take it off its folder once`() {
        val imap = HeldImap()
        val id = ImapMailService.emailId(ACCOUNT, INBOX, 7)
        cached["$ACCOUNT/$id"] = row(id, INBOX)
        val repository = repository(imap)

        runBlocking {
            withTimeout(10_000) {
                val first = launch { repository.setRead(imapCredentials, id, seen = true) }
                val second = launch { repository.setRead(imapCredentials, id, seen = true) }
                val gates = List(2) { awaitGate(imap.arrived) }
                gates[0].complete(Unit)
                first.join()
                gates[1].complete(Unit)
                second.join()
            }
        }

        assertTrue("the message is read in the cache", cached["$ACCOUNT/$id"]!!.seen)
        assertEquals(
            "one unread message left the folder's unread, so the counter drops by one: the second " +
                "mark found it already read when its write landed",
            listOf(INBOX to -1),
            unreadDeltas(),
        )
    }

    private suspend fun awaitGate(queue: LinkedBlockingQueue<CompletableDeferred<Unit>>): CompletableDeferred<Unit> {
        while (true) {
            queue.poll()?.let { return it }
            kotlinx.coroutines.yield()
        }
    }

    // ---- JMAP -------------------------------------------------------------------------------

    private var server: MockWebServer? = null

    /** The n-th `Email/set` counts down the n-th of [setArrivals], then waits on the n-th of [setGates]. */
    private val setGates = List(2) { CountDownLatch(1) }
    private val setArrivals = List(2) { CountDownLatch(1) }
    private var setsSeen = 0

    private fun startJmap(): AccountCredentials {
        protocol = MailProtocol.JMAP
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return json(sessionBody(mock))
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject["methodCalls"]!!.jsonArray[0].jsonArray
                return when (call[0].jsonPrimitive.content) {
                    "Mailbox/get" -> json(
                        """{"methodResponses":[["Mailbox/get",{"accountId":"acc1","state":"m1","list":[""" +
                            """{"id":"inbox","name":"Inbox","role":"inbox","totalEmails":1,"unreadEmails":1}""" +
                            """],"notFound":[]},"c0"]]}""",
                    )
                    "Email/set" -> {
                        val updated = call[1].jsonObject["update"]!!.jsonObject.keys
                        val index = synchronized(this@ReadMarkRaceCountsOnceTest) { setsSeen++ }
                        setArrivals[index].countDown()
                        setGates[index].await(10, TimeUnit.SECONDS)
                        json(
                            """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"e1","newState":"e2",""" +
                                """"updated":{${updated.joinToString(",") { "\"$it\":null" }}}},"s0"]]}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
        mock.start()
        server = mock
        return AccountCredentials(
            server = mock.url("/").toString().removeSuffix("/"),
            username = "alex",
            password = "secret",
            id = ACCOUNT,
            protocol = MailProtocol.JMAP,
            authType = AuthType.BASIC,
        )
    }

    @After fun stop() {
        setGates.forEach { it.countDown() }
        server?.shutdown()
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun sessionBody(mock: MockWebServer): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${mock.url("/jmap/api")}","state":"sess-1"}
        """.trimIndent()

    /** [first] out and held at its `Email/set`, then [second] out and held at its own; let go in that order. */
    private fun race(first: suspend () -> Unit, second: suspend () -> Unit) = runBlocking {
        withTimeout(10_000) {
            val one: Job = launch { first() }
            awaitLatch(setArrivals[0])
            val two: Job = launch { second() }
            awaitLatch(setArrivals[1])
            setGates[0].countDown()
            one.join()
            setGates[1].countDown()
            two.join()
        }
    }

    private suspend fun awaitLatch(latch: CountDownLatch) {
        while (!latch.await(10, TimeUnit.MILLISECONDS)) kotlinx.coroutines.yield()
    }

    @Test fun `two JMAP read marks of one unread message take it off its folder once`() {
        val credentials = startJmap()
        cached["$ACCOUNT/m0"] = row("m0", "inbox")
        val repository = repository(HeldImap())

        race(
            { repository.setRead(credentials, "m0", seen = true) },
            { repository.setRead(credentials, "m0", seen = true) },
        )

        assertTrue("the message is read in the cache", cached["$ACCOUNT/m0"]!!.seen)
        assertEquals(
            "one unread message left the folder's unread, so the counter drops by one: the second " +
                "mark found it already read when its write landed",
            listOf("inbox" to -1),
            unreadDeltas(),
        )
    }

    @Test fun `a bulk JMAP read mark racing a single one takes the message off its folder once`() {
        val credentials = startJmap()
        cached["$ACCOUNT/m0"] = row("m0", "inbox")
        val repository = repository(HeldImap())

        race(
            { repository.setRead(credentials, "m0", seen = true) },
            { repository.setReadAll(credentials, listOf("m0"), seen = true) },
        )

        assertTrue("the message is read in the cache", cached["$ACCOUNT/m0"]!!.seen)
        assertEquals(
            "the single mark landed first and took the message off; the bulk mark, read before " +
                "its round trip, found it already read when its write landed",
            listOf("inbox" to -1),
            unreadDeltas(),
        )
    }

    companion object {
        const val ACCOUNT = "acc1"
        const val INBOX = "INBOX"

        /** An [OAuthTokenRefresher] built without its constructor: the held `setFlag` never reaches it. */
        fun unusedTokenRefresher(): OAuthTokenRefresher {
            val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, OAuthTokenRefresher::class.java) as OAuthTokenRefresher
        }
    }
}

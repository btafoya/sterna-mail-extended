package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
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
import app.sterna.core.jmap.OAuthClient
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A bulk action whose rows come from a batch ticket ([MailRepository.beginRemovalAll]) must not
 * decount their source folder a second time when the server answers: the folder lost them at the
 * tap. Counted twice, the drawer's badge goes down by twenty for ten lines and only a full sync
 * ever repairs it.
 *
 * `destroyAll` RUN on its JMAP branch against a server that answers, as in
 * [DestroySparesWhatMovedOnTheWireTest], with the drawer counters recorded: the flag is executed,
 * not read off the source.
 *
 * The IMAP movers, which reach the same partition past a real `UID MOVE`, are executed the same way
 * in [BulkImapCountsWhatLeftTheFolderTest].
 */
class BulkActionCountsTheSourceOnceTest {

    private val wire = mutableListOf<String>()
    private val bodies = mutableListOf<Pair<String, String>>()

    /** The cached rows, keyed by account and id (#31). */
    private val cached = linkedMapOf<String, EmailEntity>()

    /** The per-folder deltas the drawer was handed, as (mailboxId, total, unread), in order. */
    private val folderCounts = mutableListOf<Triple<String, Int, Int>>()

    private val deleted = mutableListOf<List<String>>()

    private val store = RecordingAccountStorage()

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    wire += "session"
                    return json(sessionBody())
                }
                val body = request.body.readUtf8()
                val call = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray[0].jsonArray
                val method = call[0].jsonPrimitive.content
                wire += method
                bodies += method to body
                return when (method) {
                    "Identity/get" -> json("""{"methodResponses":[["Identity/get",{"list":[]},"i0"]]}""")
                    "Mailbox/get" -> json("""{"methodResponses":[["Mailbox/get",{"list":[]},"c0"]]}""")
                    // Every id this test uses is filed in the expected folder and nowhere else, so
                    // the guard of #122 spares none of them and the destroy goes out whole.
                    "Email/get" -> {
                        val asked = call[1].jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content }
                        json(
                            """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[""" +
                                asked.joinToString(",") { """{"id":"$it","mailboxIds":{"trash-1":true}}""" } +
                                """],"notFound":[]},"g0"]]}""",
                        )
                    }
                    "Email/set" -> {
                        val args = call[1].jsonObject
                        val destroy = args["destroy"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                        val update = args["update"]?.jsonObject?.keys.orEmpty()
                        json(
                            """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"s1","newState":"s2",""" +
                                """"destroyed":[${destroy.joinToString(",") { "\"$it\"" }}],"notDestroyed":{},""" +
                                """"updated":{${update.joinToString(",") { "\"$it\":null" }}},"notUpdated":{}},"s0"]]}""",
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

    /** One mail account, which is what lets `reconcileLinkedAccounts` return before it touches a
     *  DAO or launches anything on the background scope. */
    private fun sessionBody(): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${server.url("/jmap/api")}","state":"sess-1"}
        """.trimIndent()

    private fun row(id: String, seen: Boolean = false) = EmailEntity(
        id = id,
        accountId = "acc",
        mailboxId = "trash-1",
        threadId = "t-$id",
        subject = "Subject $id",
        preview = "Preview",
        receivedAt = "2026-09-01T10:00:00Z",
        fromName = "Alex",
        fromEmail = "alex@example.test",
        seen = seen,
        flagged = false,
        hasAttachment = false,
        sortKey = 1_756_720_800_000,
    )

    private fun cache(vararg rows: EmailEntity) = rows.forEach { cached["${it.accountId}/${it.id}"] = it }

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
            "evictFromCacheKeepingIndex" -> {
                val accountId = args[0] as String
                (args[1] as List<String>).forEach { cached.remove("$accountId/$it") }
                Unit
            }
            "deleteById" -> {
                val accountId = args[0] as String
                val id = args[1] as String
                deleted += listOf(id)
                cached.remove("$accountId/$id")
                Unit
            }
            "deleteByIds" -> {
                val accountId = args[0] as String
                val ids = args[1] as List<String>
                deleted += ids
                ids.forEach { cached.remove("$accountId/$it") }
                Unit
            }
            "upsertAll" -> {
                (args[0] as List<EmailEntity>).forEach { cached["${it.accountId}/${it.id}"] = it }
                Unit
            }
            else -> error("EmailDao.${method.name} was called by a path that must not touch it.")
        }
    } as EmailDao

    private fun mailboxDao(): MailboxDao = Proxy.newProxyInstance(
        MailboxDao::class.java.classLoader,
        arrayOf(MailboxDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "adjustCounts" -> {
                folderCounts += Triple(args[1] as String, args[2] as Int, args[3] as Int)
                Unit
            }
            // What an action that moved nothing asks before evicting, to decide the index row.
            "roleForId" -> "trash"
            else -> error("MailboxDao.${method.name} was not expected here.")
        }
    } as MailboxDao

    /** A bulk move withdraws its ids from any pending purge order before anything else (#99). */
    private fun purgeSnapshotDao(): PurgeSnapshotDao = Proxy.newProxyInstance(
        PurgeSnapshotDao::class.java.classLoader,
        arrayOf(PurgeSnapshotDao::class.java),
    ) { _, method, _ ->
        if (method.name != "unlistEmails") error("PurgeSnapshotDao.${method.name} was not expected here.")
        Unit
    } as PurgeSnapshotDao

    private fun repository(): MailRepository = MailRepository(
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
        purgeSnapshotDao = purgeSnapshotDao(),
        localDraftDao = unusableDao<LocalDraftDao>(),
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        outboxFilesDir = File("outbox-never-read"),
        localDraftFilesDir = File("drafts-never-read"),
    )

    private fun credentials(): AccountCredentials = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex",
        password = "secret",
        id = "acc",
        protocol = MailProtocol.JMAP,
        authType = AuthType.BASIC,
    )

    private fun argsOf(method: String): JsonObject =
        Json.parseToJsonElement(bodies.single { it.first == method }.second)
            .jsonObject["methodCalls"]!!.jsonArray[0].jsonArray[1].jsonObject

    private fun destroyedOnTheWire(): List<String> =
        argsOf("Email/set")["destroy"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun unreadDeltas(): List<Pair<String, Int>> =
        store.calls.filter { it.first == "adjustInboxUnread" }
            .map { it.second["mailboxId"] as String to it.second["delta"] as Int }

    @Test fun `with no ticket out, the folder is decounted by the destroy itself`() {
        cache(row("m1"), row("m2"))

        val result = runBlocking { repository().destroyAll(credentials(), listOf("m1", "m2"), "trash-1") }

        assertEquals(setOf("m1", "m2"), result.succeeded)
        assertEquals(
            "the destroy really went out, guard of #122 included: a count read off a path that " +
                "stopped before the wire says nothing",
            listOf("session", "Identity/get", "Mailbox/get", "Email/get", "Email/set"),
            wire,
        )
        assertEquals(listOf("m1", "m2"), destroyedOnTheWire())
        assertEquals(listOf("trash-1" to -2), unreadDeltas())
        assertEquals(listOf(Triple("trash-1", -2, -2)), folderCounts)
    }

    @Test fun `rows a ticket already decounted are not decounted again on the ack`() {
        cache(row("m1"), row("m2"))
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2")) }!!
        assertEquals("the tap took the two unread off the folder", listOf("trash-1" to -2), unreadDeltas())

        val result = runBlocking { repository.destroyAll(credentials(), ticket.emailIds, "trash-1") }

        assertEquals(setOf("m1", "m2"), result.succeeded)
        assertEquals(
            "the rows are out of the cache, and the destroy must still work off the ticket: a " +
                "read of the cache answers nothing for them, and nothing would go out at all",
            listOf("m1", "m2"),
            destroyedOnTheWire(),
        )
        assertEquals(
            "and the index rows go, which is the ack's own business",
            listOf(listOf("m1", "m2")),
            deleted,
        )
        assertEquals(
            "the folder was decounted at the tap and may not be decounted again: twice over, the " +
                "drawer's badge drops by twenty for ten lines",
            listOf("trash-1" to -2),
            unreadDeltas(),
        )
        assertEquals(listOf(Triple("trash-1", -2, -2)), folderCounts)
    }

    @Test fun `a move works off the ticket's rows, and credits the folder they are going to`() {
        cache(row("m1"), row("m2"))
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2")) }!!
        store.calls.clear()
        folderCounts.clear()

        val result = runBlocking { repository.moveAllToMailbox(credentials(), ticket.emailIds, "archive-1") }

        assertEquals(setOf("m1", "m2"), result.succeeded)
        assertEquals(
            "the destination's badge is computed from the rows, and once a ticket is out the " +
                "cache answers nothing for them: read from the cache alone, the mover has no row " +
                "at all and the folder the messages land in never counts them",
            listOf("archive-1" to 2),
            unreadDeltas(),
        )
        assertEquals(listOf(Triple("archive-1", 2, 2)), folderCounts)
    }

    @Test fun `a mover taking one id works off the batch ticket and counts the source once`() {
        // What a collapsed conversation's swipe does: one ticket for the thread's members, then the
        // single-id movers (`delete`, `archive`, `moveToMailbox`) written one after the other.
        cache(row("m1"), row("m2"))
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2")) }!!
        assertEquals("the gesture took the two unread off the folder", listOf("trash-1" to -2), unreadDeltas())
        store.calls.clear()
        folderCounts.clear()

        val dest = runBlocking { repository.moveToMailbox(credentials(), ticket.emailIds.first(), "archive-1") }

        assertEquals("archive-1", dest)
        assertEquals(
            "the mover must read the row the ticket holds and credit the destination ALONE: the " +
                "source folder left at the finger. Decounted again here, the drawer's badge drops " +
                "by six for a conversation of three, and only a full sync repairs it.",
            listOf("archive-1" to 1),
            unreadDeltas(),
        )
        assertEquals(listOf(Triple("archive-1", 1, 1)), folderCounts)
    }

    @Test fun `a selection moved into the folder it is already in gets back what the tap took`() {
        // "Move to…" with the folder the lines are already in, on a selection whose rows a ticket
        // holds. The server accepts the set, nothing moved, and nothing in the nudge credits a
        // folder a message never left: unreturned, the tap's decount stands for good.
        cache(row("m1"), row("m2"))
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2")) }!!
        assertEquals("the tap took the two unread off the folder", listOf("trash-1" to -2), unreadDeltas())
        store.calls.clear()
        folderCounts.clear()

        val result = runBlocking { repository.moveAllToMailbox(credentials(), ticket.emailIds, "trash-1") }

        assertEquals(setOf("m1", "m2"), result.succeeded)
        assertEquals(
            "the two messages are still in trash-1 on the server, so its badge owes them: left " +
                "uncredited it sits two short until that folder is read whole again",
            listOf("trash-1" to 2),
            unreadDeltas(),
        )
        assertEquals(listOf(Triple("trash-1", 2, 2)), folderCounts)
    }

    @Test fun `the folder that was credited back is not credited a second time by the ticket`() {
        cache(row("m1"), row("m2"))
        val repository = repository()
        val ticket = runBlocking { repository.beginRemovalAll("acc", listOf("m1", "m2")) }!!
        runBlocking { repository.moveAllToMailbox(credentials(), ticket.emailIds, "trash-1") }
        store.calls.clear()
        folderCounts.clear()

        runBlocking { repository.endRemovalAll(ticket, restore = ticket.emailIds) }

        assertEquals(
            "the flight ended where the folder was credited: a caller closing its ticket " +
                "afterwards, and asking for everything back, may not add the same two again",
            emptyList<Pair<String, Int>>(),
            unreadDeltas(),
        )
        assertEquals(emptyList<Triple<String, Int, Int>>(), folderCounts)
    }

    @Test fun `a batch that mixes held and unheld rows nudges each half on its own terms`() {
        // The flag is one boolean for a whole nudge, so a batch holding both kinds has to be split
        // rather than decided for the majority. `m2` is read, which is what tells the two halves
        // apart in the deltas below.
        cache(row("m1"), row("m2", seen = true))
        val repository = repository()
        runBlocking { repository.beginRemovalAll("acc", listOf("m1")) }
        store.calls.clear()
        folderCounts.clear()

        val result = runBlocking { repository.destroyAll(credentials(), listOf("m1", "m2"), "trash-1") }

        assertEquals(setOf("m1", "m2"), result.succeeded)
        assertEquals(
            "only the row no ticket holds may be decounted here, and it is read, so the folder " +
                "loses one message and no unread",
            listOf("trash-1" to 0),
            unreadDeltas(),
        )
        assertEquals(listOf(Triple("trash-1", -1, 0)), folderCounts)
    }
}

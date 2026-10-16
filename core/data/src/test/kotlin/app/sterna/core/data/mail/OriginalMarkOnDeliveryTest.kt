package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.OutboxDao
import app.sterna.core.data.db.OutboxEntity
import app.sterna.core.data.db.RecentContactDao
import app.sterna.core.data.db.ScheduledSendDao
import app.sterna.core.data.settings.OutgoingDateZones
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.time.ZoneId

/**
 * `MailSender.performSend`, `MailSender.deleteOutboxMarkingOriginal` then `MailSender.markOriginalOf`
 * RUN, in the worker's order, with a row naming the message it answers or forwards (#259): what is
 * set on the original, on which account, and what a failure, a hang or a cancellation of the mark
 * leaves behind. `performSend` alone marks nothing. Every write is recorded into one trace, so an
 * order is read and not inferred.
 *
 * The sending account is always `account-1` and the original's account `account-2`, as when the
 * From picker moved a reply to another account: a mark set through the sender's credentials would
 * land on a message of the wrong mailbox, or on none.
 *
 * What this does NOT cover: `MailRepository.noteOwnKeywordWrite` itself (the repository is not
 * built here), whether a sync afterwards reads the mark back, nor that Room runs
 * `OutboxDao.deleteMarkingOriginal` as one transaction: the DAO is a recording stand-in here.
 */
class OriginalMarkOnDeliveryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    /** Every write the send makes, in the order it reached its edge. */
    private val trace = mutableListOf<String>()

    /** The `Email/set` arguments the JMAP server received. */
    private val emailSets = mutableListOf<JsonObject>()

    private fun credentials(id: String, protocol: MailProtocol) = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "$id@example.test",
        password = "secret",
        id = id,
        protocol = protocol,
        authType = AuthType.BASIC,
    )

    private class Accounts(vararg known: AccountCredentials) : RecordingAccountStorage() {
        private val byId = known.associateBy { it.id }
        override fun credentials(id: String): AccountCredentials? {
            super.credentials(id)
            return byId[id]
        }
    }

    private inner class RecordingImap(
        store: RecordingAccountStorage,
        private val sendFails: Boolean = false,
        private val flagFails: Boolean = false,
        /** The flag write never answers: a dead socket, or the account's IMAP mutex held elsewhere. */
        private val flagHangs: Boolean = false,
    ) : ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)) {
        override suspend fun send(credentials: AccountCredentials, message: OutgoingMessage, sentMailbox: String?) {
            if (sendFails) error("550 refused")
            trace += "send from ${credentials.id}"
        }

        override suspend fun setFlag(credentials: AccountCredentials, mailboxId: String, uid: Long, flag: String, set: Boolean) {
            if (flagFails) error("NO no such message")
            if (flagHangs) {
                trace += "setFlag hangs"
                awaitCancellation()
            }
            trace += "setFlag ${credentials.id} $mailboxId $uid $flag $set"
        }
    }

    private inner class RecordingHost : MailSendHost {
        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession {
            trace += "session ${credentials.id}"
            return MailRepository.JmapAccountSession(
                credentials = credentials,
                session = JmapSession(
                    capabilities = mapOf(Jmap.CORE_CAPABILITY to JsonObject(emptyMap()), Jmap.MAIL_CAPABILITY to JsonObject(emptyMap())),
                    accounts = mapOf("acc-two" to JmapAccount(name = "Two")),
                    primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "acc-two"),
                    username = "two",
                    apiUrl = server.url("/jmap/api").toString(),
                ),
                accountId = "acc-two",
                auth = BasicAuth("two", "secret"),
                rolesToMailboxId = emptyMap(),
            )
        }

        override suspend fun syncMailbox(
            session: JmapSession,
            accountId: String,
            auth: JmapAuth,
            mailboxId: String,
            sizing: FolderSyncSizing,
            localAccountId: String,
        ): MailRepository.MailboxSync = error("every send here is an IMAP one")

        override suspend fun uploadAttachment(
            credentials: AccountCredentials,
            bytes: ByteArray,
            type: String?,
            name: String?,
            disposition: String,
            cid: String?,
        ): EmailBodyPart = error("no row here carries an attachment")

        override suspend fun destroyDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?) {
            trace += "destroyDraft ${credentials.id} $emailId"
        }

        override suspend fun imapDraftAddressing(credentials: AccountCredentials, emailId: String, budgetMs: Int): Set<String>? =
            error("the addressing proof belongs to the queueing path")

        override fun noteOwnKeywordWrite(accountId: String, emailId: String) {
            trace += "note $accountId $emailId"
        }
    }

    /** Nothing a delivery does may reach the message cache but through [recordingOutboxDao]. */
    private fun recordingEmailDao(): EmailDao =
        Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, _ ->
            when (method.name) {
                "toString" -> "recording EmailDao"
                else -> error("EmailDao.${method.name} was not expected here.")
            }
        } as EmailDao

    /** The delivered row's way out, recorded; [transactionFails] makes the transaction throw. */
    private fun recordingOutboxDao(transactionFails: Boolean = false): OutboxDao =
        Proxy.newProxyInstance(OutboxDao::class.java.classLoader, arrayOf(OutboxDao::class.java)) { _, method, args ->
            when (method.name) {
                "deleteMarkingOriginal" -> {
                    if (transactionFails) error("database is locked")
                    val original = args[1] as OriginalMark?
                    trace += "deleteMarkingOriginal ${args[0]} " +
                        (original?.let { "${it.accountId} ${it.emailId} ${it.mark}" } ?: "nothing")
                    Unit
                }
                "delete" -> {
                    trace += "delete ${args[0]}"
                    Unit
                }
                "toString" -> "recording OutboxDao"
                else -> error("OutboxDao.${method.name} was not expected here.")
            }
        } as OutboxDao

    private val sentFolder: MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            if (method.name != "idForRole") error("MailboxDao.${method.name} was not expected here.")
            if (args[1] == "sent") "Sent" else null
        } as MailboxDao

    private fun sender(
        store: RecordingAccountStorage,
        imap: ImapMailService = RecordingImap(store),
        outboxDao: OutboxDao = recordingOutboxDao(),
    ) = MailSender(
        client = JmapClient(),
        imap = imap,
        mailboxDao = sentFolder,
        emailDao = recordingEmailDao(),
        accountStore = store,
        outboxDao = outboxDao,
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        outboxFilesDir = tmp.newFolder("outbox"),
        scheduledFilesDir = tmp.newFolder("scheduled"),
        host = RecordingHost(),
        outgoingDates = OutgoingDateZones(MutableStateFlow(false), deviceZone = { ZoneId.of("UTC") }),
    )

    private fun item(
        originalAccountId: String? = null,
        originalEmailId: String? = null,
        originalMark: String? = null,
        draftEmailId: String? = null,
    ) = OutboxEntity(
        id = 7,
        accountId = "account-1",
        recipients = "bob@example.test",
        subject = "Re: Lunch",
        textBody = "Yes",
        createdAtMillis = 1_000,
        notBeforeMillis = 1_000,
        draftEmailId = draftEmailId,
        originalAccountId = originalAccountId,
        originalEmailId = originalEmailId,
        originalMark = originalMark,
    )

    private val imapOriginal = "imap:account-2:INBOX:42"

    /** What `OutboxWorker` does with a row once it is SENDING, in its order. */
    private suspend fun deliverThenMark(sender: MailSender, credentials: AccountCredentials, item: OutboxEntity) {
        sender.performSend(credentials, item)
        sender.deleteOutboxMarkingOriginal(item)
        sender.markOriginalOf(item)
    }

    // ---- IMAP

    @Test fun `a delivered reply marks its original answered on the account the original was read in`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))

        runBlocking {
            deliverThenMark(
                sender(store),
                credentials("account-1", MailProtocol.IMAP),
                item("account-2", imapOriginal, "ANSWERED"),
            )
        }

        assertEquals(
            listOf(
                "send from account-1",
                "note account-2 $imapOriginal",
                "deleteMarkingOriginal 7 account-2 $imapOriginal ANSWERED",
                "delete 7",
                "note account-2 $imapOriginal",
                "setFlag account-2 INBOX 42 \\Answered true",
            ),
            trace,
        )
    }

    @Test fun `a delivered forward marks its original forwarded and not answered`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))

        runBlocking {
            deliverThenMark(
                sender(store),
                credentials("account-1", MailProtocol.IMAP),
                item("account-2", imapOriginal, "FORWARDED"),
            )
        }

        assertEquals(
            listOf(
                "send from account-1",
                "note account-2 $imapOriginal",
                "deleteMarkingOriginal 7 account-2 $imapOriginal FORWARDED",
                "delete 7",
                "note account-2 $imapOriginal",
                "setFlag account-2 INBOX 42 \$Forwarded true",
            ),
            trace,
        )
    }

    /**
     * The row is still SENDING until `performSend` returns: a mark made there, waiting on a socket
     * or a mutex, is what a stopped worker turns into a delivered message parked as interrupted.
     */
    @Test fun `performSend alone delivers and marks nothing`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))

        runBlocking {
            sender(store).performSend(
                credentials("account-1", MailProtocol.IMAP),
                item("account-2", imapOriginal, "ANSWERED", draftEmailId = "draft-9"),
            )
        }

        assertEquals(listOf("send from account-1", "destroyDraft account-1 draft-9"), trace)
    }

    /** The original is gone, or the server refuses the flag: the message has left, only the server's mark is lost. */
    @Test fun `a server mark that fails returns and writes nothing on the phone`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))
        val sender = sender(store, imap = RecordingImap(store, flagFails = true))

        val outcome = runCatching { runBlocking { sender.markOriginalOf(item("account-2", imapOriginal, "ANSWERED")) } }

        assertNull("markOriginalOf must swallow a failed mark, got ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals(listOf("note account-2 $imapOriginal"), trace)
    }

    /** Unbounded, the worker that called it never finishes, and WorkManager stops it at its ceiling. */
    @Test(timeout = 10_000)
    fun `a mark that never answers ends within its budget`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))
        val sender = sender(store, imap = RecordingImap(store, flagHangs = true))

        runBlocking { sender.markOriginalOf(item("account-2", imapOriginal, "ANSWERED"), budgetMs = 200) }

        assertEquals(listOf("note account-2 $imapOriginal", "setFlag hangs"), trace)
    }

    /** A worker being stopped must stop: a swallowed cancellation runs on as if nothing had happened. */
    @Test(timeout = 10_000)
    fun `a cancelled mark is not swallowed`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))
        val sender = sender(store, imap = RecordingImap(store, flagHangs = true))
        var ranOn = false

        runBlocking {
            val worker = launch {
                sender.markOriginalOf(item("account-2", imapOriginal, "ANSWERED"), budgetMs = 60_000)
                ranOn = true
            }
            while ("setFlag hangs" !in trace) yield()
            worker.cancelAndJoin()
        }

        assertEquals("the code after markOriginalOf ran in a cancelled coroutine", false, ranOn)
    }

    @Test fun `a delivery that fails marks nothing`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))
        val sender = sender(store, imap = RecordingImap(store, sendFails = true))

        val outcome = runCatching {
            runBlocking { sender.performSend(credentials("account-1", MailProtocol.IMAP), item("account-2", imapOriginal, "ANSWERED")) }
        }

        assertTrue("the refused delivery must still reach the worker", outcome.isFailure)
        assertEquals(emptyList<String>(), trace)
    }

    @Test fun `a row naming no original marks nothing`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP))

        runBlocking { deliverThenMark(sender(store), credentials("account-1", MailProtocol.IMAP), item()) }

        assertEquals(listOf("send from account-1", "deleteMarkingOriginal 7 nothing", "delete 7"), trace)
    }

    /** A throw here would reach the worker's catch, which re-queues a message that has already left. */
    @Test fun `a transaction that fails still takes the delivered row out, and does not throw`() {
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.IMAP))
        val sender = sender(store, outboxDao = recordingOutboxDao(transactionFails = true))

        val outcome = runCatching { runBlocking { sender.deleteOutboxMarkingOriginal(item("account-2", imapOriginal, "ANSWERED")) } }

        assertNull("deleteOutboxMarkingOriginal relayed ${outcome.exceptionOrNull()}", outcome.exceptionOrNull())
        assertEquals(listOf("note account-2 $imapOriginal", "delete 7"), trace)
    }

    // ---- JMAP

    private fun serveEmailSet(response: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val call = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray.single().jsonArray
                if (call[0].toString() != "\"Email/set\"") return MockResponse().setResponseCode(500)
                emailSets += call[1].jsonObject
                trace += "Email/set"
                return MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"methodResponses":[["Email/set",$response,"s0"]]}""")
            }
        }
    }

    @Test fun `a forward of a jmap original sets the forwarded keyword through that account's session`() {
        serveEmailSet("""{"newState":"s2","updated":{"M9":null}}""")
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.JMAP))

        runBlocking {
            deliverThenMark(sender(store), credentials("account-1", MailProtocol.IMAP), item("account-2", "M9", "FORWARDED"))
        }

        assertEquals(
            listOf(
                "send from account-1",
                "note account-2 M9",
                "deleteMarkingOriginal 7 account-2 M9 FORWARDED",
                "delete 7",
                "note account-2 M9",
                "session account-2",
                "Email/set",
            ),
            trace,
        )
        assertEquals(
            Json.parseToJsonElement("""{"accountId":"acc-two","update":{"M9":{"keywords/${'$'}forwarded":true}}}"""),
            emailSets.single(),
        )
    }

    @Test fun `a jmap original the server no longer has leaves the send standing`() {
        serveEmailSet("""{"newState":"s2","notUpdated":{"M9":{"type":"notFound"}}}""")
        val store = Accounts(credentials("account-1", MailProtocol.IMAP), credentials("account-2", MailProtocol.JMAP))

        runBlocking {
            deliverThenMark(sender(store), credentials("account-1", MailProtocol.IMAP), item("account-2", "M9", "ANSWERED"))
        }

        assertEquals(
            listOf(
                "send from account-1",
                "note account-2 M9",
                "deleteMarkingOriginal 7 account-2 M9 ANSWERED",
                "delete 7",
                "note account-2 M9",
                "session account-2",
                "Email/set",
            ),
            trace,
        )
        assertEquals(
            Json.parseToJsonElement("""{"accountId":"acc-two","update":{"M9":{"keywords/${'$'}answered":true}}}"""),
            emailSets.single(),
        )
    }

    // ---- the row, on its way in and back out

    @Test fun `the three columns read back as one mark only when all three name it`() {
        assertEquals(OriginalMark("account-2", "M9", ReplyMark.FORWARDED), originalMarkOf("account-2", "M9", "FORWARDED"))
        assertEquals(OriginalMark("account-2", "M9", ReplyMark.ANSWERED), originalMarkOf("account-2", "M9", "ANSWERED"))
        assertNull(originalMarkOf(null, "M9", "ANSWERED"))
        assertNull(originalMarkOf("account-2", null, "ANSWERED"))
        assertNull(originalMarkOf("account-2", "M9", null))
        assertNull(originalMarkOf("account-2", "M9", "REACTED"))
    }

    /** Reopened from the Outbox and sent again, the message is a new row: the mark has to come back. */
    @Test fun `a queued row reopened for editing keeps its original`() {
        val reopened = outboxDraftOf(item("account-2", imapOriginal, "FORWARDED"), emptyList())

        assertEquals(OriginalMark("account-2", imapOriginal, ReplyMark.FORWARDED), reopened.originalMark)
        assertNull(outboxDraftOf(item(), emptyList()).originalMark)
    }

    @Test fun `the queued row is written with the original it was handed`() {
        val rows = mutableMapOf<Long, OutboxEntity>()
        val outboxDao = Proxy.newProxyInstance(OutboxDao::class.java.classLoader, arrayOf(OutboxDao::class.java)) { _, method, args ->
            when (method.name) {
                "insert" -> { rows[5L] = (args[0] as OutboxEntity).copy(id = 5L); 5L }
                "byId" -> rows[args[0] as Long]
                "update" -> { val row = args[0] as OutboxEntity; rows[row.id] = row; Unit }
                "toString" -> "recording OutboxDao"
                else -> error("OutboxDao.${method.name} was not expected here.")
            }
        } as OutboxDao
        val store = Accounts(credentials("account-1", MailProtocol.IMAP))

        runBlocking {
            sender(store, outboxDao = outboxDao).enqueueSend(
                credentials = credentials("account-1", MailProtocol.IMAP),
                to = listOf("bob@example.test"),
                subject = "Fwd: Lunch",
                body = "See below",
                learnRecipients = false,
                draftUidValidity = null,
                originalMark = OriginalMark("account-2", imapOriginal, ReplyMark.FORWARDED),
            )
        }

        val row = rows.getValue(5L)
        assertEquals(
            listOf("account-2", imapOriginal, "FORWARDED"),
            listOf(row.originalAccountId, row.originalEmailId, row.originalMark),
        )
    }
}

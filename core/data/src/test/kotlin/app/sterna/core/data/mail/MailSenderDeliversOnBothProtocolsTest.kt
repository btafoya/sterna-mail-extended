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
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.time.ZoneId

/**
 * `MailSender.performSend` RUN, on both protocols, against a recorded SMTP seam and a JMAP server
 * that answers. Until this test the delivery path was held by source lints alone, which read the
 * text of the decisions and cannot take either branch.
 *
 * What is pinned is what leaves the process: the Sent folder handed to the IMAP append, the
 * identity and the envelope of the JMAP submission, and the mailbox the delivered copy is pulled
 * back from. The expected values are written out; nothing here recomputes the rule that produced
 * them.
 *
 * What this does NOT cover:
 * - Attachments, on either branch: every item here carries none.
 * - The pre-built entity fork on IMAP: no IMAP item here carries one. On JMAP it is taken, but only
 *   the submission's identity and envelope and the absence of a `Bcc:` in the imported bytes are
 *   read; the entity's own bytes stay held by [PrebuiltEntityTest].
 * - The on-behalf sub-account fork is taken once, for its envelope; the re-filing of the Sent copy
 *   into the sub-account (`Email/copy`) is answered with an error the send swallows, and nothing
 *   here checks it.
 * - The date of an IMAP send: [RecordingImap] stands in for the service that builds the bytes, so
 *   the switch there is read by [ImapSentDateFollowsTheSwitchTest].
 */
class MailSenderDeliversOnBothProtocolsTest {

    private lateinit var server: MockWebServer

    @get:Rule val tmp = TemporaryFolder()

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    /** One JMAP request the server received, as the method it called and the arguments it carried. */
    private val received = mutableListOf<Pair<String, JsonObject>>()

    private val store = RecordingAccountStorage()

    /** An account that files its own Sent copy, so nothing may be appended to it. */
    private class StorageRefusingTheSentCopy : RecordingAccountStorage() {
        override fun uploadSentCopy(id: String): Boolean {
            super.uploadSentCopy(id)
            return false
        }
    }

    /** What `imap.send` was handed, the third argument above all: which folder (if any) is appended. */
    private data class Append(val message: OutgoingMessage, val sentMailbox: String?)

    private class RecordingImap(store: RecordingAccountStorage) : ImapMailService(
        ImapClient(),
        SmtpClient(),
        OAuthTokenRefresher(OAuthClient(), store),
    ) {
        val sends = mutableListOf<Append>()

        override suspend fun send(
            credentials: AccountCredentials,
            message: OutgoingMessage,
            sentMailbox: String?,
        ) {
            sends += Append(message, sentMailbox)
        }
    }

    /** The five edges `MailSender` reaches the repository through, none of them taken by accident. */
    private class RecordingHost(private val session: MailRepository.JmapAccountSession? = null) : MailSendHost {
        val syncedMailboxes = mutableListOf<String>()
        val destroyed = mutableListOf<Triple<String, String, Long?>>()

        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            session ?: error("the JMAP session seam was reached by a path that should not need one")

        override suspend fun syncMailbox(
            session: JmapSession,
            accountId: String,
            auth: JmapAuth,
            mailboxId: String,
            sizing: FolderSyncSizing,
            localAccountId: String,
        ): MailRepository.MailboxSync {
            syncedMailboxes += mailboxId
            return MailRepository.MailboxSync(fetchedIds = emptyList(), departedIds = emptyList())
        }

        override suspend fun uploadAttachment(
            credentials: AccountCredentials,
            bytes: ByteArray,
            type: String?,
            name: String?,
            disposition: String,
            cid: String?,
        ): EmailBodyPart = error("no item here carries an attachment to upload")

        override suspend fun destroyDraft(credentials: AccountCredentials, emailId: String, frozenUidValidity: Long?) {
            destroyed += Triple(credentials.id, emailId, frozenUidValidity)
        }

        override suspend fun imapDraftAddressing(
            credentials: AccountCredentials,
            emailId: String,
            budgetMs: Int,
        ): Set<String>? = error("the addressing proof belongs to the queueing path, not to delivery")
    }

    private fun mailboxDaoAnswering(role: String, id: String?, asked: MutableList<Pair<String, String>>): MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            if (method.name != "idForRole") error("MailboxDao.${method.name} was not expected here.")
            asked += (args[0] as String) to (args[1] as String)
            if (args[1] == role) id else null
        } as MailboxDao

    private fun sender(
        accountStore: RecordingAccountStorage = store,
        imap: ImapMailService = RecordingImap(store),
        mailboxDao: MailboxDao = unusableDao<MailboxDao>(),
        host: MailSendHost = RecordingHost(),
        outgoingDates: OutgoingDateZones = OutgoingDateZones(switch, deviceZone = { kolkata }),
    ) = MailSender(
        client = JmapClient(),
        imap = imap,
        mailboxDao = mailboxDao,
        emailDao = unusableDao<EmailDao>(),
        accountStore = accountStore,
        outboxDao = unusableDao<OutboxDao>(),
        scheduledSendDao = unusableDao<ScheduledSendDao>(),
        recentContactDao = unusableDao<RecentContactDao>(),
        outboxFilesDir = File("outbox-never-read"),
        host = host,
        outgoingDates = outgoingDates,
    )

    /** The quoted-dates-in-UTC switch (#120), off unless a test turns it on. */
    private val switch = MutableStateFlow(false)

    /** A device at +05:30, never the machine's zone: the build container runs in UTC. */
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private fun item(
        fromEmail: String? = "alex@example.test",
        cc: String? = null,
        bcc: String? = null,
        draftEmailId: String? = null,
        draftUidValidity: Long? = null,
        pgpEntityPath: String? = null,
    ) = OutboxEntity(
        id = 7,
        accountId = "account-1",
        recipients = "bob@example.test",
        cc = cc,
        bcc = bcc,
        subject = "Lunch",
        textBody = "One o'clock?",
        fromName = "Alex",
        fromEmail = fromEmail,
        createdAtMillis = 1_000,
        notBeforeMillis = 1_000,
        draftEmailId = draftEmailId,
        draftUidValidity = draftUidValidity,
        pgpEntityPath = pgpEntityPath,
    )

    private fun credentials(protocol: MailProtocol) = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex@example.test",
        password = "secret",
        id = "account-1",
        protocol = protocol,
        authType = AuthType.BASIC,
    )

    // ---- IMAP

    /**
     * The account uploads its own Sent copy, and the folder cached under the `sent` role is the one
     * that reaches the append. A branch that appended into the Drafts folder, or into no folder at
     * all, would leave the reader with no copy of what was sent.
     */
    @Test fun `the imap branch appends the copy into the folder cached under the sent role`() {
        val asked = mutableListOf<Pair<String, String>>()
        val imap = RecordingImap(store)
        val host = RecordingHost()
        val sender = sender(imap = imap, mailboxDao = mailboxDaoAnswering("sent", "Sent Items", asked), host = host)

        runBlocking { sender.performSend(credentials(MailProtocol.IMAP), item()) }

        assertEquals(1, imap.sends.size)
        assertEquals("Sent Items", imap.sends.single().sentMailbox)
        assertEquals(listOf("account-1" to "sent"), asked)
        val message = imap.sends.single().message
        assertEquals(listOf("bob@example.test"), message.to)
        assertEquals("Lunch", message.subject)
        assertEquals("Alex <alex@example.test>", message.from)
        // The IMAP branch returns before the JMAP half: nothing is pulled back from a server.
        assertEquals(emptyList<String>(), host.syncedMailboxes)
    }

    /**
     * Who the message is addressed to, field by field. `outgoing` is handed cc and bcc as
     * positional arguments, so swapping the two compiles and stays green under every other
     * assertion here, while every hidden recipient arrives in a `Cc:` header the visible ones read.
     * Two addresses on each side, all four different, so a swap cannot look like the truth.
     */
    @Test fun `the imap message keeps its visible and its blind copies apart`() {
        val imap = RecordingImap(store)
        val sender = sender(imap = imap, mailboxDao = mailboxDaoAnswering("sent", "Sent Items", mutableListOf()))

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.IMAP),
                item(cc = "carol@example.test, erin@example.test", bcc = "dave@example.test, frank@example.test"),
            )
        }

        val message = imap.sends.single().message
        assertEquals(listOf("bob@example.test"), message.to)
        assertEquals(listOf("carol@example.test", "erin@example.test"), message.cc)
        assertEquals(listOf("dave@example.test", "frank@example.test"), message.bcc)
    }

    /**
     * The same send on an account whose server files the Sent copy itself: the append is refused by
     * handing `null` down, not by omitting the send. A copy appended here is a duplicate the reader
     * has to delete by hand.
     */
    @Test fun `an account that does not upload its sent copy appends into no folder`() {
        val storage = StorageRefusingTheSentCopy()
        val asked = mutableListOf<Pair<String, String>>()
        val imap = RecordingImap(storage)
        val sender = sender(
            accountStore = storage,
            imap = imap,
            mailboxDao = mailboxDaoAnswering("sent", "Sent Items", asked),
        )

        runBlocking { sender.performSend(credentials(MailProtocol.IMAP), item()) }

        assertEquals(1, imap.sends.size)
        assertNull(imap.sends.single().sentMailbox)
    }

    /**
     * The draft the item replaces is destroyed after the delivery stands, through the seam, under
     * the numbering the ROW carries. Frozen at composer time: a numbering looked up now names a
     * different message.
     */
    @Test fun `the draft the row replaces is destroyed after delivery under the row's own numbering`() {
        val host = RecordingHost()
        val sender = sender(
            mailboxDao = mailboxDaoAnswering("sent", "Sent Items", mutableListOf()),
            host = host,
        )

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.IMAP),
                item(draftEmailId = "draft-9", draftUidValidity = 41L),
            )
        }

        assertEquals(listOf(Triple("account-1", "draft-9", 41L)), host.destroyed)
    }

    /** A row replacing no draft destroys nothing: the id is the only licence to destroy. */
    @Test fun `a row that replaces no draft destroys nothing`() {
        val host = RecordingHost()
        val sender = sender(
            mailboxDao = mailboxDaoAnswering("sent", "Sent Items", mutableListOf()),
            host = host,
        )

        runBlocking { sender.performSend(credentials(MailProtocol.IMAP), item()) }

        assertEquals(emptyList<Triple<String, String, Long?>>(), host.destroyed)
    }

    // ---- JMAP

    /** The bytes of every blob uploaded, in order: on the pre-built path, the message as submitted. */
    private val uploaded = mutableListOf<String>()

    private fun serveJmap(identities: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                if (request.path.orEmpty().startsWith("/jmap/upload/")) {
                    uploaded += body
                    return json("""{"blobId":"blob-1","type":"message/rfc822","size":${body.length}}""")
                }
                val calls = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray
                // Every call of the request, not just the first: the send puts `Email/set` and
                // `EmailSubmission/set` in one request, and the submission is the second.
                calls.forEach { received += it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonObject }
                return when (calls[0].jsonArray[0].jsonPrimitive.content) {
                    "Identity/get" ->
                        json("""{"methodResponses":[["Identity/get",{"list":[$identities]},"i0"]]}""")
                    "Mailbox/get" ->
                        json(
                            """{"methodResponses":[["Mailbox/get",{"list":[""" +
                                """{"id":"login-drafts","name":"Drafts","role":"drafts"},""" +
                                """{"id":"login-sent","name":"Sent","role":"sent"}]},"c0"]]}""",
                        )
                    "Email/set" ->
                        json(
                            """{"methodResponses":[["Email/set",{"created":{"draft":{"id":"sent-1"}}},"e0"],""" +
                                """["EmailSubmission/set",{"created":{"sub":{"id":"sub-1"}}},"s0"]]}""",
                        )
                    "Email/import" ->
                        json(
                            """{"methodResponses":[["Email/import",{"created":{"draft":{"id":"sent-1"}}},"i0"],""" +
                                """["EmailSubmission/set",{"created":{"sub":{"id":"sub-1"}}},"s0"]]}""",
                        )
                    "EmailSubmission/get" ->
                        json("""{"methodResponses":[["EmailSubmission/get",{"list":[]},"g0"]]}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /**
     * A warm session handed straight to the sender: the seam exists so no `fetchSession` is owed.
     * [accountId] other than the login's primary `acc1` is a delegated sub-account (#31), which is
     * what makes `onBehalf` true.
     */
    private fun jmapSession(accountId: String = "acc1"): MailRepository.JmapAccountSession =
        MailRepository.JmapAccountSession(
            credentials = credentials(MailProtocol.JMAP),
            session = JmapSession(
                capabilities = mapOf(Jmap.CORE_CAPABILITY to JsonObject(emptyMap()), Jmap.MAIL_CAPABILITY to JsonObject(emptyMap())),
                accounts = mapOf("acc1" to JmapAccount(name = "Alex"), "shared" to JmapAccount(name = "Team")),
                primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "acc1"),
                username = "alex",
                apiUrl = server.url("/jmap/api").toString(),
                // Concatenated, not `server.url(...)`: HttpUrl would percent-encode the braces and
                // the `{accountId}` substitution would find nothing to replace.
                uploadUrl = server.url("/").toString() + "jmap/upload/{accountId}/",
            ),
            accountId = accountId,
            auth = BasicAuth("alex", "secret"),
            rolesToMailboxId = mapOf("drafts" to "mb-drafts", "sent" to "mb-sent"),
        )

    /** A signed entity on disk, where the outbox row points for a pre-built send. */
    private fun prebuiltEntityFile(): String =
        tmp.newFile("prebuilt-entity.mime").apply {
            writeText("Content-Type: text/plain; charset=utf-8\r\n\r\nOne o'clock?\r\n")
        }.absolutePath

    /** The arguments of the one call to [method] the server received. */
    private fun argsOf(method: String): JsonObject =
        received.single { it.first == method }.second

    /**
     * A `From:` the server has an identity for: the submission is created under that identity's id
     * and carries NO envelope, the ordinary case where the server derives the `mail from` itself.
     * The delivered copy is then pulled back from the account's own Sent folder.
     */
    @Test fun `a from the server knows submits under its identity with no envelope`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        val host = RecordingHost(jmapSession())
        val sender = sender(host = host)

        runBlocking { sender.performSend(credentials(MailProtocol.JMAP), item()) }

        val submission = argsOf("EmailSubmission/set")["create"]!!.jsonObject
        assertEquals("identity-7", submissionIdentity(submission))
        assertNull(submissionEnvelope(submission))
        assertEquals(listOf("mb-sent"), host.syncedMailboxes)
    }

    /**
     * A `From:` no server identity carries (an alias typed by hand, #172): the submission still
     * goes out under the account's only identity, but with an explicit envelope naming the alias,
     * or the `mail from` the server derives leaves a mismatch DMARC fails on.
     */
    @Test fun `a from the server does not know carries an envelope naming it`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        val host = RecordingHost(jmapSession())
        val sender = sender(host = host)

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.JMAP),
                item(fromEmail = "alias@example.test", cc = "carol@example.test", bcc = "dave@example.test"),
            )
        }

        val submission = argsOf("EmailSubmission/set")["create"]!!.jsonObject
        assertEquals("identity-7", submissionIdentity(submission))
        assertEquals("alias@example.test", submissionEnvelope(submission))
        assertEquals(
            listOf("bob@example.test", "carol@example.test", "dave@example.test"),
            submissionRcptTo(submission),
        )
    }

    /**
     * The recipients of the message itself, on a send the server derives the envelope for: with a
     * `From:` it has an identity for there IS no envelope, so the `Email/set` create is the only
     * thing naming who is written to. A dropped `bcc` there delivers to nobody hidden and says
     * nothing, and the submission the other tests read cannot see it. The blind copy is the point:
     * the envelope a pre-built send owes its hidden recipients is not owed here.
     */
    @Test fun `the message handed to the server carries to, cc and bcc in their own fields`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        val sender = sender(host = RecordingHost(jmapSession()))

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.JMAP),
                item(cc = "carol@example.test, erin@example.test", bcc = "dave@example.test, frank@example.test"),
            )
        }

        assertNull(submissionEnvelope(argsOf("EmailSubmission/set")["create"]!!.jsonObject))
        assertEquals(listOf("bob@example.test"), draftAddresses("to"))
        assertEquals(listOf("carol@example.test", "erin@example.test"), draftAddresses("cc"))
        assertEquals(listOf("dave@example.test", "frank@example.test"), draftAddresses("bcc"))
    }

    /**
     * A signed message with a blind copy, from an address the server has an identity for. The
     * imported bytes carry no `Bcc:` (they reach the visible recipients as they are), so the
     * submission is the only place the hidden recipient can be named: without an envelope the
     * server derives `rcptTo` from the headers and the blind copy is never delivered.
     */
    @Test fun `a pre-built send with a blind copy names every recipient in an envelope from its identity`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        val sender = sender(host = RecordingHost(jmapSession()))

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.JMAP),
                item(cc = "carol@example.test", bcc = "dave@example.test", pgpEntityPath = prebuiltEntityFile()),
            )
        }

        val submission = argsOf("EmailSubmission/set")["create"]!!.jsonObject
        assertEquals("identity-7", submissionIdentity(submission))
        assertEquals("alex@example.test", submissionEnvelope(submission))
        assertEquals(
            listOf("bob@example.test", "carol@example.test", "dave@example.test"),
            submissionRcptTo(submission),
        )
        assertFalse(
            "the hidden address must not be written into the bytes the visible recipients receive",
            uploaded.single().contains("dave@example.test"),
        )
    }

    /**
     * The same on a delegated sub-account (#31): the submission is created on the login's account
     * under the login's identity, while `From:` keeps the delegated address. The envelope's
     * `mailFrom` is the login's, the identity the submission is created under; the delegated `From:`
     * there would be a sender the login has no identity for.
     */
    @Test fun `a delegated pre-built send with a blind copy takes its envelope from the login's identity`() {
        serveJmap("""{"id":"identity-login","name":"Alex","email":"alex@example.test"}""")
        val sender = sender(host = RecordingHost(jmapSession(accountId = "shared")))

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.JMAP),
                item(fromEmail = "team@example.test", bcc = "dave@example.test", pgpEntityPath = prebuiltEntityFile()),
            )
        }

        val submissionArgs = argsOf("EmailSubmission/set")
        assertEquals("the submission goes through the login's account", "acc1", submissionArgs["accountId"]!!.jsonPrimitive.content)
        val submission = submissionArgs["create"]!!.jsonObject
        assertEquals("identity-login", submissionIdentity(submission))
        assertEquals("alex@example.test", submissionEnvelope(submission))
        assertEquals(listOf("bob@example.test", "dave@example.test"), submissionRcptTo(submission))
        assertTrue(
            "the imported message must still say it is from the delegated address",
            uploaded.single().lines().any { it.startsWith("From:") && "team@example.test" in it },
        )
    }

    /** A signed message with no blind copy: its headers name every recipient, and nothing is added. */
    @Test fun `a pre-built send with no blind copy carries no envelope`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        val sender = sender(host = RecordingHost(jmapSession()))

        runBlocking {
            sender.performSend(
                credentials(MailProtocol.JMAP),
                item(cc = "carol@example.test", pgpEntityPath = prebuiltEntityFile()),
            )
        }

        val submission = argsOf("EmailSubmission/set")["create"]!!.jsonObject
        assertEquals("identity-7", submissionIdentity(submission))
        assertNull(sub(submission)["envelope"])
    }

    // ---- the date of a JMAP send, under the quoted-dates-in-UTC switch (#120)

    /** The `sentAt` of the last message created, the one field the server writes `Date:` from. */
    private fun lastSentAt(): String =
        received.last { it.first == "Email/set" }.second["create"]!!.jsonObject["draft"]!!
            .jsonObject["sentAt"]!!.jsonPrimitive.content

    /** The `Date:` line of the last message imported whole, the pre-built path's only date. */
    private fun lastImportedDateLine(): String = uploaded.last().lines().single { it.startsWith("Date:") }

    @Test fun `switch on, a jmap message is sent at a UTC instant`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        switch.value = true
        runBlocking { sender(host = RecordingHost(jmapSession())).performSend(credentials(MailProtocol.JMAP), item()) }
        assertTrue("sentAt: ${lastSentAt()}", lastSentAt().endsWith("Z"))
    }

    @Test fun `switch off, a jmap message is sent at the device's offset`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        switch.value = false
        runBlocking { sender(host = RecordingHost(jmapSession())).performSend(credentials(MailProtocol.JMAP), item()) }
        assertTrue("sentAt: ${lastSentAt()}", lastSentAt().endsWith("+05:30"))
    }

    @Test fun `switch on, a pre-built jmap message is dated in UTC`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        switch.value = true
        runBlocking {
            sender(host = RecordingHost(jmapSession()))
                .performSend(credentials(MailProtocol.JMAP), item(pgpEntityPath = prebuiltEntityFile()))
        }
        assertTrue(lastImportedDateLine(), lastImportedDateLine().endsWith(" GMT"))
    }

    @Test fun `switch off, a pre-built jmap message is dated at the device's offset`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        switch.value = false
        runBlocking {
            sender(host = RecordingHost(jmapSession()))
                .performSend(credentials(MailProtocol.JMAP), item(pgpEntityPath = prebuiltEntityFile()))
        }
        assertTrue(lastImportedDateLine(), lastImportedDateLine().endsWith(" +0530"))
    }

    /**
     * One sender, made with the switch on and turned off before its first send, then flipped
     * between sends: a zone taken when the sender was built, or kept from an earlier send, dates
     * the message with the answer of another moment.
     */
    @Test fun `the switch is read when a jmap message goes out, not before`() {
        serveJmap("""{"id":"identity-7","name":"Alex","email":"alex@example.test"}""")
        switch.value = true
        val sender = sender(host = RecordingHost(jmapSession()))
        val prebuilt = prebuiltEntityFile()
        switch.value = false
        runBlocking { sender.performSend(credentials(MailProtocol.JMAP), item()) }
        assertTrue("sentAt: ${lastSentAt()}", lastSentAt().endsWith("+05:30"))
        switch.value = true
        runBlocking { sender.performSend(credentials(MailProtocol.JMAP), item()) }
        assertTrue("sentAt: ${lastSentAt()}", lastSentAt().endsWith("Z"))
        switch.value = false
        runBlocking { sender.performSend(credentials(MailProtocol.JMAP), item(pgpEntityPath = prebuilt)) }
        assertTrue(lastImportedDateLine(), lastImportedDateLine().endsWith(" +0530"))
    }

    /** One address field of the created message, empty when the create does not name that field. */
    private fun draftAddresses(field: String): List<String> =
        argsOf("Email/set")["create"]!!.jsonObject["draft"]!!.jsonObject[field]
            ?.jsonArray?.map { it.jsonObject["email"]!!.jsonPrimitive.content }
            .orEmpty()

    private fun sub(create: JsonObject): JsonObject = create["sub"]!!.jsonObject

    private fun submissionIdentity(create: JsonObject): String =
        sub(create)["identityId"]!!.jsonPrimitive.content

    private fun submissionEnvelope(create: JsonObject): String? =
        sub(create)["envelope"]?.jsonObject?.get("mailFrom")?.jsonObject?.get("email")?.jsonPrimitive?.content

    private fun submissionRcptTo(create: JsonObject): List<String> =
        sub(create)["envelope"]!!.jsonObject["rcptTo"]!!.jsonArray.map { it.jsonObject["email"]!!.jsonPrimitive.content }
}

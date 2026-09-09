package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailEntity
import app.sterna.core.data.db.LocalDraftDao
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.MailboxUidValidityDao
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * `MailDrafts.uploadDraft` RUN, on both protocols, against a recorded IMAP seam and a JMAP server
 * that answers. Until this test the save's upload half was held by source lints alone, which read
 * the text of the decisions and cannot take either branch.
 *
 * What is pinned is what leaves the process: the folder the IMAP draft is appended into, who it is
 * addressed to, the mailbox and the fields of the JMAP create, and the destruction of the original
 * the new draft replaces (#63) with the numbering it must oppose (#99). The expected values are
 * written out; nothing here recomputes the rule that produced them.
 *
 * What this does NOT cover:
 * - Attachments, on either branch: every row here carries none, so neither the staged MIME parts
 *   nor the blob upload is executed.
 * - The read-receipt read: every row here asks for a receipt, which the carry rule answers without
 *   a server read, so `imapDraftReceiptRequested` and `jmapDraftReceiptRequested` are not entered.
 * - The re-upload dedupe: no row here has a failed attempt behind it, so `draftIsAlreadyThere` is
 *   never asked and the APPEND always goes out.
 */
class MailDraftsUploadsOnBothProtocolsTest {

    private lateinit var server: MockWebServer

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

    /** An expunge asked of the repository: the folder, the ids, and the numbering it must oppose. */
    private data class Expunge(val folder: String, val ids: List<String>, val expectedUidValidity: Long?)

    /** A JMAP destroy asked of the repository: the ids and the folder they are opposed to (#122). */
    private data class Destroy(val ids: List<String>, val expectedMailboxId: String?)

    /** What `imap.appendDraft` was handed: the folder above all, and the message built from the row. */
    private data class Append(val draftsMailbox: String, val message: OutgoingMessage)

    private class RecordingImap(
        store: RecordingAccountStorage,
        /** What the server says the replaced draft is copied to; null is "could not be established". */
        private val originalAddressing: Set<String>?,
    ) : ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)) {
        val appends = mutableListOf<Append>()
        val addressingReads = mutableListOf<Pair<String, Long>>()

        override suspend fun appendDraft(
            credentials: AccountCredentials,
            draftsMailbox: String,
            message: OutgoingMessage,
        ) {
            appends += Append(draftsMailbox, message)
        }

        override suspend fun ccAndBccOf(
            credentials: AccountCredentials,
            mailboxId: String,
            uid: Long,
            budgetMs: Int,
        ): Set<String>? {
            addressingReads += mailboxId to uid
            return originalAddressing
        }
    }

    /** The six edges `MailDrafts` reaches the repository through, none of them taken by accident. */
    private class RecordingHost(private val session: MailRepository.JmapAccountSession? = null) : MailDraftHost {
        val refreshed = mutableListOf<String>()
        val expunged = mutableListOf<Expunge>()
        val destroyed = mutableListOf<Destroy>()

        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            session ?: error("the JMAP session seam was reached by a path that should not need one")

        override suspend fun refreshMailbox(credentials: AccountCredentials, mailboxId: String) {
            refreshed += mailboxId
        }

        override suspend fun uploadAttachment(
            credentials: AccountCredentials,
            bytes: ByteArray,
            type: String?,
            name: String?,
            disposition: String,
            cid: String?,
        ): EmailBodyPart = error("no row here carries an attachment to upload")

        override suspend fun imapDestroyGroup(
            credentials: AccountCredentials,
            source: String,
            ids: List<String>,
            succeeded: MutableSet<String>,
            failed: MutableSet<String>,
            expectedUidValidity: Long?,
        ) {
            expunged += Expunge(source, ids, expectedUidValidity)
        }

        override suspend fun destroyAll(
            credentials: AccountCredentials,
            emailIds: List<String>,
            expectedMailboxId: String?,
            expectedUidValidity: Long?,
        ): MailRepository.BulkResult {
            destroyed += Destroy(emailIds, expectedMailboxId)
            return MailRepository.BulkResult(emailIds.toSet(), emptySet())
        }

        override suspend fun recordedUidValidity(credentials: AccountCredentials, mailboxId: String): Long? =
            error("the upload is handed the frozen numbering; reading one here is the defect (#99)")
    }

    private fun mailboxDaoAnswering(roles: Map<String, String>, asked: MutableList<Pair<String, String>>): MailboxDao =
        Proxy.newProxyInstance(MailboxDao::class.java.classLoader, arrayOf(MailboxDao::class.java)) { _, method, args ->
            if (method.name != "idForRole") error("MailboxDao.${method.name} was not expected here.")
            asked += (args[0] as String) to (args[1] as String)
            roles[args[1] as String]
        } as MailboxDao

    /** Cached rows for the draft being replaced, plus the upsert the JMAP branch writes back. */
    private fun emailDaoOver(rows: List<EmailEntity>, upserted: MutableList<EmailEntity>): EmailDao =
        Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, args ->
            when (method.name) {
                "emailsByIds" -> {
                    val ids = (if (args.size > 2) args[1] else args[0]) as List<*>
                    rows.filter { it.id in ids }
                }
                "mailboxOf" -> rows.firstOrNull { it.id == args[1] as String }?.mailboxId
                "upsertAll" -> {
                    @Suppress("UNCHECKED_CAST")
                    upserted += args[0] as List<EmailEntity>
                    Unit
                }
                else -> error("EmailDao.${method.name} was not expected here.")
            }
        } as EmailDao

    /** One row to upload, taken out and given back; nothing else of the table is reachable. */
    private fun localDraftDaoHolding(row: LocalDraftEntity, states: MutableList<String>): LocalDraftDao =
        Proxy.newProxyInstance(LocalDraftDao::class.java.classLoader, arrayOf(LocalDraftDao::class.java)) { _, m, args ->
            when (m.name) {
                "byId" -> row.takeIf { it.accountId == args[0] && it.id == args[1] }
                "setState" -> { states += args[2].toString(); Unit }
                "deleteById" -> Unit
                else -> error("LocalDraftDao.${m.name} was not expected here.")
            }
        } as LocalDraftDao

    private fun drafts(
        imap: ImapMailService,
        mailboxDao: MailboxDao,
        emailDao: EmailDao,
        localDraftDao: LocalDraftDao,
        host: MailDraftHost,
    ) = MailDrafts(
        client = JmapClient(),
        imap = imap,
        mailboxDao = mailboxDao,
        emailDao = emailDao,
        accountStore = store,
        localDraftDao = localDraftDao,
        mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
        localDraftFilesDir = File("local-drafts-never-written"),
        host = host,
    )

    private fun row(
        replacesEmailId: String? = null,
        replacesUidValidity: Long? = null,
    ) = LocalDraftEntity(
        accountId = ACCOUNT,
        id = "local-draft:1",
        messageId = "<lunch@sterna.test>",
        toAddresses = "bob@example.test",
        cc = "carol@example.test, erin@example.test",
        bcc = "dave@example.test",
        subject = "Lunch",
        textBody = "One o'clock?",
        fromName = "Alex",
        // Deliberately NOT `credentials.username`: with the two equal, the fallbacks to the stored
        // identity and to the signed-in address answer the same string and nothing is proven (#31).
        fromEmail = "alex+delegated@example.test",
        replacesEmailId = replacesEmailId,
        replacesUidValidity = replacesUidValidity,
        // True so the receipt leg of the carry rule answers without a server read.
        requestReceipt = true,
        createdAtMillis = 1_000,
        updatedAtMillis = 1_000,
        notBeforeMillis = 0,
    )

    private fun cachedDraft(id: String, mailboxId: String) = EmailEntity(
        id = id,
        accountId = ACCOUNT,
        mailboxId = mailboxId,
        threadId = null,
        subject = "Lunch",
        preview = null,
        receivedAt = null,
        fromName = "Alex",
        fromEmail = "alex@example.test",
        seen = true,
        flagged = false,
        hasAttachment = false,
        sortKey = 1_000,
    )

    private fun credentials(protocol: MailProtocol) = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex@example.test",
        password = "secret",
        id = ACCOUNT,
        protocol = protocol,
        authType = AuthType.BASIC,
    )

    // ---- IMAP

    /**
     * The folder cached under the `drafts` role is the one the APPEND lands in, and the reader is
     * shown it: an APPEND returns no id, so the folder is reloaded or the draft appears nowhere.
     * A branch appending into Sent, or into the account's Inbox, files the draft where the reader
     * will not look for it.
     */
    @Test fun `the imap upload appends into the folder cached under the drafts role`() {
        val asked = mutableListOf<Pair<String, String>>()
        val imap = RecordingImap(store, originalAddressing = null)
        val host = RecordingHost()
        val drafts = drafts(
            imap = imap,
            mailboxDao = mailboxDaoAnswering(mapOf("drafts" to "Drafts", "sent" to "Sent Items"), asked),
            emailDao = emailDaoOver(emptyList(), mutableListOf()),
            localDraftDao = localDraftDaoHolding(row(), mutableListOf()),
            host = host,
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.IMAP), "local-draft:1") }

        assertEquals(1, imap.appends.size)
        assertEquals("Drafts", imap.appends.single().draftsMailbox)
        assertEquals(listOf(ACCOUNT to "drafts"), asked)
        assertEquals(listOf("Drafts"), host.refreshed)
    }

    /**
     * Who the appended copy is addressed to, field by field. `localDraftOutgoing` reads the row's
     * three address columns, and two of them are one comma apart in the entity: a cc arriving in
     * `bcc` shows every hidden recipient to the visible ones the day the draft is sent.
     */
    @Test fun `the appended draft keeps its visible and its blind copies apart`() {
        val imap = RecordingImap(store, originalAddressing = null)
        val drafts = drafts(
            imap = imap,
            mailboxDao = mailboxDaoAnswering(mapOf("drafts" to "Drafts"), mutableListOf()),
            emailDao = emailDaoOver(emptyList(), mutableListOf()),
            localDraftDao = localDraftDaoHolding(row(), mutableListOf()),
            host = RecordingHost(),
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.IMAP), "local-draft:1") }

        val message = imap.appends.single().message
        assertEquals(listOf("bob@example.test"), message.to)
        assertEquals(listOf("carol@example.test", "erin@example.test"), message.cc)
        assertEquals(listOf("dave@example.test"), message.bcc)
        assertEquals("Alex <alex+delegated@example.test>", message.from)
        assertEquals("Lunch", message.subject)
        // Minted at the save and frozen on the row, so a retry can recognise its own copy.
        assertEquals("<lunch@sterna.test>", message.messageId)
        assertTrue(message.requestReceipt)
    }

    /**
     * The draft this one replaces (#63) is expunged from the folder its cached row names, under the
     * numbering the ROW froze (#99), through the repository's guard. A destroy carrying no numbering
     * expunges a UID that names whatever the folder holds now.
     */
    @Test fun `the replaced original is expunged under the numbering the row froze`() {
        val original = ImapMailService.emailId(ACCOUNT, "Drafts", 12L)
        val imap = RecordingImap(store, originalAddressing = setOf("carol@example.test"))
        val host = RecordingHost()
        val drafts = drafts(
            imap = imap,
            mailboxDao = mailboxDaoAnswering(mapOf("drafts" to "Drafts"), mutableListOf()),
            emailDao = emailDaoOver(listOf(cachedDraft(original, "Drafts")), mutableListOf()),
            localDraftDao = localDraftDaoHolding(row(original, replacesUidValidity = 77L), mutableListOf()),
            host = host,
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.IMAP), "local-draft:1") }

        assertEquals(listOf(Expunge("Drafts", listOf(original), 77L)), host.expunged)
        // Read from the server, not from the cached row: the proof is what the server holds.
        assertEquals(listOf("Drafts" to 12L), imap.addressingReads)
    }

    /**
     * The addressing the server would not hand back is an unknown, and an unknown keeps the
     * original (#63): a duplicate the reader can delete, never a copy destroyed on a failed read.
     */
    @Test fun `an addressing that could not be established spares the original`() {
        val original = ImapMailService.emailId(ACCOUNT, "Drafts", 12L)
        val host = RecordingHost()
        val drafts = drafts(
            imap = RecordingImap(store, originalAddressing = null),
            mailboxDao = mailboxDaoAnswering(mapOf("drafts" to "Drafts"), mutableListOf()),
            emailDao = emailDaoOver(listOf(cachedDraft(original, "Drafts")), mutableListOf()),
            localDraftDao = localDraftDaoHolding(row(original, replacesUidValidity = 77L), mutableListOf()),
            host = host,
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.IMAP), "local-draft:1") }

        assertEquals(emptyList<Expunge>(), host.expunged)
    }

    /** And an original copied to somebody the replacement drops is kept for the same reason. */
    @Test fun `an original addressed to somebody the replacement drops is kept`() {
        val original = ImapMailService.emailId(ACCOUNT, "Drafts", 12L)
        val host = RecordingHost()
        val drafts = drafts(
            imap = RecordingImap(store, originalAddressing = setOf("carol@example.test", "frank@example.test")),
            mailboxDao = mailboxDaoAnswering(mapOf("drafts" to "Drafts"), mutableListOf()),
            emailDao = emailDaoOver(listOf(cachedDraft(original, "Drafts")), mutableListOf()),
            localDraftDao = localDraftDaoHolding(row(original, replacesUidValidity = 77L), mutableListOf()),
            host = host,
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.IMAP), "local-draft:1") }

        assertEquals(emptyList<Expunge>(), host.expunged)
    }

    // ---- JMAP

    private fun serveJmap() {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val calls = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray
                calls.forEach { received += it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonObject }
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"methodResponses":[["Email/set",{"created":{"draft":{"id":"srv-9"}}},"s0"]]}""")
            }
        }
    }

    /** A warm session handed straight to the drafts: the seam exists so no `fetchSession` is owed. */
    private fun jmapSession(): MailRepository.JmapAccountSession = MailRepository.JmapAccountSession(
        credentials = credentials(MailProtocol.JMAP),
        session = JmapSession(
            capabilities = mapOf(
                Jmap.CORE_CAPABILITY to JsonObject(emptyMap()),
                Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()),
            ),
            accounts = mapOf("acc1" to JmapAccount(name = "Alex")),
            primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "acc1"),
            username = "alex",
            apiUrl = server.url("/jmap/api").toString(),
        ),
        accountId = "acc1",
        auth = BasicAuth("alex", "secret"),
        rolesToMailboxId = mapOf("drafts" to "mb-drafts", "sent" to "mb-sent"),
    )

    /** The created message of the one `Email/set` the server received. */
    private fun createdDraft(): JsonObject =
        received.single { it.first == "Email/set" }.second["create"]!!.jsonObject["draft"]!!.jsonObject

    private fun addresses(field: String): List<String> =
        createdDraft()[field]?.jsonArray?.map { it.jsonObject["email"]!!.jsonPrimitive.content }.orEmpty()

    /**
     * The create files the draft in the mailbox the session names under the `drafts` role, and
     * carries the composer's own From (issue #31): a sub-account's draft filed under the login's
     * address is a draft that will be sent by the wrong person.
     */
    @Test fun `the jmap create files the draft under the drafts role with its own from`() {
        serveJmap()
        val drafts = drafts(
            imap = RecordingImap(store, originalAddressing = null),
            mailboxDao = unusableDao<MailboxDao>(),
            emailDao = emailDaoOver(emptyList(), mutableListOf()),
            localDraftDao = localDraftDaoHolding(row(), mutableListOf()),
            host = RecordingHost(jmapSession()),
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.JMAP), "local-draft:1") }

        assertEquals(listOf("mb-drafts"), createdDraft()["mailboxIds"]!!.jsonObject.keys.toList())
        assertEquals(listOf("alex+delegated@example.test"), addresses("from"))
        assertEquals(listOf("bob@example.test"), addresses("to"))
        assertEquals(listOf("carol@example.test", "erin@example.test"), addresses("cc"))
        assertEquals(listOf("dave@example.test"), addresses("bcc"))
        assertEquals("Lunch", createdDraft()["subject"]!!.jsonPrimitive.content)
        // The receipt request is stored with the draft, or the box comes back clear on reopen (D1).
        assertEquals(
            listOf("alex+delegated@example.test"),
            addresses("header:Disposition-Notification-To:asAddresses"),
        )
        // The typed text, which nothing else on this branch reads: the local cache write is pinned
        // elsewhere, so an empty body here reaches the server while the list still shows the draft.
        assertEquals(
            "One o'clock?",
            createdDraft()["bodyValues"]!!.jsonObject["body"]!!.jsonObject["value"]!!.jsonPrimitive.content,
        )
    }

    /**
     * The original is destroyed through the repository's JMAP destroy, opposed to the folder the
     * cache says it is in (#122) and never to a numbering: a JMAP id still names the draft after
     * another client has moved it out of Drafts, and destroying it there loses filed mail.
     */
    @Test fun `the replaced original is destroyed against the folder the cache names`() {
        serveJmap()
        val host = RecordingHost(jmapSession())
        val upserted = mutableListOf<EmailEntity>()
        val drafts = drafts(
            imap = RecordingImap(store, originalAddressing = null),
            mailboxDao = mailboxDaoAnswering(mapOf("drafts" to "mb-drafts"), mutableListOf()),
            // Filed outside Drafts by another client since the composer read the id: that is the
            // case #122 exists for, and the role folder must not be what the destroy is held to.
            emailDao = emailDaoOver(listOf(cachedDraft("srv-1", "mb-archive")), upserted),
            localDraftDao = localDraftDaoHolding(row("srv-1"), mutableListOf()),
            host = host,
        )

        runBlocking { drafts.uploadLocalDraft(credentials(MailProtocol.JMAP), "local-draft:1") }

        assertEquals(listOf(Destroy(listOf("srv-1"), "mb-archive")), host.destroyed)
        assertEquals(emptyList<Expunge>(), host.expunged)
        // The saved copy is cached under the id the server answered, or the list flickers empty.
        assertEquals(listOf("srv-9"), upserted.map { it.id })
    }

    private companion object {
        const val ACCOUNT = "account-1"
    }
}

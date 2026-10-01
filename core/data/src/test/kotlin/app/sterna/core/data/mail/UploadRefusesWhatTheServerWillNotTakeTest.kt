package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
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
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `MailRepository.uploadAttachment` RUN against a JMAP server advertising `maxSizeUpload` (#242):
 * a file over the limit is refused before the upload leaves, one at the limit or under a server
 * that says nothing goes out. What is read is the list of requests the server received.
 *
 * What this does NOT cover:
 * - How the composer words the refusal; only the exception and its sizes are pinned.
 * - IMAP, whose attachments never reach this upload.
 */
class UploadRefusesWhatTheServerWillNotTakeTest {

    private val received = mutableListOf<String>()
    private val uploadedBytes = mutableListOf<Long>()

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    /** Routed on path and method, never on arrival order. [coreCapability] is the raw JSON object. */
    private fun serve(coreCapability: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") {
                    received += "session"
                    return json(sessionBody(coreCapability))
                }
                if (request.path.orEmpty().startsWith("/upload/")) {
                    received += "upload ${request.path}"
                    uploadedBytes += request.bodySize
                    return json("""{"accountId":"acc1","blobId":"blob-1","type":"application/pdf","size":${request.bodySize}}""")
                }
                val body = request.body.readUtf8()
                val method = Json.parseToJsonElement(body).jsonObject["methodCalls"]!!
                    .jsonArray[0].jsonArray[0].jsonPrimitive.content
                received += method
                return when (method) {
                    "Identity/get" -> json("""{"methodResponses":[["Identity/get",{"list":[]},"i0"]]}""")
                    "Mailbox/get" -> json("""{"methodResponses":[["Mailbox/get",{"list":[]},"c0"]]}""")
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** One mail account, so `reconcileLinkedAccounts` returns before it touches a DAO. */
    private fun sessionBody(coreCapability: String): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":$coreCapability,"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${server.url("/jmap/api")}",
         "uploadUrl":"${server.url("/upload/")}{accountId}/","state":"sess-1"}
        """.trimIndent()

    private fun repository(): MailRepository {
        val store = RecordingAccountStorage()
        return MailRepository(
            client = JmapClient(),
            emailDao = unusableDao<EmailDao>(),
            emailFtsDao = unusableDao<EmailFtsDao>(),
            emailBodyDao = unusableDao<EmailBodyDao>(),
            mailboxDao = unusableDao<MailboxDao>(),
            imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
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
        )
    }

    private fun credentials(): AccountCredentials = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex",
        password = "secret",
        id = "account-1",
        protocol = MailProtocol.JMAP,
        authType = AuthType.BASIC,
    )

    private fun upload(size: Int) = runBlocking {
        repository().uploadAttachment(credentials(), ByteArray(size), "application/pdf", "report.pdf", "attachment", null)
    }

    @Test fun aFileOverTheLimitIsRefusedAndNeverUploaded() {
        serve("""{"maxSizeUpload":1000}""")

        val refused = assertThrows(ContentTooLargeException::class.java) { upload(1001) }

        assertEquals(1001L, refused.bytes)
        assertEquals(1000L, refused.maxBytes)
        assertEquals(listOf("session", "Identity/get", "Mailbox/get"), received)
    }

    @Test fun aFileExactlyAtTheLimitIsUploaded() {
        serve("""{"maxSizeUpload":1000}""")

        val part = upload(1000)

        assertEquals("blob-1", part.blobId)
        assertEquals(listOf("session", "Identity/get", "Mailbox/get", "upload /upload/acc1/"), received)
        assertEquals(listOf(1000L), uploadedBytes)
    }

    @Test fun aServerThatAdvertisesNoLimitGetsTheUpload() {
        serve("""{}""")

        val part = upload(5000)

        assertEquals("blob-1", part.blobId)
        assertEquals(listOf("session", "Identity/get", "Mailbox/get", "upload /upload/acc1/"), received)
        assertEquals(listOf(5000L), uploadedBytes)
    }
}

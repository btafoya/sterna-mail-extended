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
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.EmailLabels
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `MailRepository.setLabel` run against a JMAP server whose answer to `Email/set` each test chooses,
 * over a stand-in for the `emails` table that keeps one `labelsJson` per (account, id). What the
 * server was sent, and what the row held when it was sent, are both read back.
 *
 * What this does not cover: the `notFound` prune, which is `setFlagged`'s own path; the cursor
 * advance; and that Room runs `setLabels` as written, which no JVM test here executes.
 */
class SetLabelTest {

    private val labels = linkedMapOf<String, String>()
    private val setLabelsCalls = CopyOnWriteArrayList<String>()

    /** What the row held when each `Email/set` reached the server. */
    private val rowAtTheServer = CopyOnWriteArrayList<String?>()
    private val patches = CopyOnWriteArrayList<JsonObject>()

    @Volatile private var accept = true

    private var server: MockWebServer? = null

    private val store = object : RecordingAccountStorage() {
        override fun account(id: String): StoredAccount? =
            StoredAccount(id = id, server = "unused", username = "alex", protocol = MailProtocol.JMAP)
    }

    @Suppress("UNCHECKED_CAST")
    private fun emailDao(): EmailDao = Proxy.newProxyInstance(
        EmailDao::class.java.classLoader,
        arrayOf(EmailDao::class.java),
    ) { _, method, args ->
        when (method.name) {
            "mailboxOf" -> "inbox".takeIf { labels.containsKey("${args[0]}/${args[1]}") }
            "labelsJsonOf" -> labels["${args[0]}/${args[1]}"]
            "setLabels" -> {
                val key = "${args[0]}/${args[1]}"
                setLabelsCalls += args[2] as String
                if (labels.containsKey(key)) labels[key] = args[2] as String
                Unit
            }
            else -> error("EmailDao.${method.name} was called by a path this test does not model.")
        }
    } as EmailDao

    private fun repository(): MailRepository = MailRepository(
        client = JmapClient(),
        emailDao = emailDao(),
        emailFtsDao = unusableDao<EmailFtsDao>(),
        emailBodyDao = unusableDao<EmailBodyDao>(),
        mailboxDao = unusableDao<MailboxDao>(),
        imap = ImapMailService(ImapClient(), SmtpClient(), ReadMarkRaceCountsOnceTest.unusedTokenRefresher()),
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

    private fun start(): AccountCredentials {
        val mock = MockWebServer()
        mock.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return json(sessionBody(mock))
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject.getValue("methodCalls").jsonArray[0].jsonArray
                return when (call[0].jsonPrimitive.content) {
                    "Mailbox/get" -> json("""{"methodResponses":[["Mailbox/get",{"accountId":"acc1","state":"m1","list":[],"notFound":[]},"c0"]]}""")
                    "Identity/get" -> json("""{"methodResponses":[["Identity/get",{"list":[]},"i0"]]}""")
                    "Email/set" -> {
                        rowAtTheServer += labels["$ACCOUNT/m1"]
                        val update = call[1].jsonObject.getValue("update").jsonObject
                        patches += update.getValue("m1").jsonObject
                        if (accept) {
                            json("""{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"e1","newState":"e2","updated":{"m1":null}},"s0"]]}""")
                        } else {
                            json(
                                """{"methodResponses":[["Email/set",{"accountId":"acc1","oldState":"e1","newState":"e1",""" +
                                    """"notUpdated":{"m1":{"type":"invalidPatch"}}},"s0"]]}""",
                            )
                        }
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
        server?.shutdown()
    }

    @Test fun `a label the server takes is added to the row, after the server answered`() {
        val credentials = start()
        labels["$ACCOUNT/m1"] = EmailLabels.encode(listOf("work"))

        runBlocking { repository().setLabel(credentials, "m1", "read-later", set = true) }

        assertEquals(listOf("""{"keywords/${'$'}label:read-later":true}"""), patches.map { it.toString() })
        assertEquals(listOf("work", "read-later"), EmailLabels.decode(labels["$ACCOUNT/m1"]))
        assertEquals(
            "the row was written before the server answered",
            listOf(EmailLabels.encode(listOf("work"))),
            rowAtTheServer.toList(),
        )
    }

    @Test fun `a label the server takes off leaves the row, and only that one`() {
        val credentials = start()
        labels["$ACCOUNT/m1"] = EmailLabels.encode(listOf("work", "read-later", "x"))

        runBlocking { repository().setLabel(credentials, "m1", "read-later", set = false) }

        assertEquals(listOf("""{"keywords/${'$'}label:read-later":null}"""), patches.map { it.toString() })
        assertEquals(listOf("work", "x"), EmailLabels.decode(labels["$ACCOUNT/m1"]))
    }

    @Test fun `a label the server refuses leaves the row as it was, and the refusal reaches the caller`() {
        val credentials = start()
        accept = false
        val before = EmailLabels.encode(listOf("work"))
        labels["$ACCOUNT/m1"] = before

        val outcome = runCatching { runBlocking { repository().setLabel(credentials, "m1", "read-later", set = true) } }

        assertTrue("the refusal must reach the caller, which takes the checkbox back", outcome.isFailure)
        assertEquals(1, patches.size)
        assertEquals(before, labels["$ACCOUNT/m1"])
        assertEquals(emptyList<String>(), setLabelsCalls.toList())
    }

    @Test fun `an IMAP account is refused before anything is written or sent`() {
        val imap = AccountCredentials(
            server = "unused",
            username = "alex",
            password = "secret",
            id = ACCOUNT,
            protocol = MailProtocol.IMAP,
            imap = MailEndpoint("unused.test", 143, ConnectionSecurity.NONE),
        )
        labels["$ACCOUNT/m1"] = "[]"

        val outcome = runCatching { runBlocking { repository().setLabel(imap, "m1", "work", set = true) } }

        assertTrue("expected IllegalStateException, got $outcome", outcome.exceptionOrNull() is IllegalStateException)
        assertEquals("[]", labels["$ACCOUNT/m1"])
        assertEquals(emptyList<String>(), setLabelsCalls.toList())
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

    private companion object {
        const val ACCOUNT = "acc1"
    }
}

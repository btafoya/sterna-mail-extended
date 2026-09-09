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
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
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
import java.io.File
import java.lang.reflect.Proxy

/**
 * `MailRepository.destroyAll` RUN, on its JMAP branch, against a JMAP server that answers: the
 * guard of Codeberg #122 is executed rather than read, and what it spares and what it destroys are
 * read off the wire.
 *
 * The guard says a message may only be destroyed where the server still files it in the expected
 * folder alone, so a message rescued out of the Trash by a move keeps its JMAP id and must survive
 * the purge that was confirmed while it was still in there. Until this test the whole traversal
 * from `destroyAll` down to the `Email/set` was held by source lints alone
 * ([DestroyChecksTheFolderWiringTest]), which read the text of the decision and cannot take the
 * branch.
 *
 * The rule itself is [TrashPurge.destroyableIds] and it has its own cases; this test never
 * recomputes it. The expected ids are written out.
 *
 * What this does NOT cover:
 * - The IMAP branch, which never reaches this code ([ImapDestroyUnderNumberingTest] holds it).
 * - A location read that fails. `aFailedCheckDestroysNothingAndPropagates` states that it must
 *   propagate, and states it on the source; nothing here throws.
 * - Batching. Both waves are one batch, well under the session's advertised limits.
 */
class DestroySparesWhatMovedOnTheWireTest {

    /** One request the server actually received, in the order it arrived. */
    private data class Sent(val method: String, val body: String, val authorization: String?)

    private val received = mutableListOf<Sent>()
    private val infoLines = mutableListOf<Triple<String, String, Throwable?>>()
    private val warnLines = mutableListOf<Triple<String, String, Throwable?>>()
    private val daoCalls = mutableListOf<Pair<String, List<Any?>>>()

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    /**
     * Routed on the method the body asks for, never on arrival order: `jmapSession` walks Session,
     * Identity/get and Mailbox/get before the check's Email/get, and a queue of canned answers
     * would silently hand the wrong one to the wrong call the day that order changes.
     */
    private fun serve(locations: Map<String, List<String>>, destroyed: List<String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val authorization = request.getHeader("Authorization")
                if (request.method == "GET") {
                    received += Sent("session", request.path.orEmpty(), authorization)
                    return json(sessionBody())
                }
                val body = request.body.readUtf8()
                val method = methodOf(body)
                received += Sent(method, body, authorization)
                return when (method) {
                    "Identity/get" -> json("""{"methodResponses":[["Identity/get",{"list":[]},"i0"]]}""")
                    "Mailbox/get" -> json("""{"methodResponses":[["Mailbox/get",{"list":[]},"c0"]]}""")
                    "Email/get" -> json(emailGetBody(locations))
                    "Email/set" -> json(emailSetBody(destroyed))
                    else -> MockResponse().setResponseCode(500)
                }
            }
        }
    }

    private fun json(body: String): MockResponse =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** A session with exactly one mail account, which is what lets `reconcileLinkedAccounts`
     *  return before it touches a DAO or launches anything on the background scope. */
    private fun sessionBody(): String =
        """
        {"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{}},
         "accounts":{"acc1":{"name":"Alex","accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},
         "primaryAccounts":{"urn:ietf:params:jmap:mail":"acc1"},
         "username":"alex","apiUrl":"${server.url("/jmap/api")}","state":"sess-1"}
        """.trimIndent()

    /** `{mailboxId: true}` per RFC 8621 §4.1.1, the shape the location read decodes. */
    private fun emailGetBody(locations: Map<String, List<String>>): String {
        val rows = locations.entries.joinToString(",") { (id, folders) ->
            """{"id":"$id","mailboxIds":{${folders.joinToString(",") { "\"$it\":true" }}}}"""
        }
        return """{"methodResponses":[["Email/get",{"accountId":"acc1","state":"s1","list":[$rows],"notFound":[]},"g0"]]}"""
    }

    private fun emailSetBody(destroyed: List<String>): String =
        """{"methodResponses":[["Email/set",{"accountId":"acc1","newState":"s2","destroyed":[""" +
            destroyed.joinToString(",") { "\"$it\"" } +
            """],"notDestroyed":{}},"s0"]]}"""

    private fun methodOf(body: String): String =
        callOf(body)[0].jsonPrimitive.content

    private fun argsOf(body: String): JsonObject = callOf(body)[1].jsonObject

    private fun callOf(body: String) =
        Json.parseToJsonElement(body).jsonObject["methodCalls"]!!.jsonArray[0].jsonArray

    private fun stringsAt(args: JsonObject, key: String): List<String> =
        args[key]!!.jsonArray.map { it.jsonPrimitive.content }

    /**
     * The only DAO the destroy path is allowed to reach, answering nothing and writing down its
     * arguments minus the continuation a suspend call appends. The ten others stay [unusableDao].
     */
    private fun recordingEmailDao(): EmailDao =
        Proxy.newProxyInstance(EmailDao::class.java.classLoader, arrayOf(EmailDao::class.java)) { _, method, args ->
            val passed = args.orEmpty().dropLast(1)
            when (method.name) {
                "emailsByIds" -> { daoCalls += "emailsByIds" to passed; emptyList<Any>() }
                "deleteByIds" -> { daoCalls += "deleteByIds" to passed; Unit }
                else -> error("EmailDao.${method.name} was called by a path that should not touch the database.")
            }
        } as EmailDao

    private fun repository(): MailRepository {
        val store = RecordingAccountStorage()
        return MailRepository(
            client = JmapClient(),
            emailDao = recordingEmailDao(),
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
            mailboxUidValidityDao = unusableDao<MailboxUidValidityDao>(),
            outboxFilesDir = File("outbox-never-read"),
            localDraftFilesDir = File("drafts-never-read"),
            logInfo = { tag, message, error -> infoLines += Triple(tag, message, error) },
            logWarn = { tag, message, error -> warnLines += Triple(tag, message, error) },
        )
    }

    /** Basic auth, no OAuth: the token refresher answers null without a request of its own, and
     *  `http://` survives normalisation, so the session URL is the local server's, in clear. */
    private fun credentials(): AccountCredentials = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex",
        password = "secret",
        id = "account-1",
        protocol = MailProtocol.JMAP,
        authType = AuthType.BASIC,
    )

    /**
     * A caller that names no folder: [TrashPurge.destroyableIds] spares the whole wave, so the
     * destroy must stop before the wire. An unverifiable destroy destroys nothing.
     */
    @Test fun aWaveWithNoExpectedFolderIsSparedWholeAndNeverReachesTheServer() {
        serve(
            locations = mapOf("m1" to listOf("trash-1"), "m2" to listOf("trash-1")),
            destroyed = emptyList(),
        )

        val result = runBlocking {
            repository().destroyAll(credentials(), listOf("m1", "m2"), expectedMailboxId = null)
        }

        assertEquals(emptySet<String>(), result.succeeded)
        assertEquals(setOf("m1", "m2"), result.failed)
        assertEquals(
            "the check must reach the server even with no folder to check against, and the " +
                "destroy must not",
            listOf("session", "Identity/get", "Mailbox/get", "Email/get"),
            received.map { it.method },
        )
        assertEquals(
            listOf(
                Triple(
                    "MailSync",
                    "destroy spared account-1/null: 2 of 2 not in that folder alone: m1, m2",
                    null,
                ),
            ),
            infoLines,
        )
        assertEquals(emptyList<Triple<String, String, Throwable?>>(), warnLines)
        assertEquals(emptyList<Pair<String, List<Any?>>>(), daoCalls)
    }

    /**
     * The message of #122: two ids confirmed together, one of which the server now files in the
     * Trash AND somewhere else, because it was moved out between the confirmation and the purge.
     * It must come back as failed and stay on the server; only the other may be destroyed.
     */
    @Test fun theIdTheServerAlsoFilesElsewhereIsSparedAndTheOtherIsDestroyed() {
        serve(
            locations = mapOf(
                "m-elsewhere" to listOf("trash-1", "archive-1"),
                "m-doomed" to listOf("trash-1"),
            ),
            destroyed = listOf("m-doomed"),
        )

        val result = runBlocking {
            repository().destroyAll(
                credentials(),
                listOf("m-elsewhere", "m-doomed"),
                expectedMailboxId = "trash-1",
            )
        }

        assertEquals(setOf("m-doomed"), result.succeeded)
        assertEquals(setOf("m-elsewhere"), result.failed)
        assertEquals(
            listOf("session", "Identity/get", "Mailbox/get", "Email/get", "Email/set"),
            received.map { it.method },
        )

        // The check asked about both ids, for the two properties the verdict is computed from.
        val checkRequest = received.single { it.method == "Email/get" }
        val check = argsOf(checkRequest.body)
        assertEquals("acc1", check["accountId"]!!.jsonPrimitive.content)
        // Under the credentials of the account being purged: folder ids collide between two
        // accounts of one server (#31), so a check read under the wrong account answers about
        // another mailbox that exists.
        assertEquals("Basic YWxleDpzZWNyZXQ=", checkRequest.authorization)
        assertEquals(listOf("m-elsewhere", "m-doomed"), stringsAt(check, "ids"))
        assertEquals(listOf("id", "mailboxIds"), stringsAt(check, "properties"))

        // And the destroy carried the survivor alone. This is the assertion #122 is about: an
        // Email/set that also names `m-elsewhere` is the rescued message destroyed where it was
        // rescued to, and a BulkResult alone cannot see it.
        val destroy = received.single { it.method == "Email/set" }
        assertEquals("acc1", argsOf(destroy.body)["accountId"]!!.jsonPrimitive.content)
        assertEquals(listOf("m-doomed"), stringsAt(argsOf(destroy.body), "destroy"))
        assertEquals("Basic YWxleDpzZWNyZXQ=", destroy.authorization)

        assertEquals(
            listOf(
                Triple(
                    "MailSync",
                    "destroy spared account-1/trash-1: 1 of 2 not in that folder alone: m-elsewhere",
                    null,
                ),
            ),
            infoLines,
        )
        assertEquals(emptyList<Triple<String, String, Throwable?>>(), warnLines)

        // The cache eviction is scoped the same way: the spared row stays.
        assertEquals(
            listOf(
                "emailsByIds" to listOf<Any?>("account-1", listOf("m-doomed")),
                "deleteByIds" to listOf<Any?>("account-1", listOf("m-doomed")),
            ),
            daoCalls,
        )
    }
}

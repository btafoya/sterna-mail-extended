package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.filter.SieveVacation
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.jmapDiscoveryClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.JmapAccount
import app.sterna.core.jmap.model.JmapSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A JMAP filters save, run through `MailAccounts` against a JMAP server that answers, on a mailbox
 * whose `sterna` script an IMAP account has already given a responder: Stalwart serves one Sieve
 * store to JMAP and ManageSieve alike, so a JMAP save that writes `sterna` without that responder
 * switches it off, without a question.
 *
 * What is pinned is the blob that leaves the process and the script the `SieveScript/set` updates.
 *
 * What this does NOT cover:
 * - A download of the `sterna` script that fails: the save throws before uploading, nothing here
 *   runs that path.
 * - The server-side effect of `onSuccessActivateScript` on another active script.
 */
class FiltersOverJmapKeepTheResponderTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

    private data class Script(val id: String, val name: String, val blobId: String, val active: Boolean, val body: String)

    /** The account's Sieve scripts, as `SieveScript/get` lists them and the download serves them. */
    private var scripts: List<Script> = emptyList()

    private val methods = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val uploads = CopyOnWriteArrayList<String>()
    private val downloads = CopyOnWriteArrayList<String>()

    private fun serveJmap() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/download/") -> {
                        val blobId = path.removePrefix("/download/acc1/").substringBefore('/')
                        downloads += blobId
                        val script = scripts.firstOrNull { it.blobId == blobId }
                            ?: return MockResponse().setResponseCode(404)
                        MockResponse().setBody(script.body)
                    }
                    path.startsWith("/upload/") -> {
                        uploads += request.body.readUtf8()
                        json("""{"accountId":"acc1","blobId":"uploaded-1","type":"application/sieve","size":1}""")
                    }
                    else -> jmapMethod(request)
                }
            }
        }
    }

    private fun jmapMethod(request: RecordedRequest): MockResponse {
        val call = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray[0].jsonArray
        val name = call[0].jsonPrimitive.content
        val args = call[1].jsonObject
        methods += name to args
        val response = when (name) {
            "SieveScript/get" -> {
                val list = scripts.joinToString(",", "[", "]") {
                    """{"id":"${it.id}","name":"${it.name}","blobId":"${it.blobId}","isActive":${it.active}}"""
                }
                """["SieveScript/get",{"accountId":"acc1","state":"1","list":$list,"notFound":[]},"s0"]"""
            }
            "SieveScript/validate" -> """["SieveScript/validate",{"accountId":"acc1","error":null},"s0"]"""
            "SieveScript/set" -> {
                val updated = args["update"]?.jsonObject?.keys?.joinToString(",", "{", "}") { "\"$it\":null" }
                if (updated != null) {
                    """["SieveScript/set",{"accountId":"acc1","updated":$updated},"s0"]"""
                } else {
                    """["SieveScript/set",{"accountId":"acc1","created":{"new":{"id":"s-new"}}},"s0"]"""
                }
            }
            else -> error("unexpected JMAP call: $name $args")
        }
        return json("""{"methodResponses":[$response],"sessionState":"1"}""")
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private val base get() = "http://${server.hostName}:${server.port}"

    private val credentials get() = AccountCredentials(
        server = base,
        username = "alex@example.test",
        password = "secret",
        id = "account-1",
        protocol = MailProtocol.JMAP,
        authType = AuthType.BASIC,
    )

    private inner class Host : MailAccountHost {
        override suspend fun jmapSession(credentials: AccountCredentials) = MailRepository.JmapAccountSession(
            credentials = credentials,
            session = JmapSession(
                capabilities = mapOf(
                    Jmap.CORE_CAPABILITY to JsonObject(emptyMap()),
                    Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()),
                    Jmap.SIEVE_CAPABILITY to JsonObject(emptyMap()),
                ),
                accounts = mapOf("acc1" to JmapAccount(name = "Alex")),
                primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "acc1"),
                username = "alex",
                apiUrl = "$base/jmap/api",
                downloadUrl = "$base/download/{accountId}/{blobId}/{name}?type={type}",
                uploadUrl = "$base/upload/{accountId}/",
            ),
            accountId = "acc1",
            auth = BasicAuth("alex", "secret"),
            rolesToMailboxId = emptyMap(),
        )

        override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth = error("not on this path")
        override suspend fun firstSync(credentials: AccountCredentials): MailboxMeta = error("not on this path")
        override fun cachedJmapSession(): MailRepository.JmapAccountSession? = null
    }

    private inline fun <reified T> untouched(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, called, _ ->
            error("${T::class.simpleName}.${called.name} was not expected here.")
        } as T

    private val noManageSieve = object : SieveConnector {
        override suspend fun <T> session(
            host: String,
            port: Int,
            username: String,
            password: String,
            block: SieveScripts.() -> T,
        ): T = error("a JMAP account opened ManageSieve")
    }

    private fun accounts(): MailAccounts {
        val store = RecordingAccountStorage()
        return MailAccounts(
            client = JmapClient(),
            discoveryClient = jmapDiscoveryClient(),
            imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
            emailDao = untouched<EmailDao>(),
            emailFtsDao = untouched<EmailFtsDao>(),
            emailBodyDao = untouched<EmailBodyDao>(),
            mailboxDao = untouched<MailboxDao>(),
            snoozedDao = untouched<SnoozedDao>(),
            accountStore = store,
            oauthClient = OAuthClient(),
            bgScope = CoroutineScope(Dispatchers.Unconfined),
            host = Host(),
            sieve = noManageSieve,
        )
    }

    private val before = listOf(FilterRule(name = "Boss", field = RuleField.FROM, value = "boss@example.test", moveTo = "Work"))

    private val after = before + FilterRule(name = "News", field = RuleField.SUBJECT, value = "newsletter", markRead = true)

    private val away = SieveVacation(
        isEnabled = true,
        subject = "Away",
        textBody = "Back on the 28th.",
        addresses = listOf("alex@example.test"),
    )

    private fun sterna(body: String, active: Boolean) = Script("s-sterna", "sterna", "blob-sterna", active, body)

    /** The ids the `SieveScript/set` calls updated, in order. */
    private fun updatedIds(): List<String> = methods.filter { it.first == "SieveScript/set" }
        .map { it.second["update"]?.jsonObject?.keys?.single() ?: "<create>" }

    @Test fun `a JMAP filters save keeps the responder an IMAP account wrote into the sterna script`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before, away), active = true))
        val accounts = accounts()

        val loaded = runBlocking { accounts.loadFilterRules(credentials) }
        assertEquals(FilterRulesState.Loaded(rules = before, foreignActiveScript = false, scriptUnreadable = false), loaded)

        runBlocking { accounts.saveFilterRules(credentials, after) }

        val uploaded = uploads.single()
        assertTrue(uploaded, uploaded.lines().any { it.startsWith("# STERNA-VACATION-V1: ") })
        assertTrue(uploaded, "vacation :subject \"Away\" :addresses [\"alex@example.test\"] \"Back on the 28th.\";" in uploaded)
        assertTrue(uploaded, "header :contains \"subject\" \"newsletter\"" in uploaded)
        assertEquals(SieveCodec.generate(after, away), uploaded)
        assertEquals(listOf("s-sterna"), updatedIds())
    }

    @Test fun `an unreadable responder marker is not carried into the JMAP save`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before) + "# STERNA-VACATION-V1: {pas du json\n", active = true))

        runBlocking { accounts().saveFilterRules(credentials, after) }

        val uploaded = uploads.single()
        assertFalse(uploaded, "STERNA-VACATION-V1" in uploaded)
        assertEquals(SieveCodec.generate(after), uploaded)
        assertEquals(listOf("s-sterna"), updatedIds())
    }

    @Test fun `the responder is read from the script named sterna, not from the active one`() {
        serveJmap()
        scripts = listOf(
            sterna(SieveCodec.generate(before, away), active = false),
            Script("s-vacation", "vacation", "blob-vacation", active = true, body = "require [\"vacation\"];\nvacation \"Gone.\";\n"),
        )

        runBlocking { accounts().saveFilterRules(credentials, after) }

        assertEquals(listOf("blob-sterna"), downloads)
        assertEquals(SieveCodec.generate(after, away), uploads.single())
        assertEquals(listOf("s-sterna"), updatedIds())
    }

    @Test fun `without a sterna script nothing is downloaded and the save creates one`() {
        serveJmap()
        scripts = listOf(Script("s-vacation", "vacation", "blob-vacation", active = true, body = "vacation \"Gone.\";\n"))

        runBlocking { accounts().saveFilterRules(credentials, after) }

        assertEquals(emptyList<String>(), downloads)
        assertEquals(SieveCodec.generate(after), uploads.single())
        assertEquals(listOf("<create>"), updatedIds())
    }
}

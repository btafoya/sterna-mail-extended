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
import app.sterna.core.data.filter.FilterScriptStatus
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
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The responder and filters screens of a JMAP account whose admin withdrew those permissions (#286):
 * Stalwart keeps `vacationresponse` and `sieve` in the session-wide capabilities and drops them
 * from the account's own `accountCapabilities` only. The screens must say the server does not
 * offer them, without a call the server would refuse with `forbidden`.
 *
 * What this does NOT cover: a session whose account map is empty, the global fallback, which the
 * existing fixtures without `accountCapabilities` keep pinned.
 */
class CapabilityWithdrawnFromTheAccountTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"methodResponses":[["error",{"type":"forbidden"},"x0"]],"sessionState":"1"}""")
        }
        server.start()
    }

    @After fun stop() {
        server.shutdown()
    }

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
                    Jmap.VACATION_CAPABILITY to JsonObject(emptyMap()),
                    Jmap.SIEVE_CAPABILITY to JsonObject(emptyMap()),
                ),
                accounts = mapOf(
                    "acc1" to JmapAccount(
                        name = "Alex",
                        accountCapabilities = mapOf(Jmap.MAIL_CAPABILITY to JsonObject(emptyMap())),
                    ),
                ),
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
            serverContactDao = untouched<app.sterna.core.data.db.ServerContactDao>(),
            accountStore = store,
            oauthClient = OAuthClient(),
            bgScope = CoroutineScope(Dispatchers.Unconfined),
            host = Host(),
            sieve = noManageSieve,
        )
    }

    @Test fun theResponderSaysUnsupportedWithoutACall() = runBlocking {
        assertEquals(VacationState.Unsupported, accounts().loadVacation(credentials))
        assertEquals(0, server.requestCount)
    }

    @Test fun theFiltersSayUnsupportedWithoutACall() = runBlocking {
        assertEquals(FilterRulesState.Unsupported, accounts().loadFilterRules(credentials))
        assertEquals(0, server.requestCount)
    }

    @Test fun theFilterStatusIsEmptyWithoutACall() = runBlocking {
        assertEquals(FilterScriptStatus(), accounts().loadFilterScriptStatus(credentials))
        assertEquals(0, server.requestCount)
    }
}

package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.DiscoveredMailAccount
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.SnoozedDao
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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * `MailAccounts.reconcileLinkedAccounts` RUN, against a store that answers what it is told to and a
 * JMAP server that answers `Mailbox/get`. It is the one path of the class that can destroy an
 * account, and until here it was held by source lints alone, which read the text of a decision
 * and cannot take either branch of it.
 *
 * What is pinned is what leaves the class: the raw list and the probes the store is handed (#129),
 * the five tables purged for exactly the ids the store answered and no other (#31), the early
 * return that costs a single-account login no request, and the after-add entry reusing the cached
 * session's own auth for the same login only. Every expected value is written out; nothing here
 * recomputes the rule that produced it.
 *
 * What this does NOT cover:
 * - The store's own verdict. `LinkedAccountsDiffTest` executes the diff it is made of; here the
 *   store is told what to answer, so a wrong diff would pass through untouched.
 * - What a purge does to rows: each DAO call is recorded, never executed.
 * - A probe that fails. The server answers every `Mailbox/get` here; a refusal reaches the store
 *   as a failed `Result`, and what the diff makes of one is `LinkedAccountsDiffTest`'s.
 * - Every other function of the class: discovery, OAuth, the creators, the identities and the
 *   server settings stay under their source lints.
 */
class MailAccountsPruneFollowsTheStoreTest {

    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.start()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val call = Json.parseToJsonElement(request.body.readUtf8())
                    .jsonObject["methodCalls"]!!.jsonArray[0].jsonArray
                val name = call[0].jsonPrimitive.content
                val args = call[1].jsonObject
                seen += Seen(name, args["accountId"]!!.jsonPrimitive.content, request.getHeader("Authorization"))
                val body = when (name) {
                    "Mailbox/get" -> """{"methodResponses":[["Mailbox/get",{"list":$mailboxList},"c0"]]}"""
                    else -> error("unexpected JMAP call: $name $args")
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
    }

    @After fun stop() {
        server.shutdown()
    }

    /** One JMAP request the server received: the method, the account it named, its credential. */
    private data class Seen(val method: String, val accountId: String, val authorization: String?)

    private val seen = mutableListOf<Seen>()

    /** What the server answers a `Mailbox/get` with, as the raw `list` of the response. */
    private val mailboxList = """[{"id":"mb-shared","name":"Shared inbox"}]"""

    /** A store whose two answers on this path are chosen by the test, not derived from anything. */
    private class StoreDeciding : RecordingAccountStorage() {
        var linked: List<StoredAccount> = emptyList()
        var verdict: List<String> = emptyList()
        var known: AccountCredentials? = null

        override fun linkedAccounts(loginId: String): List<StoredAccount> {
            super.linkedAccounts(loginId)
            return linked
        }

        override fun reconcileLinkedAccounts(
            loginId: String,
            discovered: List<DiscoveredMailAccount>,
            probes: Map<String, Result<*>>,
        ): List<String> {
            super.reconcileLinkedAccounts(loginId, discovered, probes)
            return verdict
        }

        override fun credentials(id: String): AccountCredentials? {
            super.credentials(id)
            return known?.takeIf { it.id == id }
        }
    }

    private val store = StoreDeciding()

    /** The repository's side of the seam: only the cache read is reachable from this path. */
    private class RecordingHost(private val cached: MailRepository.JmapAccountSession? = null) : MailAccountHost {
        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession =
            error("the JMAP session seam was reached by a path that should not need one")

        override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth =
            error("no path here builds an auth of its own")

        override suspend fun firstSync(credentials: AccountCredentials): MailboxMeta =
            error("no path here creates an account")

        override fun cachedJmapSession(): MailRepository.JmapAccountSession? = cached
    }

    /** The one call each table takes on this path, recorded under the account it named. */
    private inline fun <reified T> daoRecording(method: String, into: MutableList<String>): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, called, args ->
            if (called.name != method) error("${T::class.simpleName}.${called.name} was not expected here.")
            into += args[0] as String
            Unit
        } as T

    private class Purged {
        val emails = mutableListOf<String>()
        val index = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val mailboxes = mutableListOf<String>()
        val snoozed = mutableListOf<String>()
        val hook = mutableListOf<String>()
    }

    private val purged = Purged()

    private fun accounts(host: MailAccountHost = RecordingHost()) = MailAccounts(
        client = JmapClient(),
        discoveryClient = jmapDiscoveryClient(),
        imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
        emailDao = daoRecording<EmailDao>("deleteForAccount", purged.emails),
        emailFtsDao = daoRecording<EmailFtsDao>("clearAccount", purged.index),
        emailBodyDao = daoRecording<EmailBodyDao>("deleteForAccount", purged.bodies),
        mailboxDao = daoRecording<MailboxDao>("deleteForAccount", purged.mailboxes),
        snoozedDao = daoRecording<SnoozedDao>("deleteForAccount", purged.snoozed),
        accountStore = store,
        oauthClient = OAuthClient(),
        // Unconfined: the purge is launched, and must have run before the assertion reads it.
        bgScope = CoroutineScope(Dispatchers.Unconfined),
        host = host,
    ).also { it.onAccountPruned = { purged.hook += it } }

    private fun credentials() = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex@example.test",
        password = "secret",
        id = ACCOUNT,
        protocol = MailProtocol.JMAP,
        authType = AuthType.BASIC,
    )

    private fun mailAccount(name: String) =
        JmapAccount(name = name, accountCapabilities = mapOf(Jmap.MAIL_CAPABILITY to JsonObject(emptyMap())))

    /** A session listing [accounts] as mail accounts, the first of them primary. */
    private fun session(vararg accounts: Pair<String, String>) = JmapSession(
        capabilities = mapOf(
            Jmap.CORE_CAPABILITY to JsonObject(emptyMap()),
            Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()),
        ),
        accounts = accounts.associate { (id, name) -> id to mailAccount(name) },
        primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to accounts.first().first),
        username = "alex",
        apiUrl = server.url("/jmap/api").toString(),
    )

    private val twoAccounts get() = session("acc1" to "Alex", "acc2" to "Shared")

    private val auth = BasicAuth("alex", "secret")

    private fun reconcile(session: JmapSession) = runBlocking {
        accounts().reconcileLinkedAccounts(credentials(), session, auth)
    }

    private fun storeReconciles(): List<Map<String, Any?>> =
        store.calls.filter { it.first == "reconcileLinkedAccounts" }.map { it.second }

    /**
     * The store is handed the session's list whole, primary first and names taken from the
     * session, plus one probe per NON-primary account: filtering the list here is what turns a
     * passing "forbidden" into a prune (#129), and probing the primary is a request nothing reads.
     */
    @Test fun `the store receives the raw list and one probe per non-primary account`() {
        reconcile(twoAccounts)

        val args = store.args("reconcileLinkedAccounts")
        assertEquals(ACCOUNT, args["loginId"])
        assertEquals(
            listOf(DiscoveredMailAccount("acc1", "Alex"), DiscoveredMailAccount("acc2", "Shared")),
            args["discovered"],
        )
        @Suppress("UNCHECKED_CAST")
        val probes = args["probes"] as Map<String, Result<*>>
        assertEquals(listOf("acc2"), probes.keys.toList())
        @Suppress("UNCHECKED_CAST")
        val answered = probes["acc2"]!!.getOrThrow() as List<app.sterna.core.jmap.model.Mailbox>
        assertEquals(listOf("mb-shared" to "Shared inbox"), answered.map { it.id to it.name })
        assertEquals(listOf(Seen("Mailbox/get", "acc2", "Basic YWxleDpzZWNyZXQ=")), seen)
    }

    /**
     * The purge follows the store's verdict, not the session: each of the five tables is cleared
     * for exactly the id the store answered, and the app-layer hook runs for it first.
     */
    @Test fun `the purge follows the store's verdict`() {
        store.verdict = listOf("acc2")

        reconcile(twoAccounts)

        assertEquals(listOf("acc2"), purged.hook)
        assertEquals(listOf("acc2"), purged.emails)
        assertEquals(listOf("acc2"), purged.index)
        assertEquals(listOf("acc2"), purged.bodies)
        assertEquals(listOf("acc2"), purged.mailboxes)
        assertEquals(listOf("acc2"), purged.snoozed)
    }

    /** Same session, a store that prunes nothing: no table is touched and the hook stays silent. */
    @Test fun `a store that prunes nothing leaves every table alone`() {
        store.verdict = emptyList()

        reconcile(twoAccounts)

        assertEquals(emptyList<String>(), purged.hook)
        assertEquals(emptyList<String>(), purged.emails)
        assertEquals(emptyList<String>(), purged.index)
        assertEquals(emptyList<String>(), purged.bodies)
        assertEquals(emptyList<String>(), purged.mailboxes)
        assertEquals(emptyList<String>(), purged.snoozed)
    }

    /** A login with one mail account and no sub-account stored costs neither the store nor the server. */
    @Test fun `a single account with nothing linked asks neither the store nor the server`() {
        store.linked = emptyList()

        reconcile(session("acc1" to "Alex"))

        assertEquals(emptyList<Map<String, Any?>>(), storeReconciles())
        assertEquals(emptyList<Seen>(), seen)
    }

    /**
     * One mail account in the session but a sub-account still stored: the store IS consulted, with
     * the one-entry list and no probe. That is how the last sub-account of a login gets revoked.
     */
    @Test fun `a single account with a stored sub-account still consults the store`() {
        store.linked = listOf(StoredAccount(id = "account-2", server = credentials().server, username = "alex@example.test", loginId = ACCOUNT))

        reconcile(session("acc1" to "Alex"))

        val args = store.args("reconcileLinkedAccounts")
        assertEquals(ACCOUNT, args["loginId"])
        assertEquals(listOf(DiscoveredMailAccount("acc1", "Alex")), args["discovered"])
        assertEquals(emptyMap<String, Result<*>>(), args["probes"])
        assertEquals(emptyList<Seen>(), seen)
    }

    /** A warm session for [credentials], as the repository would have cached it after a sync. */
    private fun cachedFor(credentials: AccountCredentials) = MailRepository.JmapAccountSession(
        credentials = credentials,
        session = twoAccounts,
        accountId = "acc1",
        auth = auth,
        rolesToMailboxId = emptyMap(),
    )

    /**
     * The after-add entry reuses the cached session and ITS auth: the probe goes out under the
     * credential the cache holds, and no session is fetched for it.
     */
    @Test fun `the after-add reconcile reuses the cached session and its own auth`() {
        store.known = credentials()

        runBlocking { accounts(RecordingHost(cachedFor(credentials()))).reconcileLinkedAccountsAfterAdd(ACCOUNT) }

        assertEquals(1, storeReconciles().size)
        assertEquals(listOf(Seen("Mailbox/get", "acc2", "Basic YWxleDpzZWNyZXQ=")), seen)
    }

    /** A cache warm for another user name is not this login's: nothing is asked of anyone. */
    @Test fun `the after-add reconcile ignores a cache warm for another user`() {
        store.known = credentials()

        runBlocking {
            accounts(RecordingHost(cachedFor(credentials().copy(username = "jordan@example.test"))))
                .reconcileLinkedAccountsAfterAdd(ACCOUNT)
        }

        assertEquals(emptyList<Map<String, Any?>>(), storeReconciles())
        assertEquals(emptyList<Seen>(), seen)
    }

    /** And the same user name on another server is another login too. */
    @Test fun `the after-add reconcile ignores a cache warm for another server`() {
        store.known = credentials()

        runBlocking {
            accounts(RecordingHost(cachedFor(credentials().copy(server = "https://elsewhere.example.test"))))
                .reconcileLinkedAccountsAfterAdd(ACCOUNT)
        }

        assertEquals(emptyList<Map<String, Any?>>(), storeReconciles())
        assertEquals(emptyList<Seen>(), seen)
    }

    private companion object {
        const val ACCOUNT = "account-1"
    }
}

package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
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
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.jmapDiscoveryClient
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * [MailAccounts.syncServerContacts] run against a JMAP server answering the `ContactCard` methods and a real
 * SQLite behind [SqlServerContactDao]: the rows land under the app's account id, the delta is asked
 * from the stored state, and an IMAP account asks nobody anything.
 */
class MailAccountsSyncsServerContactsTest {

    private lateinit var server: MockWebServer
    private val dao = SqlServerContactDao()

    @Before fun start() {
        server = MockWebServer()
        server.start()
    }

    @After fun stop() {
        server.shutdown()
        dao.db.close()
    }

    private val auth = BasicAuth("alex", "secret")

    private fun session() = JmapSession(
        capabilities = mapOf(
            Jmap.CORE_CAPABILITY to JsonObject(emptyMap()),
            Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()),
            Jmap.CONTACTS_CAPABILITY to JsonObject(emptyMap()),
        ),
        accounts = mapOf(
            "mail" to JmapAccount("me", mapOf(Jmap.MAIL_CAPABILITY to JsonObject(emptyMap()))),
            "book" to JmapAccount("me", mapOf(Jmap.CONTACTS_CAPABILITY to JsonObject(emptyMap()))),
        ),
        primaryAccounts = mapOf(Jmap.MAIL_CAPABILITY to "mail", Jmap.CONTACTS_CAPABILITY to "book"),
        username = "alex",
        apiUrl = server.url("/jmap/api").toString(),
    )

    private fun credentials(
        protocol: MailProtocol = MailProtocol.JMAP,
        id: String = ACCOUNT,
        jmapAccountId: String? = null,
    ) = AccountCredentials(
        server = server.url("/").toString().removeSuffix("/"),
        username = "alex@example.test",
        password = "secret",
        id = id,
        protocol = protocol,
        jmapAccountId = jmapAccountId,
        authType = AuthType.BASIC,
    )

    /** Who asked for the session that reconciles linked accounts and rereads folders and identities. */
    private val reconcilingSessionsAsked = mutableListOf<String>()
    private val authsAsked = mutableListOf<String>()

    private inner class Host : MailAccountHost {
        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession {
            reconcilingSessionsAsked += credentials.id
            return MailRepository.JmapAccountSession(credentials, session(), "mail", auth, emptyMap())
        }

        override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth {
            authsAsked += credentials.id
            return auth
        }

        override suspend fun firstSync(credentials: AccountCredentials): MailboxMeta = error("not on this path")
        override fun cachedJmapSession(): MailRepository.JmapAccountSession? = null
    }

    /** The account ids the store lists; [onPost] runs as each POST reaches the server. */
    private val configured = mutableListOf(ACCOUNT)
    private var onPost: () -> Unit = {}

    private val linked = mutableSetOf<String>()

    private inner class Store : RecordingAccountStorage() {
        override fun accounts(): List<StoredAccount> = configured.map {
            StoredAccount(id = it, server = "unused", username = "alex", loginId = if (it in linked) ACCOUNT else null)
        }

        override fun account(id: String): StoredAccount? = accounts().firstOrNull { it.id == id }
    }

    private inline fun <reified T> untouched(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, called, _ ->
            error("${T::class.simpleName}.${called.name} was not expected here.")
        } as T

    private fun accounts(): MailAccounts {
        val store = Store()
        return MailAccounts(
            client = JmapClient(),
            discoveryClient = jmapDiscoveryClient(),
            imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
            emailDao = untouched<EmailDao>(),
            emailFtsDao = untouched<EmailFtsDao>(),
            emailBodyDao = untouched<EmailBodyDao>(),
            mailboxDao = untouched<MailboxDao>(),
            snoozedDao = untouched<SnoozedDao>(),
            serverContactDao = dao,
            accountStore = store,
            oauthClient = OAuthClient(),
            bgScope = CoroutineScope(Dispatchers.Unconfined),
            host = Host(),
        )
    }

    private val answers = ArrayDeque<String>()

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    /** Served on a GET in place of [session] when set. */
    private var sessionBody: String? = null

    /** The session on a GET, the queued answers in order on a POST. */
    private fun serve() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.method == "GET") {
                    json(sessionBody ?: Json.encodeToString(JmapSession.serializer(), session()))
                } else {
                    onPost()
                    json(answers.removeFirst())
                }
        }
    }

    private fun respond(json: String) {
        answers.addLast(json)
        serve()
    }

    /** Method name and arguments of every call the server received, in order. */
    private fun calls(): List<Pair<String, JsonObject>> = List(server.requestCount) { server.takeRequest() }
        .filter { it.method == "POST" }
        .map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject["methodCalls"]!!.jsonArray }
        .flatten().map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonObject }

    @Test fun `a JMAP account's first pass stores the whole book under the app's account id`() {
        respond(
            """{"methodResponses":[
              ["ContactCard/query",{"accountId":"book","queryState":"q","ids":["c1"],"position":0,"total":1},"q0"],
              ["ContactCard/get",{"accountId":"book","state":"s1","list":[
                {"id":"c1","name":{"full":"Jordan"},"emails":{"e":{"address":"jordan@example.org"}}}],"notFound":[]},"g0"]
            ]}""",
        )

        val outcome = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.Refreshed(whole = true, cards = 1, destroyed = 0, addresses = 1, stateKept = true), outcome)
        assertEquals(
            "the address book must not take the session that reconciles linked accounts, rereads " +
                "folders and identities and replaces the cached session",
            emptyList<String>(), reconcilingSessionsAsked,
        )
        assertEquals(listOf(ACCOUNT), authsAsked)
        assertEquals(listOf("ContactCard/query", "ContactCard/get"), calls().map { it.first })
        assertEquals(listOf("$ACCOUNT|c1|Jordan|jordan@example.org"), dao.rows())
        assertEquals(listOf("$ACCOUNT=s1"), dao.states())
    }

    @Test fun `a JMAP account with a stored state asks the changes since it`() {
        dao.db.createStatement().use {
            it.executeUpdate("INSERT INTO server_contacts VALUES ('$ACCOUNT', 'c1', 'Jordan', 'jordan@example.org')")
            it.executeUpdate("INSERT INTO server_contacts_state VALUES ('$ACCOUNT', 's1')")
        }
        respond(
            """{"methodResponses":[["ContactCard/changes",{"accountId":"book","oldState":"s1","newState":"s2",
               "hasMoreChanges":false,"created":[],"updated":[],"destroyed":["c1"]},"c0"]]}""",
        )

        val outcome = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.Refreshed(whole = false, cards = 0, destroyed = 1, addresses = 0, stateKept = true), outcome)
        val sent = calls()
        assertEquals(listOf("ContactCard/changes"), sent.map { it.first })
        assertEquals("s1", sent.single().second["sinceState"]!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), dao.rows())
        assertEquals(listOf("$ACCOUNT=s2"), dao.states())
    }

    @Test fun `an IMAP account asks neither a session nor the server, and writes nothing`() {
        assertEquals(ServerContactsSync.Imap, runBlocking { accounts().syncServerContacts(credentials(MailProtocol.IMAP)) })

        assertEquals(emptyList<String>(), reconcilingSessionsAsked + authsAsked)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<String>(), dao.writes)
    }

    @Test fun `an account removed while its book is read gets nothing written`() {
        respond(
            """{"methodResponses":[
              ["ContactCard/query",{"accountId":"book","queryState":"q","ids":["c1"],"position":0,"total":1},"q0"],
              ["ContactCard/get",{"accountId":"book","state":"s1","list":[
                {"id":"c1","name":{"full":"Jordan"},"emails":{"e":{"address":"jordan@example.org"}}}],"notFound":[]},"g0"]
            ]}""",
        )
        onPost = { configured.clear() }

        val outcome = runBlocking { runCatching { accounts().syncServerContacts(credentials()) } }

        assertEquals(
            "the account was signed out while the server answered: its copy must not be written",
            emptyList<String>(), dao.writes,
        )
        assertEquals(emptyList<String>(), dao.rows() + dao.states())
        assertTrue("the pass reports the account gone, got $outcome", outcome.exceptionOrNull() is AccountGoneException)
    }

    @Test fun `a linked sub-account asks nothing and gets no copy of its login's book`() {
        configured += LINKED
        linked += LINKED
        respond(
            """{"methodResponses":[
              ["ContactCard/query",{"accountId":"book","queryState":"q","ids":["c1"],"position":0,"total":1},"q0"],
              ["ContactCard/get",{"accountId":"book","state":"s1","list":[
                {"id":"c1","name":{"full":"Jordan"},"emails":{"e":{"address":"jordan@example.org"}}}],"notFound":[]},"g0"]
            ]}""",
        )

        val outcome = runBlocking { accounts().syncServerContacts(credentials(id = LINKED, jmapAccountId = "shared")) }

        assertEquals(ServerContactsSync.Linked, outcome)

        assertEquals(
            "the session's book is the login's personal one: a shared or delegated mailbox must not " +
                "be handed a copy of it, nor ask the server anything",
            0, server.requestCount,
        )
        assertEquals(emptyList<String>(), reconcilingSessionsAsked + authsAsked)
        assertEquals(emptyList<String>(), dao.writes)
    }

    @Test fun `the composer's search reads the given account's copy, for the query, up to the limit`() {
        dao.db.createStatement().use {
            it.executeUpdate("INSERT INTO server_contacts VALUES ('$ACCOUNT', 'c1', 'Ann', 'ann@x.org')")
            it.executeUpdate("INSERT INTO server_contacts VALUES ('$ACCOUNT', 'c2', 'Anna', 'anna@x.org')")
            it.executeUpdate("INSERT INTO server_contacts VALUES ('$ACCOUNT', 'c3', 'Annie', 'annie@x.org')")
            it.executeUpdate("INSERT INTO server_contacts VALUES ('$ACCOUNT', 'c4', 'Bob', 'bob@x.org')")
            it.executeUpdate("INSERT INTO server_contacts VALUES ('other', 'c5', 'Anne', 'anne@x.org')")
        }

        val found = runBlocking { accounts().serverContacts(ACCOUNT, "ann", 2) }

        assertEquals(listOf("ann@x.org", "anna@x.org"), found.map { it.email })
        assertEquals(
            listOf("bob@x.org"),
            runBlocking { accounts().serverContacts(ACCOUNT, "bob", 6) }.map { it.email },
        )
        assertEquals(
            listOf("anne@x.org"),
            runBlocking { accounts().serverContacts("other", "ann", 6) }.map { it.email },
        )
    }

    @Test fun `a session without a contacts account is said, and asks nothing`() {
        sessionBody = stalwartSession(withContacts = false)
        serve()

        val outcome = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.NoAddressBook(capabilityListed = false), outcome)
        assertEquals("only the session was fetched", 1, server.requestCount)
        assertEquals(emptyList<String>(), dao.writes)
    }

    @Test fun `a session listing the capability but no account for it is said apart`() {
        sessionBody = stalwartSession(withContacts = true, accountHasContacts = false)
        serve()

        val outcome = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.NoAddressBook(capabilityListed = true), outcome)
        assertEquals(emptyList<String>(), dao.writes)
    }

    @Test fun `a book read without a state keeps its cards and says the state was not kept`() {
        sessionBody = stalwartSession(withContacts = true)
        respond(
            """{"methodResponses":[
              ["ContactCard/query",{"accountId":"b","queryState":"q1","ids":["a"],"position":0,"total":1},"q0"],
              ["ContactCard/get",{"accountId":"b","list":[
                {"@type":"Card","id":"a","name":{"full":"Ada"},"emails":{"0":{"address":"ada@example.org"}}}
              ],"notFound":[]},"g0"]
            ]}""",
        )

        val outcome = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.Refreshed(whole = true, cards = 1, destroyed = 0, addresses = 1, stateKept = false), outcome)
        assertEquals(listOf("$ACCOUNT|a|Ada|ada@example.org"), dao.rows())
        assertEquals(emptyList<String>(), dao.states())
    }

    /**
     * The shapes Stalwart sends: a session whose contacts account is also the mail account, JSContact
     * cards with `@type`, `name.components` and `emails` as a map of objects, a card without address,
     * then a pass with nothing changed. Both passes must leave the book and its state in place.
     */
    @Test fun `two passes against Stalwart's shapes keep the book and its state`() {
        sessionBody = stalwartSession(withContacts = true)
        respond(
            """{"methodResponses":[
              ["ContactCard/query",{"accountId":"b","queryState":"q1","canCalculateChanges":true,"position":0,
                "ids":["a","b","c","d"],"total":4,"limit":500},"q0"],
              ["ContactCard/get",{"accountId":"b","state":"sAQ","list":[
                {"@type":"Card","id":"a","name":{"@type":"Name","components":[
                  {"@type":"NameComponent","kind":"given","value":"Ada"},
                  {"@type":"NameComponent","kind":"surname","value":"Lovelace"}],"isOrdered":true},
                 "emails":{"0":{"@type":"EmailAddress","address":"ada@example.org","contexts":{"work":true},"pref":1}}},
                {"@type":"Card","id":"b","name":{"@type":"Name","full":"Jordan Lee"},
                 "emails":{"k1":{"@type":"EmailAddress","address":"jordan@example.org"},
                           "k2":{"@type":"EmailAddress","address":"jl@work.example"}}},
                {"@type":"Card","id":"c","emails":{"0":{"@type":"EmailAddress","address":"noname@example.org"}}},
                {"@type":"Card","id":"d","name":{"@type":"Name","full":"Own card"}}
              ],"notFound":[]},"g0"]
            ]}""",
        )
        respond(
            """{"methodResponses":[["ContactCard/changes",{"accountId":"b","oldState":"sAQ","newState":"sAQ",
               "hasMoreChanges":false,"created":[],"updated":[],"destroyed":[]},"c0"]]}""",
        )
        val book = listOf(
            "$ACCOUNT|a|Ada Lovelace|ada@example.org",
            "$ACCOUNT|b|Jordan Lee|jl@work.example",
            "$ACCOUNT|b|Jordan Lee|jordan@example.org",
            "$ACCOUNT|c||noname@example.org",
        )

        val first = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.Refreshed(whole = true, cards = 4, destroyed = 0, addresses = 4, stateKept = true), first)
        assertEquals(book, dao.rows().sorted())
        assertEquals(listOf("$ACCOUNT=sAQ"), dao.states())

        val second = runBlocking { accounts().syncServerContacts(credentials()) }

        assertEquals(ServerContactsSync.Refreshed(whole = false, cards = 0, destroyed = 0, addresses = 0, stateKept = true), second)
        assertEquals("a pass that saw nothing change must not empty the copy", book, dao.rows().sorted())
        assertEquals(listOf("$ACCOUNT=sAQ"), dao.states())
        assertEquals(
            listOf("ContactCard/query", "ContactCard/get", "ContactCard/changes"),
            calls().map { it.first },
        )
    }

    /** Stalwart's session, one account "b" holding mail and, when [withContacts], the address book;
     *  [accountHasContacts] false lists the capability with no account for it. */
    private fun stalwartSession(withContacts: Boolean, accountHasContacts: Boolean = withContacts): String {
        val contacts = if (withContacts) "\"${Jmap.CONTACTS_CAPABILITY}\":{}," else ""
        val accountContacts =
            if (accountHasContacts) "\"${Jmap.CONTACTS_CAPABILITY}\":{\"maxAddressBooksPerCard\":null,\"mayCreateAddressBook\":true}," else ""
        val primaryContacts = if (accountHasContacts) "\"${Jmap.CONTACTS_CAPABILITY}\":\"b\"," else ""
        return """{
          "capabilities":{"${Jmap.CORE_CAPABILITY}":{"maxSizeUpload":50000000,"maxConcurrentUpload":4,
            "maxSizeRequest":10000000,"maxConcurrentRequests":4,"maxCallsInRequest":16,"maxObjectsInGet":500,
            "maxObjectsInSet":500,"collationAlgorithms":["i;ascii-numeric","i;ascii-casemap","i;unicode-casemap"]},
            $contacts"${Jmap.MAIL_CAPABILITY}":{}},
          "accounts":{"b":{"name":"alex@example.test","isPersonal":true,"isReadOnly":false,
            "accountCapabilities":{$accountContacts"${Jmap.MAIL_CAPABILITY}":{"maxMailboxesPerEmail":null,
              "maxMailboxDepth":10,"emailQuerySortOptions":["receivedAt"],"mayCreateTopLevelMailbox":true}}}},
          "primaryAccounts":{$primaryContacts"${Jmap.MAIL_CAPABILITY}":"b"},
          "username":"alex@example.test",
          "apiUrl":"${server.url("/jmap/")}",
          "downloadUrl":"${server.url("/jmap/download/{accountId}/{blobId}/{name}?accept={type}")}",
          "uploadUrl":"${server.url("/jmap/upload/{accountId}/")}",
          "eventSourceUrl":"${server.url("/jmap/eventsource/?types={types}&closeafter={closeafter}&ping={ping}")}",
          "state":"session-1"
        }"""
    }

    private companion object {
        const val ACCOUNT = "account-1"
        const val LINKED = "account-2"
    }
}

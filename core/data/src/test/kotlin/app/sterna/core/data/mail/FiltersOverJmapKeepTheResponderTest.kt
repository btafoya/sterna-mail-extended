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
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.FilterScriptWrite
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.filter.SieveVacation
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.formRulesWrite
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
 * - A download of the opened script that fails: the read throws, nothing here runs that path.
 * - The server-side effect of `onSuccessActivateScript`: only whether it is asked is pinned.
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
                when {
                    updated != null -> """["SieveScript/set",{"accountId":"acc1","updated":$updated},"s0"]"""
                    "create" in args -> """["SieveScript/set",{"accountId":"acc1","created":{"new":{"id":"s-new"}}},"s0"]"""
                    else -> """["SieveScript/set",{"accountId":"acc1"},"s0"]"""
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
            serverContactDao = untouched<app.sterna.core.data.db.ServerContactDao>(),
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

    /** What each `SieveScript/set` asked to activate, null for nothing. */
    private fun activations(): List<String?> = methods.filter { it.first == "SieveScript/set" }
        .map { it.second["onSuccessActivateScript"]?.jsonPrimitive?.content }

    /** The script as the filters screen reads it when it opens. */
    private fun MailAccounts.opening() = runBlocking { loadFilterRules(credentials) } as FilterRulesState.Loaded

    /** What the filters screen's Save writes over [opened], its read at opening: [rules] paired with the form rules by position. */
    private fun MailAccounts.saveRules(opened: FilterRulesState.Loaded, rules: List<FilterRule>) = runBlocking {
        val forms = opened.scriptRules.filterIsInstance<ScriptRule.Form>()
        saveFilterScript(credentials, formRulesWrite(opened, rules.indices.map { forms.getOrNull(it)?.id }, rules))
    }

    @Test fun `a JMAP filters save keeps the responder an IMAP account wrote into the sterna script`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before, away), active = true))
        val accounts = accounts()
        val loaded = accounts.opening()

        accounts.saveRules(loaded, after)

        assertEquals("sterna", loaded.scriptName)
        assertEquals(before, loaded.rules)
        val uploaded = uploads.single()
        assertTrue(uploaded, uploaded.lines().any { it.startsWith("# STERNA-VACATION-V1: ") })
        assertTrue(uploaded, "vacation :subject \"Away\" :addresses [\"alex@example.test\"] \"Back on the 28th.\";" in uploaded)
        assertTrue(uploaded, "header :contains \"subject\" \"newsletter\"" in uploaded)
        assertEquals(after, loadedFilterRules("sterna", uploaded).rules)
        assertEquals(listOf("s-sterna"), updatedIds())
        assertEquals("the active script is written in place, never activated again", listOf<String?>(null), activations())
    }

    @Test fun `a sender blocked on JMAP after an IMAP account turned the responder on gets no reply`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before, away.copy(trashFolder = "Trash")), active = true))
        val accounts = accounts()

        val outcome = runBlocking {
            addBlockRule(
                address = "spam@example.test",
                trashFolder = "Trash",
                load = { accounts.loadFilterRules(credentials) },
                save = { accounts.saveFilterScript(credentials, it) },
            )
        }

        assertEquals(BlockOutcome.ADDED, outcome)
        val uploaded = uploads.single()
        assertTrue(
            uploaded,
            "if not anyof (address :is \"from\" \"spam@example.test\") {\n" +
                "    vacation :subject \"Away\" :addresses [\"alex@example.test\"] \"Back on the 28th.\";\n}\n" in uploaded,
        )
    }

    @Test fun `the active script is opened on JMAP, not an inactive sterna, and written under its own name`() {
        serveJmap()
        val roundcube = "require [\"fileinto\"];\n\n# rule:[x]\nif header :contains \"subject\" \"x\" {\n    fileinto \"X\";\n}\n"
        scripts = listOf(
            sterna(SieveCodec.generate(before), active = false),
            Script("s-roundcube", "roundcube", "blob-roundcube", active = true, body = roundcube),
        )
        val accounts = accounts()
        val loaded = accounts.opening()

        accounts.saveRules(loaded, listOf(FilterRule(name = "x", field = RuleField.SUBJECT, value = "y", moveTo = "X")))

        assertEquals("roundcube", loaded.scriptName)
        assertEquals("read at opening, then again before writing", listOf("blob-roundcube", "blob-roundcube"), downloads)
        assertEquals(roundcube.replace("\"subject\" \"x\"", "\"subject\" \"y\""), uploads.single())
        assertEquals(listOf("s-roundcube"), updatedIds())
        assertEquals(listOf<String?>(null), activations())
    }

    @Test fun `an active vacation script is the server's responder, so sterna is opened and a save activates it`() {
        serveJmap()
        scripts = listOf(
            sterna(SieveCodec.generate(before, away), active = false),
            Script("s-vacation", "vacation", "blob-vacation", active = true, body = "require [\"vacation\"];\nvacation \"Gone.\";\n"),
        )

        val accounts = accounts()
        val loaded = accounts.opening()
        accounts.saveRules(loaded, after)

        assertEquals("sterna", loaded.scriptName)
        assertEquals(listOf("blob-sterna", "blob-sterna"), downloads)
        assertEquals(after, loadedFilterRules("sterna", uploads.single()).rules)
        assertEquals(listOf("s-sterna"), updatedIds())
        assertEquals(listOf<String?>("s-sterna"), activations())
    }

    @Test fun `without a script to open nothing is downloaded and the save creates sterna and activates it`() {
        serveJmap()
        scripts = listOf(Script("s-vacation", "vacation", "blob-vacation", active = true, body = "vacation \"Gone.\";\n"))

        val accounts = accounts()
        val loaded = accounts.opening()
        accounts.saveRules(loaded, after)

        assertEquals(
            "the auto-reply running, a sender block is refused; the filters screen keeps its warning",
            FilterRulesState.Loaded(rules = emptyList(), foreignActiveScript = true, scripts = listOf(ListedScript("vacation", true))),
            loaded,
        )
        assertEquals(emptyList<String>(), downloads)
        assertEquals(after, loadedFilterRules("sterna", uploads.single()).rules)
        assertEquals(listOf("<create>"), updatedIds())
        assertEquals(listOf<String?>("#new"), activations())
    }

    @Test fun `with the server's auto-reply running, a sender block writes nothing`() {
        serveJmap()
        scripts = listOf(
            sterna(SieveCodec.generate(before), active = false),
            Script("s-vacation", "vacation", "blob-vacation", active = true, body = "require [\"vacation\"];\nvacation \"Gone.\";\n"),
        )
        val accounts = accounts()

        val outcome = runBlocking {
            addBlockRule(
                address = "spam@example.test",
                trashFolder = "Trash",
                load = { accounts.loadFilterRules(credentials) },
                save = { accounts.saveFilterScript(credentials, it) },
            )
        }

        assertEquals("activating sterna would stop the auto-reply without a word", BlockOutcome.FAILED, outcome)
        assertEquals(emptyList<String>(), uploads)
        assertEquals(emptyList<String>(), updatedIds())
    }

    @Test fun `a rule changed elsewhere since the screen opened refuses the save, and nothing is uploaded`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before), active = true))
        val accounts = accounts()
        val opened = accounts.opening()
        scripts = listOf(sterna(SieveCodec.generate(after), active = true))

        val thrown = runCatching { accounts.saveRules(opened, after.take(1)) }.exceptionOrNull()

        assertTrue("$thrown", thrown is FilterScriptChangedException)
        assertEquals(emptyList<String>(), uploads)
        assertEquals(emptyList<String>(), updatedIds())
    }

    @Test fun `sterna created elsewhere after an account with no script was opened refuses the save`() {
        serveJmap()
        val accounts = accounts()
        val opened = accounts.opening()
        scripts = listOf(sterna(SieveCodec.generate(before), active = false))

        val thrown = runCatching { accounts.saveRules(opened, after) }.exceptionOrNull()

        assertTrue("$thrown", thrown is FilterScriptChangedException)
        assertEquals(emptyList<String>(), uploads)
        assertEquals(emptyList<String>(), updatedIds())
    }

    private val roundcubeText = "require [\"fileinto\"];\n\n# rule:[x]\nif header :contains \"subject\" \"x\" {\n    fileinto \"X\";\n}\n"

    private fun roundcube(active: Boolean) = Script("s-roundcube", "roundcube", "blob-roundcube", active, roundcubeText)

    @Test fun `making a script active on JMAP is a SieveScript set with the activation alone, and no blob moves`() {
        serveJmap()
        scripts = listOf(
            sterna(SieveCodec.generate(before), active = true),
            Script("s-other", "other", "blob-other", active = false, body = "keep;\n"),
            roundcube(active = false),
        )

        runBlocking { accounts().activateScript(credentials, "roundcube") }

        val sets = methods.filter { it.first == "SieveScript/set" }.map { it.second }
        assertEquals("one SieveScript/set", 1, sets.size)
        assertEquals(
            "no create, no update: the script's text is not written",
            setOf("accountId", "onSuccessActivateScript"),
            sets.single().keys,
        )
        assertEquals("s-roundcube", sets.single()["onSuccessActivateScript"]?.jsonPrimitive?.content)
        assertEquals(emptyList<String>(), uploads)
        assertEquals(emptyList<String>(), downloads)
    }

    @Test fun `a script asked by name on JMAP is the one downloaded, not the active one`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before), active = true), roundcube(active = false))

        val state = runBlocking { accounts().loadFilterRules(credentials, "roundcube") } as FilterRulesState.Loaded

        assertEquals("roundcube", state.scriptName)
        assertEquals(roundcubeText, state.scriptText)
        assertEquals(listOf("blob-roundcube"), downloads)
        assertEquals(listOf(ListedScript("sterna", true), ListedScript("roundcube", false)), state.scripts)
        assertEquals(true, state.chosen)
        assertEquals(true, state.inactiveBesideActive)
    }

    @Test fun `a save over a chosen inactive script on JMAP updates it and activates nothing`() {
        serveJmap()
        scripts = listOf(sterna(SieveCodec.generate(before), active = true), roundcube(active = false))
        val accounts = accounts()
        val opened = runBlocking { accounts.loadFilterRules(credentials, "roundcube") } as FilterRulesState.Loaded

        val saved = runBlocking { accounts.saveFilterScript(credentials, FilterScriptWrite(opened.snapshot, opened.scriptToEdit.add(after[1]))) }

        assertEquals(listOf("s-roundcube"), updatedIds())
        assertEquals("sterna stays the active script", listOf<String?>(null), activations())
        assertEquals(listOf("blob-roundcube", "blob-roundcube"), downloads)
        assertEquals(true, saved.inactiveBesideActive)
    }
}

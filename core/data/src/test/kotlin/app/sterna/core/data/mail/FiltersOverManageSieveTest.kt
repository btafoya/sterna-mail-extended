package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.filter.BlockOutcome
import app.sterna.core.data.filter.BulwarkMetadata
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.copy
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.FilterScriptWrite
import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.FilterScriptWarning
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.readable
import app.sterna.core.data.filter.SieveVacation
import app.sterna.core.data.filter.VacationMarker
import app.sterna.core.data.filter.addBlockRule
import app.sterna.core.data.filter.blockRule
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.data.filter.formRulesWrite
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapFolder
import app.sterna.core.imap.ManageSieveException
import app.sterna.core.imap.SieveScriptInfo
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.jmapDiscoveryClient
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The filters and the auto-reply of an IMAP account, executed through `MailAccounts` against an in-memory ManageSieve
 * server behind the [SieveConnector] seam.
 *
 * What this does NOT cover:
 * - The wire. `ManageSieveClientTest` holds the dialogue, the literal octet count of a non-ASCII
 *   script, STARTTLS and the `CHECKSCRIPT`-without-`VERSION` decision this fake only mirrors.
 * - `ManageSieveConnector`, the delegations from the seam to `ManageSieveSession`: a swapped one
 *   passes here, and only the source lint `ManageSieveConnectorWiringTest` holds them.
 * - The JMAP bodies of the three functions; only their dispatch is run below. The JMAP filters
 *   save is run in `FiltersOverJmapKeepTheResponderTest`.
 */
class FiltersOverManageSieveTest {

    private class FakeSieve(
        scripts: Map<String, String> = emptyMap(),
        var active: String? = null,
        private val offersCheckScript: Boolean = true,
        private val refuse: (verb: String) -> Exception? = { null },
        /** The command at which the connection is lost, before the server receives it. */
        private val cutAt: String? = null,
        private val extensions: List<String> = ANNOUNCED,
        /** Thrown by the connection itself, before any command: offline, refused, timed out. */
        private val unreachable: Exception? = null,
    ) : SieveConnector {
        val scripts = scripts.toMutableMap()
        val logins = mutableListOf<List<Any>>()
        val commands = mutableListOf<String>()
        val checked = mutableListOf<String>()

        override suspend fun <T> session(
            host: String,
            port: Int,
            username: String,
            password: String,
            block: SieveScripts.() -> T,
        ): T {
            logins += listOf(host, port, username, password)
            unreachable?.let { throw it }
            return Session().block()
        }

        private inner class Session : SieveScripts {
            override val sieveExtensions: List<String> get() = extensions

            private fun receive(command: String) {
                val verb = command.substringBefore(' ')
                if (verb == cutAt) {
                    throw ManageSieveException("ManageSieve connection failed: cut", ManageSieveException.Kind.UNREACHABLE)
                }
                commands += command
                refuse(verb)?.let { throw it }
            }

            override fun listScripts(): List<SieveScriptInfo> {
                receive("LISTSCRIPTS")
                return scripts.keys.sorted().map { SieveScriptInfo(it, it == active) }
            }

            override fun getScript(name: String): String {
                receive("GETSCRIPT $name")
                return scripts[name] ?: throw ManageSieveException(
                    "GETSCRIPT: NO (NONEXISTENT) no such script",
                    ManageSieveException.Kind.REFUSED,
                    serverText = "no such script",
                    responseCode = "NONEXISTENT",
                )
            }

            override fun checkScript(script: String): Boolean {
                if (!offersCheckScript) return false
                receive("CHECKSCRIPT")
                checked += script
                return true
            }

            override fun putScript(name: String, script: String) {
                receive("PUTSCRIPT $name")
                scripts[name] = script
            }

            override fun setActive(name: String) {
                receive("SETACTIVE $name")
                active = name.ifEmpty { null }
            }
        }
    }

    private class Host : MailAccountHost {
        var jmapSessions = 0

        override suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession {
            jmapSessions++
            throw JmapReached()
        }

        override suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth = error("not on this path")
        override suspend fun firstSync(credentials: AccountCredentials): MailboxMeta = error("not on this path")
        override fun cachedJmapSession(): MailRepository.JmapAccountSession? = null
    }

    private class JmapReached : RuntimeException("the JMAP session was asked for")

    private inline fun <reified T> untouched(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, called, _ ->
            error("${T::class.simpleName}.${called.name} was not expected here.")
        } as T

    private val host = Host()

    private fun accounts(sieve: FakeSieve, store: RecordingAccountStorage = RecordingAccountStorage()): MailAccounts {
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
            host = host,
            sieve = sieve,
        )
    }

    private val imapAccount = AccountCredentials(
        server = "",
        username = "alex@example.test",
        password = "imap-secret",
        id = "account-1",
        protocol = MailProtocol.IMAP,
        imap = MailEndpoint("imap.example.test", 993, ConnectionSecurity.TLS),
        smtp = MailEndpoint("smtp.example.test", 465, ConnectionSecurity.TLS),
        authType = AuthType.BASIC,
    )

    private val oauth = OAuthCredentials(
        accessToken = "access",
        refreshToken = "refresh",
        accessExpiresAtMillis = Long.MAX_VALUE,
        tokenEndpoint = "https://login.example.test/token",
        clientId = "client",
    )

    private val rules = listOf(
        FilterRule(name = "Boss", field = RuleField.FROM, value = "boss@example.test", moveTo = "Work", flag = true),
        FilterRule(name = "News", field = RuleField.SUBJECT, value = "newsletter", markRead = true),
        FilterRule(name = "Off", enabled = false, value = "ignored"),
    )

    private val login = listOf<Any>("imap.example.test", 4190, "alex@example.test", "imap-secret")

    private fun refused(verb: String, text: String) =
        ManageSieveException("$verb: NO $text", ManageSieveException.Kind.REFUSED, serverText = text)

    private inline fun <reified T : Throwable> thrownBy(block: () -> Unit): T {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return t
            throw AssertionError("expected ${T::class.simpleName}, got $t", t)
        }
        fail("expected ${T::class.simpleName}, nothing was thrown")
        throw AssertionError()
    }

    // ---- which transport -----------------------------------------------------------------------

    @Test fun `only a password IMAP account has a ManageSieve host, and it is the IMAP host`() {
        assertEquals("imap.example.test", manageSieveHost(imapAccount))
        assertNull("a JMAP account, even one carrying an IMAP endpoint", manageSieveHost(imapAccount.copy(protocol = MailProtocol.JMAP)))
        assertNull("OAuth tokens", manageSieveHost(imapAccount.copy(oauth = oauth, authType = AuthType.OAUTH)))
        assertNull("an imported OAuth account not signed in yet", manageSieveHost(imapAccount.copy(authType = AuthType.OAUTH)))
        assertNull("tokens under a BASIC flag", manageSieveHost(imapAccount.copy(oauth = oauth)))
        assertNull("no IMAP endpoint", manageSieveHost(imapAccount.copy(imap = null)))
    }

    /** The script as the filters screen reads it when it opens. */
    private fun MailAccounts.opening(account: AccountCredentials = imapAccount) =
        runBlocking { loadFilterRules(account) } as FilterRulesState.Loaded

    /** What the filters screen's Save writes over [opened], its read at opening: [rules] paired with the form rules by position. */
    private fun MailAccounts.saveRules(opened: FilterRulesState.Loaded, rules: List<FilterRule>) = runBlocking {
        val forms = opened.scriptRules.filterIsInstance<ScriptRule.Form>()
        saveFilterScript(imapAccount, formRulesWrite(opened, rules.indices.map { forms.getOrNull(it)?.id }, rules))
    }

    /** The script the responder screen read when it opened. */
    private fun MailAccounts.openingVacation() = (runBlocking { loadVacation(imapAccount) } as VacationState.Loaded).script

    private val nothingRead = ScriptSnapshot(null, null)

    /** A responder read with the script it came from set aside, where only the responder is asserted. */
    private fun VacationState.withoutScript() = (this as? VacationState.Loaded)?.copy(script = null) ?: this

    /** The form rules of the script [name] as the fake server now holds it. */
    private fun FakeSieve.rulesOf(name: String) = loadedFilterRules(name, scripts.getValue(name)).rules

    private val handWritten = "require [\"fileinto\"];\n\n# rule:[x]\nif header :contains \"subject\" \"x\" {\n    fileinto \"X\";\n}\n"

    private val handRule = FilterRule(name = "x", field = RuleField.SUBJECT, value = "x", moveTo = "X")

    // ---- read ----------------------------------------------------------------------------------

    @Test fun `what the server announces reaches the form, and a server that lists nothing offers it everything`() {
        val listed = FakeSieve(scripts = mapOf("sterna" to handWritten), active = "sterna")
        val accounts = accounts(listed)
        val opened = accounts.opening()
        assertEquals(ANNOUNCED, opened.sieveExtensions)
        assertEquals("the state a save answers carries it too", ANNOUNCED, accounts.saveRules(opened, listOf(handRule.copy(value = "y"))).sieveExtensions)
        val silent = FakeSieve(scripts = mapOf("sterna" to handWritten), active = "sterna", extensions = emptyList())
        assertNull(accounts(silent).opening().sieveExtensions)
    }


    @Test fun `the active script is read on the IMAP host with the IMAP login`() {
        val text = SieveCodec.generate(rules)
        val sieve = FakeSieve(scripts = mapOf("sterna" to text), active = "sterna")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(
            FilterRulesState.Loaded(
                rules = rules, scriptName = "sterna", scriptText = text, scripts = listOf(ListedScript("sterna", true)),
                sieveExtensions = ANNOUNCED,
            ),
            state,
        )
        assertEquals(listOf(login), sieve.logins)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
        assertEquals(0, host.jmapSessions)
    }

    @Test fun `the active script is opened, not an inactive sterna beside it`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = "roundcube")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(
            FilterRulesState.Loaded(
                rules = listOf(handRule),
                scriptName = "roundcube",
                scriptText = handWritten,
                scripts = listOf(ListedScript("roundcube", true), ListedScript("sterna", false)),
                sieveExtensions = ANNOUNCED,
            ),
            state,
        )
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube"), sieve.commands)
    }

    @Test fun `with no active script the inactive sterna is opened`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = null)

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) } as FilterRulesState.Loaded

        assertEquals("sterna", state.scriptName)
        assertEquals(rules, state.rules)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
    }

    @Test fun `a sterna script edited by hand is read, its hand-written block kept apart`() {
        val edited = SieveCodec.generate(rules) + "if header :contains \"subject\" \"x\" { discard; }\n"
        val sieve = FakeSieve(scripts = mapOf("sterna" to edited), active = "sterna")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) } as FilterRulesState.Loaded

        assertEquals(false, state.foreignActiveScript)
        assertEquals(false, state.scriptUnreadable)
        assertEquals(rules, state.rules)
        assertEquals(rules.size + 1, state.scriptRules.size)
    }

    @Test fun `a script that cannot be cut into blocks is unreadable`() {
        val broken = "keep;\n/* never closed\n"
        val sieve = FakeSieve(scripts = mapOf("roundcube" to broken), active = "roundcube")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(
            FilterRulesState.Loaded(
                rules = emptyList(),
                foreignActiveScript = true,
                scriptUnreadable = true,
                scriptName = "roundcube",
                scriptText = broken,
                scripts = listOf(ListedScript("roundcube", true)),
                sieveExtensions = ANNOUNCED,
            ),
            state,
        )
    }

    @Test fun `without a script to open nothing is fetched and the list is empty`() {
        val sieve = FakeSieve(scripts = mapOf("vacation" to "vacation \"away\";\n"), active = null)

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(
            FilterRulesState.Loaded(rules = emptyList(), scripts = listOf(ListedScript("vacation", false)), sieveExtensions = ANNOUNCED),
            state,
        )
        assertEquals(listOf("LISTSCRIPTS"), sieve.commands)
    }

    @Test fun `a NO from the server reaches the caller of the read, not Unsupported`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules)),
            active = "sterna",
            refuse = { if (it == "GETSCRIPT") refused(it, "permission denied") else null },
        )

        val thrown = thrownBy<ManageSieveException> { runBlocking { accounts(sieve).loadFilterRules(imapAccount) } }

        assertEquals(ManageSieveException.Kind.REFUSED, thrown.kind)
        assertEquals("permission denied", thrown.serverText)
    }

    // ---- a script chosen by name ------------------------------------------------------------------

    @Test fun `a script asked by name is the one read, not the active one, and it is marked chosen and inactive`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = "sterna")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount, "roundcube") } as FilterRulesState.Loaded

        assertEquals("roundcube", state.scriptName)
        assertEquals(handWritten, state.scriptText)
        assertEquals(listOf(handRule), state.rules)
        assertEquals(listOf(ListedScript("roundcube", false), ListedScript("sterna", true)), state.scripts)
        assertEquals(true, state.chosen)
        assertEquals("sterna stays active beside it, and a save leaves it so", true, state.inactiveBesideActive)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube"), sieve.commands)
    }

    @Test fun `a script asked by name that is gone opens the active one instead, not chosen`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount, "deleted") } as FilterRulesState.Loaded

        assertEquals("sterna", state.scriptName)
        assertEquals(false, state.chosen)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
    }

    @Test fun `making a script active sends SETACTIVE alone, and no script is read or written`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules), "other" to "keep;\n", "roundcube" to "keep;\n/* never closed\n"),
            active = "sterna",
        )
        val before = sieve.scripts.toMap()

        runBlocking { accounts(sieve).activateScript(imapAccount, "roundcube") }

        assertEquals(listOf("SETACTIVE roundcube"), sieve.commands)
        assertEquals("roundcube", sieve.active)
        assertEquals("an unreadable script is put in service as it stands", before, sieve.scripts)
        assertEquals(listOf(login), sieve.logins)
        assertEquals(0, host.jmapSessions)
    }

    @Test fun `a save over a chosen inactive script writes it and leaves the active one active`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = "sterna")
        val accounts = accounts(sieve)
        val opened = runBlocking { accounts.loadFilterRules(imapAccount, "roundcube") } as FilterRulesState.Loaded
        val added = FilterRule(name = "Boss", value = "boss@example.test", moveTo = "Work")
        sieve.commands.clear()

        val saved = runBlocking {
            accounts.saveFilterScript(imapAccount, FilterScriptWrite(opened.snapshot, opened.scriptToEdit.add(added)))
        }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube", "CHECKSCRIPT", "PUTSCRIPT roundcube"), sieve.commands)
        assertEquals("sterna", sieve.active)
        assertEquals(listOf(handRule, added), sieve.rulesOf("roundcube"))
        assertEquals("the active script is not touched", SieveCodec.generate(rules), sieve.scripts["sterna"])
        assertEquals("roundcube", saved.scriptName)
        assertEquals(listOf(ListedScript("roundcube", false), ListedScript("sterna", true)), saved.scripts)
        assertEquals(true, saved.inactiveBesideActive)
        assertEquals("the next save checks the same script", true, saved.snapshot.chosen)
    }

    @Test fun `a chosen script with nothing active is written and activated`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = null)
        val accounts = accounts(sieve)
        val opened = runBlocking { accounts.loadFilterRules(imapAccount, "roundcube") } as FilterRulesState.Loaded
        sieve.commands.clear()

        val saved = runBlocking { accounts.saveFilterScript(imapAccount, FilterScriptWrite(opened.snapshot, opened.scriptToEdit)) }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube", "CHECKSCRIPT", "PUTSCRIPT roundcube", "SETACTIVE roundcube"), sieve.commands)
        assertEquals("roundcube", sieve.active)
        assertEquals(listOf(ListedScript("roundcube", true), ListedScript("sterna", false)), saved.scripts)
        assertEquals(false, saved.inactiveBesideActive)
    }

    @Test fun `a chosen script changed elsewhere refuses the save, and nothing is written`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = "sterna")
        val accounts = accounts(sieve)
        val opened = runBlocking { accounts.loadFilterRules(imapAccount, "roundcube") } as FilterRulesState.Loaded
        sieve.scripts["roundcube"] = handWritten + "keep;\n"
        sieve.commands.clear()

        thrownBy<FilterScriptChangedException> {
            runBlocking { accounts.saveFilterScript(imapAccount, FilterScriptWrite(opened.snapshot, opened.scriptToEdit)) }
        }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube"), sieve.commands)
        assertEquals(handWritten + "keep;\n", sieve.scripts["roundcube"])
    }

    @Test fun `the status of a chosen script is read for that script`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = "sterna")

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount, "roundcube") }!!

        assertEquals(FilterScriptStatus(scriptExists = true, scriptActive = false, enabledRuleCount = 1, foreignActive = true), status)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube"), sieve.commands)
    }

    // ---- status --------------------------------------------------------------------------------

    @Test fun `over ManageSieve an active vacation script is the one opened, and its block is counted`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules), "vacation" to "vacation \"away\";\n"),
            active = "vacation",
        )

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }

        assertEquals(
            FilterScriptStatus(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 1,
                vacationScriptExists = true,
                vacationScriptActive = true,
                foreignActive = false,
            ),
            status,
        )
        assertEquals(listOf(login), sieve.logins)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT vacation"), sieve.commands)
    }

    @Test fun `a vacation script that exists but is not the active one`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules), "vacation" to "vacation \"away\";\n"),
            active = "sterna",
        )

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }

        assertEquals(
            FilterScriptStatus(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 2,
                vacationScriptExists = true,
                vacationScriptActive = false,
                foreignActive = false,
            ),
            status,
        )
    }

    @Test fun `an inactive sterna with nothing active is opened, and its rules are not running`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "other" to "keep;\n"), active = null)

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }!!

        assertEquals(FilterScriptStatus(scriptExists = true, scriptActive = false, enabledRuleCount = 2), status)
        assertEquals(
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
        )
    }

    @Test fun `without a script to open the status still names the other scripts`() {
        val sieve = FakeSieve(scripts = mapOf("vacation" to "vacation \"away\";\n", "other" to "keep;\n"), active = null)

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }

        assertEquals(FilterScriptStatus(vacationScriptExists = true), status)
        assertEquals(listOf("LISTSCRIPTS"), sieve.commands)
    }

    @Test fun `a cancelled IMAP read stays a cancellation, for the status and for the rules`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules)),
            active = "sterna",
            refuse = { if (it == "LISTSCRIPTS") CancellationException("left the screen") else null },
        )
        val accounts = accounts(sieve)

        val status = thrownBy<CancellationException> { runBlocking { accounts.loadFilterScriptStatus(imapAccount) } }
        assertEquals("left the screen", status.message)
        val read = thrownBy<CancellationException> { runBlocking { accounts.loadFilterRules(imapAccount) } }
        assertEquals("left the screen", read.message)
    }

    @Test fun `a failed status read is null, as on JMAP`() {
        val sieve = FakeSieve(refuse = { if (it == "LISTSCRIPTS") refused(it, "try later") else null })

        assertNull(runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) })
    }

    // ---- save ----------------------------------------------------------------------------------

    @Test fun `a save over the active script writes it under its name and activates nothing`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = "roundcube")
        val accounts = accounts(sieve)
        val loaded = runBlocking { accounts.loadFilterRules(imapAccount) } as FilterRulesState.Loaded
        val added = FilterRule(name = "Boss", value = "boss@example.test", moveTo = "Work")
        sieve.commands.clear()

        runBlocking { accounts.saveFilterScript(imapAccount, FilterScriptWrite(loaded.snapshot, loaded.scriptToEdit.add(added))) }

        assertEquals(
            "SETACTIVE on another script switches the running one off: none is sent over the active one",
            listOf("LISTSCRIPTS", "GETSCRIPT roundcube", "CHECKSCRIPT", "PUTSCRIPT roundcube"),
            sieve.commands,
        )
        assertEquals("roundcube", sieve.active)
        assertEquals(listOf(handRule, added), sieve.rulesOf("roundcube"))
        assertTrue(sieve.scripts.getValue("roundcube").startsWith(handWritten))
        assertEquals("the inactive sterna is not touched", SieveCodec.generate(rules), sieve.scripts["sterna"])
    }

    @Test fun `a save over the active script puts it in service with no SETACTIVE to lose`() {
        val before = listOf(FilterRule(name = "Old", value = "old@example.test", moveTo = "Old"))
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(before)), active = "sterna", cutAt = "SETACTIVE")
        val accounts = accounts(sieve)
        val opened = accounts.opening()

        accounts.saveRules(opened, rules)

        assertEquals(rules, sieve.rulesOf("sterna"))
        val status = runBlocking { accounts.loadFilterScriptStatus(imapAccount) }!!
        assertEquals(true, status.scriptActive)
        assertEquals(2, status.enabledRuleCount)
        assertNull(refreshedFilterWarning(previous = null, status = status, responderEnabled = null))
    }

    @Test fun `an inactive sterna with no active script is written and activated`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = null)
        val accounts = accounts(sieve)
        val loaded = runBlocking { accounts.loadFilterRules(imapAccount) } as FilterRulesState.Loaded
        sieve.commands.clear()

        runBlocking { accounts.saveFilterScript(imapAccount, FilterScriptWrite(loaded.snapshot, loaded.scriptToEdit)) }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna", "CHECKSCRIPT", "PUTSCRIPT sterna", "SETACTIVE sterna"), sieve.commands)
        assertEquals("sterna", sieve.active)
    }

    @Test fun `a script activated since the screen opened refuses the save, and nothing is written`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to handWritten), active = null)
        val accounts = accounts(sieve)
        val opened = accounts.opening()
        sieve.active = "roundcube"
        sieve.commands.clear()

        thrownBy<FilterScriptChangedException> { accounts.saveRules(opened, rules.drop(1)) }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube"), sieve.commands)
        assertEquals(SieveCodec.generate(rules), sieve.scripts["sterna"])
        assertEquals("roundcube", sieve.active)
    }

    @Test fun `a rule added elsewhere since the screen opened refuses the save, and it is kept`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")
        val accounts = accounts(sieve)
        val opened = accounts.opening()
        val elsewhere = SieveCodec.generate(rules) + "if header :contains \"subject\" \"new\" {\n    fileinto \"New\";\n}\n"
        sieve.scripts["sterna"] = elsewhere
        sieve.commands.clear()

        thrownBy<FilterScriptChangedException> { accounts.saveRules(opened, rules.drop(1)) }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
        assertEquals(elsewhere, sieve.scripts["sterna"])
    }

    @Test fun `a script turned unreadable since the screen opened is not replaced unasked`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")
        val accounts = accounts(sieve)
        val opened = accounts.opening()
        sieve.scripts["sterna"] = "keep;\n/* never closed\n"

        thrownBy<FilterScriptChangedException> { accounts.saveRules(opened, rules) }

        assertEquals("keep;\n/* never closed\n", sieve.scripts["sterna"])
    }

    @Test fun `sterna created elsewhere after an empty account was opened refuses the save`() {
        val sieve = FakeSieve()
        val accounts = accounts(sieve)
        val opened = accounts.opening()
        sieve.scripts["sterna"] = handWritten

        thrownBy<FilterScriptChangedException> { accounts.saveRules(opened, rules) }

        assertEquals(handWritten, sieve.scripts["sterna"])
        assertNull(sieve.active)
    }

    @Test fun `an account with no script gets sterna, checked, stored and activated in one connection`() {
        val sieve = FakeSieve(offersCheckScript = true)
        val script = readable("").add(rules[0])

        runBlocking { accounts(sieve).saveFilterScript(imapAccount, FilterScriptWrite(nothingRead, script)) }

        assertEquals(listOf("LISTSCRIPTS", "CHECKSCRIPT", "PUTSCRIPT sterna", "SETACTIVE sterna"), sieve.commands)
        assertEquals(listOf(script.emit()), sieve.checked)
        assertEquals(script.emit(), sieve.scripts["sterna"])
        assertEquals("sterna", sieve.active)
        assertEquals(listOf(login), sieve.logins)
        assertEquals(0, host.jmapSessions)
    }

    @Test fun `without CHECKSCRIPT the save stores and activates only`() {
        val sieve = FakeSieve(offersCheckScript = false)

        runBlocking { accounts(sieve).saveFilterScript(imapAccount, FilterScriptWrite(nothingRead, readable("").add(rules[0]))) }

        assertEquals(listOf("LISTSCRIPTS", "PUTSCRIPT sterna", "SETACTIVE sterna"), sieve.commands)
        assertEquals("sterna", sieve.active)
    }

    @Test fun `a PUTSCRIPT rejection reaches the caller with the server's text and changes nothing`() {
        val sieve = FakeSieve(
            scripts = mapOf("roundcube" to handWritten),
            active = "roundcube",
            refuse = { if (it == "PUTSCRIPT") refused(it, "line 4: unknown extension") else null },
        )

        val thrown = thrownBy<ManageSieveException> {
            runBlocking {
                accounts(sieve).saveFilterScript(imapAccount, FilterScriptWrite(ScriptSnapshot("roundcube", handWritten), readable(handWritten).add(rules[0])))
            }
        }

        assertEquals("line 4: unknown extension", thrown.serverText)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube", "CHECKSCRIPT", "PUTSCRIPT roundcube"), sieve.commands)
        assertEquals("roundcube", sieve.active)
        assertEquals(handWritten, sieve.scripts["roundcube"])
    }

    @Test fun `a CHECKSCRIPT rejection stops the save before anything is stored`() {
        val sieve = FakeSieve(refuse = { if (it == "CHECKSCRIPT") refused(it, "fileinto: unknown") else null })

        val thrown = thrownBy<ManageSieveException> {
            runBlocking { accounts(sieve).saveFilterScript(imapAccount, FilterScriptWrite(nothingRead, readable("").add(rules[0]))) }
        }

        assertEquals("fileinto: unknown", thrown.serverText)
        assertEquals(listOf("LISTSCRIPTS", "CHECKSCRIPT"), sieve.commands)
        assertNull(sieve.scripts["sterna"])
    }

    @Test fun `a connection lost between PUTSCRIPT and a first SETACTIVE reads back as rules not running`() {
        val sieve = FakeSieve(cutAt = "SETACTIVE")
        val accounts = accounts(sieve)

        val thrown = thrownBy<ManageSieveException> { accounts.saveRules(accounts.opening(), rules) }
        assertEquals(ManageSieveException.Kind.UNREACHABLE, thrown.kind)

        val status = runBlocking { accounts.loadFilterScriptStatus(imapAccount) }!!
        assertEquals(true, status.scriptExists)
        assertEquals(false, status.scriptActive)
        assertEquals(
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
        )
    }

    // ---- a sender block --------------------------------------------------------------------------

    @Test fun `a readable Bulwark script, active, takes a sender block in place`() {
        val bulwark = fixture("f237-bulwark")
        val sieve = FakeSieve(scripts = mapOf("bulwark" to bulwark), active = "bulwark")
        val accounts = accounts(sieve)

        val outcome = runBlocking {
            addBlockRule(
                address = "spam@example.test",
                trashFolder = "INBOX.Trash",
                load = { accounts.loadFilterRules(imapAccount) },
                save = { accounts.saveFilterScript(imapAccount, it) },
            )
        }

        assertEquals(BlockOutcome.ADDED, outcome)
        assertEquals(
            listOf("LISTSCRIPTS", "GETSCRIPT bulwark", "LISTSCRIPTS", "GETSCRIPT bulwark", "CHECKSCRIPT", "PUTSCRIPT bulwark"),
            sieve.commands,
        )
        assertEquals(setOf("bulwark"), sieve.scripts.keys)
        assertEquals("bulwark", sieve.active)
        val reread = loadedFilterRules("bulwark", sieve.scripts.getValue("bulwark"))
        assertEquals(BulwarkMetadata.READABLE, reread.script!!.bulwarkMetadata)
        assertTrue(blockRule("spam@example.test", "INBOX.Trash") in reread.rules)
    }

    // ---- the auto-reply -------------------------------------------------------------------------

    private val away = VacationResponse(
        isEnabled = true,
        fromDate = "2026-09-20T00:00:00Z",
        toDate = "2026-09-27T23:59:59Z",
        subject = "Away",
        textBody = "Back on the 28th.",
    )

    /** [away] as the opened script keeps it for [imapAccount] and [identified]. */
    private val awayInScript = SieveVacation(
        isEnabled = true,
        fromDate = "2026-09-20T00:00:00Z",
        toDate = "2026-09-27T23:59:59Z",
        subject = "Away",
        textBody = "Back on the 28th.",
        addresses = listOf("alex@example.test", "a.martin@example.test"),
    )

    private class IdentifiedStorage : RecordingAccountStorage() {
        override fun identities(accountId: String?): List<StoredIdentity> {
            super.identities(accountId)
            return listOf(
                StoredIdentity(id = "i1", name = "Alex", email = "alex@example.test"),
                StoredIdentity(id = "i2", name = "Alex Martin", email = "a.martin@example.test"),
            )
        }
    }

    private val identified = IdentifiedStorage()

    @Test fun `the auto-reply is read out of the active script, with nothing to confirm`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules, awayInScript)), active = "sterna")

        val state = runBlocking { accounts(sieve).loadVacation(imapAccount) }.withoutScript()

        assertEquals(VacationState.Loaded(response = away, foreignActiveScript = false), state)
        assertEquals(listOf(login), sieve.logins)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
        assertEquals(0, host.jmapSessions)
        assertEquals(
            ScriptSnapshot("sterna", SieveCodec.generate(rules, awayInScript)),
            (runBlocking { accounts(sieve).loadVacation(imapAccount) } as VacationState.Loaded).script,
        )
    }

    @Test fun `without a script, or without a responder in it, the auto-reply is off and empty`() {
        for (scripts in listOf(emptyMap(), mapOf("sterna" to SieveCodec.generate(rules)))) {
            val sieve = FakeSieve(scripts = scripts, active = scripts.keys.firstOrNull())

            val state = runBlocking { accounts(sieve).loadVacation(imapAccount) }.withoutScript()

            assertEquals("scripts $scripts", VacationState.Loaded(response = VacationResponse(), foreignActiveScript = false), state)
        }
    }

    @Test fun `another active script is where the auto-reply is read, and nothing asks before writing`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules, awayInScript), "roundcube" to handWritten),
            active = "roundcube",
        )

        val state = runBlocking { accounts(sieve).loadVacation(imapAccount) }.withoutScript()

        assertEquals(VacationState.Loaded(response = VacationResponse(), foreignActiveScript = false), state)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube"), sieve.commands)
        assertEquals("roundcube", sieve.active)
    }

    @Test fun `only a script that cannot be cut asks before writing the auto-reply`() {
        val handEdited = SieveCodec.generate(rules, awayInScript) + "if header :contains \"subject\" \"x\" { discard; }\n"
        val broken = SieveCodec.generate(rules, awayInScript) + "/* never closed\n"
        for ((script, confirm) in listOf(handEdited to false, broken to true)) {
            val sieve = FakeSieve(scripts = mapOf("sterna" to script), active = "sterna")

            val state = runBlocking { accounts(sieve).loadVacation(imapAccount) }.withoutScript()

            assertEquals(script, VacationState.Loaded(response = away, foreignActiveScript = confirm), state)
        }
    }

    @Test fun `a server missing vacation, date or relational is Unsupported, the server's own cause`() {
        for (missing in listOf("vacation", "date", "relational")) {
            val sieve = FakeSieve(
                scripts = mapOf("sterna" to SieveCodec.generate(rules, awayInScript)),
                active = "sterna",
                extensions = listOf("fileinto", "imap4flags", "vacation", "date", "relational") - missing,
            )

            val state = runBlocking { accounts(sieve).loadVacation(imapAccount) }

            assertSame("without $missing", VacationState.Unsupported, state)
            assertEquals("without $missing", listOf(login), sieve.logins)
        }
    }

    @Test fun `an IMAP account ManageSieve is never tried on is AccountUnsupported, without a connection`() {
        for (account in listOf(imapAccount.copy(oauth = oauth, authType = AuthType.OAUTH), imapAccount.copy(imap = null))) {
            val sieve = FakeSieve()
            val accounts = accounts(sieve)

            assertSame(VacationState.AccountUnsupported, runBlocking { accounts.loadVacation(account) })
            val thrown = thrownBy<IllegalStateException> { runBlocking { accounts.saveVacation(account, away, read = null) } }

            assertEquals("Automatic replies are not available for this account", thrown.message)
            assertEquals(emptyList<List<Any>>(), sieve.logins)
            assertEquals(0, host.jmapSessions)
        }
    }

    @Test fun `a failed connection throws out of the auto-reply read, never Unsupported`() {
        val offline = ManageSieveException("ManageSieve connection failed: timeout", ManageSieveException.Kind.UNREACHABLE)
        val sieve = FakeSieve(unreachable = offline)

        val thrown = thrownBy<ManageSieveException> { runBlocking { accounts(sieve).loadVacation(imapAccount) } }

        assertSame(offline, thrown)
    }

    @Test fun `saving the auto-reply rewrites its segment alone, every other block byte for byte`() {
        val text = "require [\"fileinto\", \"vacation\", \"date\", \"relational\"];\n\n" +
            "# rule:[boss]\nif address :is \"from\" \"boss@example.test\" {\n    fileinto \"Work\";\n}\n\n" +
            "# rule:[hand]\nif header :contains \"subject\" \"sale\" {\n    discard;\n}\n"
        val boss = FilterRule(name = "boss", match = RuleMatch.IS, value = "boss@example.test", moveTo = "Work")
        val sieve = FakeSieve(scripts = mapOf("roundcube" to text), active = "roundcube")
        val accounts = accounts(sieve, identified)
        val read = accounts.openingVacation()
        sieve.commands.clear()

        val written = runBlocking { accounts.saveVacation(imapAccount, away, read) }

        assertEquals(away, written.response)
        assertEquals(
            text + "\n" + SieveCodec.vacationMarkerLine(awayInScript) + "\n" +
                SieveCodec.vacationToSieve(awayInScript, listOf(boss)) + "\n",
            sieve.scripts["roundcube"],
        )
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT roundcube", "CHECKSCRIPT", "PUTSCRIPT roundcube"), sieve.commands)
        assertEquals("roundcube", sieve.active)
        assertEquals(ScriptSnapshot("roundcube", sieve.scripts["roundcube"]), written.script)
    }

    @Test fun `an auto-reply save over a script changed since the screen opened writes nothing`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")
        val accounts = accounts(sieve, identified)
        val read = accounts.openingVacation()
        val elsewhere = SieveCodec.generate(rules.drop(1))
        sieve.scripts["sterna"] = elsewhere
        sieve.commands.clear()

        thrownBy<FilterScriptChangedException> { runBlocking { accounts.saveVacation(imapAccount, away, read) } }

        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
        assertEquals(elsewhere, sieve.scripts["sterna"])
    }

    @Test fun `saving the auto-reply keeps the rules and replies for the account's own addresses`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")
        val accounts = accounts(sieve, identified)
        val read = accounts.openingVacation()
        sieve.commands.clear()
        sieve.logins.clear()

        val written = runBlocking { accounts.saveVacation(imapAccount, away, read) }

        val stored = sieve.scripts.getValue("sterna")
        assertEquals(away, written.response)
        assertEquals(rules, sieve.rulesOf("sterna"))
        assertEquals(VacationMarker.Found(awayInScript), SieveCodec.readVacation(stored))
        assertEquals(listOf(stored), sieve.checked)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna", "CHECKSCRIPT", "PUTSCRIPT sterna"), sieve.commands)
        assertEquals("sterna", sieve.active)
        assertEquals(listOf(login), sieve.logins)
        assertEquals("account-1", identified.args("identities")["accountId"])
        assertTrue(stored, ":addresses [\"alex@example.test\", \"a.martin@example.test\"]" in stored)
    }

    @Test fun `saving the auto-reply names the account's Trash, and a filters save keeps it`() {
        val blocked = rules + blockRule("spam@example.test", "INBOX.Trash")
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(blocked)), active = "sterna")
        val accounts = accounts(sieve, identified)

        runBlocking { accounts.saveVacation(imapAccount, away, accounts.openingVacation(), trashFolder = "INBOX.Trash") }

        val withTrash = awayInScript.copy(trashFolder = "INBOX.Trash")
        val stored = sieve.scripts.getValue("sterna")
        assertEquals(VacationMarker.Found(withTrash), SieveCodec.readVacation(stored))
        assertEquals(blocked, sieve.rulesOf("sterna"))
        assertTrue(stored, "if allof (not anyof (address :is \"from\" \"spam@example.test\"), currentdate" in stored)

        accounts.saveRules(accounts.opening(), blocked.map { if (it.name == "News") it.copy(value = "digest") else it })

        assertEquals(VacationMarker.Found(withTrash), SieveCodec.readVacation(sieve.scripts.getValue("sterna")))
    }

    @Test fun `a sender blocked after the auto-reply gets no reply, and unblocked gets one again`() {
        val withTrash = awayInScript.copy(trashFolder = "INBOX.Trash")
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules, withTrash)), active = "sterna")
        val accounts = accounts(sieve)

        val outcome = runBlocking {
            addBlockRule(
                address = "spam@example.test",
                trashFolder = "INBOX.Trash",
                load = { accounts.loadFilterRules(imapAccount) },
                save = { accounts.saveFilterScript(imapAccount, it) },
            )
        }

        assertEquals(BlockOutcome.ADDED, outcome)
        val blocked = sieve.scripts.getValue("sterna")
        assertTrue(blocked, "if allof (not anyof (address :is \"from\" \"spam@example.test\"), currentdate" in blocked)

        accounts.saveRules(accounts.opening(), rules)

        val unblocked = sieve.scripts.getValue("sterna")
        assertEquals(rules, sieve.rulesOf("sterna"))
        assertEquals(VacationMarker.Found(withTrash), SieveCodec.readVacation(unblocked))
        assertTrue(unblocked, "if allof (currentdate" in unblocked)
        assertFalse(unblocked, "not anyof" in unblocked)
    }

    @Test fun `the auto-reply then the filters, and the final script carries both`() {
        val sieve = FakeSieve()
        val accounts = accounts(sieve, identified)

        runBlocking { accounts.saveVacation(imapAccount, away, accounts.openingVacation()) }
        assertEquals("sterna", sieve.active)
        accounts.saveRules(accounts.opening(), rules)

        val stored = sieve.scripts.getValue("sterna")
        assertEquals(rules, sieve.rulesOf("sterna"))
        assertEquals(VacationMarker.Found(awayInScript), SieveCodec.readVacation(stored))
        assertEquals(VacationState.Loaded(response = away), runBlocking { accounts.loadVacation(imapAccount) }.withoutScript())
    }

    @Test fun `the filters then the auto-reply, and the final script carries both`() {
        val sieve = FakeSieve()
        val accounts = accounts(sieve, identified)

        accounts.saveRules(accounts.opening(), rules)
        runBlocking { accounts.saveVacation(imapAccount, away, accounts.openingVacation()) }

        assertEquals(VacationMarker.Found(awayInScript), SieveCodec.readVacation(sieve.scripts.getValue("sterna")))
        assertEquals(rules, (runBlocking { accounts.loadFilterRules(imapAccount) } as FilterRulesState.Loaded).rules)
    }

    @Test fun `a filters save leaves an unreadable auto-reply marker where it was`() {
        val broken = "# STERNA-VACATION-V1: {not json"
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules) + broken + "\n"), active = "sterna")

        val accounts = accounts(sieve)
        accounts.saveRules(accounts.opening(), rules.map { if (it.name == "News") it.copy(value = "digest") else it })

        assertTrue(sieve.scripts.getValue("sterna"), sieve.scripts.getValue("sterna").contains(broken + "\n"))
    }

    @Test fun `saving the auto-reply over a script that cannot be cut replaces what could not be read`() {
        val sieve = FakeSieve(scripts = mapOf("roundcube" to "require \"fileinto\";\n/* never closed\n"), active = "roundcube")

        val accounts = accounts(sieve, identified)
        runBlocking { accounts.saveVacation(imapAccount, away, accounts.openingVacation()) }

        assertEquals(readable("").withResponder(awayInScript).emit(), sieve.scripts["roundcube"])
        assertEquals("roundcube", sieve.active)
    }

    // ---- accounts ManageSieve does not serve -----------------------------------------------------

    @Test fun `an OAuth IMAP account is Unsupported and refused, without a connection`() {
        for (account in listOf(
            imapAccount.copy(oauth = oauth, authType = AuthType.OAUTH),
            imapAccount.copy(authType = AuthType.OAUTH),
        )) {
            val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")
            val accounts = accounts(sieve)

            assertSame(FilterRulesState.Unsupported, runBlocking { accounts.loadFilterRules(account) })
            assertEquals(FilterScriptStatus(), runBlocking { accounts.loadFilterScriptStatus(account) })
            val thrown = thrownBy<IllegalStateException> { runBlocking { accounts.saveFilterScript(account, FilterScriptWrite(nothingRead, readable(""))) } }

            assertEquals("Server-side filters are not available for this account", thrown.message)
            assertEquals(emptyList<List<Any>>(), sieve.logins)
            assertEquals(0, host.jmapSessions)
        }
    }

    @Test fun `an IMAP account without an IMAP endpoint is refused before any network`() {
        val sieve = FakeSieve()

        val thrown = thrownBy<IllegalStateException> {
            runBlocking { accounts(sieve).saveFilterScript(imapAccount.copy(imap = null), FilterScriptWrite(nothingRead, readable(""))) }
        }

        assertEquals("Server-side filters are not available for this account", thrown.message)
        assertEquals(emptyList<List<Any>>(), sieve.logins)
        assertEquals(0, host.jmapSessions)
    }

    @Test fun `a JMAP account never opens ManageSieve`() {
        val sieve = FakeSieve()
        val jmap = imapAccount.copy(protocol = MailProtocol.JMAP, server = "https://jmap.example.test", imap = null)
        val accounts = accounts(sieve)

        thrownBy<JmapReached> { runBlocking { accounts.loadFilterRules(jmap) } }
        assertNull(runBlocking { accounts.loadFilterScriptStatus(jmap) })
        thrownBy<JmapReached> { runBlocking { accounts.saveFilterScript(jmap, FilterScriptWrite(nothingRead, readable(""))) } }
        thrownBy<JmapReached> { runBlocking { accounts.loadVacation(jmap) } }
        thrownBy<JmapReached> { runBlocking { accounts.saveVacation(jmap, away, read = null) } }

        assertEquals(5, host.jmapSessions)
        assertEquals(emptyList<List<Any>>(), sieve.logins)
    }

    // ---- a folder named in UTF-8 ---------------------------------------------------------------

    @Test fun `an IMAP child folder with a Chinese name is cached under its decoded path`() {
        for ((path, delimiter) in listOf("项目/报告" to "/", "INBOX.项目.报告" to ".")) {
            val mailbox = imapMailboxEntity(
                "account-1",
                ImapFolder(name = "报告", path = path, role = null, delimiter = delimiter),
                index = 0,
            ).toMailbox()
            assertEquals(path, mailbox.id)
            assertEquals("报告", mailbox.name)
            assertNull(mailbox.parentId)
        }
    }

    @Test fun `a rule into a Chinese folder path comes back identical through the server`() {
        val intoChinese = listOf(
            FilterRule(name = "报告", value = "report@example.test", moveTo = "项目/报告"),
            FilterRule(name = "dot", value = "dot@example.test", moveTo = "INBOX.项目.报告"),
        )
        val sieve = FakeSieve()
        val accounts = accounts(sieve)

        accounts.saveRules(accounts.opening(), intoChinese)
        val state = runBlocking { accounts.loadFilterRules(imapAccount) } as FilterRulesState.Loaded

        assertEquals(intoChinese, state.rules)
        assertEquals(false, state.scriptUnreadable)
        val stored = sieve.scripts.getValue("sterna")
        assertTrue(stored, "fileinto \"项目/报告\";" in stored)
        assertTrue(stored, "fileinto \"INBOX.项目.报告\";" in stored)
    }
}

/** What the fake server announces unless a test says otherwise; a load hands it on as the form's offer. */
private val ANNOUNCED = listOf("fileinto", "imap4flags", "vacation", "date", "relational")

package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.RecordingAccountStorage
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.FilterScriptWarning
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.ImapFolder
import app.sterna.core.imap.ManageSieveException
import app.sterna.core.imap.SieveScriptInfo
import app.sterna.core.imap.SmtpClient
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.OAuthClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The filters of an IMAP account, executed through `MailAccounts` against an in-memory ManageSieve
 * server behind the [SieveConnector] seam.
 *
 * What this does NOT cover:
 * - The wire. `ManageSieveClientTest` holds the dialogue, the literal octet count of a non-ASCII
 *   script, STARTTLS and the `CHECKSCRIPT`-without-`VERSION` decision this fake only mirrors.
 * - `ManageSieveConnector`, the delegations from the seam to `ManageSieveSession`: a swapped one
 *   passes here, and only the source lint `ManageSieveConnectorWiringTest` holds them.
 * - The JMAP bodies of the three functions, which have no executed test; only their dispatch is
 *   run below.
 */
class FiltersOverManageSieveTest {

    private class FakeSieve(
        scripts: Map<String, String> = emptyMap(),
        var active: String? = null,
        private val offersCheckScript: Boolean = true,
        private val refuse: (verb: String) -> Exception? = { null },
        /** The command at which the connection is lost, before the server receives it. */
        private val cutAt: String? = null,
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
            return Session().block()
        }

        private inner class Session : SieveScripts {
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

    private fun accounts(sieve: FakeSieve): MailAccounts {
        val store = RecordingAccountStorage()
        return MailAccounts(
            client = JmapClient(),
            imap = ImapMailService(ImapClient(), SmtpClient(), OAuthTokenRefresher(OAuthClient(), store)),
            emailDao = untouched<EmailDao>(),
            emailFtsDao = untouched<EmailFtsDao>(),
            emailBodyDao = untouched<EmailBodyDao>(),
            mailboxDao = untouched<MailboxDao>(),
            snoozedDao = untouched<SnoozedDao>(),
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

    // ---- read ----------------------------------------------------------------------------------

    @Test fun `the sterna script's rules are read on the IMAP host with the IMAP login`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules)), active = "sterna")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(FilterRulesState.Loaded(rules = rules, foreignActiveScript = false, scriptUnreadable = false), state)
        assertEquals(listOf(login), sieve.logins)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
        assertEquals(0, host.jmapSessions)
    }

    @Test fun `another active script is reported, the sterna rules still read`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules), "roundcube" to "require \"fileinto\";\nkeep;\n"),
            active = "roundcube",
        )

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(FilterRulesState.Loaded(rules = rules, foreignActiveScript = true, scriptUnreadable = false), state)
    }

    @Test fun `a sterna script edited by hand is unreadable and foreign, even while active`() {
        val edited = SieveCodec.generate(rules) + "if header :contains \"subject\" \"x\" { discard; }\n"
        val sieve = FakeSieve(scripts = mapOf("sterna" to edited), active = "sterna")

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(FilterRulesState.Loaded(rules = rules, foreignActiveScript = true, scriptUnreadable = true), state)
    }

    @Test fun `without a sterna script nothing is fetched and the list is empty`() {
        val sieve = FakeSieve(scripts = mapOf("vacation" to "vacation \"away\";\n"), active = null)

        val state = runBlocking { accounts(sieve).loadFilterRules(imapAccount) }

        assertEquals(FilterRulesState.Loaded(rules = emptyList(), foreignActiveScript = false, scriptUnreadable = false), state)
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

    // ---- status --------------------------------------------------------------------------------

    @Test fun `an inactive sterna script beside an active vacation script`() {
        val sieve = FakeSieve(
            scripts = mapOf("sterna" to SieveCodec.generate(rules), "vacation" to "vacation \"away\";\n"),
            active = "vacation",
        )

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }

        assertEquals(
            FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 2,
                vacationScriptExists = true,
                vacationScriptActive = true,
                foreignActive = true,
            ),
            status,
        )
        assertEquals(listOf(login), sieve.logins)
        assertEquals(listOf("LISTSCRIPTS", "GETSCRIPT sterna"), sieve.commands)
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

    @Test fun `without a sterna script the status still names the other scripts`() {
        val sieve = FakeSieve(scripts = mapOf("vacation" to "vacation \"away\";\n", "other" to "keep;\n"), active = "other")

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }

        assertEquals(
            FilterScriptStatus(vacationScriptExists = true, vacationScriptActive = false, foreignActive = true),
            status,
        )
        assertEquals(listOf("LISTSCRIPTS"), sieve.commands)
    }

    @Test fun `a status without any vacation script says so`() {
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(rules), "other" to "keep;\n"), active = "sterna")

        val status = runBlocking { accounts(sieve).loadFilterScriptStatus(imapAccount) }

        assertEquals(
            FilterScriptStatus(
                scriptExists = true,
                scriptActive = true,
                enabledRuleCount = 2,
                vacationScriptExists = false,
                vacationScriptActive = false,
                foreignActive = false,
            ),
            status,
        )
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

    @Test fun `a save checks, stores and activates the generated script in one connection`() {
        val sieve = FakeSieve(scripts = mapOf("roundcube" to "keep;\n"), active = "roundcube", offersCheckScript = true)

        runBlocking { accounts(sieve).saveFilterRules(imapAccount, rules) }

        assertEquals(listOf("CHECKSCRIPT", "PUTSCRIPT sterna", "SETACTIVE sterna"), sieve.commands)
        assertEquals(listOf(SieveCodec.generate(rules)), sieve.checked)
        assertEquals(SieveCodec.generate(rules), sieve.scripts["sterna"])
        assertEquals("sterna", sieve.active)
        assertEquals(listOf(login), sieve.logins)
        assertEquals(0, host.jmapSessions)
    }

    @Test fun `without CHECKSCRIPT the save stores and activates only`() {
        val sieve = FakeSieve(offersCheckScript = false)

        runBlocking { accounts(sieve).saveFilterRules(imapAccount, rules) }

        assertEquals(listOf("PUTSCRIPT sterna", "SETACTIVE sterna"), sieve.commands)
        assertEquals("sterna", sieve.active)
    }

    @Test fun `a PUTSCRIPT rejection reaches the caller with the server's text and activates nothing`() {
        val sieve = FakeSieve(
            scripts = mapOf("roundcube" to "keep;\n"),
            active = "roundcube",
            refuse = { if (it == "PUTSCRIPT") refused(it, "line 4: unknown extension") else null },
        )

        val thrown = thrownBy<ManageSieveException> { runBlocking { accounts(sieve).saveFilterRules(imapAccount, rules) } }

        assertEquals("line 4: unknown extension", thrown.serverText)
        assertEquals(listOf("CHECKSCRIPT", "PUTSCRIPT sterna"), sieve.commands)
        assertEquals("roundcube", sieve.active)
    }

    @Test fun `a CHECKSCRIPT rejection stops the save before anything is stored`() {
        val sieve = FakeSieve(refuse = { if (it == "CHECKSCRIPT") refused(it, "fileinto: unknown") else null })

        val thrown = thrownBy<ManageSieveException> { runBlocking { accounts(sieve).saveFilterRules(imapAccount, rules) } }

        assertEquals("fileinto: unknown", thrown.serverText)
        assertEquals(listOf("CHECKSCRIPT"), sieve.commands)
        assertNull(sieve.scripts["sterna"])
    }

    @Test fun `a connection lost between PUTSCRIPT and SETACTIVE reads back as rules not running`() {
        val sieve = FakeSieve(scripts = mapOf("roundcube" to "keep;\n"), active = "roundcube", cutAt = "SETACTIVE")
        val accounts = accounts(sieve)

        val thrown = thrownBy<ManageSieveException> { runBlocking { accounts.saveFilterRules(imapAccount, rules) } }
        assertEquals(ManageSieveException.Kind.UNREACHABLE, thrown.kind)

        val status = runBlocking { accounts.loadFilterScriptStatus(imapAccount) }!!
        assertEquals(true, status.scriptExists)
        assertEquals(false, status.scriptActive)
        assertEquals(
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
        )
    }

    @Test fun `over an active sterna, a connection lost before SETACTIVE leaves the new rules running`() {
        val before = listOf(FilterRule(name = "Old", value = "old@example.test", moveTo = "Old"))
        val sieve = FakeSieve(scripts = mapOf("sterna" to SieveCodec.generate(before)), active = "sterna", cutAt = "SETACTIVE")
        val accounts = accounts(sieve)

        thrownBy<ManageSieveException> { runBlocking { accounts.saveFilterRules(imapAccount, rules) } }

        val status = runBlocking { accounts.loadFilterScriptStatus(imapAccount) }!!
        assertEquals(true, status.scriptActive)
        assertEquals(2, status.enabledRuleCount)
        assertEquals(
            FilterRulesState.Loaded(rules = rules, foreignActiveScript = false, scriptUnreadable = false),
            runBlocking { accounts.loadFilterRules(imapAccount) },
        )
        assertNull(refreshedFilterWarning(previous = null, status = status, responderEnabled = null))
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
            val thrown = thrownBy<IllegalStateException> { runBlocking { accounts.saveFilterRules(account, rules) } }

            assertEquals("Server-side filters are not available for this account", thrown.message)
            assertEquals(emptyList<List<Any>>(), sieve.logins)
            assertEquals(0, host.jmapSessions)
        }
    }

    @Test fun `an IMAP account without an IMAP endpoint is refused before any network`() {
        val sieve = FakeSieve()

        val thrown = thrownBy<IllegalStateException> {
            runBlocking { accounts(sieve).saveFilterRules(imapAccount.copy(imap = null), rules) }
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
        thrownBy<JmapReached> { runBlocking { accounts.saveFilterRules(jmap, rules) } }

        assertEquals(3, host.jmapSessions)
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

        runBlocking { accounts.saveFilterRules(imapAccount, intoChinese) }
        val state = runBlocking { accounts.loadFilterRules(imapAccount) }

        assertEquals(FilterRulesState.Loaded(rules = intoChinese, foreignActiveScript = false, scriptUnreadable = false), state)
        val stored = sieve.scripts.getValue("sterna")
        assertTrue(stored, "fileinto \"项目/报告\";" in stored)
        assertTrue(stored, "fileinto \"INBOX.项目.报告\";" in stored)
    }
}

package app.sterna.core.data.mail

import app.sterna.core.data.filter.SourceText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint. `ManageSieveConnector` is the one piece of the IMAP filters path no JVM test runs:
 * `ManageSieveSession` cannot be built outside `:core:imap`, and `FiltersOverManageSieveTest` goes
 * through a fake behind the same seam. So its code is pinned here, whole, line for line, and the
 * shipped default of `MailAccounts` with it.
 *
 * It proves the delegations are written, never that they work. It does not cover:
 * - what `ManageSieveClient` and `ManageSieveSession` do with what they are handed, which is
 *   `ManageSieveClientTest`'s, nor that `ManageSieveClient()` is the STARTTLS transport;
 * - the order of `ManageSieveClient.session`'s own parameters: pinned here is the call as written,
 *   and swapping two `String` parameters over there would still compile and pass;
 * - `MailRepository` building `MailAccounts` without a `sieve` argument, which
 *   `MailRepositoryLogsThroughASeamTest` pins.
 */
class ManageSieveConnectorWiringTest {

    @Test fun `the connector opens the session it is asked for and forwards each command unchanged`() {
        val lines = SourceText.codeLines(SourceText.read("$DIR/SieveConnector.kt"))
        val start = lines.indexOfFirst { it.startsWith("internal class ManageSieveConnector(") }
        val end = lines.indexOfFirst { it.startsWith("internal fun manageSieveHost(") }
        check(start >= 0 && end > start) { "SieveConnector.kt no longer declares ManageSieveConnector before manageSieveHost" }

        assertEquals(
            "ManageSieveConnector is no longer, line for line, what this was written against. A " +
                "delegation that drops or swaps an argument, or answers Unit, passes every executed test.",
            listOf(
                "internal class ManageSieveConnector(private val client: ManageSieveClient = ManageSieveClient()) : SieveConnector {",
                "override suspend fun <T> session(",
                "host: String,",
                "port: Int,",
                "username: String,",
                "password: String,",
                "block: SieveScripts.() -> T,",
                "): T = client.session(host, port, username, password) { SessionScripts(this).block() }",
                "private class SessionScripts(private val session: ManageSieveSession) : SieveScripts {",
                "override fun listScripts(): List<SieveScriptInfo> = session.listScripts()",
                "override fun getScript(name: String): String = session.getScript(name)",
                "override fun checkScript(script: String): Boolean = session.checkScript(script)",
                "override fun putScript(name: String, script: String) = session.putScript(name, script)",
                "override fun setActive(name: String) = session.setActive(name)",
                "}",
                "}",
            ),
            lines.subList(start, end),
        )
    }

    @Test fun `MailAccounts ships with the real connector`() {
        val lines = SourceText.codeLines(SourceText.read("$DIR/MailAccounts.kt"))
        assertEquals(
            listOf("private val sieve: SieveConnector = ManageSieveConnector(),"),
            lines.filter { it.startsWith("private val sieve:") },
        )
    }

    private companion object {
        const val DIR = "core/data/src/main/kotlin/app/sterna/core/data/mail"
    }
}

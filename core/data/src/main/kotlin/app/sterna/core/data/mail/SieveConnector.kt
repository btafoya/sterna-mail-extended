package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.imap.ManageSieveClient
import app.sterna.core.imap.ManageSieveSession
import app.sterna.core.imap.SieveScriptInfo

/** The ManageSieve commands [MailAccounts] sends inside one authenticated connection. */
interface SieveScripts {
    /** The Sieve extensions the server advertised after `STARTTLS`, as listed. */
    val sieveExtensions: List<String>

    fun listScripts(): List<SieveScriptInfo>

    fun getScript(name: String): String

    /** `false` when the server offers no `CHECKSCRIPT` and nothing was sent; a rejection throws. */
    fun checkScript(script: String): Boolean

    fun putScript(name: String, script: String)

    fun setActive(name: String)
}

/**
 * One ManageSieve connection per [session]. An interface so [MailAccounts] runs against an
 * in-memory server in tests: the loopback transport of `ManageSieveClient` is internal to
 * `:core:imap`, and the shipped one requires a TLS handshake.
 */
interface SieveConnector {
    suspend fun <T> session(host: String, port: Int, username: String, password: String, block: SieveScripts.() -> T): T
}

internal class ManageSieveConnector(private val client: ManageSieveClient = ManageSieveClient()) : SieveConnector {
    override suspend fun <T> session(
        host: String,
        port: Int,
        username: String,
        password: String,
        block: SieveScripts.() -> T,
    ): T = client.session(host, port, username, password) { SessionScripts(this).block() }

    private class SessionScripts(private val session: ManageSieveSession) : SieveScripts {
        override val sieveExtensions: List<String> get() = session.capabilities.sieveExtensions
        override fun listScripts(): List<SieveScriptInfo> = session.listScripts()
        override fun getScript(name: String): String = session.getScript(name)
        override fun checkScript(script: String): Boolean = session.checkScript(script)
        override fun putScript(name: String, script: String) = session.putScript(name, script)
        override fun setActive(name: String) = session.setActive(name)
    }
}

/**
 * The host an account's filters are reached on over ManageSieve: its IMAP host. Null for a JMAP
 * account, for an IMAP account without an IMAP host, and for an OAuth one, refused without trying:
 * Outlook, the one OAuth IMAP provider, has no ManageSieve, and the client authenticates with
 * `PLAIN` only.
 */
internal fun manageSieveHost(credentials: AccountCredentials): String? = when {
    credentials.protocol != MailProtocol.IMAP -> null
    credentials.oauth != null || credentials.authType == AuthType.OAUTH -> null
    else -> credentials.imap?.host
}

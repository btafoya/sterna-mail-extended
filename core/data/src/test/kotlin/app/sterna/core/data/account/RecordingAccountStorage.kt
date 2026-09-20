package app.sterna.core.data.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * An [AccountStorage] that stores nothing and writes down what it was handed. It exists so a test
 * can assert on a value it never passed: an argument that arrives here without the caller naming
 * it is the default the interface declares, executed rather than read off the source.
 */
internal open class RecordingAccountStorage : AccountStorage {

    /** Every call, in order, as its name and the arguments the implementation received. */
    val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

    /** The arguments of the first call to [name]. Fails the test when there was none. */
    fun args(name: String): Map<String, Any?> =
        calls.firstOrNull { it.first == name }?.second ?: error("No call to $name was recorded.")

    private fun record(name: String, vararg args: Pair<String, Any?>) {
        calls += name to args.toMap()
    }

    override val accountsFlow: StateFlow<List<StoredAccount>> = MutableStateFlow(emptyList())

    override fun accounts(): List<StoredAccount> {
        record("accounts")
        return emptyList()
    }

    override fun accountsUnreadable(): Boolean {
        record("accountsUnreadable")
        return false
    }

    override fun currentId(): String? {
        record("currentId")
        return null
    }

    override fun addOAuth(
        server: String,
        username: String,
        accountName: String,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
        protocol: MailProtocol,
        imapHost: String,
        imapPort: Int,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int,
        smtpSecurity: ConnectionSecurity,
        documentHost: String,
    ): String {
        record(
            "addOAuth",
            "server" to server,
            "username" to username,
            "accountName" to accountName,
            "accessToken" to accessToken,
            "refreshToken" to refreshToken,
            "accessExpiresAtMillis" to accessExpiresAtMillis,
            "tokenEndpoint" to tokenEndpoint,
            "clientId" to clientId,
            "protocol" to protocol,
            "imapHost" to imapHost,
            "imapPort" to imapPort,
            "imapSecurity" to imapSecurity,
            "smtpHost" to smtpHost,
            "smtpPort" to smtpPort,
            "smtpSecurity" to smtpSecurity,
            "documentHost" to documentHost,
        )
        return "added"
    }

    override fun updateOAuthTokens(id: String, accessToken: String, refreshToken: String, accessExpiresAtMillis: Long) {
        record(
            "updateOAuthTokens",
            "id" to id,
            "accessToken" to accessToken,
            "refreshToken" to refreshToken,
            "accessExpiresAtMillis" to accessExpiresAtMillis,
        )
    }

    override fun account(id: String): StoredAccount? {
        record("account", "id" to id)
        return null
    }

    override fun identities(accountId: String?): List<StoredIdentity> {
        record("identities", "accountId" to accountId)
        return emptyList()
    }

    override fun setServerIdentities(accountId: String, identities: List<StoredIdentity>) {
        record("setServerIdentities", "accountId" to accountId, "identities" to identities)
    }

    override fun uploadSentCopy(id: String): Boolean {
        record("uploadSentCopy", "id" to id)
        return true
    }

    override fun showOnlySubscribedFolders(id: String): Boolean {
        record("showOnlySubscribedFolders", "id" to id)
        return false
    }

    override fun setFolderWatched(id: String, folderId: String, watched: Boolean) {
        record("setFolderWatched", "id" to id, "folderId" to folderId, "watched" to watched)
    }

    override fun setSpecialFolder(id: String, role: String, folderId: String?) {
        record("setSpecialFolder", "id" to id, "role" to role, "folderId" to folderId)
    }

    override fun replaceCollapsedFolder(id: String, oldId: String, newId: String, delimiter: String) {
        record("replaceCollapsedFolder", "id" to id, "oldId" to oldId, "newId" to newId, "delimiter" to delimiter)
    }

    override fun replaceWatchedFolder(id: String, oldId: String, newId: String, delimiter: String) {
        record("replaceWatchedFolder", "id" to id, "oldId" to oldId, "newId" to newId, "delimiter" to delimiter)
    }

    override fun replaceHiddenFolder(id: String, oldId: String, newId: String, delimiter: String) {
        record("replaceHiddenFolder", "id" to id, "oldId" to oldId, "newId" to newId, "delimiter" to delimiter)
    }

    override fun forgetHiddenFolders(id: String, folderIds: Collection<String>) {
        record("forgetHiddenFolders", "id" to id, "folderIds" to folderIds)
    }

    override fun replaceSpecialFolder(id: String, oldId: String, newId: String, delimiter: String) {
        record("replaceSpecialFolder", "id" to id, "oldId" to oldId, "newId" to newId, "delimiter" to delimiter)
    }

    override fun forgetSpecialFolders(id: String, folderIds: Collection<String>) {
        record("forgetSpecialFolders", "id" to id, "folderIds" to folderIds)
    }

    override fun credentials(id: String): AccountCredentials? {
        record("credentials", "id" to id)
        return null
    }

    override fun linkedAccounts(loginId: String): List<StoredAccount> {
        record("linkedAccounts", "loginId" to loginId)
        return emptyList()
    }

    override fun reconcileLinkedAccounts(
        loginId: String,
        discovered: List<DiscoveredMailAccount>,
        probes: Map<String, Result<*>>,
    ): List<String> {
        record("reconcileLinkedAccounts", "loginId" to loginId, "discovered" to discovered, "probes" to probes)
        return emptyList()
    }

    override fun attachOAuth(
        id: String,
        username: String?,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
    ): Boolean {
        record(
            "attachOAuth",
            "id" to id,
            "username" to username,
            "accessToken" to accessToken,
            "refreshToken" to refreshToken,
            "accessExpiresAtMillis" to accessExpiresAtMillis,
            "tokenEndpoint" to tokenEndpoint,
            "clientId" to clientId,
        )
        return true
    }

    override fun saveInboxMeta(mailboxId: String, mailboxName: String, accountName: String, unread: Int) {
        record(
            "saveInboxMeta",
            "mailboxId" to mailboxId,
            "mailboxName" to mailboxName,
            "accountName" to accountName,
            "unread" to unread,
        )
    }

    override fun allInboxScopes(): List<Pair<String, String>> {
        record("allInboxScopes")
        return emptyList()
    }

    override fun adjustInboxUnread(accountId: String, mailboxId: String, delta: Int) {
        record("adjustInboxUnread", "accountId" to accountId, "mailboxId" to mailboxId, "delta" to delta)
    }

    override fun saveInboxMetaFor(
        accountId: String,
        mailboxId: String,
        mailboxName: String,
        accountName: String,
        unread: Int,
    ) {
        record(
            "saveInboxMetaFor",
            "accountId" to accountId,
            "mailboxId" to mailboxId,
            "mailboxName" to mailboxName,
            "accountName" to accountName,
            "unread" to unread,
        )
    }
}

package app.sterna.core.data.account

import kotlinx.coroutines.flow.StateFlow

/**
 * What this module reads and writes on the account store, and nothing else. [AccountStore] itself
 * cannot be built off a device, because it opens shared preferences through a [android.content.Context]
 * while being constructed, so a JVM test that wants a [app.sterna.core.data.mail.MailRepository]
 * needs a type it can implement.
 *
 * The surface is deliberately narrow: the `app` module consumes about fifty further members of the
 * concrete class and keeps depending on it directly, so widening this interface to "everything the
 * store offers" would put the whole preference layer back in front of every fake.
 *
 * The default argument values live here rather than on the implementation, because Kotlin forbids
 * an override from restating one. A caller holding either type sees the same defaults.
 */
interface AccountStorage {

    /** The account list as a live value, for screens that display it. */
    val accountsFlow: StateFlow<List<StoredAccount>>

    /** The stored accounts, or an empty list, including when the blob could not be decoded. */
    fun accounts(): List<StoredAccount>

    /** Whether the stored account list could not be read, the one thing [accounts] cannot say. */
    fun accountsUnreadable(): Boolean

    /** The account the app is currently showing, or null when none is stored. */
    fun currentId(): String?

    /** Add a JMAP account authenticated via OAuth and make it current. Returns its new id. */
    fun addOAuth(
        server: String,
        username: String,
        accountName: String,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
        protocol: MailProtocol = MailProtocol.JMAP,
        imapHost: String = "",
        imapPort: Int = 993,
        imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
        smtpHost: String = "",
        smtpPort: Int = 587,
        smtpSecurity: ConnectionSecurity = ConnectionSecurity.STARTTLS,
        documentHost: String = "",
    ): String

    /** Persist freshly minted OAuth tokens after a refresh. A blank [refreshToken] keeps the stored one. */
    fun updateOAuthTokens(id: String, accessToken: String, refreshToken: String, accessExpiresAtMillis: Long)

    /** Look up a single stored account by id. */
    fun account(id: String): StoredAccount?

    /** Sending identities for an account (null id = current); never empty for a known account. */
    fun identities(accountId: String?): List<StoredIdentity>

    /** Persist the identities discovered from the JMAP server. Manual identities are left untouched. */
    fun setServerIdentities(accountId: String, identities: List<StoredIdentity>)

    /** Whether the app files a copy of a sent message in the account's Sent folder. */
    fun uploadSentCopy(id: String): Boolean

    /** Whether this account's folder lists hide the folders it is not subscribed to on the server. */
    fun showOnlySubscribedFolders(id: String): Boolean

    /** Add or remove a folder from the account's watched set. No-op if the id is unknown. */
    fun setFolderWatched(id: String, folderId: String, watched: Boolean)

    /** Pin [folderId] as the folder carrying [role] for this account, or null to go back to the
     *  automatic role. No-op if the id is unknown. */
    fun setSpecialFolder(id: String, role: String, folderId: String?)

    /** Re-key a folded folder after an IMAP rename, carrying everything under it across. */
    fun replaceCollapsedFolder(id: String, oldId: String, newId: String, delimiter: String = "/")

    /** Re-key a watched folder after an IMAP rename, including watched children of [oldId]. */
    fun replaceWatchedFolder(id: String, oldId: String, newId: String, delimiter: String = "/")

    /** Re-key a hidden folder after an IMAP rename, carrying everything under it across. */
    fun replaceHiddenFolder(id: String, oldId: String, newId: String, delimiter: String = "/")

    /** Drop the hide registry keys of folders a delete destroyed, descendants included. */
    fun forgetHiddenFolders(id: String, folderIds: Collection<String>)

    /** Re-key the special-folder choices after an IMAP rename, carrying everything under it across. */
    fun replaceSpecialFolder(id: String, oldId: String, newId: String, delimiter: String = "/")

    /** Drop the special-folder choices naming folders a delete destroyed, descendants included. */
    fun forgetSpecialFolders(id: String, folderIds: Collection<String>)

    /** Everything needed to open a connection for an account, secrets decrypted. */
    fun credentials(id: String): AccountCredentials?

    /** Sub-accounts linked to [loginId] (they share its credential). Empty for a standalone login. */
    fun linkedAccounts(loginId: String): List<StoredAccount>

    /** Reconcile the sub-accounts of [loginId] against its session, returning the pruned ids. */
    fun reconcileLinkedAccounts(
        loginId: String,
        discovered: List<DiscoveredMailAccount>,
        probes: Map<String, Result<*>>,
    ): List<String>

    /** Attach freshly granted OAuth material to an imported account. False for an unknown id. */
    fun attachOAuth(
        id: String,
        username: String? = null,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
    ): Boolean

    /** Record the current account's inbox id, name and unread count. */
    fun saveInboxMeta(mailboxId: String, mailboxName: String, accountName: String, unread: Int)

    /** The (account id, inbox id) pairs across every account, for reads that stay account-scoped. */
    fun allInboxScopes(): List<Pair<String, String>>

    /** Mirror a drawer-count nudge into the stored inbox snapshot. */
    fun adjustInboxUnread(accountId: String, mailboxId: String, delta: Int)

    /** Record a specific account's inbox id, name and unread count. */
    fun saveInboxMetaFor(accountId: String, mailboxId: String, mailboxName: String, accountName: String, unread: Int)
}

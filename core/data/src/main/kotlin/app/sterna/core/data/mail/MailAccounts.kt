package app.sterna.core.data.mail

import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AccountStorage
import app.sterna.core.data.account.DiscoveredMailAccount
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.OAuthCredentials
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.accountAddresses
import app.sterna.core.data.account.accountKeyOf
import app.sterna.core.data.account.resolveExistingLogin
import app.sterna.core.data.account.resolveExistingLoginAmong
import app.sterna.core.data.account.revokedLinkedIds
import app.sterna.core.data.db.ContactRow
import app.sterna.core.data.db.EmailBodyDao
import app.sterna.core.data.db.EmailDao
import app.sterna.core.data.db.EmailFtsDao
import app.sterna.core.data.db.MailboxDao
import app.sterna.core.data.db.ServerContactDao
import app.sterna.core.data.db.SnoozedDao
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.FilterScriptWrite
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.filter.FilterScriptStatus
import app.sterna.core.data.filter.SieveCodec
import app.sterna.core.data.filter.SieveVacation
import app.sterna.core.data.filter.VacationMarker
import app.sterna.core.data.filter.activatesOnSave
import app.sterna.core.data.filter.filterScriptStatus
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.filter.chosenScriptName
import app.sterna.core.data.filter.listedAfterWrite
import app.sterna.core.data.filter.listing
import app.sterna.core.data.filter.openedScriptName
import app.sterna.core.data.filter.scriptWrittenOver
import app.sterna.core.data.filter.stopsServerResponder
import app.sterna.core.data.filter.unchangedSince
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.mail.MailRepository.DiscoveryResult
import app.sterna.core.data.rethrowIfCancelled
import app.sterna.core.data.search.SearchHistoryRepository
import app.sterna.core.imap.MANAGESIEVE_PORT
import app.sterna.core.imap.SieveScriptInfo
import app.sterna.core.jmap.BasicAuth
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapAuth
import app.sterna.core.jmap.JmapClient
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthClient
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.OAuthTokens
import app.sterna.core.jmap.buildAuthorizationUrl
import app.sterna.core.jmap.model.JmapSession
import app.sterna.core.jmap.model.Quota
import app.sterna.core.jmap.model.SieveScript as JmapSieveScript
import app.sterna.core.jmap.model.VacationResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What [MailAccounts] needs from [MailRepository] and nothing more. */
interface MailAccountHost {
    suspend fun jmapSession(credentials: AccountCredentials): MailRepository.JmapAccountSession
    suspend fun jmapAuth(credentials: AccountCredentials): JmapAuth
    /** The first sync a new account owes before it is shown; the repository's own `refresh`. */
    suspend fun firstSync(credentials: AccountCredentials): MailboxMeta
    /** The session the repository holds warm, if any: the after-add reconcile reuses its auth. */
    fun cachedJmapSession(): MailRepository.JmapAccountSession?
}

/**
 * The accounts themselves: finding a server, proving a credential, the OAuth grants, the three
 * creators, the linked sub-accounts of one login (#31, #129), the server's identities (#32, #172)
 * and its settings (vacation, quotas, Sieve rules). Reached as [MailRepository.accounts].
 *
 * Two things stay in the repository on purpose. `refresh` is the first sync a creator owes, and
 * it is the sync every other path runs, so the creators reach it through [MailAccountHost.firstSync]
 * rather than own a copy. `DiscoveryResult` stays `MailRepository.DiscoveryResult`: it is named
 * from five files, and the move was decided to change no caller's type.
 */
class MailAccounts(
    private val client: JmapClient,
    private val discoveryClient: JmapClient,
    private val imap: ImapMailService,
    private val emailDao: EmailDao,
    private val emailFtsDao: EmailFtsDao,
    private val emailBodyDao: EmailBodyDao,
    private val mailboxDao: MailboxDao,
    private val snoozedDao: SnoozedDao,
    /** The copy of each JMAP account's server address book. */
    private val serverContactDao: ServerContactDao,
    private val accountStore: AccountStorage,
    private val oauthClient: OAuthClient,
    /** The repository's own background scope: the purge of a pruned account outlives the call. */
    private val bgScope: CoroutineScope,
    private val host: MailAccountHost,
    private val logWarn: (String, String, Throwable?) -> Unit = { _, _, _ -> },
    private val sieve: SieveConnector = ManageSieveConnector(),
    /** Cleared whole when a linked account is revoked: a search carries no account. Null in tests. */
    private val searchHistory: SearchHistoryRepository? = null,
) {

    /**
     * App-layer teardown for a linked sub-account pruned on reconcile (#31): clears its notification
     * baselines. Set by the app layer at startup.
     */
    var onAccountPruned: ((String) -> Unit)? = null

    /** JMAP autodiscovery (RFC 8620 §2.2). A 401/403 from a reachable candidate means the server was
     *  found but the password is wrong. Candidates are probed at once; rank decides, not latency. */
    suspend fun discoverJmapServer(email: String, password: String, token: String? = null): DiscoveryResult {
        val hosts = Jmap.autodiscoverHosts(email)
        if (hosts.isEmpty()) return DiscoveryResult.NotFound
        // A non-null [token] is a bearer credential (an API token, or a just-granted access token).
        val auth = if (token != null) BearerAuth(token) else BasicAuth(email.trim(), password)
        // No budget passed on purpose: the shipped value lives once, in JMAP_DISCOVERY_BUDGET_MS.
        return discoverJmapAmong(hosts) { host ->
            probeHost { discoveryClient.fetchSession(Jmap.sessionUrlFor(host), auth) }
        }
    }

    /**
     * Resolve a manually-entered JMAP server to the value to persist as the account's `server`: the first
     */
    suspend fun resolveJmapServerInput(serverInput: String, auth: JmapAuth): String {
        val candidates = Jmap.sessionUrlCandidates(serverInput)
        var firstError: Throwable? = null
        for (url in candidates) {
            val session = try {
                client.fetchSession(url, auth)
            } catch (t: Throwable) {
                if (firstError == null) firstError = t
                continue
            }
            if (session.mailAccountId() != null) {
                return if (candidates.size == 1) serverInput.trim() else url
            }
            if (firstError == null) firstError = JmapException("This user has no JMAP mail account.")
        }
        throw firstError ?: JmapException("No JMAP server found at $serverInput")
    }

    /**
     * The address the server itself associates with [auth] at [server]: the session's `username` (RFC 8620
     */
    suspend fun sessionIdentity(server: String, auth: JmapAuth): String? {
        val session = client.fetchSession(Jmap.sessionUrlFor(server), auth)
        val accountName = session.mailAccountId()?.let { session.accounts[it]?.name }
        val looksLikeEmail = Regex("""[^@\s]+@[^@\s]+\.[^@\s]+""")
        return sequenceOf(session.username, accountName.orEmpty())
            .map { it.trim() }
            .firstOrNull { looksLikeEmail.matches(it) }
    }

    /**
     * Validate an account's credentials without persisting anything or disturbing the active session: JMAP
     * fetches the session and checks for a mail account, IMAP connects.
     */
    suspend fun testConnection(credentials: AccountCredentials): Result<Unit> = runCatching {
        if (credentials.protocol == MailProtocol.IMAP) {
            imap.testConnection(credentials)
        } else {
            val session = client.fetchSession(
                Jmap.sessionUrlFor(credentials.server),
                host.jmapAuth(credentials),
            )
            requireNotNull(session.mailAccountId()) { "This user has no JMAP mail account." }
            Unit
        }
    }

    /**
     * OAuth metadata for a host, or null when it advertises none.
     */
    suspend fun discoverOAuth(host: String, addressDomain: String): OAuthMetadata? =
        oauthClient.discoverMetadata(host, addressDomain)

    /** The OpenID configuration of an issuer the user typed, or null when no sign-in can be driven from it. */
    suspend fun discoverAtIssuer(issuer: String): OAuthMetadata? = oauthClient.discoverAtIssuer(issuer)

    /** Begin the device flow against [metadata] using Sterna's client id + scopes. */
    suspend fun startDeviceAuthorization(metadata: OAuthMetadata): DeviceAuthorization =
        oauthClient.startDeviceAuthorization(metadata, Jmap.OAUTH_CLIENT_ID, Jmap.OAUTH_SCOPE)

    /** Poll the token endpoint once for a pending device authorization. */
    suspend fun pollDeviceToken(metadata: OAuthMetadata, deviceCode: String): DeviceTokenResult =
        oauthClient.pollDeviceToken(metadata, deviceCode, Jmap.OAUTH_CLIENT_ID)

    /**
     * The URL the browser is sent to for the authorization-code grant. Sterna's client id and scopes
     * unless the sign-in is at an issuer the user typed, which knows her client id and no other.
     */
    fun authorizationUrl(
        metadata: OAuthMetadata,
        redirectUri: String,
        state: String,
        codeChallenge: String,
        clientId: String = Jmap.OAUTH_CLIENT_ID,
        scope: String = Jmap.OAUTH_SCOPE,
    ): String = buildAuthorizationUrl(
        authorizationEndpoint = metadata.authorizationEndpoint
            ?: throw JmapException("Server has no authorization endpoint"),
        clientId = clientId,
        redirectUri = redirectUri,
        scope = scope,
        state = state,
        codeChallenge = codeChallenge,
    )

    /** Redeem an authorization code, proving PKCE possession with [codeVerifier] (RFC 7636).
     *  [clientId] is the one the authorization URL carried: the server ties the code to it. */
    suspend fun exchangeCode(
        metadata: OAuthMetadata,
        code: String,
        redirectUri: String,
        codeVerifier: String,
        clientId: String = Jmap.OAUTH_CLIENT_ID,
    ): OAuthTokens = oauthClient.exchangeCode(metadata, code, redirectUri, clientId, codeVerifier)

    /** Throws, persisting nothing, if [tokens] don't authenticate against [host]. Returns true when
     *  THIS add created the account, a re-add must land on the existing one and succeed.
     *  [clientId] is the one [tokens] were issued to. It is stored with the account because the
     *  refresh posts it back, and a refresh token is refused to any other client. */
    suspend fun addOAuthAccount(
        host: String,
        email: String,
        metadata: OAuthMetadata,
        tokens: OAuthTokens,
        accountName: String,
        clientId: String = Jmap.OAUTH_CLIENT_ID,
    ): Boolean {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        client.fetchSession(Jmap.sessionUrlFor(host), BearerAuth(tokens.accessToken)).mailAccountId()
            ?: error("This user has no JMAP mail account.")
        // [host] proved the tokens; it does not decide the identity when the app guessed it. The
        // candidate list drops the apex, so a guessed host is `mail.<domain>` where the password
        // path stores `<domain>`, one mailbox added both ways became two accounts (#55). Only the
        // guessed ones: a typed host is stored verbatim by its own button. See oauthHostWasGuessed.
        val guessedHost = oauthHostWasGuessed(host, Jmap.autodiscoverHosts(email))
        val server = if (guessedHost) {
            oauthServerToStore(
                discoverJmapServer(email, password = "", token = tokens.accessToken),
                host,
            )
        } else {
            host
        }
        // Said out loud: the account is written under the host we wanted to improve, so #55 stands.
        if (guessedHost && server == host) {
            logWarn("MailRepository", "OAuth add: nothing autodiscovered for $host; stored as-is (#55)", null)
        }
        // Asked before the store writes: addOAuth refreshes the login already stored under this
        // identity. On both keys this route has written, in the order the store resolves them.
        val created = resolveExistingLoginAmong(
            accountStore.accounts(),
            listOf(
                accountKeyOf(MailProtocol.JMAP, server, "", email),
                accountKeyOf(MailProtocol.JMAP, host, "", email),
            ),
        ) == null
        val id = accountStore.addOAuth(
            server = server,
            username = email,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = metadata.tokenEndpoint,
            clientId = clientId,
            documentHost = host,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = this.host.firstSync(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
        // Only on a real creation. A re-add lands on the login already there, with its shared
        // sub-accounts, and eviction reads the raw discovered list: a session listing the login's
        // own account but not the shares would prune every sub-account and purge five tables (#129).
        if (created) reconcileLinkedAccountsAfterAdd(id)
        return created
    }

    /** Begin the device flow for a built-in OAuth provider (Microsoft, …). */
    suspend fun startProviderDeviceAuth(provider: OAuthProvider): DeviceAuthorization =
        oauthClient.startDeviceAuthorization(provider.metadata, provider.clientId, provider.scope)

    /** Poll a built-in provider's token endpoint once for a pending device authorization. */
    suspend fun pollProviderToken(provider: OAuthProvider, deviceCode: String): DeviceTokenResult =
        oauthClient.pollDeviceToken(provider.metadata, deviceCode, provider.clientId)

    /**
     * Run a provider device flow to completion: start it, hand the code to [onCode], then poll to its deadline.
     */
    suspend fun runProviderDeviceFlow(
        provider: OAuthProvider,
        onCode: (DeviceAuthorization) -> Unit,
    ): Result<OAuthTokens> = runCatching {
        val device = startProviderDeviceAuth(provider)
        onCode(device)
        var interval = device.interval.coerceAtLeast(1).toLong()
        val deadline = System.currentTimeMillis() + device.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000)
            when (val r = pollProviderToken(provider, device.deviceCode)) {
                is DeviceTokenResult.Success -> return@runCatching r.tokens
                DeviceTokenResult.Pending -> Unit
                DeviceTokenResult.SlowDown -> interval += 5
                is DeviceTokenResult.Failed -> throw OAuthDeniedException(r)
            }
        }
        throw OAuthDeniedException(DeviceTokenResult.Failed("expired_token"))
    }

    /**
     * Validate freshly granted OAuth [tokens] against [provider]'s IMAP server with XOAUTH2, then store them.
     */
    suspend fun addOAuthImapAccount(
        provider: OAuthProvider,
        email: String,
        tokens: OAuthTokens,
        accountName: String,
    ): Boolean {
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        // Microsoft IMAP wants the account's exact primary address as the XOAUTH2 user=,
        // not whatever alias the user typed, take it from the signed-in identity (id_token).
        val username = emailFromIdToken(tokens.idToken) ?: email
        val probe = AccountCredentials(
            server = "",
            username = username,
            password = "",
            protocol = MailProtocol.IMAP,
            imap = provider.imap,
            smtp = provider.smtp,
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        try {
            imap.testConnection(probe)
        } finally {
            runCatching { imap.disconnect("") }
        }
        // On the values the store is actually given: the IMAP host is the endpoint an IMAP account
        // is keyed on, and `username` is the address the id_token named, not the typed one.
        val created = resolveExistingLogin(
            accountStore.accounts(),
            accountKeyOf(MailProtocol.IMAP, "", provider.imap.host, username),
        ) == null
        val id = accountStore.addOAuth(
            server = "",
            username = username,
            accountName = accountName,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt,
            tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
            protocol = MailProtocol.IMAP,
            imapHost = provider.imap.host,
            imapPort = provider.imap.port,
            imapSecurity = provider.imap.security,
            smtpHost = provider.smtp.host,
            smtpPort = provider.smtp.port,
            smtpSecurity = provider.smtp.security,
        )
        val credentials = accountStore.credentials(id) ?: error("Account could not be loaded after creation.")
        val meta = host.firstSync(credentials)
        accountStore.saveInboxMeta(meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
        return created
    }

    /** Complete OAuth sign-in for an already-imported (inert) account [accountId] using freshly
     *  granted [tokens]. Validates via IMAP XOAUTH2 first; throws (account left inert) on failure. */
    suspend fun signInImportedOAuth(accountId: String, provider: OAuthProvider, tokens: OAuthTokens) {
        val account = accountStore.account(accountId) ?: error("Account not found.")
        val expiresAt = System.currentTimeMillis() + tokens.expiresIn * 1000
        val username = emailFromIdToken(tokens.idToken) ?: account.username
        val probe = AccountCredentials(
            server = "", username = username, password = "",
            protocol = MailProtocol.IMAP,
            imap = MailEndpoint(account.imapHost, account.imapPort, account.imapSecurity),
            smtp = MailEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity),
            oauth = OAuthCredentials(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken.orEmpty(),
                accessExpiresAtMillis = expiresAt,
                tokenEndpoint = provider.metadata.tokenEndpoint,
                clientId = provider.clientId,
            ),
        )
        try { imap.testConnection(probe) } finally { runCatching { imap.disconnect("") } }
        accountStore.attachOAuth(
            id = accountId, username = username,
            accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(),
            accessExpiresAtMillis = expiresAt, tokenEndpoint = provider.metadata.tokenEndpoint,
            clientId = provider.clientId,
        )
        val credentials = accountStore.credentials(accountId) ?: error("Account could not be loaded.")
        val meta = host.firstSync(credentials)
        accountStore.saveInboxMetaFor(accountId, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount)
    }

    private fun emailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val payload = idToken.split(".").getOrNull(1) ?: return null
            val decoded = android.util.Base64.decode(
                payload,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val obj = Json.parseToJsonElement(String(decoded, Charsets.UTF_8)).jsonObject
            (obj["preferred_username"] ?: obj["email"])?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** #31: several mail accounts per login; #129 probes non-primary ones for admission only. */
    internal suspend fun reconcileLinkedAccounts(
        credentials: AccountCredentials,
        session: JmapSession,
        auth: JmapAuth,
    ) {
        val mailAccountIds = session.mailAccountIds()
        val loginId = accountStore.account(credentials.id)?.loginKey() ?: credentials.id
        if (mailAccountIds.size <= 1 && accountStore.linkedAccounts(loginId).isEmpty()) return
        val discovered = mailAccountIds.map { DiscoveredMailAccount(it, session.accounts[it]?.name.orEmpty()) }
        val probes = discovered.drop(1).associate { candidate ->
            candidate.jmapAccountId to
                runCatching { client.getMailboxes(session, candidate.jmapAccountId, auth) }.rethrowIfCancelled()
        }
        // Read before the store rewrites the list: a pruned record is no longer in it afterwards.
        val linkedBefore = accountStore.linkedAccounts(loginId)
        val pruned = runCatching { accountStore.reconcileLinkedAccounts(loginId, discovered, probes) }
            .getOrDefault(emptyList())
        pruned.forEach { prunedId ->
            // App-layer teardown first (notification baselines); each step best-effort so one
            // failure never leaves the rest of a revoked account behind.
            onAccountPruned?.let { hook -> runCatching { hook(prunedId) } }
            bgScope.launch {
                // One runCatching PER delete: a failure in one table must not leave the
                // remaining tables' rows of a revoked account behind.
                runCatching { emailDao.deleteForAccount(prunedId) }
                runCatching { emailFtsDao.clearAccount(prunedId) }
                runCatching { emailBodyDao.deleteForAccount(prunedId) }
                runCatching { mailboxDao.deleteForAccount(prunedId) }
                runCatching { snoozedDao.deleteForAccount(prunedId) }
                runCatching { imap.forgetPendingExpunges(prunedId) }
                runCatching { serverContactDao.deleteForAccount(prunedId) }
            }
        }
        // A revocation only: a duplicate pruned beside the record it copies is the same mailbox,
        // still on this phone, and its searches were never about an account that left.
        if (revokedLinkedIds(linkedBefore, discovered).any { it in pruned }) {
            bgScope.launch { runCatching { searchHistory?.clear() } }
        }
    }

    /**
     * Brings [credentials]' copy of the server address book up to date; IMAP has none. A bare session,
     * not `MailAccountHost.jmapSession`, which reconciles accounts, rereads folders and identities and
     * replaces the cached session. Throws on a failed read or a removed account, writing nothing.
     */
    suspend fun syncServerContacts(credentials: AccountCredentials): ServerContactsSync {
        if (credentials.protocol == MailProtocol.IMAP) return ServerContactsSync.Imap
        // The session's book is the login's own: a shared or delegated sub-account must not be given it.
        if (accountStore.account(credentials.id)?.isLinked == true) return ServerContactsSync.Linked
        val auth = host.jmapAuth(credentials)
        val session = client.fetchSession(Jmap.sessionUrlFor(credentials.server), auth)
        val stillConfigured = { checkAccountStillConfigured(credentials.id, accountStore.accounts().map { it.id }) }
        return refreshServerContacts(
            accountId = credentials.id,
            dao = serverContactDao,
            readAll = { client.getAllContacts(session, auth).also { stillConfigured() } },
            readChanges = { since -> client.contactChanges(session, since, auth).also { stillConfigured() } },
        ) ?: ServerContactsSync.NoAddressBook(capabilityListed = Jmap.CONTACTS_CAPABILITY in session.capabilities)
    }

    suspend fun serverBook(accountId: String): List<ContactRow> = serverContactDao.book(accountId)

    /** #31: discover linked sub-accounts for the new login [id]; best-effort, except cancellation. */
    suspend fun reconcileLinkedAccountsAfterAdd(id: String) {
        runCatching {
            val credentials = accountStore.credentials(id) ?: return
            val cached = host.cachedJmapSession()
                ?.takeIf { it.credentials.server == credentials.server && it.credentials.username == credentials.username }
                ?: return
            // The cached session's own auth, so the #129 probe costs no token work of its own.
            reconcileLinkedAccounts(credentials, cached.session, cached.auth)
        }.rethrowIfCancelled()
    }

    /**
     * The server-side vacation responder: JMAP `VacationResponse`, or on IMAP the auto-reply kept in
     * the script the filters open, over ManageSieve. A failed ManageSieve dialogue throws, never Unsupported.
     */
    suspend fun loadVacation(credentials: AccountCredentials): VacationState {
        if (credentials.protocol == MailProtocol.IMAP) return loadVacationBySieve(credentials)
        val ctx = host.jmapSession(credentials)
        val response = client.getVacationResponse(ctx.session, ctx.accountId, ctx.auth)
            ?: return VacationState.Unsupported
        return VacationState.Loaded(response)
    }

    /** [read] and [trashFolder] are read on IMAP only: [read] is `VacationState.Loaded.script`, the script the save goes over. */
    suspend fun saveVacation(
        credentials: AccountCredentials,
        vacation: VacationResponse,
        read: ScriptSnapshot?,
        trashFolder: String? = null,
    ): SavedVacation {
        when (credentials.protocol) {
            MailProtocol.IMAP -> {
                val sieveHost = manageSieveHost(credentials)
                    ?: throw IllegalStateException("Automatic replies are not available for this account")
                return saveVacationBySieve(
                    sieveHost, credentials, vacation, trashFolder,
                    read ?: throw IllegalStateException("The auto-reply was not read before this save"),
                )
            }
            MailProtocol.JMAP -> Unit
        }
        val ctx = host.jmapSession(credentials)
        return SavedVacation(client.setVacationResponse(ctx.session, ctx.accountId, ctx.auth, vacation), script = null)
    }

    /** Identities read now from the server and stored (#172). It throws; empty means "none". */
    suspend fun serverIdentities(credentials: AccountCredentials): List<StoredIdentity> {
        if (credentials.protocol == MailProtocol.IMAP) {
            throw IllegalStateException("Identity/get is JMAP-only; an IMAP alias is local")
        }
        val ctx = host.jmapSession(credentials)
        val identities = storedServerIdentities(client.getIdentities(ctx.session, ctx.accountId, ctx.auth))
        accountStore.setServerIdentities(credentials.id, identities)
        return identities
    }

    /** Create one sending identity (`Identity/set`, #172) and return its id; IMAP throws. */
    suspend fun createIdentity(credentials: AccountCredentials, name: String, email: String): String {
        if (credentials.protocol == MailProtocol.IMAP) {
            throw IllegalStateException("Identity/set is JMAP-only; an IMAP alias is local")
        }
        val ctx = host.jmapSession(credentials)
        // Named, not positional: a swap compiles silently and posts the display name as address.
        val created = client.createIdentity(ctx.session, ctx.accountId, name = name, email = email, auth = ctx.auth)
        // #172: `jmapSession` hands back a cached session, so nothing re-reads Identity/get until the
        // next cold start. Re-read here, only once the server has confirmed the creation.
        refreshServerIdentities(ctx.session, ctx.accountId, credentials.id, ctx.auth)
        return created
    }

    /** Server-side quotas (RFC 9425); empty for IMAP, without the capability, and on any error. */
    suspend fun loadQuotas(credentials: AccountCredentials): List<Quota> {
        if (credentials.protocol == MailProtocol.IMAP) return emptyList()
        return runCatching {
            val ctx = host.jmapSession(credentials)
            client.getQuotas(ctx.session, ctx.accountId, ctx.auth)
        }.getOrDefault(emptyList())
    }

    /**
     * What the script [script] says, or without a name the one the filters open, see [chosenScriptName];
     * Unsupported for an IMAP account on OAuth or without an IMAP host, and for JMAP without the capability.
     * A failed ManageSieve read throws.
     */
    suspend fun loadFilterRules(credentials: AccountCredentials, script: String? = null): FilterRulesState {
        if (credentials.protocol == MailProtocol.IMAP) return loadFilterRulesBySieve(credentials, script)
        val ctx = host.jmapSession(credentials)
        if (!ctx.session.capabilities.containsKey(app.sterna.core.jmap.Jmap.SIEVE_CAPABILITY)) {
            return FilterRulesState.Unsupported
        }
        val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
        val opened = chosenScriptName(scripts.jmapListed(), script, serverResponder = true)
            ?.let { name -> scripts.first { it.name == name } }
        return loadedFilterRules(
            scriptName = opened?.name,
            scriptText = opened?.let { jmapScriptText(ctx.session, ctx.accountId, ctx.auth, it.blobId) },
            stopsServerResponder = stopsServerResponder(scripts.jmapListed(), serverResponder = true),
        ).listing(scripts.jmapListed(), script, serverResponder = true)
            .copy(sieveExtensions = ctx.session.sieveExtensions(ctx.accountId))
    }

    /**
     * Make [name] the account's active script, and nothing else: its text is neither read nor written, so a
     * script Sterna cannot cut into blocks is put in service as it stands.
     */
    suspend fun activateScript(credentials: AccountCredentials, name: String) {
        when (credentials.protocol) {
            MailProtocol.IMAP -> {
                val sieveHost = manageSieveHost(credentials)
                    ?: throw IllegalStateException("Server-side filters are not available for this account")
                return sieveSession(sieveHost, credentials) { setActive(name) }
            }
            MailProtocol.JMAP -> Unit
        }
        val ctx = host.jmapSession(credentials)
        val script = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth).firstOrNull { it.name == name }
            ?: throw FilterScriptChangedException()
        client.activateSieveScript(ctx.session, ctx.accountId, script.id, ctx.auth)
    }

    /**
     * Whether the rules of [script], or without a name of the script the filters open, are actually running
     * server-side, and whether the vacation responder runs as a script beside them; null when the read fails.
     */
    suspend fun loadFilterScriptStatus(credentials: AccountCredentials, script: String? = null): FilterScriptStatus? {
        if (credentials.protocol == MailProtocol.IMAP) return loadFilterScriptStatusBySieve(credentials, script)
        return runCatching {
            val ctx = host.jmapSession(credentials)
            if (!ctx.session.capabilities.containsKey(app.sterna.core.jmap.Jmap.SIEVE_CAPABILITY)) {
                return@runCatching FilterScriptStatus()
            }
            val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
            val opened = chosenScriptName(scripts.jmapListed(), script, serverResponder = true)
                ?.let { name -> scripts.first { it.name == name } }
            filterScriptStatus(scripts.jmapListed(), opened?.name, opened?.let { jmapScriptText(ctx.session, ctx.accountId, ctx.auth, it.blobId) })
        }.getOrElseUnlessCancelled { null }
    }

    /**
     * Write [write] once the server still holds what it was built on and has validated it, activating
     * only as [activatesOnSave] decides, and answer the script as written. Throws on refusal, and before
     * any network for an account no transport serves: the screen hiding Save is not the guard.
     */
    suspend fun saveFilterScript(credentials: AccountCredentials, write: FilterScriptWrite): FilterRulesState.Loaded {
        val name = write.name
        val text = write.script.emit()
        when (credentials.protocol) {
            MailProtocol.IMAP -> {
                val sieveHost = manageSieveHost(credentials)
                    ?: throw IllegalStateException("Server-side filters are not available for this account")
                return sieveSession(sieveHost, credentials) {
                    val (scripts, activated) = writeOver(write.read, name, text)
                    write.written(text, scripts, activated, serverResponder = false).copy(sieveExtensions = announced(sieveExtensions))
                }
            }
            MailProtocol.JMAP -> Unit
        }
        val ctx = host.jmapSession(credentials)
        val scripts = client.getSieveScripts(ctx.session, ctx.accountId, ctx.auth)
        val over = scriptWrittenOver(write.read, scripts.jmapListed(), serverResponder = true)
            ?.let { current -> scripts.first { it.name == current } }
        val current = over?.let { jmapScriptText(ctx.session, ctx.accountId, ctx.auth, it.blobId) }
        if (!unchangedSince(write.read, over?.name, current)) throw FilterScriptChangedException()
        val blob = client.uploadBlob(
            ctx.session, ctx.accountId, text.toByteArray(Charsets.UTF_8), "application/sieve", ctx.auth,
        )
        client.validateSieve(ctx.session, ctx.accountId, blob.blobId, ctx.auth)?.let {
            throw IllegalStateException("The server rejected the filters: $it")
        }
        val activated = activatesOnSave(scripts.jmapListed(), serverResponder = true)
        client.saveSieveScript(
            ctx.session, ctx.accountId, name, blob.blobId,
            existingId = scripts.firstOrNull { it.name == name }?.id,
            activate = activated,
            auth = ctx.auth,
        )
        return write.written(text, scripts.jmapListed(), activated, serverResponder = true)
            .copy(sieveExtensions = ctx.session.sieveExtensions(ctx.accountId))
    }

    /** What the server holds once [this] is written as [text] over [scripts], the listing read before the write. */
    private fun FilterScriptWrite.written(
        text: String,
        scripts: List<ListedScript>,
        activated: Boolean,
        serverResponder: Boolean,
    ): FilterRulesState.Loaded = loadedFilterRules(name, text)
        .listing(listedAfterWrite(scripts, name, activated), requested = name.takeIf { read.chosen }, serverResponder)

    /** A ManageSieve server that lists no `SIEVE` capability announced nothing: every feature stays offered. */
    private fun announced(extensions: List<String>): List<String>? = extensions.takeIf { it.isNotEmpty() }

    /** [loadFilterRules] over ManageSieve: one listing, then the script [chosenScriptName] picks. */
    private suspend fun loadFilterRulesBySieve(credentials: AccountCredentials, script: String?): FilterRulesState {
        val sieveHost = manageSieveHost(credentials) ?: return FilterRulesState.Unsupported
        return sieveSession(sieveHost, credentials) {
            val scripts = listScripts().sieveListed()
            val name = chosenScriptName(scripts, script, serverResponder = false)
            loadedFilterRules(scriptName = name, scriptText = name?.let { getScript(it) })
                .listing(scripts, script, serverResponder = false)
                .copy(sieveExtensions = announced(sieveExtensions))
        }
    }

    /** [loadFilterScriptStatus] over ManageSieve, the same facts from one listing; null when the read fails. */
    private suspend fun loadFilterScriptStatusBySieve(credentials: AccountCredentials, script: String?): FilterScriptStatus? {
        val sieveHost = manageSieveHost(credentials) ?: return FilterScriptStatus()
        return runCatching {
            sieveSession(sieveHost, credentials) {
                val scripts = listScripts().sieveListed()
                val name = chosenScriptName(scripts, script, serverResponder = false)
                filterScriptStatus(scripts, name, name?.let { getScript(it) })
            }
        }.getOrElseUnlessCancelled { null }
    }

    /**
     * A ManageSieve write over the script [read], refused if the server no longer holds it. `PUTSCRIPT` then `SETACTIVE`
     * is not atomic: a first activation cut between the two leaves the script stored and inactive; over the active script
     * `PUTSCRIPT` alone puts it in service. A rejection passes through as `ManageSieveException`, text and kind intact.
     * Answers the listing read before the write, and whether [name] was activated.
     */
    private fun SieveScripts.writeOver(read: ScriptSnapshot, name: String, text: String): Pair<List<ListedScript>, Boolean> {
        val scripts = listScripts().sieveListed()
        val over = scriptWrittenOver(read, scripts, serverResponder = false)
        if (!unchangedSince(read, over, over?.let { getScript(it) })) throw FilterScriptChangedException()
        checkScript(text)
        putScript(name, text)
        val activated = activatesOnSave(scripts, serverResponder = false)
        if (activated) setActive(name)
        return scripts to activated
    }

    /** [loadVacation] over ManageSieve: the capability gate, one listing, then the script [openedScriptName] picks. */
    private suspend fun loadVacationBySieve(credentials: AccountCredentials): VacationState {
        val sieveHost = manageSieveHost(credentials) ?: return VacationState.AccountUnsupported
        return sieveSession(sieveHost, credentials) {
            if (!sieveExtensions.map { it.lowercase() }.containsAll(VACATION_SIEVE_EXTENSIONS)) {
                return@sieveSession VacationState.Unsupported
            }
            val name = openedScriptName(listScripts().sieveListed(), serverResponder = false)
            val opened = loadedFilterRules(scriptName = name, scriptText = name?.let { getScript(it) })
            val marker = opened.scriptText?.let { SieveCodec.readVacation(it) } as? VacationMarker.Found
            VacationState.Loaded(
                response = marker?.vacation?.toVacationResponse() ?: VacationResponse(),
                foreignActiveScript = opened.foreignActiveScript,
                script = opened.snapshot,
            )
        }
    }

    /**
     * [saveVacation] over ManageSieve, in one connection: the auto-reply's segment of the opened script
     * rewritten, the rest byte for byte. An unreadable script is replaced whole: the screen has asked first.
     */
    private suspend fun saveVacationBySieve(
        sieveHost: String,
        credentials: AccountCredentials,
        vacation: VacationResponse,
        trashFolder: String?,
        read: ScriptSnapshot,
    ): SavedVacation {
        val written = SieveVacation(
            isEnabled = vacation.isEnabled,
            fromDate = vacation.fromDate,
            toDate = vacation.toDate,
            subject = vacation.subject,
            textBody = vacation.textBody,
            addresses = accountAddresses(accountStore.identities(credentials.id), credentials.username).distinct(),
            trashFolder = trashFolder,
        )
        val opened = loadedFilterRules(scriptName = read.name, scriptText = read.text)
        val text = opened.scriptToEdit.withResponder(written).emit()
        val name = read.name ?: SieveCodec.SCRIPT_NAME
        sieveSession(sieveHost, credentials) { writeOver(read, name, text) }
        return SavedVacation(written.toVacationResponse(), ScriptSnapshot(name, text))
    }

    private suspend fun jmapScriptText(session: JmapSession, accountId: String, auth: JmapAuth, blobId: String): String =
        client.downloadBlob(session, accountId, blobId, "application/sieve", "sterna.siv", auth).toString(Charsets.UTF_8)

    private fun List<JmapSieveScript>.jmapListed() = map { ListedScript(it.name, it.isActive) }

    private fun List<SieveScriptInfo>.sieveListed() = map { ListedScript(it.name, it.isActive) }

    private fun SieveVacation.toVacationResponse() = VacationResponse(
        isEnabled = isEnabled,
        fromDate = fromDate,
        toDate = toDate,
        subject = subject,
        textBody = textBody,
    )

    /** [sieveHost] comes from [manageSieveHost]; the login is the one `ImapMailService` gives the IMAP server. */
    private suspend fun <T> sieveSession(sieveHost: String, credentials: AccountCredentials, block: SieveScripts.() -> T): T =
        sieve.session(sieveHost, MANAGESIEVE_PORT, credentials.username, credentials.password, block)

    /** Best-effort (#32). [accountId] is the JMAP account, [localAccountId] the record. */
    internal suspend fun refreshServerIdentities(
        session: JmapSession,
        accountId: String,
        localAccountId: String,
        auth: JmapAuth,
    ) {
        runCatching {
            val serverIdentities = storedServerIdentities(client.getIdentities(session, accountId, auth))
            accountStore.setServerIdentities(localAccountId, serverIdentities)
        }
    }
}

/** What a dated auto-reply requires of a ManageSieve server; the responder is not offered without all three. */
private val VACATION_SIEVE_EXTENSIONS = listOf("vacation", "date", "relational")

package app.sterna.ui.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.StoredIdentity
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChanged
import app.sterna.core.data.mail.OAuthDeniedException
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.data.pgp.PgpResult
import app.sterna.pgp.PgpProviders
import app.sterna.push.NewMailNotifier
import app.sterna.push.PushController
import app.sterna.ui.connect.oauthFailureMessage
import app.sterna.widget.RecentMailWidgetDraw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs the Accounts list and per-account detail screens in Settings. */
class AccountsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.container.accountStore
    private val storage = application.container.storageRepository
    private val mail = application.container.mailRepository
    private val pgp = application.container.pgpEngine
    private val settings = application.container.settingsRepository

    /** The "Separator line above the signature" setting (#90), so the identity editor's signature
     *  preview shows what the composer will actually write — with the "-- " line or without it.
     *  Read here rather than in the composable: the preview must never claim a shape the composer
     *  does not produce. */
    val signatureDelimiter: StateFlow<Boolean> = settings.signatureDelimiter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Live from the store, not a snapshot refreshed after this screen's own edits: the same
     *  screen also has to follow writes it did not make — discovery adding or pruning a shared
     *  account under a login (issue #31) changes what "Shared accounts: …" must say. */
    val accounts: StateFlow<List<StoredAccount>> = store.accountsFlow

    private val _currentId = MutableStateFlow(store.currentId())
    val currentId = _currentId.asStateFlow()

    /** Imported accounts still awaiting their one-time sign-in (inert, not yet dismissed). Reactive:
     *  derived from [accounts] so signing one in or dismissing it drops it from the Backup list. */
    val pendingImportAccounts: StateFlow<List<StoredAccount>> = accounts
        .map { list -> list.filter { it.importPending && store.credentials(it.id) == null } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.pendingImportAccounts())

    /** Whether an account has a stored credential (false == inert, awaiting sign-in). */
    fun isSignedIn(id: String): Boolean = store.credentials(id) != null

    /** Swipe-dismiss: disconnect and REMOVE the imported account entirely (it leaves the account
     *  list; the user can re-import it later). Undoable via [restoreImport]. */
    fun dismissImport(id: String) {
        store.remove(id)
        refresh()
    }

    /** Undo a dismiss: re-add the removed account, back on the "accounts to sign in" list. */
    fun restoreImport(account: StoredAccount) {
        store.readdImportedAccount(account)
        refresh()
    }

    /** Cached-message count for the account detail screen currently open. */
    private val _cacheCount = MutableStateFlow(0)
    val cacheCount = _cacheCount.asStateFlow()

    /** "Test connection" result on the account detail screen. */
    sealed interface ConnTest {
        data object Idle : ConnTest
        data object Testing : ConnTest
        data object Ok : ConnTest
        data class Failed(val message: String) : ConnTest
    }

    private val _connTest = MutableStateFlow<ConnTest>(ConnTest.Idle)
    val connTest = _connTest.asStateFlow()

    fun clearConnTest() { _connTest.value = ConnTest.Idle }

    /**
     * Try the (possibly edited) server settings without saving. A blank password
     * falls back to the stored one, so testing after only changing the host works.
     */
    fun testConnection(
        accountId: String,
        server: String,
        username: String,
        password: String,
        isImap: Boolean,
        imapHost: String,
        imapPort: Int?,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int?,
        smtpSecurity: ConnectionSecurity,
    ) {
        _connTest.value = ConnTest.Testing
        viewModelScope.launch {
            val stored = store.credentials(accountId)
            val pw = password.ifBlank { stored?.password.orEmpty() }
            val credentials = AccountCredentials(
                server = server.trim(),
                username = username.trim(),
                password = pw,
                id = accountId,
                protocol = if (isImap) MailProtocol.IMAP else MailProtocol.JMAP,
                imap = if (isImap) MailEndpoint(imapHost.trim(), imapPort ?: 0, imapSecurity) else null,
                smtp = if (isImap) MailEndpoint(smtpHost.trim(), smtpPort ?: 0, smtpSecurity) else null,
                // Keep the stored auth mode so API-token accounts test with Bearer, not Basic.
                authType = store.account(accountId)?.authType ?: AuthType.BASIC,
                // Carry the stored OAuth tokens: without them an OAuth account tests with an empty
                // password and always fails, a false negative (audit A5).
                oauth = stored?.oauth,
            )
            _connTest.value = mail.testConnection(credentials).fold(
                onSuccess = { ConnTest.Ok },
                onFailure = { ConnTest.Failed(it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    /** Re-read the selected account after any change ([accounts] itself follows the store). */
    fun refresh() {
        _currentId.value = store.currentId()
    }

    fun account(id: String): StoredAccount? = store.account(id)

    /** Whether this account is watched for new mail (the current account, or push-for-all is on).
     *  An unwatched account's notifications toggle is inert, so the UI says so under it (issue A8);
     *  the toggle itself stays live, since turning it on is the other half of the fix. */
    fun isWatched(id: String): Boolean =
        PushController.isWatched(id, store.currentId(), store.pushAllAccounts())

    fun syncWindow(id: String): SyncWindow = store.syncWindow(id)

    /**
     * Write the account's "Messages to sync" window, and — when it actually changed — drop that
     * account's sync cursors, so the next refresh re-queries the folder in full and applies the
     * new window. Without the drop the setting is inert on any folder already synced: the delta
     * branch of a sync never sends the window and never fetches backwards, so the only way the
     * user could apply a wider window was Storage → Clear account cache.
     *
     * Nothing is fetched here. Forcing a sync from the settings screen would need a cross-screen
     * trigger to gain a few seconds; the next pull, push, folder change or cold start applies it.
     */
    fun setSyncWindow(id: String, window: SyncWindow) {
        val changed = syncWindowChanged(store.syncWindow(id), window)
        store.setSyncWindow(id, window)
        if (changed) mail.dropSyncCursors(id)
        refresh()
    }

    /**
     * Set the account's accent colour (ARGB), or null for auto — and redraw the home screen, which
     * is the only place that would otherwise keep the old one.
     *
     * Since this batch the latest-messages widget marks every row with the colour of the account it
     * came from (`RecentMailWidgetDraw.read` hands `RecentMailWidgetContent.accountColors` in). The
     * home screen is outside this window and re-reads nothing on its own: `updatePeriodMillis="0"`
     * means the system asks for a draw at placement, at boot and after an app update, and at no
     * other time. So a cell already drawn keeps the colour it was drawn with.
     *
     * ⛔ AND THE ARRIVAL TRIGGER DOES NOT COVER IT, which is the whole reason these two lines are
     * here. `RecentMailWidgetPush.redraws` collects `MailRepository.observeRecentUnifiedInbox`,
     * i.e. `UnifiedInbox.recentRows`, whose key is `map(::scopes).distinctUntilChanged()` over the
     * stored accounts: a colour changes no (account, inbox) pair, so nothing re-subscribes and
     * nothing is emitted — deliberately, since every `saveAccounts` (an OAuth token refreshed,
     * identities discovered) would otherwise rebuild an observed query. The `distinctUntilChanged`
     * in `redraws` would swallow it in any case: it compares a `RecentInbox`, which carries rows
     * and `configured` and no colour at all. Without the call below, the dots stay the old colour
     * until mail happens to ARRIVE — days on a quiet mailbox — and the cell would be showing a
     * colour the app no longer holds, with nothing on screen to say so.
     *
     * ⛔ `forget()` BEFORE `refresh()`, never the other way round, exactly as at the two settings
     * writes. The row factory falls back to the last snapshot it managed to read when its own read
     * times out (`RecentMailWidgetDraw.forget`), and that snapshot was projected under the OLD
     * colour: a slow read right after this write would repaint the very dots the reader has just
     * replaced.
     */
    fun setColor(id: String, color: Int?) {
        store.setColor(id, color)
        refresh()
        viewModelScope.launch {
            RecentMailWidgetDraw.forget()
            RecentMailWidgetDraw.refresh(getApplication())
        }
    }

    /** Enable/disable new-mail notifications for an account; re-arm push to apply. */
    fun setNotificationsEnabled(id: String, enabled: Boolean) {
        store.setNotificationsEnabled(id, enabled)
        if (enabled) {
            // The account's baselines froze while it was excluded from every diff pass;
            // drop them so re-enabling reseeds silently instead of bursting weeks of mail.
            NewMailNotifier.clear(getApplication(), id)
        }
        refresh()
        PushController.apply(getApplication(), userInitiated = true)
    }

    /**
     * Turn the app's Sent-folder copy on/off for an account (IMAP only). Written at the toggle, not
     * at Save: this screen's Save button belongs to the text fields, and a switch that waited for it
     * would lose the choice to a back gesture.
     */
    fun setUploadSentCopy(id: String, enabled: Boolean) {
        store.setUploadSentCopy(id, enabled)
        refresh()
    }

    /** Load this account's cached-message count into [cacheCount]. */
    fun loadCacheCount(id: String) {
        viewModelScope.launch { _cacheCount.value = storage.accountMessageCount(id) }
    }

    /** Clear just this account's cached mail, then refresh the displayed count. */
    fun clearAccountCache(id: String) {
        viewModelScope.launch {
            storage.clearAccountCache(id)
            mail.resetSyncState()
            _cacheCount.value = storage.accountMessageCount(id)
        }
    }

    fun switchTo(id: String) {
        store.setCurrent(id)
        refresh()
    }

    /**
     * Persist edits. A blank [password] keeps the existing one.
     *
     * THIS PARAMETER LIST IS PUBLISHED TO THE USER. The account screen states, just above its Save
     * button, what that button writes — `R.string.settings_save_scope`, in nine languages — and
     * that sentence is answerable to the parameters below.
     *
     * It is NOT a one-to-one mapping of them, and must not be read as one. The caption speaks in
     * the screen's vocabulary, the signature in the store's:
     *  - "the account name" is the field labelled **Display name** (`settings_display_name_label`),
     *    not a string called account name anywhere the user can see;
     *  - "the server settings" covers [server] for JMAP and the eight [imapHost] … [smtpSecurity]
     *    parameters for IMAP, which is why the caption says it in the plural;
     *  - "the credentials" is [username] AND [password] (a password or an API token) — the two
     *    whose silent loss breaks the account, so they are never dropped from the sentence;
     *  - "the identities" covers [identities] and [defaultIdentityId], and ALSO [signature]:
     *    the account-level signature is not edited anywhere of its own, it is derived from the
     *    first identity's (`AccountEditorSave.kt:46`), and every field feeding it lives in the
     *    Identities section of the screen. That is the one parameter the caption does not name,
     *    deliberately — naming it would describe the store, not the screen.
     *
     * The same sentence also tells the user that everything else on this screen (colour,
     * notifications, sync window, PGP) is saved the moment they change it.
     *
     * So adding a parameter here, or dropping one, can make a sentence false in nine languages at
     * once, silently: no test reads a translated sentence for meaning. Before changing this
     * signature, ask which of the four groups above the new field falls into, and if it falls into
     * none, re-read `settings_save_scope` in all nine `strings.xml` under `app/src/main/res`.
     * `SaveScopeCaptionTest` holds the caption's placement, its one forbidden claim and the words
     * every translation must still contain, but it cannot notice that the enumeration went stale.
     */
    fun save(
        id: String,
        accountName: String,
        server: String,
        username: String,
        password: String,
        signature: String? = null,
        identities: List<StoredIdentity>? = null,
        defaultIdentityId: String? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        imapSecurity: ConnectionSecurity? = null,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        smtpSecurity: ConnectionSecurity? = null,
    ) {
        store.updateAccount(
            id, server = server, username = username, accountName = accountName, signature = signature,
            imapHost = imapHost, imapPort = imapPort, imapSecurity = imapSecurity,
            smtpHost = smtpHost, smtpPort = smtpPort, smtpSecurity = smtpSecurity,
        )
        if (identities != null) store.setIdentities(id, identities)
        store.setDefaultIdentity(id, defaultIdentityId)
        if (password.isNotBlank()) {
            store.updatePassword(id, password)
            // Saving a password signs in an inert BASIC import — take it off the pending list.
            store.setImportPending(id, false)
        }
        refresh()
    }

    // --- Sign in an inert (imported) account via Microsoft OAuth device flow ---

    /** Device-flow sign-in state for the account detail screen of an inert OAuth account. */
    sealed interface AccountSignIn {
        data object Idle : AccountSignIn
        data object Starting : AccountSignIn
        data class Approval(
            val userCode: String,
            val verificationUri: String,
            val verificationUriComplete: String?,
        ) : AccountSignIn
        data object Connecting : AccountSignIn
        data class Failed(val message: String, val offerAppPassword: Boolean) : AccountSignIn
        data object Success : AccountSignIn
    }

    private val _accountSignIn = MutableStateFlow<AccountSignIn>(AccountSignIn.Idle)
    val accountSignIn: StateFlow<AccountSignIn> = _accountSignIn.asStateFlow()
    private var accountSignInJob: Job? = null

    /** Launch the Microsoft device flow for an inert OAuth [accountId] and drive it to completion. */
    fun startAccountOAuth(accountId: String) {
        val account = store.account(accountId) ?: return
        val provider = OAuthProvider.forImapHost(account.imapHost) ?: return
        if (_accountSignIn.value != AccountSignIn.Idle && _accountSignIn.value !is AccountSignIn.Failed) return
        _accountSignIn.value = AccountSignIn.Starting
        accountSignInJob = viewModelScope.launch {
            val result = mail.runProviderDeviceFlow(provider) { device ->
                _accountSignIn.value = AccountSignIn.Approval(
                    device.userCode, device.verificationUri, device.verificationUriComplete,
                )
            }
            result.fold(
                onSuccess = { tokens ->
                    _accountSignIn.value = AccountSignIn.Connecting
                    runCatching { mail.signInImportedOAuth(accountId, provider, tokens) }.fold(
                        onSuccess = {
                            store.setImportPending(accountId, false)
                            refresh()
                            _accountSignIn.value = AccountSignIn.Success
                        },
                        onFailure = { e ->
                            _accountSignIn.value = AccountSignIn.Failed(
                                e.message ?: "sign-in failed", offerAppPassword = true,
                            )
                        },
                    )
                },
                onFailure = { e ->
                    val msg = if (e is OAuthDeniedException) {
                        oauthFailureMessage(getApplication(), e.failure)
                    } else {
                        e.message ?: "sign-in failed"
                    }
                    _accountSignIn.value = AccountSignIn.Failed(msg, offerAppPassword = true)
                },
            )
        }
    }

    /** Cancel an in-progress device flow; the account stays inert and retryable. */
    fun cancelAccountOAuth() {
        accountSignInJob?.cancel()
        accountSignInJob = null
        _accountSignIn.value = AccountSignIn.Idle
    }

    /** Reset sign-in state (e.g. on entering/leaving the account detail screen). */
    fun resetAccountSignIn() { _accountSignIn.value = AccountSignIn.Idle }

    /** OAuth→app-password fallback: drop OAuth material so a password field signs the account in. */
    fun switchAccountToAppPassword(accountId: String) {
        accountSignInJob?.cancel()
        accountSignInJob = null
        store.convertToBasicAuth(accountId)
        refresh()
        _accountSignIn.value = AccountSignIn.Idle
    }

    // --- OpenPGP (OpenKeychain) ---

    /** Whether an OpenPGP provider is installed and bindable. */
    private val _pgpAvailable = MutableStateFlow(false)
    val pgpAvailable = _pgpAvailable.asStateFlow()

    /** Sign-key chooser round-trip state for the account detail screen. */
    sealed interface PgpSetup {
        data object Idle : PgpSetup
        data class NeedsInteraction(val pendingIntent: PendingIntent) : PgpSetup
        data class Failed(val message: String?) : PgpSetup
    }

    private val _pgpSetup = MutableStateFlow<PgpSetup>(PgpSetup.Idle)
    val pgpSetup = _pgpSetup.asStateFlow()

    /** The OpenPGP apps installed right now. Refreshed on entering the screen, never cached for
     *  the process lifetime: providers are installed and removed while the app lives. */
    private val _pgpProviders = MutableStateFlow<List<String>>(emptyList())
    val pgpProviders = _pgpProviders.asStateFlow()

    /** The one Sterna will actually bind — the stored choice while it is still installed, the
     *  default otherwise ([PgpProviders.resolve]). It is what the row must show: naming the stored
     *  choice instead would keep printing an app that was uninstalled. */
    private val _pgpProviderInUse = MutableStateFlow<String?>(null)
    val pgpProviderInUse = _pgpProviderInUse.asStateFlow()

    /**
     * ⛔ Both values are computed FIRST and published together, with no suspension between the two
     * assignments. Publishing the list before resolving the choice opens a frame where the row is
     * drawn (two providers) with nothing in use — and a tap in that frame used to read "in use =
     * nothing", which is not a provider anyone can be switching away from.
     *
     * ⛔ The package-manager query runs on IO: `queryIntentServices` is a binder round trip to
     * `system_server`, and `viewModelScope` is the main thread.
     *
     * ⭐ The last line writes down the provider in use when nothing was written down yet, so a
     * device with a single OpenPGP app does not silently change provider the day a second one is
     * installed ([PgpProviders.providerToPin]). It settles after one pass — the second visit finds
     * a stored value and writes nothing — and it clears no signing key: the provider it records is
     * the one already in use, which is not a switch of keyring.
     */
    fun refreshPgpAvailable() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }
            val stored = settings.pgpProvider.first()
            val inUse = PgpProviders.resolve(installed, stored)
            _pgpProviders.value = installed
            _pgpProviderInUse.value = inUse
            _pgpAvailable.value = pgp.isAvailable()
            PgpProviders.providerToPin(stored, inUse)?.let { settings.setPgpProvider(it) }
        }
    }

    /**
     * Persist [packageName] as the OpenPGP app to use, and — only when that is a real switch of
     * keyring ([PgpProviders.switchErasesKeys]) — drop every account's signing key.
     *
     * ⛔ What is in use is re-read HERE, from storage, never taken from [_pgpProviderInUse]: that
     * one is screen state, and a tap can land before it holds anything. Reading it would then say
     * "nothing in use", and the keys of every account would be destroyed for a choice that changed
     * nothing.
     *
     * ⭐ The choice is written BEFORE the keys are cleared (WYSIWYG): between the two writes what
     * the screen shows must already be what the app uses, never the reverse.
     */
    fun setPgpProvider(packageName: String) {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { PgpProviders.installed(getApplication()) }
            val inUse = PgpProviders.resolve(installed, settings.pgpProvider.first())
            settings.setPgpProvider(packageName)
            _pgpProviderInUse.value = packageName
            if (PgpProviders.switchErasesKeys(inUse, packageName)) {
                store.clearPgpSignKeys()
                refresh()
            }
            _pgpAvailable.value = pgp.isAvailable()
        }
    }

    fun clearPgpSetup() { _pgpSetup.value = PgpSetup.Idle }

    /**
     * Open the provider's sign-key chooser for this account (hinted with its
     * address). On success the key is persisted and PGP enabled; a
     * [PgpSetup.NeedsInteraction] state asks the UI to run the pending intent
     * and call back with its result Intent.
     */
    fun choosePgpKey(accountId: String, interactionResult: Intent? = null) {
        val account = store.account(accountId) ?: return
        viewModelScope.launch {
            when (val result = pgp.getSignKeyId(account.username, interactionResult)) {
                is PgpResult.Success -> {
                    store.setPgp(
                        accountId,
                        enabled = true,
                        signKeyId = result.value,
                        encryptByDefault = account.pgpEncryptByDefault,
                    )
                    refresh()
                    _pgpSetup.value = PgpSetup.Idle
                }
                is PgpResult.UserInteractionRequired ->
                    _pgpSetup.value = PgpSetup.NeedsInteraction(result.pendingIntent)
                is PgpResult.Error -> _pgpSetup.value = PgpSetup.Failed(result.message)
                PgpResult.NotAvailable -> _pgpSetup.value = PgpSetup.Failed(null)
            }
        }
    }

    /** Toggle PGP off (keeps the chosen key), or update encrypt-by-default. */
    fun setPgp(accountId: String, enabled: Boolean, encryptByDefault: Boolean) {
        val account = store.account(accountId) ?: return
        store.setPgp(accountId, enabled, account.pgpSignKeyId, encryptByDefault)
        refresh()
    }

    /**
     * Sign out and purge that account's cached mail + the attachment cache. Signing out a login
     * cascades to the JMAP sub-accounts linked to it (issue #31): they share its credential, so
     * they cannot outlive it — each has its cache, notification baseline and push torn down too.
     */
    fun signOut(id: String) {
        val app = getApplication<Application>()
        // Everything this sign-out will remove, resolved BEFORE removal so UnifiedPush teardown
        // (which needs the credentials) and the cache purge can run per removed account.
        val target = store.account(id)
        val toRemove = buildList {
            add(id)
            if (target != null && !target.isLinked) addAll(store.linkedAccounts(id).map { it.id })
        }.distinct()
        store.allCredentials().filter { it.id in toRemove }.forEach {
            app.container.unifiedPushManager.teardown(it)
        }
        // ⛔ THIS LINE IS WHAT STOPS THE SYNC, and it must stay above the purge below. Nothing here
        // cancels a sync in flight — it lives in the list screen's scope, not this one — so what
        // ends it is the account leaving the store: every page a walk writes is checked against
        // this list first (`checkAccountStillConfigured`), and the walk unwinds on the first page
        // that follows the removal. Purging first and removing afterwards would leave that window
        // wide open again, and the orphan sweep at the end of this coroutine would run BEFORE the
        // pages it is meant to collect — which is exactly how #121's ghosts came back.
        val removed = store.removeCascading(id).ifEmpty { toRemove }
        removed.forEach { NewMailNotifier.clear(app, it) }
        refresh()
        mail.resetSyncState()
        viewModelScope.launch {
            removed.forEach {
                mail.disconnectImap(it)
                storage.purgeAccount(it)
            }
            // Then anything else left by an account that is no longer configured (Codeberg
            // #121): the per-id purges above only cover what THIS sign-out removed, and a row
            // whose account went by any other path is exactly the ghost the unified list used to
            // draw unlabelled. Sweeps nothing while the store lists no account at all.
            storage.purgeOrphanedAccounts { store.accounts().map { it.id } }
        }
        PushController.apply(app, userInitiated = true)
    }
}

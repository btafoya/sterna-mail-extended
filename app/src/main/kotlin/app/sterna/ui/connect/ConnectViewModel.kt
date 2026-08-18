package app.sterna.ui.connect

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.account.AccountCredentials
import app.sterna.core.data.account.AuthType
import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.accountKeyOf
import app.sterna.core.data.account.resolveExistingLogin
import app.sterna.core.data.autoconfig.MailAutoconfigResult
import app.sterna.core.data.autoconfig.discoverMailAutoconfig
import app.sterna.core.data.mail.MailRepository
import app.sterna.core.data.mail.OAuthDeniedException
import app.sterna.core.imap.ImapException
import app.sterna.core.data.mail.OAuthProvider
import app.sterna.core.jmap.BearerAuth
import app.sterna.core.jmap.DeviceAuthorization
import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthMetadata
import app.sterna.net.hasUsableNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the connect/account-setup screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Connecting : ConnectState
    data object Discovering : ConnectState
    data object Connected : ConnectState
    /** Autodiscovery found no server; the user must enter it manually. */
    data object NeedsServer : ConnectState
    /** Device flow started: show the user code and wait for browser approval. */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
    ) : ConnectState
    /** Authorization-code flow started: the browser must be sent to [authorizationUrl] (#55). */
    data class AwaitingBrowser(val authorizationUrl: String) : ConnectState
    data class Error(val message: String) : ConnectState
}

/** True when a JMAP password sign-in is aimed at Fastmail — their endpoint only accepts API
 *  tokens (#54): the address is @fastmail.com/.fm, or the server points at api.fastmail.com. */
internal fun isFastmailTarget(email: String, server: String): Boolean {
    val domain = email.trim().substringAfterLast('@', "").lowercase()
    return domain == "fastmail.com" || domain == "fastmail.fm" ||
        server.contains("api.fastmail.com", ignoreCase = true)
}

/** The account an add resolved to: its id, and whether this add is what created it. */
internal data class AddedAccount(val id: String, val created: Boolean)

/**
 * WHAT AN ADD SAYS WHEN IT IS OVER, as a pure function — null for "say nothing".
 *
 * An add that resolved onto an account already stored under the same identity refreshes it in
 * place (`AccountStore.add` / `AccountStore.addOAuth`) instead of minting a second one. Two routes
 * then said "Account added" over an account nobody added, and four said nothing at all: the state
 * on screen has to be true about what just happened, so a re-add says it was a re-add.
 *
 * ⛔ Written here rather than as an `if` in the six routes, and that is the point. No test in this
 * module can build `ConnectViewModel`, `OAuthCodeSignIn` or `OutlookSignIn` (an Application, a
 * container, a repository), so a branch written in a route is held by NOTHING — the two resources
 * could be swapped and the whole suite would stay green. Same reasoning, same shape, as
 * [oauthRanOutMessage].
 *
 * [createdRes] is what THIS route says when it really did create an account, and null means "this
 * route says nothing on a create" — the four that were silent stay silent, no noise is added.
 */
@StringRes
internal fun accountAddedToast(created: Boolean, @StringRes createdRes: Int?): Int? =
    if (created) createdRes else R.string.connect_account_refreshed

/**
 * Whether an IMAP sign-in failure means the server LOOKED AT the credentials and said no —
 * the one reading that may tell the user "wrong email or password".
 *
 * A [responseCode] (RFC 5530) is the server's own typed verdict and PRIMES the message text:
 * only the credentials family — `AUTHENTICATIONFAILED`, `AUTHORIZATIONFAILED`, `EXPIRED` —
 * blames the password. `[UNAVAILABLE]` during AUTHENTICATE is a server down, not a password
 * wrong, however much the message talks about authentication.
 *
 * Without a code (servers predating RFC 5530), the historical fallback: the redacted command
 * verb in the message — `LOGIN`/`AUTHENTICATE` — unchanged in behaviour.
 */
internal fun imapAuthRejected(responseCode: String?, message: String): Boolean =
    if (responseCode != null) {
        responseCode in CREDENTIAL_RESPONSE_CODES
    } else {
        message.contains("LOGIN", ignoreCase = true) ||
            message.contains("AUTHENTICATE", ignoreCase = true)
    }

/** The RFC 5530 codes that mean "the credentials themselves were refused" — and no other. */
private val CREDENTIAL_RESPONSE_CODES = setOf("AUTHENTICATIONFAILED", "AUTHORIZATIONFAILED", "EXPIRED")

/**
 * What an add ended on: the account's id, and the priming failure it survived, if any.
 *
 * [primeFailure] is never a reason to undo the add — see [addAccountThenPrime]. It is carried out
 * so the screen can log it (and so a test can read it) instead of it vanishing silently.
 */
internal data class PrimedAccount(val id: String, val primeFailure: Throwable? = null)

/**
 * Add an account in the one order that cannot write mail under an empty account id (#121).
 *
 * Every add path used to build credentials with no id yet (`AccountCredentials.id` defaults to
 * `""`), hand them to the repository to "validate and prime the cache", and only then create the
 * account. The priming write is account-scoped, so a full page of inbox rows landed under
 * `accountId = ""` on every single account added — rows no account owns, that nothing ever
 * refreshes and no label can name.
 *
 * So the steps are separated and ordered here, once, for all three paths:
 *  1. [validate] proves the credentials **while writing nothing** (a session fetch / an IMAP
 *     login), which is what keeps the rule the flow was written around — a mistyped password
 *     must never leave an account behind;
 *  2. [persist] creates the account (or finds the one being re-authenticated) so an id exists;
 *  3. [prime] fills the cache with credentials **this function stamps** with that id — callers
 *     never build them, so no path can pass a blank one, and a fourth path added later inherits
 *     the guarantee by construction.
 *
 * ⛔ A FAILED PRIMING KEEPS THE ACCOUNT. Step 1 is what protects the user from a half-add: nothing
 * is persisted until the credentials are proven, so a mistyped password still leaves nothing
 * behind. Once they ARE proven, taking the account back out on a network hiccup costs more than it
 * saves:
 *  - it throws away credentials the server just accepted, over a transient failure;
 *  - `refresh()` persists the folder list before it rethrows, so removing the account afterwards
 *    leaves mailbox rows under an id no account owns — the very class of orphan #121 closes;
 *  - `AccountStore.remove()` moves the current account to the first remaining one whenever the
 *    removed id is the current one, and an add always makes the new account current. Undoing a
 *    fourth add therefore silently moved the user off the account she was reading;
 *  - the OAuth add path (`MailRepository.addOAuthAccount`) has always kept the account in this
 *    exact situation, and process death produces the same "account added, inbox not loaded yet"
 *    state anyway — which the first refresh repairs on its own, showing nothing.
 *
 * So the account stays and its cache fills on the next refresh; the caller gets the failure back in
 * [PrimedAccount.primeFailure] to log. Cancellation is not a failure: it unwinds untouched, so
 * leaving the screen mid-priming cannot be reported as an error.
 */
internal suspend fun addAccountThenPrime(
    probe: AccountCredentials,
    validate: suspend (AccountCredentials) -> Unit,
    persist: suspend () -> AddedAccount,
    prime: suspend (AccountCredentials) -> Unit,
): PrimedAccount {
    validate(probe)
    val account = persist()
    // The belt inside the flow: an id-less account cannot be primed, it can only be a bug.
    check(account.id.isNotBlank()) { "Refusing to prime the cache under a blank account id." }
    return try {
        prime(probe.copy(id = account.id))
        PrimedAccount(account.id)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (t: Throwable) {
        PrimedAccount(account.id, primeFailure = t)
    }
}

class ConnectViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    /**
     * The context the offline guard of every add route reads its connectivity from — the app's one
     * connectivity check ([hasUsableNetwork]), the same one that picks "Sending…" over "queued" at
     * send time. Offline, no route on this screen can succeed: the guard says so at once instead of
     * letting the user watch a spinner run out the DNS, connect and read timeouts of every
     * candidate host first.
     */
    private val app: Application get() = getApplication()

    private val _state = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    private val _importSignIn = MutableStateFlow<ImportSignIn>(ImportSignIn.None)
    /** Drives the per-account password prompt shown after a settings import (see [beginImportSignIn]). */
    val importSignIn: StateFlow<ImportSignIn> = _importSignIn.asStateFlow()

    private var oauthJob: Job? = null
    private var importOAuthJob: Job? = null

    // ---- IMAP/SMTP settings discovery (the autoconfig cascade, under today's form) ----

    private var imapDiscoveryJob: Job? = null

    /** The address the cascade last set out for — a job still running, or a verdict rendered.
     *  Either way the same address is not probed twice; a different one starts over. */
    private var imapDiscoveryEmail: String? = null

    private val _imapDiscovery = MutableStateFlow<MailAutoconfigResult.Found?>(null)

    /** The one discovered configuration awaiting the screen. Consumed once
     *  ([consumeImapDiscovery]): a field the user then clears must not refill itself. */
    val imapDiscovery: StateFlow<MailAutoconfigResult.Found?> = _imapDiscovery.asStateFlow()

    private val _discovery = MutableStateFlow<DiscoveryVerdict?>(null)

    /**
     * The DURABLE verdict of the last probe, kept BESIDE the one-shot [imapDiscovery] and never in
     * place of it — the two answer different questions:
     *
     *  - [imapDiscovery] is a signal to WRITE the fields, and is consumed the moment they are
     *    written, so a host the user then clears by hand does not refill itself;
     *  - this one is what the WALK reads ([stepToRender], [addressAdvance], [offersManualSetup]).
     *    Consumed, it would send the credentials step back to the address step seconds after
     *    reaching it, and again on every rotation, throwing away the password being typed.
     *
     * It is never nulled: a later probe replaces it with its own verdict, address included, and
     * [verdictFor] is what decides whether it still answers for the address on screen. It does NOT
     * survive process death (this ViewModel has no `SavedStateHandle`) — that is deliberate, and
     * [stepToRender] is where the walk copes with it.
     */
    internal val discovery: StateFlow<DiscoveryVerdict?> = _discovery.asStateFlow()

    private val _oauthDiscovery = MutableStateFlow<OAuthDiscovery?>(null)

    /**
     * How the address' server says this account signs in — the answer the credentials step asks
     * [credentialsAsk] about, and the one the hand-over reuses instead of asking twice.
     *
     * Kept beside [discovery] rather than inside it: the two probes answer different questions and
     * a refusal to probe ([verdictAfterRefusal]) has nothing to say about OAuth. Like [discovery]
     * it is never nulled and never survives process death — after which the walk falls back to the
     * address step and both probes run again.
     */
    internal val oauthDiscovery: StateFlow<OAuthDiscovery?> = _oauthDiscovery.asStateFlow()

    /**
     * Run the autoconfig cascade for [email] in the background, holding what it finds for the
     * screen to write into the VISIBLE host/port/security fields, and its whole verdict for the
     * walk. Whether it may set out at all is [shouldDiscoverImapSettings]'s call — executed by a
     * test — given the [step] the screen is really drawing (the cascade may run before any protocol
     * is chosen, but only there) and the one Android-bound fact (connectivity) computed here:
     * offline it does not start, and records nothing either — what says so to the user is the
     * address step's own button ([advanceFromAddress]). Leaving the screen kills the ViewModel and
     * the job with it.
     *
     * ⛔ [MailAutoconfigResult.NotFound] still changes nothing the user can see — no state, no
     * spinner, no error — but it is no longer dropped on the floor: "this domain publishes nothing"
     * and "the probe has not finished" are the two answers the walk's address step chooses between,
     * and they used to be the same absence of a value.
     */
    internal fun discoverImapSettings(
        step: ConnectStep,
        route: ConnectRoute,
        preset: PresetForm,
        email: String,
    ) {
        // ⛔ ONE read of the link, for both decisions below. Read twice, the guard can refuse for
        // want of a network and the refusal be recorded as though the network were there — the
        // fabricated NotFound this volet exists to remove, on a window nothing can reproduce.
        val online = hasUsableNetwork(app)
        if (!shouldDiscoverImapSettings(step, route, preset, email, online = online)) {
            // A probe that never sets out still has to answer the walk for the refusals the user
            // can act on. What it records — and, crucially, what it must NOT record when the
            // refusal is the missing network itself — is [verdictAfterRefusal]'s call.
            _discovery.value = verdictAfterRefusal(_discovery.value, email, online = online)
            return
        }
        val address = email.trim()
        if (address == imapDiscoveryEmail) return
        imapDiscoveryJob?.cancel()
        imapDiscoveryEmail = address
        imapDiscoveryJob = viewModelScope.launch {
            // ⛔ No runCatching around the cascade: it owns its failures (they become NotFound
            // inside) and RETHROWS cancellation, which must keep unwinding through here — a
            // cancelled probe is the user leaving, not a verdict.
            //
            // ⛔ The two probes run CONCURRENTLY, each under its own budget: chained they would
            // cost this step both budgets — 20 s instead of 10 — for everyone, including the
            // majority whose server has no OAuth at all. [probeAddressTogether] is where that is
            // executed by a test on a virtual clock; nothing here can be made to prove it.
            val probe = probeAddressTogether(
                settings = { discoverMailAutoconfig(address) },
                oauth = { probeOAuthFor(address) },
            )
            val result = probe.settings
            // ⛔ The OAuth answer BEFORE the verdict the walk steps on: the credentials step is
            // drawn the instant _discovery lands, and drawn without this it would flash the
            // password field of a server that wants a browser.
            _oauthDiscovery.value = probe.oauth
            // The durable copy first, whatever the answer: it is what the walk steps on.
            _discovery.value = DiscoveryVerdict(address, result)
            // Then the one-shot signal, which only a Found has anything to say to.
            _imapDiscovery.value = result as? MailAutoconfigResult.Found
        }
    }

    /**
     * Ask the address' domain how it signs in — no credentials involved ([MailRepository.discoverOAuth]
     * is a plain GET), which is what lets this run at the address step, where there is nothing else
     * to go on.
     *
     * ⛔ IT RIDES WITH EVERY CASCADE, on every step, and gating it on the step is the defect this
     * spelling fixes. The settings verdict was never gated, so a pass from another step recorded a
     * `Found` with NO OAuth answer beside it — and the credentials step then drew a bare wheel for
     * an address that could never be probed again. The extra requests a manual-form pass now
     * makes are credential-free GETs to the reader's own domain; that is the price of the two
     * answers always travelling together.
     *
     * ⛔ It always ANSWERS: `chosen == null` is "nothing published OAuth here", which is a fact the
     * credentials step acts on (the password field). "Nobody asked" is no longer expressible.
     *
     * ⛔ WHICH hosts it may ask is [mailOAuthCandidates]' call and NOT the four raw candidates: the
     * first of those is the organisation's website, and its OAuth document sent the reader to a
     * browser, onto a sign-in page that had nothing to do with her mail. The manual fallback's
     * button keeps all four ([connectOAuth]), because there she asked for OAuth herself.
     */
    private suspend fun probeOAuthFor(address: String): OAuthDiscovery {
        val chosen = discoverOAuthAmong(mailOAuthCandidates(address)) {
            container.mailRepository.discoverOAuth(it)
        }
        return OAuthDiscovery(address, chosen)
    }

    /**
     * Where the address step's button leads — [addressAdvance]'s call, executed by a test, fed the
     * two facts the composable cannot hold honestly:
     *
     *  - the DURABLE verdict, read here rather than passed in, so one function answers whatever the
     *    call site remembered;
     *  - the link, read HERE AND NOW. Airplane mode is switched on a screen that is not
     *    recomposing, so a value captured when the button was drawn is the link as it was before
     *    she left the network — which is exactly the case being fixed.
     *
     * ⛔ No side effect: this is also what the button's `enabled` is computed from, i.e. it runs
     * during composition. What offline SAYS is [reportOffline], called from the tap alone.
     */
    internal fun advanceFromAddress(email: String): AddressAdvance =
        addressAdvance(email, _discovery.value, online = hasUsableNetwork(app))

    /**
     * Say that the device is offline, on the same surface and with the same sentence as the six
     * sign-in routes' own guards ([connect], [connectAuto]…): `R.string.connect_offline`, which
     * already lives in all nine locales.
     *
     * ⛔ It is a REPORT, not a step: the walk stays on the address step. The whole defect was that
     * an offline tap moved the user into the manual form — protocol chips, hosts, ports — with
     * nothing said about why the app had stopped looking.
     */
    internal fun reportOffline() {
        _state.value = ConnectState.Error(string(R.string.connect_offline))
    }

    /** The screen wrote the verdict into the fields: drop it so it is applied exactly once. */
    fun consumeImapDiscovery() {
        _imapDiscovery.value = null
    }

    /**
     * The walk moved to another step: whatever a FINISHED attempt left behind goes with the step it
     * belongs to. [clearsAttemptOnLeave] decides which states those are — and, just as much, which
     * ones must survive, because they are what stops a second submit.
     */
    internal fun clearFinishedAttempt() {
        if (clearsAttemptOnLeave(_state.value)) _state.value = ConnectState.Idle
    }

    init {
        observeOutlookSignIn()
        observeOAuthCodeSignIn()
    }

    fun connect(server: String, username: String, password: String, accountName: String) {
        if (_state.value is ConnectState.Connecting || _state.value is ConnectState.Discovering) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        _state.value = ConnectState.Connecting
        viewModelScope.launch { finishJmapConnect(server.trim(), username, password, accountName) }
    }

    /**
     * Autodiscovery path (RFC 8620 §2.2): the user gives only their email +
     * password; we probe the email domain's `/.well-known/jmap` to find the
     * server. On success we connect; if nothing responds we ask for the server
     * manually ([ConnectState.NeedsServer]); a credential rejection is reported
     * as such.
     */
    fun connectAuto(email: String, password: String, accountName: String) {
        if (_state.value is ConnectState.Connecting || _state.value is ConnectState.Discovering) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = try {
                container.mailRepository.discoverJmapServer(email.trim(), password)
            } catch (cancelled: CancellationException) {
                // Leaving the screen cancels the probes. That is not "no server found", and the
                // NeedsServer below would be a "tell me your server" form nobody asked for, written
                // onto a screen that is gone.
                throw cancelled
            } catch (_: Throwable) {
                MailRepository.DiscoveryResult.NotFound
            }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishJmapConnect(result.server, email, password, accountName)
                }
                MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(getApplication<Application>().getString(R.string.connect_bad_credentials))
                MailRepository.DiscoveryResult.NotFound ->
                    _state.value = ConnectState.NeedsServer
            }
        }
    }

    /**
     * API-token sign-in (issue #54): authenticate with a server-generated Bearer
     * token (e.g. a Fastmail API token) instead of a password. A blank [server]
     * autodiscovers from the email domain; otherwise the input is resolved
     * tolerantly (bare host, base URL, or a pasted session URL all work).
     */
    fun connectToken(server: String, email: String, token: String, accountName: String) {
        if (busy()) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        val emailTrim = email.trim()
        val serverTrim = server.trim()
        if (serverTrim.isNotBlank()) {
            _state.value = ConnectState.Connecting
            viewModelScope.launch { finishTokenConnect(serverTrim, emailTrim, token, accountName) }
            return
        }
        _state.value = ConnectState.Discovering
        viewModelScope.launch {
            val result = try {
                container.mailRepository.discoverJmapServer(emailTrim, password = "", token = token)
            } catch (cancelled: CancellationException) {
                // As in connectAuto: a cancelled probe is not a verdict about the server, and the
                // token path must not answer one on a screen the user has left.
                throw cancelled
            } catch (_: Throwable) {
                MailRepository.DiscoveryResult.NotFound
            }
            when (result) {
                is MailRepository.DiscoveryResult.Found -> {
                    _state.value = ConnectState.Connecting
                    finishTokenConnect(result.server, emailTrim, token, accountName)
                }
                MailRepository.DiscoveryResult.BadCredentials ->
                    _state.value = ConnectState.Error(string(R.string.connect_token_rejected))
                MailRepository.DiscoveryResult.NotFound ->
                    _state.value = ConnectState.NeedsServer
            }
        }
    }

    /** Resolve the server input with the token, validate, persist. Mirrors [finishJmapConnect]. */
    private suspend fun finishTokenConnect(server: String, email: String, token: String, accountName: String) {
        try {
            val resolved = container.mailRepository.resolveJmapServerInput(server, BearerAuth(token))
            // The token alone identifies the account server-side — the typed address is never
            // validated by Bearer auth, so a mistyped email would silently become the account's
            // identity (From default, notifications, display). Adopt the session's own address
            // (RFC 8620 username / mail account name) when it declares one; the typed value
            // only stands when the server exposes no usable address.
            val address = runCatching {
                container.mailRepository.sessionIdentity(resolved, BearerAuth(token))
            }.getOrNull() ?: email
            // Re-adding the same mailbox (whatever email was typed) resolves to the same
            // server + adopted address: the store refreshes that account in place, never a
            // duplicate. Asked BEFORE the network round-trip, and only to know what to SAY —
            // ⛔ not filtered on authType any more: the same address added by password and then by
            // token is one account, and the old filter made the token re-add mint a second one.
            val stored = container.accountStore.accounts()
            val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, resolved, "", address)) == null
            // Blank id on purpose, even when re-adding: the real id comes back from the persist
            // step below and [addAccountThenPrime] stamps it onto the credentials it primes with,
            // so a brand-new token account can no longer cache its inbox under no account (#121).
            val probe = AccountCredentials(resolved, address, token, authType = AuthType.API_TOKEN)
            val added = addAccountThenPrime(
                probe = probe,
                validate = { container.mailRepository.testConnection(it).getOrThrow() },
                // One call, whether or not the account is already there: add() refreshes the
                // login stored under this identity and hands back ITS id, atomically.
                persist = {
                    AddedAccount(
                        container.accountStore.add(resolved, address, token, accountName.trim(), authType = AuthType.API_TOKEN),
                        created = created,
                    )
                },
                prime = { primeInbox(it) },
            )
            warnIfNotPrimed(added)
            // Surface linked sub-accounts before navigating, like the password path (#31):
            // a token login's sub-accounts resolve Bearer auth via the login.
            // ⛔ ONLY on a real creation. See [finishJmapConnect] for why a re-add must not.
            if (created) container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)
            accountAddedToast(created, null)?.let { toast(it) }
            _state.value = ConnectState.Connected
        } catch (cancelled: CancellationException) {
            // Leaving the screen cancels this coroutine: not something to show an error for.
            throw cancelled
        } catch (t: Throwable) {
            // A 401/403 with a token means the token itself was rejected — say so.
            val code = (t as? JmapException)?.httpCode
            _state.value = ConnectState.Error(
                if (code == 401 || code == 403) string(R.string.connect_token_rejected)
                else t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /**
     * OAuth device flow (RFC 8628): discover the OAuth server for the email's
     * domain, start a device authorization, show the user code, then poll until
     * the user approves in a browser. The password field is ignored.
     */
    fun connectOAuth(email: String, server: String, accountName: String) {
        if (busy()) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        val emailTrim = email.trim()
        // If the user entered a server (advanced), discover OAuth there; otherwise
        // derive candidate hosts from the email domain.
        val candidates = if (server.isNotBlank()) listOf(server.trim()) else Jmap.autodiscoverHosts(emailTrim)
        _state.value = ConnectState.Discovering
        oauthJob = viewModelScope.launch {
            // ⚠ COLLECT, don't stop at the first answer, and WHICH answer ends the search is
            // collectOAuthHosts' call, not this screen's — it is the rule a fake probe can run.
            //
            // ⛔ Unless the address step has already asked, for this very address and with no
            // server typed by hand: its answer stands ([reusableOAuth]). Searching again, seconds
            // later and on the path the reader did not choose, means a host that answers once but
            // not twice is reported as "this server doesn't support OAuth sign-in" — false, and
            // said to someone who never asked for OAuth in the first place.
            val reused = reusableOAuth(_oauthDiscovery.value, emailTrim, server)
            val discovered = reused?.let { listOf(it) }
                ?: collectOAuthHosts(candidates) { container.mailRepository.discoverOAuth(it) }
            // "No OAuth here" and "OAuth, but not a flow this app can drive" are different facts
            // and used to be the same sentence. WHICH sentence is oauthDiscoveryFailure's call and
            // no longer this screen's — it answers for a null metadata too, so the "nothing
            // answered anywhere" corner asks it again instead of naming a string here.
            val chosen = chooseOAuthHost(discovered)
            val advertised = chosen?.second
            val discoveryFailure = oauthDiscoveryFailure(advertised)
            if (chosen == null || discoveryFailure != null) {
                _state.value = ConnectState.Error(
                    string(discoveryFailure ?: requireNotNull(oauthDiscoveryFailure(null))),
                )
                return@launch
            }
            val (host, found) = chosen
            // WHICH grant is chooseOAuthGrant's call, and it prefers the device flow: this screen
            // only acts on the answer. The code grant hands over to the app-scoped driver, because
            // from here the sign-in leaves for a browser and comes back through MainActivity —
            // possibly after this ViewModel is gone.
            if (chooseOAuthGrant(found) == OAuthGrant.AUTHORIZATION_CODE) {
                // BY NAME: `email` and `accountName` are adjacent Strings, a swap compiles, and
                // the account would be persisted with its label where its address belongs.
                container.oauthCodeSignIn.start(
                    host = host,
                    metadata = found,
                    email = emailTrim,
                    accountName = accountName,
                )
                return@launch
            }
            val start = runCatching { container.mailRepository.startDeviceAuthorization(found) }
            val device = start.getOrNull()
            if (device == null) {
                // Whatever the server said about the refusal, said. A transport failure carries no
                // oauthError and falls back to the old "Couldn't start OAuth sign-in".
                val spec = oauthFailureSpec(
                    error = (start.exceptionOrNull() as? JmapException)?.oauthError.orEmpty(),
                    description = "",
                    aadstsCode = null,
                    fallbackRes = R.string.connect_oauth_failed,
                )
                _state.value = ConnectState.Error(
                    if (spec.arg == null) string(spec.resId) else string(spec.resId, spec.arg),
                )
                return@launch
            }
            _state.value = ConnectState.AwaitingApproval(
                device.userCode, device.verificationUri, device.verificationUriComplete,
            )
            pollForToken(host, found, device, emailTrim, accountName)
        }
    }

    /**
     * Wait for approval, then say what the wait concluded.
     *
     * ⛔ The waiting itself is [pollDeviceGrant]'s, not this function's, and that is what makes it
     * testable at all: nothing in this repo can build this ViewModel, so while the loop lived here
     * it was executed by no test. This body is the mapping from verdict to screen, and nothing else.
     */
    private suspend fun pollForToken(
        host: String,
        metadata: OAuthMetadata,
        device: DeviceAuthorization,
        email: String,
        accountName: String,
    ) {
        // A poll the client could not read at all rises instead of passing for a dead network (it
        // used to be swallowed into `Failed("network error")`). It must not leave this coroutine:
        // an uncaught throw in viewModelScope takes the app down mid sign-in. ⛔ The guard is HERE
        // and not inside the loop: pollDeviceGrant catches nothing, so cancelling the screen still
        // ends the wait instead of leaving it alive for the rest of the device code's half hour.
        val verdict = try {
            pollDeviceGrant(
                // ⛔ No clamp here: the one-second floor is pollDeviceGrant's, where a test runs it.
                intervalSeconds = device.interval.toLong(),
                expiresInSeconds = device.expiresIn.toLong(),
            ) { container.mailRepository.pollDeviceToken(metadata, device.deviceCode) }
        } catch (cancelled: CancellationException) {
            // Leaving the screen cancels the poll: not something to show an error for.
            throw cancelled
        } catch (t: Throwable) {
            val spec = oauthFailureSpec(
                error = (t as? JmapException)?.oauthError.orEmpty(),
                description = "",
                aadstsCode = null,
                fallbackRes = R.string.connect_oauth_failed,
            )
            _state.value = ConnectState.Error(
                if (spec.arg == null) string(spec.resId) else string(spec.resId, spec.arg),
            )
            return
        }
        when (verdict) {
            is DevicePollVerdict.Approved -> {
                _state.value = ConnectState.Connecting
                val outcome = runCatching {
                    container.mailRepository.addOAuthAccount(host, email, metadata, verdict.tokens, accountName)
                }
                _state.value = outcome.fold(
                    onSuccess = { created ->
                        accountAddedToast(created, null)?.let { toast(it) }
                        ConnectState.Connected
                    },
                    onFailure = { ConnectState.Error(it.message ?: it.javaClass.simpleName) },
                )
            }
            is DevicePollVerdict.Refused -> {
                // Not every terminal poll is a refusal by the reader: say what the server said.
                _state.value = ConnectState.Error(oauthFailureMessage(getApplication<Application>(), verdict.failure))
            }
            // WHICH END went quiet, and both sentences already exist in nine languages. ⛔ The
            // choice is oauthRanOutMessage's, not this screen's: written here as an `if`, it was
            // held by nothing — no test can build this ViewModel, so the two resources could be
            // swapped back and the whole suite stayed green.
            is DevicePollVerdict.RanOut ->
                _state.value = ConnectState.Error(string(oauthRanOutMessage(verdict.everReachedAServer)))
        }
    }

    /**
     * OAuth device flow for Outlook/Microsoft over IMAP+SMTP with XOAUTH2. Delegates to the
     * app-scoped [OutlookSignIn] so the token poll survives the round-trip to the browser
     * (backgrounding / app lock / this screen being popped) — see [OutlookSignIn]. The
     * email labels the account; the password field is ignored.
     */
    fun connectOutlookOAuth(email: String, accountName: String) {
        if (busy()) return
        // ⛔ The two facts below outrank being offline, and are checked first on purpose: an
        // unconfigured build and an empty address stay wrong once the network is back, so saying
        // "you're offline" there would send the user off to fix the one thing that is not broken.
        if (!OAuthProvider.MICROSOFT.isConfigured) {
            _state.value = ConnectState.Error(string(R.string.connect_oauth_provider_unconfigured))
            return
        }
        val emailTrim = email.trim()
        if (emailTrim.isBlank()) {
            _state.value = ConnectState.Error(string(R.string.connect_oauth_need_email))
            return
        }
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        container.outlookSignIn.start(emailTrim, accountName)
    }

    /** Mirror the app-scoped Outlook sign-in into this screen's state. */
    private fun observeOutlookSignIn() {
        viewModelScope.launch {
            container.outlookSignIn.progress.collect { p ->
                _state.value = when (p) {
                    OutlookProgress.Idle -> return@collect
                    OutlookProgress.Starting -> ConnectState.Discovering
                    is OutlookProgress.AwaitingApproval ->
                        ConnectState.AwaitingApproval(p.userCode, p.verificationUri, p.verificationUriComplete)
                    OutlookProgress.Connecting -> ConnectState.Connecting
                }
            }
        }
        viewModelScope.launch {
            container.outlookSignIn.outcomes.collect { o ->
                _state.value = when (o) {
                    OutlookOutcome.Success -> ConnectState.Connected
                    is OutlookOutcome.Error -> ConnectState.Error(o.message)
                }
            }
        }
    }

    /** Mirror the app-scoped authorization-code sign-in into this screen's state (#55). */
    private fun observeOAuthCodeSignIn() {
        viewModelScope.launch {
            container.oauthCodeSignIn.progress.collect { p ->
                _state.value = when (p) {
                    // Idle is where the driver sits before and after a sign-in: adopting it would
                    // wipe whatever this screen is showing, including the error just emitted.
                    OAuthCodeProgress.Idle -> return@collect
                    is OAuthCodeProgress.AwaitingBrowser -> ConnectState.AwaitingBrowser(p.authorizationUrl)
                    OAuthCodeProgress.Connecting -> ConnectState.Connecting
                }
            }
        }
        viewModelScope.launch {
            container.oauthCodeSignIn.outcomes.collect { o ->
                _state.value = when (o) {
                    OAuthCodeOutcome.Success -> ConnectState.Connected
                    is OAuthCodeOutcome.Error -> ConnectState.Error(o.message)
                }
            }
        }
    }

    // ---- post-import per-account sign-in ----

    /** One imported account still awaiting sign-in. [provider] is set only for OAUTH accounts on a
     *  known provider (Microsoft); null means basic-auth or an unknown XOAUTH2 server. */
    data class PendingAccount(
        val id: String,
        val label: String,
        val email: String,
        val authType: AuthType,
        val provider: OAuthProvider?,
    )

    /**
     * State of the "sign in to your imported accounts" step. After an import the user sees a
     * [Listing] of every pending account and drives it: tapping a row selects it ([SignInTarget]);
     * signing one in drops it off the list; swiping a row dismisses it. When the list empties we
     * either enter the app ([Done], at least one account is signed in) or fall back to the
     * add-account form ([None]).
     */
    sealed interface ImportSignIn {
        data object None : ImportSignIn
        data class Listing(val pending: List<PendingAccount>, val selected: SignInTarget? = null) : ImportSignIn
        data object Done : ImportSignIn
        data class Approval(val userCode: String, val verificationUri: String, val verificationUriComplete: String?)
    }

    /** The one account the user tapped to sign in, plus that account's in-progress sign-in state. */
    data class SignInTarget(
        val account: PendingAccount,
        val verifying: Boolean = false,
        val error: String? = null,
        /** Non-null while this OAuth account is awaiting browser approval (device flow). */
        val approval: ImportSignIn.Approval? = null,
        /** After a failed/declined OAuth sign-in, offer switching this account to an app password. */
        val offerAppPasswordFallback: Boolean = false,
        /** Render a password field even though the account imported as OAuth (fallback chosen). */
        val forcePassword: Boolean = false,
    )

    /** The inert imported accounts still awaiting sign-in, as [StoredAccount]s for the list UI.
     *  Re-read on each access so it reflects sign-ins/dismissals; the [importSignIn] flow drives
     *  the recomposition that re-reads it. */
    val pendingStoredAccounts: List<StoredAccount>
        get() = container.accountStore.pendingImportAccounts()

    /** Imported accounts still awaiting sign-in: inert (no stored secret), any auth type. */
    private fun pendingAccounts(): List<PendingAccount> =
        container.accountStore.pendingImportAccounts().map { a ->
            PendingAccount(
                id = a.id, label = a.label(), email = a.username,
                authType = a.authType,
                provider = if (a.authType == AuthType.OAUTH) OAuthProvider.forImapHost(a.imapHost) else null,
            )
        }

    /**
     * After an import, show the user-driven list of accounts to sign in. When nothing is pending we
     * either enter the app (something is already signed in) or fall back to the add-account form.
     */
    fun beginImportSignIn() {
        val p = pendingAccounts()
        _importSignIn.value = if (p.isEmpty()) finishOrIdle() else ImportSignIn.Listing(p)
    }

    /**
     * "Add account" escape from the pending-imports list: leave the listing for the normal
     * add-account form. With every imported account still deferred there is no signed-in account,
     * so without this exit the listing is a dead end (fresh install → import → skip → stranded;
     * only uninstalling recovered). The pending accounts are untouched — the form offers the way
     * back ([resumeImportSignIn]) while any remain.
     */
    fun leaveImportListing() {
        if (currentListing()?.selected == null) _importSignIn.value = ImportSignIn.None
    }

    /**
     * Re-enter the sign-in list for any still-unauthenticated imported account — e.g. the app was
     * killed mid-sign-in and relaunched, or the user left it via [leaveImportListing] and wants
     * back. Does nothing when there are none (so a genuine first run just shows the add-account
     * form). Never overrides a list already on screen.
     */
    fun resumeImportSignIn() {
        if (_importSignIn.value is ImportSignIn.Listing) return
        val p = pendingAccounts()
        if (p.isNotEmpty()) _importSignIn.value = ImportSignIn.Listing(p)
    }

    private fun currentListing() = _importSignIn.value as? ImportSignIn.Listing

    /** Recompute the pending list, keeping [keepSelected] only if that account is still pending.
     *  When nothing is left, finish (enter the app) or fall back to the add-account form. */
    private fun refreshListing(keepSelected: SignInTarget? = currentListing()?.selected) {
        val p = pendingAccounts()
        _importSignIn.value =
            if (p.isEmpty()) finishOrIdle()
            else ImportSignIn.Listing(p, keepSelected?.takeIf { s -> p.any { it.id == s.account.id } })
    }

    private fun finishOrIdle(): ImportSignIn =
        if (container.accountStore.accounts().any { container.accountStore.credentials(it.id) != null }) ImportSignIn.Done
        else ImportSignIn.None

    /** The user tapped a row: open that account's inline sign-in. */
    fun selectImportAccount(id: String) {
        val listing = currentListing() ?: return
        val account = listing.pending.firstOrNull { it.id == id } ?: return
        _importSignIn.value = listing.copy(selected = SignInTarget(account))
    }

    /** Back to the plain list (cancel any in-progress device flow). */
    fun closeImportAccount() {
        importOAuthJob?.cancel(); importOAuthJob = null
        refreshListing(keepSelected = null)
    }

    /** Swipe-dismiss: disconnect and REMOVE the imported account entirely (it never appears in the
     *  account list; the user can re-import it later). Undoable via [restoreImportAccount]. */
    fun dismissImportAccount(id: String) {
        container.accountStore.remove(id)
        if (currentListing()?.selected?.account?.id == id) closeImportAccount() else refreshListing()
    }

    /** Undo a swipe-dismiss: re-add the removed account, back on the "to sign in" list. */
    fun restoreImportAccount(account: StoredAccount) {
        container.accountStore.readdImportedAccount(account)
        refreshListing()
    }

    /** Mutate the currently-selected [SignInTarget] in place, if any. */
    private fun updateSelected(block: (SignInTarget) -> SignInTarget) {
        val l = currentListing() ?: return
        val s = l.selected ?: return
        _importSignIn.value = l.copy(selected = block(s))
    }

    /** A sign-in succeeded: return to the list (the account drops off; an empty list enters the app). */
    private fun closeImportAccountThenAdvance() = refreshListing(keepSelected = null)

    /** Verify the selected imported account with [password]; on success save it and return to the list. */
    fun submitImportPassword(password: String) {
        val target = currentListing()?.selected ?: return
        if (target.verifying || password.isBlank()) return
        val account = container.accountStore.account(target.account.id) ?: return
        if (!hasUsableNetwork(app)) {
            updateSelected { it.copy(error = string(R.string.connect_offline)) }
            return
        }
        updateSelected { it.copy(verifying = true, error = null) }
        viewModelScope.launch {
            container.mailRepository.testConnection(credentialsFor(account, password)).fold(
                onSuccess = {
                    container.accountStore.updatePassword(target.account.id, password)
                    container.accountStore.setImportPending(target.account.id, false)
                    closeImportAccountThenAdvance()
                },
                onFailure = { e ->
                    val msg = e.message ?: string(R.string.connect_bad_credentials)
                    val authRejected = msg.contains("LOGIN", true) || msg.contains("AUTHENTICATE", true) || msg.contains("credential", true)
                    updateSelected {
                        it.copy(
                            verifying = false,
                            error = if (authRejected) msg + " " + string(R.string.connect_provider_app_password_note) else msg,
                            offerAppPasswordFallback = authRejected,
                        )
                    }
                },
            )
        }
    }

    /** Launch the Microsoft device flow for the selected OAuth account and drive it to completion. */
    fun startImportOAuth() {
        val target = currentListing()?.selected ?: return
        val provider = target.account.provider ?: return
        if (target.verifying || target.approval != null) return
        if (!hasUsableNetwork(app)) {
            updateSelected { it.copy(error = string(R.string.connect_offline)) }
            return
        }
        updateSelected { it.copy(verifying = true, error = null, offerAppPasswordFallback = false) }
        importOAuthJob = viewModelScope.launch {
            val result = container.mailRepository.runProviderDeviceFlow(provider) { device ->
                updateSelected {
                    it.copy(
                        verifying = false,
                        approval = ImportSignIn.Approval(device.userCode, device.verificationUri, device.verificationUriComplete),
                    )
                }
            }
            result.fold(
                onSuccess = { tokens ->
                    updateSelected { it.copy(approval = null, verifying = true, error = null) }
                    runCatching { container.mailRepository.signInImportedOAuth(target.account.id, provider, tokens) }.fold(
                        onSuccess = {
                            container.accountStore.setImportPending(target.account.id, false)
                            refreshListing(keepSelected = null)
                        },
                        onFailure = { e ->
                            updateSelected {
                                it.copy(
                                    verifying = false,
                                    error = e.message ?: string(R.string.connect_oauth_failed),
                                    offerAppPasswordFallback = true,
                                )
                            }
                        },
                    )
                },
                onFailure = { e ->
                    val msg = if (e is OAuthDeniedException) oauthFailureMessage(getApplication<Application>(), e.failure)
                    else (e.message ?: string(R.string.connect_oauth_failed))
                    updateSelected { it.copy(approval = null, verifying = false, error = msg, offerAppPasswordFallback = true) }
                },
            )
        }
    }

    /** Cancel an in-progress import device flow; the account stays selected and retryable. */
    fun cancelImportOAuth() {
        importOAuthJob?.cancel(); importOAuthJob = null
        updateSelected { it.copy(approval = null, verifying = false) }
    }

    /** Fallback: switch the selected account to manual app-password auth and show its password field. */
    fun switchImportToAppPassword() {
        importOAuthJob?.cancel(); importOAuthJob = null
        val target = currentListing()?.selected ?: return
        container.accountStore.convertToBasicAuth(target.account.id)
        updateSelected {
            it.copy(
                account = it.account.copy(authType = AuthType.BASIC, provider = null),
                forcePassword = true, offerAppPasswordFallback = false,
                approval = null, error = null, verifying = false,
            )
        }
    }

    private fun credentialsFor(a: StoredAccount, password: String) = AccountCredentials(
        server = a.server,
        username = a.username,
        password = password,
        id = a.id,
        protocol = a.protocol,
        imap = if (a.protocol == MailProtocol.IMAP) MailEndpoint(a.imapHost, a.imapPort, a.imapSecurity) else null,
        smtp = if (a.protocol == MailProtocol.IMAP) MailEndpoint(a.smtpHost, a.smtpPort, a.smtpSecurity) else null,
    )

    /** Cancel an in-progress browser sign-in (either grant) and return to the form. */
    fun cancelOAuth() {
        oauthJob?.cancel()
        oauthJob = null
        container.outlookSignIn.cancel()
        // Drops the pending authorization with it: after Cancel, a redirect that still arrives
        // (the browser was left open on the sign-in page) finds nothing and exchanges nothing.
        container.oauthCodeSignIn.cancel()
        _state.value = ConnectState.Idle
    }

    private fun busy() = _state.value is ConnectState.Connecting ||
        _state.value is ConnectState.Discovering ||
        _state.value is ConnectState.AwaitingApproval ||
        _state.value is ConnectState.AwaitingBrowser

    /**
     * Say [resId] out loud, on the Application — the same instrument as `OAuthCodeSignIn.toast` /
     * `OutlookSignIn.toast`, because the sentence has to survive the screen navigating away the
     * moment an add succeeds. LENGTH_LONG: it is a full sentence, not a word.
     */
    private fun toast(@StringRes resId: Int) {
        Toast.makeText(app, string(resId), Toast.LENGTH_LONG).show()
    }
    // ⛔ Two lines, and both are pinned whole by ConnectPrimingWiringTest: this helper is the last
    // stretch between accountAddedToast's answer and the reader. Naming a resource here instead of
    // showing [resId] puts the same sentence on every add again — the defect, one floor lower.

    private fun string(resId: Int) = getApplication<Application>().getString(resId)
    private fun string(resId: Int, vararg args: Any) = getApplication<Application>().getString(resId, *args)

    /**
     * Load an existing account's inbox into the cache and record its meta. The single entry
     * point every add path primes through — and the only place this screen calls `refresh()`,
     * so the credentials it writes under always carry the id [addAccountThenPrime] stamped.
     */
    /**
     * An add whose first inbox load failed still added the account, and the screen goes on to
     * [ConnectState.Connected] like any other add: the account is there, its cache fills on the
     * first refresh — the same state a process death mid-add leaves, which the app already repairs
     * without saying anything. Nothing to show the user, but the cause is worth a log line.
     */
    private fun warnIfNotPrimed(added: PrimedAccount) {
        added.primeFailure?.let {
            android.util.Log.w(
                "SternaConnect",
                "account ${added.id} was added; loading its inbox failed and will be retried on the next refresh",
                it,
            )
        }
    }

    private suspend fun primeInbox(credentials: AccountCredentials) {
        val meta = container.mailRepository.refresh(credentials)
        container.accountStore.saveInboxMetaFor(
            credentials.id, meta.mailboxId, meta.mailboxName, meta.accountName, meta.unreadCount,
        )
    }

    /** Validate against [server], persist on success. Runs in the caller's coroutine. */
    private suspend fun finishJmapConnect(server: String, username: String, password: String, accountName: String) {
        try {
            // Was this add the one that CREATED the account? Asked before the network
            // round-trip, on the same identity the store keys on: add() refreshes a login already
            // there, so afterwards the answer is always "it exists".
            val stored = container.accountStore.accounts()
            val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.JMAP, server, "", username)) == null
            // Blank id on purpose: this copy only ever proves the credentials. The cache is
            // primed from the id-stamped copy [addAccountThenPrime] builds (#121).
            val probe = AccountCredentials(server, username.trim(), password)
            val added = addAccountThenPrime(
                probe = probe,
                // Writes nothing — only persist once we know they work.
                validate = { container.mailRepository.testConnection(it).getOrThrow() },
                // A blank name falls back to the address.
                persist = {
                    AddedAccount(
                        container.accountStore.add(server, username, password, accountName.trim()),
                        created = created,
                    )
                },
                prime = { primeInbox(it) },
            )
            warnIfNotPrimed(added)
            // Surface linked sub-accounts before navigating, so the accounts list the flow
            // lands on is already complete (#31).
            //
            // ⛔ ONLY when this add CREATED the account. A re-add now lands on the login that was
            // already there — with its sub-accounts — instead of minting a second one, and it may
            // have just moved that login from OAuth to a password. Reconciling there hands
            // `diffLinkedAccounts` a session of the other auth family, and eviction reads the RAW
            // discovered list (AccountStore.kt:155): a session that lists the login's own account
            // but not the shares would prune every sub-account and purge its five tables — a real
            // shared mailbox deleted, which is the destruction #129 exists to prevent.
            //
            // Nothing is lost by skipping it: this call is an optimisation that reuses the cached
            // session so the accounts screen is complete without a round trip, and the connect()
            // hook reconciles anyway (see [MailRepository.reconcileLinkedAccountsAfterAdd]). On a
            // re-add the list is already complete, so the only thing at stake is a share granted
            // between the last refresh and this re-add appearing one refresh later.
            if (created) container.mailRepository.reconcileLinkedAccountsAfterAdd(added.id)
            accountAddedToast(created, null)?.let { toast(it) }
            _state.value = ConnectState.Connected
        } catch (cancelled: CancellationException) {
            // Leaving the screen cancels this coroutine: not something to show an error for.
            throw cancelled
        } catch (t: Throwable) {
            // Fastmail's endpoint refuses password auth outright (API tokens only, #54):
            // a 401 from it gets the same steer as the inline hint, not just a bare error.
            val msg = t.message ?: t.javaClass.simpleName
            val fastmail401 = (t as? JmapException)?.httpCode == 401 && isFastmailTarget(username, server)
            _state.value = ConnectState.Error(
                if (fastmail401) msg + " " + string(R.string.connect_fastmail_token_hint) else msg,
            )
        }
    }

    fun connectImap(
        username: String,
        password: String,
        accountName: String,
        imapHost: String,
        imapPort: Int,
        imapSecurity: ConnectionSecurity,
        smtpHost: String,
        smtpPort: Int,
        smtpSecurity: ConnectionSecurity,
    ) {
        if (_state.value is ConnectState.Connecting) return
        if (!hasUsableNetwork(app)) {
            _state.value = ConnectState.Error(string(R.string.connect_offline))
            return
        }
        _state.value = ConnectState.Connecting
        viewModelScope.launch {
            try {
                // Was this add the one that CREATED the account? An IMAP account is keyed on
                // its IMAP host, not on the (blank) server. Asked before the network round-trip.
                val stored = container.accountStore.accounts()
                val created = resolveExistingLogin(stored, accountKeyOf(MailProtocol.IMAP, "", imapHost, username)) == null
                // Blank id on purpose: proving credentials only. See [addAccountThenPrime] (#121).
                val probe = AccountCredentials(
                    server = "",
                    username = username.trim(),
                    password = password,
                    protocol = MailProtocol.IMAP,
                    imap = MailEndpoint(imapHost.trim(), imapPort, imapSecurity),
                    smtp = MailEndpoint(smtpHost.trim(), smtpPort, smtpSecurity),
                )
                val added = addAccountThenPrime(
                    probe = probe,
                    // Connects + authenticates + lists folders, without caching any of it.
                    validate = { container.mailRepository.testConnection(it).getOrThrow() },
                    persist = {
                        AddedAccount(
                            container.accountStore.add(
                                server = "",
                                username = username,
                                password = password,
                                accountName = accountName.trim(),
                                protocol = MailProtocol.IMAP,
                                imapHost = imapHost,
                                imapPort = imapPort,
                                imapSecurity = imapSecurity,
                                smtpHost = smtpHost,
                                smtpPort = smtpPort,
                                smtpSecurity = smtpSecurity,
                            ),
                            created = created,
                        )
                    },
                    prime = { primeInbox(it) },
                )
                warnIfNotPrimed(added)
                accountAddedToast(created, null)?.let { toast(it) }
                _state.value = ConnectState.Connected
            } catch (cancelled: CancellationException) {
                // Leaving the screen cancels this coroutine: not something to show an error for.
                throw cancelled
            } catch (t: Throwable) {
                // A rejected IMAP login carries the server's RFC 5530 response code when it sent
                // one, and surfaces as "LOGIN … failed" / "AUTHENTICATE …" (the command verb is
                // redacted, so no password leaks). When the credentials themselves were refused,
                // point the user at app passwords — most big providers refuse the normal one.
                val code = (t as? ImapException)?.responseCode
                _state.value = ConnectState.Error(
                    when {
                        imapAuthRejected(code, t.message.orEmpty()) ->
                            string(R.string.connect_bad_credentials) + " " +
                                string(R.string.connect_provider_app_password_note)
                        // The server named its failure: show the CODE, never the response body —
                        // a server's free text has already leaked a bearer token once (8e942069).
                        code != null -> string(R.string.connect_imap_refused, code)
                        else -> t.message ?: t.javaClass.simpleName
                    },
                )
            }
        }
    }
}

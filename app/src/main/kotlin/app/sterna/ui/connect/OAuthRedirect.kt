package app.sterna.ui.connect

import app.sterna.core.jmap.OAuthMetadata
import app.sterna.core.jmap.Pkce

/**
 * The authorization-code round trip through the browser, as decisions that can be EXECUTED.
 *
 * Nothing here touches Android: no `Uri`, no `Context`, no `ViewModel`. The driver
 * ([OAuthCodeSignIn]) parses the redirect and performs the network call; WHAT to do with a
 * redirect is decided by [redirectVerdict] / [AuthorizationRequestSlot], which a unit test runs
 * for real instead of reading the code that contains them.
 */

/** The host half of the redirect this app registers, i.e. `<applicationId>://oauth`. */
const val OAUTH_REDIRECT_HOST = "oauth"

/**
 * The redirect the authorization server sends the browser back to.
 *
 * Built from the [applicationId] it is given, never from the literal `app.sterna`. The test
 * build (`-PtestApp`) is `app.sterna.test` and installs ALONGSIDE production: two packages
 * claiming one scheme make the return non-deterministic — Android picks, and the bench proves
 * nothing about the app under test. The manifest registers `android:scheme="${applicationId}"`
 * for the same reason, and this is the same string computed for the token exchange, where the
 * server compares it byte for byte with the one it was given (RFC 6749 §4.1.3).
 */
fun oauthRedirectUri(applicationId: String): String = "$applicationId://$OAUTH_REDIRECT_HOST"

/** True when a VIEW intent's [scheme]/[host] are this app's own OAuth redirect and nothing else. */
fun isOAuthRedirect(scheme: String?, host: String?, applicationId: String): Boolean =
    scheme.equals(applicationId, ignoreCase = true) && host.equals(OAUTH_REDIRECT_HOST, ignoreCase = true)

/**
 * A fresh `state` for one authorization request (RFC 6749 §10.12).
 *
 * Drawn by [Pkce.newCodeVerifier], i.e. from `SecureRandom` over the RFC 3986 `unreserved`
 * alphabet, for the same reason the verifier is: `state` is what makes a redirect belong to the
 * sign-in the user actually started. A guessable one lets anything that can open a link on this
 * phone hand the app an authorization code of its own choosing, and the account that gets added is
 * the attacker's. 43 characters of that alphabet is ~256 bits.
 */
fun newOAuthState(): String = Pkce.newCodeVerifier(43)

/**
 * The one authorization request in flight, held IN MEMORY only.
 *
 * Nothing here is persisted (arbitration: KISS + irreversible-first). `codeVerifier` is the
 * proof that redeems a code; on disk it would outlive the browser trip it exists for, and it would
 * have to be wiped on every path out. So a process death while the browser is in front loses the
 * request, and the redirect that comes back finds nothing and says so — see
 * [RedirectVerdict.NoPendingRequest]. That is a readable sentence and a fresh start, not a
 * degraded exchange.
 */
data class PendingAuthorization(
    val state: String,
    val codeVerifier: String,
    val host: String,
    val metadata: OAuthMetadata,
    val email: String,
    val accountName: String,
) {
    init {
        // THE guard of this whole flow: no exchange without PKCE, ever. There is no fallback to
        // a bare authorization code and no "the server didn't ask for a challenge" path, so a
        // request that could not prove possession cannot be built in the first place — the failure
        // lands on the developer who built it, never on a user's redirect.
        require(codeVerifier.isNotBlank()) {
            "an authorization request without a code verifier could only be exchanged without PKCE"
        }
        require(state.isNotBlank()) { "an authorization request without a state cannot be matched" }
    }
}

/** What a redirect coming back from the browser means, decided before any network call. */
sealed interface RedirectVerdict {
    /** Redeem [code] with [request]'s verifier — the only verdict that reaches the token endpoint. */
    data class Exchange(val code: String, val request: PendingAuthorization) : RedirectVerdict

    /** Nothing was in flight: the process died during the browser trip, or this is a stale link. */
    data object NoPendingRequest : RedirectVerdict

    /** The `state` is not the one we drew: not our sign-in. The code is NOT exchanged. */
    data object StateMismatch : RedirectVerdict

    /** The server said no (`error=access_denied`, …): the user refused, or the client is unknown. */
    data class Refused(val error: String) : RedirectVerdict

    /** Our own sign-in came back with neither a code nor an error. */
    data object NothingUsable : RedirectVerdict
}

/**
 * Decide what a redirect is worth. The ORDER below is the guard, not the individual checks:
 *
 *  1. something must be in flight — otherwise there is no verifier, and no exchange is possible;
 *  2. the `state` must be the one we drew, and it is compared BEFORE `code` or `error` are even
 *     looked at, so a forged redirect cannot get an authorization code of its own choosing
 *     exchanged against our client id;
 *  3. only then does the server's answer matter: a refusal is a message, a code is an exchange.
 *
 * Consumption (a redirect is good exactly once) belongs to [AuthorizationRequestSlot]; this
 * function is a pure verdict and can be run twice on the same input without meaning anything
 * different.
 */
fun redirectVerdict(
    pending: PendingAuthorization?,
    state: String?,
    code: String?,
    error: String?,
): RedirectVerdict {
    if (pending == null) return RedirectVerdict.NoPendingRequest
    // Blank never matches: a redirect with no state at all is not "the same as ours".
    if (state.isNullOrBlank() || state != pending.state) return RedirectVerdict.StateMismatch
    if (!error.isNullOrBlank()) return RedirectVerdict.Refused(error)
    if (code.isNullOrBlank()) return RedirectVerdict.NothingUsable
    return RedirectVerdict.Exchange(code, pending)
}

/** The four values the token endpoint is called with (RFC 6749 §4.1.3), as one comparable value. */
data class TokenExchange(
    val metadata: OAuthMetadata,
    val code: String,
    val redirectUri: String,
    val codeVerifier: String,
)

/**
 * What to POST for a redirect that earned an exchange — the PROVENANCE of the four arguments, as a
 * function a test can run.
 *
 * The verifier is the one THIS request drew, and nothing else will do. "There is a verifier in
 * the call" is not the rule: a freshly drawn one (`Pkce.newCodeVerifier()`) is non-blank, passes
 * `PendingAuthorization`'s guard, reads perfectly well at the call site — and turns PKCE into
 * decoration, because the challenge the server holds was derived from the other one. That is why
 * this is a value returned by a function and compared whole in a test, instead of four arguments
 * spelled out at a call site where only their shape can be checked.
 */
fun tokenExchangeFor(verdict: RedirectVerdict.Exchange, redirectUri: String): TokenExchange =
    TokenExchange(
        metadata = verdict.request.metadata,
        code = verdict.code,
        redirectUri = redirectUri,
        codeVerifier = verdict.request.codeVerifier,
    )

/**
 * The one in-flight request, and the rule that it is redeemed ONCE.
 *
 * An authorization code is a one-shot key to the account and the browser can deliver the same
 * redirect twice (a re-tap on a history entry, an activity recreated from the same intent). So the
 * request is cleared as soon as it has produced a verdict of its own — the second identical return
 * finds nothing in flight and exchanges nothing.
 *
 * Except on the two verdicts that mean "this redirect was not our sign-in": with nothing armed
 * there is nothing to clear, and on a state mismatch clearing would let anything able to open a
 * link cancel the user's real sign-in from outside.
 */
class AuthorizationRequestSlot {
    private var pending: PendingAuthorization? = null

    @Synchronized
    fun arm(request: PendingAuthorization) {
        pending = request
    }

    @Synchronized
    fun disarm() {
        pending = null
    }

    @Synchronized
    fun isArmed(): Boolean = pending != null

    /** The verdict for this redirect, consuming the request unless it was not ours. */
    @Synchronized
    fun redeem(state: String?, code: String?, error: String?): RedirectVerdict {
        val verdict = redirectVerdict(pending, state, code, error)
        val keep = verdict is RedirectVerdict.NoPendingRequest || verdict is RedirectVerdict.StateMismatch
        if (!keep) pending = null
        return verdict
    }
}

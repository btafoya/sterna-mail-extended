package app.sterna.ui.connect

import android.content.Context
import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthMetadata

/** A message to show, as the resource to look up plus the one argument it formats, if any. */
data class OAuthMessage(@StringRes val resId: Int, val arg: String? = null)

/**
 * The decision behind [oauthFailureMessage], as a pure function: no `Context`, no `ViewModel`, so
 * a unit test can EXECUTE it instead of reading the code that contains it.
 *
 * [error] is the OAuth protocol's own word for the refusal (RFC 6749 §5.2), plus two of ours:
 * `http_<status>` when the server sent no `error` field, and `network_error` when nothing was
 * reached. It is shown to the reader — a closed vocabulary is safe to show.
 *
 * ⛔ [description] is READ (an admin-consent refusal only identifies itself there) and never
 * SHOWN. It is free text written by the server, on endpoints whose payload is the reader's own
 * tokens, and it used to be rendered verbatim.
 */
fun oauthFailureSpec(
    error: String,
    description: String,
    aadstsCode: String?,
    @StringRes fallbackRes: Int = R.string.connect_oauth_denied,
): OAuthMessage = when {
    error == "authorization_declined" || error == "access_denied" ->
        OAuthMessage(R.string.connect_oauth_declined)
    error == "expired_token" ->
        OAuthMessage(R.string.connect_oauth_expired)
    // Unverified-publisher / admin-consent / app-not-approved → the org must approve, or app password.
    // ⛔ Gated on an AADSTS code, and it must stay gated: this sentence NAMES MICROSOFT, while
    // `description` is prose from whatever server answered. A self-hosted Stalwart / Keycloak /
    // Authelia replying "invalid_client, contact your administrator" matches `admin` and would be
    // told to ask Microsoft for approval. Every real Microsoft consent refusal carries a code.
    aadstsCode != null && (
        aadstsCode == "AADSTS650051" || aadstsCode == "AADSTS90094" || aadstsCode == "AADSTS65001" ||
            description.contains("admin", ignoreCase = true) ||
            description.contains("consent", ignoreCase = true) ||
            description.contains("verified publisher", ignoreCase = true) ||
            description.contains("not been approved", ignoreCase = true)
        ) ->
        OAuthMessage(R.string.connect_oauth_admin_consent)
    aadstsCode != null -> OAuthMessage(R.string.connect_oauth_error_code, aadstsCode)
    error == "network_error" -> OAuthMessage(R.string.connect_oauth_network)
    // The server refused and said why: say why — but only in the protocol's own words. `error` is
    // `obj["error"]` off the wire, i.e. server text of arbitrary length and content, and it is now
    // wired to the screen: without this gate the leak we closed on `description` simply moves one
    // field over. What does not look like a keyword falls back instead of being rendered.
    OAUTH_ERROR_TOKEN.matches(error) -> OAuthMessage(R.string.connect_oauth_error_reason, error)
    else -> OAuthMessage(fallbackRes)
}

/** RFC 6749 §5.2 error codes are `*( %x20-21 / %x23-5B / %x5D-7E )` in practice lower snake_case,
 *  plus the `http_<status>` tokens this app makes up when the server sent no `error` field. */
private val OAUTH_ERROR_TOKEN = Regex("""^[a-z][a-z0-9_]{0,39}$|^http_\d{3}$""")

/** The grant a discovered server is signed into with, in the order this app prefers them. */
enum class OAuthGrant {
    /** RFC 8628. Nothing leaves the app: a code is typed on another screen. */
    DEVICE_CODE,

    /** RFC 6749 §4.1 + PKCE. A browser round-trip, coming back on `<applicationId>://oauth`. */
    AUTHORIZATION_CODE,

    /** Neither: the server advertises OAuth this app cannot drive. */
    NONE,
}

/**
 * Which grant to drive against [metadata] — as a pure function, so the choice is EXECUTED by a
 * test rather than read off the screen that acts on it.
 *
 * ⛔ The device flow wins whenever a server offers both, and that ordering is the point of the
 * function. It is the path in production today, it never leaves the app (so no browser, no
 * redirect, no chance of coming back into a dead process), and every server signed into so far
 * uses it. The authorization code grant exists for servers that have NO device endpoint at all.
 */
fun chooseOAuthGrant(metadata: OAuthMetadata): OAuthGrant = when {
    metadata.supportsDeviceFlow -> OAuthGrant.DEVICE_CODE
    metadata.supportsCodeFlow -> OAuthGrant.AUTHORIZATION_CODE
    else -> OAuthGrant.NONE
}

/**
 * Probe [candidates] in order and COLLECT what answers — the search that feeds [chooseOAuthHost].
 *
 * ⚠ COLLECT, don't stop at the first answer. `Jmap.autodiscoverHosts` tries the BARE DOMAIN first
 * — the organisation's website — so a site that publishes an OAuth document would end the search
 * before `mail.<domain>` is ever asked, and refuse a sign-in that worked the day before.
 *
 * ⛔ ONLY a device flow ends the search, and that is not a leftover. A bare domain publishing an
 * `authorization_endpoint` (any site behind an SSO portal does) would otherwise stop the probe
 * here, and the mail server that carries the device flow would never be asked: someone who signed
 * in yesterday without leaving the app is sent to a browser, to the WEBSITE's identity provider,
 * which does not issue tokens for the mail server. The code grant is the fallback for when NO
 * candidate has a device flow, and [chooseOAuthHost] is where that ranking happens — after every
 * candidate has been given its chance to answer. Probing one host past a code-only answer is the
 * price of that promise.
 *
 * Extracted from the connect screen so the loop can be RUN by a test with a fake probe: which
 * hosts get asked is the decision, and it is invisible in the list the screen ends up with.
 */
suspend fun collectOAuthHosts(
    candidates: List<String>,
    discover: suspend (String) -> OAuthMetadata?,
): List<Pair<String, OAuthMetadata>> {
    val discovered = mutableListOf<Pair<String, OAuthMetadata>>()
    for (host in candidates) {
        val metadata = runCatching { discover(host) }.getOrNull() ?: continue
        discovered += host to metadata
        if (metadata.supportsDeviceFlow) break
    }
    return discovered
}

/**
 * Which discovered host the sign-in goes to: the first that speaks the device flow, then the first
 * that speaks the code grant, and failing both the first that answered at all.
 *
 * ⚠ The fallback is not decoration. `Jmap.autodiscoverHosts` tries the BARE DOMAIN first — the
 * organisation's website, the least likely of the four to be the mail server — so stopping at the
 * first host that merely advertises OAuth turns a working sign-in into "this server offers OAuth,
 * but not in a way Sterna can use yet" for anyone whose site publishes an OAuth document and whose
 * mail lives on `mail.<domain>`. And when NO candidate has a usable grant, the first responder is
 * what makes that sentence true rather than "this server doesn't support OAuth sign-in".
 *
 * ⛔ The two capability passes are in [OAuthGrant]'s own order and both read it through
 * [chooseOAuthGrant], so "which grant do we prefer" is stated once: a device flow on `mail.…`
 * still wins over a code grant on the bare domain.
 */
fun chooseOAuthHost(discovered: List<Pair<String, OAuthMetadata>>): Pair<String, OAuthMetadata>? =
    discovered.firstOrNull { chooseOAuthGrant(it.second) == OAuthGrant.DEVICE_CODE }
        ?: discovered.firstOrNull { chooseOAuthGrant(it.second) == OAuthGrant.AUTHORIZATION_CODE }
        ?: discovered.firstOrNull()

/**
 * null when discovery found a server this app can actually sign into; otherwise what to show.
 *
 * The two failures are different facts and used to render the same sentence: a server with no
 * OAuth at all, and a server that offers OAuth but no grant this app can drive — the second was
 * being told it "doesn't support OAuth sign-in", which is simply false.
 *
 * ⚠ "A grant this app can drive" is [chooseOAuthGrant]'s answer and no longer a device endpoint:
 * a server publishing only an authorization endpoint is signed into through the code grant (#55),
 * so it is not a failure at all. Only a server with NEITHER endpoint keeps the flow sentence.
 */
@StringRes
fun oauthDiscoveryFailure(metadata: OAuthMetadata?): Int? = when {
    metadata == null -> R.string.connect_oauth_unsupported
    chooseOAuthGrant(metadata) == OAuthGrant.NONE -> R.string.connect_oauth_flow_unsupported
    else -> null
}

/**
 * Map a device-flow failure to a specific, actionable message. Microsoft refusals carry an AADSTS
 * code / description in [DeviceTokenResult.Failed]; unverified-publisher and admin-consent refusals
 * point the user at asking their admin or using an app password instead of a dead-end "declined".
 * Shared by the first-run import sign-in and the account-settings sign-in.
 */
fun oauthFailureMessage(context: Context, failure: DeviceTokenResult.Failed): String {
    val spec = oauthFailureSpec(
        error = failure.error,
        description = failure.description,
        aadstsCode = failure.aadstsCode,
    )
    return if (spec.arg == null) context.getString(spec.resId)
    else context.getString(spec.resId, spec.arg)
}

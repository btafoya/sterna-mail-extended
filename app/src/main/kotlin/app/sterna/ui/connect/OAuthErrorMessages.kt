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

/**
 * Which discovered host the sign-in goes to: the first that speaks the device flow, and failing
 * that the first that answered at all.
 *
 * ⚠ The fallback is not decoration. `Jmap.autodiscoverHosts` tries the BARE DOMAIN first — the
 * organisation's website, the least likely of the four to be the mail server — so stopping at the
 * first host that merely advertises OAuth turns a working sign-in into "this server offers OAuth,
 * but not in a way Sterna can use yet" for anyone whose site publishes an OAuth document and whose
 * mail lives on `mail.<domain>`. And when NO candidate has a device flow, the first responder is
 * what makes that sentence true rather than "this server doesn't support OAuth sign-in".
 */
fun chooseOAuthHost(discovered: List<Pair<String, OAuthMetadata>>): Pair<String, OAuthMetadata>? =
    discovered.firstOrNull { it.second.supportsDeviceFlow } ?: discovered.firstOrNull()

/**
 * null when discovery found a server this app can actually sign into; otherwise what to show.
 *
 * The two failures are different facts and used to render the same sentence: a server with no
 * OAuth at all, and a server that offers OAuth but no device-authorization endpoint — the second
 * was being told it "doesn't support OAuth sign-in", which is simply false.
 */
@StringRes
fun oauthDiscoveryFailure(metadata: OAuthMetadata?): Int? = when {
    metadata == null -> R.string.connect_oauth_unsupported
    !metadata.supportsDeviceFlow -> R.string.connect_oauth_flow_unsupported
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

package app.sterna.ui.connect

import app.sterna.core.jmap.Jmap
import app.sterna.core.jmap.JmapException
import app.sterna.core.jmap.OAuthMetadata

data class CodeGrantClient(val clientId: String, val scope: String)

/** Who a code-grant sign-in at a discovered server asks as. A server publishing a registration
 *  endpoint refuses Sterna's fixed id (Stalwart 1.0), so [register] runs, for that case only, and
 *  its failure is the caller's to say: falling back on `sterna` there is a sure `invalid_client`. */
suspend fun codeGrantClientFor(metadata: OAuthMetadata, register: suspend () -> String): CodeGrantClient =
    if (metadata.asksRegistration) {
        CodeGrantClient(clientId = register(), scope = Jmap.OAUTH_REGISTERED_SCOPE)
    } else {
        CodeGrantClient(clientId = Jmap.OAUTH_CLIENT_ID, scope = Jmap.OAUTH_SCOPE)
    }

/** How a sign-in at a discovered server proceeds, once any registration has answered. */
sealed interface OAuthRoute {
    data class Code(val client: CodeGrantClient) : OAuthRoute
    data object Device : OAuthRoute
}

/** The route for [metadata]: [chooseOAuthGrant]'s grant, [codeGrantClientFor]'s client on the code
 *  grant. Anything but the code grant is the device flow; `oauthDiscoveryFailure` has turned a
 *  document without a drivable grant away before. A failed registration is thrown to the caller,
 *  except a refusal to register anonymously, after which the document is taken as if it published
 *  no registration endpoint: [register] is never asked twice. */
suspend fun oauthRouteFor(metadata: OAuthMetadata, register: suspend () -> String): OAuthRoute {
    if (chooseOAuthGrant(metadata) != OAuthGrant.AUTHORIZATION_CODE) return OAuthRoute.Device
    val client = try {
        codeGrantClientFor(metadata, register)
    } catch (refused: JmapException) {
        if (!refusesAnonymousRegistration(refused)) throw refused
        return oauthRouteFor(metadata.copy(registrationEndpoint = null)) { error("registration already refused") }
    }
    return OAuthRoute.Code(client)
}

/** RFC 7591 § 3: a server may require an initial access token to register, which Sterna never holds.
 *  Stalwart before 1.0 publishes its endpoint so and answers 401, yet signs `sterna` in as before;
 *  any other refusal is a real one, and falling back there would hide it behind `invalid_client`. */
private fun refusesAnonymousRegistration(refused: JmapException): Boolean =
    refused.httpCode == 401 || refused.httpCode == 403

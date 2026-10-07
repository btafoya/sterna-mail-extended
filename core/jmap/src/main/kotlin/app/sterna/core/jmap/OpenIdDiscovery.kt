package app.sterna.core.jmap

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Discovery at an OpenID Connect issuer the user typed, see [OAuthClient.discoverAtIssuer]. */
object OpenIdDiscovery {
    /** Well-known suffix of an OpenID Provider's configuration (OpenID Connect Discovery §4). */
    const val CONFIGURATION_PATH = "/.well-known/openid-configuration"

    /**
     * What a sign-in at a typed issuer asks for: who signed in, and a refresh token. Not
     * [Jmap.OAUTH_SCOPE]: its JMAP URNs are a mail server's vocabulary, and an identity provider
     * answers a scope it does not know with `invalid_scope`.
     */
    const val SCOPE = "openid email offline_access"

    /**
     * The issuer a user typed, in the form a document's `issuer` is compared to, or null when it
     * cannot be one. A missing scheme means https, as in [Jmap.sessionUrlFor]. A written
     * `http:` is refused, never upgraded: the user asked for cleartext and is told no.
     * A pasted configuration URL stands for its issuer.
     */
    fun issuerOf(issuerInput: String): String? {
        var s = issuerInput.trim()
        if (s.isEmpty()) return null
        if (!WRITTEN_SCHEME.containsMatchIn(s)) s = "https://$s"
        return canonicalOpenIdIssuer(s.trimEnd('/').removeSuffix(CONFIGURATION_PATH))
    }

    /**
     * Where the configuration of a typed issuer is fetched, or null when [issuerOf] refuses it.
     * The well-known path goes after the issuer's own path (OpenID Connect Discovery §4.1).
     * RFC 8414 inserts it before, which is why [Jmap.oauthMetadataUrlFor] cannot serve here.
     */
    fun configurationUrlFor(issuerInput: String): String? =
        issuerOf(issuerInput)?.let { "$it$CONFIGURATION_PATH" }

    /**
     * A scheme, then a slash: one slash is enough, so that `http:/host` is not taken for a host
     * named `http`. The slash keeps `host:8443/path` on the other side, a port and no scheme.
     */
    private val WRITTEN_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:/")
}

/**
 * An issuer in the form two of them are compared in (OpenID Connect Discovery §4.3): host case
 * and default port normalised, trailing slashes dropped, the path as OkHttp resolves it. Null for
 * anything but https, and for a URL carrying a query, a fragment or credentials.
 * Strict, unlike [OpenIdDiscovery.issuerOf]: it also judges the `issuer` a document claims, and
 * a server's own word gets no scheme supplied for it.
 */
internal fun canonicalOpenIdIssuer(issuer: String): String? {
    val url = issuer.toHttpUrlOrNull() ?: return null
    if (!url.isHttps) return null
    if (url.query != null || url.fragment != null) return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.toString().trimEnd('/')
}

/**
 * What is left of a fetched configuration once judged against [typedIssuer], a value of
 * [OpenIdDiscovery.issuerOf], or null when no sign-in can be driven from it.
 *
 * The typed issuer is the only input besides the document, on purpose. The fetch follows 3xx, so
 * the document may have been served by another host, and nothing about the response reaches here:
 * a redirect cannot choose who is trusted. Nor is there an address domain: the second owner of
 * [oauthEndpointAllowed] is left empty, and an empty owner owns nothing.
 */
internal fun guardedOpenIdConfiguration(found: OAuthMetadata, typedIssuer: String): OAuthMetadata? {
    val typedHost = typedIssuer.toHttpUrlOrNull()?.host ?: return null
    // OpenID Connect Discovery §4.3: the document must be about the issuer that was asked. An
    // absent `issuer` decodes to "", which is no issuer and fails the same comparison.
    if (canonicalOpenIdIssuer(found.issuer) != typedIssuer) return null

    fun allowed(endpoint: String?) = oauthEndpointAllowed(endpoint.orEmpty(), typedHost, "")
    val device = found.deviceAuthorizationEndpoint
    // The device endpoint is guarded like the other two but decides nothing: usable means the
    // authorization and token endpoints both survived. It is then the only one left that can
    // name another host in a document that is returned at all.
    return found.copy(
        authorizationEndpoint = found.authorizationEndpoint?.takeIf { allowed(it) },
        tokenEndpoint = found.tokenEndpoint.takeIf { allowed(it) }.orEmpty(),
        deviceAuthorizationEndpoint = device?.takeIf { allowed(it) },
        endpointsOffDomain = !device.isNullOrBlank() &&
            oauthEndpointNamesAnotherHost(device, typedHost, ""),
    ).takeIf { it.supportsCodeFlow }
}

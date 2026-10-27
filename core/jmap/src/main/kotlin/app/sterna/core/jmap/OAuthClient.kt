package app.sterna.core.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit

/** OAuth 2.0 authorization-server metadata (RFC 8414), trimmed to what we use. */
@Serializable
data class OAuthMetadata(
    val issuer: String = "",
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String = "",
    @SerialName("device_authorization_endpoint") val deviceAuthorizationEndpoint: String? = null,
    @SerialName("registration_endpoint") val registrationEndpoint: String? = null,
        /**
         * Set by [OAuthClient.discoverMetadata] when a named endpoint was blanked for its host, the
         * document sent the sign-in to somewhere we will not carry tokens. Never read off the wire.
         */
    @kotlinx.serialization.Transient val endpointsOffDomain: Boolean = false,
) {
    /** True when this server supports the Device Authorization Grant (RFC 8628). */
    val supportsDeviceFlow: Boolean
        get() = !deviceAuthorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()

    /** True when this server supports the Authorization Code Grant (RFC 6749 §4.1). */
    val supportsCodeFlow: Boolean
        get() = !authorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()

    val asksRegistration: Boolean
        get() = !registrationEndpoint.isNullOrBlank()
}

/** Response to a device-authorization request (RFC 8628 §3.2). */
@Serializable
data class DeviceAuthorization(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int = 1800,
    val interval: Int = 5,
)

/** A successful token grant (RFC 6749 §5.1). */
@Serializable
data class OAuthTokens(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "Bearer",
    /** Present when the `openid` scope was requested; carries the signed-in identity. */
    @SerialName("id_token") val idToken: String? = null,
    /** The scopes actually granted (may differ from those requested). */
    @SerialName("scope") val scope: String? = null,
)

@Serializable
internal data class ClientRegistration(
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("grant_types") val grantTypes: List<String>,
    @SerialName("response_types") val responseTypes: List<String>,
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String,
    val scope: String,
    @SerialName("client_name") val clientName: String,
)

@Serializable
internal data class RegisteredClient(@SerialName("client_id") val clientId: String = "")

/** A parsed OAuth error response (RFC 6749 §5.2). [aadstsCode] is the first AADSTS code
 *  found in [description], when present (Microsoft), e.g. "AADSTS650051". */
data class OAuthError(
    val error: String,
    val description: String = "",
    val aadstsCode: String? = null,
)

/** One device-token poll outcome (RFC 8628 §3.5). */
sealed interface DeviceTokenResult {
    data class Success(val tokens: OAuthTokens) : DeviceTokenResult
    /** `authorization_pending`, the user hasn't approved yet; keep polling. */
    data object Pending : DeviceTokenResult
    /** `slow_down`, poll less often (increase the interval by 5s, per RFC). */
    data object SlowDown : DeviceTokenResult
    /** Terminal failure: `expired_token`, `access_denied`, or an HTTP/transport error.
     *  [description] and [aadstsCode] carry the server's `error_description` detail when present. */
    data class Failed(
        val error: String,
        val description: String = "",
        val aadstsCode: String? = null,
    ) : DeviceTokenResult
}

    /**
     * PKCE, Proof Key for Code Exchange (RFC 7636). A public client cannot keep a secret, so PKCE
     */
object Pkce {
    /** The only method we announce. `plain` sends the verifier itself through the browser. */
    const val CODE_CHALLENGE_METHOD = "S256"

    /** RFC 7636 §4.1 `unreserved` = alpha / DIGIT / "-" / "." / "_" / "~". */
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /**
         * One instance, seeded by the OS; `SecureRandom.nextInt(bound)` is unbiased.
         */
    internal val random: SecureRandom = SecureRandom()

    /** A fresh `code_verifier`: [length] characters of `unreserved`, 43..128 (RFC 7636 §4.1). */
    fun newCodeVerifier(length: Int = 64): String {
        require(length in 43..128) { "a code verifier is 43 to 128 characters (RFC 7636 §4.1)" }
        val out = StringBuilder(length)
        repeat(length) { out.append(UNRESERVED[random.nextInt(UNRESERVED.length)]) }
        return out.toString()
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded, RFC 7636 §4.2. */
    fun codeChallengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
}

    /**
     * Build the URL the browser is sent to (RFC 6749 §4.1.1). Pure: String in, String out, no I/O.
     */
fun buildAuthorizationUrl(
    authorizationEndpoint: String,
    clientId: String,
    redirectUri: String,
    scope: String,
    state: String,
    codeChallenge: String,
): String {
    // Neither the endpoint nor any parameter goes into the message: this is the same rule the
    // POSTs below follow, and the caller already knows what it passed in.
    val base = authorizationEndpoint.toHttpUrlOrNull()
        ?: throw JmapException("The authorization endpoint is not a usable URL")
    if (base.scheme != "https") {
        throw JmapException("The authorization endpoint must be https")
    }
    val builder = base.newBuilder()
    GRANT_PARAMETERS.forEach { builder.removeAllQueryParameters(it) }
    return builder
        .addEncodedQueryParameter("response_type", percentEncode("code"))
        .addEncodedQueryParameter("client_id", percentEncode(clientId))
        .addEncodedQueryParameter("redirect_uri", percentEncode(redirectUri))
        .addEncodedQueryParameter("scope", percentEncode(scope))
        .addEncodedQueryParameter("state", percentEncode(state))
        .addEncodedQueryParameter("code_challenge", percentEncode(codeChallenge))
        .addEncodedQueryParameter("code_challenge_method", percentEncode(Pkce.CODE_CHALLENGE_METHOD))
        .build()
        .toString()
}

/** The seven names the grant owns, also the ones taken off the endpoint's own query. */
private val GRANT_PARAMETERS = listOf(
    "response_type",
    "client_id",
    "redirect_uri",
    "scope",
    "state",
    "code_challenge",
    "code_challenge_method",
)

    /**
     * Add `login_hint` (OIDC Core §3.1.2.1) to a device-flow verification URL, so the approval page
     */
fun withLoginHint(verificationUri: String, loginHint: String): String {
    if (loginHint.isBlank()) return verificationUri
    val url = verificationUri.toHttpUrlOrNull() ?: return verificationUri
    if (url.scheme != "https") return verificationUri
    if (LOGIN_HINT in url.queryParameterNames) return verificationUri
    return url.newBuilder()
        .addEncodedQueryParameter(LOGIN_HINT, percentEncode(loginHint))
        .build()
        .toString()
}

/** The hint's name (OIDC Core §3.1.2.1), also the one the server may have written itself. */
private const val LOGIN_HINT = "login_hint"

/**
 * RFC 3986 percent-encoding: everything outside `unreserved` becomes `%XX` over its UTF-8 bytes.
 * A space is `%20` and never `+`, see [buildAuthorizationUrl]. Uppercase hex, no locale involved.
 */
private fun percentEncode(value: String): String {
    val out = StringBuilder(value.length)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val code = byte.toInt() and 0xFF
        val char = code.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") {
            out.append(char)
        } else {
            out.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"

    /** The client every OAuth POST uses, built here, and not the shared JMAP one. */
internal fun defaultOAuthHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

    /**
     * Whether a discovery document may name [endpoint]: only over https, and only on [queriedHost]
     */
internal fun oauthEndpointAllowed(endpoint: String, queriedHost: String, addressDomain: String): Boolean {
    val url = endpoint.toHttpUrlOrNull() ?: return false
    if (url.scheme != "https") return false
    val host = url.host.lowercase()
    return underOwner(host, queriedHost) || underOwner(host, addressDomain)
}

    /**
     * Whether [endpoint] names a host that is neither under [queriedHost] nor under [addressDomain]:
     * the one of [oauthEndpointAllowed]'s three refusals that is about who, and the only one the
     * connect screen may describe as the server handing sign-in elsewhere.
     */
internal fun oauthEndpointNamesAnotherHost(
    endpoint: String,
    queriedHost: String,
    addressDomain: String,
): Boolean {
    val url = endpoint.toHttpUrlOrNull() ?: return false
    val host = url.host.lowercase()
    return !underOwner(host, queriedHost) && !underOwner(host, addressDomain)
}

/** [host] is [owner] or a subdomain of it. An empty [owner] owns nothing: never `h.endsWith(".")`. */
private fun underOwner(host: String, owner: String): Boolean {
    val own = owner.lowercase()
    if (own.isEmpty()) return false
    return host == own || host.endsWith(".$own")
}

    /**
     * Minimal OAuth 2.0 client for the Device Authorization Grant (RFC 8628), the Authorization Code
     */
class OAuthClient internal constructor(
    /** Metadata discovery only: follows 3xx, on purpose. See [defaultOAuthHttpClient]. */
    private val discoveryHttpClient: OkHttpClient,
    /** Every OAuth POST, the four that carry a secret and the registration. One field, so none can be forgotten. */
    private val tokenHttpClient: OkHttpClient,
    private val json: Json,
) {
    /** Public constructor for app code, shared client for discovery, dedicated one for the POSTs. */
    constructor() : this(JmapClient.defaultHttpClient(), defaultOAuthHttpClient(), JmapClient.DefaultJson)

        /** Fetch the server's OAuth metadata, or null if it advertises none. */
    suspend fun discoverMetadata(host: String, addressDomain: String): OAuthMetadata? = withContext(Dispatchers.IO) {
        val metadataUrl = Jmap.oauthMetadataUrlFor(host)
        val request = Request.Builder()
            .url(metadataUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        val found = runCatching {
            discoveryHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<OAuthMetadata>(response.body?.string().orEmpty())
            }
        }.getOrNull() ?: return@withContext null
        if (found.tokenEndpoint.isBlank()) return@withContext null

            // The host actually asked, read off the URL actually built: [host] may be a bare host or a
            // full URL. Unreadable leaves [addressDomain] as the only owner, the safe side.
        val queriedHost = metadataUrl.toHttpUrlOrNull()?.host.orEmpty()
        fun allowed(endpoint: String?) = oauthEndpointAllowed(endpoint.orEmpty(), queriedHost, addressDomain)
            // The fact is narrower than the refusal: only a host that is someone else's raises it.
            // A cleartext endpoint on our own host is blanked the same and says nothing about who.
        fun elsewhere(endpoint: String?) = !endpoint.isNullOrBlank() &&
            oauthEndpointNamesAnotherHost(endpoint, queriedHost, addressDomain)
        found.copy(
            authorizationEndpoint = found.authorizationEndpoint?.takeIf { allowed(it) },
            tokenEndpoint = found.tokenEndpoint.takeIf { allowed(it) }.orEmpty(),
            deviceAuthorizationEndpoint = found.deviceAuthorizationEndpoint?.takeIf { allowed(it) },
            // Left out of `endpointsOffDomain`: registration carries no token, a foreign one is only dropped.
            registrationEndpoint = found.registrationEndpoint?.takeIf { allowed(it) },
            endpointsOffDomain = elsewhere(found.tokenEndpoint) ||
                elsewhere(found.authorizationEndpoint) ||
                elsewhere(found.deviceAuthorizationEndpoint),
        )
    }

    suspend fun discoverAtIssuer(issuer: String): OAuthMetadata? = withContext(Dispatchers.IO) {
        val typedIssuer = OpenIdDiscovery.issuerOf(issuer) ?: return@withContext null
        val configurationUrl = OpenIdDiscovery.configurationUrlFor(issuer) ?: return@withContext null
        val request = Request.Builder()
            .url(configurationUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        val found = runCatching {
            discoveryHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<OAuthMetadata>(response.body?.string().orEmpty())
            }
        }.getOrNull() ?: return@withContext null
        guardedOpenIdConfiguration(found, typedIssuer)
    }

    /** Begin the device flow: ask the server for a user code + verification URL. */
    suspend fun startDeviceAuthorization(
        metadata: OAuthMetadata,
        clientId: String,
        scope: String,
    ): DeviceAuthorization = withContext(Dispatchers.IO) {
        val endpoint = metadata.deviceAuthorizationEndpoint
            ?: throw JmapException("Server has no device-authorization endpoint")
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", scope)
            .build()
        val request = Request.Builder().url(endpoint).post(form).header("Accept", "application/json").build()
        tokenHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                    // The `error` field only, RFC 6749 §5.2's short closed vocabulary: neither the body
                    // nor `error_description` goes into the message or the cause, this endpoint's
                    // payload is a device code, and a proxy's error page is prose.
                throw JmapException(
                    "Device authorization failed: HTTP ${response.code}",
                    httpCode = response.code,
                    oauthError = parseError(body)?.error,
                )
            }
            decodeGuarded(body, "device authorization", DeviceAuthorization.serializer())
        }
    }

    /** Register Sterna as a public client (RFC 7591 §3.1) and return the `client_id` it was issued. */
    suspend fun registerClient(
        metadata: OAuthMetadata,
        redirectUri: String,
        scope: String,
    ): String = withContext(Dispatchers.IO) {
        // Not only at discovery: an OAuthMetadata can be built by hand, and this payload carries the redirect.
        val endpoint = metadata.registrationEndpoint?.toHttpUrlOrNull()
            ?: throw JmapException("Server has no usable client registration endpoint")
        if (!endpoint.isHttps) throw JmapException("The client registration endpoint must be https")
        val registration = ClientRegistration(
            redirectUris = listOf(redirectUri),
            grantTypes = listOf("authorization_code", "refresh_token"),
            responseTypes = listOf("code"),
            tokenEndpointAuthMethod = "none",
            scope = scope,
            clientName = "Sterna",
        )
        val payload = json.encodeToString(ClientRegistration.serializer(), registration)
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(endpoint).post(payload).header("Accept", "application/json").build()
        tokenHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw JmapException(
                    "Client registration failed: HTTP ${response.code}",
                    httpCode = response.code,
                    oauthError = parseError(body)?.error,
                )
            }
            decodeGuarded(body, "client registration", RegisteredClient.serializer()).clientId
                .takeIf { it.isNotBlank() }
                ?: throw JmapException("The client registration response carries no client id")
        }
    }

    /** Poll the token endpoint once for the device grant. */
    suspend fun pollDeviceToken(
        metadata: OAuthMetadata,
        deviceCode: String,
        clientId: String,
    ): DeviceTokenResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .add("device_code", deviceCode)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder().url(metadata.tokenEndpoint).post(form).header("Accept", "application/json").build()
            // Caught by type, IOException only. Catching by scope made a 200 whose body this client
            // cannot read report itself as a dead network. Catching by type also keeps a
            // CancellationException (leaving the screen mid-poll) passing.
        try {
            tokenHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    DeviceTokenResult.Success(decodeGuarded(body, "token", OAuthTokens.serializer()))
                } else {
                    val parsed = parseError(body)
                    when (parsed?.error) {
                        "authorization_pending" -> DeviceTokenResult.Pending
                        "slow_down" -> DeviceTokenResult.SlowDown
                        else -> DeviceTokenResult.Failed(
                            error = parsed?.error ?: "http_${response.code}",
                            description = parsed?.description.orEmpty(),
                            aadstsCode = parsed?.aadstsCode,
                        )
                    }
                }
            }
            // A fixed token, not `e.message`: OkHttp names the URL, the host and the port in it, and
            // the connect screen displays this field. The caller only needs to know the exchange never
            // reached a server.
        } catch (_: IOException) {
            DeviceTokenResult.Failed("network_error")
        }
    }

        /**
         * Exchange an authorization code for tokens (RFC 6749 §4.1.3), proving PKCE possession.
         */
    suspend fun exchangeCode(
        metadata: OAuthMetadata,
        code: String,
        redirectUri: String,
        clientId: String,
        codeVerifier: String,
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()
        val request = Request.Builder().url(metadata.tokenEndpoint).post(form).header("Accept", "application/json").build()
        tokenHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                    // Same halves as `startDeviceAuthorization`: the protocol's `error` word travels,
                    // the body and `error_description` never do, this payload is the account's tokens.
                throw JmapException(
                    "Code exchange failed: HTTP ${response.code}",
                    httpCode = response.code,
                    oauthError = parseError(body)?.error,
                )
            }
            decodeGuarded(body, "token", OAuthTokens.serializer())
        }
    }

    /** Exchange a refresh token for a fresh access token (RFC 6749 §6). */
    suspend fun refresh(
        tokenEndpoint: String,
        refreshToken: String,
        clientId: String,
    ): OAuthTokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", clientId)
            .build()
        val request = Request.Builder().url(tokenEndpoint).post(form).header("Accept", "application/json").build()
        tokenHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // RFC 6749 §5.2 refuses with 400/401; a 5xx `error` is a proxy's, not "add the account again".
                throw JmapException(
                    "Token refresh failed: HTTP ${response.code}",
                    httpCode = response.code,
                    oauthError = if (response.code in 400..499) parseError(body)?.error else null,
                )
            }
            decodeGuarded(body, "token", OAuthTokens.serializer())
        }
    }

        /**
         * Decode an OAuth response without letting the payload into the failure.
         */
    private fun <T> decodeGuarded(body: String, what: String, serializer: KSerializer<T>): T =
        try {
            json.decodeFromString(serializer, body)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is what
            // decodeFromString raises on a structurally wrong document.
            throw JmapException("Could not decode the $what response")
        }

    internal fun parseError(body: String): OAuthError? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val error = obj["error"]?.jsonPrimitive?.content ?: return@runCatching null
        val description = obj["error_description"]?.jsonPrimitive?.content.orEmpty()
        OAuthError(error, description, AADSTS_REGEX.find(description)?.value)
    }.getOrNull()

    private companion object {
        private val AADSTS_REGEX = Regex("AADSTS\\d+")
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

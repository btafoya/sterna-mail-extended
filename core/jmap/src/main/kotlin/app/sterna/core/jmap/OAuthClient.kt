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
import okhttp3.OkHttpClient
import okhttp3.Request
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
    /**
     * Set by [OAuthClient.discoverMetadata] when a named endpoint was blanked FOR ITS HOST — the
     * document sent the sign-in to somewhere we will not carry tokens. Never read off the wire.
     *
     * Narrower than "an endpoint was refused", and deliberately: [oauthEndpointAllowed] also
     * refuses cleartext and an unreadable URL, and a server naming `http://mail.<your domain>`
     * has named no one else. The connect screen turns this into a sentence that says the server
     * hands sign-in to another domain, so the fact has to be exactly that and nothing wider.
     */
    @kotlinx.serialization.Transient val endpointsOffDomain: Boolean = false,
) {
    /** True when this server supports the Device Authorization Grant (RFC 8628). */
    val supportsDeviceFlow: Boolean
        get() = !deviceAuthorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()

    /** True when this server supports the Authorization Code Grant (RFC 6749 §4.1). */
    val supportsCodeFlow: Boolean
        get() = !authorizationEndpoint.isNullOrBlank() && tokenEndpoint.isNotBlank()
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
    /** `authorization_pending` — the user hasn't approved yet; keep polling. */
    data object Pending : DeviceTokenResult
    /** `slow_down` — poll less often (increase the interval by 5s, per RFC). */
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
 * PKCE — Proof Key for Code Exchange (RFC 7636).
 *
 * A public client cannot keep a secret, so the authorization code it gets back through the browser
 * is all that stands between an eavesdropper and an account. PKCE ties the code to the app that
 * started the flow: a random verifier is kept in memory, only its SHA-256 digest travels in the
 * browser, and the token exchange proves possession of the original.
 *
 * Pure functions on purpose: no `Context`, no `OkHttpClient`, so the derivation can be pinned on
 * the RFC's own test vector (appendix B) instead of being re-derived by a test.
 */
object Pkce {
    /** The only method we announce. `plain` sends the verifier itself through the browser. */
    const val CODE_CHALLENGE_METHOD = "S256"

    /** RFC 7636 §4.1 `unreserved` = ALPHA / DIGIT / "-" / "." / "_" / "~". */
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /**
     * One instance, seeded by the OS. `SecureRandom.nextInt(bound)` is unbiased.
     *
     * The TYPE IS DECLARED, so swapping in a `java.util.Random` does not compile — and
     * `internal` (invisible outside this module) lets the test read the source and pin it. A
     * predictable verifier makes PKCE decorative: whoever intercepts the code coming back through
     * the browser computes the verifier and redeems the code. "Two draws differ" does not see it.
     */
    internal val random: SecureRandom = SecureRandom()

    /** A fresh `code_verifier`: [length] characters of `unreserved`, 43..128 (RFC 7636 §4.1). */
    fun newCodeVerifier(length: Int = 64): String {
        require(length in 43..128) { "a code verifier is 43 to 128 characters (RFC 7636 §4.1)" }
        val out = StringBuilder(length)
        repeat(length) { out.append(UNRESERVED[random.nextInt(UNRESERVED.length)]) }
        return out.toString()
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`, unpadded — RFC 7636 §4.2. */
    fun codeChallengeOf(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
        )
}

/**
 * Build the URL the browser is sent to (RFC 6749 §4.1.1). Pure: String in, String out, no I/O.
 *
 * https only (#2). This URL is handed to a browser, and over cleartext the whole exchange —
 * client id, redirect, state, challenge, and then the code coming back — is readable and
 * rewritable by anything on the path. That is the same refusal `network_security_config.xml`
 * enforces for the app's own traffic, and there is no opt-out here either: a non-https endpoint
 * raises and NOTHING is built, so a caller cannot end up with a half-usable URL.
 *
 * An endpoint that already carries a query (`https://host/login?tenant=x`) keeps what we do not
 * set — but RFC 6749 §3.1 forbids a parameter TWICE, and this endpoint can be typed in by hand,
 * so one arriving with its own `client_id` or `state` would otherwise produce a duplicate and let
 * the SERVER arbitrate, usually in favour of the first occurrence, the one we do not control. So
 * the grant's seven names are removed from the base query first: ours win, the rest survives.
 *
 * Values are PERCENT-encoded (RFC 3986), not form-encoded: `URLEncoder` writes a space as `+`,
 * and a server that reads its query per RFC 3986 then sees a single scope
 * `…jmap:core+offline_access` and refuses the sign-in. `%20` is read correctly by both families,
 * and it is what the reference client (AppAuth, via `Uri.Builder`) sends.
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

/** The seven names the grant owns — also the ones taken off the endpoint's own query. */
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
 * opens on the address the user typed a screen earlier instead of asking for it again (#55).
 * Pure: String in, String out, no I/O.
 *
 * This URL is the SERVER's, so it is never refused, and nothing here throws: anything that
 * cannot be read (a scheme `HttpUrl` will not parse, a blank hint) gives the ORIGINAL string back,
 * verbatim. A page that still asks for the address is a sign-in; a button that opens nothing is not.
 *
 * NOT over cleartext, and that is a REFUSAL OF THE HINT, not of the URL (#2). `toHttpUrlOrNull`
 * takes `http://` as happily as `https://`, and a hint is an IDENTITY: a cleartext verification URL
 * carried none until this function existed, and with one it would put the user's full address in
 * front of every proxy log and browser history on the path. On `http` the URL is handed back
 * untouched — the sign-in works exactly as it did before, only unassisted.
 *
 * A `login_hint` already on the URL WINS and nothing is added. RFC 6749 §3.1 forbids a parameter
 * twice, and a duplicate leaves the server to arbitrate between the two, usually in favour of the
 * first — the one we did not write.
 *
 * It goes in the QUERY, through `HttpUrl`, and not by concatenation: appended by hand to a bare
 * `https://host/approve` the pair lands in the path (`/approve&login_hint=…`) and the page 404s.
 * The value is PERCENT-encoded (RFC 3986), never form-encoded: `URLEncoder` writes a SPACE as `+`
 * (and `~` as `%7E`), so an address typed with a space in it would arrive carrying a plus — a
 * different address, matched by nobody. A literal `+` is NOT the tell: `URLEncoder`
 * percent-encodes that one exactly like [percentEncode] does, which is why a `nina+qa@…` case alone
 * stayed green under a `URLEncoder` mutant. Same reason, same [percentEncode], as
 * [buildAuthorizationUrl].
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

/** The hint's name (OIDC Core §3.1.2.1) — also the one the server may have written itself. */
private const val LOGIN_HINT = "login_hint"

/**
 * RFC 3986 percent-encoding: everything outside `unreserved` becomes `%XX` over its UTF-8 bytes.
 * A space is `%20` and never `+` — see [buildAuthorizationUrl]. Uppercase hex, no locale involved.
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

/**
 * The client the four OAuth POSTs use — built here, and NOT the shared JMAP one.
 *
 * These POSTs go to URLs that are already resolved (the token and device endpoints come from the
 * metadata just fetched) or hard-coded (`OAuthProvider`'s Microsoft endpoints), so they have no
 * discovery hop left to make: a 3xx on one of them can only be a redirect that makes a secret
 * leave for another host. OkHttp replays the POST body verbatim on a 307/308 — and for [refresh]
 * that body is the long-lived refresh token, on a path walked at every sync — and turns a
 * 301/302/303 into a bodyless GET whose answer the caller then parses as a token grant, which lets
 * the redirect target choose the access token the app will use. `followSslRedirects(false)`: nor
 * down to cleartext either.
 *
 * Discovery is deliberately NOT covered by this. `discoverMetadata` keeps the shared
 * `JmapClient.defaultHttpClient()` and keeps following 3xx: it is a GET carrying no secret, and a
 * server behind a proxy or a vhost answers a redirect there (#54, #137).
 *
 * Timeouts match `JmapClient.defaultHttpClient()` — these are sync calls, not a gesture the reader
 * is watching.
 */
internal fun defaultOAuthHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Whether a discovery document may name [endpoint]: only over https, and only on [queriedHost]
 * itself, on [addressDomain] itself, or on a subdomain of either. Refused by construction: any
 * third party, any descent to cleartext http, and any host that merely CONTAINS one of the two
 * without being under it (`evil-<domain>`, `<domain>.evil.tld` — hence the leading dot in the
 * suffix check). Case-insensitive; an empty owner accepts nothing, so a malformed address (which
 * gives an empty [addressDomain]) falls back to the queried host alone. A pure function, so the
 * decision is executed by a test with pinned arguments instead of being re-read off this text.
 *
 * Same rule and same shape as `MailAutoconfig.redirectAllowed`, deliberately: this is the second
 * place where a document we did not write chooses a host, and one rule is easier to carry than
 * two. Not shared code — `core:data` depends on `core:jmap`, not the other way round.
 */
internal fun oauthEndpointAllowed(endpoint: String, queriedHost: String, addressDomain: String): Boolean {
    val url = endpoint.toHttpUrlOrNull() ?: return false
    if (url.scheme != "https") return false
    val host = url.host.lowercase()
    return underOwner(host, queriedHost) || underOwner(host, addressDomain)
}

/**
 * Whether [endpoint] names a host that is neither under [queriedHost] nor under [addressDomain] —
 * the ONE of [oauthEndpointAllowed]'s three refusals that is about WHO, and the only one the
 * connect screen is entitled to describe as the server handing sign-in elsewhere.
 *
 * False for an endpoint that cannot be read as a URL and false for a cleartext one on a host
 * that IS ours: both are refused, neither names a third party, and saying so would be a new lie
 * in the place a lie was just taken out (#54, #137). A pure function, executed by a test.
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
 * Minimal OAuth 2.0 client for the Device Authorization Grant (RFC 8628), the
 * Authorization Code Grant with PKCE (RFC 6749 §4.1 + RFC 7636) and refresh-token
 * grants — the flows used to sign a mobile app into a JMAP server (e.g. Stalwart)
 * without ever handling the password. Pure JVM, unit-testable with MockWebServer.
 */
class OAuthClient internal constructor(
    /** Metadata discovery only: follows 3xx, on purpose. See [defaultOAuthHttpClient]. */
    private val discoveryHttpClient: OkHttpClient,
    /** The four POSTs that carry a secret. One field, so none of them can be forgotten. */
    private val tokenHttpClient: OkHttpClient,
    private val json: Json,
) {
    /** Public constructor for app code — shared client for discovery, dedicated one for the POSTs. */
    constructor() : this(JmapClient.defaultHttpClient(), defaultOAuthHttpClient(), JmapClient.DefaultJson)

    /**
     * Fetch the server's OAuth metadata, or null if it advertises none.
     *
     * [addressDomain] is the domain of the address being signed in — the bare domain of
     * `Jmap.autodiscoverHosts`, PASSED IN and never guessed by trimming a label off [host]:
     * `mail.example.com` would give `example.com`, but an `example.com` typed by hand would give
     * `com`. Empty when the address is malformed, and then the queried host alone is the owner.
     *
     * WHAT THE DOCUMENT MAY NAME is judged here, endpoint by endpoint ([oauthEndpointAllowed]),
     * because this GET follows 3xx on purpose (#54, #137) and what comes back names the URLs that
     * the device code, the authorization code, the PKCE verifier and — for the whole life of the
     * account — the REFRESH TOKEN are POSTed to. `AccountStore.addOAuth` engraves the token
     * endpoint on the login record and discovery is never replayed, so a third party named here is
     * not one failed sign-in: it is a permanent forward.
     *
     * A refused endpoint is BLANKED, and the object is still returned with [endpointsOffDomain]
     * raised. Not dropped: `supportsDeviceFlow` / `supportsCodeFlow` already require a non-empty
     * token endpoint, so nothing can be POSTed anywhere, while returning null would make the
     * connect screen say "this server does not support OAuth", which is false.
     *
     * A document with NO token endpoint is still null, exactly as before: that is a server
     * without OAuth, not a refusal.
     */
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

        // The host actually asked, read off the URL that was actually built: [host] may be a bare
        // host or a full URL, and `oauthMetadataUrlFor` is what normalises the two. Unreadable
        // leaves [addressDomain] as the only owner — the safe side.
        val queriedHost = metadataUrl.toHttpUrlOrNull()?.host.orEmpty()
        fun allowed(endpoint: String?) = oauthEndpointAllowed(endpoint.orEmpty(), queriedHost, addressDomain)
        // The FACT is narrower than the refusal: only a host that is someone else's raises it.
        // A cleartext endpoint on our own host, or one that is not a URL at all, is blanked just
        // the same and says nothing about who — see [OAuthMetadata.endpointsOffDomain].
        fun elsewhere(endpoint: String?) = !endpoint.isNullOrBlank() &&
            oauthEndpointNamesAnotherHost(endpoint, queriedHost, addressDomain)
        found.copy(
            authorizationEndpoint = found.authorizationEndpoint?.takeIf { allowed(it) },
            tokenEndpoint = found.tokenEndpoint.takeIf { allowed(it) }.orEmpty(),
            deviceAuthorizationEndpoint = found.deviceAuthorizationEndpoint?.takeIf { allowed(it) },
            endpointsOffDomain = elsewhere(found.tokenEndpoint) ||
                elsewhere(found.authorizationEndpoint) ||
                elsewhere(found.deviceAuthorizationEndpoint),
        )
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
                // The `error` field only — the connect screen has to be able to say WHY the server
                // refused, and RFC 6749 §5.2 gives a short closed vocabulary for exactly that.
                // Neither the body nor `error_description` goes into the message or the cause:
                // this endpoint's payload is a device code, and a proxy's error page is prose.
                throw JmapException(
                    "Device authorization failed: HTTP ${response.code}",
                    httpCode = response.code,
                    oauthError = parseError(body)?.error,
                )
            }
            decodeGuarded(body, "device authorization", DeviceAuthorization.serializer())
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
        // Caught BY TYPE, IOException only. Catching by scope (`runCatching` over the block) made
        // a 200 whose body this client cannot read — a proxy's HTML page, a truncated document —
        // report itself as a dead network, and sent the reader off to check a connection that was
        // working. A body we cannot read is a server problem and rises like everywhere else; and
        // catching by type keeps a CancellationException (leaving the screen mid-poll) passing.
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
        // A fixed token, NOT `e.message`: OkHttp writes that message and it names the URL, the
        // host and the port ("Failed to connect to mail.example.org/…:443"), and the connect
        // screen DISPLAYS this field. That is the same leak `decodeGuarded` is here to stop —
        // the caller only needs to know the exchange never reached a server.
        } catch (_: IOException) {
            DeviceTokenResult.Failed("network_error")
        }
    }

    /**
     * Exchange an authorization code for tokens (RFC 6749 §4.1.3), proving PKCE possession.
     *
     * The fourth POST that carries a secret: [code] is a one-shot key to the account and
     * [codeVerifier] is what makes it usable, so it goes through [tokenHttpClient] like the other
     * three — a 3xx here would hand both to whatever host the Location names.
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
                // Same halves as `startDeviceAuthorization`: the protocol's `error` word travels so
                // the screen can say WHY, the body and `error_description` never do — this
                // endpoint's payload is the account's tokens.
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
                throw JmapException("Token refresh failed: HTTP ${response.code}", httpCode = response.code)
            }
            decodeGuarded(body, "token", OAuthTokens.serializer())
        }
    }

    /**
     * Decode an OAuth response WITHOUT letting the payload into the failure.
     *
     * kotlinx.serialization puts a slice of the offending JSON in its message — and on these
     * endpoints that JSON is the access and refresh tokens themselves. The message does not stay
     * in logcat: `refresh` is called from the token refresher, under
     * `MailRepository.jmapAuth`/`refresh`, whose `t.message` the mail list now DISPLAYS. A
     * truncated body, a proxy's HTML error page served as JSON, a number where a string belongs —
     * any of them is enough, and the reader would be shown their own bearer token.
     *
     * So: no excerpt and no cause, exactly like `JmapClient.decodeList` and `postJmap`. [what] is
     * enough to place the failure, and the raw body is available to whoever can attach a debugger.
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
    }
}

package app.sterna.core.data.unsubscribe

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Why a one-click unsubscribe did not go through; each maps to its own sentence on screen. */
enum class UnsubscribeFailure {
    /**
     * The server answered 3xx. Refused rather than followed: OkHttp turns a POST into a GET on
     * 301/302/303, so following it would become exactly the page fetch this feature avoids (D3).
     */
    REDIRECT,

    /** The request never left the device (no route, no DNS, timed out) — and the device agrees. */
    OFFLINE,

    /**
     * The transport died while the device is online: the host named by the SENDER is unreachable,
     * not the reader's connection. Dead unsubscribe endpoints make this the likeliest failure of
     * the whole feature, and "no network" would send that reader to toggle a working Wi-Fi.
     */
    UNREACHABLE,

    /** The server said no, or the URL was one we refuse to POST to. */
    REFUSED,
}

/** Outcome of a one-click unsubscribe POST. */
sealed interface UnsubscribeResult {
    data object Sent : UnsubscribeResult
    data class Failed(val reason: UnsubscribeFailure) : UnsubscribeResult
}

/** RFC 8058 §3.1: the POST body, verbatim and complete. Nothing else is ever sent. */
internal const val ONE_CLICK_BODY = "List-Unsubscribe=One-Click"

private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

/**
 * Whether a URL may be POSTed to at all: https, and nothing else (D4). Refused here rather than
 * left to fail inside the platform's cleartext policy, so the reader gets our sentence. The parser
 * already drops `http:` URIs, so this is the second lock on the same door.
 */
internal fun isPostableUnsubscribeUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

/**
 * The exact request a one-click unsubscribe sends.
 *
 * No `Authorization` header, ever: this POST goes to a third-party domain named by the sender.
 * The project has no global interceptor, so an absent line is genuinely an absent header — the
 * invariant is kept by NOT reusing `JmapClient.postWithRetry`, which requires a token.
 *
 * The URL is used verbatim: `LinkCleaner` is NOT applied (D6). The subscriber token often lives
 * in the query parameters, and stripping one would break the request or unsubscribe somebody else.
 */
internal fun oneClickRequest(url: String): Request = Request.Builder()
    .url(url)
    .post(ONE_CLICK_BODY.toRequestBody(FORM_MEDIA_TYPE))
    .build()

/** What an HTTP status code means for the reader. */
internal fun oneClickOutcome(code: Int): UnsubscribeResult = when {
    code in 200..299 -> UnsubscribeResult.Sent
    code in 300..399 -> UnsubscribeResult.Failed(UnsubscribeFailure.REDIRECT)
    else -> UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED)
}

/**
 * What a thrown failure means for the reader. Matched on the exception TYPE, never on its text —
 * that sentence is written by the platform resolver and changes with the Android version and the
 * system language.
 *
 * The type alone does not name the culprit: a transport failure with [online] false means the
 * device is off the network, while one with [online] true means **the sender's host** is
 * unreachable. Blaming the reader's connection for a dead third-party endpoint sends them to reboot
 * a working router. [online] is the state at the moment of the failure, handed in by the app layer.
 */
internal fun oneClickFailure(t: Throwable, online: Boolean): UnsubscribeResult {
    var error: Throwable? = t
    var hops = 0
    while (error != null && hops++ < 8) {
        if (error is UnknownHostException || error is SocketException || error is InterruptedIOException) {
            return UnsubscribeResult.Failed(
                if (online) UnsubscribeFailure.UNREACHABLE else UnsubscribeFailure.OFFLINE,
            )
        }
        error = error.cause
    }
    return UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED)
}

/**
 * The HTTP client for unsubscribe POSTs, built here and NOT shared with the JMAP one:
 * `followRedirects(false)` (see [UnsubscribeFailure.REDIRECT]) and `followSslRedirects(false)`
 * carry the whole point of the feature. Short timeouts — this is a gesture the reader is watching.
 */
internal fun defaultUnsubscribeHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/**
 * Sends the RFC 8058 one-click unsubscribe: a single `POST` carrying `List-Unsubscribe=One-Click`
 * and nothing else. No browser is opened, no document fetched, no image loaded — the entire privacy
 * argument for the feature.
 */
class UnsubscribeClient(
    private val httpClient: OkHttpClient = defaultUnsubscribeHttpClient(),
) {
    /**
     * Unsubscribe from [url], or say why not. Refuses anything that is not https (D4).
     *
     * [isOnline] is a lambda and not a boolean because it must be answered **at the moment the
     * request fails**, not when this client was built. Supplied by the app layer, which owns the
     * one `ConnectivityManager` read the app makes. Not consulted unless something is thrown.
     */
    suspend fun oneClick(url: String, isOnline: () -> Boolean): UnsubscribeResult =
        if (!isPostableUnsubscribeUrl(url)) {
            UnsubscribeResult.Failed(UnsubscribeFailure.REFUSED)
        } else {
            post(url, isOnline)
        }

    /**
     * The POST itself, without the scheme gate — so the wire behaviour (headers, body, and above
     * all the refusal to follow a redirect) is exercised against a local test server, which speaks
     * plain http. Callers go through [oneClick].
     */
    internal suspend fun post(url: String, isOnline: () -> Boolean): UnsubscribeResult =
        withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(oneClickRequest(url)).execute().use { response ->
                    oneClickOutcome(response.code)
                }
            } catch (t: Throwable) {
                oneClickFailure(t, isOnline())
            }
        }
}

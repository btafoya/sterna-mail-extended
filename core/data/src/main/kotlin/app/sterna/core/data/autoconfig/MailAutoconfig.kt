package app.sterna.core.data.autoconfig

import app.sterna.core.data.account.ConnectionSecurity
import app.sterna.core.data.account.MailEndpoint
import app.sterna.core.data.account.childElements
import app.sterna.core.data.account.childText
import app.sterna.core.data.account.safeXmlDocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

/**
 * What discovering the IMAP/SMTP settings for a typed email address answered.
 *
 * [MailAutoconfigResult.Found] carries the STORED types ([MailEndpoint], [ConnectionSecurity])
 * on purpose: the screen that will consume this prefills the very fields the account record
 * holds, and a parallel endpoint type would be one translation nobody tests.
 */
sealed interface MailAutoconfigResult {
    data class Found(
        val incoming: MailEndpoint,
        val outgoing: MailEndpoint,
        val username: String,
    ) : MailAutoconfigResult

    data object NotFound : MailAutoconfigResult
}

/**
 * How long the whole cascade may take, all tiers together — what the sign-in screen waits for.
 * Same motif as [app.sterna.core.data.mail.JMAP_DISCOVERY_BUDGET_MS]: production passes nothing,
 * so the shipped value lives in exactly one place, which a test pins.
 */
const val MAIL_AUTOCONFIG_BUDGET_MS = 10_000L

/**
 * The two — and ONLY two — URLs a domain may publish its configuration at, in the order they are
 * asked (Thunderbird autoconfig format). ⛔ Both are on the user's own domain: the privacy policy
 * promises the app contacts nobody else, so no Mozilla ISPDB and no DNS SRV walk may ever be
 * appended here.
 */
fun autoconfigUrlsFor(domain: String): List<String> = listOf(
    "https://autoconfig.$domain/mail/config-v1.1.xml",
    "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
)

/**
 * The username a published config means: `%EMAILADDRESS%` is the full address,
 * `%EMAILLOCALPART%` the part before the `@`, an absent/empty username is the full address, and
 * anything else is taken verbatim. A pure function, so the decision is executed by a test with
 * pinned arguments instead of being re-read off the code that contains it.
 */
fun substitutedUsername(raw: String, emailAddress: String): String {
    if (raw.isBlank()) return emailAddress
    return raw
        .replace("%EMAILADDRESS%", emailAddress)
        .replace("%EMAILLOCALPART%", emailAddress.substringBefore('@'))
}

/**
 * The HTTP client the published-config tiers fetch with — local to this file, like the
 * unsubscribe one, and NOT the JMAP client (whose code path attaches an Authorization header).
 *
 * ⛔ It follows NO redirect on its own: OkHttp's built-in following pins the scheme but not the
 * HOST, so one 302 from `autoconfig.<domain>` would carry the fetch — up to 20 hops — to a third
 * party (`autoconfig.thunderbird.net`, the ISPDB), and the privacy policy promises the app talks
 * to nobody but the user's own server. 3xx answers are walked by [fetchPublishedConfig] by hand
 * instead, every hop passing [redirectAllowed].
 */
internal fun defaultAutoconfigHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

/** How many 3xx hops one published-config URL may be walked through before its tier gives up. */
internal const val MAX_AUTOCONFIG_REDIRECTS = 5

/**
 * Whether a redirect may carry the fetch to [target]: only over https, and only to [domain]
 * itself or one of its subdomains. Refused by construction: any third party
 * (`autoconfig.thunderbird.net`), any descent to cleartext http, and any host that merely
 * CONTAINS the domain without being under it (`evil-<domain>`, `<domain>.evil.tld` — hence the
 * leading dot in the suffix check). Case-insensitive. A pure function, so the decision is
 * executed by a test with pinned arguments instead of being re-read off this text.
 */
internal fun redirectAllowed(target: HttpUrl, domain: String): Boolean {
    if (target.scheme != "https") return false
    val host = target.host.lowercase()
    val own = domain.lowercase()
    return host == own || host.endsWith(".$own")
}

/** Socket timeout for one guessed-host handshake: a guess is cheap, waiting for one is not. */
private const val TLS_PROBE_TIMEOUT_MS = 5_000

/**
 * The real TLS probe behind a guessed host: connect, handshake, and — the part that carries the
 * guard — verify the certificate FOR THAT NAME. ⚠ A bare [SSLSocket] does not check the hostname
 * at all; `endpointIdentificationAlgorithm = "HTTPS"` is what makes "the handshake succeeded"
 * mean "a certificate valid for this exact name", i.e. what keeps a wildcard-DNS catch-all or a
 * spoofed host from being prefilled as the server the password will go to.
 */
internal suspend fun realTlsProbe(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        (factory.createSocket() as SSLSocket).use { socket ->
            socket.soTimeout = TLS_PROBE_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), TLS_PROBE_TIMEOUT_MS)
            val params = socket.sslParameters
            params.endpointIdentificationAlgorithm = "HTTPS"
            socket.sslParameters = params
            socket.startHandshake()
            true
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        false
    }
}

/**
 * The cascade as the UI calls it: address only, shipped budget, production wiring. A separate
 * entry point on purpose — [discoverMailSettings]'s test seams put [OkHttpClient] in its
 * signature, and OkHttp is an implementation dependency of this module that a caller must not
 * need on its classpath just to start a discovery.
 */
suspend fun discoverMailAutoconfig(emailAddress: String): MailAutoconfigResult =
    discoverMailSettings(emailAddress)

/**
 * Discover the IMAP/SMTP settings for [emailAddress], first found wins:
 *
 *  1. the config the domain PUBLISHED, at the two [autoconfigUrlsFor] locations in order;
 *  2. guessed hosts (`imap.`/`mail.` at 993, `smtp.`/`mail.` at 465), each kept only if a TLS
 *     handshake with a certificate valid for that name succeeds, and only if BOTH sides do.
 *
 * A published config outranks a guess by construction: the guesses are not even probed while a
 * tier above them has answered. [budgetMs] bounds what the caller waits for, not merely what is
 * decided: the work runs in a child job that is cancelled but never JOINED at the deadline, since
 * a blocking OkHttp/socket call ignores cancellation until its own timeout.
 *
 * [autoconfigUrls], [httpClient] and [tlsProbe] are seams; production passes none of them.
 * ⛔ Cancellation retraverses: leaving the sign-in cancels this, and no catch here turns that
 * into a [MailAutoconfigResult.NotFound] (the lesson `probeOfFailure` paid for).
 */
suspend fun discoverMailSettings(
    emailAddress: String,
    budgetMs: Long = MAIL_AUTOCONFIG_BUDGET_MS,
    autoconfigUrls: List<String> = autoconfigUrlsFor(emailAddress.substringAfterLast('@')),
    httpClient: OkHttpClient = defaultAutoconfigHttpClient(),
    tlsProbe: suspend (host: String, port: Int) -> Boolean = ::realTlsProbe,
): MailAutoconfigResult {
    val domain = emailAddress.substringAfterLast('@')
    val decided = CompletableDeferred<MailAutoconfigResult>()
    val work = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + work)
    try {
        scope.launch {
            val verdict = try {
                runCascade(emailAddress, domain, autoconfigUrls, httpClient, tlsProbe)
            } catch (t: Throwable) {
                // A crashed cascade is "nothing found", but a CANCELLED one is not: cancellation
                // is the caller leaving, and classifying it as a verdict keeps nobody informed.
                if (t is CancellationException) throw t
                MailAutoconfigResult.NotFound
            }
            decided.complete(verdict)
        }
        return withTimeoutOrNull(budgetMs) { decided.await() } ?: MailAutoconfigResult.NotFound
    } finally {
        // A request to stop, not a wait for it: a blocked fetch dies on its own socket timeout.
        work.cancel()
    }
}

/** The tiers, in the order that IS the decision: published config first, guesses last. */
private suspend fun runCascade(
    emailAddress: String,
    domain: String,
    autoconfigUrls: List<String>,
    httpClient: OkHttpClient,
    tlsProbe: suspend (host: String, port: Int) -> Boolean,
): MailAutoconfigResult {
    for (url in autoconfigUrls) {
        fetchPublishedConfig(httpClient, url, domain, emailAddress)?.let { return it }
    }
    return guessedSettings(domain, emailAddress, tlsProbe)
}

/**
 * Fetch ONE published-config URL and read it, or answer null for "this tier said nothing".
 * ⛔ An HTTP 200 is NOT a verdict — the verdict is [parsePublishedConfig]'s, taken on the XML
 * root: catch-all reverse proxies (masto.top, live case) answer 200 with an HTML page on both
 * autoconfig paths.
 *
 * 3xx answers are walked here BY HAND (the client refuses to follow any on its own), at most
 * [MAX_AUTOCONFIG_REDIRECTS] hops: a domain may park the well-known path behind a 3xx (#54/#137
 * arbitration), but each hop must either stay on the very origin this fetch already reached — a
 * path-only redirect opens no new connection — or pass [redirectAllowed], i.e. https on the
 * user's own domain. ⛔ A refused redirect ends the tier with null and the refused target is
 * never contacted at all.
 */
private suspend fun fetchPublishedConfig(
    httpClient: OkHttpClient,
    url: String,
    domain: String,
    emailAddress: String,
): MailAutoconfigResult.Found? = withContext(Dispatchers.IO) {
    try {
        var current = url.toHttpUrl()
        repeat(MAX_AUTOCONFIG_REDIRECTS + 1) {
            val next = httpClient.newCall(Request.Builder().url(current).build()).execute()
                .use { response ->
                    if (!response.isRedirect) {
                        if (!response.isSuccessful) return@withContext null
                        val body = response.body ?: return@withContext null
                        return@withContext parsePublishedConfig(body.byteStream(), emailAddress)
                    }
                    val target = response.header("Location")?.let { current.resolve(it) }
                        ?: return@withContext null
                    val sameOrigin = target.scheme == current.scheme &&
                        target.host == current.host && target.port == current.port
                    if (!sameOrigin && !redirectAllowed(target, domain)) return@withContext null
                    target
                }
            current = next
        }
        null // Still a redirect after MAX_AUTOCONFIG_REDIRECTS hops: the tier gives up.
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }
}

/**
 * Read a Thunderbird-autoconfig document, hardened the same way as the K-9 import (shared
 * SafeXml), or answer null to reject the WHOLE tier. Rejected: a root that is not
 * `clientConfig`, no `incomingServer type="imap"` (pop3/caldav/... are not mail settings we can
 * store), no `outgoingServer type="smtp"`, and any endpoint [endpointOf] refuses.
 */
internal fun parsePublishedConfig(body: InputStream, emailAddress: String): MailAutoconfigResult.Found? {
    val document = try {
        safeXmlDocumentBuilderFactory().newDocumentBuilder().parse(body)
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        return null // Not XML at all — an HTML page, a truncated body, a doctype.
    }
    val root = document.documentElement ?: return null
    if (root.tagName != "clientConfig") return null
    val provider = root.childElements().firstOrNull { it.tagName == "emailProvider" } ?: return null
    val incomingEl = provider.childElements().firstOrNull {
        it.tagName == "incomingServer" && it.getAttribute("type").equals("imap", ignoreCase = true)
    } ?: return null
    val outgoingEl = provider.childElements().firstOrNull {
        it.tagName == "outgoingServer" && it.getAttribute("type").equals("smtp", ignoreCase = true)
    } ?: return null
    val incoming = endpointOf(incomingEl) ?: return null
    val outgoing = endpointOf(outgoingEl) ?: return null
    return MailAutoconfigResult.Found(
        incoming = incoming,
        outgoing = outgoing,
        username = substitutedUsername(incomingEl.childText("username"), emailAddress),
    )
}

/**
 * One `<incomingServer>`/`<outgoingServer>` element as a stored endpoint, or null to reject it —
 * and with it the tier. ⛔ `socketType` is an allow-list: `SSL` and `STARTTLS`, nothing else.
 * `plain` — or any value this build does not recognise — is NOT mapped to
 * [ConnectionSecurity.NONE]: an unauthenticated XML fetch does not get to decide that a password
 * may travel in cleartext.
 */
private fun endpointOf(server: Element): MailEndpoint? {
    val host = server.childText("hostname")
    if (host.isBlank()) return null
    val port = server.childText("port").toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val security = when (server.childText("socketType").uppercase()) {
        "SSL" -> ConnectionSecurity.TLS
        "STARTTLS" -> ConnectionSecurity.STARTTLS
        else -> return null
    }
    return MailEndpoint(host, port, security)
}

/**
 * The guessed tier: `imap.<domain>` then `mail.<domain>` at 993, `smtp.<domain>` then
 * `mail.<domain>` at 465 — implicit TLS ports only, so the security of a kept guess is always
 * [ConnectionSecurity.TLS] and the username the full address. A guess is a NAME until [tlsProbe]
 * shakes hands with a certificate valid for it; both sides must pass or nothing is answered —
 * half a configuration prefilled is worse than an empty form.
 */
private suspend fun guessedSettings(
    domain: String,
    emailAddress: String,
    tlsProbe: suspend (host: String, port: Int) -> Boolean,
): MailAutoconfigResult {
    val incomingHost = firstVerifiedHost(listOf("imap.$domain", "mail.$domain"), 993, tlsProbe)
        ?: return MailAutoconfigResult.NotFound
    val outgoingHost = firstVerifiedHost(listOf("smtp.$domain", "mail.$domain"), 465, tlsProbe)
        ?: return MailAutoconfigResult.NotFound
    return MailAutoconfigResult.Found(
        incoming = MailEndpoint(incomingHost, 993, ConnectionSecurity.TLS),
        outgoing = MailEndpoint(outgoingHost, 465, ConnectionSecurity.TLS),
        username = emailAddress,
    )
}

/** The first of [hosts] that passes the handshake on [port], probed one at a time, in order. */
private suspend fun firstVerifiedHost(
    hosts: List<String>,
    port: Int,
    tlsProbe: suspend (host: String, port: Int) -> Boolean,
): String? = hosts.firstOrNull { tlsProbe(it, port) }

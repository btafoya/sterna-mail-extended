package app.sterna.core.jmap

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One autodiscovery candidate's connect budget. A sign-in pays it twice in a row, once for the
 * typed domain to give up and once for the guess behind it to connect, and both plus an answer
 * must fit inside JMAP_DISCOVERY_BUDGET_MS. OkHttp spends it per IP address tried, so a dual-stack
 * name that swallows SYNs spends it twice over. Below this, a slow but live domain loses its
 * handshake to a guessed subdomain, and the host discovery elects is written into the account.
 */
const val JMAP_DISCOVERY_CONNECT_TIMEOUT_MS = 4_000L

/**
 * The shared client with its connect bound alone shortened, derived rather than rebuilt so that
 * the read timeout and the redirect posture cannot drift away from it.
 */
internal fun discoveryHttpClient(): OkHttpClient =
    JmapClient.defaultHttpClient().newBuilder()
        .connectTimeout(JMAP_DISCOVERY_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

/** The client autodiscovery probes its candidates with; see [discoveryHttpClient]. */
fun jmapDiscoveryClient(): JmapClient = JmapClient(discoveryHttpClient(), JmapClient.DefaultJson)

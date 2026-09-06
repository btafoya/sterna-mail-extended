package app.sterna.ui.connect

import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthTokens
import kotlinx.coroutines.delay

/** What the device-grant poll decided. Not a case of [DeviceTokenResult]: that says what one
 *  exchange answered, this what a whole wait concluded. */
sealed interface DevicePollVerdict {
    data class Approved(val tokens: OAuthTokens) : DevicePollVerdict
    data class Refused(val failure: DeviceTokenResult.Failed) : DevicePollVerdict
    /** The delay ran out. [everReachedAServer]: at least one poll was answered BY A SERVER. */
    data class RanOut(val everReachedAServer: Boolean) : DevicePollVerdict
}

/**
 * Wait for the user to approve the device code, and say how the wait ended.
 *
 * [delay] stays at the head of the loop: at the foot, the first poll goes out with no wait at
 * all and a server that is down becomes a burst of retries for thirty minutes. It catches
 * nothing, which is what keeps `CancellationException` passing; a `catch (t: Throwable)
 * { continue }` here turns Cancel into an immortal coroutine behind a screen that is gone.
 *
 * @param intervalSeconds seconds between polls, per the server's `interval` — floored at one
 *   second: `interval` is optional in RFC 8628 § 3.2, so `0` is legal and `delay(0)` bounds nothing.
 * @param expiresInSeconds how long the device code stays valid — the wait's whole budget.
 * @param poll one exchange with the token endpoint.
 */
suspend fun pollDeviceGrant(
    intervalSeconds: Long,
    expiresInSeconds: Long,
    now: () -> Long = System::currentTimeMillis,
    poll: suspend () -> DeviceTokenResult,
): DevicePollVerdict {
    var interval = intervalSeconds.coerceAtLeast(1)
    var everReachedAServer = false
    val deadline = now() + expiresInSeconds * 1000L
    while (now() < deadline) {
        delay(interval * 1000)
        when (val result = poll()) {
            is DeviceTokenResult.Success -> return DevicePollVerdict.Approved(result.tokens)
            // Both are a SERVER TALKING, which is the whole of what the flag records.
            DeviceTokenResult.Pending -> everReachedAServer = true
            DeviceTokenResult.SlowDown -> {
                everReachedAServer = true
                interval += 5
            }
            // #55: `network_error` is the one `Failed` that is not a refusal — the exchange reached
            // no server, so nothing was said and the device code is still good. It does not arm
            // everReachedAServer: that is precisely what it failed to do.
            is DeviceTokenResult.Failed ->
                if (result.error != "network_error") return DevicePollVerdict.Refused(result)
        }
    }
    return DevicePollVerdict.RanOut(everReachedAServer)
}

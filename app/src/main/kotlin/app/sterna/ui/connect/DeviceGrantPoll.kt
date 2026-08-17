package app.sterna.ui.connect

import app.sterna.core.jmap.DeviceTokenResult
import app.sterna.core.jmap.OAuthTokens
import kotlinx.coroutines.delay

/**
 * WHAT the device-grant poll decided — the whole of it, and nothing about how it is said.
 *
 * ⛔ Deliberately NOT a new case of [DeviceTokenResult]. That vocabulary is what one exchange with
 * the token endpoint answered; this is what a WHOLE WAIT concluded, and they are different facts:
 * a poll that reached nothing is a `Failed` down there and is not a verdict up here. Widening
 * `DeviceTokenResult` instead would have made `OAuthClient`'s `when` arms and the two Microsoft
 * loops — deliberately out of this fix's scope — answer a question they have no opinion on.
 */
sealed interface DevicePollVerdict {
    data class Approved(val tokens: OAuthTokens) : DevicePollVerdict
    data class Refused(val failure: DeviceTokenResult.Failed) : DevicePollVerdict
    /** The delay ran out. [everReachedAServer]: at least one poll was answered BY A SERVER. */
    data class RanOut(val everReachedAServer: Boolean) : DevicePollVerdict
}

/**
 * Wait for the user to approve the device code, and say how the wait ended.
 *
 * Pure, `suspend` and executable — the same shape as [discoverOAuthAmong] next door, and for the
 * same reason: `ConnectViewModel` is an `AndroidViewModel` on the app container, which no test in
 * this module can build. While this loop lived there, NOTHING in this repo executed it; the only
 * thing holding its decision was a source lint reading the file as text, so no mutation of the loop
 * itself could be seen. Here the decision runs, on an injected clock ([now], the idiom of
 * `OutboxLogic.badgeCount`), against a fake [poll] replaying a literal list of results.
 *
 * ⛔ [delay] STAYS AT THE HEAD of the loop. Moved to the foot it would let the first poll go out
 * with no wait at all, and — now that an unreachable poll no longer ends the wait — would turn a
 * server that is down into a burst of retries for thirty minutes. The head is what bounds the rate.
 *
 * ⛔ This function CATCHES NOTHING: no `try`, no `runCatching`. That is what keeps
 * `CancellationException` passing — leaving the screen, tapping Cancel, the back gesture — and it
 * is the risk this fix is closest to. A `catch (t: Throwable) { continue }` written here would turn
 * Cancel into an immortal coroutine polling for thirty minutes behind a screen that is gone. The
 * caller's own `catch (Throwable)` still stands around the call, so a `JmapException` from
 * `decodeGuarded` (a body the client could not read) rises and is reported for what it is instead
 * of being dressed up as a dead network.
 *
 * @param intervalSeconds seconds between polls, per the server's `interval` — floored at one second
 *   HERE, inside the loop it paces, and not at the call site. `interval` is optional in RFC 8628
 *   § 3.2, so `0` is a legal answer and `delay(0)` bounds nothing: the floor is what makes the
 *   sentence above ("the head is what bounds the rate") true at all. Left at the call site it was
 *   a guard no test could execute, on the one path where losing it costs a burst of POSTs against
 *   a token endpoint for the whole life of the device code.
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
            // ⭐ #55: `network_error` is the one `Failed` that is NOT a refusal. `OAuthClient`
            // answers it at its `catch (_: IOException)` — the exchange reached no server at all,
            // so nothing has been said about this sign-in and the device code is still good for the
            // rest of its half hour. Ending here is what killed the reporter's sign-in seconds
            // after the code appeared, on one poll going out over a link that had dropped.
            // ⛔ It does NOT arm everReachedAServer: that is precisely what it failed to do.
            is DeviceTokenResult.Failed ->
                if (result.error != "network_error") return DevicePollVerdict.Refused(result)
        }
    }
    return DevicePollVerdict.RanOut(everReachedAServer)
}

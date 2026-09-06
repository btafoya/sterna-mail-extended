package app.sterna.ui.inbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * How a refresh ended, as far as the list has to render it — and there are FOUR ends, not three.
 * `refresh()` used to spell success, failure and cancellation; the fourth, "the coroutine never
 * came back", was covered by nothing at all, so the list announced a refresh in progress for ever
 * (#130): no error, no traffic, no log line.
 *
 * A closed type rather than a nullable pair, so the mapping at the call site cannot quietly forget
 * an arm, and so the decision runs in a plain JVM test — `InboxViewModel` is an `AndroidViewModel`
 * and cannot be instantiated in one, which is already why [RefreshNotice] exists.
 */
internal sealed interface RefreshOutcome {
    /** The work returned: the list has fresh mail. */
    data object Delivered : RefreshOutcome

    /** The work threw, and this is what it threw — the text the banner shows, and the throwable
     *  the connectivity probe judges. Cancellation is NOT here: see [refreshWithin]. */
    data class Failed(val cause: Throwable) : RefreshOutcome

    /** The budget ran out with the work still in flight. An ERROR, never an offline claim. */
    data object AbandonedAtBudget : RefreshOutcome
}

/**
 * How long a single pull-to-refresh may keep the spinner turning.
 *
 * Why 180 s, and not something that feels quick: ONE JMAP call is already worth 20 s of connect
 * plus 30 s of read (`JmapClient.defaultHttpClient()`), and a refresh chains several of them. What
 * this bound fixes is "never", not "slow", so it has to sit well clear of a sync that is merely
 * having a bad day on a train.
 *
 * It is NOT "above the worst legitimate case", and claiming so would be false: the unified inbox
 * walks the accounts IN SERIES (`MailRepository.refreshAllInboxes`), so its worst legitimate case
 * is N times one account's, and 180 s is under that from about three slow accounts on. When the
 * bound fires there, the accounts still ahead in the walk are not refreshed at all — the list says
 * the refresh failed, and it is right, but it does not say which accounts were missed. That is a
 * DEBT, accepted knowingly rather than a property: a tern that spins a lie for five minutes is
 * worse than a refresh cut short, and per-account bounding is a different change.
 *
 * This is NOT a `callTimeout` and it has no business in `JmapClient`. The push connection's
 * client is derived from the shared one (`JmapClient.kt`, `httpClient.newBuilder()` for the event
 * source), so a global call timeout there would cut the event stream at a fixed interval — push
 * would drop and reconnect for ever, which is a worse bug than the one being fixed.
 */
internal const val REFRESH_BUDGET_MS = 180_000L

/**
 * Run [work] under [budgetMs] and answer with which of the three ends it reached.
 *
 * **`withTimeoutOrNull(budgetMs) { work() }` does NOT do this.** That form JOINS its block: the
 * budget only bounds a body that notices cancellation, and a refresh sits in a blocking OkHttp
 * `execute()` which ignores it until its own socket gives up. The naive form would hand the main
 * thread back only after the socket's own delays — and #130 is precisely the case where the socket
 * has no delay to give (nothing covers name resolution), so the defect would survive whole.
 *
 * So the shape is the one already written and argued in
 * `core/data/.../JmapDiscovery.kt` (`discoverJmapAmong`): the work runs in a [Job] that is a CHILD
 * of the caller's context — cancelling the screen still cancels it — but that is never JOINED. It
 * completes a [CompletableDeferred], the budget is spent awaiting THAT, and the `finally` merely
 * ASKS the work to stop without waiting for it to agree.
 *
 * The debt, plainly: the bound stops the screen's lie, not the socket. An abandoned refresh's
 * thread is still out there and may return long after; it can no longer say anything, because the
 * only writer of the status is this envelope's return value.
 *
 * At the deadline, a verdict that has ALREADY LANDED wins over the clock. The work completes
 * from an IO thread while the deadline goes off on Main, so the two genuinely race: reading the
 * clock alone would tell the reader a refresh failed while its mail is already in the database,
 * and would skip `reportSuccess()`, leaving a stale offline banner up. Same doctrine as
 * `verdictAtBudget` in `JmapDiscovery` — "an answer that landed is not held back by a black hole".
 * A [work] that landed on a CANCELLATION is not a verdict and stays [RefreshOutcome.AbandonedAtBudget].
 *
 * A [CancellationException] out of [work] is not something the refresh said. It leaves for the
 * caller unchanged (the deferred is completed exceptionally AND it is rethrown), so
 * `InboxViewModel.refresh`'s `catch (c: CancellationException) { throw c }` keeps deciding exactly
 * as it did — a superseding refresh must not stomp the status of the one that replaced it.
 */
internal suspend fun refreshWithin(
    /**
     * Defaulted on purpose, and production passes nothing, so the shipped bound lives in exactly
     * ONE place — [REFRESH_BUDGET_MS], which a test pins. Spelled at the call site it would be a
     * second copy no JVM test can reach (`InboxViewModel` is not instantiable there): the call
     * could be handed `Long.MAX_VALUE` with the whole suite still green, i.e. #130 back in full.
     * That is the trap commit `1cee7f42` paid for on the discovery budget. Tests pass their own.
     */
    budgetMs: Long = REFRESH_BUDGET_MS,
    work: suspend () -> Unit,
): RefreshOutcome {
    val done = CompletableDeferred<RefreshOutcome>()
    // What the work settled on, readable WITHOUT awaiting: at the deadline the verdict may already
    // be in while the resumption that carries it has not been dispatched yet. Written once, before
    // `done`, and never for a cancellation. Atomic because the work lands on another thread.
    val landed = AtomicReference<RefreshOutcome?>(null)
    // A child of the caller — leaving the screen still cancels the work — that is never joined.
    val running = Job(coroutineContext[Job])
    val scope = CoroutineScope(coroutineContext + running)
    try {
        // UNDISPATCHED: the work starts on the caller's thread, as it did before this envelope
        // existed, and — the point — it has BEGUN before the clock below starts. Started with a
        // dispatch, work that lands at the very instant of the deadline is queued behind it and
        // loses the race by construction, whatever the deadline then reads.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                work()
                landed.set(RefreshOutcome.Delivered)
                done.complete(RefreshOutcome.Delivered)
            } catch (c: CancellationException) {
                // Not a verdict: it leaves for the caller exactly as it arrived, and `landed` stays
                // null so a deadline racing it still reads AbandonedAtBudget.
                done.completeExceptionally(c)
                throw c
            } catch (t: Throwable) {
                landed.set(RefreshOutcome.Failed(t))
                done.complete(RefreshOutcome.Failed(t))
            }
        }
        return withTimeoutOrNull(budgetMs) { done.await() }
            ?: landed.get()
            ?: RefreshOutcome.AbandonedAtBudget
    } finally {
        // A request to stop, not a wait for it: a blocking read dies on its own socket's terms.
        running.cancel()
    }
}

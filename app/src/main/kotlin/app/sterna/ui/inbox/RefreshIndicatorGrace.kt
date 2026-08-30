package app.sterna.ui.inbox

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * How long a refresh may run before anything is DRAWN about it (#178).
 *
 * The tern used to light the instant `refresh()` was called — before anything knew whether the
 * fetch would take 80 ms or 8 s — so every folder tap, every account switch and every cold start
 * blinked it for nothing. A refresh that lands inside this grace is one the reader never needed to
 * be told about: the rows simply arrive.
 *
 * ⛔ This is the DISPLAY only. "A refresh is running" is [MailUi.refreshing] and is
 * not delayed by a millisecond: it still empties the centre of the screen so "this folder is empty"
 * cannot lie during a fetch (#63), and it is still the seed of the state flow.
 *
 * ⚠ A FRESH view runs no refresh at all (`refreshUnlessFresh` returns inside its window), so on the
 * common folder switch nothing empties the centre by that path any more. Emptying it there is the
 * screen's own arm: it catches on `refreshLoading || staleRows` and draws only on
 * `refreshLoading && !staleRows`.
 */
internal const val REFRESH_INDICATOR_GRACE_MS = 250L

/**
 * Once shown, the smallest time the indicator stays on screen.
 *
 * Without it the grace only moves the flash: a refresh landing at 260 ms would draw the tern for
 * ten milliseconds, which is worse than never drawing it. The floor hangs off the instant the
 * indicator BECAME visible, not off the start of the refresh, so it is bounded by construction.
 */
internal const val REFRESH_INDICATOR_MIN_SHOW_MS = 500L

/**
 * One stretch of continuous refreshing, as the eye sees it — not as the ViewModel counts jobs.
 *
 * A reconcile that supersedes another (`refresh()` cancels and replaces) is the SAME stretch here:
 * see [startRefreshRun]. Nothing in this file reads a clock; every decision takes `now` as a
 * parameter, so the whole rule runs in a plain JVM test — `InboxViewModel` is an `AndroidViewModel`
 * and cannot be instantiated in one, which is already why [RefreshNotice] and `BoundedRefresh.kt`
 * exist.
 */
internal data class RefreshRun(
    val startedAt: Long,
    /**
     * This stretch answers a gesture the reader made in so many words — the pull, or a Retry
     * button. There is no grace for those: the indicator IS the answer to the gesture, and a pull
     * that draws nothing for a quarter of a second reads as a pull that did nothing.
     */
    val instant: Boolean = false,
    /** When the refreshing stopped; null while it is still running. */
    val endedAt: Long? = null,
)

/** The instant [run] would first be drawn, ignoring whether it is still running. */
private fun showAt(run: RefreshRun): Long =
    run.startedAt + if (run.instant) 0L else REFRESH_INDICATOR_GRACE_MS

/**
 * Start a stretch at [now], or extend the one already running.
 *
 * ⛔ A refresh starting ON TOP of a stretch still in flight extends it instead of opening a new
 * one. Opening a new one restarts both the grace and the floor, and the app cancels-and-replaces
 * refreshes readily (a reconnect walk, an account switch, a folder tap while the previous fetch is
 * still out) — so a cascade would either keep the indicator permanently just-about-to-appear, or
 * make it blink once per reconcile, which is #178 with extra steps.
 *
 * A stretch CAN be promoted to [RefreshRun.instant] mid-flight, and never demoted: if the reader
 * pulls while a background reconcile is already running, the pull deserves its answer now, and the
 * grace it was serving is no longer anybody's silence to keep.
 *
 * ⛔ A stretch that has ENDED but is STILL DRAWN — serving its floor — is reopened, not replaced.
 * Replacing it was the first version of this rule, and it defeated the floor at the only moment
 * the floor is for: shown at 250, landed at 300, a second folder tapped at 400 gave a NEW stretch,
 * so the indicator went dark at 400 and came back at 650. Dark for a quarter of a second between
 * two folder taps is the in-and-out blink this file exists to remove, with two more edges than
 * before the fix. The same edit protects the answer to a pull, which any background reconcile
 * landing behind it could otherwise cut short.
 *
 * A stretch that has ended and is NOT drawn — the nominal case, gone inside its own grace, or its
 * floor already served — is replaced outright: there is nothing on screen to keep continuous.
 */
internal fun startRefreshRun(previous: RefreshRun?, now: Long, instant: Boolean): RefreshRun = when {
    previous == null -> RefreshRun(startedAt = now, instant = instant)
    previous.endedAt == null -> previous.copy(instant = previous.instant || instant)
    refreshIndicatorShowing(previous, now) ->
        previous.copy(endedAt = null, instant = previous.instant || instant)
    else -> RefreshRun(startedAt = now, instant = instant)
}

/**
 * Whether the indicator is drawn at [now].
 *
 * ⛔ The nominal case of #178 is the FIRST rule below, and it is the one a naive implementation
 * gets wrong: a stretch that ended before it was ever due to be shown is never shown AT ALL. A
 * floor applied blindly to it would light the tern from 250 ms to 750 ms for a refresh that had
 * already been over for 170 ms — the very blink this file exists to remove.
 *
 * Otherwise the indicator runs from [showAt] to `max(endedAt, showAt + REFRESH_INDICATOR_MIN_SHOW_MS)`,
 * and for ever while the stretch is still running.
 */
internal fun refreshIndicatorShowing(run: RefreshRun?, now: Long): Boolean {
    if (run == null) return false
    val from = showAt(run)
    val ended = run.endedAt ?: return now >= from
    if (ended < from) return false
    return now >= from && now < maxOf(ended, from + REFRESH_INDICATOR_MIN_SHOW_MS)
}

/**
 * The next instant at which [refreshIndicatorShowing] answers differently, or null when it never
 * will again — the single wake-up [refreshIndicatorVisibility] schedules per emission.
 *
 * ⛔ Null, not `showAt`, for a stretch that ended inside its grace: that one has no visible future
 * at all, and booking a wake-up for it would put the screen back on the scheduler once per folder
 * tap to change nothing.
 */
internal fun nextRefreshIndicatorChange(run: RefreshRun?, now: Long): Long? {
    if (run == null) return null
    val from = showAt(run)
    val ended = run.endedAt
    if (ended != null && ended < from) return null
    if (now < from) return from
    // Visible from here on: only an END can take it off, and a running stretch has none.
    if (ended == null) return null
    val until = maxOf(ended, from + REFRESH_INDICATOR_MIN_SHOW_MS)
    return if (now < until) until else null
}

/**
 * The indicator's visibility over time, on the exact shape of `OutboxLogic.badgeCount`: each
 * stretch emitted upstream schedules ONE wake-up — the next instant the answer changes — and never
 * polls. [now] is injected and has NO default: this file must not name a clock,
 * because picking the wrong one is invisible here and wrong everywhere. It measures a DURATION, so
 * it is `SystemClock.elapsedRealtime` at the call site — see the clock in `InboxViewModel`, which a
 * source lint pins. A wall clock would be nudged by NTP mid-refresh while the `delay` below, which
 * is monotonic, would not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun refreshIndicatorVisibility(
    runs: Flow<RefreshRun?>,
    now: () -> Long,
): Flow<Boolean> = runs
    .flatMapLatest { run ->
        flow {
            while (true) {
                val instant = now()
                emit(refreshIndicatorShowing(run, instant))
                val next = nextRefreshIndicatorChange(run, instant) ?: break
                delay(next - instant)
            }
        }
    }
    .distinctUntilChanged()

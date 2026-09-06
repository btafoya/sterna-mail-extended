package app.sterna.ui.message

import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * How far the FINGER must actually have travelled before a flick is allowed to change message.
 *
 * The reader's [androidx.compose.foundation.pager.HorizontalPager] used to be mounted with no
 * `flingBehavior` at all, so it took the library's own rule, and that rule looks at SPEED ALONE:
 * `SnapFlingBehavior`'s `MinFlingVelocityDp` is 400 dp/s, and above it
 * `LazyListSnapLayoutInfoProvider` snaps to `NextItem`/`PreviousItem` instead of `ClosestItem` —
 * whatever the page offset happens to be, zero included. The Pager KDoc says so in as many words:
 * "any fling that has high enough velocity will always move to the next page". A 10-20 dp nudge
 * over 25-40 ms clears 400 dp/s easily, so a brief brush of the thumb turned the page (reported
 * 2026-08-18). Nothing about that depends on screen width.
 *
 * `androidx.viewpager.widget.ViewPager` demanded BOTH conditions — `MIN_DISTANCE_FOR_FLING` = 25 dip
 * AND `MIN_FLING_VELOCITY` = 400 dip/s. Compose kept the velocity and lost the distance floor. This
 * file is that floor, and nothing else: the velocity threshold, the snap animation and
 * `snapPositionalThreshold` all stay the library's.
 *
 * WHERE THE TRAVEL IS READ, and why it is not the pager's offset. The first cut of this file
 * measured `currentPageOffsetFraction × pageSize`, i.e. what the pager had already SCROLLED. That
 * is the finger's travel minus everything the pager never saw — its own touch slop, and whatever
 * the body `WebView`'s `AndroidView` interop handle held back before letting go. On a 360 dp bench
 * the gap measured 15 to 25 dp: a deliberate 40 dp flick moved the page by less than the floor and
 * did not change message, 0 times out of 4 (bench pass, no-go). So the travel is read where it is
 * exact: [PagerTouchTravel] watches the pointer on [PointerEventPass.Initial] and remembers
 * `x_up − x_down`, which is what `ViewPager` compared (`deltaX = x − initialMotionX`), against the
 * very constant this file inherits from it.
 *
 * Everything below is in px, as the pointer reports it: the composable converts the dp, so the two
 * decisions — the floor and the travel machine — need no `Density` and are executed off-device by
 * `PagerFlingDistanceTest`. What they are FED is another matter: the three lines that join the
 * observer to the guard (the getter below, the call site inside the loop, and the `remember` in
 * `MessageScreen`) execute nowhere in a JVM test and are only held by the source lint of
 * `PagerFlingWiringTest`, which reads text and watches nothing happen.
 */

/**
 * The travel a fling must have covered before it may carry the reader to another message, in dp —
 * absolute, never a share of the page.
 *
 * This is `androidx.viewpager.widget.ViewPager.MIN_DISTANCE_FOR_FLING`, the floor Compose dropped,
 * and it is compared against the same quantity ViewPager compared it against: the distance the
 * FINGER covered between touching down and lifting.
 *
 * In dp on purpose: a fraction of the page would follow the screen — wider on a tablet, different
 * again after a rotation — while what is being bounded is a movement of the thumb, which does not
 * grow with the display.
 */
internal const val MIN_FLING_DISTANCE_DP = 25

/**
 * The velocity the pager is ALLOWED TO SEE, given how far the finger actually travelled.
 *
 * Under the floor the answer is `0f` rather than "no fling": a zero velocity is below the library's
 * own 400 dp/s, so `LazyListSnapLayoutInfoProvider` falls back to `ClosestItem` and the page settles
 * on `snapPositionalThreshold` (0.5 by default) — i.e. the SLOW drag keeps working, unchanged, by
 * the library's own path. Refusing the fling outright would mean re-writing that path here. This is
 * what keeps the fix small. `snapPositionalThreshold` governs that slow path only; it has never
 * had any say over a fast fling, and no comment here should suggest otherwise.
 *
 * At the floor exactly, and above it, the velocity passes through UNCHANGED, sign included: the
 * guard only ever removes the accidental page turn, it never re-tunes the deliberate one.
 *
 * @param travelPx the finger's travel for the gesture just released, signed, as
 *   [PagerTouchTravel] recorded it. A finger's travel has no denominator: there is no page size
 *   here, and no fraction to fold over at mid-page.
 * @param minDistancePx [MIN_FLING_DISTANCE_DP] converted by the caller.
 * @param velocityPxPerSec the release velocity the pager was handed, signed.
 */
internal fun flingVelocityForTravel(
    travelPx: Float,
    minDistancePx: Float,
    velocityPxPerSec: Float,
): Float = if (abs(travelPx) < minDistancePx) 0f else velocityPxPerSec

/**
 * What [advanceTravel] carries between pointer events: where the finger went down, how far it has
 * travelled since, and whether it is still down.
 */
internal data class PointerTravel(
    val downX: Float = 0f,
    val travelPx: Float = 0f,
    val fingerDown: Boolean = false,
)

/**
 * One pointer event folded into the travel being tracked.
 *
 * The travel is NOT cleared when the finger lifts; it is cleared by the next PRESS EVENT. The
 * fling runs after the release, so the only travel it can read is the one the gesture that just
 * ended left behind — clear it at the lift and the guard always sees 0 px and no flick ever changes
 * message again.
 *
 * The reset hangs on [isPress] — the EVENT — and never on our own [PointerTravel.fingerDown].
 * A gesture can end without any "no pointer is pressed" event ever reaching this node: the
 * notification shade or the system back gesture takes the touch, the stream is cancelled, and the
 * flag stays true. Keyed on the flag, the NEXT gesture would then be measured from the OLD origin —
 * an arbitrarily large travel, so the accidental 10 dp nudge changes message again, which is the
 * reported defect coming back through a side door. Keyed on the event, whatever state a cancelled
 * gesture left behind, the next press starts from a fresh origin. Nothing here assumes Compose
 * restarts the block after a cancellation; nobody on this project can check that.
 *
 * No stale travel can be read out of the TOUCH stream: a fling needs a drag, a drag needs a press,
 * and a press has zeroed the travel. A fling with no press before it reads the last finger's.
 *
 * The travel kept is SIGNED and FINAL — `x_up − x_down`, not the furthest point reached. That is
 * what `ViewPager` compared, and it closes the there-and-back gesture on the way: leave 60 dp to the
 * left, come back next to the starting point, and the travel is short, so the page does not change.
 *
 * The lift branch is the one that carries the value: an up event still reports the pointer's true
 * final position, whatever the touch slop swallowed and however the events were batched, so it is
 * written in before the gesture is closed.
 *
 * This function is pure and runs off-device, and `PagerFlingDistanceTest` executes it. The STATE
 * it is fed, on the other hand — which event, which coordinate, which boolean — is only ever proven
 * by the source lint in `PagerFlingWiringTest`, which reads text and watches nothing happen.
 *
 * @param isPress whether this event is a `PointerEventType.Press`, i.e. a finger going down.
 * @param anyPressed whether any pointer of the event is still pressed.
 * @param x the pointer's x, in the pager's own coordinates.
 */
internal fun advanceTravel(
    previous: PointerTravel,
    isPress: Boolean,
    anyPressed: Boolean,
    x: Float,
): PointerTravel =
    when {
        isPress -> PointerTravel(downX = x, travelPx = 0f, fingerDown = true)
        anyPressed -> previous.copy(travelPx = x - previous.downX)
        previous.fingerDown -> previous.copy(travelPx = x - previous.downX, fingerDown = false)
        else -> previous
    }

/**
 * The finger's travel across the pager, read from the pointer stream and held for the fling.
 *
 * The observer CONSUMES NOTHING — no `change.consume()`, ever. It watches
 * [PointerEventPass.Initial], which runs before the pager's own drag handling, so consuming there
 * would take the gesture away from the pager, from the body `WebView`'s interop handle and from
 * everything #152 / #97 / #10 settled about who owns a drag. Watching without consuming leaves that
 * chain exactly as it was.
 *
 * [state] is a plain `var`, deliberately NOT a `mutableStateOf`: nothing reads it during
 * composition and nothing should recompose when a finger moves. It is read once, at fling time.
 *
 * The coordinate read is `changes.firstOrNull()`'s, whichever pointer that is: nothing here
 * checks `change.id`, and [advanceTravel]'s `anyPressed` stays true while ANY finger is down. So a
 * second finger landing mid-gesture emits a Press, which RESTARTS the measurement on it — the
 * travel is then that second finger's, from its own origin. Left as is, on purpose: a two-finger
 * gesture is not a page swipe, the body claims it at ACTION_POINTER_DOWN anyway (#152), and the
 * worst outcome is a page that does not turn. Same approximation `BodyWebView.onTouchEvent` already
 * makes by reading `event.x`.
 *
 * The [modifier] is built once, in the constructor, because `Modifier.pointerInput` is not
 * `@Composable`; the holder itself is `remember`ed by the screen.
 */
internal class PagerTouchTravel {
    private var state = PointerTravel()

    /**
     * The travel of the gesture that just ended, signed, in px.
     *
     * Nothing executing can see this getter. Return [PointerTravel.downX] instead — also a
     * `Float`, also compiles without a warning — and the guard is handed the absolute x of the
     * touch down, 0 to 720 px on a 360 dp bench, which clears the 50 px floor almost everywhere:
     * every flick passes and the 2026-08-18 report is back, whole, with a green suite. That is why
     * `PagerFlingWiringTest` pins this class WHOLE and not just the loop.
     */
    val travelPx: Float get() = state.travelPx

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                state = advanceTravel(
                    previous = state,
                    isPress = event.type == PointerEventType.Press,
                    anyPressed = event.changes.any { it.pressed },
                    x = change.position.x,
                )
            }
        }
    }
}

/**
 * The pager's default fling, with [MIN_FLING_DISTANCE_DP] of finger travel demanded of it first.
 *
 * A wrapper, deliberately: everything the library decides — the 400 dp/s threshold, the decay, the
 * snap animation, `snapPositionalThreshold` — is left to [PagerDefaults.flingBehavior] with its own
 * defaults, and the only thing this adds is the velocity filter in front of it.
 *
 * @param travel the holder whose [PagerTouchTravel.modifier] must also be on the pager; without it
 *   the travel stays at 0 px and NO flick can change message.
 */
@Composable
internal fun rememberPageFlingBehavior(
    pagerState: PagerState,
    travel: PagerTouchTravel,
): TargetedFlingBehavior {
    val delegate = PagerDefaults.flingBehavior(state = pagerState)
    val minDistancePx = with(LocalDensity.current) { MIN_FLING_DISTANCE_DP.dp.toPx() }
    return remember(delegate, travel, minDistancePx) {
        DistanceGatedFlingBehavior(delegate, travel, minDistancePx)
    }
}

/** [rememberPageFlingBehavior]'s wrapper: filter the velocity, then let the library do the rest. */
private class DistanceGatedFlingBehavior(
    private val delegate: TargetedFlingBehavior,
    private val travel: PagerTouchTravel,
    private val minDistancePx: Float,
) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        val allowed = flingVelocityForTravel(
            travelPx = travel.travelPx,
            minDistancePx = minDistancePx,
            velocityPxPerSec = initialVelocity,
        )
        return with(delegate) { performFling(allowed, onRemainingDistanceUpdated) }
    }
}

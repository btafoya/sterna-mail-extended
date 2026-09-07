package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How far the FINGER must have travelled before a flick may change message.
 *
 * Reported 2026-08-18: a brief, fast nudge — 10 to 20 dp in 25 to 40 ms — turns the page. The
 * pager was mounted with no `flingBehavior`, so the rule was the library's, and the library's rule
 * is speed alone: above `MinFlingVelocityDp` (400 dp/s) it snaps to the next page whatever the
 * offset, zero included. `androidx.viewpager.widget.ViewPager` wanted BOTH that speed AND 25 dp of
 * travel; the floor under test here is that lost 25 dp.
 *
 * WHICH travel, because the first cut of this fix got it wrong and the bench pass caught it: the
 * pager's own `currentPageOffsetFraction × pageSize` is what the pager has SCROLLED, i.e. the
 * finger's travel minus the touch slop and minus whatever the body WebView's interop handle held
 * back — 15 to 25 dp short on a 360 dp bench, where a deliberate 40 dp flick then changed nothing,
 * 0 times out of 4. So the quantity is the finger's own `x_up − x_down`, and [advanceTravel], the
 * little machine that records it, is executed here alongside the floor itself.
 *
 * Everything is in px, as the pointer reports it — the composable converts the dp — and every
 * expected value below is a LITERAL: a test that re-derives the floor is a copy of the rule, not a
 * check on it.
 *
 * What this file proves stops at the two pure functions. Whether the machine is FED the right
 * event, the right coordinate and the right booleans, whether the guard is handed the travel and
 * not some other Float, and whether the holder survives a recomposition, are all invisible here and
 * held only by the source lint of [PagerFlingWiringTest].
 */
class PagerFlingDistanceTest {

    /** 25 dp at a density of 2.0 — the 360 dp bench the no-go was measured on. */
    private val floorPx = 50f

    /** Well past the library's own 400 dp/s (800 px/s here): a real flick. */
    private val flick = 1200f

    private fun velocity(travelPx: Float, velocityPxPerSec: Float = flick): Float =
        flingVelocityForTravel(
            travelPx = travelPx,
            minDistancePx = floorPx,
            velocityPxPerSec = velocityPxPerSec,
        )

    @Test fun `the floor is androidx ViewPager's own 25 dp`() {
        // Pinned as a number because nothing else can see it: this file works in px, and the dp→px
        // conversion happens in the composable. 25 dp is ViewPager's MIN_DISTANCE_FOR_FLING, the
        // condition Compose dropped when it kept the 400 dp/s. Lower it and the reported nudge —
        // 10 to 20 dp — walks straight back under the guard; raise it and the 40 dp flick the
        // bench measured stops working, which is the no-go this branch came back from.
        assertEquals(25, MIN_FLING_DISTANCE_DP)
    }

    // -- the floor: under it the accident, at it and above it the swipe --------------------------

    @Test fun `a fast nudge shorter than the floor is given no speed at all`() {
        // 49 px = 24.5 dp on the bench, flicked at 1200 px/s. This IS the report: the library
        // would take it to the next message.
        assertEquals(0f, velocity(49f), 0f)
    }

    @Test fun `the same nudge leftwards is given no speed either`() {
        assertEquals(0f, velocity(-49f, velocityPxPerSec = -flick), 0f)
    }

    @Test fun `a nudge that moved nothing at all is given no speed`() {
        assertEquals(0f, velocity(0f), 0f)
    }

    @Test fun `at the floor exactly the flick passes`() {
        // The comparison must be `<`, so equality passes: at exactly the distance ViewPager asked
        // for, the gesture is a swipe, not a brush.
        assertEquals(1200f, velocity(50f), 0f)
    }

    @Test fun `at the floor exactly the leftward flick passes with its sign`() {
        assertEquals(-1200f, velocity(-50f, velocityPxPerSec = -flick), 0f)
    }

    @Test fun `the 40 dp flick the bench measured passes`() {
        // 80 px = 40 dp at density 2.0: the gesture that changed no page at all, 0 out of 4, when
        // this guard was reading the pager's offset instead of the finger's travel.
        assertEquals(1200f, velocity(80f), 0f)
    }

    @Test fun `past the floor the velocity is handed on unchanged`() {
        // Unchanged, not re-tuned: the library decides the page turn exactly as it did before this
        // guard existed.
        assertEquals(1200f, velocity(120f), 0f)
    }

    @Test fun `past the floor a leftward velocity keeps its sign and its size`() {
        assertEquals(-1200f, velocity(-120f, velocityPxPerSec = -flick), 0f)
    }

    @Test fun `a slow release past the floor still gets its zero, unchanged`() {
        // A drag let go of at rest already has no fling: the guard must hand on the 0f it was
        // given, which is what drops the gesture into the library's ClosestItem path — the slow
        // swipe, still decided by snapPositionalThreshold, untouched by any of this.
        assertEquals(0f, velocity(120f, velocityPxPerSec = 0f), 0f)
    }

    // -- the travel machine, executed -------------------------------------------------------------

    @Test fun `a touch down takes the origin and starts from zero`() {
        assertEquals(
            PointerTravel(downX = 200f, travelPx = 0f, fingerDown = true),
            advanceTravel(PointerTravel(), isPress = true, anyPressed = true, x = 200f),
        )
    }

    @Test fun `a finger still down reports its distance from the origin, rightwards`() {
        assertEquals(
            PointerTravel(downX = 200f, travelPx = 60f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 10f, fingerDown = true),
                isPress = false,
                anyPressed = true,
                x = 260f,
            ),
        )
    }

    @Test fun `a finger still down reports a negative travel going left`() {
        assertEquals(
            PointerTravel(downX = 200f, travelPx = -60f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = -10f, fingerDown = true),
                isPress = false,
                anyPressed = true,
                x = 140f,
            ),
        )
    }

    @Test fun `the lift writes the final travel and closes the gesture`() {
        // The up event carries the pointer's true final position whatever the slop swallowed, so
        // the travel is recomputed from it — 280 − 200 = 80 px — and NOT left at the last move's
        // value (60 px here).
        assertEquals(
            PointerTravel(downX = 200f, travelPx = 80f, fingerDown = false),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 60f, fingerDown = true),
                isPress = false,
                anyPressed = false,
                x = 280f,
            ),
        )
    }

    @Test fun `the travel outlives the lift`() {
        // THE reason it is not cleared at the lift: the fling runs after the release, and this is
        // the state it reads. Clear it there and the guard sees 0 px forever — no flick ever
        // changes message, which is exactly the failure the bench measured.
        val lifted = PointerTravel(downX = 200f, travelPx = 80f, fingerDown = false)
        assertEquals(lifted, advanceTravel(lifted, isPress = false, anyPressed = false, x = 999f))
    }

    @Test fun `the next touch down clears the previous gesture's travel`() {
        // The case that tells the real machine from one that resets on every event: the travel of
        // the finished gesture must survive until a NEW finger goes down, and then go to zero with
        // a new origin.
        assertEquals(
            PointerTravel(downX = 500f, travelPx = 0f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 80f, fingerDown = false),
                isPress = true,
                anyPressed = true,
                x = 500f,
            ),
        )
    }

    @Test fun `a press after a gesture that was never closed starts a fresh origin`() {
        // The cancelled gesture: the shade, or the system back gesture, takes the touch and no
        // "nothing is pressed any more" event ever reaches this machine, so fingerDown is still
        // true and a travel of 60 px is still on the books. The NEXT press must not be measured
        // from that stale origin — 500 − 200 = 300 px would sail over the floor and turn the page
        // on a 10 dp nudge, the reported defect back through a side door.
        assertEquals(
            PointerTravel(downX = 500f, travelPx = 0f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 200f, travelPx = 60f, fingerDown = true),
                isPress = true,
                anyPressed = true,
                x = 500f,
            ),
        )
    }

    @Test fun `a press wins over anyPressed, whatever the event says about pressure`() {
        // Order of the branches, pinned: isPress is read FIRST and alone. Written
        // `isPress && anyPressed ->`, or placed under the anyPressed branch, a press whose change
        // no longer reports itself pressed would fall through to the move branch and measure from
        // the previous gesture's origin.
        assertEquals(
            PointerTravel(downX = 400f, travelPx = 0f, fingerDown = true),
            advanceTravel(
                PointerTravel(downX = 100f, travelPx = 90f, fingerDown = true),
                isPress = true,
                anyPressed = false,
                x = 400f,
            ),
        )
    }

    @Test fun `an event with no finger and no gesture open changes nothing`() {
        assertEquals(
            PointerTravel(),
            advanceTravel(PointerTravel(), isPress = false, anyPressed = false, x = 700f),
        )
    }

    @Test fun `a there-and-back gesture ends up with a short travel`() {
        // Signed and final, as ViewPager compared it: 120 px out and back to 5 px from the origin
        // is a 5 px gesture, not a 120 px one.
        var travel = advanceTravel(PointerTravel(), isPress = true, anyPressed = true, x = 200f)
        travel = advanceTravel(travel, isPress = false, anyPressed = true, x = 320f)
        travel = advanceTravel(travel, isPress = false, anyPressed = false, x = 205f)
        assertEquals(5f, travel.travelPx, 0f)
        assertEquals(0f, velocity(5f), 0f)
    }

    @Test fun `a real swipe ends up with the travel the finger covered`() {
        var travel = advanceTravel(PointerTravel(), isPress = true, anyPressed = true, x = 300f)
        travel = advanceTravel(travel, isPress = false, anyPressed = true, x = 260f)
        travel = advanceTravel(travel, isPress = false, anyPressed = false, x = 220f)
        assertEquals(-80f, travel.travelPx, 0f)
        assertEquals(-1200f, velocity(-80f, velocityPxPerSec = -flick), 0f)
    }
}

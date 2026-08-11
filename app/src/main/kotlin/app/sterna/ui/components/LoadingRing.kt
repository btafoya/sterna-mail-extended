package app.sterna.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sterna.ui.rememberMotionEnabled

/**
 * The centred "this screen is still loading" ring.
 *
 * Material 3's indeterminate arc lengthens and shortens as it turns. With the system animations
 * turned off (developer options → `ANIMATOR_DURATION_SCALE = 0`) what is left on screen is a few
 * pixels of stroke on the circumference, which no longer reads as a loading indicator at all —
 * that is what the reporter filmed, and what #63 is about. Why Compose leaves it in that state at
 * scale 0 has not been isolated here, and nothing below depends on the answer. What is established
 * is the setting: the app already honours it for its own motion ([rememberMotionEnabled]), and
 * Material 3 knows nothing about it.
 *
 * So when motion is off the ring is drawn DETERMINATE at a full turn: one whole, perfectly still
 * circle. Nothing is hidden — the screen still says it is loading, it just says it without moving.
 *
 * What it says OUT LOUD stays indeterminate in both branches: a full turn is how the ring is drawn,
 * never a claim that the load is finished. See the resting branch below.
 */
@Composable
fun LoadingRing(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
) {
    val rest = loadingRingProgress(rememberMotionEnabled())
    if (rest == null) {
        CircularProgressIndicator(modifier = modifier, strokeWidth = strokeWidth)
    } else {
        // A closed ring, and nothing else: the determinate indicator's own defaults draw a grey
        // track behind it and hold a 4 dp notch open between the two ends, so at a full turn it
        // would rest as a circle with a gap bitten out of it.
        //
        // The wrapping Box is not decoration — it is what the ring ANNOUNCES. The determinate
        // overload publishes ProgressBarRangeInfo(1f, 0f..1f) of its own accord, so a screen
        // reader would read "100 %" over a load still running, and precisely to the people who
        // switched animations off, which is an accessibility recommendation. `clearAndSetSemantics`
        // clears the semantics of all descendant nodes and sets new ones, so the indicator's
        // progressSemantics() disappears under one Indeterminate node — the same thing the animated
        // branch already says. It has to be this shape: a semantics{} put on the indicator's own
        // modifier would sit as a SIBLING of Material 3's internal progressSemantics() on one node,
        // and which of the two wins is a question this module can neither run nor prove.
        //
        // SIZE: the caller's modifier goes on the Box, so `LoadingRing(Modifier.size(24.dp))`
        // (MessageScreen's body-layout ring) makes the Box 24 dp and hands its child a max of
        // 24 dp. Material 3's own `.size(40.dp)` on the indicator is enforced against the incoming
        // constraints — documented on Modifier.size: the requested size is respected "unless the
        // incoming measurement Constraints do not allow it" — so it lands at 24 dp, exactly as it
        // did when the caller's modifier was chained onto the indicator directly. No explicit size
        // is forced here: doing so would either freeze the default 40 dp or stretch the ring for a
        // caller passing fill/min constraints. (All fifteen sites pass align(), size(24.dp) or
        // nothing; align() is parent data and is read off the Box, which is now the child.)
        Box(
            modifier.clearAndSetSemantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { rest },
                strokeWidth = strokeWidth,
                trackColor = Color.Transparent,
                strokeCap = StrokeCap.Butt,
                gapSize = 0.dp,
            )
        }
    }
}

/** The turn a resting ring is drawn at: the WHOLE circle, never a fragment of one. */
internal const val LOADING_RING_REST_TURN = 1f

/**
 * How much of the ring to draw, or `null` to let Material 3 run its own indeterminate animation.
 *
 * The decision lives out here, and is executed by `LoadingRingTest`, because [LoadingRing] itself
 * cannot be run in this module. It is one question — is decorative motion allowed — but it is the
 * whole fix: answer `null` with animations off and the loading screens go back to the unreadable
 * fragment of #63, answer a turn with them on and every loading screen freezes into a static ring
 * that no longer distinguishes "working" from "stuck".
 */
internal fun loadingRingProgress(motionOn: Boolean): Float? =
    if (motionOn) null else LOADING_RING_REST_TURN

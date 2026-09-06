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
 * Material 3's indeterminate arc lengthens and shortens as it turns, and with the system animation
 * scale at 0 what is left on screen is a few pixels of stroke that no longer read as a loading
 * indicator at all — that is what the reporter filmed (#63). Why Compose leaves it in that state has
 * not been isolated here, and nothing below depends on the answer.
 *
 * So when motion is off the ring is drawn DETERMINATE at a full turn: one whole, still circle. What
 * it says OUT LOUD stays indeterminate in both branches — a full turn is how the ring is drawn,
 * never a claim that the load is finished.
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
        // track behind it and hold a notch open between the ends, so at a full turn it would rest
        // as a circle with a gap bitten out of it.
        //
        // The wrapping Box is not decoration — it is what the ring ANNOUNCES. The determinate
        // overload publishes ProgressBarRangeInfo(1f, …) of its own accord, so a screen reader
        // would read "100 %" over a load still running, and precisely to the people who switched
        // animations off. `clearAndSetSemantics` replaces every descendant's semantics with one
        // Indeterminate node; a semantics{} on the indicator's own modifier would instead sit as a
        // sibling of Material 3's internal progressSemantics(), and which wins this module cannot
        // prove.
        //
        // SIZE: the caller's modifier goes on the Box, and Material 3's own `.size(40.dp)` on the
        // indicator is enforced against the incoming constraints, so it lands wherever the caller
        // asked. No explicit size is forced here: doing so would either freeze the default or
        // stretch the ring for a caller passing fill constraints.
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
 * The decision lives out here, and is executed by `LoadingRingTest`, because [LoadingRing] itself
 * cannot be run in this module. Answer `null` with animations off and the loading screens go back
 * to the unreadable fragment of #63; answer a turn with them on and every screen freezes into a
 * static ring that no longer distinguishes "working" from "stuck".
 */
internal fun loadingRingProgress(motionOn: Boolean): Float? =
    if (motionOn) null else LOADING_RING_REST_TURN

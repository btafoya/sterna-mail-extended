package app.sterna.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

internal val MinTouchTarget = 48.dp

/** What [touchTargetOverflow] must add on each end of a side drawn at [drawn] to reach [MinTouchTarget]. */
internal fun touchOverflow(drawn: Dp): Dp = ((MinTouchTarget - drawn) / 2).coerceAtLeast(0.dp)

internal fun overflowReportedExtent(measured: Int, overflowPx: Int): Int = (measured - 2 * overflowPx).coerceAtLeast(0)

/**
 * Measures the content larger by the overflow on each side but reports it at its own size, so the
 * pointer target grows into the neighbours' space without moving them. Not
 * `minimumInteractiveComponentSize()`: that one reports the 48 dp too and pushes the neighbours.
 *
 * The modifiers that follow decide what the room is used for: a `clickable` and then a `padding` of
 * the same overflow keep the drawing at its size inside a larger target. An ancestor that clips, a
 * `Surface` with a shape for one, cuts the overflowing part of the target along with the drawing.
 * So does a `clip` ahead of the `clickable` in the same chain: a clipped drawing takes its click
 * outside the clip and hands the ripple back inside through a shared interaction source.
 */
internal fun Modifier.touchTargetOverflow(horizontal: Dp, vertical: Dp): Modifier = layout { measurable, constraints ->
    val x = horizontal.roundToPx()
    val y = vertical.roundToPx()
    val placeable = measurable.measure(constraints.offset(2 * x, 2 * y))
    layout(overflowReportedExtent(placeable.width, x), overflowReportedExtent(placeable.height, y)) {
        placeable.place(-x, -y)
    }
}

internal fun Modifier.touchTargetOverflow(overflow: Dp): Modifier = touchTargetOverflow(overflow, overflow)

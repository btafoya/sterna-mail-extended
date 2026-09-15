package app.sterna.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The composer's header gives way to the body: past [composeHeaderMaxHeightPx] it stops growing and
 * scrolls, so the body always keeps [composeBodyMinHeight] and the formatting bar keeps its row.
 * While the header fits under its cap nothing changes, and when the cap rises again (keyboard down)
 * `ScrollState` clamps its offset back to 0 on its own.
 *
 * It reads the height the enclosing `Column` offers the header, in the same measure pass: that is
 * the column's height minus the unweighted children above it. The bar sits below the body and is
 * measured after the header, so it is reserved through [barShown] rather than found in the
 * constraints.
 *
 * A layout modifier and not `BoxWithConstraints`: the height moves on every frame of the keyboard's
 * animation, and a subcomposition would recompose the whole editor on each of them, where this only
 * measures again. Not `onSizeChanged` either: read one frame late, the header shakes as the keyboard
 * slides. Must come before `verticalScroll`, which throws when measured without a bounded height.
 */
fun Modifier.yieldToBody(bodyMin: Dp, barShown: Boolean): Modifier = layout { measurable, constraints ->
    val cap = composeHeaderMaxHeightPx(constraints.minHeight, constraints.maxHeight, bodyMin, barShown, this)
    val placeable = measurable.measure(constraints.copy(maxHeight = cap))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}

/**
 * The tallest the header may be, in px, out of the [maxHeightPx] its column offers: that height less
 * the body's minimum and, while it is shown, the formatting bar, never below one header row, and
 * never outside the incoming constraints.
 */
fun composeHeaderMaxHeightPx(
    minHeightPx: Int,
    maxHeightPx: Int,
    bodyMin: Dp,
    barShown: Boolean,
    density: Density,
): Int {
    val reservePx = with(density) { (if (barShown) bodyMin + FORMATTING_BAR_HEIGHT else bodyMin).roundToPx() }
    val floorPx = with(density) { HEADER_ROW_HEIGHT.roundToPx() }
    return (maxHeightPx - reservePx).coerceAtLeast(floorPx).coerceIn(minHeightPx, maxHeightPx)
}

/**
 * Three lines of the body's own text, plus the margins its decoration box pads it with. [lineHeight]
 * is the field's text style's, in sp, so the minimum follows the system font size.
 */
fun composeBodyMinHeight(lineHeight: TextUnit, density: Density): Dp {
    val line = if (lineHeight.isSp) lineHeight else BODY_LINE_HEIGHT_FALLBACK
    return with(density) { line.toDp() } * BODY_MIN_LINES + BODY_PADDING_TOP + BODY_PADDING_BOTTOM
}

/** How many lines of the body stay on screen however tall the header grows. */
const val BODY_MIN_LINES: Int = 3

/**
 * Stands in for a line height given in em or left unspecified, which has no size of its own here:
 * `bodyLarge`'s, the style `MaterialTheme` hands the body field.
 */
val BODY_LINE_HEIGHT_FALLBACK: TextUnit = 24.sp

/** The body's inner margins, set on its decoration box and counted in [composeBodyMinHeight]. */
val BODY_PADDING_TOP: Dp = 12.dp
val BODY_PADDING_BOTTOM: Dp = 16.dp

/** Set on the formatting bar and reserved under the body by [yieldToBody]: one number for both. */
val FORMATTING_BAR_HEIGHT: Dp = 48.dp

/** The header never shrinks below one of its rows, the 48 dp touch target each of them keeps. */
val HEADER_ROW_HEIGHT: Dp = 48.dp

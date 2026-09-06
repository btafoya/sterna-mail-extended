package app.sterna.ui.message

import kotlin.math.roundToInt

/**
 * Whether the reader's bottom Reply/Forward bar is on screen.
 *
 * The setting behind [enabled] (#63) does not change WHEN the bar appears; it lets a reader remove
 * it. Both its actions stay reachable without it, from the top bar and its menu.
 */
internal fun replyBarVisible(enabled: Boolean, bodyReady: Boolean, wantsBar: Boolean): Boolean =
    enabled && bodyReady && wantsBar

/**
 * The bar's assumed height before it has been measured, in dp — a first frame laid out with no
 * reserve has its last lines covered the moment the bar reveals.
 */
internal const val REPLY_BAR_FALLBACK_DP = 76f

/** The gap between the end of the text and the bar, in dp: it must not sit on the last line. */
internal const val REPLY_BAR_CLEARANCE_DP = 4f

/**
 * How much blank the BODY reserves under itself for that bar, in device pixels.
 *
 * The space is reserved inside the HTML document (`<div class="s-end">`), not in the Compose layout:
 * hide the bar without answering 0 here and every message keeps ~80 dp of blank under nothing, while
 * removing the invisible measuring copy does not help — [measuredPx] then stays 0.
 *
 * The two dp values are converted HERE: passed in already converted, swapping the two arguments
 * reserves the bar plus 76 dp instead of plus 4, and no test could reach it.
 */
internal fun bodyBottomInsetPx(
    enabled: Boolean,
    measuredPx: Int,
    density: Float,
): Int {
    if (!enabled) return 0
    val bar = if (measuredPx > 0) measuredPx else (REPLY_BAR_FALLBACK_DP * density).roundToInt()
    return bar + (REPLY_BAR_CLEARANCE_DP * density).roundToInt()
}

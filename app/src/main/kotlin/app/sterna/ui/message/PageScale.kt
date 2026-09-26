package app.sterna.ui.message

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The body's page scale as `WebViewClient.onScaleChanged` last reported it, and the scale its
 * document was laid out at, the one scale at which both [bodySpacerCss] blanks measure exactly the
 * header and the bar (#171). A new document starts from a fresh [PageScale].
 *
 * [layoutScale] stays null until the body is revealed: reports that arrive while it loads are the
 * WebView fitting the page, and taking one for the reference would fold the header at opening.
 * [bodyRevealed] is called on a height report of the current document and nowhere else: a reload
 * does not reveal the body again, and the fallbacks that reveal it without measuring it can run
 * before that document is laid out. A body only they revealed keeps a null [layoutScale].
 */
internal data class PageScale(
    val scale: Float = 0f,
    val layoutScale: Float? = null,
    private val revealed: Boolean = false,
) {
    /** With no report before the reveal, the first one after it carries the layout scale as [oldScale]. */
    fun scaled(oldScale: Float, newScale: Float): PageScale = copy(
        scale = newScale,
        layoutScale = layoutScale ?: oldScale.takeIf { revealed && it > 0f },
    )

    fun bodyRevealed(): PageScale =
        if (revealed) this else copy(revealed = true, layoutScale = scale.takeIf { it > 0f })
}

/**
 * How far the collapsing header is pushed up, in device px: the scroll, plus what a pinch below
 * [layoutScale] took off the top blank. A pinch rescales the page without a re-layout, so that blank
 * paints `headerHeightPx × scale / layoutScale`. Above [layoutScale] the header does not come down.
 */
internal fun headerCollapsePx(scrollY: Int, headerHeightPx: Int, scale: Float, layoutScale: Float?): Int =
    minOf(scrollY + pinchDeficitPx(headerHeightPx, scale, layoutScale), headerHeightPx)

/** Whether a pinch below [layoutScale] left the Reply/Forward bar over at least a pixel of text. */
internal fun barHiddenByZoom(bottomInsetPx: Int, scale: Float, layoutScale: Float?): Boolean =
    bottomInsetPx * (1.0 - pinchRatio(scale, layoutScale)) >= 1.0

private fun pinchDeficitPx(reservedPx: Int, scale: Float, layoutScale: Float?): Int =
    max(0, (reservedPx * (1.0 - pinchRatio(scale, layoutScale))).roundToInt())

// An unknown layout scale is a ratio of 1, never a guess: taken as the device density, a newsletter
// that lays out shrunk would open with its header folded and its bar gone.
private fun pinchRatio(scale: Float, layoutScale: Float?): Double {
    val s0 = layoutScale ?: return 1.0
    if (s0 <= 0f || scale <= 0f) return 1.0
    return scale.toDouble() / s0.toDouble()
}

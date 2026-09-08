package app.sterna.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.components.monogramColor
import app.sterna.ui.components.onAccentColor

/**
 * The two decisions behind a widget badge, without a single Android type, which is the point of the
 * of the file.
 */

/**
 * The colour a row's badge is painted in: the palette's own answer for that slot, resolved against
 */
internal fun widgetMonogramArgb(monogram: RecentRowMonogram, ramps: List<ToneRamp>): Int =
    monogramColor(monogram.slot, ramps).toArgb()

/**
 * The colour of the LETTER on a disc of [discArgb], black or white by WCAG luminance, exactly as
 * the in-app badge picks it ([onAccentColor]).
 */
internal fun widgetMonogramLetterArgb(discArgb: Int): Int = onAccentColor(Color(discArgb)).toArgb()

/**
 * What makes two badges the same cached bitmap: the letter, the resolved colour, and the side in
 * pixels.
 */
internal fun widgetMonogramKey(initial: String, argb: Int, side: Int): String = "$initial|$argb|$side"

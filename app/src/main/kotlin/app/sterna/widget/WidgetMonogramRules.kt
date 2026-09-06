package app.sterna.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.components.monogramColor
import app.sterna.ui.components.onAccentColor

/**
 * The two decisions behind a widget badge, WITHOUT a single Android type — which is the point of the
 * file. [WidgetMonogram] next door owns the drawing, and merely TOUCHING that object would run its
 * class initialiser. So the two things worth checking — which colour a badge comes out, and what
 * makes two badges the same cached bitmap — live here and are RUN by [WidgetMonogramRulesTest].
 */

/**
 * The colour a row's badge is painted in: the palette's own answer for that slot, resolved against
 * the ramps in force AT THE MOMENT THE LAUNCHER ASKS. No tint rule is restated here —
 * [monogramColor] is the app's own, and a second derivation is a copy that drifts.
 */
internal fun widgetMonogramArgb(monogram: RecentRowMonogram, ramps: List<ToneRamp>): Int =
    monogramColor(monogram.slot, ramps).toArgb()

/**
 * The colour of the LETTER on a disc of [discArgb] — black or white by WCAG luminance, exactly as
 * the in-app badge picks it. Never a hard-coded white: on the palest tone of a light scheme it
 * reads at 1.40:1, an initial that is technically drawn and practically invisible.
 */
internal fun widgetMonogramLetterArgb(discArgb: Int): Int = onAccentColor(Color(discArgb)).toArgb()

/**
 * What makes two badges THE SAME cached bitmap: the letter, the RESOLVED colour, and the side in
 * pixels.
 *
 * The colour, not the slot. A slot does not change when the theme does, so a key built on it would
 * serve the previous theme's disc for as long as the process lives, with nothing to invalidate it
 * against. With the colour inside the key a stale entry is simply never asked for again. The side is
 * in the key because it is read from the resources and differs between densities.
 */
internal fun widgetMonogramKey(initial: String, argb: Int, side: Int): String = "$initial|$argb|$side"

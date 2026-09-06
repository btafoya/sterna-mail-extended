package app.sterna.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.sterna.ui.components.ToneRamp
import app.sterna.ui.components.monogramColor
import app.sterna.ui.components.onAccentColor

/**
 * The two decisions behind a widget badge, WITHOUT a single Android type — which is the whole point
 * of the file.
 *
 * [WidgetMonogram] next door owns the drawing: `Bitmap`, `Canvas`, `Paint`, `LruCache`. None of
 * those can be executed in this repository's JVM suite (there is no Robolectric here), and merely
 * TOUCHING that object would run its class initialiser, which builds an `android.util.LruCache`.
 * So the two things worth checking — which colour a badge comes out, and what makes two badges the
 * same cached bitmap — live here, are pure, and are RUN by [WidgetMonogramRulesTest].
 */

/**
 * The colour a row's badge is painted in: the palette's own answer for that slot, resolved against
 * the ramps in force AT THE MOMENT THE LAUNCHER ASKS.
 *
 * No tint rule is restated here. [monogramColor] is the app's, the one the message list draws
 * with, and the home screen agreeing with the list is the point of the whole pane — a second
 * derivation, however faithful the day it is written, is a copy that drifts.
 */
internal fun widgetMonogramArgb(monogram: RecentRowMonogram, ramps: List<ToneRamp>): Int =
    monogramColor(monogram.slot, ramps).toArgb()

/**
 * The colour of the LETTER on a disc of [discArgb] — black or white, by WCAG luminance, exactly as
 * the in-app badge picks it ([onAccentColor]).
 *
 * Never a hard-coded white. The palest tone of a light scheme is a pastel; white on it reads at
 * 1.40:1 on the yellow end of the account palette, i.e. an initial that is technically drawn and
 * practically invisible, on a surface nobody can debug.
 */
internal fun widgetMonogramLetterArgb(discArgb: Int): Int = onAccentColor(Color(discArgb)).toArgb()

/**
 * What makes two badges THE SAME cached bitmap: the letter, the RESOLVED colour, and the side in
 * pixels.
 *
 * THE COLOUR, NOT THE SLOT. A slot does not change when the theme does — the same message keeps
 * family 2, tone 4 through a switch to dark — so a key built on it would serve the previous theme's
 * disc for as long as the process lives, and there is nothing to invalidate it against:
 * `updatePeriodMillis="0"`, no periodic redraw, no configuration listener. With the colour inside
 * the key there is no invalidation to hold at all: a stale entry is simply never asked for again
 * and the LRU evicts it.
 *
 * The side is in the key because it is read from the resources ([WidgetMonogram]), so it differs
 * between densities and would silently reuse a bitmap of the wrong size.
 */
internal fun widgetMonogramKey(initial: String, argb: Int, side: Int): String = "$initial|$argb|$side"

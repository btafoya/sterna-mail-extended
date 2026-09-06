package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * How tall the recipient suggestion menu may be, given the room left under the field once the keyboard
 * is up — and whether it may be shown at all. A flat [SUGGESTION_MENU_MAX_HEIGHT] is taller than
 * everything below the To: field on a small screen, so the menu hung over the keyboard (#143).
 * What is established about the keyboard, and what is not. On API 30+ the window is not resized for
 * the IME, and the keyboard arrives only as an inset — the case this is written for. On API 26-29 the
 * manifest still carries `adjustResize`, so the root may ALSO shrink and the keyboard be subtracted
 * twice, which is exactly the reporter's device. Erring towards not showing is the safe side of that
 * unknown, but it IS an unknown: this paragraph must not be rewritten into a claim.
 */
fun suggestionMenuMaxHeight(
    windowHeightPx: Int,
    fieldBottomPx: Int,
    imeHeightPx: Int,
    density: Density,
): Dp? {
    val freePx = windowHeightPx - fieldBottomPx - imeHeightPx
    val free = with(density) { freePx.toDp() }
    if (free < minimumSuggestionRow(density)) return null
    return minOf(free, SUGGESTION_MENU_MAX_HEIGHT)
}

/**
 * The room one suggestion needs at THIS font scale.
 *
 * [SUGGESTION_ROW_MIN_HEIGHT] is the avatar plus its padding and is only true at `fontScale 1`: the
 * row's two text lines grow with the system font size and push it past 80 dp at `font_scale 2.0`,
 * so measuring against the un-scaled 56 dp showed the sliced row this fix refuses to draw.
 *
 * Scaling is clamped at 1, and the error is deliberately one-sided: at a large font scale the menu
 * disappears slightly earlier than strictly necessary, which is the honest side to be wrong on.
 */
fun minimumSuggestionRow(density: Density): Dp =
    SUGGESTION_ROW_MIN_HEIGHT * max(1f, density.fontScale)

/** The cap the menu keeps whenever there is room for it: about four and a half rows. */
val SUGGESTION_MENU_MAX_HEIGHT: Dp = 256.dp

/**
 * One suggestion row at `fontScale 1`: a 40 dp avatar ([app.sterna.ui.components.ContactAvatar])
 * plus 8 dp of padding above and below. Scale it with [minimumSuggestionRow] before comparing it
 * to anything — the row's two text lines are not in this number.
 */
val SUGGESTION_ROW_MIN_HEIGHT: Dp = 56.dp

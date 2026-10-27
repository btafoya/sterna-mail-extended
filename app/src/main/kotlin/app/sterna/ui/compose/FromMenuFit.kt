package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How tall the composer's From menu may be so that Material3's `DropdownMenuPositionProvider`
 * still opens it under its anchor. The provider only keeps that position when
 * `anchorBottom + menuHeight <= visibleFrameHeight - POPUP_VERTICAL_MARGIN.roundToPx()`; past it
 * the menu moves up and covers the From row.
 *
 * [visibleFrameHeightPx] is the height of `View.getWindowVisibleDisplayFrame`, the frame the popup
 * measures itself against: it excludes the status bar, the navigation bar and the keyboard, so a
 * menu that fits it also ends above the navigation bar. [anchorBottomPx] is the anchor's bottom in
 * window coordinates (`positionInWindow`), the provider's own frame of reference. Both are read in
 * the composer's window, never in the popup's.
 *
 * Never below [FROM_MENU_MIN_HEIGHT]: a zero or negative cap would open an empty menu. Past that
 * floor the provider opens the menu above the anchor, or centred on it, which beats a menu with
 * no entry. [Dp.Infinity] while the
 * window has not been measured, rather than shrinking the menu to its floor on a guess.
 */
fun fromMenuMaxHeight(
    visibleFrameHeightPx: Int,
    anchorBottomPx: Int,
    density: Density,
): Dp {
    if (visibleFrameHeightPx <= 0) return Dp.Infinity
    val room = with(density) {
        (visibleFrameHeightPx - POPUP_VERTICAL_MARGIN.roundToPx() - anchorBottomPx).toDp()
    }
    return maxOf(room - FROM_MENU_BOTTOM_MARGIN, FROM_MENU_MIN_HEIGHT)
}

/** Material3's own `MenuVerticalMargin`, which `DropdownMenu` keeps between the menu and the visible frame's edges. */
val POPUP_VERTICAL_MARGIN: Dp = 48.dp

/** Absorbs the rounding of the cap to whole pixels and keeps the menu's shadow off the limit. */
val FROM_MENU_BOTTOM_MARGIN: Dp = 8.dp

/** One 48 dp menu entry plus the menu's own 8 dp padding above and below it. */
val FROM_MENU_MIN_HEIGHT: Dp = 64.dp

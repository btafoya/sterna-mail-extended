package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour test: it executes [fromMenuMaxHeight] with the geometry measured on the 1080×2400 bench
 * (density 2.625, From row ending at 390 px in the window, navigation bar 63 px in gestures, 126 px
 * with three buttons) and checks the capped menu against the rule Material3's
 * `DropdownMenuPositionProvider` applies before opening a menu under its anchor:
 * `anchorBottom + menuHeight <= visibleFrameHeight - 48 dp`, where the visible frame is the
 * window minus the status and navigation bars. A menu failing it opens over the From row.
 *
 * The status bar height is not taken from the bench: 63 px stands for any bar that makes the
 * visible frame shorter than the screen, which is the case the rule exists for.
 */
class FromMenuFitTest {

    private val density = Density(2.625f)

    private val screenPx = 2400
    private val statusBarPx = 63
    private val anchorBottomPx = 390

    /** `MenuVerticalMargin.roundToPx()` at this density. */
    private val providerMarginPx = 126

    private fun visibleFramePx(navigationBarPx: Int) = screenPx - statusBarPx - navigationBarPx

    private fun cap(navigationBarPx: Int, anchorBottomPx: Int = this.anchorBottomPx): Dp =
        fromMenuMaxHeight(
            visibleFrameHeightPx = visibleFramePx(navigationBarPx),
            anchorBottomPx = anchorBottomPx,
            density = density,
        )

    private fun menuBottomPx(cap: Dp): Int = anchorBottomPx + with(density) { cap.roundToPx() }

    private fun assertOpensUnderTheRow(navigationBarPx: Int) {
        val cap = cap(navigationBarPx)
        val limit = visibleFramePx(navigationBarPx) - providerMarginPx
        assertTrue(
            "the capped menu's bottom (${menuBottomPx(cap)} px) must stay within the provider's limit " +
                "($limit px), or it opens over the From row",
            menuBottomPx(cap) <= limit,
        )
        assertTrue(
            "the capped menu's bottom (${menuBottomPx(cap)} px) must not pass the top of the bar " +
                "(${screenPx - navigationBarPx} px)",
            menuBottomPx(cap) <= screenPx - navigationBarPx,
        )
        val slackPx = with(density) { (FROM_MENU_BOTTOM_MARGIN + 1.dp).toPx() }
        assertTrue(
            "the capped menu (${menuBottomPx(cap)} px) must use the room down to the limit ($limit px) " +
                "less its own margin",
            menuBottomPx(cap) >= limit - slackPx,
        )
    }

    @Test fun `gesture navigation, the menu opens under the From row`() = assertOpensUnderTheRow(63)

    @Test fun `three-button navigation, the menu opens under the From row`() = assertOpensUnderTheRow(126)

    @Test fun `with almost no room the menu still shows one whole entry`() {
        val cap = fromMenuMaxHeight(visibleFrameHeightPx = 400, anchorBottomPx = 300, density = density)
        assertEquals("below one entry of room the menu falls back to the floor", FROM_MENU_MIN_HEIGHT, cap)
        assertTrue("the floor must hold one 48 dp entry and the menu's 8 dp paddings", FROM_MENU_MIN_HEIGHT >= 64.dp)
    }

    @Test fun `before the window is measured nothing is capped`() {
        val cap = fromMenuMaxHeight(visibleFrameHeightPx = 0, anchorBottomPx = 0, density = density)
        assertEquals("an unmeasured window must not shrink the menu to its floor", Dp.Infinity, cap)
    }
}

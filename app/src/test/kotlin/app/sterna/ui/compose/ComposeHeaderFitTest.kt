package app.sterna.ui.compose

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour test: it executes the two decisions `yieldToBody` measures with and pins numbers. At
 * density 1 a px is a dp, so every expected value reads as the arithmetic in its comment.
 */
class ComposeHeaderFitTest {

    private val one = Density(1f)

    @Test fun `the body's minimum is three lines of its text plus the decoration box's margins`() {
        // 3 × 24 + 12 + 16.
        assertEquals(
            "a 24 sp line must give the body 100 dp: three lines, the 12 dp top margin and the 16 dp " +
                "bottom margin. Short of the margins, the third line is cut by the keyboard.",
            100.dp,
            composeBodyMinHeight(24.sp, one),
        )
        // 3 × 20 + 12 + 16.
        assertEquals("the minimum must follow the style's line height", 88.dp, composeBodyMinHeight(20.sp, one))
    }

    @Test fun `the body's minimum does not depend on the screen density`() {
        assertEquals(
            "sp are independent of the density: 24 sp at density 2 is still 100 dp",
            100.dp,
            composeBodyMinHeight(24.sp, Density(2f)),
        )
    }

    @Test fun `the body's minimum follows the system font size`() {
        assertTrue(
            "at font scale 1.5 the three lines are taller, so the minimum must be taller than 100 dp",
            composeBodyMinHeight(24.sp, Density(1f, fontScale = 1.5f)) > 100.dp,
        )
    }

    @Test fun `a line height with no size of its own falls back to bodyLarge's 24 sp`() {
        assertEquals(
            "an unspecified line height must count as 24 sp, 100 dp in all, and not throw",
            100.dp,
            composeBodyMinHeight(TextUnit.Unspecified, one),
        )
    }

    @Test fun `with the bar shown the header gets what the body and the bar leave`() {
        // 400 − 100 − 48: the symptom's case, keyboard up and the body focused.
        assertEquals(
            "the formatting bar's 48 dp must come off the header's cap while the body has the focus, " +
                "or the bar takes the body's third line",
            252,
            composeHeaderMaxHeightPx(0, 400, bodyMin = 100.dp, barShown = true, density = one),
        )
    }

    @Test fun `with the bar hidden only the body is reserved`() {
        // 400 − 100.
        assertEquals(
            "a recipient field has the focus: no bar is drawn, so nothing but the body's minimum is " +
                "reserved",
            300,
            composeHeaderMaxHeightPx(0, 400, bodyMin = 100.dp, barShown = false, density = one),
        )
    }

    @Test fun `the header never shrinks below one of its rows`() {
        // 120 − 100 = 20, under the 48 dp floor.
        assertEquals(
            "on a window this short the header keeps one 48 dp row, the field being typed in",
            48,
            composeHeaderMaxHeightPx(0, 120, bodyMin = 100.dp, barShown = false, density = one),
        )
    }

    @Test fun `the cap never leaves the incoming constraints`() {
        assertEquals(
            "with less than one row offered, the cap is what is offered: the floor must not push the " +
                "header past its column",
            30,
            composeHeaderMaxHeightPx(0, 30, bodyMin = 100.dp, barShown = false, density = one),
        )
        assertEquals(
            "a minimum height imposed from above, taller than the one-row floor, must survive the cap",
            60,
            composeHeaderMaxHeightPx(60, 120, bodyMin = 100.dp, barShown = false, density = one),
        )
    }

    @Test fun `with the keyboard down the cap is far above any header`() {
        // 2000 − 100 − 48: a header with every field open is a few hundred dp.
        assertEquals(
            "with room to spare the cap must be the whole room less the reserve, so the header is " +
                "measured at its natural height and has nothing to scroll",
            1852,
            composeHeaderMaxHeightPx(0, 2000, bodyMin = 100.dp, barShown = true, density = one),
        )
    }

    @Test fun `the reserve is converted at the screen's density`() {
        // Density 2: 800 − 200 − 96; then 250 − 200 = 50, under the 96 px floor.
        assertEquals(
            "at density 2, 100 dp of body and 48 dp of bar are 296 px",
            504,
            composeHeaderMaxHeightPx(0, 800, bodyMin = 100.dp, barShown = true, density = Density(2f)),
        )
        assertEquals(
            "…and the one-row floor is 96 px",
            96,
            composeHeaderMaxHeightPx(0, 250, bodyMin = 100.dp, barShown = false, density = Density(2f)),
        )
    }

    @Test fun `the forwarded block under the note is reserved with the body`() {
        // 400 − 100 − 48 − 48: keyboard up, the note focused, the block folded to its title.
        assertEquals(
            "the forwarded block sits under the note and is measured before it: its height must " +
                "come off the header's cap, or it eats the note's three lines",
            204,
            composeHeaderMaxHeightPx(0, 400, bodyMin = 100.dp, barShown = true, density = one, blockBelow = 48.dp),
        )
        // Density 2: 800 − 200 − 320.
        assertEquals(
            "the block's reserve is converted at the screen's density like the rest",
            280,
            composeHeaderMaxHeightPx(0, 800, bodyMin = 100.dp, barShown = false, density = Density(2f), blockBelow = 160.dp),
        )
    }

    @Test fun `the block reserves nothing when absent, its folded height with the note focused, its ceiling otherwise`() {
        val ready = ForwardedPreview.Ready("x")
        assertEquals(0.dp, forwardedBlockReserve(ForwardedPreview.None, noteFocused = false))
        assertEquals(0.dp, forwardedBlockReserve(ForwardedPreview.None, noteFocused = true))
        assertEquals(FORWARDED_BLOCK_FOLDED_HEIGHT, forwardedBlockReserve(ready, noteFocused = true))
        assertEquals(FORWARDED_BLOCK_FOLDED_HEIGHT, forwardedBlockReserve(ForwardedPreview.Pending, noteFocused = true))
        assertEquals(FORWARDED_BLOCK_MAX_HEIGHT, forwardedBlockReserve(ready, noteFocused = false))
        assertEquals(FORWARDED_BLOCK_MAX_HEIGHT, forwardedBlockReserve(ForwardedPreview.Pending, noteFocused = false))
    }
}

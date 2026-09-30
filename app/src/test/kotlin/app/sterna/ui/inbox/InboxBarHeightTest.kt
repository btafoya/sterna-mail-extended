package app.sterna.ui.inbox

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour test: it executes `inboxBarHeight` and pins numbers. At density 1 a px is a dp. A
 * `Density` with a font scale converts sp through Compose's nonlinear table, the device's own: at
 * scale 2, 24 sp is 36 dp and 16 sp is 28 dp, not 48 and 32.
 */
class InboxBarHeightTest {

    @Test fun `at font scale 1 the bar keeps Material's 64 dp`() {
        assertEquals(
            "with titleMedium 24 sp and labelMedium 16 sp at scale 1 the bar must stay 64 dp, " +
                "the look of the list is unchanged",
            64.dp,
            inboxBarHeight(24.sp, 16.sp, Density(1f)),
        )
        assertEquals(
            "sp are independent of the screen density: at density 3 the bar is still 64 dp",
            64.dp,
            inboxBarHeight(24.sp, 16.sp, Density(3f)),
        )
    }

    @Test fun `at font scale 2 the bar grows to hold both lines and their margin`() {
        val height = inboxBarHeight(24.sp, 16.sp, Density(1f, fontScale = 2f))
        // 36 + 28 + 24.
        assertEquals(
            "at font scale 2 the two lines are 36 + 28 dp tall; with the 24 dp margin the bar must " +
                "be 88 dp, or the account line is clipped by the bar's bottom edge",
            88.dp,
            height,
        )
        assertTrue("at font scale 2 the bar must be taller than 64 dp, was $height", height > 64.dp)
    }

    @Test fun `the bar never shrinks below 64 dp`() {
        assertEquals(
            "small line heights must not shrink the bar below Material's 64 dp",
            64.dp,
            inboxBarHeight(10.sp, 10.sp, Density(1f, fontScale = 0.85f)),
        )
    }

    @Test fun `unspecified line heights fall back to Material's values`() {
        assertEquals(
            "unspecified line heights must count as 24 sp and 16 sp and not throw",
            88.dp,
            inboxBarHeight(TextUnit.Unspecified, TextUnit.Unspecified, Density(1f, fontScale = 2f)),
        )
    }
}

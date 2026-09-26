package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a pinch below the layout scale does to the two Compose layers over the body (#171), executed.
 * A pinch rescales the page without a re-layout, so each `vh` blank shrinks by the ratio
 * scale / layoutScale while the header and the bar keep their size.
 */
class PinchedSpacerTest {

    private val header = 538
    private val inset = 250

    @Test fun `an unknown layout scale leaves the header and the bar exactly as before`() {
        // Scales a WebView really reports: density-relative, below 1 for a shrunk newsletter, up to
        // the density and beyond. None of them may be taken as the reference when none is known.
        for (scale in listOf(0.5f, 1f, 1.575f, 2.625f, 3.5f)) {
            for (scrollY in listOf(0, 100, 538, 2000)) {
                assertEquals(
                    "layoutScale unknown, scale $scale, scrollY $scrollY: the header must collapse by " +
                        "the scroll alone, as it did before any pinch was measured — anything more " +
                        "folds the header of a message nobody pinched",
                    minOf(scrollY, header), headerCollapsePx(scrollY, header, scale, null),
                )
            }
            assertFalse(
                "layoutScale unknown, scale $scale: the bar must not be taken away",
                barHiddenByZoom(inset, scale, null),
            )
        }
    }

    @Test fun `at the layout scale nothing moves, whatever that scale is`() {
        // 1.575 is initial-scale=0.6 on a 2.625 device: a newsletter that opens shrunk must open with
        // its header whole.
        for (s0 in listOf(0.6f, 1.575f, 2.625f)) {
            assertEquals(0, headerCollapsePx(0, header, s0, s0))
            assertEquals(120, headerCollapsePx(120, header, s0, s0))
            assertFalse("at s0 = $s0 the bar stays", barHiddenByZoom(inset, s0, s0))
        }
    }

    @Test fun `a pinch out pushes the header up by what the top blank lost`() {
        // Half the layout scale: the 538 px blank now paints 269 px, the header must give up 269.
        assertEquals(
            "pinched to half the layout scale at the top of the message, the header covered the " +
                "first 269 px of text",
            269, headerCollapsePx(0, header, 1f, 2f),
        )
        assertEquals(369, headerCollapsePx(100, header, 1f, 2f))
        assertEquals("never more than the header itself", header, headerCollapsePx(400, header, 1f, 2f))
        // A quarter off the layout scale: 538 × 0.25 = 134.5, rounded.
        assertEquals(135, headerCollapsePx(0, header, 1.5f, 2f))
    }

    @Test fun `a pinch in brings nothing down`() {
        // Above the layout scale the blank is taller than the header: the header stays where the
        // scroll puts it, it never slides down over the gap.
        assertEquals(0, headerCollapsePx(0, header, 4f, 2f))
        assertEquals(100, headerCollapsePx(100, header, 4f, 2f))
        assertEquals(header, headerCollapsePx(2000, header, 4f, 2f))
        assertFalse(barHiddenByZoom(inset, 4f, 2f))
    }

    @Test fun `the bar leaves as soon as it would cover a pixel of text, and comes back at the layout scale`() {
        assertTrue("pinched to half, the bar covered 125 px of the end", barHiddenByZoom(inset, 1f, 2f))
        // 250 × (1 − 0.995) = 1.25 px: covered.
        assertTrue(barHiddenByZoom(inset, 0.995f, 1f))
        // 250 × (1 − 0.997) = 0.75 px: under a pixel, the bar stays.
        assertFalse(barHiddenByZoom(inset, 0.997f, 1f))
        assertFalse("back at the layout scale the bar returns", barHiddenByZoom(inset, 2f, 2f))
        assertFalse("no bar, no blank, nothing to hide", barHiddenByZoom(0, 1f, 2f))
    }

    @Test fun `the layout scale is the last one reported before the reveal`() {
        // A newsletter with initial-scale=0.6 on a 2.625 device: the WebView fits it while it loads.
        val opened = PageScale().scaled(2.625f, 1.575f).bodyRevealed()
        assertEquals(1.575f, opened.layoutScale)
        assertEquals(
            "opened without a pinch, the header must be whole",
            0, headerCollapsePx(0, header, opened.scale, opened.layoutScale),
        )
        val pinched = opened.scaled(1.575f, 0.7875f)
        assertEquals("the reference survives the pinch", 1.575f, pinched.layoutScale)
        assertEquals(269, headerCollapsePx(0, header, pinched.scale, pinched.layoutScale))
        assertEquals("a second reveal of the same document changes nothing", pinched, pinched.bodyRevealed())
    }

    @Test fun `with no report before the reveal, the first pinch carries the reference`() {
        val revealed = PageScale().bodyRevealed()
        assertNull(revealed.layoutScale)
        assertEquals(0, headerCollapsePx(0, header, revealed.scale, revealed.layoutScale))
        val pinched = revealed.scaled(2.625f, 1.3125f)
        assertEquals(2.625f, pinched.layoutScale)
        assertEquals(269, headerCollapsePx(0, header, pinched.scale, pinched.layoutScale))
    }

    @Test fun `reports while the document loads are never the reference`() {
        val loading = PageScale().scaled(2.625f, 1.575f).scaled(1.575f, 1.2f)
        assertNull("nothing is the layout scale before the reveal", loading.layoutScale)
        assertEquals(1.2f, loading.bodyRevealed().layoutScale)
    }
}

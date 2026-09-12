package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The swipe reveal's own opacity, run as a function. Behaviour, JVM only: `SwipeableEmailRow` is
 * not instantiable here, so the decision is tested where it was extracted to.
 */
class SwipeRevealAlphaTest {

    private val width = 800f

    @Test fun `a row at rest paints no reveal`() {
        assertEquals(
            "a row nobody is touching must show nothing behind it: a non-zero alpha at rest " +
                "paints a coloured band under every line of the list.",
            0f,
            swipeRevealAlpha(offsetPx = 0f, rowWidthPx = width, lift = 0f, committed = false),
            0f,
        )
    }

    @Test fun `the finger halfway through sees the whole affordance`() {
        assertEquals(
            "while the finger is down the reveal is what says which action will fire; anything " +
                "under 1 makes the label and its colour read as faded out mid-gesture.",
            1f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = 0f, committed = false),
            0f,
        )
    }

    @Test fun `the finger held at the stop still sees the whole affordance`() {
        assertEquals(
            "the drag is clamped to the row's own width, so a firm swipe parks the finger exactly " +
                "there with nothing committed yet. Fading here empties the cell under the finger: " +
                "a blank strip, no label, on a gesture the user can still cancel by sliding back.",
            1f,
            swipeRevealAlpha(offsetPx = width, rowWidthPx = width, lift = 0f, committed = false),
            0f,
        )
        assertEquals(
            "and the same on the other side, where the offset is negative.",
            1f,
            swipeRevealAlpha(offsetPx = -width, rowWidthPx = width, lift = 0f, committed = false),
            0f,
        )
    }

    @Test fun `once committed the reveal burns down on the take-off's own clock`() {
        assertEquals(
            "at lift 0 the row has not moved yet, so the reveal is still owed in full.",
            1f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = 0f, committed = true),
            0f,
        )
        assertEquals(
            "halfway through the flight the reveal must be halfway out — the same clock as the " +
                "row's own fade. A reveal that stays opaque leaves the colour behind a " +
                "half-transparent row.",
            0.5f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = 0.5f, committed = true),
            0f,
        )
        assertEquals(
            "and at the end of the flight it is gone, so the cell is empty for the whole server " +
                "round trip instead of holding a Delete label on a row already sent.",
            0f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = 1f, committed = true),
            0f,
        )
    }

    @Test fun `committed without motion the reveal goes with the slide`() {
        assertEquals(
            "reduced motion has no lift to burn down, so the reveal stays while the row is still " +
                "crossing the cell.",
            1f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = 0f, committed = true),
            0f,
        )
        assertEquals(
            "the moment the row has cleared its own width the cell is empty — this is the whole " +
                "defect: up to ten seconds of a coloured Delete band on a cell with no row in it.",
            0f,
            swipeRevealAlpha(offsetPx = width, rowWidthPx = width, lift = 0f, committed = true),
            0f,
        )
        assertEquals(
            "and an overshoot past the width is just as gone, on either side.",
            0f,
            swipeRevealAlpha(offsetPx = -(width + 40f), rowWidthPx = width, lift = 0f, committed = true),
            0f,
        )
    }

    @Test fun `an unmeasured row divides by nothing and cuts nothing short`() {
        assertEquals(
            "before the first onSizeChanged the width is 0; the distance test must not fire on " +
                "it, or the very first swipe of a fresh list loses its reveal outright.",
            1f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = 0f, lift = 0f, committed = true),
            0f,
        )
        assertEquals(
            "and with no width the flight clock is still the one that answers.",
            0.25f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = 0f, lift = 0.75f, committed = true),
            0f,
        )
    }

    @Test fun `a lift running past its ends stays inside zero and one`() {
        assertEquals(
            "a spring overshoot must not hand the compositor a negative alpha.",
            0f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = 1.2f, committed = true),
            0f,
        )
        assertEquals(
            "nor one above 1.",
            1f,
            swipeRevealAlpha(offsetPx = 300f, rowWidthPx = width, lift = -0.2f, committed = true),
            0f,
        )
    }
}

package app.sterna.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where a held row of [ReorderableColumn] lands, executed on laid-out rows: [dropTarget],
 *  [edgeScroll], [movedBlock] and the drag's guards are the whole of what the column decides. */
class ReorderDropTest {

    /** Five rows of 100 px from the top of the viewport, the held one at [current] included. */
    private val five = (0 until 5).map { ItemBounds(it, it * 100f, it * 100f + 100f) }

    private fun heldAt(current: Int, dy: Float, bounds: List<ItemBounds> = five, allowed: (Int) -> Boolean = { true }): Int {
        val slot = bounds.single { it.index == current }
        return dropTarget(slot.top + dy, slot.bottom + dy, current, bounds, allowed = allowed)
    }

    @Test fun `a row lifted and not moved, or moved short of a neighbour's middle, keeps its place`() {
        assertEquals(
            "a row lifted in place, or carried less than half a neighbour, changed places.",
            listOf(2, 2, 2),
            listOf(0f, 49f, -49f).map { heldAt(2, it) },
        )
    }

    @Test fun `past a neighbour's middle the row takes its place, down and up`() {
        assertEquals(
            "carried past the middle of the row below, or above, the held row did not take its place.",
            listOf(3, 1),
            listOf(51f, -51f).map { heldAt(2, it) },
        )
        assertEquals(
            "a row carried past two rows in one frame did not take the farther one's place.",
            listOf(4, 0),
            listOf(151f, -151f).map { heldAt(2, it) },
        )
    }

    @Test fun `carried past either end, the row stops at the first or the last place`() {
        assertEquals(
            "the first row carried far above, or the last far below, left the list.",
            listOf(0, 4),
            listOf(heldAt(0, -900f), heldAt(4, 900f)),
        )
        assertEquals(
            "the first row carried far below, or the last far above, did not reach the other end.",
            listOf(4, 0),
            listOf(heldAt(0, 900f), heldAt(4, -900f)),
        )
    }

    @Test fun `a short row held over a tall one does not swap back on the next frame`() {
        // Row 0 is 50 px, row 1 is 200 px. Carried 110 px down, the short row's bottom (160)
        // passes the tall row's middle (150), so they swap.
        val before = listOf(ItemBounds(0, 0f, 50f), ItemBounds(1, 50f, 250f))
        assertEquals("the short row did not pass the tall one", 1, dropTarget(110f, 160f, 0, before))
        // Laid out again: the tall row now spans 0..200, the short one's slot 200..250, drawn
        // where the finger still is.
        val after = listOf(ItemBounds(0, 0f, 200f), ItemBounds(1, 200f, 250f))
        assertEquals(
            "the next frame sent the short row straight back above the tall one: judged on the " +
                "held row's middle, the two rows trade places on every frame.",
            1,
            dropTarget(110f, 160f, 1, after),
        )
    }

    @Test fun `a refused place is skipped for the farthest accepted one, and the row stays when none is`() {
        assertEquals(
            "carried past a place it may not take, the row did not land on the accepted one beyond.",
            3,
            heldAt(1, 251f) { it != 4 },
        )
        assertEquals(
            "with every place passed refused, the row moved anyway.",
            1,
            heldAt(1, 251f) { false },
        )
    }

    @Test fun `with nothing laid out the row keeps its place`() {
        assertEquals(2, dropTarget(0f, 100f, 2, emptyList()))
    }

    @Test fun `the list scrolls only while the held row reaches into an end zone, faster the deeper`() {
        assertEquals(
            "clear of both ends, near the top, deep into the top, near the bottom, past the bottom",
            listOf(0f, -0.5f, -1f, 0.5f, 1f),
            listOf(
                edgeScroll(200f, 300f, 0f, 1000f, 100f),
                edgeScroll(50f, 150f, 0f, 1000f, 100f),
                edgeScroll(-500f, -400f, 0f, 1000f, 100f),
                edgeScroll(850f, 950f, 0f, 1000f, 100f),
                edgeScroll(1500f, 1600f, 0f, 1000f, 100f),
            ),
        )
    }

    @Test fun `a block of rows moves whole, its first row landing where asked`() {
        val abcde = listOf("a", "b", "c", "d", "e")
        assertEquals(
            "a block of rows was not moved whole, or did not land with its first row at the index asked.",
            listOf(
                listOf("d", "a", "b", "c", "e"),
                listOf("b", "c", "a", "d", "e"),
                listOf("a", "d", "e", "b", "c"),
                listOf("c", "d", "e", "a", "b"),
            ),
            listOf(abcde.movedBlock(0, 3, 1), abcde.movedBlock(1, 2, 0), abcde.movedBlock(1, 2, 3), abcde.movedBlock(0, 2, 3)),
        )
        assertEquals(
            "a block that does not fit, or a landing place past the end, changed the list.",
            listOf(abcde, abcde, abcde),
            listOf(abcde.movedBlock(0, 2, 4), abcde.movedBlock(4, 2, 0), abcde.movedBlock(1, 1, 1)),
        )
    }

    @Test fun `a carried block passes the rows below its last row, and lands so that it ends after them`() {
        // Rows 1 to 3 carried together (a parent and its two children), 300 px tall from 100.
        assertEquals(
            "a block carried past the middle of the row under it did not land one place down, or " +
                "one carried past the row above it did not land one place up: the rows of the block " +
                "itself were taken for rows it passed.",
            listOf(2, 0, 1),
            listOf(
                dropTarget(151f, 451f, 1, five, size = 3),
                dropTarget(49f, 349f, 1, five, size = 3),
                dropTarget(140f, 440f, 1, five, size = 3),
            ),
        )
    }

    // ── one drag at a time, found by its key ────────────────────────────────────────────────────

    private val keys = listOf<Any>("a", "b", "c", "d")

    @Test fun `a second long press while a row is held changes nothing`() {
        val held = lift(null, keys, "b")
        assertEquals(
            "a long press on another handle took over the drag in progress: two fingers, and the " +
                "row the first one carries is dropped at the second one's place.",
            held,
            lift(held, keys, "d"),
        )
    }

    @Test fun `only the held row's own release or cancel ends the drag, and only it writes`() {
        val held = lift(null, keys, "b")!!.copy(current = 3)
        assertEquals(
            "another row's cancel or release ended the drag, or wrote a move for a row it does not carry.",
            listOf(false, null, true, 1 to 3),
            listOf(
                endsHold(held, "d"),
                releasedMove(held, keys, "d", { 1 }, { _, _ -> true }),
                endsHold(held, "b"),
                releasedMove(held, keys, "b", { 1 }, { _, _ -> true }),
            ),
        )
    }

    @Test fun `a list that changed under the finger writes the held row, found by its key`() {
        val held = lift(null, keys, "c")!!.copy(current = 3)
        assertEquals(
            "the list changed during the drag (a sync, an arrow) and the release wrote the row that " +
                "now sits at the index lifted, not the one held; or it wrote a row that is gone.",
            listOf<Pair<Int, Int>?>(1 to 3, null),
            listOf(
                releasedMove(held, listOf("a", "c", "d", "e"), "c", { 1 }, { _, _ -> true }),
                releasedMove(held, listOf("a", "b", "d", "e"), "c", { 1 }, { _, _ -> true }),
            ),
        )
    }

    @Test fun `a release asks the judge with the index found by key, and writes nothing it refuses`() {
        val held = lift(null, keys, "a")!!.copy(current = 2)
        val asked = mutableListOf<Pair<Int, Int>>()
        val refused = releasedMove(held, listOf("z", "a", "b", "c"), "a", { 1 }) { f, t -> asked += f to t; false }
        assertEquals(
            "the judge was not asked about the move actually written, or its refusal was ignored.",
            listOf<Any?>(null, listOf(1 to 2)),
            listOf(refused, asked),
        )
    }
}

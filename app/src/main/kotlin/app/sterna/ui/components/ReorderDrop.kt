package app.sterna.ui.components

/** Where one row of a [ReorderableColumn] is laid out, in pixels along the list's viewport. */
internal data class ItemBounds(val index: Int, val top: Float, val bottom: Float) {
    val center: Float get() = (top + bottom) / 2f
}

/**
 * The index the held row should take, given where its block of [size] rows is drawn ([top] to
 * [bottom]) and where the rows are laid out ([itemBounds], the block's own slots from [current]
 * among them). Passing a row below the block lands the block right after that row.
 *
 * A row is passed once the held row's leading edge crosses its center: the bottom edge going down,
 * the top edge going up. Comparing the held row's own center instead makes a short row held over a
 * taller one swap back and forth on every frame, its center landing in the taller row's new slot.
 *
 * Of the rows passed, the farthest one [allowed] accepts wins, so a held row can skip over rows it
 * may not take the place of. [current] when none is.
 */
internal fun dropTarget(
    top: Float,
    bottom: Float,
    current: Int,
    itemBounds: List<ItemBounds>,
    size: Int = 1,
    allowed: (Int) -> Boolean = { true },
): Int {
    val passedBelow = itemBounds.filter { it.index >= current + size && it.center < bottom }.map { it.index - size + 1 }
    val passedAbove = itemBounds.filter { it.index < current && it.center > top }.map { it.index }
    val passed = passedBelow.sortedDescending().ifEmpty { passedAbove.sorted() }
    return passed.firstOrNull(allowed) ?: current
}

/**
 * How fast the list should scroll while a row is held near one of its ends, from -1 (up, full
 * speed) to 1 (down, full speed): the share of [zone] the held row reaches into, at either end of
 * the viewport from [start] to [end]. Zero while it stays clear of both.
 */
internal fun edgeScroll(top: Float, bottom: Float, start: Float, end: Float, zone: Float): Float {
    if (zone <= 0f) return 0f
    val intoTop = start + zone - top
    val intoBottom = bottom - (end - zone)
    return when {
        intoTop > 0f && intoBottom <= 0f -> -(intoTop / zone).coerceAtMost(1f)
        intoBottom > 0f && intoTop <= 0f -> (intoBottom / zone).coerceAtMost(1f)
        else -> 0f
    }
}

/** This list with the [size] elements from [from] moved as one block, the first of them landing at
 *  [to]. Unchanged when the block or the landing place does not fit. */
internal fun <T> List<T>.movedBlock(from: Int, size: Int, to: Int): List<T> {
    if (size < 1 || from < 0 || from + size > this.size || to == from || to !in 0..this.size - size) return this
    val rest = subList(0, from) + subList(from + size, this.size)
    return rest.subList(0, to) + subList(from, from + size) + rest.subList(to, rest.size)
}

/** The row being carried, by its key, and the index it holds in the list as drawn. The index it
 *  was lifted from is not kept: the list can change under the finger, and only the key still names
 *  the row being carried. */
internal data class Hold(val key: Any, val current: Int)

/** The hold after the row keyed [caller] is long-pressed, [keys] being the list's keys. A second
 *  finger on another handle changes nothing while a row is carried. */
internal fun lift(hold: Hold?, keys: List<Any>, caller: Any): Hold? {
    if (hold != null) return hold
    val at = keys.indexOf(caller)
    return if (at < 0) null else Hold(caller, at)
}

/** Whether a release or a cancel from the row keyed [caller] ends [hold]: only the carried row's
 *  own gesture does, another finger's cancel leaves the drag alone. */
internal fun endsHold(hold: Hold?, caller: Any): Boolean = hold != null && hold.key == caller

/**
 * The move a release by [caller] writes, as `from` to `to`, or null for none. `from` is the carried
 * row's index in [keys] at the release, found by its key; a row whose key is gone writes nothing,
 * and so does a landing place its block of [span] rows no longer fits at, or one [canMove] refuses.
 */
internal fun releasedMove(
    hold: Hold?,
    keys: List<Any>,
    caller: Any,
    span: (Int) -> Int,
    canMove: (Int, Int) -> Boolean,
): Pair<Int, Int>? {
    if (hold == null || !endsHold(hold, caller)) return null
    val from = keys.indexOf(hold.key)
    val to = hold.current
    if (from < 0 || to == from || to !in 0..keys.size - span(from)) return null
    return if (canMove(from, to)) from to to else null
}

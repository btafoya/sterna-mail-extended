package app.sterna.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * A list whose rows the user reorders by holding a handle and dragging. [row] draws one item and
 * applies the handle modifier it is handed to the part of the row that starts a drag: a long press
 * there lifts the row, anywhere else the list scrolls as usual.
 *
 * A row travels with the [span] rows starting at it (its own children, in a tree drawn flat), as
 * one block. Nothing is written while it is carried: the other rows only make room for it. On
 * release, [onMove] is called once with the row's index in [items], found by its key at that
 * moment, and the index it lands at once the block is moved, and only if [canMove] accepts that
 * pair; a refused place is never offered while carrying. The caller writes the move and hands back
 * the new [items].
 *
 * [key] must be stable and savable in a `Bundle`, as any key of a `LazyColumn`.
 */
@Composable
internal fun <T> ReorderableColumn(
    items: List<T>,
    key: (T) -> Any,
    canMove: (from: Int, to: Int) -> Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    span: (index: Int) -> Int = { 1 },
    row: @Composable (item: T, handle: Modifier) -> Unit,
) {
    val listState = rememberLazyListState()
    val drag = remember { HeldRow() }
    val currentItems by rememberUpdatedState(items)
    val currentKey by rememberUpdatedState(key)
    val currentSpan by rememberUpdatedState(span)
    val currentCanMove by rememberUpdatedState(canMove)
    val currentOnMove by rememberUpdatedState(onMove)
    // The order just dropped, drawn until the caller's new list arrives: without it the row jumps
    // back to where it was lifted from for the frames the write takes.
    var dropped by remember { mutableStateOf<List<T>?>(null) }
    LaunchedEffect(items) { dropped = null }

    fun currentKeys() = currentItems.map(currentKey)

    fun settle() {
        val hold = drag.hold ?: return
        val from = currentKeys().indexOf(hold.key)
        if (from < 0) return
        val bounds = listState.layoutInfo.visibleItemsInfo.map {
            ItemBounds(it.index, it.offset.toFloat(), (it.offset + it.size).toFloat())
        }
        val to = dropTarget(drag.top, drag.top + drag.height, hold.current, bounds, currentSpan(from)) {
            it == from || currentCanMove(from, it)
        }
        drag.hold = hold.copy(current = to)
    }

    fun startDrag(caller: Any) {
        val keys = currentKeys()
        val hold = lift(drag.hold, keys, caller)
        if (hold == null || hold == drag.hold) return
        val from = keys.indexOf(caller)
        val carried = keys.subList(from, minOf(from + currentSpan(from), keys.size)).toSet()
        val slots = listState.layoutInfo.visibleItemsInfo.filter { it.key in carried }
        val own = slots.firstOrNull { it.key == caller } ?: return
        drag.top = own.offset.toFloat()
        drag.height = slots.sumOf { it.size }.toFloat()
        drag.hold = hold
    }

    fun endDrag(caller: Any, commit: Boolean) {
        if (!endsHold(drag.hold, caller)) return
        val move = if (commit) releasedMove(drag.hold, currentKeys(), caller, currentSpan, currentCanMove) else null
        if (move != null) {
            val (from, to) = move
            dropped = currentItems.movedBlock(from, currentSpan(from), to)
            currentOnMove(from, to)
        }
        drag.hold = null
    }

    AutoScrollWhileHolding(listState, drag, ::settle)

    val hold = drag.hold
    val keys = items.map(key)
    val heldFrom = if (hold == null) -1 else keys.indexOf(hold.key)
    val heldSpan = if (heldFrom < 0) 0 else span(heldFrom)
    val shown = if (hold != null && heldFrom >= 0) items.movedBlock(heldFrom, heldSpan, hold.current) else dropped ?: items
    val carried = if (heldFrom < 0) emptySet<Any>() else keys.subList(heldFrom, minOf(heldFrom + heldSpan, keys.size)).toSet()
    LazyColumn(state = listState, modifier = modifier) {
        itemsIndexed(shown, key = { _, item -> key(item) }) { _, item ->
            val itemKey = key(item)
            val inBlock = itemKey in carried
            // Pinned while carried: auto-scroll can carry the row's slot off screen, and a lazy list
            // disposes an unpinned item there, cancelling the gesture under the user's finger.
            val pinnable = LocalPinnableContainer.current
            DisposableEffect(inBlock) {
                val pin = if (inBlock) pinnable?.pin() else null
                onDispose { pin?.release() }
            }
            val handle = Modifier.pointerInput(itemKey) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startDrag(itemKey) },
                    onDrag = { change, amount ->
                        if (drag.hold?.key == itemKey) {
                            change.consume()
                            drag.top += amount.y
                            settle()
                        }
                    },
                    onDragEnd = { endDrag(itemKey, commit = true) },
                    onDragCancel = { endDrag(itemKey, commit = false) },
                )
            }
            // Every row of the block is offset as the held one is: they sit right under it in the
            // list, so the one offset keeps them together under the finger.
            val placement = if (inBlock) {
                Modifier.zIndex(1f).graphicsLayer {
                    val slot = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == hold?.key }
                    translationY = if (slot != null) drag.top - slot.offset else 0f
                }
            } else {
                Modifier.animateItem()
            }
            Surface(modifier = placement, shadowElevation = if (inBlock) 6.dp else 0.dp) {
                row(item, handle)
            }
        }
    }
}

/** The row being carried, and where its block is drawn in pixels along the viewport. [top] follows
 *  the finger alone, so scrolling under a still finger leaves the block where it is on screen. */
private class HeldRow {
    var hold: Hold? by mutableStateOf<Hold?>(null)
    var top by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
}

/** Scrolls the list, once a frame, while the carried block is within 48 dp of either end. */
@Composable
private fun AutoScrollWhileHolding(listState: LazyListState, drag: HeldRow, settle: () -> Unit) {
    val density = LocalDensity.current
    val zone = with(density) { 48.dp.toPx() }
    val fastest = with(density) { 12.dp.toPx() }
    val holding = drag.hold != null
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val layout = listState.layoutInfo
            val speed = edgeScroll(
                drag.top,
                drag.top + drag.height,
                layout.viewportStartOffset.toFloat(),
                layout.viewportEndOffset.toFloat(),
                zone,
            )
            if (speed != 0f && listState.scrollBy(speed * fastest) != 0f) settle()
        }
    }
}

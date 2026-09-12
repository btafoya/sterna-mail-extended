package app.sterna.ui.inbox

import kotlin.math.abs

/*
 * How visible the coloured band behind a swiped list row is, alongside SwipeCommit.kt's "when does
 * a swipe fire". Its own file so the decision can be run by a test: the row that draws it is a
 * composable, and nothing of it is reachable from the JVM.
 */

/**
 * The reveal's opacity for a row dragged [offsetPx] from rest, its take-off [lift] frames in.
 *
 * The invariant is that the band belongs to the cell only for as long as the row does. A swipe is
 * network-first: the row is not dropped locally, it waits for the server to acknowledge, which has
 * run to ten seconds on a slow link. Once a [committed] row has travelled its whole [rowWidthPx]
 * the cell is empty on purpose, and a "Delete" label left sitting there offers a gesture already
 * spent. Before that, [lift] is the take-off's own clock, so the band burns down with the row
 * instead of blinking out on its last frame.
 *
 * [committed] is the trap, and the reason a distance alone will not do. The drag is clamped to the
 * row's width, so a firm swipe parks the finger at exactly the offset a finished flight ends on:
 * without the flag, that reachable position would blank the cell under a finger that can still
 * slide back and cancel.
 *
 * An unmeasured row reports [rowWidthPx] 0, and is deliberately not treated as having arrived:
 * the first swipe of a freshly laid-out list would lose its reveal outright.
 */
internal fun swipeRevealAlpha(
    offsetPx: Float,
    rowWidthPx: Float,
    lift: Float,
    committed: Boolean,
): Float {
    if (offsetPx == 0f) return 0f
    if (!committed) return 1f
    if (rowWidthPx > 0f && abs(offsetPx) >= rowWidthPx) return 0f
    return (1f - lift).coerceIn(0f, 1f)
}

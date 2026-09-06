package app.sterna.ui.search

/**
 * How much of [delta] the panel can actually take, given where it currently sits.
 *
 * The travel is 0 (shut) to [panelHeightPx], so a drag past either end only gets the part that fits.
 * That "rest" is not a detail: from a nested scroll, the number returned here is what the panel
 * PROMISES it consumed, and anything it claims but does not apply is silently destroyed. So the
 * caller must move the panel by exactly this and nothing else — written as "how much it takes"
 * rather than "where it ends up" precisely so promise and movement are the same number.
 */
fun searchPanelTakes(offsetPx: Float, panelHeightPx: Float, delta: Float): Float =
    (offsetPx + delta).coerceIn(0f, panelHeightPx) - offsetPx

/**
 * A released drag lands the advanced-filter panel on one side or the other: this is that decision,
 * and the ONLY copy of it. The panel can be dragged from three places, each a separate gesture
 * modifier, and left to themselves they would drift — the same flick opening the panel from the
 * handle and leaving it shut from the title bar.
 *
 * @param offsetPx how far the panel has been pulled out, 0 (shut) to [panelHeightPx].
 * @param panelHeightPx the panel's measured height, i.e. the full travel.
 * @param velocity the release velocity in px/s, POSITIVE downwards — the sign both callers use.
 */
fun searchPanelSettlesOpen(offsetPx: Float, panelHeightPx: Float, velocity: Float): Boolean = when {
    // A flick decides first: honouring the halfway line over it would make a deliberate throw stop
    // dead at 40% of the travel.
    velocity > SEARCH_PANEL_FLING_VELOCITY -> true
    velocity < -SEARCH_PANEL_FLING_VELOCITY -> false
    // Otherwise the nearest side, so a stray micro-drag lands where it started rather than toggling.
    else -> offsetPx > panelHeightPx / 2f
}

/** Above this release speed (px/s) the throw decides, below it the halfway line does. */
private const val SEARCH_PANEL_FLING_VELOCITY = 800f

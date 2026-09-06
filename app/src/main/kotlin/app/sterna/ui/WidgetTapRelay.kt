package app.sterna.ui

/** What a widget tap order must do in the composition currently on hand. Three arms, and no fourth. */
enum class WidgetTapStep {

    /** Nothing is pending. Do not consume anything, do not touch the list. */
    Ignore,

    /** The order is real but the list is not composed yet: take it from the activity and keep it. */
    Hold,

    /** The list exists: consume, drop what is held, and put the list on the unified inbox. */
    Deliver,
}

/**
 * Whether a home-screen widget tap (#112) can be carried out in THIS composition, or must be kept
 * for the next one — decided with no Android type in hand, so the rule can be EXECUTED in a JVM
 * test, same shape as [WidgetTapNavigation] beside it.
 *
 * The defect it exists for: a tap that STARTS the process opened the current account's Inbox
 * instead of "All inboxes". `MainNavHost` looks its list up imperatively, so in the composition
 * that receives the tap the list is null; the effect returned there BEFORE consuming the activity's
 * order, and nothing ever recomposed that host again, since the only thing that would have is the
 * state write the early return skipped.
 *
 * The warm intermittence is READ, not measured: a flag left up by an earlier cold start explains
 * a tap that does nothing, and the bench's alternation fits that, but nobody has watched the two
 * halves happen. Not to be published as the cause.
 *
 * So the order is consumed FIRST and held, never "waited for in place": consuming is what forces
 * the recomposition in which the list exists, and holding is what stops that consumption from
 * throwing the tap away. The hold is passive — [Hold] writes the same two values on every frame
 * and equal writes notify nobody — so what re-runs this rule is the list appearing.
 */
object WidgetTapRelay {

    /**
     * [ordered] is the activity's one-shot flag, [held] this host's own copy of an order already
     * taken off it, [listReady] whether the inbox's ViewModel exists in this composition. Every
     * combination is run in `WidgetTapRelayTest`.
     */
    fun step(ordered: Boolean, held: Boolean, listReady: Boolean): WidgetTapStep = when {
        // Neither a fresh order nor one already taken: the widget was not touched.
        !ordered && !held -> WidgetTapStep.Ignore
        // The list exists — deliver, whichever of the two flags carries the order.
        listReady -> WidgetTapStep.Deliver
        // An order with no list to give it to: keep it rather than drop it. The caller consumes the
        // activity's flag in this same step, which is what brings the next composition.
        else -> WidgetTapStep.Hold
    }
}

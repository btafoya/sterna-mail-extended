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
 * test, same shape and same reason as [WidgetTapNavigation] beside it.
 *
 * The defect it exists for (bench cell C1, 2026-08-17): a tap that STARTS the process opened the
 * current account's Inbox instead of "All inboxes". `MainNavHost` looks its list up imperatively
 * (`nav.getBackStackEntry("inbox")`, no snapshot state read), so in the composition that receives
 * the tap the NavHost has not composed its start destination yet and the list is null. The effect
 * returned there, BEFORE consuming the activity's order — and nothing ever recomposed that host
 * again, because the only thing that would have is the state write the early return skipped. The
 * order then stayed `true` in the activity for good, so every later tap was a `true → true`
 * assignment: no state change, no recomposition, no navigation.
 *
 * ⚠ THE WARM INTERMITTENCE IS READ, NOT MEASURED. A flag left up by an earlier cold start
 * explains a tap that does nothing, and a recomposition arriving from elsewhere explains the stale
 * order firing later; the alternation the bench reported on 2026-08-17 fits that, but nobody has
 * watched the two halves happen. ⛔ Not to be published as the cause — the bench cell is what says.
 *
 * ⛔ SO THE ORDER IS CONSUMED FIRST AND HELD, never "waited for in place". Consuming is what forces
 * the recomposition in which the list exists; holding is what stops that consumption from throwing
 * the tap away. It is the shape `pendingFolderOpen` already uses next door for the notification
 * path, for exactly the same cold-start reason.
 *
 * ⚠ WHAT "HELD" DOES AND DOES NOT MEAN. The hold is passive. [Hold] writes the same two values on
 * every frame that follows, equal writes notify nobody, and this rule therefore does not re-run
 * itself. What re-runs it is the list appearing — `listViewModel` turning non-null is a key of the
 * caller's effect. The held order is simply still there when that happens, instead of having been
 * thrown away; a list that never appears keeps it for the life of the composition and spends it on
 * nothing.
 */
object WidgetTapRelay {

    /**
     * [ordered] is the activity's one-shot flag, [held] this host's own copy of an order already
     * taken off it, [listReady] whether the inbox's ViewModel exists in this composition.
     *
     * `(ordered, held, listReady)` and its verdicts, all of them run in `WidgetTapRelayTest`:
     * `(false, false, *)` → Ignore · `(true, false, false)` → Hold · `(false, true, false)` → Hold ·
     * `(true, false, true)` → Deliver · `(false, true, true)` → Deliver · `(true, true, true)` →
     * Deliver.
     */
    fun step(ordered: Boolean, held: Boolean, listReady: Boolean): WidgetTapStep = when {
        // Neither a fresh order nor one already taken: the widget was not touched. Acting here
        // would put "All inboxes" on screen on a plain recomposition.
        !ordered && !held -> WidgetTapStep.Ignore
        // The list exists — deliver, whichever of the two flags carries the order.
        listReady -> WidgetTapStep.Deliver
        // An order with no list to give it to: keep it rather than drop it. The caller consumes
        // the activity's flag in this same step, which is what brings the next composition.
        else -> WidgetTapStep.Hold
    }
}

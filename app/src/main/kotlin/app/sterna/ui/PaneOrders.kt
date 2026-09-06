package app.sterna.ui

/** Where a tapped new-mail notification opens its message. Two arms, and no third. */
enum class EmailOpenOrder {

    /** Push the `message/…` route, full screen, over whatever is on top — as before #103. */
    Navigate,

    /** Post the message as the reading pane's anchor: the list beside it stays on screen. */
    ShowInPane,
}

/**
 * Whether a tapped notification may put its message in the reading pane instead of pushing the
 * full-screen reader (#103), decided from the split and the WHOLE STACK of routes with no Android
 * type in hand, so the rule is EXECUTED in a JVM test — the same shape, and the same reason, as
 * [WidgetTapNavigation] beside it: a verdict written as an `if` inside the effect is read by no
 * test, and inverting it leaves every test green.
 *
 * The pane is only offered when the LIST is what is on screen: the stack holds nothing but the
 * graph's own entry (route null, first) and `inbox` — an empty stack included, which is the cold
 * start, where the NavHost has not composed its start destination yet and that destination is
 * the list. Anything else on top gets [EmailOpenOrder.Navigate], the reader stacked as it always
 * was, and above all the COMPOSER: posted in the pane, the message would sit UNDER the live draft,
 * invisible, and wait there for the person to come back to a list she never asked to open it on
 * (the shape of #112). The same holds for the search results, the settings and a full-screen
 * reader: the reader goes on top of what is open, where the tap is seen at once.
 *
 * A null ABOVE the list is an entry the rule cannot name (a nested graph's, should one be added)
 * and is treated as unknown: [EmailOpenOrder.Navigate], never a pane under something unnamed.
 */
object PaneOrders {

    /**
     * [stack] = `nav.currentBackStack.value.map { it.destination.route }`, oldest first — the
     * form [WidgetTapNavigation.from] reads. Verdicts, all run in `PaneOrdersTest`: one pane →
     * Navigate · `[]` / `[null]` / `[null, inbox]` → ShowInPane · anything else on top → Navigate.
     */
    fun emailOpen(twoPanes: Boolean, stack: List<String?>): EmailOpenOrder {
        if (!twoPanes) return EmailOpenOrder.Navigate
        val onTheList = stack.withIndex().all { (i, route) ->
            (i == 0 && route == null) || destination(route) == INBOX
        }
        return if (onTheList) EmailOpenOrder.ShowInPane else EmailOpenOrder.Navigate
    }

    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    /** The list's route in `SternaApp`'s NavHost — the only screen the pane may be posted beside. */
    private const val INBOX = "inbox"
}

package app.sterna.ui

/** Where a tapped new-mail notification opens its message. Two arms, and no third. */
enum class EmailOpenOrder {

    /** Push the `message/…` route, full screen, over whatever is on top, as before #103. */
    Navigate,

    /** Post the message as the reading pane's anchor: the list beside it stays on screen. */
    ShowInPane,
}

/**
 * Whether a tapped notification may put its message in the reading pane instead of pushing the
 * full-screen reader (#103), decided from the split and the whole stack of routes with no Android
 * type in hand, so the rule is executed in a JVM test, the same shape, and the same reason, as
 * [WidgetTapNavigation] beside it: a verdict written as an `if` inside the effect is read by no
 * test, and inverting it leaves every test green.
 */
object PaneOrders {

    /**
     * [stack] = `nav.currentBackStack.value.map { it.destination.route }`, oldest first, the
     * form [WidgetTapNavigation.from] reads. Verdicts, all run in `PaneOrdersTest`: one pane gives
     * Navigate; `[]`, `[null]` and `[null, inbox]` give ShowInPane; anything else on top gives Navigate.
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

    /** The list's route in `SternaApp`'s NavHost, the only screen the pane may be posted beside. */
    private const val INBOX = "inbox"
}

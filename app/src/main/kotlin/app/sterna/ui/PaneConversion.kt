package app.sterna.ui

/**
 * What happens to a message that is open when the WINDOW changes width (#103).
 *
 * Nothing in the manifest holds a rotation back: the activity is recreated and the NavHost
 * restores the back stack it had. So a message read full screen in portrait comes back as a
 * `message/…` destination on a window that now wants two panes, and a message read in the pane
 * comes back as an anchor on a window that now has one. Both are converted ONCE, from the STATE
 * — the restored stack, the restored anchor — and never from the intent: `MainActivity`'s
 * `savedInstanceState == null` guard and `heldUnifiedOpen`'s `remember` exist precisely so that a
 * tap does not replay itself across a recreation, and a conversion driven off an intent would be
 * that replay under another name.
 *
 * Both verdicts live here, pure and executed by `PaneConversionTest`, for the reason [PaneOrders]
 * next door gives: a verdict written as an `if` inside a `LaunchedEffect` is read by no test, and
 * inverting it leaves the whole suite green.
 */
object PaneConversion {

    /**
     * A full-screen reader restored on a wide window, with the LIST directly under it: it moves
     * into the pane.
     *
     * "Directly under" is the whole rule, and it is not a detail of the implementation:
     *  - a reader opened from Search sits on `inbox → search → message` and stays FULL SCREEN at
     *    every width — the pane belongs to the list, and the results are what the reader came from;
     *  - a reader over the composer (`inbox → compose → message`) likewise: popping it would drop
     *    the person onto a live draft she did not ask to come back to, and posting the message in
     *    the pane would park it under that draft, invisible (the shape of #112);
     *  - a reader over another reader converts neither: only the one directly over the list.
     *
     * On ONE pane nothing converts either: popping the restored reader would leave the message in
     * an anchor a narrow list has nowhere to show, i.e. lose the message the person was reading.
     *
     * [stack] = `nav.currentBackStack.value.map { it.destination.route }`, oldest first — the same
     * form [PaneOrders.emailOpen] and [WidgetTapNavigation.from] read, led by the graph's own null
     * entry.
     */
    fun readerToPane(twoPanes: Boolean, stack: List<String?>): Boolean {
        if (!twoPanes || stack.size < 2) return false
        return destination(stack[stack.size - 1]) == MESSAGE && destination(stack[stack.size - 2]) == INBOX
    }

    /**
     * An anchor on a narrow window: it goes back to being a route. Two panes keep it — there the
     * anchor IS the reader, and navigating would stack a full-screen copy of it over itself.
     */
    fun paneToReader(twoPanes: Boolean, hasAnchor: Boolean): Boolean = !twoPanes && hasAnchor

    /** The route's own name, without its arguments — the reading [PaneOrders] takes, kept in step. */
    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    /** The list's route in `SternaApp`'s NavHost. */
    private const val INBOX = "inbox"

    /** The full-screen reader's route name, before its `/{emailId}`. */
    private const val MESSAGE = "message"
}

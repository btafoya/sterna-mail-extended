package app.sterna.ui

/**
 * What happens to a message that is open when the WINDOW changes width (#103).
 *
 * Nothing in the manifest holds a rotation back, so a message read full screen in portrait comes
 * back as a `message/…` destination on a window that now wants two panes, and one read in the pane
 * comes back as an anchor on a window that now has one. Both are converted ONCE, from the STATE, and
 * never from the intent — a conversion driven off an intent would be the tap replay that
 * `savedInstanceState == null` exists to prevent.
 *
 * Both verdicts are pure and executed by `PaneConversionTest`: a verdict written as an `if` inside a
 * `LaunchedEffect` is read by no test, and inverting it leaves the whole suite green.
 */
object PaneConversion {

    /**
     * A full-screen reader restored on a wide window, with the LIST directly under it: it moves into
     * the pane.
     *
     * "Directly under" is the whole rule. A reader opened from Search stays full screen at every
     * width, the pane belonging to the list; a reader over the composer likewise, popping it dropping
     * the person onto a live draft; a reader over another reader converts neither. On ONE pane
     * nothing converts either — popping would leave the message in an anchor a narrow list has
     * nowhere to show.
     *
     * [stack] is `currentBackStack` mapped to routes, oldest first, led by the graph's null entry.
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

    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    private const val INBOX = "inbox"

    private const val MESSAGE = "message"
}

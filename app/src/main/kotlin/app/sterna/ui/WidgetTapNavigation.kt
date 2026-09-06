package app.sterna.ui

/** What a consumed widget tap does to the back stack. Two arms, and no third. */
enum class WidgetTapReturn {

    /** Pop back to the list, so the unified inbox the tap asked for is what the user SEES. */
    BackToList,

    /** Leave the back stack exactly as it is. The list underneath still changes selection. */
    StayPut,
}

/**
 * Whether a home-screen widget tap may unwind the back stack (#112), decided from the WHOLE STACK of
 * routes and with no Android type in hand, so the rule can be EXECUTED in a JVM test.
 *
 * The defect it exists for: `selectUnified()` moves the LIST, which is one destination of the
 * NavHost and not the screen on top of it, so with the app sitting on a message a tap on the counter
 * switched the list underneath and the user discovered it later, on Back. The promise published on
 * #112 is "the counter should open All Inboxes", and a list that is not on screen is not opened.
 *
 * It is NOT unconditional: popping the COMPOSER out from under someone's hands to show them a
 * number risks the draft they are writing, and a lost draft does not come back.
 *
 * And it is decided on the STACK, never on the top entry. The caller's move is
 * `popBackStack("inbox", inclusive = false)`, which drops EVERYTHING above the list, so a rule
 * reading `currentBackStackEntry` alone said BackToList for two stacks reachable today:
 * `inbox → search → message`, where the pop destroys the search results, and
 * `inbox → compose → message`, where it takes the composer and its draft.
 *
 * So: pop only when every entry above the list is a reader. The routes compared are the ones
 * DECLARED in the graph, arguments and all, hence the comparison on the segment before the first
 * `/` or `?`. The list also holds the NavGraph's own entry, whose route is null; it sits BELOW
 * the list, where this rule never looks, and a null ABOVE the list is an unknown destination and
 * treated as one. Do not "clean up" by dropping nulls — that is what would let an unrecognised
 * entry be popped.
 */
object WidgetTapNavigation {

    /**
     * [backStack] is oldest-first, the order `NavController.currentBackStack` hands back. The
     * reachable stacks and their verdicts are all run in `WidgetTapNavigationTest`.
     */
    fun from(backStack: List<String?>): WidgetTapReturn {
        val screens = backStack.map(::destination)
        // lastIndexOf, not indexOf: whatever came before a second visit to the list is already
        // behind it and would not be popped.
        val list = screens.lastIndexOf(INBOX)
        if (list < 0) return WidgetTapReturn.StayPut
        val above = screens.subList(list + 1, screens.size)
        // Nothing above the list: the unified view is already on screen.
        if (above.isEmpty()) return WidgetTapReturn.StayPut
        return if (above.all { it == MESSAGE }) WidgetTapReturn.BackToList else WidgetTapReturn.StayPut
    }

    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    /** The list's route in `SternaApp`'s NavHost — the floor the caller pops back to. */
    private const val INBOX = "inbox"

    /** The reader's route in `SternaApp`'s NavHost, and the only destination that is popped. */
    private const val MESSAGE = "message"
}

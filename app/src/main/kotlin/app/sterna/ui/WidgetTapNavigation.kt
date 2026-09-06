package app.sterna.ui

/** What a consumed widget tap does to the back stack. Two arms, and no third. */
enum class WidgetTapReturn {

    /** Pop back to the list, so the unified inbox the tap asked for is what the user SEES. */
    BackToList,

    /** Leave the back stack exactly as it is. The list underneath still changes selection. */
    StayPut,
}

/**
 * Whether a home-screen widget tap may unwind the back stack (#112), decided from the WHOLE STACK
 * of routes and with no Android type in hand, so the rule can be EXECUTED in a JVM test — same
 * shape and the same reason as [NotificationAccountSwitch] and [NotificationFolderSwitch] beside it.
 *
 * The defect it exists for: `InboxViewModel.selectUnified()` moves the LIST, and the list is one
 * destination of `MainActivity`'s NavHost, not the screen on top of it. With the app sitting on a
 * message, a tap on the counter used to switch the list underneath and leave the reader on screen —
 * the user saw nothing change and discovered the switch later, on Back. The promise published on
 * #112 is "the counter should open All Inboxes", and a list that is not on screen is not opened.
 * `FLAG_ACTIVITY_CLEAR_TOP` cannot help: there is a single activity, and the reader is a navigation
 * destination inside it.
 *
 * AND IT IS NOT UNCONDITIONAL — that is the whole reason this is a rule and not a `popBackStack`
 * call. Popping the COMPOSER out from under someone's hands to show them a number risks the draft
 * they are writing, and a lost draft does not come back. No counter is worth that.
 *
 * AND IT IS DECIDED ON THE STACK, NEVER ON THE TOP ENTRY. This is the correction that matters,
 * and it is not theoretical. The caller's move is `popBackStack("inbox", inclusive = false)`, which
 * does not mean "drop the reader": it drops EVERYTHING above the list. A rule that looked only at
 * `currentBackStackEntry` said BackToList for two stacks that are reachable today —
 *
 *  - `inbox → search → message`: search opens a message (`SternaApp.kt`'s search screen), so the
 *    reader sits on top and the pop also destroys the `search` entry and its ViewModel. The user's
 *    results are gone;
 *  - `inbox → compose → message`: the composer is open, a new-mail banner arrives and is tapped
 *    (that navigation is unguarded and stacks over the composer), then the widget is tapped. The
 *    pop takes the COMPOSER with it, and the draft in it — the exact thing this rule swears never
 *    to do, arrived at through the back door.
 *
 * So: pop only when every entry above the list is a reader. Anywhere else the back stack is left
 * strictly alone, and the list underneath is still put on the unified view, which the user meets on
 * their way back. That compromise is already the one written for the composer.
 *
 * ROUTES, AND WHAT ELSE IS IN THAT LIST. The stack is read by the caller as
 * `nav.currentBackStack.value.map { it.destination.route }` (navigation 2.8.5:
 * `NavController.currentBackStack` is a `StateFlow<List<NavBackStackEntry>>`, read from the
 * library's own API — not observed on a device by the session that wrote this). Each route is
 * the one DECLARED in the graph, arguments and all: the reader is
 * `message/{emailId}?accountId={accountId}&…`, the composer `compose?replyTo={replyTo}&…`. Hence
 * the comparison on the segment before the first `/` or `?` rather than on the whole string —
 * which also holds for a filled-in URL, should a caller ever hand one over.
 *
 * The list also holds an entry that is NOT a screen: the NavGraph's own entry, first, put there
 * when the graph is set. `SternaApp` builds its `NavHost` with a `startDestination` and no `route`,
 * so that entry's route is **null**. It is not filtered out — it is simply not the list and not a
 * reader, and it sits BELOW the list, where this rule never looks. A null anywhere ABOVE the list
 * would be an unknown destination and is treated as one: [WidgetTapReturn.StayPut]. Do not
 * "clean up" by dropping nulls; dropping them is what would let an unrecognised entry be popped.
 */
object WidgetTapNavigation {

    /**
     * [backStack] is oldest-first, the order `NavController.currentBackStack` hands back.
     *
     * Reachable stacks and their verdicts, all of them run in `WidgetTapNavigationTest`:
     * `inbox` → StayPut · `inbox → message` → BackToList · `inbox → message → message` →
     * BackToList · `inbox → search → message` → StayPut · `inbox → compose → message` → StayPut.
     */
    fun from(backStack: List<String?>): WidgetTapReturn {
        val screens = backStack.map(::destination)
        // lastIndexOf, not indexOf: whatever came before a second visit to the list is already
        // behind it and would not be popped, so only the topmost list entry bounds the slice.
        val list = screens.lastIndexOf(INBOX)
        if (list < 0) return WidgetTapReturn.StayPut
        val above = screens.subList(list + 1, screens.size)
        // Nothing above the list: the unified view is already what is on screen, and a pop here
        // would be a no-op at best.
        if (above.isEmpty()) return WidgetTapReturn.StayPut
        return if (above.all { it == MESSAGE }) WidgetTapReturn.BackToList else WidgetTapReturn.StayPut
    }

    /** The route's own name, without its arguments: `message/{emailId}?a={a}` is `message`. */
    private fun destination(route: String?): String? =
        route?.takeWhile { it != '/' && it != '?' }

    /** The list's route in `SternaApp`'s NavHost — the floor the caller pops back to. */
    private const val INBOX = "inbox"

    /** The reader's route in `SternaApp`'s NavHost, and the only destination that is popped. */
    private const val MESSAGE = "message"
}

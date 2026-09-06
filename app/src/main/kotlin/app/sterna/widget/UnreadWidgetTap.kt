package app.sterna.widget

import app.sterna.core.data.account.StoredAccount

/**
 * Where a tap on the cell lands. Two arms only, and neither is a screen the widget invents:
 * [UnifiedInbox] asks the app for the cross-account list the drawer offers, [AppAsIs] opens the app
 * and asks for nothing.
 *
 * "Where the app opens" is the app's answer, not the widget's, and since the list reopens on the
 * view it was left in (#179) that is no longer always the Inbox. Not a defect to correct from this
 * side: the cell COUNTS inboxes and has no business commanding a view.
 */
internal enum class WidgetTapTarget { UnifiedInbox, AppAsIs }

/**
 * WHAT a tap asks the app for, kept apart from Android so it can be EXECUTED in a JVM test; the
 * plumbing that spends it is pinned by a source lint.
 *
 * It mirrors [UnreadWidgetContent] arm for arm, and it has to: the cell that says "All inboxes"
 * is the cell that must open All inboxes. Two rules that drift apart put a label on the home screen
 * that lies about what tapping it does, which is why the bound they share is [AllInboxesView], one
 * line both consult. The RULES stay separate: they answer different questions.
 *
 *  1. The app has that view (two accounts or more) → [UnifiedInbox], by the same route the drawer
 *     uses.
 * 2. One account → [AppAsIs]. Never [UnifiedInbox]: the drawer offers no such entry, so the
 *     widget would be ordering a view that is not there.
 *  3. No account at all → [AppAsIs]. No list is composed and no `InboxViewModel` exists, so the
 *     request would be consumed by nobody; and the widget must not decide it is worth interrupting
 *     sign-in for.
 */
internal object UnreadWidgetTap {

    fun of(accounts: List<StoredAccount>): WidgetTapTarget =
        if (AllInboxesView.existsFor(accounts)) WidgetTapTarget.UnifiedInbox else WidgetTapTarget.AppAsIs
}

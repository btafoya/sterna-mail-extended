package app.sterna.widget

import app.sterna.core.data.account.StoredAccount

/**
 * Does the app HAVE a cross-account "All inboxes" list at all? One bound, in one place, because the
 * widget asks the same question twice for two different reasons — and, since the drawer learnt to
 * reopen on the view you left, `InboxViewModel` asks it a third time, off the widget entirely, to
 * decide whether a remembered unified view may be restored.
 *
 * [UnreadWidgetContent] asks it to decide what the cell is CALLED; [UnreadWidgetTap] asks it to
 * decide what a tap on that cell OPENS. Each used to carry its own `accounts.size > 1`, held
 * together by nothing but a comment on either side — and a bound moved on one side alone puts a
 * label on the home screen that lies about what touching it does ("All inboxes" opening a single
 * folder, or the reverse). There is no way for the user to discover that except by tapping.
 *
 * It does NOT merge the two rules, and must not: they answer different questions and each keeps
 * its own three arms. Only the bound is shared, and it lives in its own file precisely so neither
 * rule has to import the other.
 *
 * The bound itself is not invented here either: the drawer posts its "All inboxes" entry under
 * `if (accounts.size > 1)` (`InboxScreen`), and the widget must never name or open a view the app
 * does not offer. If the drawer's condition ever moves, this is the line that follows it.
 */
internal object AllInboxesView {

    fun existsFor(accounts: List<StoredAccount>): Boolean = accounts.size > 1
}

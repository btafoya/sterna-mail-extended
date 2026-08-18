package app.sterna.widget

import app.sterna.core.data.account.StoredAccount

/**
 * Where a tap on the cell lands. Two arms only, and neither is a screen the widget invents.
 *
 *  - [UnifiedInbox] — ask the app for the cross-account "All inboxes" list, the very entry the
 *    drawer offers;
 *  - [AppAsIs] — open the app and ask for nothing at all. It already starts on the inbox of the
 *    current account (`InboxViewModel`, `Sel.Folder(store.inboxMailboxId())`), so there is nothing
 *    left to request; asking anyway would be the widget ordering a view the app does not have.
 */
internal enum class WidgetTapTarget { UnifiedInbox, AppAsIs }

/**
 * WHAT a tap asks the app for, kept apart from Android so it can be EXECUTED in a JVM test — same
 * shape and same reason as [UnreadWidgetContent] and [UnreadWidgetPush] next door. `Intent`,
 * `PendingIntent` and `MainActivity` do not run in this suite (there is no Robolectric here), so
 * the decision lives here with no Android type in sight and the plumbing that spends it is pinned
 * by a source lint.
 *
 * ⛔ It mirrors [UnreadWidgetContent] arm for arm, and it has to: the cell that says "All inboxes"
 * is the cell that must open All inboxes, and a cell that says "Inbox" must not. Two rules that
 * drift apart put a label on the home screen that lies about what tapping it does — which is why
 * the bound they share is [AllInboxesView], one line both consult, rather than a copy each held
 * together by this paragraph. ⛔ The RULES stay separate: they answer different questions.
 *
 * The promise made to the reporter of #112 is "the counter should open All Inboxes", and the rule
 * below is that promise everywhere the view exists — which is not everywhere:
 *
 *  1. **The app has that view** ([AllInboxesView]: two accounts or more) → [UnifiedInbox]. The
 *     drawer posts its "All inboxes" entry under the same bound (`InboxScreen`), so from here up
 *     the view genuinely exists and the widget asks for it by the same route the drawer uses.
 *  2. **One account** → [AppAsIs]. ⛔ Never [UnifiedInbox]: the drawer offers no such entry with a
 *     single account, so the widget would be ordering a view that is not there. Opening the app
 *     plainly already lands on that account's inbox — which is exactly what the number counted.
 *  3. **No account at all** → [AppAsIs], for a sharper version of the same reason. With nothing
 *     configured the app routes to the welcome/connect screen (`RootState.NeedAccount`): no list is
 *     composed, no `InboxViewModel` exists, and a request to select the unified view would be
 *     consumed by nobody — held, at best, until a list appears and then applied to a first account
 *     that has no second one to be unified with. ⛔ And the widget must not decide it is worth
 *     interrupting sign-in for. The tap opens the app; the app says what it needs.
 */
internal object UnreadWidgetTap {

    fun of(accounts: List<StoredAccount>): WidgetTapTarget =
        if (AllInboxesView.existsFor(accounts)) WidgetTapTarget.UnifiedInbox else WidgetTapTarget.AppAsIs
}

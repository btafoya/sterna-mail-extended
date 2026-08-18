package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.account.StoredAccount

/**
 * What the widget's label IS, without saying how it is drawn. Two shapes, because the two labels
 * come from different places: one is a resource the app translates, the other is a name the server
 * chose and nobody translates.
 */
internal sealed interface WidgetLabel {

    /** A string resource, drawn as `getString(id, *args)`. */
    data class Translated(val id: Int, val args: List<Int> = emptyList()) : WidgetLabel

    /** Text taken verbatim from stored data (a mailbox name); never translated, never formatted. */
    data class Verbatim(val text: String) : WidgetLabel
}

/** Everything the widget draws: one label and one number. */
internal data class UnreadWidgetState(val label: WidgetLabel, val count: Int)

/**
 * The widget's rendering decision, kept apart from Android so it can be EXECUTED in a JVM test —
 * same shape and same reason as `BootRestart` behind `BootReceiver`. There is no Robolectric here:
 * a `RemoteViews`, an `AppWidgetProvider` and a `Context` cannot run in this suite, so the only
 * part worth proving is the part that decides, and it lives here with no Android type in sight.
 *
 * ⛔ The number is never decided here. It always comes from
 * `MailRepository.observeUnifiedInboxUnread()` — the same derivation the drawer's "All inboxes (N)"
 * reads (`UnifiedInbox`), including when a single account is configured, where the scopes are worth
 * exactly that one inbox. A second way of counting is the one thing that branch existed to prevent.
 *
 * What IS decided here is what the number is called, and the rule has three arms:
 *
 *  1. **The app has an "All inboxes" view** ([AllInboxesView], the bound [UnreadWidgetTap] reads
 *     too, so the cell cannot end up saying one thing and opening another) → the drawer's bare
 *     `inbox_all_inboxes`, whatever the count is.
 *     ⛔ Never the drawer's "(N)" form: in the drawer that row carries no other number, while here
 *     the big figure above the label IS the count — the counted string would draw 3, and "All
 *     inboxes  (3)" right under it, in a cell whose entire content is that one number.
 *     (`inbox_all_inboxes_unread` stays where it belongs, in the drawer.)
 *  2. **One account** → ⛔ never "All inboxes". The drawer only offers that entry from two accounts
 *     up (see [AllInboxesView]), so naming a grouping that does not exist would be the widget
 *     claiming more than the app has. The label is that account's own inbox name — the same word
 *     the drawer and the top bar already show for it — unless the server left that name BLANK, in
 *     which case it falls back to the app's name like arm 3, because a figure with nothing written
 *     under it names nothing at all. ⛔ The count is kept: a missing name is a cosmetic gap, a
 *     total quietly replaced by 0 is a lie about the mailbox.
 *  3. **No account at all** → the app's name, and zero. Nothing invented, no new string, and no
 *     number carried over from a caller.
 */
internal object UnreadWidgetContent {

    fun of(accounts: List<StoredAccount>, unread: Int): UnreadWidgetState = when {
        // Arm 1 first, because it is the one shared with [UnreadWidgetTap]: the cell that SAYS
        // "All inboxes" has to be the cell that OPENS it, and [AllInboxesView] is the single bound
        // both consult so one of them cannot drift. The other two arms are this rule's own.
        AllInboxesView.existsFor(accounts) ->
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_all_inboxes), unread)
        accounts.isEmpty() ->
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), 0)
        else -> {
            // AccountStore.inboxMailboxName() only falls back when there is no ACCOUNT; a name the
            // server left empty travels through it untouched, and lands here.
            val inboxName = accounts.single().inboxName
            if (inboxName.isBlank()) {
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), unread)
            } else {
                UnreadWidgetState(WidgetLabel.Verbatim(inboxName), unread)
            }
        }
    }
}

package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHAT THIS PREVENTS: a home-screen cell that says one thing and opens another.
 *
 * [UnreadWidgetContent] decides what the cell is CALLED, [UnreadWidgetTap] decides what a tap on it
 * ASKS FOR, and each used to carry its own `accounts.size > 1`. Nothing but a comment held the two
 * numbers together, so one of them could be moved alone — and the cell would then read
 * "All inboxes" and open a single folder, or read "Inbox" and open the unified list. On the home
 * screen there is no way for the user to find that out except by tapping.
 *
 * ⛔ THIS TEST DOES NOT RECOMPUTE THE BOUND. It never looks at `accounts.size` and holds no
 * threshold of its own: for each literal account list it RUNS both rules and compares the two
 * answers they return. Moving the bound in one place only makes the two answers disagree, whatever
 * the new bound is; moving it in the shared place moves both and this stays green — which is the
 * point, the bound is free to change, the disagreement is not.
 *
 * Each rule's own arms are pinned next door, by [UnreadWidgetContentTest] and [UnreadWidgetTapTest].
 * ⛔ They are NOT one rule and must not be merged: they return different things, and each keeps its
 * three arms. It is the bound that is shared.
 */
class WidgetCellOpensWhatItSaysTest {

    private fun account(id: String) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = "inbox-$id",
        inboxName = "Inbox",
    )

    @Test fun `the cell says All inboxes exactly when a tap opens the unified inbox`() {
        val cases = mapOf(
            "no account" to emptyList(),
            "one account" to listOf(account("a")),
            "two accounts" to listOf(account("a"), account("b")),
            "three accounts" to listOf(account("a"), account("b"), account("c")),
        )
        assertEquals(
            "the label and the tap target must agree account for account: a cell labelled " +
                "'All inboxes' that opens something else — or the reverse — lies on the home " +
                "screen, where nothing explains it (#112).",
            cases.mapValues { (_, accounts) -> saysAllInboxes(accounts) },
            cases.mapValues { (_, accounts) -> opensUnifiedInbox(accounts) },
        )
    }

    /** What the drawn cell is called — read off the rendered state, never derived here. */
    private fun saysAllInboxes(accounts: List<StoredAccount>): Boolean =
        UnreadWidgetContent.of(accounts, unread = 7).label ==
            WidgetLabel.Translated(R.string.inbox_all_inboxes)

    /** What a tap on that same cell asks the app for — again the returned value, not a rule. */
    private fun opensUnifiedInbox(accounts: List<StoredAccount>): Boolean =
        UnreadWidgetTap.of(accounts) == WidgetTapTarget.UnifiedInbox
}

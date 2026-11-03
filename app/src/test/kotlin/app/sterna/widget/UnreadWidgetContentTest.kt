package app.sterna.widget

import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's label, run, not read as source, not recomputed here. Every expectation below is a
 * literal `UnreadWidgetState`, so a shipped rule whose arms were swapped or dropped disagrees with
 * this file instead of agreeing with it.
 */
class UnreadWidgetContentTest {

    private fun account(id: String, inboxName: String = "Inbox") = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = "inbox-$id",
        inboxName = inboxName,
    )

    /**
     * The number is drawn once. The drawer's "(N)" form is right in the drawer, where the row
     */
    @Test fun `two accounts with unread mail wear the bare label, not the counted one`() {
        assertEquals(
            "with two accounts the label must be the bare R.string.inbox_all_inboxes whatever the " +
                "count is: R.string.inbox_all_inboxes_unread repeats, under the figure, the very " +
                "number the figure shows.",
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_all_inboxes), count = 7),
            UnreadWidgetContent.of(listOf(account("a"), account("b")), unread = 7),
        )
    }

    @Test fun `two accounts with nothing unread wear the same bare label`() {
        assertEquals(
            "the label must not depend on the count at all — one arm, one string, whether or not " +
                "something is unread.",
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_all_inboxes), count = 0),
            UnreadWidgetContent.of(listOf(account("a"), account("b")), unread = 0),
        )
    }

    /**
     * The arm the whole file exists for. Asserted on the value returned, not on the absence of a
     * word: "not All inboxes" would be satisfied by anything at all, including an empty label.
     */
    @Test fun `a single account is named by its own inbox, never by All inboxes`() {
        assertEquals(
            "one account must be labelled with that account's inboxName — the word the drawer and " +
                "the top bar already show for it. Either 'All inboxes' string here names a " +
                "grouping the app does not offer at one account.",
            UnreadWidgetState(WidgetLabel.Verbatim("Bandeja de entrada"), count = 3),
            UnreadWidgetContent.of(
                listOf(account("solo", inboxName = "Bandeja de entrada")),
                unread = 3,
            ),
        )
    }

    /**
     * A server is free to hand back an empty mailbox name, and `AccountStore.inboxMailboxName()`
     */
    @Test fun `a single account whose inbox name is blank falls back to the app's name`() {
        assertEquals(
            "a blank inbox name must fall back to R.string.inbox_app_name — and keep the real " +
                "count. Drawn verbatim it is an empty label under the figure; zeroed, the widget " +
                "says there is no unread mail when there is.",
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), count = 4),
            UnreadWidgetContent.of(listOf(account("solo", inboxName = "   ")), unread = 4),
        )
    }

    @Test fun `a single account whose inbox name is empty falls back the same way`() {
        assertEquals(
            "an empty string is the same gap as a whitespace one — isBlank(), not isEmpty().",
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), count = 4),
            UnreadWidgetContent.of(listOf(account("solo", inboxName = "")), unread = 4),
        )
    }

    @Test fun `no account configured shows the app's name and zero`() {
        assertEquals(
            "with no account the label must be R.string.inbox_app_name and the count 0 — not the " +
                "number handed in. A widget left on the home screen after the last account is " +
                "removed otherwise keeps displaying the total it last knew.",
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), count = 0),
            UnreadWidgetContent.of(emptyList(), unread = 12),
        )
    }

    @Test fun `the every-folder counter says Unread at one account and at several`() {
        assertEquals(
            "from one account up the every-folder cell must wear R.string.inbox_unread_view, the " +
                "drawer's own word for what it counts, and the figure handed in (#283). The inbox's " +
                "name would be a lie at one account: the figure counts every folder.",
            listOf(
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_unread_view), count = 3),
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_unread_view), count = 9),
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_unread_view), count = 0),
            ),
            listOf(
                UnreadWidgetContent.of(UnreadWidgetKind.EveryFolder, listOf(account("solo", inboxName = "Bandeja")), unread = 3),
                UnreadWidgetContent.of(UnreadWidgetKind.EveryFolder, listOf(account("a"), account("b")), unread = 9),
                UnreadWidgetContent.of(UnreadWidgetKind.EveryFolder, listOf(account("a"), account("b"), account("c")), unread = 0),
            ),
        )
    }

    @Test fun `the every-folder counter with no account shows the app's name and zero, as the other`() {
        assertEquals(
            UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), count = 0),
            UnreadWidgetContent.of(UnreadWidgetKind.EveryFolder, emptyList(), unread = 12),
        )
    }

    @Test fun `the inboxes' counter keeps its labels when asked through its kind`() {
        val cases = listOf(
            emptyList(),
            listOf(account("solo", inboxName = "Bandeja")),
            listOf(account("a"), account("b")),
        )
        assertEquals(
            listOf(
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_app_name), count = 0),
                UnreadWidgetState(WidgetLabel.Verbatim("Bandeja"), count = 5),
                UnreadWidgetState(WidgetLabel.Translated(R.string.inbox_all_inboxes), count = 5),
            ),
            cases.map { UnreadWidgetContent.of(UnreadWidgetKind.Inboxes, it, unread = 5) },
        )
    }
}

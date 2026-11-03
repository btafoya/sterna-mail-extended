package app.sterna.widget

import app.sterna.MainActivity
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What this prevents: a home-screen cell that says one thing and opens another.
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

    @Test fun `the every-folder cell says Unread exactly when a tap carries the Unread extra`() {
        val cases = mapOf(
            "no account" to emptyList(),
            "one account" to listOf(account("a")),
            "two accounts" to listOf(account("a"), account("b")),
            "three accounts" to listOf(account("a"), account("b"), account("c")),
        )
        assertEquals(
            "label and extra must agree account for account: 'Unread' with EXTRA_OPEN_UNREAD, " +
                "anything else with no Unread extra.",
            cases.mapValues { (_, accounts) -> saysUnread(accounts) },
            cases.mapValues { (_, accounts) -> extraOf(UnreadWidgetKind.EveryFolder, accounts) == MainActivity.EXTRA_OPEN_UNREAD },
        )
        assertEquals(
            "and the every-folder cell never asks for the unified inbox, at any account count.",
            emptyMap<String, String?>(),
            cases.mapValues { (_, accounts) -> extraOf(UnreadWidgetKind.EveryFolder, accounts) }
                .filterValues { it == MainActivity.EXTRA_OPEN_UNIFIED },
        )
        assertEquals(
            "with an account, it does say Unread: the agreement above would also hold for a cell " +
                "that never said it and never opened it.",
            mapOf("no account" to false, "one account" to true, "two accounts" to true, "three accounts" to true),
            cases.mapValues { (_, accounts) -> saysUnread(accounts) },
        )
    }

    @Test fun `the inboxes' cell that says All inboxes carries the unified extra, never the Unread one`() {
        val cases = listOf(emptyList(), listOf(account("a")), listOf(account("a"), account("b")))
        assertEquals(
            listOf(null, null, MainActivity.EXTRA_OPEN_UNIFIED),
            cases.map { extraOf(UnreadWidgetKind.Inboxes, it) },
        )
    }

    @Test fun `the unread list opens the Unread view from every row, and never a message`() {
        val row = RecentRowTap("msg-1", "acct-a", "mbox-a")
        val carried = RecentWidgetKind.entries.associateWith { kind ->
            RecentMailWidgetTap.templateExtras(kind, listOf(account("a"))).keys + RecentMailWidgetTap.rowExtras(kind, row).keys
        }
        assertEquals(
            "a row of the unread list must carry EXTRA_OPEN_UNREAD and none of the message " +
                "extras: with EXTRA_OPEN_EMAIL_ID beside it, the cell titled \"Unread messages\" " +
                "opens one message instead of the view it lists.",
            setOf(MainActivity.EXTRA_OPEN_UNREAD),
            carried.getValue(RecentWidgetKind.Unread),
        )
        assertEquals(
            "and a row of the inboxes' list opens its own message, never the Unread view.",
            setOf(MainActivity.EXTRA_OPEN_EMAIL_ID, MainActivity.EXTRA_OPEN_ACCOUNT_ID, MainActivity.EXTRA_OPEN_MAILBOX_ID),
            carried.getValue(RecentWidgetKind.Inboxes),
        )
        assertEquals(
            "the Unread extra is a flag MainActivity reads as a boolean, set to true.",
            mapOf(MainActivity.EXTRA_OPEN_UNREAD to true),
            RecentMailWidgetTap.templateExtras(RecentWidgetKind.Unread, listOf(account("a"))),
        )
    }

    @Test fun `the unread list asks for no view without an account, as the every-folder counter does`() {
        val cases = mapOf(
            "no account" to emptyList(),
            "one account" to listOf(account("a")),
            "two accounts" to listOf(account("a"), account("b")),
        )
        assertEquals(
            "with no account the unread list's touch must carry no opening extra: MainActivity " +
                "keeps an Unread request pending and delivers it after the next sign-in, a view " +
                "nobody asked for by then. From one account up it opens the Unread view.",
            mapOf(
                "no account" to emptyMap(),
                "one account" to mapOf(MainActivity.EXTRA_OPEN_UNREAD to true),
                "two accounts" to mapOf(MainActivity.EXTRA_OPEN_UNREAD to true),
            ),
            cases.mapValues { (_, accounts) -> RecentMailWidgetTap.templateExtras(RecentWidgetKind.Unread, accounts) },
        )
        assertEquals(
            "and it is the every-folder counter's own decision, account count for account count, " +
                "so the list and the counter titled alike never open two different things.",
            cases.mapValues { (_, accounts) -> extraOf(UnreadWidgetKind.EveryFolder, accounts) },
            cases.mapValues { (_, accounts) -> RecentMailWidgetTap.templateExtras(RecentWidgetKind.Unread, accounts).keys.singleOrNull() },
        )
        assertEquals(
            "the inboxes' list never carries an opening extra in its template, at any account count.",
            cases.mapValues { emptyMap<String, Boolean>() },
            cases.mapValues { (_, accounts) -> RecentMailWidgetTap.templateExtras(RecentWidgetKind.Inboxes, accounts) },
        )
    }

    private fun saysUnread(accounts: List<StoredAccount>): Boolean =
        UnreadWidgetContent.of(UnreadWidgetKind.EveryFolder, accounts, unread = 7).label ==
            WidgetLabel.Translated(R.string.inbox_unread_view)

    private fun extraOf(kind: UnreadWidgetKind, accounts: List<StoredAccount>): String? =
        UnreadWidgetTap.extraOf(UnreadWidgetTap.of(kind, accounts))

    /** What the drawn cell is called, read off the rendered state, never derived here. */
    private fun saysAllInboxes(accounts: List<StoredAccount>): Boolean =
        UnreadWidgetContent.of(accounts, unread = 7).label ==
            WidgetLabel.Translated(R.string.inbox_all_inboxes)

    /** What a tap on that same cell asks the app for, again the returned value, not a rule. */
    private fun opensUnifiedInbox(accounts: List<StoredAccount>): Boolean =
        UnreadWidgetTap.of(accounts) == WidgetTapTarget.UnifiedInbox
}

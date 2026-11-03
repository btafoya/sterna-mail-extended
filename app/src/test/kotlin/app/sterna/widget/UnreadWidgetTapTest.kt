package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tap's destination, run, not read as source, not recomputed here. Every expectation below is
 * a literal [WidgetTapTarget], so a shipped rule whose arms were swapped or collapsed disagrees
 * with this file instead of agreeing with it.
 */
class UnreadWidgetTapTest {

    private fun account(id: String) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = "inbox-$id",
    )

    @Test fun `two accounts open the unified inbox`() {
        assertEquals(
            "from two accounts up the drawer posts an All inboxes entry, so the widget asks for " +
                "it — that is the promise made on #112.",
            WidgetTapTarget.UnifiedInbox,
            UnreadWidgetTap.of(listOf(account("a"), account("b"))),
        )
    }

    @Test fun `three accounts open the unified inbox too`() {
        assertEquals(
            "the arm is 'more than one account', not 'exactly two'.",
            WidgetTapTarget.UnifiedInbox,
            UnreadWidgetTap.of(listOf(account("a"), account("b"), account("c"))),
        )
    }

    /**
     * The arm the whole file exists for. Asserted on the value returned, not on the absence of
     * one: "not UnifiedInbox" would be satisfied by anything at all, including a third arm nobody
     * handles.
     */
    @Test fun `a single account opens the app as it is, never the unified inbox`() {
        assertEquals(
            "with one account the drawer offers no All inboxes entry (accounts.size > 1), so the " +
                "widget must not ask for it. Opening the app plainly already lands on that " +
                "account's inbox, which is what the number counted.",
            WidgetTapTarget.AppAsIs,
            UnreadWidgetTap.of(listOf(account("a"))),
        )
    }

    /**
     * No account configured: the app routes to welcome/connect, no list is composed and nothing
     * would consume a request for the unified view. The tap opens the app and asks for nothing.
     */
    @Test fun `no account at all opens the app as it is`() {
        assertEquals(
            "with nothing configured there is no list to select anything on — the app shows its " +
                "own sign-in route, and a pending unified request would be applied later to a " +
                "first account that has no second one to be unified with.",
            WidgetTapTarget.AppAsIs,
            UnreadWidgetTap.of(emptyList()),
        )
    }

    @Test fun `the every-folder counter opens the Unread view from one account up, the app plainly with none`() {
        assertEquals(
            "the every-folder cell must ask for the Unread view at one account as at several — the " +
                "icon's shortcut path, which answers that account's own view at one — and ask for " +
                "nothing with no account, where no list exists to open it on (#283).",
            listOf(WidgetTapTarget.AppAsIs, WidgetTapTarget.UnreadView, WidgetTapTarget.UnreadView, WidgetTapTarget.UnreadView),
            listOf(
                emptyList(),
                listOf(account("a")),
                listOf(account("a"), account("b")),
                listOf(account("a"), account("b"), account("c")),
            ).map { UnreadWidgetTap.of(UnreadWidgetKind.EveryFolder, it) },
        )
    }

    @Test fun `the inboxes' counter keeps its targets when asked through its kind`() {
        assertEquals(
            listOf(WidgetTapTarget.AppAsIs, WidgetTapTarget.AppAsIs, WidgetTapTarget.UnifiedInbox),
            listOf(emptyList(), listOf(account("a")), listOf(account("a"), account("b")))
                .map { UnreadWidgetTap.of(UnreadWidgetKind.Inboxes, it) },
        )
    }

    @Test fun `each target is carried by its own MainActivity extra, and AppAsIs by none`() {
        assertEquals(
            "UnifiedInbox must ride on EXTRA_OPEN_UNIFIED, UnreadView on EXTRA_OPEN_UNREAD, and " +
                "AppAsIs on nothing. Without its extra, a tap on the every-folder cell opens the " +
                "app on whatever view it was left on.",
            mapOf(
                WidgetTapTarget.UnifiedInbox to "app.sterna.OPEN_UNIFIED",
                WidgetTapTarget.UnreadView to "app.sterna.OPEN_UNREAD",
                WidgetTapTarget.AppAsIs to null,
            ),
            WidgetTapTarget.entries.associateWith { UnreadWidgetTap.extraOf(it) },
        )
    }

    @Test fun `each counter has its own action, request code and provider class`() {
        assertEquals(
            "UnreadWidgetKind's identity must be pinned: Inboxes on app.sterna.widget.TAP / 0 and " +
                "UnreadWidgetProvider (the cells already placed are known by that class), " +
                "EveryFolder on app.sterna.widget.TAP_UNREAD / 2 and its own provider. 1 belongs " +
                "to the latest-messages list's row template. A PendingIntent's identity ignores " +
                "extras: two kinds on one pair rewrite each other's under FLAG_UPDATE_CURRENT.",
            mapOf(
                UnreadWidgetKind.Inboxes to Triple("app.sterna.widget.TAP", 0, UnreadWidgetProvider::class.java),
                UnreadWidgetKind.EveryFolder to Triple("app.sterna.widget.TAP_UNREAD", 2, EveryFolderUnreadWidgetProvider::class.java),
            ),
            UnreadWidgetKind.entries.associateWith { Triple(it.action, it.requestCode, it.provider) },
        )
        assertEquals(
            "and no two kinds may share an action or a request code.",
            listOf(UnreadWidgetKind.entries.size, UnreadWidgetKind.entries.size),
            listOf(
                UnreadWidgetKind.entries.map { it.action }.distinct().size,
                UnreadWidgetKind.entries.map { it.requestCode }.distinct().size,
            ),
        )
    }
}

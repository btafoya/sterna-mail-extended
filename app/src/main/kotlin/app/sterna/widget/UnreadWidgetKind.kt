package app.sterna.widget

import android.appwidget.AppWidgetProvider
import app.sterna.AppContainer
import app.sterna.ui.inbox.everyFolderUnreadByAccount
import kotlinx.coroutines.flow.Flow

/**
 * The two unread counters the widget picker offers, one cell, layout and palette between them.
 * What a kind carries is what tells two cells apart to Android and to the reader, and nothing else:
 * the label and the tap target also depend on the accounts, and are decided by
 * [UnreadWidgetContent.of] and [UnreadWidgetTap.of]. From one account up, [EveryFolder] says
 * "Unread" and opens that view, the icon's shortcut's path: at one account it is that account's.
 *
 * [provider] is the ComponentName a placed cell is known by: renaming either class, or pointing a
 * kind at the other's, makes the cells already on home screens disappear or answer for each other.
 * [action] and [requestCode] are the `PendingIntent`'s identity, which ignores extras: two kinds on
 * one pair would rewrite each other's extra under `FLAG_UPDATE_CURRENT`.
 */
internal enum class UnreadWidgetKind(
    val provider: Class<out AppWidgetProvider>,
    val action: String,
    val requestCode: Int,
    val source: (AppContainer) -> Flow<Map<String, Int>>,
) {
    /** The inboxes, or the unified inbox from two accounts up. */
    Inboxes(
        UnreadWidgetProvider::class.java,
        ACTION_WIDGET_TAP,
        TAP_REQUEST_CODE,
        { container -> container.mailRepository.observeUnifiedInboxUnreadByAccount() },
    ),

    /** The drawer's "Unread" entry: every folder but Trash, Spam, Sent and Drafts. */
    EveryFolder(
        EveryFolderUnreadWidgetProvider::class.java,
        ACTION_WIDGET_TAP_UNREAD,
        TAP_UNREAD_REQUEST_CODE,
        { container -> everyFolderUnreadByAccount(container.accountStore.accountsFlow) { container.mailRepository.observeMailboxes(it) } },
    ),
}

/** Never declared in an `intent-filter`: the action's only job is to differ from the notifications',
 *  which carry none, and from the other kind's. */
private const val ACTION_WIDGET_TAP = "app.sterna.widget.TAP"

private const val ACTION_WIDGET_TAP_UNREAD = "app.sterna.widget.TAP_UNREAD"

/** A belt rather than the mechanism, the action being the disjunction. 1 is the latest-messages
 *  list's row template. */
private const val TAP_REQUEST_CODE = 0

private const val TAP_UNREAD_REQUEST_CODE = 2

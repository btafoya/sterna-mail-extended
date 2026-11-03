package app.sterna.widget

import android.appwidget.AppWidgetProvider
import androidx.annotation.StringRes
import app.sterna.AppContainer
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.RecentInbox
import app.sterna.ui.inbox.everyAccountUnreadScopesFlow
import app.sterna.ui.inbox.everyAccountUnreadScopesNow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The two message lists the widget picker offers, one cell, layout, row service and row rule
 * between them. A kind carries what tells two lists apart and nothing else.
 *
 * [provider] is the ComponentName a placed cell is known by: renaming either class, or pointing a
 * kind at the other's, makes the cells already on home screens disappear or answer for each other.
 * [action] and [requestCode] are the row template's identity, which ignores extras: two kinds on one
 * pair would rewrite each other's template under `FLAG_UPDATE_CURRENT`.
 *
 * [opensAs] is the counter whose tap every touch on the cell repeats, rows included, accounts and
 * all, or null where each row opens its own message: [RecentMailWidgetTap.templateExtras] and
 * [RecentMailWidgetTap.rowExtras] decide it.
 * A row of a cell that opens a view carries no message extra: beside the view's, `MainActivity`
 * would be asked for a message and a view at once, under a title that names only the view.
 *
 * [emptyNotice] is the kind's own: "No messages" over the unread list says the mailbox is empty.
 *
 * [read] receives the account list `RecentMailWidgetDraw.read` read once, so the scopes are those
 * of the accounts the colours are taken from.
 */
internal enum class RecentWidgetKind(
    val provider: Class<out AppWidgetProvider>,
    val action: String,
    val requestCode: Int,
    val opensAs: UnreadWidgetKind?,
    @StringRes val emptyNotice: Int,
    val read: suspend (AppContainer, List<StoredAccount>) -> RecentInbox,
    val source: (AppContainer) -> Flow<RecentInbox>,
) {
    /** The latest messages of every inbox, each row opening its message. */
    Inboxes(
        RecentMailWidgetProvider::class.java,
        ACTION_ROW_TAP,
        ROW_TAP_REQUEST_CODE,
        null,
        R.string.widget_latest_empty,
        { container, _ -> container.mailRepository.recentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT) },
        { container -> container.mailRepository.observeRecentUnifiedInbox(RecentMailWidgetDraw.ROW_LIMIT) },
    ),

    /** The unread messages of the drawer's "Unread" entry, every touch opening that view. */
    Unread(
        UnreadListWidgetProvider::class.java,
        ACTION_UNREAD_LIST_TAP,
        UNREAD_LIST_TAP_REQUEST_CODE,
        UnreadWidgetKind.EveryFolder,
        R.string.widget_unread_list_empty,
        { container, accounts ->
            container.mailRepository.recentUnread(
                everyAccountUnreadScopesNow(flowOf(accounts)) { container.mailRepository.observeMailboxes(it) },
                RecentMailWidgetDraw.ROW_LIMIT,
            )
        },
        { container ->
            container.mailRepository.observeRecentUnread(
                everyAccountUnreadScopesFlow(container.accountStore.accountsFlow) { container.mailRepository.observeMailboxes(it) },
                RecentMailWidgetDraw.ROW_LIMIT,
            )
        },
    ),
    ;

    companion object {
        /** The kind an adapter intent names. Absent is [Inboxes]: an adapter bound before the
         *  second kind existed carries no name, and it was that list's. */
        fun named(name: String?): RecentWidgetKind = entries.firstOrNull { it.name == name } ?: Inboxes
    }
}

/** Never declared in an `intent-filter`: the action's only job is to differ from the notifications',
 *  which carry none, and from every other widget's. */
private const val ACTION_ROW_TAP = "app.sterna.widget.OPEN_RECENT"

private const val ACTION_UNREAD_LIST_TAP = "app.sterna.widget.OPEN_UNREAD_LIST"

/** A belt rather than the mechanism, the action being the disjunction. 0 and 2 are the counters'.
 *  Do not "make it unique" against the notifications, no number is. */
private const val ROW_TAP_REQUEST_CODE = 1

private const val UNREAD_LIST_TAP_REQUEST_CODE = 3

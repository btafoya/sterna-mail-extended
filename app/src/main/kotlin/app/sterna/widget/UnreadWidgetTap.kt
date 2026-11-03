package app.sterna.widget

import app.sterna.MainActivity
import app.sterna.core.data.account.StoredAccount

/**
 * Where a tap on the cell lands. Neither view is one the widget invents:
 */
internal enum class WidgetTapTarget { UnifiedInbox, UnreadView, AppAsIs }

/**
 * What a tap asks the app for, kept apart from Android so it can be executed in a JVM test;
 */
internal object UnreadWidgetTap {

    fun of(accounts: List<StoredAccount>): WidgetTapTarget =
        if (AllInboxesView.existsFor(accounts)) WidgetTapTarget.UnifiedInbox else WidgetTapTarget.AppAsIs

    fun of(kind: UnreadWidgetKind, accounts: List<StoredAccount>): WidgetTapTarget = when (kind) {
        UnreadWidgetKind.Inboxes -> of(accounts)
        UnreadWidgetKind.EveryFolder ->
            if (accounts.isEmpty()) WidgetTapTarget.AppAsIs else WidgetTapTarget.UnreadView
    }

    fun extraOf(target: WidgetTapTarget): String? = when (target) {
        WidgetTapTarget.UnifiedInbox -> MainActivity.EXTRA_OPEN_UNIFIED
        WidgetTapTarget.UnreadView -> MainActivity.EXTRA_OPEN_UNREAD
        WidgetTapTarget.AppAsIs -> null
    }
}

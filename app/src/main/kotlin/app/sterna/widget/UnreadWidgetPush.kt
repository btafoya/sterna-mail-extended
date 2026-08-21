package app.sterna.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * WHEN the home-screen widget is pushed new numbers, kept apart from Android so it can be
 * EXECUTED in a JVM test — same shape and same reason as [UnreadWidgetContent] next door, and as
 * `UnifiedInbox.unreadByAccount` in `core:data`, which this is the mirror image of.
 *
 * The widget polls nothing (`updatePeriodMillis="0"`), so without this it only ever redraws when
 * the system asks: at placement, after a reboot, after an app update. The number then sits still
 * while the mail moves — the user reads a message, the drawer's "All inboxes (N)" decrements, and
 * the cell next to it still shows the old figure.
 *
 * ⛔ The collection is CONDITIONAL, and that is the whole point of this file. Collecting
 * `observeUnifiedInboxUnreadByAccount()` for the life of the process subscribes two global Room aggregates
 * — for everyone, including the large majority who will never place a widget. Sterna carries its
 * own push precisely so that the app costs nothing when nothing is happening; a widget nobody
 * placed must cost nothing at all.
 *
 * [placed] is that condition and it is a FLOW, not a boolean read once: the system tells the app
 * when the first cell appears ([android.appwidget.AppWidgetProvider.onEnabled]) and when the last
 * one goes ([android.appwidget.AppWidgetProvider.onDisabled]), so the answer changes under a
 * running process and the collection has to follow it without a restart. See [UnreadWidgetPresence].
 *
 * [unread] is a FACTORY and not a flow for the same reason `UnifiedInbox.unreadByAccount` takes
 * one: a flow handed in is already built, and a test could not then tell "never subscribed" from
 * "subscribed and empty". Asked for as a function, the SUBSCRIPTION itself is observable.
 */
internal object UnreadWidgetPush {

    /**
     * The numbers to draw, over time: nothing at all while no widget is placed, and the shared
     * unified-unread breakdown — one entry per account, whose sum is the total the small cell
     * shows — while at least one is.
     *
     * [flatMapLatest] and not [kotlinx.coroutines.flow.flatMapMerge]: when the last cell is
     * removed the previous subscription must be DROPPED, not merely superseded. The real counter is
     * a `combine` of Room flows that never completes and keeps emitting on every write to the
     * database, so a merge would leave the app counting mail on behalf of a widget that no longer
     * exists — which is the cost this file exists to refuse.
     *
     * TWO [distinctUntilChanged], and they answer two different questions.
     *
     * The FIRST, on the gate: the presence answer is republished by callbacks that can repeat (a
     * second `onEnabled` after a restore, a seed that agrees with what a callback already set), and
     * re-subscribing on an answer that has not changed would cancel and rebuild those aggregates
     * for nothing.
     *
     * The SECOND, on the NUMBERS, and it is not tidiness. Nothing upstream de-duplicates them:
     * `observeUnifiedInboxUnreadByAccount()` is a `combine` of two Room flows, and one invalidation
     * makes both legs re-emit, so a single write produces at least two emissions of the SAME map.
     * ⚠ A `Map` compares by value, so it still holds — it is merely FINER than it was on a single
     * integer: a message read in one account and one arriving in another now redraw, where the
     * total could have stayed put. That is cost, not falsehood, and the cell was wrong before.
     * Each one that got through cost a `getAppWidgetIds` binder call, a full decode of the account
     * blob, a `PendingIntent.getActivity(FLAG_UPDATE_CURRENT)` and an `updateAppWidget` the
     * launcher re-inflates on — all to redraw a cell with the digits already on it.
     * ⚠ It is placed AFTER [flatMapLatest], so it also spans a gate closing and reopening: a cell
     * removed and placed again on an unchanged count is not redrawn from here. That is correct and
     * not a gap — placing a cell makes the system call `UnreadWidgetProvider.onUpdate`, which draws
     * it, and this collection has never been what fills a brand-new cell.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        unread: () -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced -> if (anyPlaced) unread() else emptyFlow() }
        .distinctUntilChanged()
}

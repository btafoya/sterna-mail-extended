package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.RecentInbox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * WHEN the latest-messages widget is asked to draw itself again — [UnreadWidgetPush]'s twin, kept
 * apart from Android so it can be EXECUTED in a JVM test. The defect it closes: a message marked
 * read in the app left its row on the home screen carrying an unread dot, the data being right and
 * the TRIGGER missing. NOT a sprinkling of `refresh()` calls over every site that touches the
 * mail, a list that goes out of date the first time a gesture is added.
 *
 * The collection is CONDITIONAL: observing the unified inbox for the life of the process
 * subscribes a Room query woken by every page of every sync, for everyone. [placed] is
 * [RecentMailWidgetPresence], never [UnreadWidgetPresence], or each widget's presence would decide
 * the other one's freshness. [inbox] is a FACTORY so the SUBSCRIPTION is observable in a test.
 */
internal object RecentMailWidgetPush {

    /**
     * The moments the cell must be redrawn, as the READ that changed under it.
     *
     * The emitted value is a TRIGGER, not a draw: the collector re-reads through
     * `RecentMailWidgetDraw`, where the content setting and the app lock are consulted.
     * [flatMapLatest] and not `flatMapMerge`, a Room flow never completing on its own.
     *
     * TWO [distinctUntilChanged]. The FIRST, on the gate, presence being republished by callbacks
     * that can repeat. The SECOND, on THE READ: Room invalidates per TABLE, so a sync writing
     * another folder's mail hands back the identical list, each emission costing a binder call, a
     * DataStore read and an `updateAppWidget`. It sits AFTER [flatMapLatest], so it also spans a
     * gate closing and reopening — correct, since placing a cell makes the system call `onUpdate`.
     *
     * It compares a [RecentInbox], not a list of rows: `configured` is what tells an empty inbox
     * ("No messages") from no account at all, and on rows alone the last account removed would be
     * swallowed. It still compares only what this read CARRIES.
     *
     * Which is why the account list travels too: the coloured dots exist only above
     * [AllInboxesView.existsFor] while `configured` stays `true` across that bound, so on unchanged
     * rows the emission was swallowed and the cell kept stale dots for days. [accounts] and not
     * `refresh()` calls at the add/remove sites: `accountsFlow` is an in-memory StateFlow
     * republished by `saveAccounts`, the single choke point, for no Room query. It is NOT pushed
     * down into `UnifiedInbox.recentRows`, whose query would then be re-subscribed by every
     * `saveAccounts` write.
     *
     * The combine is INSIDE [flatMapLatest]: a combine RETAINS the last value of each arm, so
     * placed after, an account added while no cell exists would ask for a redraw for a widget that
     * is not there. And the second [distinctUntilChanged] still sits AFTER it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        accounts: Flow<List<StoredAccount>>,
        inbox: () -> Flow<RecentInbox>,
    ): Flow<RecentInbox> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced ->
            if (anyPlaced) inbox().combine(accounts.map(AllInboxesView::existsFor), ::Pair) else emptyFlow()
        }
        .distinctUntilChanged()
        .map { (read, _) -> read }
}

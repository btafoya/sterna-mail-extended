package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * WHEN the home-screen widget is pushed new numbers, kept apart from Android so it can be EXECUTED
 * in a JVM test. The widget polls nothing, so without this it only redraws when the system asks.
 *
 * The collection is CONDITIONAL, and that is the whole point: collecting
 * `observeUnifiedInboxUnreadByAccount()` for the life of the process subscribes two global Room
 * aggregates for everyone, including the majority who never place a widget. [placed] is that
 * condition and it is a FLOW; [accounts] is the SECOND arm, carrying what the numbers cannot;
 * [unread] is a FACTORY so the SUBSCRIPTION is observable in a test.
 */
internal object UnreadWidgetPush {

    /**
     * The numbers to draw over time: nothing while no widget is placed, the shared unified-unread
     * breakdown while at least one is.
     *
     * [flatMapLatest] and not `flatMapMerge`: when the last cell is removed the subscription must
     * be DROPPED, the real counter being a `combine` of Room flows that never completes.
     *
     * TWO [distinctUntilChanged]. The FIRST, on the gate, presence being republished by callbacks
     * that can repeat. The SECOND, on the NUMBERS: one Room invalidation makes both legs re-emit, so
     * a single write produces at least two emissions of the same map, each costing a binder call, a
     * decode of the account blob and an `updateAppWidget`. It sits AFTER [flatMapLatest], so it
     * also spans a gate closing and reopening.
     *
     * Which is why the account count travels too: the breakdown is keyed on `countedInboxes`,
     * which drops every account whose `inboxId` is still null — exactly how an IMPORTED account is
     * written — so adding or removing one crosses the bound WITHOUT moving a single entry of the map
     * and the cell keeps a label the app no longer has. THREE states, not two, the label having
     * three arms. The high arm is read through [AllInboxesView.existsFor] and never re-written as
     * `accounts.size > 1`: one bound, in one place.
     *
     * It is the BOUND that travels, never the list, `accountsFlow` being republished by every
     * settings write and every sync pass. And it is not pushed down into
     * `UnifiedInbox.unreadByAccount`, whose aggregates would then be rebuilt on each of them. The
     * debt that buys: RENAMING an account or its inbox leaves the cell as it was.
     *
     * The emitted value is still the MAP — the collector sums and ventilates it — so only the
     * COMPARED value becomes a pair. And the combine is INSIDE [flatMapLatest], a combine
     * retaining the last value of each arm. The account arm emits UNDER the bound as well: a
     * combine whose arm never speaks never emits at all.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun redraws(
        placed: Flow<Boolean>,
        accounts: Flow<List<StoredAccount>>,
        unread: () -> Flow<Map<String, Int>>,
    ): Flow<Map<String, Int>> = placed
        .distinctUntilChanged()
        .flatMapLatest { anyPlaced ->
            if (anyPlaced) unread().combine(accounts.map(::cellBound), ::Pair) else emptyFlow()
        }
        .distinctUntilChanged()
        .map { (unreadByAccount, _) -> unreadByAccount }

    /** Which of [UnreadWidgetContent.of]'s three arms the cell is drawn by — the account list
     *  projected down to the only thing this trigger may react to. */
    private enum class CellBound { ALL_INBOXES, ONE_ACCOUNT, NO_ACCOUNT }

    private fun cellBound(accounts: List<StoredAccount>): CellBound = when {
        AllInboxesView.existsFor(accounts) -> CellBound.ALL_INBOXES
        accounts.isEmpty() -> CellBound.NO_ACCOUNT
        else -> CellBound.ONE_ACCOUNT
    }
}

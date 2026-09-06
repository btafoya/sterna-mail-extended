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
 * WHEN the home-screen widget is pushed new numbers, kept apart from Android so it can be
 * EXECUTED in a JVM test — same shape and same reason as [UnreadWidgetContent] next door, and as
 * `UnifiedInbox.unreadByAccount` in `core:data`, which this is the mirror image of.
 *
 * The widget polls nothing (`updatePeriodMillis="0"`), so without this it only ever redraws when
 * the system asks: at placement, after a reboot, after an app update. The number then sits still
 * while the mail moves — the user reads a message, the drawer's "All inboxes (N)" decrements, and
 * the cell next to it still shows the old figure.
 *
 * The collection is CONDITIONAL, and that is the whole point of this file. Collecting
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
 * [accounts] is the SECOND arm, and it carries what the numbers cannot: how many accounts there
 * are. See the note on [redraws] — the cell's label and the target of a tap on it are both decided
 * on that count, and the breakdown is blind to an account that has never synced.
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
     * A `Map` compares by value, so it still holds — it is merely FINER than it was on a single
     * integer: a message read in one account and one arriving in another now redraw, where the
     * total could have stayed put. That is cost, not falsehood, and the cell was wrong before.
     * Each one that got through cost a `getAppWidgetIds` binder call, a full decode of the account
     * blob, a `PendingIntent.getActivity(FLAG_UPDATE_CURRENT)` and an `updateAppWidget` the
     * launcher re-inflates on — all to redraw a cell with the digits already on it.
     * It is placed AFTER [flatMapLatest], so it also spans a gate closing and reopening: a cell
     * removed and placed again on an unchanged count is not redrawn from here. That is correct and
     * not a gap — placing a cell makes the system call `UnreadWidgetProvider.onUpdate`, which draws
     * it, and this collection has never been what fills a brand-new cell.
     *
     * WHICH IS WHY THE ACCOUNT COUNT TRAVELS TOO, on [accounts], as a second arm. The numbers do
     * not carry it and cannot: the breakdown is keyed on `UnifiedInbox.countedInboxes`, which drops
     * every account whose `inboxId` is still null — exactly how an IMPORTED account is written, and
     * how it stays until someone signs it in. So adding or removing such an account crosses the
     * bound WITHOUT moving a single entry of the map, the de-duplication above compares two equal
     * values and swallows the emission, and with `updatePeriodMillis="0"` nothing else is coming:
     * on a quiet mailbox the cell keeps a label — and a tap target — the app no longer has, for
     * days. [UnreadWidgetContent.of] and [UnreadWidgetTap] both decide on that count.
     *
     * THREE STATES, not two, because the label has three arms: "All inboxes", the single
     * account's own inbox name, and the app's name when there is no account at all. A boolean would
     * leave the cell on the app's name after a first (inert) account arrived, and on a mailbox name
     * nobody owns after the last one left. The high arm is read through [AllInboxesView.existsFor]
     * and never re-written as `accounts.size > 1` here: one bound, in one place, is the whole
     * reason that file exists — the label and the tap target must not drift apart.
     *
     * It is the BOUND that travels, never the list. `AccountStore.accountsFlow` is republished by
     * `saveAccounts`, the choke point every mutator of the list goes through — and not only the
     * deliberate ones: a settings write (a refreshed OAuth token, a colour, identities discovered
     * on connect) republishes it, and so does EVERY SYNC PASS, which writes the stored unread count
     * back through `saveInboxMeta` / `saveInboxMetaFor`. Compared whole, all of those would redraw
     * the cell. And it is NOT pushed down into `UnifiedInbox.unreadByAccount`, which stays keyed
     * on `countedInboxes`: keyed on the accounts, its two global Room aggregates would be cancelled
     * and rebuilt on every one of them, in the collector's context.
     * THE DEBT THAT BUYS, said plainly: what the bound does not carry is not redrawn from here.
     * The account labels on the enlarged cell's lines (`UnreadWidgetContent`) and the single
     * account's inbox name both sit below it, so RENAMING an account or its inbox leaves
     * the cell as it was — and nothing else catches it, `AccountsViewModel` refreshing
     * only the latest-messages widget.
     *
     * THE EMITTED VALUE IS STILL THE MAP. Unlike the twin's, this emission is SPENT by the
     * collector — `UnreadWidgetDraw.redraw(appContext, unreadByAccount)` sums it for the big figure
     * and ventilates it into the enlarged cell's lines — so only the COMPARED value becomes a pair.
     *
     * THE COMBINE IS INSIDE [flatMapLatest], NOT AFTER IT, and that is the gate, not tidiness. A
     * combine RETAINS the last value of each arm, and the gated arm never completes — it only falls
     * silent when the last cell goes. Placed after, an account added or removed once no cell is on
     * any home screen would pair a stale map with a fresh bound, clear the de-duplication and ask
     * for a redraw for a widget that does not exist, making the first sentence above false. Inside,
     * the whole thing is dropped with the gate.
     * And the account arm emits UNDER the bound as well — no `filter`, no `dropWhile`. A combine
     * whose arm never speaks never emits at all, and a single-account install (the majority) would
     * stop following the mail entirely.
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

    /**
     * Which of [UnreadWidgetContent.of]'s three arms the cell is drawn by — the account list
     * projected down to the only thing about it this trigger may react to.
     */
    private enum class CellBound { ALL_INBOXES, ONE_ACCOUNT, NO_ACCOUNT }

    /** [UnreadWidgetContent.of]'s `when`, arm for arm, over the same bound. */
    private fun cellBound(accounts: List<StoredAccount>): CellBound = when {
        AllInboxesView.existsFor(accounts) -> CellBound.ALL_INBOXES
        accounts.isEmpty() -> CellBound.NO_ACCOUNT
        else -> CellBound.ONE_ACCOUNT
    }
}

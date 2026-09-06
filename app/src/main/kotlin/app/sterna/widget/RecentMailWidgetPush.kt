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
 * WHEN the latest-messages widget is asked to draw itself again — [UnreadWidgetPush]'s twin, arm
 * for arm, and kept apart from Android for the same reason: so it can be EXECUTED in a JVM test.
 *
 * The defect it closes was measured at the bench on 2026-08-18. A message is marked read in the
 * app, the server records it, and the row on the home screen keeps its unread dot. The local data
 * was right all along — the next real redraw showed a clean row — so what was missing was the
 * TRIGGER: `RecentMailWidgetDraw.refresh` had three callers (the tail of a delivery pass, and the
 * two settings writes that tighten what a row may say). Nothing followed a message being read, or
 * deleted, or moved, or an account being removed.
 *
 * NOT a sprinkling of `refresh()` calls over every site that touches the mail. That list has
 * fifteen-odd entries in `MailRepository` today and goes out of date the first time a gesture is
 * added. One collection of the read itself covers all of them, present and future.
 *
 * The collection is CONDITIONAL, and that is the whole point of this file, exactly as it is next
 * door. Observing the unified inbox for the life of the process subscribes a Room query — woken by
 * every write to `emails` and to `snoozed`, i.e. by every page of every sync — for everyone,
 * including the large majority who will never place this widget.
 *
 * [placed] is that condition and it is a FLOW, not a boolean read once: the system says when the
 * first cell appears and when the last one goes, so the answer changes under a running process.
 * It is [RecentMailWidgetPresence], never [UnreadWidgetPresence] — that one is seeded from the
 * COUNTER's placed cells, and gating this collection on it would make each widget's presence decide
 * the other one's freshness.
 *
 * [inbox] is a FACTORY and not a flow for [UnreadWidgetPush]'s reason: a flow handed in is already
 * built, and a test could not then tell "never subscribed" from "subscribed and quiet". Asked for
 * as a function, the SUBSCRIPTION itself is observable.
 */
internal object RecentMailWidgetPush {

    /**
     * The moments the cell must be redrawn, as the READ that changed under it: nothing at all while
     * no widget is placed, and the unified inbox's latest read while at least one is.
     *
     * THE EMITTED VALUE IS A TRIGGER, NOT A DRAW. The collector re-reads through
     * `RecentMailWidgetDraw`, which is where the notification-content setting and the app lock are
     * consulted; a caller that drew these rows directly would print senders and subjects on the
     * home screen of a reader who has asked for neither.
     *
     * [flatMapLatest] and not [kotlinx.coroutines.flow.flatMapMerge]: when the last cell is removed
     * the subscription must be DROPPED, not merely superseded. A Room flow never completes on its
     * own, so a merge would leave the app observing the mail table on behalf of a widget that no
     * longer exists — the one expense this file exists to refuse.
     *
     * TWO [distinctUntilChanged], answering two different questions.
     *
     * The FIRST, on the gate: presence is republished by callbacks that can repeat (a second
     * `onEnabled` after a restore, a seed that agrees with what a callback already set), and
     * re-subscribing on an unchanged answer cancels and rebuilds an observed query for nothing.
     *
     * The SECOND, on THE READ, and it is not tidiness. Room invalidates per TABLE: a sync writing a
     * page of some other folder's mail re-runs this query and hands back the identical list. Each
     * one that got through would cost a `getAppWidgetIds` binder call, the notification-content
     * setting re-read from DataStore, the account blob decoded, the query run a second time, and an
     * `updateAppWidget` plus a `notifyAppWidgetViewDataChanged` the launcher re-inflates on — all to
     * redraw rows already on the cell.
     * Placed AFTER [flatMapLatest], so it also spans a gate closing and reopening: a cell removed
     * and placed again on unchanged rows is not redrawn from here. That is correct — placing a cell
     * makes the system call `RecentMailWidgetProvider.onUpdate`, which draws it, and this collection
     * has never been what fills a brand-new cell.
     * IT COMPARES A [RecentInbox], NOT A LIST OF ROWS, and the difference is a sentence on the
     * home screen. `configured` is what tells an inbox with no mail (drawn "No messages") from no
     * account to read at all (drawn with the app's own name, claiming nothing) — see
     * `RecentMailWidgetContent.cell`. Compared on rows alone those two states are the same empty
     * list: the last account removed on an already-empty inbox would be swallowed here, and the
     * cell would go on affirming "No messages" to someone who has no account. The first account
     * added to an empty mailbox is the same defect the other way round.
     *
     * It still compares only what this read CARRIES. A subject edited in place moves a row; the
     * app lock being switched on does not, and that is redrawn from `SettingsViewModel` instead.
     *
     * WHICH IS WHY THE ACCOUNT LIST TRAVELS TOO, on [accounts], as a second arm. 1.5.1 gave the
     * DRAW a dependency this read does not carry: the coloured dot on each line is only painted
     * when [AllInboxesView.existsFor] holds, so going from two accounts to one must erase every dot
     * and going from one to two must paint them. `configured` is a boolean — "at least one account"
     * — and stays `true` across that bound, so on unchanged rows the value compared above was EQUAL
     * and the emission was swallowed: with `updatePeriodMillis="0"` and an account that owns none of
     * the latest rows, the cell kept stale dots for days. `UnifiedInbox` already says it —
     * the identity that travels must be the identity that is DRAWN — so the compared value is the
     * pair (read, bound), while what is EMITTED stays a [RecentInbox] trigger.
     *
     * [accounts] and not `refresh()` calls at the sites that add or remove one: it is
     * `AccountStore.accountsFlow`, an in-memory [kotlinx.coroutines.flow.StateFlow] republished by
     * `saveAccounts` — the single choke point every list mutator goes through — so one arm covers
     * sign-out, import, restore, the sub-account reconcile and whatever is added next, for no Room
     * query and no wake-up. It is NOT pushed down into `UnifiedInbox.recentRows`, which stays
     * keyed on `scopes`: keyed on the accounts, its Room query would be re-subscribed by all ~27
     * `saveAccounts` writes (a refreshed OAuth token, a colour, discovered identities).
     * A change that does NOT cross the bound is deliberately silent here — a recolour is redrawn
     * by `AccountsViewModel.setColor`, which already forgets and refreshes.
     *
     * THE COMBINE IS INSIDE [flatMapLatest], NOT AFTER IT, and that is the gate, not tidiness.
     * A combine RETAINS the last value of each arm, and the gated arm never completes — it only
     * falls silent when the last cell goes. Placed after, an account added or removed once no cell
     * is on any home screen would pair a stale read with a fresh bound, clear the de-duplication
     * and ask for a redraw for a widget that does not exist, making the first sentence of this
     * KDoc false. Inside, the whole thing is dropped with the gate.
     * And the second [distinctUntilChanged] still sits AFTER [flatMapLatest], for the reason
     * given above: moved in, it would stop spanning a gate that closes and reopens.
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

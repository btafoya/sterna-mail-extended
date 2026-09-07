package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The widget's push, RUN — not read as source. What is pinned here is not that the app counts
 * unread mail, it is WHEN it agrees to: never while no widget is placed, from the moment one is,
 * and no longer once the last one goes.
 *
 * That gate is the expensive half of this pane. `observeUnifiedInboxUnreadByAccount()` is a `combine` of two
 * global Room aggregates; collected for the life of the process it is paid by every user, and the
 * large majority will never put this widget on a home screen. So the tests below assert on what the
 * collaborator was ASKED and when it was SUBSCRIBED, the way `UnifiedInboxUnreadTest` asserts on
 * the scopes handed to its counter — a returned value would say nothing about the subscription that
 * produced it.
 *
 * The fake is HOT and never completes, on purpose. A `flowOf(…)` is finished the instant it is
 * read, and a finished inner flow makes `flatMapLatest`, `flatMapMerge` and `flatMapConcat`
 * indistinguishable — the real counter never ends, so a subscription the shipped code failed to
 * drop stays alive and keeps arriving. [Counter.push] is that arrival, aimed at a flow handed out
 * earlier.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnreadWidgetPushTest {

    /** Nothing but the id matters here: the bound this pane follows is the list's SIZE. */
    private fun account(id: String) = StoredAccount(id = id, server = "s", username = "u")

    /** Two accounts: above [AllInboxesView.existsFor], so the cell is called "All inboxes". */
    private val twoAccounts = listOf(account("a"), account("b"))

    /**
     * Stands in for `MailRepository.observeUnifiedInboxUnreadByAccount()`, and records two
     * different facts that a single counter would conflate: that the factory was CALLED, and that
     * the flow it handed back was actually COLLECTED. Each subscription starts on a breakdown of
     * its own (100, 200, …), so the collected list also says WHICH subscription produced what.
     */
    private class Counter(
        private val initial: (Int) -> Map<String, Int> = { mapOf(ACCOUNT to 100 * (it + 1)) },
    ) {
        val asked = mutableListOf<String>()
        val subscribed = mutableListOf<String>()
        private val handed = mutableListOf<MutableStateFlow<Map<String, Int>>>()

        fun unread(): Flow<Map<String, Int>> {
            val index = handed.size
            asked += "ask#$index"
            val flow = MutableStateFlow(initial(index))
            handed += flow
            return flow.onStart { subscribed += "collect#$index" }
        }

        /** The Room invalidation that would reach a subscription nothing cancelled. */
        fun push(index: Int, value: Map<String, Int>) {
            val flow = handed.getOrNull(index)
                ?: error("the counter was never asked a ${index + 1}th time — nothing to push to")
            flow.value = value
        }
    }

    private fun collectPush(
        placed: Flow<Boolean>,
        counter: Counter,
        seen: MutableList<Map<String, Int>>,
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: TestDispatcher,
        accounts: Flow<List<StoredAccount>> = MutableStateFlow(twoAccounts),
    ) = scope.launch(dispatcher) {
        UnreadWidgetPush.redraws(placed, accounts, counter::unread).toList(seen)
    }

    /**
     * The rule this whole file exists for: an install with no widget on any home screen must not
     * subscribe the unified count AT ALL. Not "subscribe and ignore", not "subscribe and draw
     * nothing" — never ask.
     */
    @Test fun `no widget placed means the count is never even asked for`() = runTest {
        val counter = Counter()
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(MutableStateFlow(false), counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "with no widget placed the count factory must not be called: collecting " +
                "observeUnifiedInboxUnread() subscribes two global Room aggregates for the whole " +
                "life of the process, and the majority of installs will never place this widget.",
            emptyList<String>(),
            counter.asked,
        )
        assertEquals("and nothing is subscribed either", emptyList<String>(), counter.subscribed)
        assertEquals("so there is nothing to draw", emptyList<Map<String, Int>>(), seen)
        job.cancel()
    }

    /** The first cell placed opens the gate, and what happens afterwards arrives. */
    @Test fun `the first widget placed starts the collection, and later counts arrive`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(false)
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(placed, counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = true
        runCurrent()
        counter.push(0, mapOf(ACCOUNT to 7))
        runCurrent()

        assertEquals("the count is asked for exactly once, when the gate opens", listOf("ask#0"), counter.asked)
        assertEquals("and that flow is really collected", listOf("collect#0"), counter.subscribed)
        assertEquals(
            "the value at subscription time is drawn, and so is every one after it — a widget " +
                "that only drew what was true when it was placed is the defect this pane closes.",
            listOf(mapOf(ACCOUNT to 100), mapOf(ACCOUNT to 7)),
            seen,
        )
        job.cancel()
    }

    /**
     * The claim [kotlinx.coroutines.flow.flatMapLatest] makes, executed rather than believed: the
     * subscription is DROPPED when the last cell goes, not merely superseded. With `flatMapMerge`
     * the app would keep counting mail for a widget that no longer exists, on every write to the
     * database, for as long as the process lives — and nothing on screen would ever say so.
     */
    @Test fun `the last widget removed really stops the collection`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(placed, counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        counter.push(0, mapOf(ACCOUNT to 4242))
        runCurrent()

        assertEquals(
            "an emission on the flow handed out while the widget was placed must not reach " +
                "anything once the last cell is gone. The real counter never completes, so a " +
                "subscription that was not cancelled goes on arriving here forever.",
            listOf(mapOf(ACCOUNT to 100)),
            seen,
        )
        assertEquals("and it is not asked again on the way out", listOf("ask#0"), counter.asked)
        job.cancel()
    }

    /** A widget placed again after the last one was removed gets a FRESH subscription. */
    @Test fun `a widget placed again is counted again, on a new subscription`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(placed, counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        placed.value = true
        runCurrent()

        assertEquals("the second placement asks again", listOf("ask#0", "ask#1"), counter.asked)
        assertEquals("and collects the new flow", listOf("collect#0", "collect#1"), counter.subscribed)
        assertEquals(
            "the cell is drawn from the second subscription",
            listOf(mapOf(ACCOUNT to 100), mapOf(ACCOUNT to 200)),
            seen,
        )
        job.cancel()
    }

    /**
     * The gate's ceiling: an answer that has not CHANGED must not re-subscribe. The presence flow
     * is fed by callbacks that can repeat, and each repeat would otherwise cancel and rebuild those
     * two Room aggregates for a state of affairs that is exactly what it already was.
     */
    @Test fun `the same answer twice does not re-subscribe the count`() = runTest {
        val counter = Counter()
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(flowOf(true, true), counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "'a widget is placed' said twice is one gate opening, not two: re-subscribing cancels " +
                "and rebuilds the unified count for nothing.",
            listOf("ask#0"),
            counter.asked,
        )
        assertEquals("and the cell is drawn once", listOf(mapOf(ACCOUNT to 100)), seen)
        job.cancel()
    }

    /**
     * THE SAME BREAKDOWN TWICE IS ONE DRAW. Nothing upstream says so: the real counter is a
     * `combine` of two Room flows, and a single write makes both legs re-emit, so one message
     * marked read arrives here as the same map two or more times over. A `Map` compares by
     * value, which is what keeps this rule true now that the payload is no longer an integer.
     *
     * A draw is not free — `getAppWidgetIds` over binder, the account blob decoded from JSON, a
     * `PendingIntent.getActivity(FLAG_UPDATE_CURRENT)`, and an `updateAppWidget` the launcher
     * re-inflates on — and every one of those repeats redrew digits that were already on the cell.
     *
     * The counter fake above cannot show this: it hands back a [MutableStateFlow], which drops a
     * value equal to the one it holds all by itself. The repetition being defended against comes
     * from the two legs of a `combine`, so it is written here as a flow that really does repeat.
     */
    @Test fun `the same count arriving twice is drawn once`() = runTest {
        val seen = mutableListOf<Map<String, Int>>()
        val first = mapOf("a" to 7, "b" to 2)
        val second = mapOf("a" to 3, "b" to 2)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnreadWidgetPush.redraws(MutableStateFlow(true), MutableStateFlow(twoAccounts)) {
                flowOf(first, mapOf("a" to 7, "b" to 2), second, second, first)
            }.toList(seen)
        }
        runCurrent()

        assertEquals(
            "a count that has not moved must not reach the draw. The unified total is a combine " +
                "of two Room flows, so one write emits the same map more than once, and each " +
                "one that got through cost a binder call, a full decode of the account blob and a " +
                "re-inflation in the launcher — for a cell already showing that number.",
            listOf(first, second, first),
            seen,
        )
        job.cancel()
    }

    /**
     * THE NUMBERS DO NOT MOVE AND THE CELL MUST STILL BE REDRAWN — the defect this pane closes.
     *
     * [UnreadWidgetContent.of] calls the cell "All inboxes" only above [AllInboxesView.existsFor],
     * i.e. from two accounts up, and below it the label is the single account's own inbox name;
     * [UnreadWidgetTap] sends a tap to two different places on the same bound. So crossing it
     * rewrites the cell in both directions WITHOUT touching the mail — and the breakdown cannot
     * report it, because it is keyed on `UnifiedInbox.countedInboxes`, which drops any account
     * whose `inboxId` is still null. An IMPORTED account is written exactly like that and stays so
     * until someone signs it in: it is in the list, and absent from the map.
     *
     * The de-duplication therefore had two equal maps and swallowed the emission. With
     * `updatePeriodMillis="0"` nothing else was coming: on a quiet mailbox the cell went on saying
     * "All inboxes" — and opening it — for days after the second account was removed.
     *
     * The expected emissions are written out in full and NOT recomputed from `existsFor` here —
     * a test that re-derived the bound would stay green with the shipped bound inverted.
     */
    @Test fun `crossing the two-account bound redraws, on a count that did not move`() = runTest {
        val counter = Counter()
        val accounts = MutableStateFlow(twoAccounts)
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(
            MutableStateFlow(true), counter, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = listOf(account("a"))
        runCurrent()
        accounts.value = twoAccounts
        runCurrent()

        assertEquals(
            "two accounts becoming one must ask for a redraw (the cell stops being 'All inboxes'), " +
                "and one becoming two must ask again — on a breakdown that is strictly identical " +
                "throughout, which is what an account that never synced leaves behind. Swallowed, " +
                "the home screen keeps a label and a tap target the app no longer has, and there " +
                "is no timer to catch it: updatePeriodMillis is 0.",
            listOf(mapOf(ACCOUNT to 100), mapOf(ACCOUNT to 100), mapOf(ACCOUNT to 100)),
            seen,
        )
        assertEquals("and the count is NOT re-subscribed for it", listOf("ask#0"), counter.asked)
        assertEquals("still one subscription", listOf("collect#0"), counter.subscribed)
        job.cancel()
    }

    /**
     * THE OTHER BOUND, and the one a boolean arm would lose: NO ACCOUNT AT ALL versus ONE.
     *
     * [UnreadWidgetContent.of] has THREE arms, not two — "All inboxes", the single account's inbox
     * name, and the app's own name with a zero when there is no account. `existsFor` is `false` on
     * both sides of the 0/1 bound, so a trigger that compared only that boolean would leave the
     * cell showing the app's name after a first account arrived, and a mailbox name nobody owns
     * after the last one left.
     *
     * The map is EMPTY from end to end here, which is not a contrivance: an account with no
     * `inboxId` contributes no entry, so "no account" and "one imported account" produce the same
     * empty breakdown — the emission has nothing else to be told apart by.
     */
    @Test fun `the first account arriving and the last one leaving are redraws too`() = runTest {
        val counter = Counter { emptyMap() }
        val accounts = MutableStateFlow(emptyList<StoredAccount>())
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(
            MutableStateFlow(true), counter, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = listOf(account("a"))
        runCurrent()
        accounts.value = emptyList()
        runCurrent()

        assertEquals(
            "no account becoming one must ask for a redraw (the cell stops being the app's own " +
                "name), and one becoming none must ask again. Both sides are below the " +
                "two-account bound, so a trigger carrying that boolean alone compares two equal " +
                "values and swallows them — and the cell names a mailbox that is not there.",
            listOf(emptyMap(), emptyMap(), emptyMap<String, Int>()),
            seen,
        )
        assertEquals("and the count is NOT re-subscribed for it", listOf("ask#0"), counter.asked)
        assertEquals("still one subscription", listOf("collect#0"), counter.subscribed)
        job.cancel()
    }

    /**
     * THE THIRD PAIR, AND THE ONE THE TWO TESTS ABOVE CANNOT SEE: no account at all versus two.
     *
     * The bound has three states, so it has three distinctions to keep, and the two crossings
     * played above both pass THROUGH the single-account state. Nothing else in this file runs
     * 0 → 2 in one step, so `NO_ACCOUNT` and `ALL_INBOXES` could be the SAME value and the whole
     * suite would stay green — with the cell left on the app's own name and a zero, opening the app
     * bare, where it must say "All inboxes" and open that list.
     *
     * And that one step is real, not a contrivance of the test: `AccountStore.importAccounts`
     * ends in a single `saveAccounts(existing + added)`, so restoring a two-account backup onto an
     * install that had none republishes the list ONCE, already at two. Every imported record is
     * written with `inboxId = null`, so `UnifiedInbox.countedInboxes` drops both and the breakdown
     * is the empty map on either side: this emission has nothing but the bound to be told apart by,
     * and swallowed it leaves that cell wrong for days (`updatePeriodMillis="0"`).
     *
     * The way back is pinned for the same reason, whichever gesture takes the list from two
     * straight to none: it is the same pair, and a bound that conflated them would lose it too.
     *
     * The expected emissions are written out in full and NOT recomputed from `existsFor` here —
     * a test that re-derived the bound would stay green with the shipped bound inverted.
     */
    @Test fun `no account becoming two in one step redraws, and back`() = runTest {
        val counter = Counter { emptyMap() }
        val accounts = MutableStateFlow(emptyList<StoredAccount>())
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(
            MutableStateFlow(true), counter, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = twoAccounts
        runCurrent()
        accounts.value = emptyList()
        runCurrent()

        assertEquals(
            "no account becoming TWO in one step must ask for a redraw, and two becoming none " +
                "must ask again — the import of a two-account backup is exactly one publication of " +
                "the list, and both records arrive with no inbox id, so the breakdown is the empty " +
                "map throughout. Swallowed, the cell goes on naming the app and showing 0, and a " +
                "tap on it opens the app bare instead of All inboxes.",
            listOf(emptyMap(), emptyMap(), emptyMap<String, Int>()),
            seen,
        )
        assertEquals("and the count is NOT re-subscribed for it", listOf("ask#0"), counter.asked)
        assertEquals("still one subscription", listOf("collect#0"), counter.subscribed)
        job.cancel()
    }

    /**
     * THE PRICE OF THE ARM ABOVE, and the reason it carries a BOUND and not the account list.
     *
     * `saveAccounts` is the choke point of some twenty-seven writes — a refreshed OAuth token, a
     * colour, identities discovered on a sync — and every one of them republishes `accountsFlow`.
     * A trigger that compared the accounts themselves would redraw the cell on all of them, each
     * time paying a `getAppWidgetIds` binder call, the account blob decoded, a
     * `PendingIntent.getActivity(FLAG_UPDATE_CURRENT)` and a re-inflation in the launcher, for a
     * cell whose label and digits are unchanged.
     *
     * And nothing may be RE-SUBSCRIBED: the breakdown stays keyed on `UnifiedInbox.countedInboxes`,
     * so an account list that moves must not cancel and rebuild two global Room aggregates.
     */
    @Test fun `an account list that does not cross the bound costs nothing`() = runTest {
        val counter = Counter()
        val accounts = MutableStateFlow(listOf(account("a"), account("b"), account("c")))
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(
            MutableStateFlow(true), counter, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = twoAccounts
        runCurrent()
        accounts.value = listOf(account("d"), account("e"))
        runCurrent()

        assertEquals(
            "three accounts becoming two, then two others, never crosses the bound: the cell is " +
                "drawn exactly the same way, so the only redraw is the one the subscription itself " +
                "asked for. Every extra one costs a binder call, a decode of the account blob and " +
                "a launcher re-inflation, on each of the ~27 writes that go through saveAccounts.",
            listOf(mapOf(ACCOUNT to 100)),
            seen,
        )
        assertEquals(
            "and above all the count is not asked again: the breakdown is keyed on countedInboxes, " +
                "and rebuilding two global Room aggregates on every saveAccounts is the expense " +
                "this arm was designed around.",
            listOf("ask#0"),
            counter.asked,
        )
        assertEquals("nor re-subscribed", listOf("collect#0"), counter.subscribed)
        job.cancel()
    }

    /**
     * ONE ACCOUNT IS NOT AN ABSENCE OF ACCOUNTS, and this test exists because the arm added for
     * the label reads a BOUND: on a single-account install — the large majority — that bound never
     * changes for the whole life of the process.
     *
     * An arm that only spoke above the bound (`filter { it }`, `dropWhile { !it }`) would leave
     * [kotlinx.coroutines.flow.combine] waiting on a value that never comes, and the cell would
     * stop following the mail ENTIRELY for everyone with one account: the number frozen on the
     * home screen, which is the defect this whole file was written to close, and with
     * `updatePeriodMillis="0"` nothing would come to clear it.
     */
    @Test fun `a single account still follows the mail`() = runTest {
        val counter = Counter()
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(
            MutableStateFlow(true), counter, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), MutableStateFlow(listOf(account("a"))),
        )
        runCurrent()

        counter.push(0, mapOf(ACCOUNT to 7))
        runCurrent()

        assertEquals(
            "below the two-account bound the cell is drawn from the single account's inbox name, " +
                "and the numbers must be followed exactly the same way: the count at subscription " +
                "time, then the message being read in the app. An arm that stayed silent under the " +
                "bound would strand the combine and freeze the cell for every single-account " +
                "install.",
            listOf(mapOf(ACCOUNT to 100), mapOf(ACCOUNT to 7)),
            seen,
        )
        assertEquals("on one subscription, asked for once", listOf("ask#0"), counter.asked)
        job.cancel()
    }

    /**
     * THE GATE GOVERNS BOTH ARMS, and this is the half a combine placed outside
     * [kotlinx.coroutines.flow.flatMapLatest] would lose. Such a combine RETAINS the last value of
     * each arm, and the gated arm never completes — it only falls silent — so an account added or
     * removed after the last cell was taken off the home screen would pair a stale breakdown with
     * a fresh bound, get past the de-duplication, and ask for a redraw on behalf of a widget that
     * does not exist.
     *
     * The cost is one `getAppWidgetIds` binder call, which is small. What is not small is the
     * claim: this file says "nothing at all while no widget is placed", and the wiring lint next
     * door prices the whole gate on it. The sentence has to stay true.
     */
    @Test fun `an account list that moves while no cell is placed redraws nothing`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(true)
        val accounts = MutableStateFlow(twoAccounts)
        val seen = mutableListOf<Map<String, Int>>()
        val job = collectPush(
            placed, counter, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        placed.value = false
        runCurrent()
        accounts.value = listOf(account("a"))
        runCurrent()

        assertEquals(
            "the last cell is gone, so crossing the two-account bound must reach nothing: there " +
                "is no label on any home screen to correct, and the gate is what this whole file " +
                "is for.",
            listOf(mapOf(ACCOUNT to 100)),
            seen,
        )
        job.cancel()
    }

    private companion object {
        /** One account id, so the maps below read as breakdowns and not as bare numbers. */
        const val ACCOUNT = "acc-1"
    }
}

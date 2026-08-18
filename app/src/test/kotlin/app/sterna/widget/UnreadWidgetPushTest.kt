package app.sterna.widget

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
 * That gate is the expensive half of this pane. `observeUnifiedInboxUnread()` is a `combine` of two
 * global Room aggregates; collected for the life of the process it is paid by every user, and the
 * large majority will never put this widget on a home screen. So the tests below assert on what the
 * collaborator was ASKED and when it was SUBSCRIBED, the way `UnifiedInboxUnreadTest` asserts on
 * the scopes handed to its counter — a returned value would say nothing about the subscription that
 * produced it.
 *
 * ⛔ The fake is HOT and never completes, on purpose. A `flowOf(…)` is finished the instant it is
 * read, and a finished inner flow makes `flatMapLatest`, `flatMapMerge` and `flatMapConcat`
 * indistinguishable — the real counter never ends, so a subscription the shipped code failed to
 * drop stays alive and keeps arriving. [Counter.push] is that arrival, aimed at a flow handed out
 * earlier.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnreadWidgetPushTest {

    /**
     * Stands in for `MailRepository.observeUnifiedInboxUnread()`, and records two different facts
     * that a single counter would conflate: that the factory was CALLED, and that the flow it
     * handed back was actually COLLECTED. Each subscription starts on a value of its own (100, 200,
     * …), so the collected list also says WHICH subscription produced what.
     */
    private class Counter {
        val asked = mutableListOf<String>()
        val subscribed = mutableListOf<String>()
        private val handed = mutableListOf<MutableStateFlow<Int>>()

        fun unread(): Flow<Int> {
            val index = handed.size
            asked += "ask#$index"
            val flow = MutableStateFlow(100 * (index + 1))
            handed += flow
            return flow.onStart { subscribed += "collect#$index" }
        }

        /** The Room invalidation that would reach a subscription nothing cancelled. */
        fun push(index: Int, value: Int) {
            val flow = handed.getOrNull(index)
                ?: error("the counter was never asked a ${index + 1}th time — nothing to push to")
            flow.value = value
        }
    }

    private fun collectPush(
        placed: Flow<Boolean>,
        counter: Counter,
        seen: MutableList<Int>,
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: TestDispatcher,
    ) = scope.launch(dispatcher) {
        UnreadWidgetPush.redraws(placed, counter::unread).toList(seen)
    }

    /**
     * The rule this whole file exists for: an install with no widget on any home screen must not
     * subscribe the unified count AT ALL. Not "subscribe and ignore", not "subscribe and draw
     * nothing" — never ask.
     */
    @Test fun `no widget placed means the count is never even asked for`() = runTest {
        val counter = Counter()
        val seen = mutableListOf<Int>()
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
        assertEquals("so there is nothing to draw", emptyList<Int>(), seen)
        job.cancel()
    }

    /** The first cell placed opens the gate, and what happens afterwards arrives. */
    @Test fun `the first widget placed starts the collection, and later counts arrive`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(false)
        val seen = mutableListOf<Int>()
        val job = collectPush(placed, counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = true
        runCurrent()
        counter.push(0, 7)
        runCurrent()

        assertEquals("the count is asked for exactly once, when the gate opens", listOf("ask#0"), counter.asked)
        assertEquals("and that flow is really collected", listOf("collect#0"), counter.subscribed)
        assertEquals(
            "the value at subscription time is drawn, and so is every one after it — a widget " +
                "that only drew what was true when it was placed is the defect this pane closes.",
            listOf(100, 7),
            seen,
        )
        job.cancel()
    }

    /**
     * ⛔ The claim [kotlinx.coroutines.flow.flatMapLatest] makes, executed rather than believed: the
     * subscription is DROPPED when the last cell goes, not merely superseded. With `flatMapMerge`
     * the app would keep counting mail for a widget that no longer exists, on every write to the
     * database, for as long as the process lives — and nothing on screen would ever say so.
     */
    @Test fun `the last widget removed really stops the collection`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<Int>()
        val job = collectPush(placed, counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        counter.push(0, 4242)
        runCurrent()

        assertEquals(
            "an emission on the flow handed out while the widget was placed must not reach " +
                "anything once the last cell is gone. The real counter never completes, so a " +
                "subscription that was not cancelled goes on arriving here forever.",
            listOf(100),
            seen,
        )
        assertEquals("and it is not asked again on the way out", listOf("ask#0"), counter.asked)
        job.cancel()
    }

    /** A widget placed again after the last one was removed gets a FRESH subscription. */
    @Test fun `a widget placed again is counted again, on a new subscription`() = runTest {
        val counter = Counter()
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<Int>()
        val job = collectPush(placed, counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        placed.value = true
        runCurrent()

        assertEquals("the second placement asks again", listOf("ask#0", "ask#1"), counter.asked)
        assertEquals("and collects the new flow", listOf("collect#0", "collect#1"), counter.subscribed)
        assertEquals("the cell is drawn from the second subscription", listOf(100, 200), seen)
        job.cancel()
    }

    /**
     * The gate's ceiling: an answer that has not CHANGED must not re-subscribe. The presence flow
     * is fed by callbacks that can repeat, and each repeat would otherwise cancel and rebuild those
     * two Room aggregates for a state of affairs that is exactly what it already was.
     */
    @Test fun `the same answer twice does not re-subscribe the count`() = runTest {
        val counter = Counter()
        val seen = mutableListOf<Int>()
        val job = collectPush(flowOf(true, true), counter, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "'a widget is placed' said twice is one gate opening, not two: re-subscribing cancels " +
                "and rebuilds the unified count for nothing.",
            listOf("ask#0"),
            counter.asked,
        )
        assertEquals("and the cell is drawn once", listOf(100), seen)
        job.cancel()
    }

    /**
     * ⛔ THE SAME NUMBER TWICE IS ONE DRAW. Nothing upstream says so: the real counter is a
     * `combine` of two Room flows, and a single write makes both legs re-emit, so one message
     * marked read arrives here as the same integer two or more times over.
     *
     * A draw is not free — `getAppWidgetIds` over binder, the account blob decoded from JSON, a
     * `PendingIntent.getActivity(FLAG_UPDATE_CURRENT)`, and an `updateAppWidget` the launcher
     * re-inflates on — and every one of those repeats redrew digits that were already on the cell.
     *
     * ⚠ The counter fake above cannot show this: it hands back a [MutableStateFlow], which drops a
     * value equal to the one it holds all by itself. The repetition being defended against comes
     * from the two legs of a `combine`, so it is written here as a flow that really does repeat.
     */
    @Test fun `the same count arriving twice is drawn once`() = runTest {
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnreadWidgetPush.redraws(MutableStateFlow(true)) { flowOf(7, 7, 3, 3, 7) }.toList(seen)
        }
        runCurrent()

        assertEquals(
            "a count that has not moved must not reach the draw. The unified total is a combine " +
                "of two Room flows, so one write emits the same integer more than once, and each " +
                "one that got through cost a binder call, a full decode of the account blob and a " +
                "re-inflation in the launcher — for a cell already showing that number.",
            listOf(7, 3, 7),
            seen,
        )
        job.cancel()
    }
}

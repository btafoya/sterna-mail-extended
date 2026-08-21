package app.sterna.widget

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.mail.RecentInbox
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
 * The latest-messages widget's push, RUN — not read as source. What is pinned here is not that the
 * app watches the mail, it is WHEN it agrees to: never while no cell is placed, from the moment one
 * is, and no longer once the last one goes — plus how much a single Room invalidation is allowed to
 * cost the home screen, and which changes may never be swallowed on the way.
 *
 * The gate is the expensive half. `observeRecentUnifiedInbox(…)` is a Room query observing `emails`
 * AND `snoozed`, so it re-runs on every page of every sync; collected for the life of the process
 * it is paid by every install, and the large majority will never place this widget. So the tests
 * below assert on what the collaborator was ASKED and when it was SUBSCRIBED, the way
 * [UnreadWidgetPushTest] does next door — a returned value would say nothing about the subscription
 * that produced it.
 *
 * ⛔ The fake is HOT and never completes, on purpose. A `flowOf(…)` is finished the instant it is
 * read, and a finished inner flow makes `flatMapLatest`, `flatMapMerge` and `flatMapConcat`
 * indistinguishable — the real query never ends, so a subscription the shipped code failed to drop
 * stays alive and goes on arriving. [Reader.push] is that arrival, aimed at a flow handed out
 * earlier.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecentMailWidgetPushTest {

    private fun row(id: String, seen: Boolean = false) = RecentEmailRow(
        id = id,
        accountId = "acc",
        mailboxId = "inbox",
        subject = "subj-$id",
        fromName = "Nell",
        fromEmail = "nell@example.test",
        seen = seen,
        sortKey = 1L,
        receivedAt = "2026-08-18T10:00:00Z",
    )

    /** A read that answered: accounts were known, and these are their latest messages. */
    private fun read(vararg rows: RecentEmailRow) = RecentInbox(configured = true, rows = rows.toList())

    /** The other empty: no inbox could be read at all, so the cell may claim nothing. */
    private val nothingToSay = RecentInbox(configured = false, rows = emptyList())

    /**
     * Stands in for `MailRepository.observeRecentUnifiedInbox(limit)`, and records two facts a
     * single counter would conflate: that the factory was CALLED, and that the flow it handed back
     * was actually COLLECTED. Each subscription starts on a read of its own (`r0`, `r1`, …), so the
     * collected values also say WHICH subscription produced what.
     */
    private class Reader(private val initial: (Int) -> RecentInbox) {
        val asked = mutableListOf<String>()
        val subscribed = mutableListOf<String>()
        private val handed = mutableListOf<MutableStateFlow<RecentInbox>>()

        fun inbox(): Flow<RecentInbox> {
            val index = handed.size
            asked += "ask#$index"
            val flow = MutableStateFlow(initial(index))
            handed += flow
            return flow.onStart { subscribed += "collect#$index" }
        }

        /** The Room invalidation that would reach a subscription nothing cancelled. */
        fun push(index: Int, inbox: RecentInbox) {
            val flow = handed.getOrNull(index)
                ?: error("the reader was never asked a ${index + 1}th time — nothing to push to")
            flow.value = inbox
        }
    }

    private fun collectPush(
        placed: Flow<Boolean>,
        reader: Reader,
        seen: MutableList<RecentInbox>,
        scope: kotlinx.coroutines.CoroutineScope,
        dispatcher: TestDispatcher,
    ) = scope.launch(dispatcher) {
        RecentMailWidgetPush.redraws(placed, reader::inbox).toList(seen)
    }

    /**
     * The rule this file exists for: an install with no latest-messages cell on any home screen
     * must not observe the inbox AT ALL. Not "observe and ignore", not "observe and draw nothing" —
     * never ask.
     */
    @Test fun `no widget placed means the inbox is never even asked for`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(MutableStateFlow(false), reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "with no cell placed the read factory must not be called: collecting " +
                "observeRecentUnifiedInbox() subscribes a Room query woken by every write to " +
                "emails and to snoozed, for the whole life of the process, and the majority of " +
                "installs will never place this widget.",
            emptyList<String>(),
            reader.asked,
        )
        assertEquals("and nothing is subscribed either", emptyList<String>(), reader.subscribed)
        assertEquals("so nothing asks for a redraw", emptyList<RecentInbox>(), seen)
        job.cancel()
    }

    /**
     * The first cell placed opens the gate, and what happens afterwards arrives — this is the
     * measured defect, in one test: a message marked read moves the rows, and the redraw follows.
     */
    @Test fun `the first widget placed starts the collection, and later reads arrive`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(false)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(placed, reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = true
        runCurrent()
        reader.push(0, read(row("r0", seen = true)))
        runCurrent()

        assertEquals("the inbox is asked for exactly once, when the gate opens", listOf("ask#0"), reader.asked)
        assertEquals("and that flow is really collected", listOf("collect#0"), reader.subscribed)
        assertEquals(
            "the read at subscription time asks for a redraw, and so does every change after it " +
                "— a cell that only followed the mail when a message ARRIVED is the defect this " +
                "pane closes: the message read in the app keeps its unread dot on the home screen.",
            listOf(read(row("r0")), read(row("r0", seen = true))),
            seen,
        )
        job.cancel()
    }

    /**
     * ⛔ The claim [kotlinx.coroutines.flow.flatMapLatest] makes, executed rather than believed: the
     * subscription is DROPPED when the last cell goes, not merely superseded. With `flatMapMerge`
     * the app would keep an observed query alive for a widget that no longer exists, re-running it
     * on every write to the mail table for as long as the process lives — and nothing on any screen
     * would say so.
     */
    @Test fun `the last widget removed really stops the collection`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(placed, reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        reader.push(0, read(row("gone")))
        runCurrent()

        assertEquals(
            "an emission on the flow handed out while the cell was placed must not reach anything " +
                "once the last one is gone. The Room flow never completes, so a subscription that " +
                "was not cancelled goes on arriving here forever.",
            listOf(read(row("r0"))),
            seen,
        )
        assertEquals("and it is not asked again on the way out", listOf("ask#0"), reader.asked)
        job.cancel()
    }

    /** A cell placed again after the last one was removed gets a FRESH subscription. */
    @Test fun `a widget placed again is followed again, on a new subscription`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(true)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(placed, reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        placed.value = false
        runCurrent()
        placed.value = true
        runCurrent()

        assertEquals("the second placement asks again", listOf("ask#0", "ask#1"), reader.asked)
        assertEquals("and collects the new flow", listOf("collect#0", "collect#1"), reader.subscribed)
        assertEquals(
            "the cell is redrawn from the second subscription",
            listOf(read(row("r0")), read(row("r1"))),
            seen,
        )
        job.cancel()
    }

    /**
     * The gate's ceiling: an answer that has not CHANGED must not re-subscribe. Presence is fed by
     * callbacks that can repeat, and each repeat would otherwise cancel and rebuild the observed
     * query for a state of affairs that is exactly what it already was.
     */
    @Test fun `the same answer twice does not re-subscribe the inbox`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(flowOf(true, true), reader, seen, backgroundScope, UnconfinedTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(
            "'a cell is placed' said twice is one gate opening, not two: re-subscribing cancels " +
                "and rebuilds the observed inbox query for nothing.",
            listOf("ask#0"),
            reader.asked,
        )
        assertEquals("and the cell is redrawn once", listOf(read(row("r0"))), seen)
        job.cancel()
    }

    /**
     * ⛔ THE SAME READ TWICE IS ONE REDRAW. Nothing upstream says so: Room invalidates per TABLE,
     * so a sync writing a page of some other folder's mail re-runs this query and hands back the
     * identical answer.
     *
     * A redraw is not free — `getAppWidgetIds` over binder, the notification-content setting
     * re-read from DataStore, the account blob decoded, the query run again, then `updateAppWidget`
     * and `notifyAppWidgetViewDataChanged` the launcher re-inflates on — and every repeat redrew
     * rows already on the cell.
     *
     * ⚠ The [Reader] above cannot show this: it hands back a [MutableStateFlow], which drops a value
     * equal to the one it holds all by itself. The repetition being defended against comes from
     * Room, so it is written here as a flow that really does repeat.
     */
    @Test fun `the same read arriving twice is drawn once`() = runTest {
        val first = read(row("a"), row("b"))
        val second = read(row("a", seen = true), row("b"))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RecentMailWidgetPush.redraws(MutableStateFlow(true)) {
                flowOf(first, first, second, second, first)
            }.toList(seen)
        }
        runCurrent()

        assertEquals(
            "a read that has not moved must not reach the draw. Room invalidates the whole table, " +
                "so one sync page re-runs this query and answers identically, and each one that " +
                "got through cost a binder call, a DataStore read, a decode of the account blob " +
                "and a re-inflation in the launcher — for a cell already showing those rows.",
            listOf(first, second, first),
            seen,
        )
        job.cancel()
    }

    /**
     * ⛔ THE TWO EMPTIES ARE NOT THE SAME EMPTY, and this is the test the first version of this
     * pane did not have.
     *
     * `RecentMailWidgetContent.cell` draws an empty inbox as "No messages" and an unreadable one —
     * no account, a blob that would not decode, an account whose inbox id is still unknown — as the
     * app's own name, which claims nothing. `configured` is the whole difference.
     *
     * The de-duplication above sits on the value that travels, so the value has to carry it. On
     * rows alone these two reads are the same empty list: someone whose inbox was empty and who
     * then removes their last account would be left with a cell still affirming "No messages", and
     * someone who adds a first account to an empty mailbox would be left on the app's name forever.
     *
     * ⚠ It is written HERE and not against `UnifiedInbox.recentRows`, which is where the two values
     * are produced: that function is exercised on its own in `core:data`, where the value BEFORE the
     * empty one is never empty, so it crosses this de-duplication only in the composition — which
     * is the composition the app ships.
     */
    @Test fun `an empty inbox and no account at all are two different redraws`() = runTest {
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            RecentMailWidgetPush.redraws(MutableStateFlow(true)) {
                flowOf(read(), nothingToSay, read())
            }.toList(seen)
        }
        runCurrent()

        assertEquals(
            "an empty inbox followed by no account at all must reach the draw TWICE: the two " +
                "carry the same (empty) rows and are drawn as different sentences — 'No messages' " +
                "and the app's own name. Swallowing the second leaves the home screen affirming " +
                "an empty inbox to a reader who no longer has one to be empty.",
            listOf(read(), nothingToSay, read()),
            seen,
        )
        job.cancel()
    }
}

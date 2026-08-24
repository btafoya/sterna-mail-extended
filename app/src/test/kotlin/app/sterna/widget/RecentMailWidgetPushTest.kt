package app.sterna.widget

import app.sterna.core.data.account.StoredAccount
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

    /** Nothing but the id matters here: the bound this pane follows is the list's SIZE. */
    private fun account(id: String) = StoredAccount(id = id, server = "s", username = "u")

    /** Two accounts: above [AllInboxesView.existsFor], so the rows carry their colour dots. */
    private val twoAccounts = listOf(account("a"), account("b"))

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
        accounts: Flow<List<StoredAccount>> = MutableStateFlow(twoAccounts),
    ) = scope.launch(dispatcher) {
        RecentMailWidgetPush.redraws(placed, accounts, reader::inbox).toList(seen)
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
            RecentMailWidgetPush.redraws(MutableStateFlow(true), MutableStateFlow(twoAccounts)) {
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
            RecentMailWidgetPush.redraws(MutableStateFlow(true), MutableStateFlow(twoAccounts)) {
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

    /**
     * ⛔ THE ROWS DO NOT MOVE AND THE CELL MUST STILL BE REDRAWN — the defect this pane closes.
     *
     * `RecentMailWidgetContent.accountColors` paints a coloured dot per line only above
     * [AllInboxesView.existsFor], i.e. from two accounts up; below it the map is empty and no line
     * has a dot. So crossing that bound changes every single line on the home screen, in both
     * directions, WITHOUT changing the mail: an account that owns none of the 25 latest rows leaves
     * `RecentInbox` byte-identical when it arrives or goes. `configured` does not save it either —
     * it is "at least one account", and it is `true` on both sides of the bound.
     *
     * The de-duplication therefore had two equal values and swallowed the emission. With
     * `updatePeriodMillis="0"` nothing else was coming: on a quiet mailbox the cell showed dots for
     * an account that had been removed, or none for one that had just arrived, for days.
     *
     * ⚠ The expected emissions are written out in full and NOT recomputed from `existsFor` here —
     * a test that re-derived the bound would stay green with the shipped bound inverted.
     */
    @Test fun `crossing the two-account bound redraws, on rows that did not move`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val accounts = MutableStateFlow(twoAccounts)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            MutableStateFlow(true), reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = listOf(account("a"))
        runCurrent()
        accounts.value = twoAccounts
        runCurrent()

        assertEquals(
            "two accounts becoming one must ask for a redraw (every dot has to go), and one " +
                "becoming two must ask again (they all come back) — on rows that are strictly " +
                "identical throughout. Swallowed, the home screen keeps the dots of an account " +
                "that was removed, and there is no timer to catch it: updatePeriodMillis is 0.",
            listOf(read(row("r0")), read(row("r0")), read(row("r0"))),
            seen,
        )
        assertEquals("and the inbox query is NOT re-subscribed for it", listOf("ask#0"), reader.asked)
        assertEquals("still one subscription", listOf("collect#0"), reader.subscribed)
        job.cancel()
    }

    /**
     * ⛔ THE PRICE OF THE ARM ABOVE, and the reason it carries a BOUND and not the account list.
     *
     * `saveAccounts` is the choke point of some twenty-seven writes — a refreshed OAuth token, a
     * colour, identities discovered on a sync — and every one of them republishes `accountsFlow`.
     * A trigger that compared the accounts themselves would redraw the cell on all of them, each
     * time paying a `getAppWidgetIds` binder call, a DataStore read, the account blob decoded, the
     * query run again and a re-inflation in the launcher, for a cell whose lines are unchanged.
     *
     * And nothing may be RE-SUBSCRIBED: the inbox read stays keyed on the folder scopes
     * (`UnifiedInbox.recentRows`), so an account list that moves must not cancel and rebuild the
     * observed Room query.
     *
     * ⚠ A recolour is deliberately not redrawn from here: `AccountsViewModel.setColor` forgets the
     * snapshot and refreshes at the gesture.
     */
    @Test fun `an account list that does not cross the bound costs nothing`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val accounts = MutableStateFlow(listOf(account("a"), account("b"), account("c")))
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            MutableStateFlow(true), reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        accounts.value = twoAccounts
        runCurrent()
        accounts.value = listOf(account("d"), account("e"))
        runCurrent()

        assertEquals(
            "three accounts becoming two, then two others, never crosses the bound: the lines are " +
                "drawn exactly the same way, so the only redraw is the one the subscription itself " +
                "asked for. Every extra one costs a binder call, a DataStore read and a launcher " +
                "re-inflation, on each of the ~27 writes that go through saveAccounts.",
            listOf(read(row("r0"))),
            seen,
        )
        assertEquals(
            "and above all the inbox is not asked again: the observed Room query is keyed on the " +
                "folder scopes, and rebuilding it on every saveAccounts is the expense this arm " +
                "was designed around.",
            listOf("ask#0"),
            reader.asked,
        )
        assertEquals("nor re-subscribed", listOf("collect#0"), reader.subscribed)
        job.cancel()
    }
    /**
     * ⛔ ONE ACCOUNT IS NOT AN ABSENCE OF ACCOUNTS, and this test exists because the arm added for
     * the colour dots reads a BOUND: on a single-account install — the large majority — that bound
     * is `false` for the whole life of the process.
     *
     * An arm that only spoke above the bound (`filter { it }`, `dropWhile { !it }`) would leave
     * [kotlinx.coroutines.flow.combine] waiting on a value that never comes, and the cell would stop
     * following the mail ENTIRELY for everyone with one account: the defect measured at the bench on
     * 2026-08-18 — a message read in the app keeps its unread dot on the home screen — back whole,
     * and with `updatePeriodMillis="0"` nothing else would come to clear it.
     */
    @Test fun `a single account still follows the mail`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            MutableStateFlow(true), reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), MutableStateFlow(listOf(account("a"))),
        )
        runCurrent()

        reader.push(0, read(row("r0", seen = true)))
        runCurrent()

        assertEquals(
            "below the two-account bound the dots are never drawn, and the rows must be followed " +
                "exactly the same way: the read at subscription time, then the message being read " +
                "in the app. An arm that stayed silent under the bound would strand the combine " +
                "and freeze the cell for every single-account install.",
            listOf(read(row("r0")), read(row("r0", seen = true))),
            seen,
        )
        job.cancel()
    }

    /**
     * ⛔ THE GATE GOVERNS BOTH ARMS, and this is the half a combine placed outside [flatMapLatest]
     * would lose. Such a combine RETAINS the last value of each arm, and the gated arm never
     * completes — it only falls silent — so an account added or removed after the last cell was
     * taken off the home screen would pair a stale read with a fresh bound, get past the
     * de-duplication, and ask for a redraw on behalf of a widget that does not exist.
     *
     * The cost is one `getAppWidgetIds` binder call and a forgotten snapshot, which is small. What
     * is not small is the claim: this file says "nothing at all while no widget is placed", and the
     * wiring lint next door prices the whole gate on it. The sentence has to stay true.
     */
    @Test fun `an account list that moves while no cell is placed redraws nothing`() = runTest {
        val reader = Reader { read(row("r$it")) }
        val placed = MutableStateFlow(true)
        val accounts = MutableStateFlow(twoAccounts)
        val seen = mutableListOf<RecentInbox>()
        val job = collectPush(
            placed, reader, seen, backgroundScope,
            UnconfinedTestDispatcher(testScheduler), accounts,
        )
        runCurrent()

        placed.value = false
        runCurrent()
        accounts.value = listOf(account("a"))
        runCurrent()

        assertEquals(
            "the last cell is gone, so crossing the two-account bound must reach nothing: there " +
                "are no dots on any home screen to correct, and the gate is what this whole file " +
                "is for.",
            listOf(read(row("r0"))),
            seen,
        )
        job.cancel()
    }
}

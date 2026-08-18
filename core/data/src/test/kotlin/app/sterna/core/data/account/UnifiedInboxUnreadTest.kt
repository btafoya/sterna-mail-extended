package app.sterna.core.data.account

import app.sterna.core.data.filter.SourceText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "All inboxes (N)" is about to be shown twice — in the drawer and on a home-screen widget — and
 * the two must be unable to disagree. That holds only if ONE piece of code decides which inboxes
 * are counted, so this pins that decision by RUNNING it: what
 * [UnifiedInbox.unreadCount] hands the counter, argument by argument, and when it asks again.
 *
 * ⛔ The tests never re-derive the rule themselves — every expectation is a literal list of pairs.
 * A test that recomputed `accounts.mapNotNull { … }` to build its expectation would agree with a
 * shipped rule that had been inverted.
 *
 * The last two tests are source lints, and they are the weakest things here: nothing in this
 * module can build an [AccountStore] or a `MailRepository` (they want a `Context`, the Keystore, a
 * database and a session, and there is no Robolectric), so the two claims that cannot be executed
 * — that the store DELEGATES rather than keeping a second copy of the derivation, and that the
 * no-argument overload is wired to the LIVE account flow — are pinned as whole lines of shipped
 * source instead. The drawer's end of that same wiring is pinned in the `app` module, by
 * `UnifiedUnreadWiringLintTest`, which is where `InboxViewModel` lives.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedInboxUnreadTest {

    private fun account(id: String, inboxId: String?, unread: Int = 0) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = inboxId,
        unread = unread,
    )

    /**
     * Stands in for `MailRepository.observeUnifiedInboxUnread(scopes)` and records what it was
     * asked — the arguments are the point, not the arithmetic. Each known scope is worth a fixed
     * number, so the collected total also says WHICH inboxes were counted.
     *
     * ⛔ HOT, and it never completes — a `flowOf(…)` here would be a fake that is FINISHED the
     * instant it is read, and a finished inner flow makes `flatMapLatest`, `flatMapMerge` and
     * `flatMapConcat` indistinguishable. The real collaborator hands back a `combine` of Room
     * flows that never ends, so the fake is a [MutableStateFlow] kept per scope list: [push] can
     * then feed a subscription the shipped code was supposed to have dropped, and see whether it
     * still reaches the total.
     */
    private class Counter(private val perScope: Map<Pair<String, String>, Int> = emptyMap()) {
        val calls = mutableListOf<List<Pair<String, String>>>()
        private val handed = mutableMapOf<List<Pair<String, String>>, MutableStateFlow<Int>>()

        fun unreadFor(scopes: List<Pair<String, String>>): Flow<Int> {
            calls += scopes
            return handed.getOrPut(scopes) {
                MutableStateFlow(scopes.sumOf { perScope[it] ?: 0 })
            }
        }

        /** Emit [value] on the flow handed out for [scopes] — the Room invalidation that would
         *  reach a subscription nothing cancelled. */
        fun push(scopes: List<Pair<String, String>>, value: Int) {
            val flow = handed[scopes]
                ?: error("the counter was never asked for $scopes, so there is nothing to push to")
            flow.value = value
        }
    }

    @Test fun `only accounts with a known inbox are counted, and always as a pair`() = runTest {
        val counter = Counter()
        val accounts = MutableStateFlow(
            listOf(account("a", "inbox-1"), account("never-synced", inboxId = null)),
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadCount(accounts, counter::unreadFor).toList(mutableListOf())
        }
        runCurrent()

        assertEquals(
            "the counter must be asked for (accountId, inboxId) pairs of synced accounts only",
            listOf(listOf("a" to "inbox-1")),
            counter.calls,
        )
        job.cancel()
    }

    /**
     * Codeberg #121, executed. Two accounts on one server are routinely handed the same mailbox
     * id; a bare folder id would then match both accounts' rows, counting one inbox twice and the
     * other never. The account id in each pair is what keeps them apart.
     */
    @Test fun `two accounts sharing an inbox id stay two distinct scopes`() = runTest {
        val counter = Counter(mapOf(("a" to "42") to 3, ("b" to "42") to 5))
        val accounts = MutableStateFlow(listOf(account("a", "42"), account("b", "42")))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadCount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        assertEquals(
            "a collision on the mailbox id must not fold the two accounts into one scope",
            listOf(listOf("a" to "42", "b" to "42")),
            counter.calls,
        )
        assertEquals("both inboxes counted, neither twice", listOf(8), seen)
        job.cancel()
    }

    /** An account added, then one removed: the count follows, and stops reading what is gone. */
    @Test fun `an account list that changes is re-counted with the new scopes`() = runTest {
        val counter = Counter(mapOf(("a" to "ia") to 2, ("b" to "ib") to 7))
        val accounts = MutableStateFlow(listOf(account("a", "ia")))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadCount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("a", "ia"), account("b", "ib"))
        runCurrent()
        accounts.value = listOf(account("b", "ib"))
        runCurrent()

        assertEquals(
            "the counter must be re-subscribed with the scopes as they are now",
            listOf(
                listOf("a" to "ia"),
                listOf("a" to "ia", "b" to "ib"),
                listOf("b" to "ib"),
            ),
            counter.calls,
        )
        assertEquals("one account, then two, then the remaining one", listOf(2, 9, 7), seen)
        job.cancel()
    }

    /**
     * ⛔ The claim `flatMapLatest` makes, executed rather than believed: a removed account's
     * subscription is DROPPED, not merely superseded. The real counter is a `combine` of Room
     * flows that never completes and keeps emitting on every invalidation, so with `flatMapMerge`
     * the old subscription would still be alive here — and "All inboxes (N)" would flip between
     * the old total and the new one at every write to a database the widget no longer counts.
     *
     * The push below is that emission, aimed at the flow handed out for the scopes as they WERE.
     */
    @Test fun `a removed account's inbox is unsubscribed, not just superseded`() = runTest {
        val counter = Counter(mapOf(("a" to "ia") to 2, ("b" to "ib") to 7))
        val accounts = MutableStateFlow(listOf(account("a", "ia"), account("b", "ib")))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadCount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("b", "ib"))
        runCurrent()

        counter.push(listOf("a" to "ia", "b" to "ib"), 4242)
        runCurrent()

        assertEquals(
            "an emission on the flow of the PREVIOUS scopes must not reach the total: with " +
                "flatMapMerge it arrives as a third value and the widget oscillates; with " +
                "flatMapConcat the new scopes are never subscribed at all, because the counter's " +
                "flow never completes.",
            listOf(9, 7),
            seen,
        )
        job.cancel()
    }

    /**
     * The dedupe's ceiling: a write that moves NOTHING the total is built from must not re-count.
     * `accountsFlow` republishes on every `saveAccounts` — an OAuth token refreshed, identities
     * discovered on connect, a sync window, a colour — and each of those otherwise cancels and
     * rebuilds a `combine` of two global Room aggregates in the collector's context.
     *
     * The fields moved here (name and colour) are real [StoredAccount] fields and are settings
     * screens away from the unread total.
     */
    @Test fun `a write that touches nothing the count is built from does not re-count`() = runTest {
        val counter = Counter(mapOf(("a" to "ia") to 5))
        val stored = account("a", "ia", unread = 5)
        val accounts = MutableStateFlow(listOf(stored))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadCount(accounts, counter::unreadFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(stored.copy(accountName = "Work", color = 0x336699))
        runCurrent()

        assertEquals(
            "renaming an account, or recolouring it, republishes the whole account list; the " +
                "count must be asked for ONCE all the same. Asking again cancels and rebuilds the " +
                "Room aggregates on the collector's context for a number that cannot have moved.",
            listOf(listOf("a" to "ia")),
            counter.calls,
        )
        assertEquals("and nothing new is emitted either", listOf(5), seen)
        job.cancel()
    }

    /**
     * The IMAP half, and the reason the dedupe key is NOT the derived scopes alone. An IMAP inbox
     * contributes `accountStore.account(id)?.unread`, a flat read inside `MailRepository`'s
     * `combine` block that no Room invalidation ever wakes. The account list changing IS the
     * notification — and here it changes without the scopes changing at all, so a key made of
     * scopes only would freeze this number where the flow first found it.
     */
    @Test fun `a stored counter moving re-counts even though the scopes are identical`() = runTest {
        val calls = mutableListOf<List<Pair<String, String>>>()
        val accounts = MutableStateFlow(listOf(account("imap", "INBOX", unread = 4)))
        val seen = mutableListOf<Int>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.unreadCount(accounts) { scopes ->
                calls += scopes
                // The flat read MailRepository does for IMAP: whatever is stored at this instant.
                flowOf(scopes.sumOf { (id, _) -> accounts.value.first { it.id == id }.unread })
            }.toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("imap", "INBOX", unread = 9))
        runCurrent()

        assertEquals(
            "the same scopes must be asked again when the stored record changes",
            listOf(listOf("imap" to "INBOX"), listOf("imap" to "INBOX")),
            calls,
        )
        assertEquals("the drawer follows the stored IMAP counter", listOf(4, 9), seen)
        job.cancel()
    }

    /**
     * ⛔ Whole line, not a `contains`: a fragment check passes on anything appended to it. This is
     * the "one copy" claim — the store must feed [UnifiedInbox.scopes] its stored list and nothing
     * else. A second `mapNotNull { … inboxId … }` here is a rule that can drift from the widget's.
     */
    @Test fun `AccountStore derives the scopes through the shared rule, not a copy of it`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/account/AccountStore.kt",
        )
        assertEquals(
            "AccountStore.allInboxScopes no longer delegates to UnifiedInbox.scopes. The drawer " +
                "and the widget then derive 'all inboxes' from two pieces of code, which is the " +
                "one thing this whole file exists to prevent.",
            listOf("fun allInboxScopes(): List<Pair<String, String>> = UnifiedInbox.scopes(accounts())"),
            SourceText.codeLines(SourceText.functionSource(source, "fun allInboxScopes()")),
        )
    }

    /**
     * SOURCE LINT, and the one hole the tests above cannot reach: they inject their own account
     * flow, so nothing here ever executes the WIRING — what the shipped overload actually
     * subscribes to. `MailRepository` needs a `Context`, a database and a session; it cannot be
     * built in this suite, and no test in this repository instantiates it.
     *
     * The mutation this exists for compiles and leaves every other test in the repository green:
     *
     *     flowOf(accountStore.accounts())
     *
     * A one-shot flow of the list as it is at subscription time. The widget and the drawer would
     * then be frozen at the number they were built with — mail read, accounts added, IMAP counters
     * moved, and neither ever moves again. That is precisely the defect this branch closes.
     *
     * ⛔ Whole lines, never a `contains`: a substring rule is satisfied by every mutation that
     * LENGTHENS the line, and `flowOf(accountStore.accountsFlow.value)` contains
     * `accountStore.accountsFlow`. The price is that reformatting the call reddens this test —
     * loud, and one line to fix.
     */
    @Test fun `MailRepository builds the no-argument overload on the live account flow`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeUnifiedInboxUnread() must hand UnifiedInbox.unreadCount the " +
                "LIVE accountStore.accountsFlow and delegate the counting to the scoped overload. " +
                "A snapshot (flowOf(accountStore.accounts())) compiles, keeps every other test " +
                "green, and freezes the drawer and the widget at the number they started with.",
            listOf(
                "fun observeUnifiedInboxUnread(): Flow<Int> = UnifiedInbox.unreadCount(",
                "accountStore.accountsFlow,",
                ") { scopes -> observeUnifiedInboxUnread(scopes) }",
            ),
            SourceText.codeLines(
                SourceText.functionSource(source, "fun observeUnifiedInboxUnread(): Flow<Int>"),
            ),
        )
    }
}

package app.sterna.core.data.account

import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.filter.SourceText
import app.sterna.core.data.mail.RecentInbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHICH inboxes the "latest messages" home-screen widget follows, and WHEN it asks again — pinned
 * by RUNNING [UnifiedInbox.recentRows], the way [UnifiedInboxUnreadTest] runs the counter's rule
 * next door. The widget's rows and the drawer's "All inboxes (N)" must be unable to disagree about
 * which accounts are in, so the derivation exists once and both call it.
 *
 * ⛔ The tests never re-derive the rule themselves — every expectation is a literal list of pairs. A
 * test that recomputed `accounts.mapNotNull { … }` to build its expectation would agree with a
 * shipped rule that had been inverted.
 *
 * The last two tests are source lints, and they are the weakest things here: nothing in this module
 * can build a `MailRepository` (it wants a `Context`, a database and a session, and there is no
 * Robolectric), and nothing here runs Room at all. So the two claims that cannot be executed — that
 * the shipped function is wired to the LIVE account flow and reuses the widget query the snapshot
 * read already runs, and that the DAO's observed read is woken by both the mail and the snoozes —
 * are pinned as whole lines of shipped source instead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UnifiedInboxRecentRowsTest {

    private fun account(id: String, inboxId: String?, unread: Int = 0) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = inboxId,
        unread = unread,
    )

    private fun row(id: String, accountId: String) = RecentEmailRow(
        id = id,
        accountId = accountId,
        mailboxId = "inbox",
        subject = "subj-$id",
        fromName = null,
        fromEmail = "someone@example.test",
        seen = false,
        sortKey = 1L,
        receivedAt = "2026-08-18T10:00:00Z",
    )

    /** A read that answered: the accounts were known, and these are their latest messages. */
    private fun read(vararg rows: RecentEmailRow) = RecentInbox(configured = true, rows = rows.toList())

    /**
     * Stands in for `MailRepository.observeRecentUnifiedInbox(limit)` and records what it was
     * ASKED — the arguments are the point. Each scope list gets a flow of its own, so the rows
     * collected also say WHICH subscription produced them.
     *
     * ⛔ HOT, and it never completes: a `flowOf(…)` here would be finished the instant it was read,
     * and a finished inner flow makes `flatMapLatest`, `flatMapMerge` and `flatMapConcat`
     * indistinguishable. The real collaborator is a Room flow that never ends, so [push] can feed a
     * subscription the shipped code was supposed to have dropped and see whether it still arrives.
     */
    private class Reader {
        val calls = mutableListOf<List<Pair<String, String>>>()
        private val handed = mutableMapOf<List<Pair<String, String>>, MutableStateFlow<List<RecentEmailRow>>>()

        fun rowsFor(scopes: List<Pair<String, String>>): Flow<List<RecentEmailRow>> {
            calls += scopes
            return handed.getOrPut(scopes) { MutableStateFlow(scopes.map { (id, _) -> seed(id) }) }
        }

        /** The Room invalidation that would reach a subscription nothing cancelled. */
        fun push(scopes: List<Pair<String, String>>, rows: List<RecentEmailRow>) {
            val flow = handed[scopes]
                ?: error("the reader was never asked for $scopes, so there is nothing to push to")
            flow.value = rows
        }

        companion object {
            /** One row per account in the scope list, so the collected value names its accounts. */
            fun seed(accountId: String) = RecentEmailRow(
                id = "seed-$accountId",
                accountId = accountId,
                mailboxId = "inbox",
                subject = null,
                fromName = null,
                fromEmail = null,
                seen = false,
                sortKey = 0L,
                receivedAt = null,
            )
        }
    }

    @Test fun `only accounts with a known inbox are read, and always as a pair`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(
            listOf(account("a", "inbox-1"), account("never-synced", inboxId = null)),
        )
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(mutableListOf())
        }
        runCurrent()

        assertEquals(
            "the widget read must be asked for (accountId, inboxId) pairs of synced accounts only " +
                "— an account whose inbox id is still unknown has no folder to read, and a made-up " +
                "scope would read someone else's.",
            listOf(listOf("a" to "inbox-1")),
            reader.calls,
        )
        job.cancel()
    }

    /**
     * Codeberg #121, on this read too. Two accounts on one server are routinely handed the same
     * mailbox id; a bare folder id would then match both accounts' rows, listing one inbox twice
     * and the other never. The account id in each pair is what keeps them apart.
     */
    @Test fun `two accounts sharing an inbox id stay two distinct scopes, in order`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "42"), account("b", "42")))
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(mutableListOf())
        }
        runCurrent()

        assertEquals(
            "a collision on the mailbox id must not fold the two accounts into one scope",
            listOf(listOf("a" to "42", "b" to "42")),
            reader.calls,
        )
        job.cancel()
    }

    /**
     * ⛔ THE ARBITRAGE THIS FILE EXISTS TO PIN, and the one place this rule DIVERGES from
     * [UnifiedInbox.unreadCount]: the key is the scopes, not [UnifiedInbox.countedInboxes].
     *
     * The count keys on the stored counter because its IMAP half reads that record flat inside a
     * `combine`, which no Room invalidation wakes. These rows come entirely from Room, so the
     * stored counter cannot move one of them — and `accountsFlow` republishes on every
     * `saveAccounts`, some 27 call sites: an OAuth token refreshed on a timer, a colour, identities
     * discovered on connect. Keyed on `countedInboxes`, each of those would cancel and rebuild an
     * observed query and redraw a home-screen cell for rows that cannot have changed.
     */
    @Test fun `a stored counter moving does not re-subscribe the rows`() = runTest {
        val reader = Reader()
        val stored = account("imap", "INBOX", unread = 4)
        val accounts = MutableStateFlow(listOf(stored))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(stored.copy(unread = 9, accountName = "Work", color = 0x336699))
        runCurrent()

        assertEquals(
            "the stored unread counter changing, an account renamed, an account recoloured: none " +
                "of them can move a row that comes from Room, and each would otherwise cancel and " +
                "rebuild the observed query and redraw the cell.",
            listOf(listOf("imap" to "INBOX")),
            reader.calls,
        )
        assertEquals("and nothing new is emitted either", listOf(read(Reader.seed("imap"))), seen)
        job.cancel()
    }

    /** An account added, then one removed: the read follows, with the scopes as they are now. */
    @Test fun `an account list that changes is re-read with the new scopes`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "ia")))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("a", "ia"), account("b", "ib"))
        runCurrent()
        accounts.value = listOf(account("b", "ib"))
        runCurrent()

        assertEquals(
            "the reader must be re-subscribed with the scopes as they are now",
            listOf(
                listOf("a" to "ia"),
                listOf("a" to "ia", "b" to "ib"),
                listOf("b" to "ib"),
            ),
            reader.calls,
        )
        assertEquals(
            "and the cell is redrawn from each of them in turn",
            listOf(
                read(Reader.seed("a")),
                read(Reader.seed("a"), Reader.seed("b")),
                read(Reader.seed("b")),
            ),
            seen,
        )
        job.cancel()
    }

    /**
     * ⛔ The claim `flatMapLatest` makes, executed rather than believed: a removed account's
     * subscription is DROPPED, not merely superseded. The real reader is a Room flow that never
     * completes and re-runs on every write to `emails`, so with `flatMapMerge` the old subscription
     * would still be alive — and the home screen would flip between the accounts as they are and
     * the accounts as they were, at every sync.
     */
    @Test fun `a removed account's rows are unsubscribed, not just superseded`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "ia"), account("b", "ib")))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = listOf(account("b", "ib"))
        runCurrent()
        reader.push(listOf("a" to "ia", "b" to "ib"), listOf(row("ghost", "a")))
        runCurrent()

        assertEquals(
            "an emission on the flow of the PREVIOUS scopes must not reach the redraw: with " +
                "flatMapMerge it arrives as a third value and the cell oscillates; with " +
                "flatMapConcat the new scopes are never subscribed at all, because a Room flow " +
                "never completes.",
            listOf(
                read(Reader.seed("a"), Reader.seed("b")),
                read(Reader.seed("b")),
            ),
            seen,
        )
        job.cancel()
    }

    /**
     * ⛔ NO ACCOUNT LEFT IS ONE EMISSION, IT SAYS `configured = false`, AND IT READS NOTHING.
     *
     * One emission, because removing the last account has to reach the home screen: an
     * `emptyFlow()` here is silence, and the cell would go on listing a deleted account's mail
     * until something else redrew it. Not a read, because `folderScopeSql(0, …)` is the constant
     * `0` — a statement that matches nothing while still subscribing Room to every write to
     * `emails`, on behalf of someone who has no account at all.
     *
     * ⭐ And `configured` rides along, decided by `recentUnifiedInboxFrom` — the same function the
     * snapshot read runs, so the two cannot answer differently. It is what the cell draws as the
     * app's own name rather than "No messages".
     *
     * ⚠ WHAT THIS TEST CANNOT SEE: the value BEFORE this one is a non-empty read, so it never
     * crosses the de-duplication the app's collector applies on the way to the draw. An inbox that
     * was ALREADY empty when the last account went is the case that does, and it is pinned in the
     * `app` module by `RecentMailWidgetPushTest` — on rows alone those two values are equal and the
     * second is swallowed.
     */
    @Test fun `no account left emits one unconfigured read and reads nothing`() = runTest {
        val reader = Reader()
        val accounts = MutableStateFlow(listOf(account("a", "ia")))
        val seen = mutableListOf<RecentInbox>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            UnifiedInbox.recentRows(accounts, reader::rowsFor).toList(seen)
        }
        runCurrent()

        accounts.value = emptyList()
        runCurrent()

        assertEquals(
            "the last account removed must ask for a redraw exactly once, and that redraw must " +
                "say configured = false — 'nothing to affirm', which the cell draws as the app's " +
                "own name. configured = true with no rows is the OTHER empty, drawn 'No messages', " +
                "and telling someone who has just removed their last account that their mail is " +
                "empty is the claim RecentInbox exists to refuse.",
            listOf(read(Reader.seed("a")), RecentInbox(configured = false, rows = emptyList())),
            seen,
        )
        assertEquals(
            "and it must not build a scope-less query: that statement matches nothing and still " +
                "subscribes Room to every write to the mail table.",
            listOf(listOf("a" to "ia")),
            reader.calls,
        )
        job.cancel()
    }

    /**
     * SOURCE LINT, and the hole the tests above cannot reach: they inject their own account flow
     * and their own reader, so nothing here executes the WIRING. `MailRepository` needs a
     * `Context`, a database and a session; no test in this repository instantiates it.
     *
     * Two mutations this exists for, both of which compile and leave every other test green:
     *
     *     flowOf(accountStore.accounts())   — a snapshot: the widget then follows the accounts it
     *                                         started with, and an account added or removed never
     *                                         reaches the home screen;
     *     a second SQL string built here    — a twin of `recentUnifiedQuery` to keep in step with
     *                                         the list's predicate by hand. That is the shape of
     *                                         #126: the observed query and the drawn query drift,
     *                                         and the cell redraws on the wrong writes.
     *
     * ⛔ Whole lines, never a `contains`: a substring rule is satisfied by every mutation that
     * LENGTHENS the line.
     */
    @Test fun `MailRepository observes the SAME widget query, over the live account flow`() {
        val source = SourceText.read(
            "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt",
        )
        assertEquals(
            "MailRepository.observeRecentUnifiedInbox must hand UnifiedInbox.recentRows the LIVE " +
                "accountStore.accountsFlow and read through recentUnifiedQuery — the statement " +
                "recentUnifiedInbox already runs. A snapshot freezes the widget at the accounts it " +
                "started with; a second SQL is a twin that drifts from the list's own predicate.",
            listOf(
                "fun observeRecentUnifiedInbox(limit: Int): Flow<RecentInbox> = UnifiedInbox.recentRows(",
                "accountStore.accountsFlow,",
                ") { scopes -> emailDao.observeRecentUnified(recentUnifiedQuery(scopes, limit)) }",
            ),
            SourceText.codeLines(
                SourceText.functionSource(source, "fun observeRecentUnifiedInbox(limit: Int)"),
            ),
        )
    }

    /**
     * SOURCE LINT, and the only kind available for this one: what makes the widget's read REACTIVE
     * is a Room annotation, and no test in this repository runs Room. There is no in-memory
     * database here and no instrumentation — `RecentUnifiedInboxSqlTest` runs the statement against
     * plain SQLite, which knows nothing about invalidation.
     *
     * Both entities are load bearing and each is one identifier:
     *
     *  - `EmailEntity` dropped: nothing wakes the query when mail is read, deleted or moved, and
     *    the whole pane is undone — the unread dot stays on the home screen exactly as it was
     *    measured at the bench on 2026-08-18, with every test still green;
     *  - `SnoozedEntity` dropped: a message snoozed away, or one whose snooze expires, changes
     *    which rows this statement returns without a single write to `emails` — the shared
     *    predicate `listRowsWhereSql` carries `notSnoozedSql`. The cell would keep showing a
     *    message the app has hidden, and hide one the app has brought back.
     *
     * `pagingSource` declares the same two for the same reason, and that is the agreement being
     * held still here.
     *
     * ⛔ Whole lines, never a `contains`.
     */
    @Test fun `the widget's observed read is woken by BOTH the mail and the snoozes`() {
        val lines = SourceText.codeLines(
            SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailDao.kt"),
        )
        val at = lines.indexOfFirst { it.startsWith("fun observeRecentUnified(") }
        check(at > 0) { "EmailDao no longer declares `fun observeRecentUnified(` — was it renamed?" }
        assertEquals(
            "EmailDao.observeRecentUnified must be a @RawQuery observing EmailEntity AND " +
                "SnoozedEntity. Without the first the widget stops following the mail at all; " +
                "without the second a snooze expiring never redraws the cell. Neither can be " +
                "caught by running anything: this suite does not run Room.",
            listOf(
                "@RawQuery(observedEntities = [EmailEntity::class, SnoozedEntity::class])",
                "fun observeRecentUnified(query: SupportSQLiteQuery): Flow<List<RecentEmailRow>>",
            ),
            lines.subList(at - 1, at + 1),
        )
    }
}

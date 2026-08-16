package app.sterna.push

import app.sterna.core.data.account.MailProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fallback poll cycle that fetched nothing — or fetched an account without its inbox — must say
 * what it left out, and why.
 *
 * [MailFetchWorker] is the ~30-minute safety net (issue #11) and it ended on a single
 * `Result.success()`, reachable without a word written in four ways: an empty account list before
 * the loop (no account at all, an account blob that failed to decode, a secret that failed to
 * decrypt — [app.sterna.core.data.account.AccountStore] drops the last two silently), the same list
 * emptied by the notifications filter, the per-account `continue` (which on JMAP fires for every
 * account a handle is held for, since `hasExtrasToPoll` is false there by construction), and the
 * IMAP half-skip, where the fetch runs with `includeInbox = false` and succeeds. A reporter's logcat
 * then shows `Worker result SUCCESS … MailFetchWorker` and nothing else, and "push covered
 * everything" is indistinguishable from "the inbox was never looked at".
 *
 * This class pins the three lines added for that ([PushController.nothingToPollLine],
 * [PushController.pollSkippedLine], [PushController.inboxSkippedLine]) — literally, since they are
 * read as evidence when diagnosing a report — the fact that they differ from each other and from
 * [PushController.nothingToWatchLine], which the service writes into the same logcat, and the fact
 * that none of them CLAIMS AN OPEN CONNECTION. That last one is not style: `PushService.isConnected`
 * answers true for a handle we hold and believe in, which is exactly the state under suspicion, so a
 * line asserting "open" would put the hypothesis into the evidence.
 *
 * KNOWN GAP, stated rather than papered over: `MailFetchWorker.doWork` is not executable by any test
 * in this module. It needs an [android.app.Application] to reach the DI container, an AccountStore
 * backed by encrypted storage, and a WorkManager `Result`; there is no Robolectric here. So the
 * WIRING — that those lines are emitted, at those places, with those arguments — is pinned by
 * reading the source, whole statements in order. That proves the call sites exist, in that order,
 * with exactly those arguments; it proves NOTHING about the values those arguments carry at runtime
 * (`store.accountsUnreadable()` really re-reading the blob is read, not run), and it breaks on
 * innocent reformatting. The decisions themselves ([shouldPollInbox], [hasExtrasToPoll]) are pure
 * and executed by `PushWatchTest`; this volet does not touch them and does not re-test them.
 */
class FallbackPollSaysWhatItSkippedTest {

    // --- an empty cycle: three failures and one choice, four sentences ---------------------------
    //
    // The promise of the whole volet is here. `watched` is already the output of two SILENT filters
    // — an unreadable blob makes AccountStore.accounts() answer an empty list, and allCredentials()
    // is a mapNotNull that drops an account whose secret failed to decrypt — so a count alone prints
    // the sentence of an empty phone for two genuine failures. The store can answer, and the repo
    // already asks it that way for this exact problem (accountGoneCause, from a fresh
    // accountsUnreadable() read). Expected strings are written out: rebuilding them with the same
    // expression as the function would make this file a copy of the code.

    /** The failure the blob gate knows about. It must not read as "this phone has no account". */
    @Test fun `an unreadable account blob is not reported as an empty install`() {
        assertEquals(
            "Fallback poll found nothing to fetch: the stored account list could not be read, " +
                "so no account was polled (not an empty install)",
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = true),
        )
    }

    /**
     * ORDER, and it is the whole reason the unreadable verdict is asked first: an unreadable blob
     * ALSO makes `stored` zero, so a `stored == 0` branch tested first would swallow it. Same
     * arguments as the empty install below, opposite sentence.
     */
    @Test fun `the unreadable verdict wins over the empty count it also produces`() {
        assertNotEquals(
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = true),
        )
        assertEquals(
            "Fallback poll found nothing to fetch: the stored account list could not be read, " +
                "so no account was polled (not an empty install)",
            PushController.nothingToPollLine(stored = 3, candidates = 0, accountsUnreadable = true),
        )
    }

    /** The genuinely empty phone: nothing configured, nothing to poll, nothing wrong. */
    @Test fun `an install with no account says exactly that`() {
        assertEquals(
            "Fallback poll found nothing to fetch: no account is configured, so there was nothing to poll",
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = false),
        )
    }

    /**
     * The third case, and the one no count could show before: accounts ARE stored, and not one
     * credential came back — a secret that failed to decrypt. "for the watched set" and not "for all
     * of them", because with push-for-all off only the current account was ever asked for.
     */
    @Test fun `stored accounts with no credentials read as a failure, not as a choice`() {
        assertEquals(
            "Fallback poll found nothing to fetch: 2 account(s) configured, but no credentials " +
                "came back for the watched set, so no account was polled (not a choice)",
            PushController.nothingToPollLine(stored = 2, candidates = 0, accountsUnreadable = false),
        )
    }

    /** The only branch that is a decision of the user's: credentials came back, notifications off. */
    @Test fun `every watched account opting out is reported as a choice`() {
        assertEquals(
            "Fallback poll found nothing to fetch: 0 of 2 watched account(s) have notifications on, cycle over",
            PushController.nothingToPollLine(stored = 2, candidates = 2, accountsUnreadable = false),
        )
    }

    /**
     * The four outcomes must be four sentences. Collapsing any two of them puts a failure and a
     * choice — or a failure and an empty phone — behind the same words, which is the defect this
     * volet exists for.
     */
    @Test fun `the four empty-cycle outcomes print four different sentences`() {
        val lines = listOf(
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = true),
            PushController.nothingToPollLine(stored = 0, candidates = 0, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 2, candidates = 0, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 2, candidates = 2, accountsUnreadable = false),
        )
        assertEquals("two empty-cycle outcomes print the same sentence: $lines", 4, lines.toSet().size)
    }

    // --- the per-account skips: what the code KNOWS, not what it hopes ---------------------------

    /**
     * The JMAP case the `continue` is widest for: the account is skipped whole even with folders
     * watched, because the handle is meant to carry them. The sentence must not read as a
     * contradiction ("nothing else to poll" next to "3 watched folders"), so it says where those
     * folders went instead of asserting there are none.
     */
    @Test fun `a skipped JMAP account says its extras are left to the handle`() {
        assertEquals(
            "Fallback poll skipped account acc-1 whole: a push connection handle is held for it, " +
                "so its inbox and its 3 watched extra folder(s) are left to that handle (JMAP)",
            PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 3),
        )
    }

    /** On IMAP the whole-skip only happens with no watched extra: IDLE sees the INBOX alone (#16). */
    @Test fun `a skipped IMAP account is one with no watched extra`() {
        assertEquals(
            "Fallback poll skipped account acc-1 whole: a push connection handle is held for it, " +
                "so its inbox and its 0 watched extra folder(s) are left to that handle (IMAP)",
            PushController.pollSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 0),
        )
    }

    /**
     * The half-skip, IMAP: the fetch runs, and the INBOX is not in it. Until this line the trace was
     * a successful fetch — the friendliest possible mark for a missed inbox.
     */
    @Test fun `an unfetched inbox says so even though the fetch ran`() {
        assertEquals(
            "Fallback poll left account acc-1's inbox unfetched: a push connection handle is held " +
                "for it, so only its 2 watched extra folder(s) were polled (IMAP)",
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 2),
        )
    }

    /**
     * ⛔ The word this volet may not print. Both lines are reached through
     * [PushService.isConnected], which is true for a [java.io.Closeable] we hold and believe in —
     * `armSummary`'s KDoc calls that "held" and refuses to call it "open" for this exact reason, and
     * a socket that died silently still answers true. A line asserting an open connection would
     * state, in the reporter's own logcat, the thing being investigated.
     */
    @Test fun `neither skip line claims a connection is open`() {
        val lines = listOf(
            PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 3),
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 2),
        )
        lines.forEach { line ->
            assertFalse("a skip line may not claim an open connection: $line", line.lowercase().contains("open"))
            assertTrue("a skip line must say the handle is held: $line", line.contains("handle is held"))
        }
    }

    /**
     * The protocol and the folder count must both reach the sentence: with either dropped, two
     * different skips would read alike and a logcat could no longer say why an account with watched
     * folders was skipped. Argument ORDER is not tested separately — the three parameters have three
     * different types, so a permutation does not compile.
     */
    @Test fun `the skip line carries both facts, so two different skips read differently`() {
        val jmapWithExtras = PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 3)
        val imapWithout = PushController.pollSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 0)
        val jmapWithout = PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 0)
        assertEquals(
            "the three skips must print three distinct sentences: $jmapWithExtras / $imapWithout / $jmapWithout",
            3,
            setOf(jmapWithExtras, imapWithout, jmapWithout).size,
        )
    }

    /** Different accounts, different lines: the id is what lets a reader follow one account. */
    @Test fun `the skip lines name the account they skipped`() {
        assertNotEquals(
            PushController.pollSkippedLine("acc-1", MailProtocol.JMAP, watchedFolders = 0),
            PushController.pollSkippedLine("acc-2", MailProtocol.JMAP, watchedFolders = 0),
        )
        assertNotEquals(
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 1),
            PushController.inboxSkippedLine("acc-2", MailProtocol.IMAP, watchedFolders = 1),
        )
    }

    /**
     * Five sentences now share the worker's and the service's logcat, and none may be read as
     * another: an arm that stopped the service, a cycle that polled nothing, a cycle that polled
     * nothing because the storage failed, an account skipped whole, an account fetched without its
     * inbox. Same account and same counts throughout, so only the wording can carry the difference.
     */
    @Test fun `none of the silent outcomes print the same sentence`() {
        val lines = listOf(
            PushController.nothingToWatchLine(candidates = 1),
            PushController.nothingToPollLine(stored = 1, candidates = 1, accountsUnreadable = false),
            PushController.nothingToPollLine(stored = 1, candidates = 1, accountsUnreadable = true),
            PushController.pollSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 1),
            PushController.inboxSkippedLine("acc-1", MailProtocol.IMAP, watchedFolders = 1),
        )
        assertEquals("two of the silent-outcome lines print the same sentence: $lines", 5, lines.toSet().size)
    }

    // --- SOURCE RULE: the call sites, read whole ------------------------------------------------
    //
    // Weakest tests of the file, and the reason is in the class KDoc: doWork cannot be run here.
    // Whole statements, in order, never a substring — a `contains` check is blind to anything
    // APPENDED to the line it looks at, and appending is exactly how a second condition or a fourth
    // argument comes back. Reading them whole is also the only way to pin the ARGUMENTS rather than
    // the mere existence of a call, and the arguments are where this volet can be nullified in
    // silence: `nothingToPollLine(candidates = accounts.size)` would print "0 of 0" for every
    // opted-out install, and `FetchAndNotify.run(…, includeInbox = false)` would take the safety net
    // away from every account whose handle is held, leaving all three new lines correct and green.
    //
    // `Log.i`, never `Log.d`/`Log.v`: proguard-rules.pro strips those in the release build reporters
    // run. No Throwable argument either: a skip is a fact the app already knows, and Log's cause
    // chain prints the server and device IPs of an OkHttp ConnectException.

    /**
     * The head of `doWork`, pinned as far as the loop. Four things ride on this window and each was
     * a way to nullify the volet silently: the guard sits AFTER `up.reconcileDistributorPresence()`,
     * so adding a line does not steal a call that runs today; it counts `watched` (pre-filter) and
     * not `accounts`; it reads the stored count and a FRESH `accountsUnreadable()` verdict, which is
     * the only thing separating a storage failure from an empty phone; and the early
     * `return Result.success()` is the same result as the one at the bottom.
     */
    @Test fun `an empty cycle names its cause, after reconciling the distributor`() {
        assertEquals(
            "expected exactly one nothingToPollLine call site in MailFetchWorker",
            1,
            workerOccurrencesOf("nothingToPollLine"),
        )
        assertEquals(
            listOf(
                "val container = (applicationContext as Application).container",
                "val store = container.accountStore",
                "val watched = if (store.pushAllAccounts()) store.allCredentials() else listOfNotNull(store.load())",
                "val accounts = watched.filter { store.notificationsEnabled(it.id) }",
                "val up = container.unifiedPushManager",
                "up.reconcileDistributorPresence()",
                "if (accounts.isEmpty()) {",
                "Log.i(",
                "TAG,",
                "PushController.nothingToPollLine(",
                "stored = store.accounts().size,",
                "candidates = watched.size,",
                "accountsUnreadable = store.accountsUnreadable(),",
                "),",
                ")",
                "return Result.success()",
                "}",
                "for (credentials in accounts) {",
            ),
            doWorkStatements().take(18),
        )
    }

    /**
     * The two skips and the fetch they guard, in one window, because the fetch is where the volet
     * can be undone without touching a line: `includeInbox = pollInbox` is what makes the safety net
     * a safety net, and it sits one statement past where a window stopping at the `continue` would
     * end. The window also pins that the decision still comes from [shouldPollInbox] with
     * (connected, linked) in that order — two booleans, so a swap compiles and changes which inboxes
     * are polled — and that the folder set is read ONCE into a local, so the count printed is the
     * one the decision was taken on. The `isImap &&` of `hasExtrasToPoll` is read whole here too, so
     * it cannot quietly disappear behind an added log line.
     */
    @Test fun `a skipped account and an unfetched inbox both say so, and the fetch keeps its inbox flag`() {
        assertEquals(
            "expected exactly one pollSkippedLine call site in MailFetchWorker",
            1,
            workerOccurrencesOf("pollSkippedLine"),
        )
        assertEquals(
            "expected exactly one inboxSkippedLine call site in MailFetchWorker",
            1,
            workerOccurrencesOf("inboxSkippedLine"),
        )
        assertEquals(
            listOf(
                "val connected = PushService.isConnected(credentials.id)",
                "val pollInbox = shouldPollInbox(connected, linked)",
                "val watchedFolders = store.watchedFolders(credentials.id)",
                "val pollExtras = hasExtrasToPoll(",
                "isImap = credentials.protocol == MailProtocol.IMAP,",
                "watchedFolders = watchedFolders,",
                ")",
                "if (!pollInbox && !pollExtras) {",
                "Log.i(TAG, PushController.pollSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))",
                "continue",
                "}",
                "if (!pollInbox) {",
                "Log.i(TAG, PushController.inboxSkippedLine(credentials.id, credentials.protocol, watchedFolders.size))",
                "}",
                "runCatching {",
                "FetchAndNotify.run(applicationContext, credentials, includeInbox = pollInbox)",
                "}.onFailure { Log.w(TAG, \"Fallback fetch failed for account \${credentials.id}\", it) }",
            ),
            doWorkStatements().dropWhile { !it.startsWith("val connected =") }.take(17),
        )
    }

    /**
     * The other fetch, on the UnifiedPush path, pinned whole for the same reason: the same mutation
     * lands there (an added `includeInbox = false` would silence that account's inbox for good), and
     * no window above reaches it. Its `continue` is deliberately left unannounced — that account WAS
     * fetched, in full; it is not a decision to do nothing.
     */
    @Test fun `the UnifiedPush path still fetches the account whole`() {
        assertEquals(
            listOf(
                "if (up.isActive(credentials.id)) {",
                "runCatching { FetchAndNotify.run(applicationContext, credentials) }",
                ".onFailure { Log.w(TAG, \"Safety poll failed for account \${credentials.id}\", it) }",
                "continue",
                "}",
            ),
            doWorkStatements().dropWhile { it != "if (up.isActive(credentials.id)) {" }.take(5),
        )
    }

    /**
     * The volet adds lines and nothing else. Every exit of `doWork` is a plain success — the early
     * return added for the empty cycle is the SAME outcome as the one at the bottom, so WorkManager's
     * retry and backoff behaviour is untouched — and both fetch call sites are still there, so no
     * account gained or lost a poll. Counted over the statements of `doWork` only, never over the
     * file: the KDoc below it names `FetchAndNotify.run` in prose, and a rule a comment can move is
     * not a rule.
     */
    @Test fun `every exit of the worker is still a plain success, with both fetch calls intact`() {
        val statements = doWorkStatements()
        assertEquals(
            "every return of doWork must be a plain success: ${statements.filter { it.startsWith("return ") }}",
            listOf("return Result.success()", "return Result.success()"),
            statements.filter { it.startsWith("return ") },
        )
        assertEquals(
            "expected exactly two FetchAndNotify.run call sites in doWork: $statements",
            2,
            statements.count { it.contains("FetchAndNotify.run") },
        )
    }

    /**
     * The statements of `MailFetchWorker.doWork`, in order, trimmed, with comments and blank lines
     * dropped. Ends at the closing brace written at member indentation, which is how every function
     * in that file closes. Fails loudly if the signature is gone, so a rename cannot turn the rules
     * above into no-ops.
     */
    private fun doWorkStatements(): List<String> {
        val lines = workerSource().lines()
        val start = lines.indexOfFirst { it.trim() == DO_WORK_SIGNATURE }
        assertTrue("signature not found in MailFetchWorker: $DO_WORK_SIGNATURE", start >= 0)
        val body = lines.drop(start + 1).takeWhile { it != "    }" }
        assertTrue("doWork is not closed at member indentation", body.size < lines.size - start - 1)
        return body.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") }
    }

    private fun workerOccurrencesOf(token: String): Int = workerSource().split(token).size - 1

    private fun workerSource(): String =
        File(repoRoot, "app/src/main/kotlin/app/sterna/push/MailFetchWorker.kt").readText()

    private companion object {
        const val DO_WORK_SIGNATURE = "override suspend fun doWork(): Result {"

        val repoRoot: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
                ?: error("cannot locate the checkout from ${File("").absolutePath}")
        }
    }
}

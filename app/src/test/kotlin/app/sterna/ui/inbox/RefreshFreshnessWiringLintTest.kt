package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxViewModel.kt` as text and proves nothing about
 * what the app does. [RefreshFreshnessTest] is the real test of #178's guard — read it first: it
 * EXECUTES the decision on literal instants. This file holds the other half, which no JVM test can
 * reach: `InboxViewModel` is an `AndroidViewModel` and is not instantiable here.
 *
 * What it pins is WHICH CALLERS sit behind the guard, because that list is the whole risk of this
 * change. Skipping a refresh is only ever safe for a refresh nobody is waiting on:
 *  - the guard on `onReconnected()` is the worst of them, and it is the first rule below;
 *  - the guard on the pull gesture or a Retry button would answer a deliberate gesture with
 *    nothing at all (`refreshRequested()`'s body is pinned whole by
 *    `RefreshIndicatorWiringLintTest`, one door, no branch);
 *  - the guard on `forceRefresh()` would silently keep the locally-evicted rows an Undo is trying
 *    to bring back;
 *  - a guard on any post-action reconcile (a move, a delete, a mark-read) would leave the list
 *    showing the state the server no longer holds.
 *
 * ⛔ Every rule compares a WHOLE unit — a whole declaration body — or COUNTS with a regex. Never a
 * `contains` on a fragment: a fragment is blind to any mutation that LENGTHENS what it matches.
 * The regex `\brefresh\(\)` is what tells the two doors apart, since "refreshUnlessFresh()" holds
 * no "refresh()" substring at all.
 */
class RefreshFreshnessWiringLintTest {

    // -- the callers that must NEVER be guarded ---------------------------------------------------

    @Test
    fun `the reconnect walk calls refresh bare, never the guarded door`() {
        val body = declarationBody("private fun onReconnected()")
        assertEquals(
            "onReconnected() must call refresh() itself, unguarded. It is the ONLY thing that ever " +
                "corrects a reachability of FAILED: it calls refresh() up to MAX_TRIES times and " +
                "reads refreshJob straight afterwards. Behind the freshness guard the call returns " +
                "without starting anything, so `mine = refreshJob` captures the PREVIOUS job, " +
                "mine?.join() returns at once, `refreshJob !== mine` is false, the error is null and " +
                "the walk returns at its first attempt — the offline banner then stays up until the " +
                "reader pulls to refresh, which is defect #65 rearmed by #178's fix. Body was:\n$body",
            "1 refresh(), 0 refreshUnlessFresh()",
            doorCount(body),
        )
    }

    @Test
    fun `the forced re-query is not guarded either`() {
        val body = declarationBody("private fun forceRefresh()")
        assertEquals(
            "forceRefresh() drops the sync cursors and re-queries from scratch so messages evicted " +
                "locally (an Undone delete) come back. Behind the freshness guard the cursors are " +
                "dropped and nothing re-queries: the rows the Undo promised stay gone from the list " +
                "until something else refreshes. Compared whole, because a guard added here reads as " +
                "one extra line. Body was:\n$body",
            "private fun forceRefresh() { repo.resetSyncState() refresh() }",
            body,
        )
    }

    // -- the six callers that must be guarded -----------------------------------------------------

    @Test
    fun `the four view switches go through the guard`() {
        assertEquals(
            "selectUnified() must reconcile through the guard: switching to the unified inbox and " +
                "straight back is #178's own gesture. Compared whole. Body was:",
            "fun selectUnified() { if (selection.value is Sel.Unified) return collapseThreads() " +
                "selection.value = Sel.Unified unifiedInboxScopes.value = store.allInboxScopes() " +
                "meta.value = Meta(UNIFIED_LABEL, UNIFIED_LABEL, store.totalUnreadCount()) " +
                "refreshUnlessFresh() }",
            declarationBody("fun selectUnified()"),
        )
        assertEquals(
            "selectUnread() must reconcile through the guard. Compared whole. Body was:",
            "fun selectUnread() { if (selection.value is Sel.Unread) return collapseThreads() " +
                "selection.value = Sel.Unread " +
                "meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount()) " +
                "refreshUnlessFresh() }",
            declarationBody("fun selectUnread()"),
        )
        assertEquals(
            "select(mailbox) must reconcile through the guard: tapping a folder, going back and " +
                "tapping it again is the gesture that fired a full reconcile every time. Compared " +
                "whole. Body was:",
            "fun select(mailbox: Mailbox) { if (selection.value == Sel.Folder(mailbox.id)) return " +
                "collapseThreads() selection.value = Sel.Folder(mailbox.id) " +
                "meta.value = Meta(store.accountLabel(), mailbox.name, mailbox.unreadEmails) " +
                "refreshUnlessFresh() }",
            declarationBody("fun select(mailbox: Mailbox)"),
        )
        assertEquals(
            "showInbox() must reconcile through the guard: Back out of a folder lands here, and it " +
                "is the second half of every folder tap. Compared whole. Body was:",
            "fun showInbox() { val inboxId = store.inboxMailboxId() " +
                "if (selection.value == Sel.Folder(inboxId)) return collapseThreads() " +
                "selection.value = Sel.Folder(inboxId) " +
                "meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount()) " +
                "refreshUnlessFresh() }",
            declarationBody("fun showInbox()"),
        )
    }

    @Test
    fun `the cold start reconciles through the guard, and only through it`() {
        val body = declarationBody("init")
        assertEquals(
            "The init block is the cold start #178 is about — the reporter's phone kills the app " +
                "and she reopens it seconds later — so its reconcile goes through the guard, and a " +
                "bare refresh() left beside it would fire anyway and make the whole change a no-op " +
                "for her case. Counted by regex, since refreshUnlessFresh() contains no refresh() " +
                "substring. Body was:\n$body",
            "0 refresh(), 1 refreshUnlessFresh()",
            doorCount(body),
        )
    }

    @Test
    fun `an account switch reconciles through the guard, and only through it`() {
        val body = declarationBody("fun onAccountChanged()")
        assertEquals(
            "Switching to an account whose inbox was read moments ago must not re-fetch it. The " +
                "register is keyed on (account, folder), so the account being switched TO is the " +
                "one judged. Body was:\n$body",
            "0 refresh(), 1 refreshUnlessFresh()",
            doorCount(body),
        )
    }

    @Test
    fun `the guard has exactly six doorsteps, and one door`() {
        val text = codeText(INBOX_VIEW_MODEL)
        val hits = Regex("""\brefreshUnlessFresh\b""").findAll(text).toList()
        assertEquals(
            "refreshUnlessFresh must appear exactly 7 times in InboxViewModel.kt: its own " +
                "declaration, plus the SIX reconciles that accompany a change of view — init, " +
                "onAccountChanged, selectUnified, selectUnread, select(mailbox), showInbox. Every " +
                "other refresh() in this file answers a gesture (pull, Retry) or reconciles the " +
                "list after a write (move, delete, mark read, Undo), and skipping one of those " +
                "leaves the screen showing a state the server no longer holds. A seventh doorstep " +
                "is one copy-paste away and nothing else in the suite would see it. Found " +
                "${hits.size}.",
            7, hits.size,
        )
    }

    // -- the door itself --------------------------------------------------------------------------

    @Test
    fun `the door reads the stored register, the current scopes and the clock`() {
        val body = declarationBody("private fun refreshUnlessFresh()")
        assertEquals(
            "The guard is one door and it decides nothing itself: it hands isFresh the stored " +
                "register, the scopes of the view on screen — currentScopes(), the ONE place that " +
                "resolves (account, folder) pairs for the unified and unread views too (#121) — and " +
                "the clock, then returns. Scopes recomputed here by hand would be a second answer " +
                "to a question that already has one; a guard that returns when isFresh is FALSE is " +
                "one word and freezes the list for good.\n" +
                "Clearing the stale error belongs to the DOOR, at its head and unconditionally: a " +
                "change of view never inherits the failure of the view before it. Every one of the " +
                "six callers used to reach refresh(), whose first line resets status whole; this " +
                "door returns above that line when the view is fresh, so without the clear one " +
                "account's failed reconcile keeps its red notice over another account's correct, " +
                "up-to-date rows for the 30 s of the window — and over a folder with nothing " +
                "cached, the whole error scene with its Retry button stands in for the " +
                "empty-folder one. Compared whole. Body was:\n$body",
            "private fun refreshUnlessFresh() { clearRefreshError() " +
                "if (isFresh(decodeFreshness(store.refreshFreshness()), currentScopes(), clock())) return " +
                "refresh() }",
            body,
        )
    }

    @Test
    fun `clearing the stale error drops the error and nothing else`() {
        val body = declarationBody("private fun clearRefreshError()")
        assertEquals(
            "clearRefreshError() is now on the path of all SIX view-switch doors, not just " +
                "onReconnected(), so its body carries the fix and is pinned here. Two guards, both " +
                "load-bearing: it copies `error = null` and touches NOTHING else — a copy that also " +
                "reset `refreshing` would relight #63, where the tern and the centred ring are " +
                "drawn together, since `refreshing` is the truth the empty-centre read consults; " +
                "and the condition is `error != null`, so inverting it means the error is never " +
                "dropped at all, neither at a view switch nor on reconnect, and the red notice " +
                "outlives the failure that raised it. Compared whole. Body was:\n$body",
            "private fun clearRefreshError() { " +
                "if (status.value.error != null) status.value = status.value.copy(error = null) }",
            body,
        )
    }

    @Test
    fun `the scopes are read at the START of the work, not at its end`() {
        val body = declarationBody("fun refresh()")
        val opening = "refreshJob = viewModelScope.launch { val reconciled = currentScopes() try {"
        val at = body.indexOf("refreshJob = viewModelScope.launch")
        check(at >= 0) { "refresh() no longer launches its work into refreshJob:\n$body" }
        assertEquals(
            "The scopes stamped as reconciled are read when the work STARTS, into a local the " +
                "Delivered arm uses. The selection can move while a refresh is in flight (a folder " +
                "tap, a notification, an account switch), and reading currentScopes() again in the " +
                "arm would stamp a folder this refresh never read — i.e. mark a stale list fresh " +
                "and hold it there for 30 s, which is the one way this feature can show wrong mail. " +
                "Compared as the whole opening of the launch. Was:\n" +
                body.substring(at, minOf(at + opening.length, body.length)),
            opening,
            body.substring(at, minOf(at + opening.length, body.length)),
        )
    }

    @Test
    fun `the register is written from the register, through the pure functions`() {
        val body = declarationBody("private fun rememberReconciled(")
        assertEquals(
            "The write is decode -> recordFresh -> encode, in that order, on the CURRENT stored " +
                "value: recordFresh is what prunes the expired entries, so a write that skips it " +
                "lets the register grow for ever, and a write built from an empty list instead of " +
                "the stored one forgets every other folder on every refresh. Compared whole. Body " +
                "was:\n$body",
            "private fun rememberReconciled(scopes: List<Pair<String, String>>) { " +
                "val register = recordFresh(decodeFreshness(store.refreshFreshness()), scopes, clock()) " +
                "store.setRefreshFreshness(encodeFreshness(register)) }",
            body,
        )
    }

    @Test
    fun `only a successful refresh is ever recorded`() {
        val text = codeText(INBOX_VIEW_MODEL)
        val hits = Regex("""\brememberReconciled\b""").findAll(text).toList()
        assertEquals(
            "rememberReconciled must appear exactly twice: its declaration, and the ONE call in " +
                "refresh()'s Delivered arm (BoundedRefreshWiringLintTest pins that arm word for " +
                "word). Called from the Failed or the AbandonedAtBudget arm as well, a refresh that " +
                "brought nothing back would mark the folder fresh and hold a 30 s old list on " +
                "screen while the reader watches it fail to update. Found ${hits.size}.",
            2, hits.size,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** How many of each door [body] calls, as one comparable string. */
    private fun doorCount(body: String): String {
        val bare = Regex("""\brefresh\(\)""").findAll(body).count()
        val guarded = Regex("""\brefreshUnlessFresh\(\)""").findAll(body).count()
        return "$bare refresh(), $guarded refreshUnlessFresh()"
    }

    /**
     * The body of the declaration whose header is [header], comments removed and whitespace
     * collapsed: from the header up to the brace that balances its first `{`. Fails loudly when
     * the header is not found exactly once, so a rename reddens these rules rather than satisfying
     * them against an empty string.
     */
    private fun declarationBody(header: String): String {
        val text = codeText(INBOX_VIEW_MODEL)
        // Word-boundary on BOTH sides when the header ends on a word character, so "init" does not
        // match inside "initialValue"; only on the left when it ends on '(' or ')'.
        val tail = if (header.last().isLetterOrDigit() || header.last() == '_') "(?![A-Za-z0-9_])" else ""
        val hits = Regex("""(?<![A-Za-z0-9_])${Regex.escape(header)}$tail""").findAll(text).toList()
        check(hits.size == 1) {
            "InboxViewModel.kt declares '$header' ${hits.size} times — expected exactly one. " +
                "Renamed, or duplicated?"
        }
        return balancedFrom(text, hits.single().range.first)
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    /**
     * [file] as ONE line of code: every comment taken out, every run of whitespace collapsed to a
     * single space — so a call the formatter spreads over eight lines reads the same as one written
     * on a line. Same scanner as [BoundedRefreshWiringLintTest]'s, deliberately: a `//` inside a
     * string literal is not a comment, and a continuation line written operator-first does not
     * start with `*`.
     */
    private fun codeText(file: File): String {
        val code = StringBuilder()
        var inBlockComment = false
        for (raw in file.readLines()) {
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            code.append('\n')
        }
        return code.toString().replace(Regex("""\s+"""), " ").trim()
    }

    companion object {
        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}

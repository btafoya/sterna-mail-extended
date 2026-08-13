package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST — and here that is not a shortcut, it is the whole reachable
 * surface. `FetchAndNotify.run` takes an Android `Context`, resolves the application container out
 * of it and calls a `MailRepository` that needs Room and a live session: no JVM test in this module
 * can enter it. What the type system already proves is proved next door — `AccountGoneException`
 * really is a `CancellationException` ([app.sterna.core.data.mail.SignedOutAccountStopsWritingTest]
 * runs that as a type check) — and what is left, "which arm of `run` catches it, and in which
 * order", is text. It is read as WHOLE LINES, arguments included.
 *
 * What the arm is for. Signing an account out cancels nothing: `AccountsViewModel.signOut` never
 * touches WorkManager, and `PushService` bumps a generation instead of cancelling the pass in
 * flight. So the guard inside `MailRepository.refreshAccountFolders` is the only thing that stops a
 * background pass from writing under the removed id — and it stops it by THROWING, through the four
 * callers of [FetchAndNotify.run], none of which knows the difference:
 *
 *  - `PushFetchWorker` → `Log.w` with a stack, then `Result.retry()` (up to two extra wake-ups);
 *  - `MailFetchWorker` → the same `Log.w`;
 *  - `PushService` → `Log.e`.
 *
 * Every sign-out would therefore print an error with a stack for a gesture that SUCCEEDED, which is
 * precisely what "make the push log say what happened, not what was hoped" (#130) was for. Caught
 * here, in the single funnel, it is one `Log.i` and no retry.
 *
 * ⛔ And this file is where the un-archive path is proved, not next door: `SignedOutAccountWiringTest`
 * reads `MailRepository.kt` ALONE, so it stays green while the guards it pins are thrown into a
 * `runCatching` living in THIS file and go nowhere. Both calls of `unarchiveThreadsOnReply` sat in
 * exactly such a `runCatching { … }.getOrDefault(emptyList())`: the guard would skip the re-file and
 * the pass would carry on to `NewMailNotifier.seed`/`notifyDiff`, writing a baseline under the
 * removed id in `:app`'s SharedPreferences — where no orphan sweep exists at all. The ghost would
 * have been moved, not removed. So the propagation is pinned here, at both sites:
 *
 *  - in [FetchAndNotify.run], `rethrowIfCancelled()` between the `runCatching` and its default, so
 *    the refusal reaches the arm above (and an ordinary move failure still costs no notification);
 *  - in [FetchAndNotify.onInboxRefreshed], the same, plus an arm of its own — for what it STOPS,
 *    not for the caller. `InboxViewModel` calls this hook inside a `runCatching` at both of its
 *    call sites (`:943` and `:957`), so a refusal let out would be absorbed there and change
 *    nothing for it. What the arm buys is that `notifyDiff`/`seed` do not run, and that a sign-out
 *    leaves one line saying what happened instead of a silence — the same thing #130 put in place
 *    on every other path.
 */
class SignedOutAccountLeavesNoPushErrorTest {

    @Test fun `the pass drops a signed-out account instead of reporting it as a failure`() {
        // Pinned as a block: the arm has to SWALLOW. A `throw gone` or a `Result`-shaped rethrow
        // slipped between the two lines puts the error entry and the two retries straight back.
        //
        // ⚠ And WHAT the line claims is not decided here. The guard throws when the pass's id is
        // not in `AccountStore.accounts()`, which answers an UNREADABLE blob with an empty list —
        // so this arm also fires when nobody signed anything out, and it used to assert a sign-out
        // in that case too. The choice is [app.sterna.core.data.mail.accountGoneCause], EXECUTED by
        // `AccountGoneCauseTest`; what is pinned here is that this call site asks it, and with
        // WHICH ARGUMENTS — a fresh `store.accountsUnreadable()`, not a constant, not a flag read
        // before the pass started. A hard-coded `false` compiles and puts the lie straight back.
        // `passKind` is this site's own word: the shared sentence decides WHICH claim is true, not
        // what the caller was running.
        assertEquals(
            "the AccountGoneException arm of FetchAndNotify.run is no longer, line for line, what " +
                "this was written against. Either it no longer asks accountGoneCause what " +
                "happened (and AccountGoneCauseTest then proves nothing about the app), or it no " +
                "longer reads accountsUnreadable() afresh, or it is back to claiming a sign-out " +
                "for account data it merely failed to read.",
            listOf(
                "} catch (gone: AccountGoneException) {",
                "android.util.Log.i(",
                "\"FetchAndNotify\",",
                "\"background pass: \${accountGoneCause(credentials.id, store.accountsUnreadable(), " +
                    "passKind = \"pass\")}, dropped\",",
                "gone,",
                ")",
                "}",
            ),
            block("run", ARM, 7),
        )
    }

    @Test fun `nothing catches ahead of it — AccountGoneException IS a CancellationException`() {
        // ⛔ Not cosmetic. The refusal is a cancellation by design, so ANY arm above this one that
        // names CancellationException (or Throwable) takes it first and the dedicated arm becomes
        // unreachable — silently, with every test in the repo still green.
        // ⚠ Matched on the KEYWORD, not on the closing brace in front of it. `} catch (` is a
        // formatting convention, not a rule of the language: an arm written on its own line
        // (`}` then `catch (c: CancellationException) {`) is the same arm, in the same place, and a
        // filter anchored on the brace does not count it — so it could be slipped ABOVE this one,
        // leaving every test here green while the dedicated arm is dead code.
        //
        // ⛔ This filter is what carries the ordering constraint. There is deliberately no
        // `catch (c: CancellationException) { throw c }` under the arm below (nothing catches
        // Throwable in this function, so it would be a no-op line), which means the order is
        // stated HERE or nowhere.
        //
        // ⚠ Asked PER FUNCTION since the second arm landed. `ARM` is one constant and the two arms
        // are spelled identically, so a whole-file `catches.first()` would state the order for
        // `run` twice and for `onInboxRefreshed` never — and an arm slipped above the second one
        // would be invisible while its own test still found its block further down.
        listOf("run", "onInboxRefreshed").forEach { function ->
            val catches = lines(function).filter { CATCH.containsMatchIn(it) }
            assertTrue(
                "FetchAndNotify.$function no longer carries the AccountGoneException arm at all; " +
                    "its catch arms are:\n" + catches.joinToString("\n"),
                ARM in catches,
            )
            assertEquals(
                "another catch arm now sits ABOVE the AccountGoneException one in " +
                    "FetchAndNotify.$function. AccountGoneException extends CancellationException, " +
                    "so whatever that arm names swallows the sign-out first and the dedicated arm " +
                    "never runs.",
                ARM, catches.first(),
            )
        }
    }

    @Test fun `the guarded call really sits inside the try the arm closes`() {
        // An arm that encloses nothing is worse than no arm: the log reads "handled" and the
        // exception still climbs out of the caller that actually made the network call.
        val body = lines("run")
        val opened = body.indexOf("try {")
        val call = body.indexOf("val refreshes = container.mailRepository.refreshAccountFolders(")
        val arm = body.indexOf(ARM)
        assertTrue(
            "FetchAndNotify.run no longer opens a try, or no longer calls refreshAccountFolders " +
                "the way this was written against:\n" + body.joinToString("\n"),
            opened >= 0 && call >= 0 && arm >= 0,
        )
        assertTrue("refreshAccountFolders is now called outside the try", opened < call)
        assertTrue("the arm no longer closes a try that is open at the call", call < arm)
        // The un-archive is the SECOND thing in that try that can refuse (its own two guards, added
        // for the path #121 never walked), and it is deeper in: inside the per-folder forEach.
        val unarchive = body.indexOf(UNARCHIVE)
        assertTrue(
            "FetchAndNotify.run no longer calls unarchiveThreadsOnReply the way this was written " +
                "against:\n" + body.joinToString("\n"),
            unarchive >= 0,
        )
        assertTrue("unarchiveThreadsOnReply is now called outside the try", opened < unarchive)
        assertTrue("the arm no longer closes a try that is open at the un-archive", unarchive < arm)
    }

    // -- the un-archive: a refusal that has to LEAVE the runCatching wrapping it ---------------------

    @Test fun `the background pass lets the un-archive's refusal out of its best-effort wrapper`() {
        // ⛔ The `runCatching` is not the defect and must stay: a move the server refuses must not
        // cost the notification. What it must not do is eat the sign-out, because the guards in
        // `unarchiveThreadsOnReply` throw a CancellationException and `runCatching` catches those
        // like anything else. Swallowed here, the re-file is skipped and the pass walks on into
        // `NewMailNotifier.seed`/`notifyDiff`, which writes a baseline under the removed id — into
        // `:app`'s SharedPreferences, which the sign-out's orphan sweep does not inventory.
        //
        // Pinned as a block: `.rethrowIfCancelled()` deleted, or moved after `.getOrDefault(...)`
        // where it can never see the exception, leaves the two guards next door doing nothing at
        // all — with SignedOutAccountWiringTest still green, since it only reads MailRepository.kt.
        assertEquals(
            "the un-archive call of FetchAndNotify.run is no longer, line for line, what this was " +
                "written against. Back to a bare .getOrDefault(emptyList()), the sign-out refusal " +
                "is swallowed with the ordinary move failures and the pass writes a notifier " +
                "baseline under an account that no longer exists.",
            listOf(
                UNARCHIVE,
                ".rethrowIfCancelled()",
                ".getOrDefault(emptyList())",
            ),
            block("run", UNARCHIVE, 3),
        )
    }

    @Test fun `the foreground inbox hook drops the signed-out account on the spot`() {
        // ⛔ The same wrapper, in the hook the UI calls after it refreshed an inbox itself. Not for
        // the caller's sake: `InboxViewModel` wraps both of its calls to this hook in a
        // `runCatching` (`:943`, `:957`), so a refusal let out of here would be absorbed there and
        // nothing above would notice. The arm is what makes the hook STOP — no seed, no notifyDiff,
        // no baseline under the removed id — and what makes a sign-out leave one line saying so
        // rather than a silence (#130).
        //
        // ⚠ The claim is [app.sterna.core.data.mail.accountGoneCause]'s, executed by
        // `AccountGoneCauseTest`, and what is pinned here is that this site ASKS it, with a FRESH
        // `accountsUnreadable()` read (a constant `false` compiles and turns an unreadable account
        // blob back into a "sign-out" in a reporter's logcat). `passKind` is this site's own word:
        // this is the FOREGROUND refresh of one inbox, not the background pass of `run`, and the
        // sentence must not flatten the two into each other.
        assertEquals(
            "the AccountGoneException arm of FetchAndNotify.onInboxRefreshed is no longer, line " +
                "for line, what this was written against. Either it stopped asking " +
                "accountGoneCause what happened, or it no longer reads accountsUnreadable() " +
                "afresh, or it borrowed the background pass's word for a foreground refresh.",
            listOf(
                ARM,
                "android.util.Log.i(",
                "\"FetchAndNotify\",",
                "\"foreground inbox: \${accountGoneCause(credentials.id, " +
                    "container.accountStore.accountsUnreadable(), passKind = \"refresh\")}, dropped\",",
                "gone,",
                ")",
                "}",
            ),
            block("onInboxRefreshed", ARM, 7),
        )
    }

    @Test fun `the foreground hook's arm encloses the un-archive AND everything it would feed`() {
        // What must stop is the REST of the hook: `notifyDiff` and `seed` both write the shared
        // notifier baseline under credentials.id, and an arm that only wrapped the call itself
        // would skip the re-file and then write that baseline anyway — the ghost moved from the
        // `emails` table into SharedPreferences, which no orphan sweep reads.
        // Same exception to the best-effort wrapper as in `run`, pinned again and not inherited:
        // the two calls are two lines in two functions, and dropping `.rethrowIfCancelled()` from
        // THIS one makes the arm below unreachable — the refusal is swallowed by the runCatching,
        // the hook carries on, and every positional rule in this test stays satisfied.
        assertEquals(
            "the un-archive call of FetchAndNotify.onInboxRefreshed is no longer, line for line, " +
                "what this was written against. Without .rethrowIfCancelled() the runCatching eats " +
                "the sign-out (AccountGoneException IS a CancellationException), the arm below " +
                "never runs, and the hook writes its baseline under the removed account anyway.",
            listOf(
                UNARCHIVE_HOOK,
                ".rethrowIfCancelled()",
                ".getOrDefault(emptyList())",
            ),
            block("onInboxRefreshed", UNARCHIVE_HOOK, 3),
        )
        val body = lines("onInboxRefreshed")
        val opened = body.indexOf("try {")
        val call = body.indexOf(UNARCHIVE_HOOK)
        val notify = body.indexOfFirst { it.startsWith("NewMailNotifier.notifyDiff(") }
        val seed = body.indexOfFirst { it.startsWith("NewMailNotifier.seed(context, credentials.id, inboxMailboxId, remembered)") }
        val arm = body.indexOf(ARM)
        assertTrue(
            "FetchAndNotify.onInboxRefreshed no longer opens a try, or no longer calls " +
                "unarchiveThreadsOnReply / notifyDiff / seed the way this was written against:\n" +
                body.joinToString("\n"),
            opened >= 0 && call >= 0 && notify >= 0 && seed >= 0 && arm >= 0,
        )
        assertTrue("unarchiveThreadsOnReply is now called outside the try", opened < call)
        assertTrue(
            "notifyDiff now runs outside the try: a refused re-file no longer stops the baseline " +
                "write that follows it, under the account that was just removed",
            call < notify && notify < arm,
        )
        assertTrue(
            "the silent seed now runs outside the try: same baseline, same removed id, same ghost",
            call < seed && seed < arm,
        )
    }

    @Test fun `nothing else is open around the guarded calls that could swallow the refusal`() {
        // ⭐ THE rule this file was missing, and a counter-expertise proved it by mutation: every
        // other rule here is about PRESENCE and ORDER, and both survive an enclosure. Wrap the
        // whole `val returned = if (…) { … } else { emptyList() }` expression of `run` in a second
        // `runCatching { … }.getOrDefault(emptyList())` and not one pinned line moves: the three
        // lines are intact, `opened < unarchive < arm` is intact, and `CATCH` does not match
        // `runCatching {`. Everything stays green — while the inner `rethrowIfCancelled()` hands
        // the cancellation to the OUTER runCatching, which eats it, the arm is never reached and
        // the pass walks on to `NewMailNotifier.notifyDiff`: the user gets a new-mail notification
        // for the account she has just deleted, and its baseline is written under the dead id.
        //
        // So this asks what `SignedOutAccountWiringTest.openBlocksAt` asks of MailRepository, on
        // the file that one cannot see. ⛔ Duplicated rather than shared: `:app` and `core:data` do
        // not share test source, and a helper reached across modules would be one more thing to
        // keep true in two places anyway.
        //
        // ⚠ The call's OWN `runCatching { … }` never appears: it opens and closes on that one line,
        // so it is not a block that is still open there. That is exactly the intent — that wrapper
        // is allowed (a refused move must not cost the notification), it is the one whose refusal
        // `rethrowIfCancelled()` re-throws. Anything ELSE still open is a place for it to die.
        listOf("run" to UNARCHIVE, "onInboxRefreshed" to UNARCHIVE_HOOK).forEach { (function, call) ->
            val body = lines(function)
            val at = body.indexOf(call)
            assertTrue(
                "FetchAndNotify.$function no longer contains the line:\n  $call\nits code is:\n" +
                    body.joinToString("\n"),
                at >= 0,
            )
            val open = openBlocksAt(body, at).filter { "try" in it || "runCatching" in it }
            assertEquals(
                "the un-archive call of FetchAndNotify.$function is enclosed in something other " +
                    "than the single `try {` its AccountGoneException arm closes:\n" +
                    open.joinToString("\n") +
                    "\nAccountGoneException IS a CancellationException, so any extra runCatching " +
                    "or try open here swallows the refusal that rethrowIfCancelled() just threw: " +
                    "the arm never runs, the pass carries on to notifyDiff/seed, and the user is " +
                    "notified of new mail for the account she just deleted. Both guards in " +
                    "MailRepository.unarchiveThreadsOnReply become decoration, with every other " +
                    "rule in this file still green.",
                listOf("try {"), open,
            )
        }
    }

    /** The lines that opened the blocks still open just before [index] — the call's enclosure.
     *  A twin of `SignedOutAccountWiringTest.openBlocksAt`; see the ⛔ note above for why it is
     *  copied and not shared. */
    private fun openBlocksAt(lines: List<String>, index: Int): List<String> {
        val stack = ArrayDeque<String>()
        lines.take(index).forEach { line ->
            val delta = line.count { it == '{' } - line.count { it == '}' }
            repeat(maxOf(0, -delta)) { stack.removeLastOrNull() }
            repeat(maxOf(0, delta)) { stack.addLast(line) }
        }
        return stack.toList()
    }

    @Test fun `the arm catches the shipped type, not a local look-alike`() {
        assertTrue(
            "FetchAndNotify must import app.sterna.core.data.mail.AccountGoneException — the type " +
                "MailRepository's guard actually throws.",
            "import app.sterna.core.data.mail.AccountGoneException" in lines(),
        )
        assertTrue(
            "FetchAndNotify must import app.sterna.core.data.rethrowIfCancelled — the shipped " +
                "helper that re-throws a cancellation out of a kept Result, not a local copy.",
            "import app.sterna.core.data.rethrowIfCancelled" in lines(),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The [count] lines of [function] starting at the one equal to [first], or a failure. */
    private fun block(function: String, first: String, count: Int): List<String> {
        val body = lines(function)
        val start = body.indexOf(first)
        assertTrue(
            "FetchAndNotify.$function no longer contains the line:\n  $first\nits code is:\n" +
                body.joinToString("\n"),
            start >= 0,
        )
        return body.subList(start, minOf(start + count, body.size))
    }

    /**
     * The code of ONE function of the file, from its declaration to the next declaration's.
     *
     * ⛔ Not a convenience: the file carries two arms spelled identically now, and every rule here
     * is "which statement follows which INSIDE one function". A whole-file read would let a line
     * belonging to `run` satisfy a rule written about `onInboxRefreshed`, and the other way round.
     */
    private fun lines(function: String): List<String> {
        val body = lines()
        val bounds = DECLARATIONS.map { declaration ->
            body.indexOfFirst { it.startsWith(declaration) }.also {
                assertTrue(
                    "FetchAndNotify no longer declares '$declaration'; this file slices the source " +
                        "between declarations and cannot tell the two arms apart without them:\n" +
                        body.joinToString("\n"),
                    it >= 0,
                )
            }
        }
        val here = DECLARATIONS.indexOfFirst { it.endsWith(" $function(") }
        assertTrue("no declaration is registered for FetchAndNotify.$function", here >= 0)
        return body.subList(bounds[here], bounds.getOrNull(here + 1) ?: body.size)
    }

    /** The file's code, trimmed, comments and blank lines cut — every rule here is about which
     *  statement follows which, and a comment between two of them changes nothing. */
    private fun lines(): List<String> = FETCH_AND_NOTIFY.readLines().map { it.trim() }.filter {
        it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*")
    }

    private companion object {
        const val ARM = "} catch (gone: AccountGoneException) {"
        const val PATH = "app/src/main/kotlin/app/sterna/push/FetchAndNotify.kt"
        /** The un-archive call of [FetchAndNotify.run] — bare, its `val returned = if (…)` is above. */
        const val UNARCHIVE =
            "runCatching { container.mailRepository.unarchiveThreadsOnReply(credentials, threads) }"

        /** The same call in [FetchAndNotify.onInboxRefreshed], where it opens its own statement. */
        const val UNARCHIVE_HOOK = "val returned = $UNARCHIVE"

        /** Every top-level declaration of the file, in source order — the slicing boundaries of
         *  [lines]. A function added between two of these has to be registered here, or its code
         *  is read as part of the one above it. */
        val DECLARATIONS = listOf(
            "suspend fun run(",
            "suspend fun onInboxRefreshed(",
            "internal fun seedsSilently(",
        )

        /** A catch arm, however it is laid out: `} catch (`, `}catch(`, or `catch (` on its own
         *  line after the previous arm's closing brace. */
        val CATCH = Regex("""^}?\s*catch\s*\(""")

        /** Repo root, walked up from the module's working directory. */
        val FETCH_AND_NOTIFY: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, PATH) }
                .firstOrNull { it.isFile }
                ?: error(
                    "cannot locate $PATH from ${File("").absolutePath} — this test reads the " +
                        "source as text and needs a working directory inside the checkout",
                )
        }
    }
}

package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. The decision and its consequence are EXECUTED by
 * [SignedOutAccountStopsWritingTest]; `MailRepository` needs Room, an Android `Context` and a live
 * session, so what is read here is only the plug — as WHOLE LINES.
 *
 * Whole lines and never a fragment, and the arguments and not just the name: the mutation that
 * matters on this path lengthens the line or swaps one id for another. `checkAccountStillConfigured
 * (accountStore.currentId(), …)` compiles, keeps the call where it is, and stops the walk of every
 * account that is not the current one instead of the one that was signed out.
 *
 * ⛔ Nine write paths are pinned, not two, and they were not all found at once — a counter-expertise
 * found three of them, a later one found the sixth, two more are a path #121 never walked at all,
 * and the ninth is the JMAP half of a function whose IMAP half had been guarded twice. Every path
 * that WRITES under a local account id has to ask, immediately before writing, because a network
 * round-trip always separates the answer from the write:
 *  - the JMAP full-query walk (page by page);
 *  - the IMAP folder walk (page by page);
 *  - the JMAP delta branch (its eviction, its upsert and its cursor);
 *  - the FTS header crawl, whose table the orphan sweep inventories too;
 *  - the IMAP branch of `refreshAccountFolders` — the background push/worker pass, which reads the
 *    watched folders whole and then writes them all in one `forEach`;
 *  - the JMAP branch of the same pass, whose folder-list write is the LAST statement of the
 *    function: the deepest guard it crosses is the one inside the last `syncMailbox`, and a ghost
 *    sweep, a cursor write and a notification read still separate that answer from this write;
 *  - `refresh`, which absorbs the refusal in a `runCatching` and would otherwise go on to write the
 *    folder list under the removed id;
 *  - `unarchiveThreadsOnReply`'s SERVER move — the only write of the nine that does not land in
 *    this phone's database. An `Email/set` un-archives the messages on the account itself: visible
 *    in the webmail and on every other client the user owns, with no Undo, and (its re-file being
 *    refused a moment later) not even a local trace;
 *  - `unarchiveThreadsOnReply`'s re-file, which lands AFTER that move: rows written there are
 *    recreated behind the sign-out's orphan sweep and show under a "deleted account" label until
 *    the process restarts.
 *
 * ⛔ And four entry checks: `syncMailbox`, `imapWriteThrough`, the same `refreshAccountFolders`
 * IMAP branch, and `unarchiveThreadsOnReply` — each SPARES a session, a LOGIN and a folder list
 * when the account is already gone at the moment it asks.
 *
 * ⚠ Spares, and nothing stronger. None of these thirteen closes the race: a check that passed
 * microseconds before the account left the store still writes, and still writes after the sweep
 * (the ⚠ paragraph of [checkAccountStillConfigured] states it, and the orphan sweep at process
 * start is what collects those). What is pinned here is the narrowing — from the minutes a deep
 * folder or a cold JMAP session takes, down to the width of one write.
 */
class SignedOutAccountWiringTest {

    private val guard = "checkAccountStillConfigured"

    /**
     * The function's body, trimmed, WITHOUT its comment and blank lines: every rule below is about
     * which statement follows which, and a comment line between two of them changes nothing. It is
     * also the only way a block pin can stay readable next to the prose these functions carry.
     */
    private fun lines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function).lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("//") }

    /** The [count] lines starting at the one equal to [first], or a failure naming the body. */
    private fun block(function: String, first: String, count: Int): List<String> {
        val body = lines(function)
        val start = body.indexOf(first)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $first\nits body is:\n" +
                body.joinToString("\n"),
            start >= 0,
        )
        return body.subList(start, minOf(start + count, body.size))
    }

    private fun indexOf(function: String, line: String): Int {
        val at = lines(function).indexOf(line)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $line\nits body is:\n" +
                lines(function).joinToString("\n"),
            at >= 0,
        )
        return at
    }

    // -- the JMAP full query: every page checked before it is written -------------------------------

    @Test fun `the JMAP page write asks first, with the walk's own account id`() {
        // Pinned as a block rather than as two present lines: the order is the whole property. The
        // guard AFTER the upsert would let each page land and only then refuse the next one, which
        // is a page of ghosts per sign-out instead of none.
        assertEquals(
            "the JMAP writePage of syncMailbox is no longer, line for line, what this was written " +
                "against. The guard must be the FIRST thing in it and must name localAccountId — " +
                "the id the rows are tagged with, not the current account's.",
            listOf(
                "writePage = { fresh ->",
                "$guard(localAccountId, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(fresh.map { it.toEntity(localAccountId, mailboxId) })",
                "},",
            ),
            block("syncMailbox", "writePage = { fresh ->", 4),
        )
    }

    @Test fun `syncMailbox refuses to start for an account that is gone, before it reads a cursor`() {
        // ⚠ The entry check refuses to START and covers nothing after itself — the branches below
        // each ask again. It is still worth its line: it spares a session, a folder list and a
        // walk for an account that is already gone when the sync begins.
        val body = lines("syncMailbox")
        val entry = body.indexOf("$guard(localAccountId, accountStore.accounts().map { it.id })")
        val key = body.indexOf("val key = syncKey(localAccountId, mailboxId)")
        val delta = body.indexOf("if (stored != null) {")
        assertTrue(
            "syncMailbox no longer checks the account list at its entry; its body is:\n" +
                body.joinToString("\n"),
            entry >= 0,
        )
        assertTrue("syncMailbox no longer resolves its cursor key / delta branch — renamed?", key >= 0 && delta >= 0)
        assertTrue("the entry check now runs after the cursor is read", entry < key)
        assertTrue("the entry check now runs inside or after the delta branch", entry < delta)
        // It must stay BELOW the blank-id refusal: a blank id is a caller's bug and has to be
        // reported as one, not as an account that was signed out (#121).
        val blank = body.indexOfFirst { it.startsWith("require(localAccountId.isNotBlank())") }
        assertTrue("the blank-id guard of #121 is gone from syncMailbox", blank >= 0)
        assertTrue("the sign-out check now shadows the blank-id guard", blank < entry)
    }

    // -- the JMAP delta branch: guarded at its WRITES, not only at its entry -------------------------

    @Test fun `the delta asks again once the server has answered, before it evicts or stores a cursor`() {
        // Two round-trips (emailQueryChanges, emailChanges) separate the entry check from here, and
        // everything below this line writes: an eviction, an upsert, and a cursor. The entry check
        // is not a proxy for any of them.
        assertEquals(
            "the delta branch of syncMailbox no longer asks whether the account still exists before " +
                "it starts writing.",
            listOf(
                "if (canApply) {",
                "$guard(localAccountId, accountStore.accounts().map { it.id })",
            ),
            block("syncMailbox", "if (canApply) {", 2),
        )
    }

    @Test fun `the delta asks once more between the fetch and the upsert`() {
        // The third round-trip of the branch sits inside this block; the upsert after it is the
        // reported symptom (rows appearing under a deleted account).
        assertEquals(
            "the delta's own page write is no longer preceded by the guard.",
            listOf(
                "val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)",
                "$guard(localAccountId, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(fetched.map { it.toEntity(localAccountId, mailboxId) })",
            ),
            block("syncMailbox", "val fetched = client.getEmailsByIds(session, accountId, toFetch, auth)", 3),
        )
    }

    // -- the IMAP full query: the same two checks ---------------------------------------------------

    @Test fun `the IMAP page write asks first too, with the credentials' own id`() {
        assertEquals(
            "the writePage of imapWriteThrough is no longer, line for line, what this was written " +
                "against. IMAP walks a folder in sequence-number pages for minutes on a deep " +
                "folder — it is the path the defect was reported on.",
            listOf(
                "writePage = { page ->",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(page)",
                "},",
            ),
            block("imapWriteThrough", "writePage = { page ->", 4),
        )
    }

    @Test fun `imapWriteThrough refuses to start for an account that is gone`() {
        val body = lines("imapWriteThrough")
        val entry = body.indexOf("$guard(credentials.id, accountStore.accounts().map { it.id })")
        val walk = body.indexOfFirst { it.startsWith("return fullQueryWriteThrough") }
        assertTrue("imapWriteThrough no longer checks the account list:\n" + body.joinToString("\n"), entry >= 0)
        assertTrue("imapWriteThrough no longer routes through the write-through", walk >= 0)
        assertTrue("the entry check now runs after the walk has started", entry < walk)
    }

    // -- the background watched-folder pass: read whole, then written whole -------------------------

    @Test fun `the watched-folder pass asks again between the network read and its grouped write`() {
        // ⭐ A pass that does NOT write page by page (its JMAP twin below is the other one):
        // `loadWatchedFolders` returns
        // when the whole network read is over (session, LOGIN, LIST, one FETCH per watched folder),
        // and only then does the `forEach` write up to `limit` messages PER folder. One check
        // immediately before the loop therefore covers every write it makes — and nothing else
        // does: this is the background push/worker path, `signOut` never touches WorkManager and
        // `PushService` only bumps its generation, so no cancellation reaches here.
        //
        // Pinned with its neighbours on both sides, because the guard line is verbatim the entry
        // check a few lines above it: what is asserted is WHERE this one is, between the pruning of
        // missing watches and the write, not merely that the file contains the call.
        assertEquals(
            "the IMAP branch of refreshAccountFolders no longer asks whether the account still " +
                "exists between the network read and the grouped write it feeds. Moved below the " +
                "forEach it is worth nothing: up to `limit` messages PER WATCHED FOLDER land under " +
                "the removed id, after the sign-out's orphan sweep has run.",
            listOf(
                "missing.forEach(onMissing)",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "loads.forEach { emailDao.upsertAll(it.messages) }",
            ),
            block("refreshAccountFolders", "missing.forEach(onMissing)", 3),
        )
    }

    @Test fun `the watched-folder pass refuses to start for an account that is gone`() {
        val body = lines("refreshAccountFolders")
        val branch = body.indexOf("if (credentials.protocol == MailProtocol.IMAP) {")
        val entry = body.indexOf("$guard(credentials.id, accountStore.accounts().map { it.id })")
        val read = body.indexOfFirst { it.startsWith("val (loads, missing) = imap.loadWatchedFolders(") }
        assertTrue(
            "refreshAccountFolders no longer checks the account list at the entry of its IMAP " +
                "branch; its body is:\n" + body.joinToString("\n"),
            entry >= 0,
        )
        assertTrue("refreshAccountFolders no longer branches on IMAP / no longer reads the watched folders", branch >= 0 && read >= 0)
        assertTrue("the entry check now runs before the protocol branch it belongs to", branch < entry)
        assertTrue(
            "the entry check now runs after the network read: signing out no longer spares the " +
                "session, the LOGIN, the LIST and the FETCHes of an account that is already gone",
            entry < read,
        )
    }

    @Test fun `the same pass asks again on its JMAP side, immediately before the folder-list write`() {
        // ⭐ The IMAP half of this function has been guarded twice since #121 and the JMAP half not
        // at all, and the two are NOT symmetrical by accident of reachability: `targets` is never
        // empty here, so `syncMailbox` does run and does ask — but the last answer it got is a
        // ghost sweep, a `putSyncState` and a `notifyRead` behind this line, and nothing asks in
        // between. What lands is `MailboxDao.replaceAll`, the table `MailboxDao` itself calls the
        // place an orphan is seen EARLIEST: a full drawer of folders under an account the user just
        // removed, written after the sign-out's orphan sweep, until the process restarts.
        //
        // Pinned as a block from the end of the refresh loop through the return, because WHERE is
        // the whole property: one line lower — below `replaceAll` — the folders are already
        // written and the refusal only skips the `return`.
        assertEquals(
            "the JMAP branch of refreshAccountFolders no longer asks whether the account still " +
                "exists immediately before mailboxDao.replaceAll. Moved below that write, or " +
                "removed, a sign-out landing after the last syncMailbox leaves the whole folder " +
                "list of the removed account in the drawer (#121).",
            listOf(
                "previewSources = emptyMap(),",
                ")",
                "}",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "mailboxDao.replaceAll(credentials.id, resolved.mailboxes.map { it.toEntity(credentials.id) })",
                "return refreshes",
            ),
            block("refreshAccountFolders", "previewSources = emptyMap(),", 6),
        )
    }

    // -- the FTS header crawl: the third paginated walk ---------------------------------------------

    @Test fun `the search-index crawl asks before every page it indexes`() {
        // ⛔ Up to HEADER_MAX/pageSize requests, and `EmailFtsDao.accountIds()` is one of the four
        // tables the orphan sweep inventories (`StorageRepository`): rows left here by a signed-out
        // account are #121 ghosts that keep answering local searches.
        assertEquals(
            "syncSearchIndex no longer asks whether the account still exists before indexing a page.",
            listOf(
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "emailFtsDao.upsert(page.emails.map { it.toFts(credentials.id) })",
            ),
            block("syncSearchIndex", "$guard(credentials.id, accountStore.accounts().map { it.id })", 2),
        )
    }

    // -- the reply's un-archive: a SERVER move, then a local re-file --------------------------------

    @Test fun `the un-archive refuses to start for an account that is gone, before it even connects`() {
        // ⛔ Not "just" an entry check that spares a session, which is why it is worth its own line
        // ahead of `connect`: everything below it leads to `client.move`, a write on the SERVER
        // made in the name of an account the user has just removed. And `connect` is one round-trip
        // at best, five or six on a cold JMAP context (fetchSession, jmapAccountIdFor,
        // reconcileLinkedAccounts, getIdentities, getMailboxes) — a check placed after it has
        // already paid for them. It does not make the move safe, though: what does that is the
        // check immediately before the move itself, pinned below.
        //
        // Pinned as a block, between the early return and the connect: below the connect it stops
        // sparing anything, and above the early return it would throw for IMAP accounts and empty
        // thread sets — paths that never write at all.
        assertEquals(
            "unarchiveThreadsOnReply no longer asks whether the account still exists before it " +
                "connects. Everything below that line ends in a SERVER move for an account the " +
                "user removed, and the session it opens is paid for before anyone asks.",
            listOf(
                "if (threadIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "val ctx = connect(credentials)",
            ),
            block(
                "unarchiveThreadsOnReply",
                "if (threadIds.isEmpty() || credentials.protocol == MailProtocol.IMAP) return emptyList()",
                3,
            ),
        )
    }

    @Test fun `the server move asks one last time, on the far side of the session`() {
        // ⭐ The widest window of the three, and the only write of the whole file that does not land
        // in this phone's database. `connect` is fetchSession + jmapAccountIdFor +
        // reconcileLinkedAccounts + getIdentities + getMailboxes, then a thread-members read: the
        // entry check is several round-trips behind by the time this line runs. A sign-out landing
        // in that window un-archives the messages ON THE SERVER for an account the user just
        // removed — in her webmail, on every other client she owns, with no Undo — and the re-file
        // guard below then refuses the local half, so the app keeps no trace of what it did.
        // "Irréversible d'abord": this one is not optional because the two others exist.
        //
        // Pinned from the recently-mutated marks so the block states the order that matters: the
        // marks and the #99 rescue stay AHEAD of the guard (they protect the rows during the very
        // move this guard authorises), and the guard stays OUTSIDE the runCatching that follows —
        // folded inside it, its refusal would be swallowed with the move's own failures and the
        // pass would carry on as if the move had merely failed.
        assertEquals(
            "unarchiveThreadsOnReply no longer asks whether the account still exists immediately " +
                "before client.move. The entry check is a session and several round-trips behind " +
                "this line: without this one, a sign-out mid-connect still un-archives the thread " +
                "on the SERVER, where nothing local can take it back.",
            listOf(
                "members.forEach { markRecentlyMutated(credentials.id, it.id) }",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "val result = runCatching {",
                "client.move(ctx.session, ctx.accountId, members.map { it.id }, inbox, ctx.auth)",
            ),
            block("unarchiveThreadsOnReply", "members.forEach { markRecentlyMutated(credentials.id, it.id) }", 4),
        )
    }

    @Test fun `the re-file asks again once the server has answered the move`() {
        // The entry check is no proxy for this one: `connect` plus the `Email/set` move are several
        // round-trips wide, and a sign-out landing inside that window leaves `upsertAll` writing
        // Inbox rows under the removed id — recreated AFTER the sign-out's orphan sweep has run,
        // and visible under a "deleted account" label until the next process start. That is the
        // reported symptom itself, on a path #121 never walked.
        //
        // Pinned from the move's own `getOrNull` so the guard is fixed on the far side of the
        // server call and immediately before the write: dropped below `emailDao.upsertAll(refiled)`
        // it protects nothing, and folded into the `runCatching` above it would be swallowed with
        // the move's own failures (which the whole-file rule below states separately).
        assertEquals(
            "the re-file of unarchiveThreadsOnReply is no longer, line for line, what this was " +
                "written against: the guard must sit between the server move and " +
                "emailDao.upsertAll(refiled). Below the upsert it is worth nothing — the ghost " +
                "rows are already written.",
            listOf(
                "}.getOrNull() ?: return emptyList()",
                "val moved = members.filter { it.id in result.done }",
                "if (moved.isEmpty()) return emptyList()",
                "moved.forEach { recentLocalMoves.mark(credentials.id, it.id) }",
                "val refiled = moved.map { it.copy(mailboxId = inbox) }",
                "$guard(credentials.id, accountStore.accounts().map { it.id })",
                "emailDao.upsertAll(refiled)",
            ),
            block("unarchiveThreadsOnReply", "}.getOrNull() ?: return emptyList()", 7),
        )
    }

    @Test fun `the un-archive still rescues from the destroy list and marks before the server call`() {
        // The two neighbours the new guards must not have displaced. #99 is exceptionless (whatever
        // moves leaves the destroy list), and the recently-mutated marks have to be set BEFORE the
        // move so a sync firing mid-move cannot evict the rows: pushed below the second guard they
        // would stop protecting anything during the very window that guard exists for.
        val body = lines("unarchiveThreadsOnReply")
        val rescue = body.indexOf("unlistFromTrashPurge(credentials.id, members.map { it.id })")
        val mark = body.indexOf("members.forEach { markRecentlyMutated(credentials.id, it.id) }")
        val move = body.indexOf("client.move(ctx.session, ctx.accountId, members.map { it.id }, inbox, ctx.auth)")
        assertTrue(
            "unarchiveThreadsOnReply no longer rescues from the trash purge / no longer marks its " +
                "members recently-mutated / no longer moves; its body is:\n" + body.joinToString("\n"),
            rescue >= 0 && mark >= 0 && move >= 0,
        )
        assertTrue("the #99 rescue now runs after the server move", rescue < move)
        assertTrue(
            "the recently-mutated marks now run after the server move: a concurrent sync can evict " +
                "the very rows this pass is moving back into the Inbox",
            mark < move,
        )
    }

    // -- the caller that absorbs the refusal ---------------------------------------------------------

    @Test fun `refresh rethrows the refusal instead of writing the folder list under a dead id`() {
        // ⛔ The hole a counter-expertise found: `runCatching { syncMailbox(...) }` absorbs the
        // guard's refusal like any other failure, and `refresh` then writes the WHOLE folder list
        // under credentials.id. `MailboxDao` calls that table the place an orphan appears earliest.
        val swallow = indexOf(
            "refresh",
            "val sync = runCatching { syncMailbox(session, accountId, auth, target.id, sizing, credentials.id) }",
        )
        val rethrow = indexOf("refresh", "if (syncError is AccountGoneException) throw syncError")
        val folders = indexOf(
            "refresh",
            "mailboxDao.replaceAll(credentials.id, mailboxes.map { it.toEntity(credentials.id) })",
        )
        assertTrue("the rethrow no longer follows the runCatching it exists to undo", swallow < rethrow)
        assertTrue(
            "the folder list is now written BEFORE the refusal is rethrown: a sign-out mid-refresh " +
                "leaves a full drawer of folders under an account that no longer exists (#121)",
            rethrow < folders,
        )
    }

    @Test fun `the unified pass skips the signed-out account instead of abandoning the whole pass`() {
        // WYSIWYG: letting the cancellation out leaves InboxViewModel with refreshing = true and
        // nothing to reset it (the list is only re-pointed when the CURRENT account was the one
        // signed out), so the spinner turns for ever over a sync that no longer exists. Pinned as
        // a whole block: a `throw gone` or a `failures += gone` slipped in here would put back the
        // spinner or raise the offline banner for a deliberate gesture.
        //
        // ⚠ And WHAT the line claims is delegated to [accountGoneCause], executed by
        // [AccountGoneCauseTest]: the guards read the store with `accounts()`, which answers an
        // unreadable blob with an empty list, so this arm also fires when nobody signed anything
        // out. The arguments are pinned, not just the call: a hard-coded `false` would put the lie
        // straight back, and `passKind` is this line's OWN word — this pass is a walk over the
        // accounts, it has read "mid-walk" since #121, and a shared sentence must not flatten it
        // into the push path's "mid-pass".
        assertEquals(
            "the AccountGoneException arm of refreshAllInboxes is no longer, line for line, what " +
                "this was written against — it must stay ABOVE the cancellation catch, it must " +
                "ask accountGoneCause what happened from a fresh accountsUnreadable() read, and " +
                "it must keep calling its own pass a WALK.",
            listOf(
                "} catch (gone: AccountGoneException) {",
                "android.util.Log.i(",
                "\"MailRepository\",",
                "\"unified refresh: \${accountGoneCause(credentials.id, accountStore.accountsUnreadable(), " +
                    "passKind = \"walk\")}, skipped\",",
                "gone,",
                ")",
                "} catch (c: CancellationException) {",
            ),
            block("refreshAllInboxes", "} catch (gone: AccountGoneException) {", 7),
        )
    }

    // -- the refusal itself -------------------------------------------------------------------------

    @Test fun `no guard anywhere in MailRepository is wrapped in something that swallows it`() {
        // ⚠ The previous version of this test asked for `checkAccountStillConfigured` and a
        // `runCatching` on the SAME LINE, which a multi-line `try { … } catch` walks straight past.
        // This one asks what actually matters: which blocks are still OPEN at the guard.
        val body = DaoQuerySource.mailSource("MailRepository").lines()
        val sites = body.indices.filter { body[it].trim().startsWith("$guard(") }
        assertEquals(
            "MailRepository no longer carries thirteen guard call sites — four entries and nine " +
                "writes. If a path was added or removed, say so here:\n" +
                sites.joinToString("\n") { body[it].trim() },
            13, sites.size,
        )
        sites.forEach { site ->
            val open = openBlocksAt(body, site).filter { "try" in it || "runCatching" in it }
            assertEquals(
                "the guard on line ${site + 1} of MailRepository is inside a block that catches:\n" +
                    open.joinToString("\n") +
                    "\nA swallowed refusal is worse than no guard: the write is skipped, so nothing " +
                    "looks wrong, but the walk carries on to its end and RECONCILES under the " +
                    "removed id.",
                emptyList<String>(), open,
            )
        }
    }

    @Test fun `MailRepository does not re-declare the guard under its own name`() {
        // ⛔ A lint that pins CALL SITES and not what they BIND TO is blind to shadowing, and this
        // file is where that costs the most: `AccountStillConfigured.kt` is in the SAME PACKAGE, so
        // a `private fun checkAccountStillConfigured(…)` declared anywhere in this class needs no
        // import to remove and no call site to touch — Kotlin resolves a member of the implicit
        // receiver ahead of a top-level declaration of the package, and all THIRTEEN sites silently
        // start calling the lenient copy. Every rule above stays green: the lines are identical,
        // their order is identical, the count is identical, no `try` is open, and
        // `SignedOutAccountStopsWritingTest` keeps executing the top-level function in its own
        // rig — the one production no longer calls.
        //
        // It is the same family of blindness as the hole this file was extended for: what is not
        // pinned is invisible. So the declaration itself is pinned, once, for all thirteen sites.
        val declaration = Regex("""\bfun\s+$guard\s*[(<]""")
        val found = DaoQuerySource.mailSource("MailRepository").lines()
            .map { it.trim() }
            .filter { declaration.containsMatchIn(it) }
        assertEquals(
            "MailRepository declares a function called '$guard' of its own:\n" +
                found.joinToString("\n") +
                "\nA member of this class shadows the top-level guard for every call site in the " +
                "file, without one of them changing by a character. The guard must stay the one " +
                "declared in AccountStillConfigured.kt — the one whose refusal is a cancellation " +
                "and whose blank-id `require` is executed next door.",
            emptyList<String>(), found,
        )
    }

    /** The lines that opened the blocks still open just before [index] — the guard's enclosure. */
    private fun openBlocksAt(lines: List<String>, index: Int): List<String> {
        val stack = ArrayDeque<String>()
        lines.take(index).forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEach
            val delta = line.count { it == '{' } - line.count { it == '}' }
            repeat(maxOf(0, -delta)) { stack.removeLastOrNull() }
            repeat(maxOf(0, delta)) { stack.addLast(line) }
        }
        return stack.toList()
    }

    @Test fun `the refusal is still a cancellation, in the shipped declaration`() {
        // Executed next door as a type check, but the declaration is what makes the four
        // downstream behaviours true at once: no reconcile, no IMAP replay, no error banner, and
        // an arm of its own in the unified pass.
        val source = DaoQuerySource.mailSource("AccountStillConfigured").lines().map { it.trim() }
        assertTrue(
            "AccountGoneException is no longer declared as a CancellationException:\n" +
                source.filter { "AccountGoneException" in it }.joinToString("\n"),
            "class AccountGoneException(val accountId: String) : kotlin.coroutines.cancellation.CancellationException(" in source,
        )
    }
}

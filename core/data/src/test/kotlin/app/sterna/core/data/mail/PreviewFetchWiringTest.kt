package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the preview fetch is PLUGGED IN — read from the shipped source, because `ImapMailService`
 * cannot be built on the JVM (its constructor takes an `OAuthTokenRefresher`, which takes an
 * `AccountStore`, which needs a `Context` and a DataStore).
 *
 * Everything this path DECIDES is executed elsewhere and is not restated here: which section is
 * read ([app.sterna.core.imap.firstTextPart], executed in `:core:imap`), what the command says
 * (`BodyPeekOnTheWireTest`, off a real socket), and what the octets become (`BodyPreviewTest`).
 * What is left is the wiring, and it is exactly the part a green suite would not notice going
 * wrong. Whole lines, compared — never a substring search.
 */
class PreviewFetchWiringTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("ImapMailService", function)

    /** Every CODE line of [function]'s body, in order, trimmed — comments and blank lines dropped.
     *  Unlike [codeLinesNaming] this keeps the lines nobody thought to name. */
    private fun codeLinesOf(function: String): List<String> =
        bodyOf(function).lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * The CODE lines of the `{ … }` block that opens at or after [anchor] in `ImapMailService.kt`,
     * braces included, trimmed, comments and blank lines dropped — for a block [bodyOf] cannot
     * reach: a lambda nested inside a function, and a function written with an expression body
     * (`DaoQuerySource` refuses those on purpose, so that a lookup never hands back a lambda's
     * braces where a function body was asked for).
     *
     * A plain brace count, and it is allowed to be plain because the two blocks pinned with it
     * carry no brace inside a string literal or a `${…}` template. ⛔ If one ever does, this stops
     * being correct — it will cut the block short and the assertion will fail loudly, which is the
     * right way round.
     */
    private fun blockLinesAfter(anchor: String): List<String> {
        val source = DaoQuerySource.mailSource("ImapMailService")
        val at = source.indexOf(anchor)
        require(at >= 0) { "ImapMailService no longer contains '$anchor' — did it get renamed?" }
        val open = source.indexOf('{', at)
        require(open >= 0) { "no block opens after '$anchor'" }
        var depth = 0
        var i = open
        var close = -1
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) { close = i; break }
            }
            i++
        }
        require(close >= 0) { "unbalanced braces after '$anchor'" }
        return source.substring(open, close + 1).lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
    }

    /** The code lines of [body] naming [needle] — comments dropped. Whole lines: the assertions
     *  compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * ⭐⛔ THE WHOLE BODY, LINE FOR LINE, IN ORDER — the only assertion here that can see a line
     * that was ADDED.
     *
     * Every other test in this file filters the body by a needle it names, so it judges the lines
     * it already expects and is blind to a new one. That is not theoretical: inserting
     * `session.setFlag(uid, "\\Seen", true)` inside the block below marks every announced message
     * READ on the server, on every device the account is open on, with no way back — and it carries
     * none of the needles, so it left this suite entirely green.
     *
     * This is a last resort and it is read as source text, because `ImapMailService` cannot be
     * built on the JVM. It is deliberately brittle: a line that changes here must be changed here
     * too, and that is the point — nothing may be added to this function silently.
     */
    @Test fun `the preview path is exactly these lines, and no others`() {
        val lines = codeLinesOf("fetchPreview")

        // The lookup itself fails loudly (mailFunctionBody errors when the function is gone), and
        // a body that is not a block would leave nothing to compare — say so rather than pass.
        assertEquals("the body must be a block, or this test is comparing nothing", "{", lines.firstOrNull())
        assertEquals(
            "no line may be added to the preview path without being written down here — an extra " +
                "command in this block runs against the user's mailbox on every notification",
            listOf(
                "{",
                "if (part == null) return null",
                "return try {",
                "onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->",
                "BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)",
                "}",
                "} catch (cancelled: CancellationException) {",
                "throw cancelled",
                "} catch (failed: Throwable) {",
                "android.util.Log.w(\"ImapPreview\", \"no preview for \$mailboxId:\$uid: \${failed.message}\")",
                "null",
                "}",
                "}",
            ),
            lines,
        )
    }

    /**
     * ⛔ The read is BOUNDED, and the bound is a parameter with no default.
     *
     * Without it `onMailbox` takes `budgetMs = 0`, which is `ImapBudget.NO_BUDGET`, which is
     * `soTimeout = 0` — block until the OS gives up. And it cannot be repaired from the outside:
     * the block is blocking with no suspension point and holds the account's mutex, so a
     * `withTimeout` around the call would cancel the coroutine while the thread stayed on the socket
     * read, with the mutex taken and every other IMAP operation on that account behind it.
     */
    @Test fun `the preview read carries a deadline, and cannot be called without one`() {
        assertEquals(
            "the budget must reach onMailbox — a call without it blocks for ever on a silent server",
            listOf("onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->"),
            codeLinesNaming(bodyOf("fetchPreview"), "onMailbox("),
        )
        // No default value: a caller cannot forget the deadline, because there is nothing to forget.
        val signature = DaoQuerySource.mailSource("ImapMailService")
            .substringAfter("suspend fun fetchPreview(")
            .substringBefore("): String?")
        assertEquals(
            "budgetMs must have no default: a defaulted deadline is a deadline a caller drops by " +
                "writing nothing at all",
            listOf("budgetMs: Int,"),
            signature.lines().map { it.trim() }.filter { it.startsWith("budgetMs") },
        )
    }

    /**
     * ⛔ Through `onMailbox`, which is the chokepoint that SELECTs the folder and refuses a UID
     * whose numbering has changed (Codeberg #99). `withSession` would fetch just as well and skip
     * the check — and a UID from a renumbered folder names ANOTHER message, whose opening line
     * would then be posted as this notification's.
     */
    @Test fun `the preview is read through the numbering chokepoint, never around it`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "the fetch must go through onMailbox: it is what SELECTs the folder and refuses a " +
                "renumbered one before a single UID goes out",
            listOf("onMailbox(credentials, mailboxId, budgetMs = budgetMs) { session, _ ->"),
            codeLinesNaming(body, "onMailbox("),
        )
        assertEquals(
            "and never around it — withSession fetches without checking the numbering",
            emptyList<String>(),
            codeLinesNaming(body, "withSession("),
        )
    }

    /**
     * ⛔ The bounded, peeked read, with the section the SERVER named — and no other fetch. Reaching
     * for `fetchSource` here would pull the whole message (megabytes, in a foreground service) to
     * show two lines of it, and `fetchSection` would pull the whole part.
     */
    @Test fun `the preview reads one bounded section, and nothing else`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "the section comes from what the envelope fetch learned, and the read is bounded by " +
                "IMAP_PREVIEW_FETCH_BYTES",
            listOf(
                "BodyPreview.fromPart(session.fetchSectionPartial(uid, part.section, IMAP_PREVIEW_FETCH_BYTES), part)",
            ),
            codeLinesNaming(body, "fetchSectionPartial("),
        )
        assertEquals(
            "no whole-message read on the notification path",
            emptyList<String>(),
            codeLinesNaming(body, "fetchSource(") + codeLinesNaming(body, "fetchSection(uid"),
        )
    }

    /**
     * ⛔ No text part means no round trip AT ALL. A message that is one PDF has no opening line;
     * connecting to learn that costs a LOGIN and a SELECT per notification, for an answer already
     * in hand.
     */
    @Test fun `a message with no text part is answered without touching the server`() {
        assertEquals(
            "the null part must short-circuit before the session is taken",
            listOf("if (part == null) return null"),
            codeLinesNaming(bodyOf("fetchPreview"), "part == null"),
        )
    }

    /**
     * A preview is decoration on a notification whose job is to say that mail arrived: every
     * failure costs the preview and nothing else. A cancellation is not a failure — it means the
     * caller is gone — and must go through.
     */
    @Test fun `a failed preview costs the preview, and a cancellation still propagates`() {
        val body = bodyOf("fetchPreview")

        assertEquals(
            "a cancellation must be re-thrown, never turned into 'no preview'",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            codeLinesNaming(body, "cancelled"),
        )
        assertEquals(
            "and every other failure yields no preview rather than taking the notification down",
            listOf("} catch (failed: Throwable) {"),
            codeLinesNaming(body, "catch (failed"),
        )
    }

    /**
     * ⛔ THE ONE THING THAT MUST NOT HAPPEN, and it has not changed: A HALF-TRUE LIST. Writing the
     * preview fetched for a NOTIFICATION into the cache would light up the message list for exactly
     * the messages that happened to be announced and leave every other row blank, which is worse
     * than a list that never promises an opening line — `BodyPreview`'s doc still refuses it word
     * for word, and `fetchPreview` above still returns its text and writes no row.
     *
     * ⭐ What changed is the way OUT of that refusal, which the refusal itself never priced (#187):
     * fill the column for THE WHOLE PAGE the folder walk is already holding, so a blank row means
     * the message has no readable text — not that nobody happened to notify it. So this test keeps
     * its object and states the new claim:
     *
     *  - the row takes the preview its caller read FOR IT. A literal on that line, of any value, is
     *    a second rule written where the row is built — `preview = null,` is #187 itself, and
     *    anything else is a decision hidden inside a mapper no JVM test can run;
     *  - what feeds it is the page, handed over WHOLE, at both read sites;
     *  - and the notification load ([loadWatchedFolders]) still READS none: its own budget reads
     *    six messages of a page of fifty, which is the half-true list exactly.
     *
     * ⛔ "Reads none" is the whole of what that last line says, and it used to be read as "writes
     * none", which was false: `@Upsert` replaces the whole row, so the `null` on that line WIPED
     * the opening lines the folder walk had cached for those very rows — the newest fifty of the
     * inbox and of each watched folder, on every push pass. The row is repaired one layer up, at
     * the write, and `PushPassKeepsCachedPreviewsTest` is where that is stated and executed.
     *
     * Read over the whole file rather than one function: the claim is about every line of this
     * layer that writes a preview, and only a file-wide read can state that.
     */
    @Test fun `the cached preview covers the whole page, never the notified messages alone`() {
        val source = DaoQuerySource.mailSource("ImapMailService")

        assertEquals(
            "EVERY line of this layer that assigns a preview, and no other. The two lookups take " +
                "the opening line read for THE ROW BEING BUILT, under that row's own cache id — a " +
                "lookup under any other id shows one correspondent's words under another " +
                "correspondent's name. The row then copies what it was handed: a literal there is " +
                "a rule written twice, and `preview = null,` is issue #187 itself",
            listOf(
                "val preview = previews[emailId(credentials.id, target.path, it.uid)]",
                "val preview = previews[emailId(credentials.id, mailboxId, it.uid)]",
                "preview = preview,",
            ),
            codeLinesNaming(source, "preview ="),
        )
        assertEquals(
            "and what fills it is the WHOLE page of the read, handed over untouched at both sites: " +
                "a subset here is the half-true list under another name. ⛔ accountId and mailboxId " +
                "BY NAME at both sites: they are two adjacent Strings, and swapping the two names " +
                "in the declaration mints 'imap:INBOX:acct-1:7' for a row cached as " +
                "'imap:acct-1:INBOX:7' — no lookup hits, no row shows an opening line, the page is " +
                "re-downloaded every walk, and not one assertion of this suite can see it, because " +
                "these lints read function BODIES and a declaration is not one",
            listOf(
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = target.path, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = mailboxId, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
            ),
            codeLinesNaming(source, "pagePreviews(session"),
        )
        assertEquals(
            "the notification load keeps READING no preview: it reads six messages of a page under " +
                "its own budget, and caching those six is the half-true list. ⛔ What it must not " +
                "do is DESTROY one, and this line cannot help it — the upsert replaces the whole " +
                "row — so the repair is at the write and is asserted just below",
            listOf("page.map { it.toEntity(credentials.id, folder.path, status.uidValidity, null) },"),
            codeLinesNaming(DaoQuerySource.mailFunctionBody("ImapMailService", "loadWatchedFolders"), "toEntity("),
        )
    }

    /**
     * ⭐⛔ THE WHOLE BODY OF THE LIST-PREVIEW READ, LINE FOR LINE, IN ORDER — the same last resort
     * as `the preview path is exactly these lines, and no others` above, and for the same reason:
     * every other assertion here filters by a needle it already expects, so none of them can see a
     * line that was ADDED. That is measured, not feared — inserting a `setFlag` into the
     * notification block left the whole suite green.
     *
     * This path is worse placed than that one: it runs inside the FOLDER WALK, on the session that
     * has the folder selected, over whole pages. A command added here does not touch one announced
     * message, it touches two hundred rows of a folder, on every refresh.
     */
    @Test fun `the list preview path is exactly these lines, and no others`() {
        val lines = codeLinesOf("pagePreviews")

        assertEquals("the body must be a block, or this test is comparing nothing", "{", lines.firstOrNull())
        assertEquals(
            "no line may be added to the list-preview path without being written down here — an " +
                "extra command in this block runs against a whole page of the user's mailbox on " +
                "every folder refresh",
            listOf(
                "{",
                "if (cachedPreviewsFor == null || page.isEmpty()) return emptyMap()",
                "val ids = page.map { emailId(accountId, mailboxId, it.uid) }",
                "var cached = emptyMap<String, String>()",
                "return try {",
                "cached = cachedPreviewsFor(ids, uidValidity)",
                "pageOpenings(",
                "cached,",
                "previewsForPage(accountId, mailboxId, page, ids.toSet() - cached.keys) { section, uids ->",
                "session.fetchSectionPartials(uids, section, IMAP_PREVIEW_FETCH_BYTES)",
                "},",
                ")",
                "} catch (cancelled: CancellationException) {",
                "throw cancelled",
                "} catch (failed: Throwable) {",
                "android.util.Log.w(\"ImapPreview\", \"no list previews for \$mailboxId: \${failed.message}\")",
                "cached",
                "}",
                "}",
            ),
            lines,
        )
    }

    /**
     * ⭐⛔ THE WALK'S PAGE BLOCK, LINE FOR LINE, IN ORDER — the third and last of the additive
     * guards, and the one that was missing.
     *
     * `pagePreviews` is pinned whole just above, and that is worth exactly nothing against a line
     * added to its CALLER. Measured, not feared: appending
     *
     *     .filterValues { it.length >= 40 }
     *
     * to the `pagePreviews(...)` call below brings #187 back for every message whose opening line
     * is short, and the whole suite stays green — `PreviewFetchWiringTest` filters on
     * `"pagePreviews(session"` and never sees the continuation line, `ImapWindowWiringTest` only
     * asserts presence, and no JVM test executes this block over a non-empty page.
     *
     * This block runs against a WHOLE PAGE of the user's folder on every refresh, and it is where
     * the row is built: what is written into `preview` is decided here and nowhere else. So it is
     * pinned the way `fetchPreview` and `pagePreviews` are, and for the same reason — nothing may
     * be added to it silently.
     */
    @Test fun `the folder walk's page block is exactly these lines, and no others`() {
        val lines = blockLinesAfter("session.walkFolder(status, limit, IMAP_FOLDER_PAGE) {")

        assertEquals(
            "no line may be added to the walk's page block without being written down here — this " +
                "block decides what every row of a refreshed folder carries in its preview column. " +
                "⛔ The numbering comes from the SELECT that enumerated this very page and is " +
                "normalised by the one shipped rule (UidValidity.stated): a cache read not bounded " +
                "by it answers with rows of the PREVIOUS numbering, whose UIDs collide with these " +
                "after a renumbering — the right sender, the right subject, somebody else's first " +
                "line, for good (#99 inside #187)",
            listOf(
                "{ page ->",
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = target.path, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
                "onPage(",
                "page.map {",
                "val preview = previews[emailId(credentials.id, target.path, it.uid)]",
                "it.toEntity(credentials.id, target.path, status.uidValidity, preview)",
                "},",
                ")",
                "}",
            ),
            lines,
        )
    }

    /**
     * ⭐⛔ THE PAGER'S READ, LINE FOR LINE, IN ORDER — the same guard, at the other read site.
     *
     * It cannot be reached through [bodyOf]: `fetchOlderPage` has an EXPRESSION body
     * (`= onMailbox(...) { … }`) and `DaoQuerySource` deliberately refuses those, so up to now this
     * function had no line-for-line pin at all and took every additive mutation the walk's block
     * took. It is scrolled through by the user rather than refreshed, so a line added here costs a
     * page per scroll step rather than per pull — the same defect, on a slower clock.
     */
    @Test fun `the older-page read is exactly these lines, and no others`() {
        val lines = blockLinesAfter("suspend fun fetchOlderPage(")

        assertEquals(
            "no line may be added to the pager's read without being written down here, and its " +
                "cache read is bounded by the numbering of the SELECT that enumerated the page",
            listOf(
                "{ session, status ->",
                "val page = session.fetchPage(status.exists, offset, limit)",
                "val previews = pagePreviews(session, accountId = credentials.id, mailboxId = mailboxId, page = page, uidValidity = UidValidity.stated(status.uidValidity), cachedPreviewsFor = cachedPreviewsFor)",
                "val messages = page.map {",
                "val preview = previews[emailId(credentials.id, mailboxId, it.uid)]",
                "it.toEntity(credentials.id, mailboxId, status.uidValidity, preview)",
                "}",
                "messages to status.exists",
                "}",
            ),
            lines,
        )
    }

    /**
     * ⛔ NOTHING ESCAPES THIS READ. It runs inside the folder walk, and the walk is what fills the
     * cache and advances what the notifier compares against: an exception coming out of here fails
     * the whole load, the next pass RE-ANNOUNCES the folder, and a re-announce moves mail on the
     * server (`unarchiveThreadsOnReply`). A missing opening line is worth none of that.
     *
     * A cancellation is not a failure — it means the caller is gone — and must still go through,
     * exactly as on the notification path.
     *
     * ⛔ And what "costs the previews" means since #187: a failed BODY READ costs the openings NOT
     * YET READ, and no more. The failure branch answers the CACHE, never `emptyMap()` — a socket
     * dropped halfway through a walk would otherwise write `preview = null` over a page of rows
     * whose openings were already paid for, i.e. turn a lost connection into an erasure. `cached`
     * is declared above the `try` for that.
     *
     * ⚠ What is NOT promised, and used to be: it does not hold for a failed CACHE read. `cached` is
     * assigned INSIDE the `try`, so a database that throws on `cachedPreviewsFor` leaves it empty
     * and the page goes out bare. That is bounded rather than repaired, and deliberately: the same
     * database serves the `upsert` this walk feeds, so a broken one fails the write too and there
     * is no page to blank; and the one failure with a bounded input — `IN (:ids)` against SQLite's
     * host-variable cap — is already held off by the "one page, never several" rule written on the
     * query itself.
     */
    @Test fun `a failed body read costs the unread previews, and a cancellation still propagates`() {
        val body = bodyOf("pagePreviews")

        assertEquals(
            "a cancellation must be re-thrown, never turned into 'no previews'",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            codeLinesNaming(body, "cancelled"),
        )
        assertEquals(
            "and every other failure yields the openings the cache already had, rather than taking " +
                "the folder walk down — and rather than nothing, which the upsert would write as a " +
                "blanked column over the whole page",
            listOf("} catch (failed: Throwable) {", "cached"),
            codeLinesNaming(body, "catch (failed") + codeLinesNaming(body, "cached").takeLast(1),
        )
    }

    /**
     * ⛔ It reads on the session the WALK already has selected, and takes none of its own. A
     * `withSession(` here would open a second block on the account's one connection — behind the
     * walk's own mutex, so a deadlock at worst — and, the reason that matters to a reader, it would
     * fetch UIDs under a SELECT that is not the one that enumerated them. A renumbered folder then
     * answers with other messages' text, and the list shows the opening of somebody else's mail
     * under this sender's name (Codeberg #99).
     */
    @Test fun `the list preview is read on the walk's own session, never on one of its own`() {
        val body = bodyOf("pagePreviews")

        assertEquals(
            "the read must use the session handed in by the walk",
            listOf("session.fetchSectionPartials(uids, section, IMAP_PREVIEW_FETCH_BYTES)"),
            codeLinesNaming(body, "session."),
        )
        assertEquals(
            "and never open one of its own — a second session selects the folder again, under a " +
                "numbering these UIDs may no longer belong to",
            emptyList<String>(),
            codeLinesNaming(body, "withSession(") + codeLinesNaming(body, "onMailbox("),
        )
    }
}

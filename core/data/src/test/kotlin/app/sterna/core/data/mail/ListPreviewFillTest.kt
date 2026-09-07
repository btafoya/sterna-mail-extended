package app.sterna.core.data.mail


import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WHO DECIDES to read opening lines for the message list, and what the decision is made of (#187).
 *
 * The decision itself is two pure functions and they are EXECUTED, in `ListPreviewDecisionTest`.
 * What is read as source text here is the wiring on the CALLER's side, because neither end can be
 * built on the JVM: `MailRepository` needs Room and an Android `Context`, `EmailDao` is generated
 * by Room at build time. Whole lines, never a fragment.
 *
 * The property that matters and that nothing else states: `ImapMailService` never learns about
 * the settings or about the database. It is handed a lambda or null, and null means "read nothing".
 */
class ListPreviewFillTest {

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The cache lambda is built in the REPOSITORY, from the two things `ImapMailService` has not
     * and must not grow: the settings, and the cache. [previewWantedInList] is the rule, called
     * rather than restated here; the DAO answers what is already filled — ids AND values, because
     * the IMAP side needs the ids to bound its fetch and the values to write back the rows it
     * therefore skipped ([pageOpenings]).
     *
     * `settings == null` — the shape a test repository is built in — answers null, i.e. NO
     * network read at all. That is the cheap side of being wrong: a list with no opening lines,
     * never a page of body fetches nobody asked for.
     *
     * AND AN UNREADABLE PREFERENCE ANSWERS THE SAME NULL. Before #187 the folder walk read no
     * DataStore at all (`AccountStore` is SharedPreferences-backed); this function put one on its
     * path. DataStore raises the `IOException` of a failed read INSIDE the flow and
     * `SettingsRepository` puts no `catch` on `dataStore.data`, so an unguarded `.first()` here
     * fails the whole walk — the message list — over one line of decoration. The guard has to be
     * `getOrElseUnlessCancelled`, not a bare `runCatching`: a cancellation means the caller is
     * gone and must go through.
     */
    @Test fun `the repository decides, from the settings and from the cache`() {
        val lines = DaoQuerySource.mailFunctionBody("MailRepository", "listPreviewCache")
            .lines().map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

        assertEquals(
            "the whole of the decision, line for line: no settings means no read, an unreadable " +
                "preference means no read either, the rule is CALLED and not rewritten, and the " +
                "answer is what the cache already holds for the page, ids AND values — read with " +
                "the SAME query both write paths take their values from (EmailDao.cachedPreviews), " +
                "and bounded by the NUMBERING the page was just read under: the caller hands both " +
                "in, this lambda invents neither",
            listOf(
                "{",
                "val prefs = settings ?: return null",
                "val wanted = runCatching {",
                "previewWantedInList(prefs.previewLines.first(), prefs.notificationContent.first())",
                "}.getOrElseUnlessCancelled { false }",
                "if (!wanted) return null",
                "return { ids, numbering -> emailDao.cachedPreviews(accountId, ids, numbering).associate { it.id to it.preview } }",
                "}",
            ),
            lines,
        )
    }

    /**
     * The three call sites, and what each of them passes.
     *
     * The two that CACHE a page ask for the cache; the folder-list refresh passes null, because it
     * walks with `limit = 1` and `onPage = {}` — it throws its page away, so a body read there
     * would be paid for on the radio and dropped on the floor.
     */
    @Test fun `the two caching reads ask for the cache, and the folder listing asks for none`() {
        val source = DaoQuerySource.mailSource("MailRepository")

        assertEquals(
            "the scroll page and the folder walk both carry the cache lambda; the folder-list " +
                "refresh must carry a literal null — it caches no message at all",
            listOf(
                "val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE, listPreviewCache(credentials.id))",
                "walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, cachedPreviewsFor = listPreviewCache(credentials.id)) },",
                "val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1, onlySubscribed = onlySubscribed, onPage = {}, cachedPreviewsFor = null)",
            ),
            codeLinesNaming(source, "imap.fetchOlderPage(") + codeLinesNaming(source, "imap.loadFolder("),
        )
    }

    /**
     * The "already filled" question is bound to a COUPLE, and both halves are load-bearing.
     *
     * THE ACCOUNT. Since the composite (accountId, id) key of #31 an email id is not unique across
     * accounts: an unbound `IN (:ids)` would find a same-server sibling account's row, report this
     * account's row as filled, and leave it blank for good — a row that can never be repaired,
     * because the gap is what drives the read.
     *
     * AND THE NUMBERING, which is the harder one and the reason this test changed (#99 inside
     * #187). An IMAP row id is `imap:$accountId:$mailboxId:$uid` and it carries NO UIDVALIDITY: a
     * UID means something only inside one numbering. When a server renumbers a folder — a
     * migration, a restore, a folder deleted and recreated — the UIDs start low again and collide
     * with the ids the cache still holds, and `MailboxUidValidityStore.invalidate` deliberately
     * leaves the `emails` rows alone. Before this column was ever written back, that collision made
     * a row show a BLANK opening line for one refresh. Since the walk writes the cached value back
     * onto the rows it did not re-read ([pageOpenings]), the same collision hands the new message
     * SOMEBODY ELSE'S first line — the right sender, the right subject, another person's words —
     * and it never heals, because a filled row is a row nothing asks about again.
     *
     * `IS` and not `=`: SQLite's `=` is never true against NULL, and a row read from a server that
     * stated no UIDVALIDITY carries NULL in that column. With `=` those accounts would never match
     * their own rows, the page would be re-fetched on every walk, and the cost this whole path
     * exists to avoid would come back in silence.
     *
     * And it asks for the id AND THE VALUE, of rows whose column is NOT NULL: the ids are what the
     * fetch subtracts, the values are what BOTH writers copy back onto their blank rows — the push
     * pass (`keepingCachedPreviews`) and the folder walk itself ([pageOpenings]). ONE query for
     * the two, deliberately: two near-twins would drift, and the direction that hurts is a gap
     * saying "already filled" about a row the write then blanks — nothing would ever ask for that
     * body again. The numbering filter keeps both halves moving together: a row of another
     * numbering is absent from the answer, so the fetch asks for it AND the write-back does not
     * claim it.
     */
    @Test fun `the already-filled question is asked of this account's rows alone`() {
        assertEquals(
            "SELECT id, preview FROM emails WHERE accountId = :accountId AND id IN (:ids) " +
                "AND uidValidity IS :uidValidity AND preview IS NOT NULL",
            DaoQuerySource.emailDaoQuery("cachedPreviews"),
        )
    }

    // -- what the walk WRITES for a row it did not re-read: pageOpenings, executed --------------
    //
    // The defect these three stand on (#187): the folder walk asks the server only for the rows
    // the cache lacks — that is what makes an opening line cost one read ever — and then wrote the
    // page with what came BACK. Room's `@Upsert` replaces the whole row, so every row that was
    // skipped for being already filled had its column set to null: 17 openings out of 20, then 1
    // out of 20, then 17 again, one flip per pull-to-refresh and per `MailFetchWorker` round.
    // `pageOpenings` is that decision, and it is EXECUTED here — the expected maps are written out
    // by hand, never recomputed from the arguments, or this file would agree with any rule at all.

    /**
     * The rows the page did NOT re-read come out carrying the value the cache already holds —
     * the whole of the fix. `keepingCachedPreviews` makes the same refusal at the other writer
     * (the push pass); this one is the folder walk's.
     */
    @Test fun `a row the page did not re-read comes out with the opening line the cache holds`() {
        val opened = pageOpenings(
            cached = mapOf(
                "imap:acct-1:INBOX:7" to "the cached opening",
                "imap:acct-1:INBOX:8" to "another cached opening",
            ),
            read = mapOf("imap:acct-1:INBOX:9" to "freshly read"),
        )

        assertEquals(
            "the page writes an opening line for every row it can name — the two it skipped keep " +
                "the cache's value, the one it fetched carries the new one. A result holding only " +
                "the fetched row is issue #187: the upsert then blanks the other two.",
            mapOf(
                "imap:acct-1:INBOX:7" to "the cached opening",
                "imap:acct-1:INBOX:8" to "another cached opening",
                "imap:acct-1:INBOX:9" to "freshly read",
            ),
            opened,
        )
    }

    /**
     * A page that read NOTHING — every row already filled, which is what a second pull-to-refresh
     * over the same page is, and what the walk is on a folder nobody has touched — must still write
     * the openings it has. This is the exact shape the flip was measured in.
     */
    @Test fun `a page that fetched nothing still carries every opening line the cache holds`() {
        assertEquals(
            "an empty fetch over a filled cache must not answer an empty map: the upsert would " +
                "wipe the column for the whole page",
            mapOf(
                "imap:acct-1:INBOX:7" to "the cached opening",
                "imap:acct-1:INBOX:8" to "another cached opening",
            ),
            pageOpenings(
                cached = mapOf(
                    "imap:acct-1:INBOX:7" to "the cached opening",
                    "imap:acct-1:INBOX:8" to "another cached opening",
                ),
                read = emptyMap(),
            ),
        )
    }

    /**
     * Which ARGUMENT wins on a collision, pinned. It cannot happen on the shipped path — the
     * fetch is asked only for the ids the cache does not have — but the two operands are two maps
     * of the same type, so their order is invisible to the compiler and to every other assertion
     * here. The freshly read body is the fresher of the two and must win.
     */
    @Test fun `a freshly read opening beats the cached one for the same row`() {
        assertEquals(
            "the read is the second operand and must overwrite: the reverse order silently makes " +
                "a stale cached line permanent",
            mapOf("imap:acct-1:INBOX:7" to "freshly read"),
            pageOpenings(
                cached = mapOf("imap:acct-1:INBOX:7" to "the stale cached opening"),
                read = mapOf("imap:acct-1:INBOX:7" to "freshly read"),
            ),
        )
    }
}

package app.sterna.core.data.mail

import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapTextPart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who decides to read opening lines for the message list, and what the decision is made of (#187).
 */
class ListPreviewFillTest {

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The cache lambda is built in the repository, from the two things `ImapMailService` has not
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
     */
    @Test fun `the two caching reads ask for the cache, and the folder listing asks for none`() {
        val source = DaoQuerySource.mailSource("MailRepository") + DaoQuerySource.mailSource("MailFolders")

        assertEquals(
            "the scroll page and the folder walk both carry the cache lambda; the folder-list " +
                "refresh, in MailFolders, must carry a literal null — it caches no message at all",
            listOf(
                "val (entities, exists) = imap.fetchOlderPage(credentials, mailboxId, offset, PAGE_SIZE, listPreviewCache(credentials.id))",
                "walk = { onPage -> imap.loadFolder(credentials, mailboxId, window, onlySubscribed = accountStore.showOnlySubscribedFolders(credentials.id), onPage = onPage, cachedPreviewsFor = listPreviewCache(credentials.id), delta = writes.delta) },",
                "val load = imap.loadFolder(credentials, requestedMailboxId = null, limit = 1, onlySubscribed = onlySubscribed, onPage = {}, cachedPreviewsFor = null)",
            ),
            codeLinesNaming(source, "imap.fetchOlderPage(") + codeLinesNaming(source, "imap.loadFolder("),
        )
    }

    /**
     * The "already filled" question is bound to a couple, and both halves are load-bearing.
     */
    @Test fun `the already-filled question is asked of this account's rows alone`() {
        assertEquals(
            "SELECT id, preview FROM emails WHERE accountId = :accountId AND id IN (:ids) " +
                "AND uidValidity IS :uidValidity AND preview IS NOT NULL",
            DaoQuerySource.emailDaoQuery("cachedPreviews"),
        )
    }

    // -- what the walk writes for a row it did not re-read: pageOpenings, executed --------------
    //
    // The defect these three stand on (#187): the folder walk asks the server only for the rows
    // the cache lacks (that is what makes an opening line cost one read ever) and then wrote the
    // page with what came back. Room's `@Upsert` replaces the whole row, so every row that was
    // skipped for being already filled had its column set to null: 17 openings out of 20, then 1
    // out of 20, then 17 again, one flip per pull-to-refresh and per `MailFetchWorker` round.

    /**
     * The rows the page did not re-read come out carrying the value the cache already holds,
     * untouched.
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
     * A page that read nothing, every row already filled, which is what a second pull-to-refresh
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
     * Which argument wins on a collision, pinned.
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

    private val rawHead = "--=felis-alternative=1 Content-Transfer-Encoding: base64Content-Type: text/plain"

    private fun message(uid: Long) = ImapMessage(
        uid = uid,
        subject = "Subject $uid",
        fromName = "Alex",
        fromEmail = "alex@example.org",
        to = emptyList(),
        dateMillis = 1_700_000_000_000L,
        seen = false,
        flagged = false,
        answered = false,
        hasAttachment = false,
        messageId = "<$uid@example.org>",
        inReplyTo = null,
        textPart = ImapTextPart(section = "1", mime = "text/plain", encoding = "7bit", charset = "utf-8"),
    )

    private val page = listOf(message(7L), message(8L))
    private val ids = listOf("imap:acct-1:INBOX:7", "imap:acct-1:INBOX:8")

    @Test fun `a cached MIME head is asked for again, a cached opening line is not`() {
        assertEquals(
            setOf("imap:acct-1:INBOX:8"),
            previewIdsToRead(ids, mapOf("imap:acct-1:INBOX:7" to "Bonjour", "imap:acct-1:INBOX:8" to rawHead)),
        )
        assertEquals(
            "a row the cache lacks is still asked for",
            setOf("imap:acct-1:INBOX:8"),
            previewIdsToRead(ids, mapOf("imap:acct-1:INBOX:7" to "Bonjour")),
        )
    }

    @Test fun `a cached opening that starts with two dashes but is no MIME head is not asked for again`() {
        listOf(
            "-- Jean",
            "---",
            "--Bonjour l'equipe, le serveur renvoie Content-Type: text/plain depuis hier",
            "--- Content-Type: application/json",
        ).forEach { opening ->
            assertEquals(
                opening,
                emptySet<String>(),
                previewIdsToRead(ids, mapOf("imap:acct-1:INBOX:7" to "Bonjour", "imap:acct-1:INBOX:8" to opening)),
            )
        }
    }

    @Test fun `a cached MIME head is replaced by the line read again`() {
        val asked = mutableListOf<Long>()
        val opened = pageOpenings(
            mapOf("imap:acct-1:INBOX:7" to "Bonjour", "imap:acct-1:INBOX:8" to rawHead),
            previewsForPage("acct-1", "INBOX", page, previewIdsToRead(ids, mapOf("imap:acct-1:INBOX:7" to "Bonjour", "imap:acct-1:INBOX:8" to rawHead))) { _, uids ->
                asked += uids
                uids.associateWith { "Read again $it" }
            },
        )
        assertEquals("only the MIME head goes to the server", listOf(8L), asked)
        assertEquals(mapOf("imap:acct-1:INBOX:7" to "Bonjour", "imap:acct-1:INBOX:8" to "Read again 8"), opened)
    }

    @Test fun `a cached MIME head the read cannot replace stays in the column`() {
        val cached = mapOf("imap:acct-1:INBOX:7" to "Bonjour", "imap:acct-1:INBOX:8" to rawHead)
        listOf<Map<Long, String>>(emptyMap(), mapOf(8L to "")).forEach { answer ->
            assertEquals(
                "a read that brings nothing back must not blank the row: the upsert would write null " +
                    "and the cache would lose what the display already hides",
                cached,
                pageOpenings(cached, previewsForPage("acct-1", "INBOX", page, previewIdsToRead(ids, cached)) { _, _ -> answer }),
            )
        }
    }
}

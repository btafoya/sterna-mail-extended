package app.sterna.core.data.mail

import app.sterna.core.imap.ImapMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * THE NOTIFICATION PASS MUST NOT ERASE WHAT THE FOLDER WALK JUST WROTE (#187).
 *
 * `ImapMailService.loadWatchedFolders` builds its rows with `preview = null` — it reads no body,
 * on purpose: it is the push path, it has its own budget, and filling six rows of a page of fifty
 * is the half-true list `BodyPreview` refuses. That was harmless while the column was ALWAYS null.
 * It is not any more: `MailRepository.refreshAccountFolders` writes those rows with `@Upsert`,
 * which REPLACES THE WHOLE ROW, so the pass blanked the opening line of the newest `limit` rows of
 * the inbox and of every watched folder — every push pass and every `MailFetchWorker` round, half
 * hourly. The line came back on the next walk and went again on the next pass: a flicker.
 *
 * [keepingCachedPreviews] is the decision and it is EXECUTED here, over rows built by the shipped
 * `toEntity` so the keys are the real cache ids. Only the WIRING is read as source text, at the
 * end, because `MailRepository` needs Room and a `Context` and cannot be built on the JVM.
 */
class PushPassKeepsCachedPreviewsTest {

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
    )

    /** A row exactly as the push pass builds it: no opening line read for it. */
    private fun pushRow(uid: Long) = message(uid).toEntity("acct-1", "INBOX", 42L, preview = null)

    /**
     * THE WITNESS. The pass hands over a page it read no bodies for; the cache holds the opening
     * lines the folder walk paid for. The rows that go to the upsert must carry them.
     *
     * Without this the user sees the opening lines after a pull-to-refresh and loses them at the
     * next push — and a snoozed message re-announced from its bare row loses the extract with them.
     */
    @Test fun `a row the push pass built blank takes back the opening line the cache holds`() {
        val kept = keepingCachedPreviews(
            listOf(pushRow(7L), pushRow(8L)),
            mapOf(
                "imap:acct-1:INBOX:7" to "Bonjour, l'été arrive",
                "imap:acct-1:INBOX:8" to "Meeting moved to Thursday",
            ),
        )

        assertEquals(
            "the page written to the cache must carry the openings the cache already had",
            listOf("Bonjour, l'été arrive", "Meeting moved to Thursday"),
            kept.map { it.preview },
        )
    }

    /**
     * EACH LINE UNDER ITS OWN ROW'S ID, never by position. The map is keyed by cache id for
     * exactly this reason: pairing it by order would put one correspondent's words under another
     * correspondent's name, in the list, silently — the same failure the page fetch is keyed by UID
     * to avoid.
     */
    @Test fun `each opening line lands on the row whose id carries it`() {
        val kept = keepingCachedPreviews(
            listOf(pushRow(7L), pushRow(8L), pushRow(9L)),
            mapOf("imap:acct-1:INBOX:9" to "from Carol", "imap:acct-1:INBOX:7" to "from Alice"),
        )

        assertEquals(
            mapOf(
                "imap:acct-1:INBOX:7" to "from Alice",
                "imap:acct-1:INBOX:8" to null,
                "imap:acct-1:INBOX:9" to "from Carol",
            ),
            kept.associate { it.id to it.preview },
        )
    }

    /**
     * A row that ALREADY carries an opening line keeps its OWN. The row is the fresher of the
     * two and the cached value is what it was about to replace; letting the cache win would freeze
     * a stale line onto a row whose message the server has since changed under the same id.
     */
    @Test fun `a row that already carries an opening line keeps its own`() {
        val row = message(7L).toEntity("acct-1", "INBOX", 42L, preview = "what the row read")

        assertEquals(
            "what the row read",
            keepingCachedPreviews(
                listOf(row),
                mapOf("imap:acct-1:INBOX:7" to "what the cache held"),
            ).single().preview,
        )
    }

    /**
     * And a row the cache knows nothing about stays BLANK. On this path blank means "no opening
     * line read yet", which is what the next folder walk fetches a body for; inventing anything
     * here — an empty string above all — would make that row indistinguishable from one whose
     * message has no readable text, and nothing would ever ask about it again.
     */
    @Test fun `a row the cache knows nothing about stays blank`() {
        val kept = keepingCachedPreviews(listOf(pushRow(7L)), emptyMap())

        assertEquals(1, kept.size)
        assertNull(kept.single().preview)
        assertEquals("nothing else about the row may change", pushRow(7L), kept.single())
    }

    /**
     * SOURCE TEXT, AND A LAST RESORT — `MailRepository` cannot be instantiated on the JVM. The
     * decision above is executed; what is pinned here is only that the shipped write goes through
     * it, with these ARGUMENTS: `loads.forEach { emailDao.upsertAll(it.messages) }` is the defect
     * itself, and it carries no name this file could otherwise see.
     *
     * One cache read per FOLDER page, inside the loop. `EmailDao.cachedPreviews` binds one host
     * variable per id and SQLite caps them at 999: joining every watched folder's page into a
     * single call would throw on an account watching enough folders, and this write is the one
     * that lands the push pass's mail.
     */
    @Test fun `the shipped write carries the cached openings over, one folder page at a time`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders")
        val codeLines = { needle: String ->
            body.lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { needle in it }
        }

        assertEquals(
            "every write this function makes must be the REBUILT page, and the read that feeds it " +
                "must be scoped to one load's messages",
            listOf(
                "val cached = emailDao.cachedPreviews(credentials.id, load.messages.map { it.id }, load.uidValidity)",
                "emailDao.upsertAll(keepingCachedPreviews(load.messages, cached.associate { it.id to it.preview }))",
            ),
            codeLines("cachedPreviews(") + codeLines("upsertAll("),
        )
    }

    /**
     * AND THE READ IS BOUNDED BY THE NUMBERING THE PAGE WAS READ UNDER — the half `keepingCachedPreviews`
     * cannot do anything about, because it never sees a numbering (#99 inside #187).
     *
     * A row id is `imap:$accountId:$mailboxId:$uid` and carries no UIDVALIDITY, and
     * `MailboxUidValidityStore.invalidate` deliberately leaves the `emails` rows in place when a
     * folder is renumbered. So after a renumbering the low UIDs the server hands back collide with
     * ids the cache still holds, and a lookup by id alone answers the PREVIOUS message's opening
     * line — which this pass would then copy onto the new row and never re-ask about, since a
     * filled row is a row nothing reads a body for again.
     *
     * The bound is the third argument and it comes from the LOAD, not from the rows: the page's own
     * entities were built under the same SELECT, but reading it back off them would make the guard
     * answerable to the very rows it is guarding. `loadWatchedFolders` puts the SELECT's answer on
     * the load, once.
     *
     * NO DEFAULT on that field, for the reason already written on `previewSources` and
     * `folderStatedEmpty`: it is an answer the server gave, and a default is a way of not asking.
     * A defaulted `null` would silently mean "unknown numbering" for every folder and quietly match
     * only the rows of servers that state no UIDVALIDITY.
     */
    @Test fun `the push load carries the numbering its page was read under, and has no default`() {
        val source = DaoQuerySource.mailSource("ImapMailService")

        assertEquals(
            "ImapWatchedLoad must declare the numbering, and declare it without a default",
            listOf("val uidValidity: Long?,"),
            source.substringAfter("data class ImapWatchedLoad(").substringBefore("\n)")
                .lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.startsWith("val uidValidity") },
        )
        assertEquals(
            "and it is filled from the SELECT that enumerated the page, through the one shipped " +
                "normalisation (UidValidity.stated): 0 means the server stated nothing, and 0 is " +
                "not a numbering — it must become null, exactly as the row's own column does",
            listOf("uidValidity = UidValidity.stated(status.uidValidity),"),
            DaoQuerySource.mailFunctionBody("ImapMailService", "loadWatchedFolders")
                .lines().map { it.trim() }
                .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
                .filter { it.startsWith("uidValidity =") },
        )
    }
}

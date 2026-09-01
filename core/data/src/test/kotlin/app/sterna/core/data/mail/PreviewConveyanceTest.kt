package app.sterna.core.data.mail

import app.sterna.core.imap.ImapMessage
import app.sterna.core.imap.ImapTextPart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The CONVEYANCE: the section the server named in the BODYSTRUCTURE that came with the envelope,
 * carried from the sync page to the notification layer — ⛔ never asked for a second time.
 *
 * [previewSourcesOf] is pure and is executed here. The two ends it is plugged into cannot be built
 * on the JVM (`ImapMailService` needs a token refresher and a socket, `MailRepository` a database),
 * so they are read as source text, whole lines at a time — including the claim that carries the
 * whole point: `loadWatchedFolders` makes no server call it did not already make.
 */
class PreviewConveyanceTest {

    private val plain = ImapTextPart("1", "text/plain", "quoted-printable", "utf-8")
    private val nested = ImapTextPart("1.2", "text/html", "base64", null)

    private fun message(uid: Long, textPart: ImapTextPart? = null) = ImapMessage(
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
        textPart = textPart,
    )

    /**
     * Keyed by the SAME cache id the page's rows get, so the notifier can look one up for a message
     * it is holding without taking an id apart — and the section and its encoding travel whole,
     * because reading a part under a charset nobody announced is mojibake on a lock screen.
     */
    @Test fun `each message's section is carried out under that message's cache id`() {
        assertEquals(
            mapOf(
                "imap:acct-1:INBOX:7" to PreviewSource(7L, plain),
                "imap:acct-1:INBOX:9" to PreviewSource(9L, nested),
            ),
            previewSourcesOf(
                "acct-1",
                "INBOX",
                listOf(message(7L, plain), message(8L), message(9L, nested)),
            ),
        )
    }

    /**
     * ⛔ A message with no `text/…` part at all is ABSENT, not present-and-null. It has no opening
     * line, so it must cost neither a round trip nor one of the pass's few preview slots — the
     * notification pre-pass counts what is in this map.
     */
    @Test fun `a message with no readable text is not in the map at all`() {
        assertEquals(
            emptyMap<String, PreviewSource>(),
            previewSourcesOf("acct-1", "INBOX", listOf(message(1L), message(2L))),
        )
    }

    /** A folder path with a ':' in it still keys the same way the cache does. */
    @Test fun `the key is the cache id, delimiters and all`() {
        assertEquals(
            setOf("imap:acct-1:Archive:2026:3"),
            previewSourcesOf("acct-1", "Archive:2026", listOf(message(3L, plain))).keys,
        )
    }

    /**
     * ⛔⭐ THE WHOLE POINT: the page is carried, never re-fetched. These are every server call the
     * watched-folder load makes, and there is no second BODYSTRUCTURE among them — the structure
     * arrives with the envelope of the page below and, until this change, was thrown away by
     * `toEntity`. A `fetchPage` or a `select` added here is one per watched folder per push pass.
     */
    @Test fun `the watched-folder load asks the server for nothing new`() {
        val body = DaoQuerySource.mailFunctionBody("ImapMailService", "loadWatchedFolders")

        assertEquals(
            "every server call of the load, and no other",
            listOf(
                "val folders = session.listFolders()",
                "val status = session.select(folder.path)",
                "val page = session.fetchPage(status.exists, offset = 0, limit = limit)",
            ),
            codeLinesNaming(body, "session."),
        )
        assertEquals(
            "the sources must be derived from THAT page, in memory",
            listOf("previewSourcesOf(credentials.id, folder.path, page),"),
            codeLinesNaming(body, "previewSourcesOf("),
        )
        assertEquals(
            "and the page's rows must still be the ones cached — the map is additional, not a swap, " +
                "and it carries `status.uidValidity`: the numbering THIS SELECT stated is stamped on " +
                "the rows it enumerated, which is what a later permanent delete opposes (#99). This " +
                "pass is the background one that MEETS the renumbering, so a row it writes unstamped " +
                "is a row a destroy can only judge against the folder's realigned record. ⛔ And it " +
                "READS no preview (#187): this pass reads six openings of a page under the " +
                "notification budget, and caching those six is the half-true list BodyPreview refuses",
            listOf("page.map { it.toEntity(credentials.id, folder.path, status.uidValidity, null) },"),
            codeLinesNaming(body, "toEntity("),
        )
    }

    // ⛔ "READS no preview" is the whole of what that line says, and it must not be read as "writes
    // none": `@Upsert` replaces the whole row, so the `null` above wipes whatever opening line the
    // cache holds for these rows unless the WRITE carries it over. That is one layer up, in
    // `MailRepository`, and it is stated and executed in `PushPassKeepsCachedPreviewsTest`.

    /**
     * ⛔ And it reaches the notifier's own carrier. IMAP fills it from the load; JMAP states that it
     * has nothing to convey, because its rows already carry `preview` — filling it there would buy
     * a round trip for a string the notifier is holding.
     */
    @Test fun `the refresh carries the sources on IMAP and states there are none on JMAP`() {
        assertEquals(
            listOf("previewSources = load.previewSources,", "previewSources = emptyMap(),"),
            codeLinesNaming(
                DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders"),
                "previewSources =",
            ),
        )
    }

    /**
     * ⛔ The read the notification layer is offered is the bounded one, and its deadline is a
     * parameter with no default at BOTH ends: the block behind it is blocking, holds the account's
     * mutex and has no suspension point, so nothing outside it can cut it short.
     */
    @Test fun `the notification read is a passthrough that cannot lose its deadline`() {
        val source = DaoQuerySource.mailSource("MailRepository")

        assertEquals(
            "the preview read must go straight to the bounded IMAP call, budget and all",
            listOf("): String? = imap.fetchPreview(credentials, mailboxId, source.uid, source.part, budgetMs)"),
            codeLinesNaming(source, "imap.fetchPreview("),
        )
        assertEquals(
            "with no default to forget — a defaulted deadline is soTimeout = 0, i.e. none. Two " +
                "such parameters in this file now, both no-default by design: the preview " +
                "passthrough's own, and imapDraftAddressing's (the draft-proof read, whose SEND " +
                "caller passes SEND_PROOF_BUDGET_MS and whose SAVE caller passes " +
                "ImapBudget.NO_BUDGET explicitly — see SendDraftDestructionWiringTest).",
            listOf(
                "budgetMs: Int,",
                "private suspend fun imapDraftAddressing(credentials: AccountCredentials, emailId: String, budgetMs: Int): Set<String>? {",
            ),
            codeLinesNaming(source, "budgetMs: Int"),
        )
        assertEquals(
            "and the numbering read, which is the only trace a refused SELECT leaves behind (#99)",
            listOf("imap.recordedUidValidity(accountId, mailboxId)"),
            codeLinesNaming(source, "recordedUidValidity(accountId,"),
        )
    }

    // -- previewsForPage: what actually goes on the wire, and where each body lands ----------------

    private val html = ImapTextPart("1.1", "text/html", "7bit", "utf-8")
    private val second = ImapTextPart("2", "text/plain", "7bit", "utf-8")

    /** A [previewsForPage] `fetch` that RECORDS what it was asked for and answers [bodies]. */
    private class Recorder(private val bodies: Map<Long, String> = emptyMap()) {
        val asked = mutableListOf<Pair<String, List<Long>>>()
        fun fetch(section: String, uids: List<Long>): Map<Long, String> {
            asked += section to uids
            return bodies.filterKeys { it in uids }
        }
    }

    private fun page(vararg uids: Long, part: ImapTextPart) = uids.map { message(it, part) }

    /**
     * ⭐ ONLY WHAT IS MISSING IS ASKED FOR. This is what makes the openings of a folder cost one
     * read each EVER: the pager walks the same folder again on every step and the pull-to-refresh
     * re-reads the newest page, so without this filter a page of two hundred rows would be
     * re-downloaded — up to 1.6 MB at 8 KB a message — every single time, on the account's one
     * connection, for text already sitting in the database.
     */
    @Test fun `only the rows with no cached opening are asked for`() {
        val recorder = Recorder(mapOf(2L to "two", 3L to "three", 5L to "five"))

        previewsForPage(
            "acct-1",
            "INBOX",
            page(1L, 2L, 3L, 4L, 5L, part = plain),
            missing = setOf("imap:acct-1:INBOX:2", "imap:acct-1:INBOX:3", "imap:acct-1:INBOX:5"),
            fetch = recorder::fetch,
        )

        assertEquals(
            "the three blank rows and nobody else — the two already filled must not go on the wire",
            listOf("1" to listOf(2L, 3L, 5L)),
            recorder.asked,
        )
    }

    /**
     * ⭐ ONE REQUEST PER SECTION, never one per message. A round trip per message would be up to two
     * hundred sequential commands on the account's SINGLE connection, held under its mutex — with
     * opening a message, marking one read and emptying the trash queued behind all of them. The
     * server names the section in the BODYSTRUCTURE it already sent, and real mail uses two to four
     * of them across a page.
     */
    @Test fun `the page is asked for one section at a time, not one message at a time`() {
        val recorder = Recorder()

        previewsForPage(
            "acct-1",
            "INBOX",
            listOf(message(10L, plain), message(11L, html), message(12L, plain)),
            missing = setOf("imap:acct-1:INBOX:10", "imap:acct-1:INBOX:11", "imap:acct-1:INBOX:12"),
            fetch = recorder::fetch,
        )

        assertEquals(
            "two calls for three messages: the two '1' parts share one, the '1.1' part has its own",
            listOf("1" to listOf(10L, 12L), "1.1" to listOf(11L)),
            recorder.asked,
        )
    }

    /**
     * ⛔ EACH BODY UNDER ITS OWN MESSAGE'S CACHE ID. The grouped answer comes back keyed by UID for
     * exactly this reason; anything that pairs it by position instead puts one correspondent's
     * words under another correspondent's name, in the list, silently.
     */
    @Test fun `each body comes back under its own message's cache id`() {
        val previews = previewsForPage(
            "acct-1",
            "INBOX",
            listOf(message(10L, second), message(11L, second), message(12L, second)),
            missing = setOf("imap:acct-1:INBOX:10", "imap:acct-1:INBOX:11", "imap:acct-1:INBOX:12"),
            fetch = { _, _ -> mapOf(10L to "from Alice", 11L to "from Bob", 12L to "from Carol") },
        )

        assertEquals(
            mapOf(
                "imap:acct-1:INBOX:10" to "from Alice",
                "imap:acct-1:INBOX:11" to "from Bob",
                "imap:acct-1:INBOX:12" to "from Carol",
            ),
            previews,
        )
    }

    /** A message the server said nothing about is simply absent — not an empty opening line. */
    @Test fun `a message the answer skipped gets no entry`() {
        val previews = previewsForPage(
            "acct-1",
            "INBOX",
            listOf(message(10L, second), message(11L, second)),
            missing = setOf("imap:acct-1:INBOX:10", "imap:acct-1:INBOX:11"),
            fetch = { _, _ -> mapOf(10L to "answered") },
        )

        assertEquals(mapOf("imap:acct-1:INBOX:10" to "answered"), previews)
    }

    /**
     * ⛔ NOTHING TO ASK MEANS NO COMMAND AT ALL — not an empty one. A page whose rows are all filled
     * is the ordinary case of every refresh after the first, and a page of attachments-only mail has
     * no text to read; either one putting a `UID FETCH` on the wire would spend the radio to learn
     * what was already in hand.
     */
    @Test fun `a page with nothing to read touches the server not at all`() {
        val allFilled = Recorder()
        previewsForPage(
            "acct-1",
            "INBOX",
            page(1L, 2L, part = plain),
            missing = emptySet(),
            fetch = allFilled::fetch,
        )
        assertEquals("every row already has its opening: nothing may go out", emptyList<Pair<String, List<Long>>>(), allFilled.asked)

        val noText = Recorder()
        previewsForPage(
            "acct-1",
            "INBOX",
            listOf(message(1L), message(2L)),
            missing = setOf("imap:acct-1:INBOX:1", "imap:acct-1:INBOX:2"),
            fetch = noText::fetch,
        )
        assertEquals("no text part anywhere: nothing may go out", emptyList<Pair<String, List<Long>>>(), noText.asked)
    }

    /**
     * The octets are read by [BodyPreview.fromPart] and by nothing written here: the transfer
     * encoding is undone, the charset applied, and HTML flattened — in that order, which is the
     * order the message imposes. A row showing `=C3=A9` or a `<p>` is the visible failure.
     */
    @Test fun `the bodies are decoded, not shown as they came off the wire`() {
        val previews = previewsForPage(
            "acct-1",
            "INBOX",
            listOf(message(10L, plain), message(11L, html)),
            missing = setOf("imap:acct-1:INBOX:10", "imap:acct-1:INBOX:11"),
            fetch = { _, _ ->
                mapOf(
                    10L to "Bonjour, l'=C3=A9t=C3=A9 arrive",
                    11L to "<p>Hello <b>world</b> &amp; more</p>",
                )
            },
        )

        assertEquals(
            mapOf(
                "imap:acct-1:INBOX:10" to "Bonjour, l'été arrive",
                "imap:acct-1:INBOX:11" to "Hello world & more",
            ),
            previews,
        )
    }

    /**
     * ⛔ A body that decodes to nothing readable is an ORDINARY answer and does not become a key —
     * the same rule [BodyPreview.fromPart] states for the notification. A blank string written into
     * the column would be indistinguishable from a real opening line for every later reader, and
     * would stop this row ever being asked about again.
     */
    @Test fun `a body that decodes to blank is not a key`() {
        val previews = previewsForPage(
            "acct-1",
            "INBOX",
            listOf(message(10L, second), message(11L, second)),
            missing = setOf("imap:acct-1:INBOX:10", "imap:acct-1:INBOX:11"),
            fetch = { _, _ -> mapOf(10L to "   \r\n  ", 11L to "real text") },
        )

        assertEquals(mapOf("imap:acct-1:INBOX:11" to "real text"), previews)
    }

    /** The code lines of [body] naming [needle], comments dropped — whole lines, never a search. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }
}

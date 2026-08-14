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
            "and the page's rows must still be the ones cached — the map is additional, not a swap",
            listOf("page.map { it.toEntity(credentials.id, folder.path) },"),
            codeLinesNaming(body, "toEntity("),
        )
    }

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

    /** The code lines of [body] naming [needle], comments dropped — whole lines, never a search. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }
}

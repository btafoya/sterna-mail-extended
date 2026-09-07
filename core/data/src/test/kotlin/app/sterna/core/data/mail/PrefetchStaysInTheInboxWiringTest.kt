package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — read this before trusting it.
 *
 * Which folders get their bodies warmed in the background is no longer a bandwidth question. Since
 * this branch, a body that lands in the cache also hands the sender's announced public key to the
 * OpenPGP provider (`autocryptPeerImportOf` decides, `importAutocryptPeer` writes, both in
 * `AutocryptPeerImport.kt`, reached from `originalSendersOf` inside the prefetch), so these lines
 * decide WHERE A CORRESPONDENT'S KEY MAY ARRIVE FROM WITHOUT ANYONE OPENING ANYTHING. Widen any of
 * them and a spoofed message that never reaches the inbox — filed as junk, or dragged to the trash
 * by the user — still deposits its author's key under the address of the correspondent it
 * imitates, inside OpenKeychain, with no gesture and nothing the app can undo.
 *
 * What actually holds the published promise, and in which order. ENCRYPTION.md and SECURITY.md
 * say that nothing arrives on its own from Spam, Trash or Archive, and that on an IMAP account a
 * key only comes from a message whose full text Sterna had to fetch. Two lines keep the prefetch
 * away from an IMAP account, and BOTH sit at the call sites, above it:
 *  - `refresh` returns into `refreshImap` before reaching anything pinned here (`MailRepository`
 *    :3496) — already pinned WHOLE by [SyncWindowReachesBothProtocolsTest], for its own reason, so
 *    this file does not restate it;
 *  - `refreshAllInboxes` takes its IMAP branch and `continue`s, which nothing watched before this
 *    file and which is pinned below, `continue` included.
 *
 * So the `if (credentials.protocol == MailProtocol.IMAP) return` opening `prefetchInboxBodies` is
 * UNREACHABLE as the code stands: no caller can reach it with an IMAP account. It is pinned all the
 * same, as the last net rather than the first, and the pin says so — removing that line alone
 * changes no observable behaviour today, and would only matter the day a third caller appears.
 *
 * What was NOT watched before this file: [BoundedFolderReadsSqlTest] (`prefetchInboxBodies`, the
 * bounded read) and [OriginalSenderWiringTest] both read the prefetch's BODY; neither reads its
 * CALL SITES, so the guard above them and the folder handed to them could both be mutated with the
 * whole suite staying green. `MailRepository` needs Room, an Android `Context` and a socket, so no
 * JVM test can execute these call sites — reading the source is the last resort, in
 * [ArmingWiringTest]'s exact form.
 *
 * Whole normalised lines, compared by equality, never `contains`: a substring is blind to every
 * mutation that LENGTHENS the line, which has caught this repository out three times. A line is
 * dropped as prose only when NOTHING is left of it once its complete block comments are removed —
 * see [codeLines], and read its before touching it.
 */
class PrefetchStaysInTheInboxWiringTest {

    @Test fun `a single-account refresh warms bodies only when the folder is the inbox`() {
        assertEquals(
            "the guard and the launch it guards must sit together, in this order, whole. ⚠ An " +
                "empty `but was []` means the line `if (target.role == \"inbox\") {` is no longer " +
                "in `refresh` verbatim — widened to `!= null`, renamed, moved, or simply " +
                "lengthened by a trailing comment — so the pair cannot be looked up at all. " +
                "Widened, a refresh of Junk, Trash or Archive prefetches their bodies, and every " +
                "prefetched body hands the sender's announced key to OpenKeychain: a spoofed " +
                "message the user never saw, and never opened, plants a key under the address of " +
                "the contact it imitates, and nothing in this app can take it back. And should " +
                "the second line hand the prefetch anything other than `target.id`, the guard " +
                "stays in place while another folder is warmed anyway.",
            listOf(REFRESH_GUARD, "bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }"),
            adjacentBlock(codeLines(bodyOf("refresh")), REFRESH_GUARD, 2),
        )
        assertEquals(
            "`target` must be BOUND exactly once in `refresh`, and to this. The pair above pins " +
                "two adjacent lines; a second binding of the same name anywhere above them — " +
                "`val target = mailboxes.first { it.role == \"junk\" }` — leaves both of those " +
                "lines untouched, satisfies every text comparison in this file, and hands the " +
                "prefetch a different folder at run time. A pin on a NAME is worth nothing " +
                "without a pin on what that name is bound to, once.",
            listOf("val target = mailboxId?.let { id -> mailboxes.firstOrNull { it.id == id } }"),
            bindingsOf(bodyOf("refresh"), "target"),
        )
    }

    @Test fun `the unified refresh warms the folder it resolved as the inbox, and no other`() {
        assertEquals(
            "`refreshAllInboxes` is the SECOND producer of a prefetch, and the one the branch " +
                "brief forgot. The folder it warms must be the one it resolved as the account's " +
                "inbox — `inbox.id`, the same `inbox` it just synced. Any other id (a mailbox " +
                "picked from `resolved.mailboxes`, the account's trash, a folder carried over " +
                "from the previous loop turn) prefetches bodies outside the inbox on every " +
                "unified refresh, and each of those bodies imports its sender's public key into " +
                "OpenKeychain unopened. Exactly one such line, so a second prefetch cannot be " +
                "added beside it — ⛔ including one hidden behind a `/**/` on its own line, which " +
                "is what this file's [codeLines] exists to stop.",
            listOf("bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }"),
            codeLinesNaming(bodyOf("refreshAllInboxes"), "prefetchInboxBodies("),
        )
    }

    @Test fun `the unified refresh resolves that folder by the server's inbox role, once`() {
        assertEquals(
            "`inbox` must be BOUND exactly once in `refreshAllInboxes`, and to this. ⛔ This is " +
                "the assertion that makes the next one mean anything: eleven lines sit between " +
                "the resolution and the launch, and `val inbox = resolved.mailboxes.firstOrNull " +
                "{ it.role == \"junk\" } ?: inbox` slipped in among them leaves the resolution " +
                "line in place, unique and verbatim, while `inbox.id` at the launch names the " +
                "junk folder. Every message in Spam then has its body fetched on each unified " +
                "refresh, and every spoofed sender in it plants a key in OpenKeychain under the " +
                "address it imitates, with nothing opened and nothing undoable.",
            listOf(INBOX_RESOLUTION),
            bindingsOf(bodyOf("refreshAllInboxes"), "inbox"),
        )
        assertEquals(
            "and the three lines of that single binding must stay exactly these, in this order. " +
                "⚠ `?: resolved.mailboxes.firstOrNull()` is deliberate and already the widest " +
                "this may be: an account whose server declares no `inbox` role warms its first " +
                "folder, whatever that is. Widen it further (drop the role filter, take a folder " +
                "by name, iterate the list) and the keys of junk senders arrive with no gesture.",
            listOf(INBOX_RESOLUTION, "?: resolved.mailboxes.firstOrNull()", "?: continue"),
            adjacentBlock(codeLines(bodyOf("refreshAllInboxes")), INBOX_RESOLUTION, 3),
        )
    }

    @Test fun `an IMAP account leaves the unified refresh before any of this`() {
        assertEquals(
            "⛔ This is one of the TWO lines that actually keep an IMAP account away from the " +
                "prefetch, and the one nothing watched: `refreshAllInboxes` must serve an IMAP " +
                "account entirely inside this branch and `continue` out of the loop turn. Drop " +
                "the `continue` — or let a statement appear after the branch and before it — and " +
                "the pass falls through into the JMAP half for an IMAP account: `resolve`, " +
                "`syncMailbox`, and then the prefetch, which then really does import the keys of " +
                "the newest inbox senders on an IMAP account without anything being opened. " +
                "ENCRYPTION.md and SECURITY.md promise the opposite in those words. The other " +
                "line, `refresh`'s `return refreshImap(...)`, is pinned whole by " +
                "[SyncWindowReachesBothProtocolsTest] and is deliberately not restated here.",
            listOf(
                "if (credentials.protocol == MailProtocol.IMAP) {",
                "val load = imapWriteThrough(credentials, mailboxId = null, limit = limit)",
                "mailboxDao.replaceAll(credentials.id, load.mailboxes)",
                "results += AccountInboxMeta(",
                "credentials.id, load.accountName, load.targetMailboxId, load.targetName, load.unread,",
                ")",
                "continue",
                "}",
            ),
            adjacentBlock(
                codeLines(bodyOf("refreshAllInboxes")),
                "if (credentials.protocol == MailProtocol.IMAP) {",
                8,
            ),
        )
    }

    @Test fun `the prefetch keeps its own IMAP refusal, unreachable though it is today`() {
        assertEquals(
            "`prefetchInboxBodies` must still open on `if (credentials.protocol == " +
                "MailProtocol.IMAP) return`, as its FIRST statement. ⚠ Be honest about what this " +
                "is worth: BOTH of its callers already leave for IMAP before reaching it " +
                "(`refresh` returns into `refreshImap`, `refreshAllInboxes` `continue`s), so " +
                "removing this line alone changes nothing observable today and no user could tell. " +
                "It is defence in depth — the last net, not the first — and it is pinned so that " +
                "the day a third caller appears, the net is still there rather than having been " +
                "tidied away as dead code. What the two documents actually rest on is pinned by " +
                "the test above and by [SyncWindowReachesBothProtocolsTest]. And should this line " +
                "move BELOW the fetch, it stops being a refusal at all: the bodies are already " +
                "cached by then.",
            listOf("{", "if (credentials.protocol == MailProtocol.IMAP) return"),
            codeLines(bodyOf("prefetchInboxBodies")).take(2),
        )
    }

    @Test fun `only these two call sites can start a prefetch at all`() {
        assertEquals(
            "the whole of MailRepository.kt may name `prefetchInboxBodies(` on exactly three " +
                "lines: the two refresh call sites and the declaration. A fourth line is a third " +
                "producer — a push pass, a delta, a folder the user opened — warming bodies " +
                "somewhere nobody audited, and every warmed body is an unopened key import. " +
                "Fewer than three means a call site was deleted and the inbox is no longer " +
                "warmed at all.",
            listOf(
                "bgScope.launch { runCatching { prefetchInboxBodies(credentials, inbox.id) } }",
                "bgScope.launch { runCatching { prefetchInboxBodies(credentials, target.id) } }",
                "private suspend fun prefetchInboxBodies(credentials: AccountCredentials, mailboxId: String) {",
            ),
            codeLines(DaoQuerySource.mailSource("MailRepository")).filter { "prefetchInboxBodies(" in it },
        )
    }

    // --- reading the source ----------------------------------------------------------------------

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /**
     * [text]'s lines as CODE: every COMPLETE block comment removed from wherever it sits on the
     * line, runs of whitespace collapsed to one, and the line dropped only when nothing is left of
     * it.
     *
     * This used to drop any line whose first two characters opened a block comment, which is a
     * hole and not a detail: an empty block comment, opened and closed at once, followed by a real
     * `bgScope.launch { runCatching { prefetchInboxBodies(credentials, junk.id) } }` is valid
     * Kotlin, and that prefix test threw the STATEMENT away with the comment — the
     * inventories in this file counted it as prose and stayed green while an unopened key import
     * ran on the junk folder at every refresh. Neither ktlint nor detekt is wired in this
     * repository to catch it either. A comment may now hide nothing: it can only ever make a rule
     * red, never satisfy one.
     *
     * A line that still opens an UNCLOSED block comment, a KDoc continuation starting with a
     * star, or a line comment, is prose and goes, as before — the comment blocks around these
     * functions must stay free to be rewritten without reddening anything.
     */
    private fun codeLines(text: String): List<String> = text.lines()
        .map { WHITESPACE.replace(BLOCK_COMMENT.replace(it, " "), " ").trim() }
        .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** The code lines of [body] naming [needle], whole — the assertions compare them, never search
     *  inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLines(body).filter { needle in it }

    /**
     * Every code line of [body] that BINDS the name [name] — a `val`/`var` declaration or a bare
     * assignment, never a comparison (`==` is excluded).
     *
     * Why a count of bindings and not just the text of one: a lint that pins "this line is in the
     * body" is answered by a line that is still there and no longer decides anything. A second
     * binding between the resolution and its use is the cheapest way to make every text pin in
     * this file lie, and it is invisible to all of them.
     */
    private fun bindingsOf(body: String, name: String): List<String> =
        codeLines(body).filter { Regex("""^(val\s+|var\s+)?$name\s*=(?!=)""").containsMatchIn(it) }

    /**
     * [count] lines starting at the single line equal to [needle]. Empty when [needle] is not there
     * exactly once, so a rename or a widening reddens the rule that uses it instead of quietly
     * satisfying it against nothing.
     */
    private fun adjacentBlock(lines: List<String>, needle: String, count: Int): List<String> {
        val at = lines.indices.filter { lines[it] == needle }.singleOrNull() ?: return emptyList()
        return if (at + count > lines.size) emptyList() else lines.subList(at, at + count)
    }

    private companion object {
        const val REFRESH_GUARD = "if (target.role == \"inbox\") {"

        const val INBOX_RESOLUTION = "val inbox = resolved.mailboxes.firstOrNull { it.role == \"inbox\" }"

        /** A block comment that OPENS AND CLOSES on the same line — the only kind that can sit
         *  beside code, and therefore the only kind [codeLines] may remove rather than obey. */
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""")

        val WHITESPACE = Regex("""\s+""")
    }
}

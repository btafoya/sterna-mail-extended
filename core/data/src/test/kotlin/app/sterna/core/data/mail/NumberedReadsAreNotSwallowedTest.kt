package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE DECISIONS NOBODY WAS WATCHING on the read-and-bin path of a move between accounts (#99, #189).
 *
 * This file guards nothing new. It closes four mutations that changed real behaviour and left the
 * whole suite green, found by `/go-no-go` and by two counter-expertises of this branch. Every rule
 * below is stated as the mutation it kills, because a lint whose reason is not written is a lint
 * the next person deletes.
 *
 * WHY THE EXISTING LINTS MISSED THEM. The ones next door filter a body by
 * `Regex("(?i)(numbering|validity|frozen|recorded)")` or by `"rawSource(" in it`. Two of these four
 * mutations sit on lines that name NONE of those words while deciding WHICH MESSAGE IS READ, and one
 * is an enclosure rather than a line at all. A closed list only sees what its needle matches.
 *
 * Source lint, the last resort as always: `MailRepository` needs Room, a `Context` and a socket,
 * so no JVM test in this module instantiates it.
 */
class NumberedReadsAreNotSwallowedTest {

    private fun codeLines(text: String): List<String> =
        text.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    /**
     * MUTATION: the body of `rawSource` reduced to `openEmail(credentials, emailId, markRead =
     * false)`, or `markRead` flipped to `true`. Both compile, both keep every other pinned line of
     * the file byte-identical, and neither names a numbering — so the numbering lints, which filter
     * on that vocabulary, cannot see either.
     *
     * What they cost:
     *  - the `cachedEmail(…) ?:` dropped ⇒ EVERY line of EVERY move between accounts opens with a
     *    full unopposed FETCH of the message from the server (and the Autocrypt key import that
     *    rides on `openEmail`), on a path whose whole point is that the server is asked once, under
     *    a numbering it has confirmed;
     *  - `markRead = true` ⇒ every move marks the message READ ON THE SERVER before it is read for
     *    the copy. Under the renumbering this branch exists for, that is someone else's message
     *    flagged `\Seen`, and nothing takes it back.
     *
     * So the body is pinned WHOLE, as a closed list of lines, on `MoveToAccountWiringTest`'s model:
     * anything added, removed, reordered or lengthened reddens it.
     */
    @Test fun `the whole body of the public read is these lines, and nothing else decides`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "rawSource")
        assertEquals(
            "⛔ EVERY line of rawSource, in order. It resolves the Email per protocol and hands " +
                "the fetch what it was given: the IMAP branch prefers the CACHED row and only " +
                "falls back on the server; both openEmail calls are markRead = false. A read that " +
                "marks mail read is a write, and this function is called by a move, by a .eml " +
                "export and by the OpenPGP retry. Body was:\n$body",
            listOf(
                "{",
                "val email = if (credentials.protocol == MailProtocol.IMAP) {",
                "cachedEmail(credentials.id, emailId) ?: openEmail(credentials, emailId, markRead = false)",
                "} else {",
                "openEmail(credentials, emailId, markRead = false)",
                "}",
                "return rawSourceBytes(emailId, fetchRawSource(credentials, email, emailId, frozen))",
                "}",
            ),
            codeLines(body),
        )
    }

    /**
     * MUTATION: `fetchRawSource`'s default flipped from `FrozenNumbering.NothingFrozen` to
     * `FrozenNumbering.Frozen(null)`. One token, no call site touched, suite green.
     *
     * What it costs: the two callers that pass nothing are ordinary reads. `rawHeaders` would stop
     * being served by the raw-source cache AND — on IMAP — be REFUSED outright, because a tick that
     * froze nothing is refused at the wire. The headers of a message would simply stop appearing.
     *
     * The default is the one place this can be said. Every OTHER pin on this path reads the
     * argument at a CALL site, and these callers have no argument to read.
     */
    @Test fun `the fetch defaults to nothing frozen, in its signature`() {
        val signature = signatureOf("fetchRawSource")
        assertEquals(
            "fetchRawSource must default to NothingFrozen — 'no tick froze anything, fall back on " +
                "the folder's record', which is what every ordinary read needs. `Frozen(null)` " +
                "here means 'a tick froze nothing at all' and is REFUSED at the wire: the two " +
                "callers that pass no argument (rawHeaders, decryptMessage) would stop working, " +
                "and the cache would stop answering them. Signature was:\n$signature",
            listOf(
                "private suspend fun fetchRawSource(",
                "credentials: AccountCredentials,",
                "email: Email,",
                "emailId: String,",
                "frozen: FrozenNumbering = FrozenNumbering.NothingFrozen,",
                "): String {",
            ),
            codeLines(signature),
        )
    }

    /**
     * MUTATION: a `runCatching { … }` — or a `try/catch` — around the IMAP block of
     * `MailRepository.delete`. It reads as defensive, it compiles, and every line pinned elsewhere
     * stays byte-identical.
     *
     * What it costs is the worst outcome this branch has: `delete` is `trashSource`, the second
     * step of a move between accounts. Swallow its refusal and the step LOOKS like it succeeded,
     * so `crossAccountMove` reports [CrossAccountMove.Moved] — a permanent duplicate on account B,
     * announced to the user as a completed move, on the one path with no Undo. Both exceptions are
     * lost that way: `ImapNumberingUnconfirmed` (the server confirmed nothing) and
     * `ImapUidValidityChanged` (the folder WAS renumbered).
     *
     * So the rule is about ENCLOSURE, not about a line, and that is why no line-by-line list
     * could ever have caught it: [openBlocksAt] answers which blocks are still open over each
     * command that goes out under a numbering.
     *
     * Deliberately NOT `imap.moveBatch(`: the bulk mover is wrapped in a `runCatching` on
     * purpose, because it reports its refusals per id through `BulkResult.failed` instead of
     * throwing them at the caller. Adding it here would red the shipped code.
     */
    @Test fun `no IMAP command that carries a numbering sits inside a block that catches`() {
        val lines = DaoQuerySource.mailSource("MailRepository").lines()
        val command = Regex("""imap\.(move|fetchSource|deleteBatch)\(""")
        val numbering = Regex("(?i)(numbering|validity|frozen|recorded)")
        val sites = lines.indices.filter { i ->
            val line = lines[i].trim()
            if (line.startsWith("//") || line.startsWith("*")) false
            else command.containsMatchIn(line) && numbering.containsMatchIn(line)
        }
        assertEquals(
            "MailRepository no longer carries five IMAP commands opposing a numbering — the two " +
                "single-message movers, the bin, the batch destroy and the opposed FETCH. If a " +
                "path was added or removed, say so here:\n" +
                sites.joinToString("\n") { "${it + 1}: ${lines[it].trim()}" },
            5, sites.size,
        )
        sites.forEach { site ->
            val catching = openBlocksAt(lines, site).filter { "try" in it || "runCatching" in it }
            assertEquals(
                "the command on line ${site + 1} of MailRepository is inside a block that catches:\n" +
                    catching.joinToString("\n") +
                    "\nA swallowed refusal is worse than no guard here: on the move between " +
                    "accounts the bin would LOOK like it succeeded, crossAccountMove would report " +
                    "Moved, and the user would be told a duplicate on the other account was a " +
                    "completed move. There is no Undo on that path.",
                emptyList<String>(), catching,
            )
        }
    }

    /** The declaration lines of [function] — from its `fun` line to the line its body opens on.
     *  These signatures are written one parameter per line precisely so this can read them. */
    private fun signatureOf(function: String): String {
        val lines = DaoQuerySource.mailSource("MailRepository").lines()
        val start = lines.indexOfFirst { Regex("""\bfun $function\(""").containsMatchIn(it) }
        check(start >= 0) { "MailRepository declares no '$function' — did it get renamed?" }
        val end = (start until lines.size).first { lines[it].trimEnd().endsWith("{") }
        return lines.subList(start, end + 1).joinToString("\n")
    }
}

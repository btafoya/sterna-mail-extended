package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every destroy written with the word `delete` in `ImapMailService` is the ONE guarded expunge of
 * `deleteBatch`, and no other.
 *
 * [DestroyNeedsTheNumberingStatedNowTest] proves that `deleteBatch` refuses an expunge the server
 * will not restate the numbering of; it proves nothing about a SECOND door. And there was one:
 *
 *     suspend fun deleteMessage(credentials: AccountCredentials, mailboxId: String, uid: Long) =
 *         onMailbox(credentials, mailboxId) { session, _ -> session.delete(uid) }
 *
 * — `onMailbox` with the status dropped on the floor (`session, _`), no frozen number taken, none
 * opposed. `onMailbox` then selects under `expectedUidValidity ?: recorded`, i.e. compares the
 * folder's current number with itself, concludes SAME, and expunges by UIDs read under a numbering
 * the server may have thrown away since — in the INBOX itself on an account with no Trash. That is
 * the #99 defect entire, in a function nobody called: it had no caller when it was removed, so it
 * destroyed nothing, but it compiled, it was public, and the first caller to find it would have
 * inherited exactly the destroy this branch and 1.4.9 exist to refuse.
 *
 * So the rule is not "deleteMessage must not come back" — a new name would satisfy that while
 * doing the same thing. It is a CLOSED LIST over the file: whatever `delete` this file writes must
 * be the one guarded line of `deleteBatch`, verbatim. Whole lines, never substrings — a rule that
 * searches inside a line is blind to anything appended to it.
 *
 * ⛔ What this rule does NOT say, and must not be read as saying: that the file destroys in one
 * place. It pins the destroys spelled `delete`, and nothing else. `setFlag` is public, takes an
 * arbitrary flag, drops the SELECT's status the same way, and an `imap.setFlag(…, "\\Deleted", …)`
 * would mark for destruction under no opposed numbering at all — reachable, unguarded, and named
 * nothing like `delete`. So is `deleteFolder`, which does not go through `onMailbox` at all. Both
 * predate this branch, neither has a destructive caller today, and both are filed rather than
 * fixed here.
 *
 * ⭐ Source text, and here the last-resort clause does not apply the usual way: this file's own
 * neighbour [DestroyNeedsTheNumberingStatedNowTest] instantiates the real [ImapMailService] on the
 * JVM and drives it onto a scripted socket, so "not instantiable" would be false. The reason is
 * simpler and it is total: an ABSENT function cannot be executed. Nothing can call a door that is
 * not there, so nothing but the source text can state that it is not there — while the DECISION
 * the surviving line asks, `UidValidity.mayDestroyUnderStatedNumbering`, is executed against a
 * real socket in that neighbour and against its own inputs in [UidValidityDecisionTest].
 */
class ImapServiceDestroysInOnePlaceTest {

    /** The code lines of [source] matching [needle] — comments dropped. Whole lines: the assertion
     *  compares them, never searches inside them. */
    private fun codeLinesNaming(source: String, needle: Regex): List<String> =
        source.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle.containsMatchIn(it) }

    private fun codeLinesNaming(source: String, needle: String): List<String> =
        codeLinesNaming(source, Regex(Regex.escape(needle)))

    /**
     * ⚠ The needle is the WORD, not the call. `delete(` — a name glued to a parenthesis — is a
     * filter on one spelling of one syntax, and the shortest way round it is the overload
     * `core/imap` deliberately keeps (`ImapClient.delete(uid: Long)`, `ImapClient.kt:910`):
     *
     *     onMailbox(credentials, mailboxId) { session, _ ->
     *         val erase: (Long) -> Unit = session::delete
     *         erase(uid)
     *     }
     *
     * Not one line there contains `delete(`. Same for `session.delete (uid)`, with a space. The
     * word boundary catches the method reference, the spaced call and the plain call alike, and it
     * does NOT open the list: `\bdelete\b` cannot match inside `deleteBatch`, `deleteFolder`,
     * `deleteMessage` or `deleteDraft`, because a letter follows. Measured on the shipped file
     * rather than assumed — the needle returns exactly one code line, the one pinned below.
     */
    @Test fun `every delete the service writes is the one guarded expunge`() {
        val source = DaoQuerySource.mailSource("ImapMailService")
        val guarded = listOf("if (mayDestroy) session.delete(uids)")
        val why =
            "the ONLY `delete` ImapMailService may write is `deleteBatch`'s, guarded by the " +
                "numbering the server just stated. Any other one is an expunge that opposes " +
                "NOTHING: `onMailbox` falls back on `expectedUidValidity ?: recorded`, matches the " +
                "folder's current numbering against itself, and erases UIDs that may now name " +
                "other, live messages (#99). A second door need not be called to be a defect — it " +
                "is public, it compiles, and it hands the first caller the destroy this branch " +
                "refuses everywhere else. ⛔ This says nothing about destroys NOT spelled " +
                "`delete`: `setFlag(…, \"\\\\Deleted\", …)` and `deleteFolder` are unopposed too, " +
                "and are filed, not covered here."
        // The word, which catches `session::delete` and `session.delete (uid)` as well.
        assertEquals(why, guarded, codeLinesNaming(source, Regex("""\bdelete\b""")))
        // And the call as it is written today, so the surviving line cannot drift into some other
        // shape while the rule above stays satisfied by it.
        assertEquals(why, guarded, codeLinesNaming(source, "session.delete("))
    }
}

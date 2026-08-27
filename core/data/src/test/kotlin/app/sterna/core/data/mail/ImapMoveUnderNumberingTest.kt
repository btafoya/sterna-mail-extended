package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the move's numbering guard is PLUGGED IN — the source-text half of
 * [MoveNeedsTheNumberingStatedNowTest], and the twin of the wiring half of
 * [ImapDestroyUnderNumberingTest] (Codeberg #99).
 *
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. It exists because `MailRepository` needs Room and a
 * `Context`, so nothing in this module can run it; the DECISION it guards is executed next door,
 * on a real [ImapMailService] driven onto a socket. What no executable test in this module can
 * say is whether the five call sites still HAND the guard anything: dropping the argument
 * compiles — `ImapMailService.move` defaults it to null — and `onMailbox` then falls back on
 * `expectedUidValidity ?: recorded`, so the SELECT compares the folder's current number with
 * itself, concludes SAME, and the `UID MOVE` goes out on UIDs that may name other messages. That
 * is the defect entire, and it is silent, legal Kotlin.
 *
 * Everything is pinned as WHOLE LINES compared as a LIST, never `in` / `contains`: a substring
 * rule is blind to anything appended to what it matched, and "one more argument" is exactly the
 * shape of the mutations that matter here.
 */
class ImapMoveUnderNumberingTest {

    private fun bodyOf(function: String): String =
        DaoQuerySource.mailFunctionBody("MailRepository", function)

    /** The code lines of [body] naming [needle] — comments dropped, whitespace normalised. Whole
     *  lines: the assertions compare them, never search inside them. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeLinesOf(body).filter { needle in it }

    /**
     * The same, for a needle that has to catch SYNONYMS.
     *
     * ⭐ Why it exists, found by a mutation that survived the whole suite: inserting
     * `val expectedUidValidity = expectedUidValidity ?: recordedNumbering(credentials.id, source)`
     * into `imapMoveGroup` reopens the defect entire — every batch then travels under the number
     * read AT EXECUTION, the one a background pass recorded AFTER the renumbering, which the guard
     * compares with itself. And it slipped under a rule pinned to the string
     * `"recordedUidValidity"`, because `MailRepository.recordedNumbering` is the SAME accessor
     * under another name, and Kotlin's shadowing left the `imap.moveBatch(…)` line byte-identical.
     * So the forbidden thing is stated as what it IS — a numbering read, whatever it is called.
     */
    private fun codeLinesNaming(body: String, needle: Regex): List<String> =
        codeLinesOf(body).filter { needle.containsMatchIn(it) }

    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /**
     * Any mention of a numbering, under any spelling: `recordedUidValidity`, `recordedNumbering`,
     * `expectedUidValidity`, a local called `numbering`. Rules using it pin the WHOLE list of
     * lines that match, so the legitimate ones are named and a new one cannot slip in.
     *
     * ⭐ `validity|frozen|recorded` and not just `numbering|uidvalidity`: the needle has to catch
     * the value under a name nobody has thought of yet, because the rules built on it are
     * closed lists and a line that does not MATCH is a line no list can miss. A `validityOf(`
     * accessor, a `frozenValidity` field, a `val frozen = …` local re-read at execution — each
     * reopens the defect and each slipped past the narrow needle. Verified not to widen any of
     * the closed lists below: every guarded body matches exactly the same lines it did before.
     */
    private val anyNumbering = Regex("(?i)(numbering|validity|frozen|recorded)")

    // ---- the three single-message movers ------------------------------------------------------

    /**
     * `archive`, `moveToMailbox` and `delete` each read the numbering ON THE SPOT, for the folder
     * the message is in RIGHT NOW — which is also the folder the move will SELECT. There is no
     * window to close here, unlike the draft paths: the id is read from the cache and the move
     * goes out in the same call, with no user confirmation and no worker in between.
     *
     * ⛔ The read is the repository's own [MailRepository.recordedUidValidity], never
     * `imap.recordedUidValidity(` — a closed-list rule in [ImapDestroyUnderNumberingTest] ("the
     * accessor really asks IMAP for the folder's numbering") pins that string to exactly three
     * lines of the file, and it does not widen.
     *
     * ⚠ And the folder it names must be the SOURCE, `mb`. Naming the destination instead reads as
     * a typo and is the worst of both worlds: the destination's numbering is compared with the
     * source's stated one, so the move is refused whenever they differ and licensed whenever two
     * unrelated folders happen to share a number.
     */
    @Test fun `the single-message movers oppose the source folder's recorded numbering`() {
        val expected = mapOf(
            "archive" to "val landed = imap.move(credentials, mb, uid, dest, recordedUidValidity(credentials, mb))",
            "moveToMailbox" to
                "val landed = imap.move(credentials, mb, uid, targetMailboxId, recordedUidValidity(credentials, mb))",
            "delete" to "val landed = imap.move(credentials, mb, uid, trash, recordedUidValidity(credentials, mb))",
        )
        expected.forEach { (function, line) ->
            val body = bodyOf(function)
            assertEquals(
                "$function must oppose the numbering recorded for the SOURCE folder to what the " +
                    "server states in the SELECT it is about to move under. Without the argument " +
                    "the call still compiles and still moves — it simply confirms nothing. " +
                    "Body was:\n$body",
                listOf(line),
                codeLinesNaming(body, "imap.move("),
            )
        }
    }

    /**
     * The undo of a move is a move, and it is the one that puts the message BACK: it selects the
     * folder the forward move landed it in, and the UIDs it carries are the ones `COPYUID`
     * reported then. A renumbering between the two — or a server that simply stops stating a
     * numbering — and those UIDs name whatever the destination folder now holds.
     *
     * ⭐ And it opposes that folder's numbering FROM THE FREEZE, never from a read. The first
     * delivery read it (`recordedUidValidity(credentials, currentFolder)`) and the read cannot
     * work: a `UID MOVE` does not SELECT its destination, and nothing else in this flow does
     * either, so on a Trash the user has never opened the read answers null and Undo after an
     * ordinary delete is REFUSED. What is opposed is what `COPYUID` stated when it handed back
     * these very UIDs, carried in `lastImapMove` — see
     * [UndoMovesUnderTheNumberingCopyUidStatedTest], which runs it.
     */
    @Test fun `the restore opposes the frozen numbering of the folder it takes the mail out of`() {
        val body = bodyOf("restoreAll")
        assertEquals(
            "restoreAll must oppose the CURRENT folder's numbering — the one it SELECTs — not " +
                "the source it is restoring to. ⛔ And it must NAME THE TWO FOLDERS IN THIS CALL, " +
                "as `route.currentFolder` / `route.sourceFolder`: they are interchangeable " +
                "Strings, so swapping them compiles, and it then SELECTs the inbox while moving " +
                "Trash-side UIDs into the Trash — live inbox mail thrown away wherever the two " +
                "folders share a numbering. Laundered through `val currentFolder = " +
                "route.currentFolder` the swap touches no watched line and the whole suite stays " +
                "green; named here, it changes this pinned line. Body was:\n$body",
            listOf(
                "imap.moveBatch(credentials, route.currentFolder, route.uids, route.sourceFolder, " +
                    "route.frozenUidValidity)",
            ),
            codeLinesNaming(body, "imap.moveBatch("),
        )
        assertEquals(
            "and the re-fetch of the restored rows must read them from the folder they went home " +
                "to, named the same way. `route.currentFolder` here fetches UIDs of one folder in " +
                "another: nothing comes back, the restored rows are never re-cached nor marked " +
                "`recentLocalMoves`, they vanish from the list until the next full sync — and the " +
                "notifier may then announce them as new mail. Body was:\n$body",
            listOf("runCatching { imap.fetchByUids(credentials, route.sourceFolder, newUids) }"),
            codeLinesNaming(body, "imap.fetchByUids("),
        )
        assertEquals(
            "and everything the move back REPORTS must be read on these two lines, whole: " +
                "`it.first in moved.uids` credits the ids that came home (turn it into " +
                "`moved.uids.values` and nothing is ever credited — 'restore failed' on a restore " +
                "that worked), and `.values` is where the mail is NOW (turn it into `.keys` and " +
                "the re-fetch asks for the UIDs it has just left). Neither mutation touches any " +
                "other line of this function. Body was:\n$body",
            listOf(
                "restored += route.messages.filter { it.first in moved.uids }.map { it.second }",
                "val newUids = moved.uids.values.toList()",
            ),
            codeLinesNaming(body, "moved."),
        )
        assertEquals(
            "restoreAll must mention a numbering on exactly these two lines: the routing decision " +
                "it EXECUTES, and the move that hands the frozen number to the wire. Any other, " +
                "under ANY name, is a read at execution — and here a read is not merely stale, it " +
                "answers NULL for every folder the user never opened, which refuses every Undo of " +
                "a delete on such an account. Body was:\n$body",
            listOf(
                "val routes = UidValidity.imapUndoRoutes(targets.map { it.emailId to it.sourceMailboxId }) " +
                    "{ lastImapMove[it] }",
                "imap.moveBatch(credentials, route.currentFolder, route.uids, route.sourceFolder, " +
                    "route.frozenUidValidity)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
    }

    /**
     * ⭐ THE UNDO ABSORBS A REFUSED MOVE-BACK, AND THE APP LAYER DEPENDS ON IT WITHOUT SAYING SO.
     *
     * `restoreAll` wraps each move-back in `runCatching { … }.getOrNull() ?: return@forEach`, so a
     * refusal — the server stating no numbering for the folder the mail sits in
     * (`ImapNumberingUnconfirmed`) — leaves that route unrestored and comes back to the caller as
     * an id in the RETURNED set, never as an exception. That is the whole reason
     * `InboxViewModel.undo()` shows the translated `status_restore_failed` and not the refusal's
     * own English sentence.
     *
     * ⛔ Removing the wrapper reads like a cleanup — the return value already says what failed, so
     * why swallow — and it is the one edit that turns a partly-refused Undo into a whole-screen
     * lie: `undo()`'s `onFailure` fills the red `SyncErrorBanner` over the list, so ONE route
     * giving up would report the entire Undo as failed, over the routes that came home. The
     * per-route count and the `status_restore_failed` snackbar it feeds are the truthful report,
     * and they exist only because the refusal stops here. (The banner's wording is `main`'s
     * business since `afbfcef7` — it resolves a string resource now, so the refusal's own English
     * sentence can no longer reach it either. That is a second lock on one of the two doors, not
     * a reason to unlock this one.)
     *
     * Both absorptions are pinned, IMAP and JMAP, as a closed list: they are the same decision on
     * the two protocols and dropping either one has the same consequence.
     */
    @Test fun `a move-back that is refused stops here, and never reaches the banner`() {
        val body = bodyOf("restoreAll")
        assertEquals(
            "restoreAll no longer wraps the move-back it sends: a refusal on ONE route now " +
                "travels up to InboxViewModel.undo()'s onFailure, which draws the red " +
                "SyncErrorBanner over the list — the whole Undo reported as failed, on top of " +
                "the routes that did come home. Body was:\n$body",
            listOf("val moved = runCatching {"),
            codeLinesNaming(body, "val moved ="),
        )
        assertEquals(
            "and both protocols must absorb it the same way — one route (or one folder's group) " +
                "gives up, the others still travel, and the ids that did not come home are the " +
                "RETURN VALUE. Body was:\n$body",
            listOf("}.getOrNull() ?: return@forEach", "}.getOrNull() ?: return@forEach"),
            codeLinesNaming(body, "getOrNull()"),
        )
    }

    /**
     * ⭐ WHAT IS FROZEN, at the four sites that write an Undo landing: the numbering the move's own
     * `COPYUID` stated for the DESTINATION — `landed.destinationUidValidity`, and nothing else.
     *
     * A closed list over the whole file, because the mistake this catches is a NEW call site: the
     * three plausible wrong fills all compile and all read fine — `null` (the Undo is refused for
     * good), the source folder's `recordedUidValidity(credentials, mb)` (two unrelated folders'
     * numbers compared, so the Undo is licensed exactly when they happen to collide), and a read
     * of the destination's recorded number (null on any folder never SELECTed, which is the
     * defect this branch exists to fix).
     *
     * ⛔ Anchored on the WRITE, `lastImapMove[`, not on the type `ImapLoc(`: a fifth site written
     * as `lastImapMove[id] = loc.copy(uidValidity = somethingElse)` names no constructor and would
     * have walked straight through a list keyed on the type without changing a single pinned line.
     * The map is what the Undo reads, so every mention of the map is what the list must cover —
     * including its one READ, in `restoreAll`, pinned here with the writes.
     */
    @Test fun `every undo landing freezes the numbering COPYUID stated for the destination`() {
        val source = DaoQuerySource.mailSource("MailRepository")
        assertEquals(
            "an Undo landing was written with something other than the destination numbering the " +
                "move itself reported, or a new site touches `lastImapMove` unseen. That value is " +
                "the ONLY statement of the destination's numbering the app ever gets: a UID MOVE " +
                "does not SELECT its destination.",
            listOf(
                "lastImapMove[emailId] = ImapLoc(dest, it, landed.destinationUidValidity)",
                "lastImapMove[emailId] = ImapLoc(targetMailboxId, it, landed.destinationUidValidity)",
                "lastImapMove[id] = ImapLoc(dest, it, landed.destinationUidValidity)",
                "val routes = UidValidity.imapUndoRoutes(targets.map { it.emailId to it.sourceMailboxId }) " +
                    "{ lastImapMove[it] }",
                "lastImapMove[emailId] = ImapLoc(trash, it, landed.destinationUidValidity)",
            ),
            codeLinesNaming(source, "lastImapMove["),
        )
    }

    // ---- the batch mover, and the three bulk gestures that feed it ----------------------------

    /**
     * On the batch path the numbering ARRIVES; it is not read here. The bulk gestures are the ones
     * with something in between — a selection the user confirmed, a `resetSyncState()`+`refresh()`
     * on the way — so the value belongs to the moment of the GESTURE, and a read at execution
     * would answer the number a refresh recorded after the renumbering. Same rule, and the same
     * reason, as `imapDestroyGroup`.
     */
    @Test fun `the batch move takes the numbering it is handed, and reads none of its own`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup must hand `moveBatch` the numbering it received. Body was:\n$body",
            listOf("runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest, expectedUidValidity) }"),
            codeLinesNaming(body, "imap.moveBatch("),
        )
        assertEquals(
            "imapMoveGroup must mention a numbering on exactly these two lines — the one that " +
                "hands the parameter to the wire, and the one that FREEZES what the move itself " +
                "reported about the destination (not a read: the server stated it in the same " +
                "response as the UIDs). Any other, under ANY name (`recordedUidValidity`, its " +
                "synonym `recordedNumbering`, a shadowing `val expectedUidValidity = … ?: …`), is " +
                "a read AT EXECUTION: it answers the number a background pass recorded AFTER the " +
                "renumbering, the guard compares it with itself, concludes SAME, and the UID MOVE " +
                "goes out on UIDs that name other, live messages. Body was:\n$body",
            listOf(
                "runCatching { imap.moveBatch(credentials, source, uidToId.keys.toList(), dest, expectedUidValidity) }",
                "lastImapMove[id] = ImapLoc(dest, it, landed.destinationUidValidity)",
            ),
            codeLinesNaming(body, anyNumbering),
        )
        assertEquals(
            "and it must READ no numbering at all — the closed list above names two lines, and " +
                "neither may become a lookup. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, Regex("recorded(UidValidity|Numbering)")),
        )
    }

    /**
     * ⭐ And the refusal must feed `failed` WITHOUT a catch of its own. `ImapNumberingUnconfirmed`
     * is not an `ImapException`, but `runCatching` catches `Throwable`, so the refused group lands
     * in `failed` exactly as a dead socket does — and `failed` is what makes the caller re-query,
     * so the messages come back instead of vanishing from a list they never left on the server.
     *
     * ⛔ This is also why no `catch (unconfirmed: ImapNumberingUnconfirmed)` may be added here: a
     * closed-list rule in [ImapDestroyUnderNumberingTest] holds the file to exactly one, on the
     * draft path, and a second one written here would swallow the refusal on the destroy path too.
     */
    @Test fun `a refused batch move fails its ids instead of counting them moved`() {
        val body = bodyOf("imapMoveGroup")
        assertEquals(
            "the whole group must land in `failed` when the move does not go out. Body was:\n$body",
            listOf(".onFailure { failed += uidToId.values }"),
            codeLinesNaming(body, ".onFailure"),
        )
        assertEquals(
            "and nothing may be counted as succeeded outside the success branch — nor, INSIDE " +
                "it, beyond what the move proved left the source (`MoveCreditsOnlyWhatLeftTheSource" +
                "Test`): an OK says the command ran, and a UID MOVE on a uid that is already gone " +
                "is a no-op the server accepts. Body was:\n$body",
            listOf("succeeded += outcome.gone"),
            codeLinesNaming(body, "succeeded +="),
        )
    }

    /**
     * The three bulk gestures, each handing its group's own numbering down. The key is the
     * `groupBy` key — the source folder as the id encodes it — so a selection spanning two folders
     * opposes two different numbers, which is the only correct answer: a UIDVALIDITY is a property
     * of ONE folder.
     *
     * ⛔ `expectedUidValidity[dest]` or a single `Long?` for the whole selection would read as a
     * simplification and be wrong in the same way for every multi-folder selection.
     */
    @Test fun `each bulk gesture hands the batch its own source folder's numbering`() {
        val expected = mapOf(
            "archiveAll" to "else -> imapMoveGroup(credentials, source, ids, dest, succeeded, failed, expectedUidValidity[source])",
            "moveAllToMailbox" to
                "else -> imapMoveGroup(credentials, source, ids, targetMailboxId, succeeded, failed, expectedUidValidity[source])",
            "deleteAll" to "else -> imapMoveGroup(credentials, source, ids, trash, succeeded, failed, expectedUidValidity[source])",
        )
        expected.forEach { (function, line) ->
            val body = bodyOf(function)
            assertEquals(
                "$function must hand imapMoveGroup the numbering frozen for THAT source folder. " +
                    "Body was:\n$body",
                listOf(line),
                codeLinesNaming(body, "imapMoveGroup("),
            )
            assertEquals(
                "$function must mention a numbering on exactly that one line and no other, under " +
                    "ANY name: it is called from a ViewModel that froze one at the gesture, and " +
                    "any read here answers the number a refresh recorded AFTER the renumbering. " +
                    "Body was:\n$body",
                listOf(line),
                codeLinesNaming(body, anyNumbering),
            )
        }
    }

    /**
     * ⭐ THE BLIND SPOT that got the first delivery rejected: the rules above each look at a mover
     * they already know about, so a mover called from somewhere ELSE was simply never looked at.
     * `reportSpamAll` and `notSpamAll` are `moveAllToMailbox` under another name and passed it
     * nothing — the default `emptyMap()` gives `null` per folder, `mayDestroyUnderStatedNumbering`
     * answers false, and the selection bar's spam button failed 100 % of the time on IMAP with
     * `markReadOnMoveOutOfInbox` having already flagged the messages `\Seen` on the server.
     *
     * So: every call of a bulk mover ANYWHERE in the repository, pinned whole, as a closed list.
     */
    @Test fun `every caller of a bulk mover inside the repository hands the numbering on`() {
        val calls = codeLinesNaming(
            DaoQuerySource.mailSource("MailRepository"),
            // Not preceded by a dot: `emailBodyDao.deleteAll()` is a table wipe, not a mover.
            Regex("""(?<![.\w])(archiveAll|moveAllToMailbox|deleteAll|reportSpamAll|notSpamAll)\("""),
        ).filterNot { it.startsWith("suspend fun ") }
        assertEquals(
            "a bulk mover is called inside MailRepository without handing its numbering on. On " +
                "IMAP every one of them ends in a UID MOVE, and one that opposes nothing is " +
                "REFUSED — the gesture fails whole, it does not degrade.",
            listOf(
                "return moveAllToMailbox(credentials, emailIds, junk, expectedUidValidity)",
                "return moveAllToMailbox(credentials, emailIds, inbox, expectedUidValidity)",
            ),
            calls,
        )
    }

    /**
     * The parameter itself, pinned on each declaration — the map must stay a map keyed by source
     * folder, and it must stay OPTIONAL only in the sense of defaulting to "nothing frozen", which
     * refuses. A default of anything else would be a licence.
     */
    @Test fun `the bulk gestures take a per-source-folder numbering, defaulting to nothing`() {
        listOf("archiveAll", "moveAllToMailbox", "deleteAll", "reportSpamAll", "notSpamAll").forEach { function ->
            val signature = signatureOf(function)
            assertEquals(
                "$function must take the numbering as a map keyed by SOURCE FOLDER: one number " +
                    "for a selection spanning two folders is one of them opposed to the other. " +
                    "The default is 'nothing frozen', which licenses nothing — any other default " +
                    "is a licence. Signature was:\n$signature",
                listOf("expectedUidValidity: Map<String, Long?> = emptyMap(),"),
                codeLinesNaming(signature, "expectedUidValidity"),
            )
        }
        val signature = signatureOf("imapMoveGroup")
        assertEquals(
            "imapMoveGroup takes ONE folder's number, and takes it without a default: it is " +
                "private, its three callers all have one to hand, and a default here is a call " +
                "site that silently stops opposing anything. Signature was:\n$signature",
            listOf("expectedUidValidity: Long?,"),
            codeLinesNaming(signature, "expectedUidValidity"),
        )
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

package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, and a narrow one: neither `InboxViewModel` nor `MessageDestroyWorker` can be
 * instantiated in a JVM test (Application, WorkManager, Room), so nothing here runs. It pins the
 * ARGUMENTS of the hand-over the folder check of Codeberg #122 rides on, whole line by whole line
 * — `contains` would be blind to any mutation that lengthens the line.
 *
 * The chain: the ViewModel knows which folder each message was in when the user confirmed →
 * the worker carries that folder in its `Data` → `destroyAll` refuses to destroy anything the
 * server no longer reports in exactly that folder. Break any link and a message another client
 * rescued during the hold-back is destroyed in its new folder.
 *
 * The SECOND thing it pins is the IMAP half of the same hand-over (Codeberg #99): the numbering
 * (UIDVALIDITY) each message was READ UNDER travels the same way, frozen once in the ViewModel
 * and replayed as-is. A worker that re-reads the numbering at execution time opposes the folder's
 * CURRENT numbering to itself, the guard concludes "same", and a folder renumbered during the
 * hold-back is expunged by UIDs that now name other messages. Nothing executable can see that
 * mutation — only these whole-line comparisons can.
 *
 * ⭐ Since this branch the frozen value comes from THE ROWS, not from the folder: `emails` carries
 * the numbering the SELECT that enumerated it stated, and the folder's record is realigned by any
 * background pass that meets a renumbering while those rows stay on screen. So the absence pinned
 * below — no `recordedUidValidity` anywhere on this path — is as load-bearing as the presences.
 * ⛔ What the split then DOES with those numbers is not pinned here but EXECUTED, in
 * `UidValidityDestroyRoutesTest`: a decision only read as text is a decision a mutation walks
 * through.
 */
class HeldBackDestroyCarriesItsFolderTest {

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The block body of `fun [name]` in [file] — braces included. */
    private fun bodyOf(file: File, name: String): String {
        val source = file.readText()
        val fn = Regex("""\bfun\s+$name\s*\(""").find(source)
            ?: error("${file.name} has no function named '$name' — did it get renamed?")
        val open = source.indexOf('{', fn.range.last)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
            i++
        }
        error("Unbalanced braces in ${file.name}.$name")
    }

    @Test fun theWorkerHandsTheDestroyTheFolderItWasEnqueuedWith() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "the worker must read the expected folder out of its own Data",
            listOf("val mailboxId = inputData.getString(KEY_MAILBOX_ID)"),
            codeLinesNaming(body, "KEY_MAILBOX_ID"),
        )
        assertEquals(
            "the worker must hand that folder AND that numbering to destroyAll — without them " +
                "the destroy can tell neither a rescued message from one still in the Trash " +
                "(#122) nor a renumbered folder from the one confirmed (#99)",
            listOf(
                "if (ids.isNotEmpty() && repo.destroyAll(credentials, ids, mailboxId, uidValidity)" +
                    ".failed.isNotEmpty()) {",
            ),
            codeLinesNaming(body, "repo.destroyAll("),
        )
    }

    /**
     * M2's only executioner. The numbering must come out of THIS REQUEST'S `Data` — frozen when
     * the user confirmed. Reading it from the repository here (`repo.recordedUidValidity(...)`)
     * would hand the guard the folder's numbering as it is at execution time, which the SELECT
     * then matches by construction: a folder renumbered during the hold-back would be expunged
     * with no failure at all, since an ordinary refresh has already recorded the new number.
     */
    @Test fun theWorkerOpposesTheNumberingItWasENQUEUED_withNotTheCurrentOne() {
        val body = bodyOf(WORKER, "doWork")
        assertEquals(
            "the worker must read the frozen numbering out of its own Data, never ask for it",
            listOf("val uidValidity = inputData.getLong(KEY_UID_VALIDITY, 0L).takeIf { it > 0L }"),
            codeLinesNaming(body, "KEY_UID_VALIDITY"),
        )
        assertEquals(
            "doWork must not look the numbering up at execution time — that is the defect",
            emptyList<String>(),
            codeLinesNaming(body, "recordedUidValidity"),
        )
    }

    @Test fun everyEnqueuedRequestCarriesTheFolderOfTheIdsItHolds() {
        val body = bodyOf(WORKER, "destroyRequests")
        assertEquals(
            "each request's Data must carry the ids of THAT chunk",
            listOf("KEY_EMAIL_IDS to chunk.toTypedArray(),"),
            codeLinesNaming(body, "KEY_EMAIL_IDS"),
        )
        assertEquals(
            "each request's Data must carry the folder of the ids in THAT request",
            listOf("KEY_MAILBOX_ID to folder.mailboxId,"),
            codeLinesNaming(body, "KEY_MAILBOX_ID"),
        )
        assertEquals(
            "and the numbering THAT folder was under when the user confirmed — 0 when there is " +
                "none, which destroys nothing rather than destroying unopposed (#99)",
            listOf("KEY_UID_VALIDITY to (folder.uidValidity ?: 0L),"),
            codeLinesNaming(body, "KEY_UID_VALIDITY"),
        )
    }

    @Test fun theViewModelGroupsTheHoldBackByFolderNotOnlyByAccount() {
        val held = bodyOf(INBOX_VIEW_MODEL, "heldBackDestroy")
        assertEquals(
            "the hold-back must record the folder each message sat in when the user confirmed",
            listOf("val targets = emails.mapNotNull { e -> credentialsFor(e)?.let { Triple(it, e.id, e.mailboxId.orEmpty()) } }"),
            codeLinesNaming(held, "credentialsFor(e)"),
        )
        assertEquals(
            "the scheduled destroy must be grouped by account AND folder, and carry the frozen numbering",
            listOf(
                "MessageDestroyWorker.schedule(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering), " +
                    "PURGE_HOLD_BACK_MS)",
            ),
            codeLinesNaming(held, "MessageDestroyWorker.schedule("),
        )
        assertEquals(
            "flushing a superseded hold-back destroys the same set — it must carry the same folders " +
                "and the numbering ALREADY captured, not a fresh one",
            listOf("MessageDestroyWorker.flushNow(getApplication(), accountId, foldersToDestroy(accountId, rows, numbering))"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy"), "MessageDestroyWorker.flushNow("),
        )
    }

    /**
     * The freeze itself: read ONCE, in the hold-back, before the destroy is enqueued. `flushNow`
     * replays the same set and cannot re-read it (it is not a suspending function) — so the
     * numbering has to be kept beside the targets, and a flush that looked it up again would
     * hand the destroy the numbering of the very renumbering it must refuse.
     *
     * ⭐ And WHERE it is read from is now half the point. The numbering must come off THE ROWS —
     * the stamp each cached row was written with by the SELECT that enumerated it — and never off
     * the FOLDER: `mailbox_uidvalidity` is realigned by any background pass that meets a
     * renumbering (`ImapMailService.loadWatchedFolders` → `UidValidityStore.invalidate`) while the
     * ids on screen still belong to the old numbering, and nothing prunes those rows on IMAP. The
     * folder's number opposed to itself reads SAME, and the expunge falls on other messages. That
     * was the defect this branch closes; the rule the routing then applies is executed, not read,
     * in `UidValidityDestroyRoutesTest`.
     */
    @Test fun theViewModelFreezesTheNumberingWithTheConfirmationAndReplaysIt() {
        assertEquals(
            "the hold-back must capture the numbering of the messages it is about, once",
            listOf("val numbering = recordedNumbering(targets)"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "heldBackDestroy"), "recordedNumbering("),
        )
        assertEquals(
            "and it must ask the repository for the numbering EACH ROW WAS READ UNDER, per account",
            listOf(
                "repo.numberingRowsWereReadUnder(credentials, emailIds).map { (emailId, uidValidity) -> " +
                    "(credentials.id to emailId) to uidValidity }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "recordedNumbering"), "repo.numberingRowsWereReadUnder("),
        )
        val flush = bodyOf(INBOX_VIEW_MODEL, "flushPendingDestroy")
        assertEquals(
            "the flush must replay the numbering captured at the confirmation, then clear it " +
                "with the targets it belongs to",
            listOf("val numbering = pendingDeleteNumbering", "pendingDeleteNumbering = emptyMap()"),
            codeLinesNaming(flush, "pendingDeleteNumbering"),
        )
        assertEquals(
            "and must never re-read it: what it would read is the numbering of the renumbering",
            emptyList<String>(),
            codeLinesNaming(flush, "recordedUidValidity"),
        )
        assertEquals(
            "each request must be one route of the pure split — one folder, one numbering, its ids " +
                "in order — translated and nothing more",
            listOf("return routes.map { MessageDestroyWorker.FolderDestroy(it.mailboxId, it.emailIds, it.uidValidity) }"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "foldersToDestroy"), "FolderDestroy("),
        )
        assertEquals(
            "and the split itself must be the pure function, keyed by folder AND numbering: a " +
                "grouping written here is a decision no test can run",
            listOf(
                "val routes = UidValidity.imapDestroyRoutes(rows.map { it.second to it.third }) { " +
                    "numbering[accountId to it] }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "foldersToDestroy"), "imapDestroyRoutes("),
        )
    }

    /**
     * ⛔ THE REGRESSION THIS BRANCH CLOSES, stated as an absence.
     *
     * `repo.recordedUidValidity(...)` answers what `mailbox_uidvalidity` holds for a FOLDER right
     * now. Reading it anywhere on the destroy path puts the defect back whole: a background pass
     * that met the renumbering has already written the new number there, the SELECT then matches
     * it by construction, and `UID STORE +FLAGS (\Deleted)` + `UID EXPUNGE` go out on UIDs that
     * name other, live messages — with no failure anywhere and the rows already off the screen.
     *
     * Two functions, because the read can come back into either: the hold-back that freezes, and
     * the freeze itself. Nothing else on this path may ask the folder anything. (The MOVE path
     * still reads it, deliberately — a move is reversible and has its own fiche; it is why this
     * assertion is scoped to these two bodies and not to the file.)
     */
    @Test fun theDestroyPathNeverAsksTheFOLDERForItsNumbering() {
        listOf("heldBackDestroy", "recordedNumbering").forEach { function ->
            assertEquals(
                "`$function` must not read the folder's current numbering — that is the value a " +
                    "renumbering has already realigned, and opposing it to itself licenses the " +
                    "expunge this whole chain exists to refuse",
                emptyList<String>(),
                codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, function), "recordedUidValidity"),
            )
        }
    }

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"
        private const val WORKER_PATH = "$APP_SOURCES/app/sterna/mail/MessageDestroyWorker.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        private val WORKER: File by lazy { File(root, WORKER_PATH) }
    }
}

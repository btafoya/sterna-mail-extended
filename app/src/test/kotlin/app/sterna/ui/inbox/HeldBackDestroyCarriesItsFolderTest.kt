package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** The code lines of [body], comments and blanks dropped, whitespace normalised. */
    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }

    /** The code lines of [body] naming [needle], comments dropped. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /**
     * The order of two WHOLE code lines of [lines], with BOTH required to exist.
     *
     * ⛔ Why this is not an `indexOf` on the raw text, which is what every one of these rules used
     * to be: `String.indexOf` answers -1 for a needle that has VANISHED, and `-1 < n` is true for
     * ever. The assertion then stops asserting without ever going red — the rule dies silently at
     * the exact moment the line it guards is renamed or removed, which is the moment it is needed.
     * Whole lines, and the index demanded, so a disappearance is named in the message.
     */
    private fun assertLineOrder(what: String, lines: List<String>, first: String, then: String) {
        val i = lines.indexOf(first)
        val j = lines.indexOf(then)
        assertTrue("$what — no code line reads exactly <$first>; the rule guards nothing", i >= 0)
        assertTrue("$what — no code line reads exactly <$then>; the rule guards nothing", j >= 0)
        assertTrue("$what — <$first> (line $i) must come BEFORE <$then> (line $j)", i < j)
    }

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

    /**
     * ⛔ THE UPSTREAM HALF OF #99: a destruction may only touch a line that is STILL THE LINE THAT
     * WAS TICKED.
     *
     * A selection holds KEYS, and an IMAP key (`imap:<account>:<folder>:<uid>`) carries the bare
     * UID with no numbering in it. A server that renumbers the shifting way makes the next
     * background walk `@Upsert` the row bearing that exact text with ANOTHER message's envelope:
     * the tick follows the key, so the new envelope is on screen already ticked and nobody touched
     * anything. Confirm the delete on a row that reads "in the Trash" and `UID EXPUNGE` leaves on a
     * live message no one selected — with no error and no way back.
     *
     * Nothing downstream can see it, and `mayDestroyUnderStatedNumbering` is not wrong: it destroys
     * what the line represents NOW, under a number the server confirms. Only the selection spans
     * the two moments, so only here can the earlier fact be opposed.
     *
     * ⚠ Source lint, and only because `InboxViewModel` needs an `Application` and Room. The
     * decision itself is EXECUTED — `DestroyOnlyWhatWasTickedTest` runs the rule, and
     * `DestroyUnderTheNumberingTheRowWasReadUnderTest` runs it against a scripted socket and reads
     * the verdict off the commands that left the client. What can only be read as text is the
     * WIRING: which list the destruction is handed, and with which arguments the split is called.
     */
    @Test fun theDestructionTakesOnlyWhatIsStillTheLineThatWasTicked() {
        val body = bodyOf(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the held-back destroy must be handed the KEPT half of the split and never plan.destroy " +
                "again: the whole leg includes the rows whose line was replaced under the tick, and " +
                "destroying those is the data loss this closes. Body was:\n$body",
            listOf(
                "heldBackDestroy(split.kept, getApplication<Application>().getString(R.string.status_message_deleted_forever))",
            ),
            codeLinesNaming(body, "heldBackDestroy("),
        )
        val lines = body.lines().map { it.trim() }
        val call = lines.indexOf("val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(")
        assertTrue(
            "the cut must go through the pure function — a comparison written inline here is a " +
                "decision no test can run. Body was:\n$body",
            call >= 0,
        )
        assertEquals(
            "and its ARGUMENTS are the rule: the destroy leg, the stamp of the TICK, and the stamp " +
                "the row carries NOW. Hand it the same map twice and it can never refuse anything, " +
                "while every name in the call stays exactly as it reads here. Body was:\n$body",
            listOf(
                "rows = plan.destroy,",
                "tickedUnder = { ticked[it.emailKey()] },",
                "readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },",
                ")",
            ),
            lines.subList(call + 1, call + 5),
        )
        assertEquals(
            "and the 'now' side must be the per-ROW read, grouped per account — the folder's own " +
                "record is the value a background pass has already realigned. Body was:\n$body",
            listOf("val now = recordedNumbering(destroyTargets)"),
            codeLinesNaming(body, "recordedNumbering("),
        )
        // ⛔ AND THE ROWS IT READS ARE THE DESTROY LEG. This line is one identifier away from
        // `plan.move`, and that single word turns the whole guard off in silence: `now` then holds
        // no entry for any row about to be destroyed, `readUnderNow` answers null for every one of
        // them, `drifted` is empty by construction and the data loss is back whole — with the call
        // above still reading, word for word, exactly as it does here. Nothing executable can see
        // it, so it is pinned here.
        assertEquals(
            "the 'now' read must be built from the DESTROY leg — built from any other list it " +
                "answers nothing about the rows being destroyed, and a guard that can never " +
                "refuse is a guard that is not there. Body was:\n$body",
            listOf(
                "val destroyTargets = plan.destroy.mapNotNull { e -> credentialsFor(e)?.let { " +
                    "Triple(it, e.id, e.mailboxId.orEmpty()) } }",
            ),
            codeLinesNaming(body, "destroyTargets ="),
        )
    }

    /**
     * The stamp of the tick is captured WITH THE KEYS, before anything suspends, and it is never
     * re-read for a key that already has one.
     *
     * Captured inside the `launch`, it would be read after `selectionNumbering`, the cache read and
     * the per-message Trash probes have all suspended — and a background pass landing in one of
     * those suspensions is precisely the event being opposed, so the field would already hold the
     * new numbering and agree with itself.
     *
     * ONE collector rather than five call sites: `select`, `toggle`, `selectThread`, `toggleThread`
     * and `selectAll` all write `_selectedKeys`, and a sixth will be added. A collector on the flow
     * IS "every write", and it forgets an unticked key on its own.
     *
     * ⛔ Which makes the give-back the dangerous half, and the reason "never re-read" is pinned as
     * hard as "read at the tick": the keys the split refuses go back into `_selectedKeys`, i.e.
     * straight back through this collector. Let it treat them as new and it stamps them with the
     * numbering of the renumbering, and the second tap on Delete destroys what the first refused.
     */
    @Test fun theStampOfTheTickIsCapturedAtTheGestureAndNeverReReadForAKeyAlreadyTicked() {
        val body = bodyOf(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "deleteSelected must capture the ticks' stamps beside the keys, oppose them in the " +
                "split, and give the refused ones back — those three lines and no fourth. " +
                "Body was:\n$body",
            listOf(
                "val ticked = awaitTickedNumbering(keys)",
                "tickedUnder = { ticked[it.emailKey()] },",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
            ),
            codeLinesNaming(body, "ticked"),
        )
        // ⛔ THE FIRST LINE OF THE LAUNCH, and that is what replaces the old "capture outside the
        // launch" rule. The stamps are no longer TAKEN at the gesture, they are AWAITED: the read
        // that produces them starts at the TICK, in the collector on _selectedKeys, and a
        // "Select all" publishes it a dozen queries later. A wait suspends, so it goes inside the
        // launch; it goes FIRST because everything below it either suspends or clears the
        // selection, and the collector empties the field the moment the selection is cleared.
        // ⛔ Still never a re-read: awaiting a publication is not reading the row again, and a
        // re-read here would be the realigned number the guard exists to refuse.
        val gestureLines = body.lines().map { it.trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { it.isNotEmpty() }
        assertEquals(
            "…and it must be the FIRST statement inside viewModelScope.launch: after " +
                "selectionNumbering, the cache read or the Trash probes it would be racing its " +
                "own clearSelection(). Body was:\n$body",
            "val ticked = awaitTickedNumbering(keys)",
            gestureLines[gestureLines.indexOfFirst { it == "viewModelScope.launch {" } + 1],
        )
        assertLineOrder(
            "and the KEYS stay captured before the launch — they say WHICH selection the stamps " +
                "are awaited for. Body was:\n$body",
            gestureLines, "val keys = _selectedKeys.value", "viewModelScope.launch {",
        )
        assertEquals(
            "the field has exactly THREE writers and no fourth: the collector on the selection, so " +
                "a new way of ticking a row cannot arrive without its stamp; and the two " +
                "give-backs — the held-back destroy's and the cross-account move's — each of " +
                "which puts the stamps OF THE TICK back before the keys they belong to. The two " +
                "remaining lines are not writers at all: they are the NAMED ARGUMENT of the pure " +
                "split, `tickedUnder = { ticked[...] }`, which reads the local copy taken at the " +
                "gesture. A writer beyond these three is a stamp written at a moment nobody chose. " +
                "Listed in file order: collector, destroy (argument, then give-back), " +
                "cross-account move (argument, then give-back).",
            listOf(
                "tickedUnder = (known + read).filterKeys { it in still }",
                "tickedUnder = { ticked[it.emailKey()] },",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
                "tickedUnder = { ticked[it.emailKey()] },",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
            ),
            codeLinesNaming(INBOX_VIEW_MODEL.readText(), "tickedUnder ="),
        )
        assertEquals(
            "and that collector must sit on _selectedKeys itself, not on any of the five sites " +
                "that add to it",
            listOf("_selectedKeys.collect { rememberTickedNumbering(it) }"),
            codeLinesNaming(INBOX_VIEW_MODEL.readText(), "rememberTickedNumbering(it)"),
        )
        assertEquals(
            "and it must read the numbering OF THE ROWS, grouped per account: one read per message " +
                "is thousands of database reads on a select-all",
            listOf("val numbering = repo.numberingRowsWereReadUnder(credentials, group.map { it.emailId })"),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "rememberTickedNumbering"), "repo."),
        )
        // ⛔ LOADING THE STAMPS TAKES FOUR LINES, NOT ONE, and each of the four can be mutated
        // while the other three keep their exact appearance: `repo.numberingRowsWereReadUnder(…)`
        // READS, `read[key] = …` WRITES what was read, `val still = …` decides WHICH STAMPS
        // SURVIVE the (suspending) read, and `tickedUnder = (known + read)…` PUBLISHES them. The
        // assertion above pins the read; the three below pin the other three, and the existing
        // `known` assertion pins the last.
        //
        // ⚠ What a broken load costs is IMAP mail, and IMAP mail only.
        // `MailRepository.numberingRowsWereReadUnder` returns `emptyMap()` for anything that is
        // not `MailProtocol.IMAP`, so a JMAP stamp is null already, and an absent stamp PASSES by
        // construction (`UidValidity.destroyableUnderTheNumberingItWasTickedUnder`, which refuses
        // only when two real numbers disagree). On IMAP the loss is whole: both #99 guards — the
        // bulk delete's and the cross-account move's — lose the ability to refuse anything at all.
        // ⭐ That repository behaviour is no longer taken on trust from source text: the decision
        // lives in `numberingStampsOfRows` and `core:data`'s `NumberingStampsTest` EXECUTES it —
        // empty on JMAP with the database never read, the row's own number on IMAP, and a
        // null-stamped row kept as a key. What no JVM test can run stays here: this load.
        val remembered = bodyOf(INBOX_VIEW_MODEL, "rememberTickedNumbering")
        // What the user loses if this line drops what it read (`read[key] = null`): every IMAP
        // stamp is null, the bulk delete destroys a line swapped under the tick again, and the
        // move between accounts sends it to the other account again — no Undo there to take it
        // back — while the repository call above still reads word for word as it does today.
        assertEquals(
            "the numbering read for the new keys must be WRITTEN under each key: a stamp that is " +
                "read and then discarded is no stamp at all, and both #99 guards then pass every " +
                "IMAP row. Body was:\n$remembered",
            listOf("group.forEach { key -> read[key] = numbering[key.emailId] }"),
            codeLinesNaming(remembered, "read[key]"),
        )
        // ⛔ ONLY THE KEYS WITHOUT A STAMP ARE READ. `(fresh + keys)` compiles without a warning
        // and inverts the rule this very test is named after: every already-stamped key is read
        // again, `read` then carries TODAY's numbering, and `(known + read)` lets `read` win. The
        // rows the split refused are handed straight back into `_selectedKeys` — the give-backs at
        // the end of `deleteSelected` and of `moveSelectedToAccount` — so they return through this
        // same collector and are re-stamped with the numbering of the renumbering itself. The
        // second tap then destroys, or sends to the other account, exactly what the first tap
        // saved: the guard would buy the user one tap.
        assertEquals(
            "only the keys that carry no stamp yet may be read: a re-read answers what the row " +
                "carries NOW, which is the one value this field exists not to hold. " +
                "Body was:\n$remembered",
            listOf("fresh.groupBy { credentialsFor(it) }.forEach { (credentials, group) ->"),
            codeLinesNaming(remembered, "groupBy"),
        )
        // ⛔ AND WHAT SURVIVES THE READ IS THE SELECTION AS IT IS NOW. The read above suspends, so
        // the stamps are filtered on the keys still ticked when it comes back. Written as a
        // constant — `emptySet()` — the field is emptied on every pass instead: no stamp survives,
        // both guards pass everything on IMAP, and no call site anywhere looks different.
        assertEquals(
            "the stamps kept must be those of the keys STILL ticked when the read came back, off " +
                "the flow and never a constant. Body was:\n$remembered",
            listOf("val still = _selectedKeys.value"),
            codeLinesNaming(remembered, "val still ="),
        )
        // ⛔ And it must read ONLY the keys it does not already hold. Re-reading a known key
        // answers what its row carries NOW — which is the value the whole field exists not to be —
        // so a collector that refreshed everything on every selection change would agree with the
        // renumbering it is opposed to, and would do it on the give-back below, where the keys the
        // split just refused come straight back through this same collector.
        assertEquals(
            "the collector must keep the stamps it already has and read the NEW keys only",
            listOf(
                "val known = tickedUnder.filterKeys { it in keys }",
                "val fresh = keys.filterNot { it in known }",
                "tickedUnder = (known + read).filterKeys { it in still }",
            ),
            codeLinesNaming(bodyOf(INBOX_VIEW_MODEL, "rememberTickedNumbering"), "known"),
        )
    }

    /**
     * What the split refused is COUNTED and GIVEN BACK. A destruction that quietly dropped part of
     * a confirmed selection would be a silent half-action — the failure mode this whole area keeps
     * producing — and the screen would agree with it.
     *
     * The counting is asymmetric on purpose: the refused rows are inside `plan.destroy.size`, so
     * they were attempted; adding them to `attemptedBefore` as well would make the message claim
     * twice as many attempts as there were. They are added to the FAILURES only, which is what
     * turns `bulkOutcome` into the existing `status_action_partly_failed` string — no new string,
     * no new translation.
     */
    @Test fun whatTheSplitRefusedIsCountedAsAFailureAndPutBackInTheSelection() {
        val body = bodyOf(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the refused rows must be counted as failures and NOT a second time as attempts. " +
                "Body was:\n$body",
            listOf(
                "val lostBefore = resolved.unresolved.size + plan.untreated.size",
                "val lost = lostBefore + split.drifted.size",
                "attemptedBefore = plan.destroy.size + lostBefore,",
                "failedBefore = lost,",
                "when (bulkOutcome(attempted = keys.size, failed = lost)) {",
            ),
            codeLinesNaming(body, "lost"),
        )
        assertEquals(
            "and they must go back into the selection, still ticked, WITH THE STAMPS THEY WERE " +
                "TICKED UNDER — and the stamps first. Given back without them, the collector sees " +
                "keys it has never met, reads their numbering as it is NOW, and the second tap on " +
                "Delete destroys exactly what the first one refused: the fix would buy one tap. " +
                "Body was:\n$body",
            listOf(
                "val lost = lostBefore + split.drifted.size",
                "if (split.drifted.isNotEmpty()) {",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
                "_selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
            ),
            codeLinesNaming(body, "split.drifted"),
        )
        assertLineOrder(
            "…and the stamps BEFORE the keys: nothing suspends between the two lines, which is " +
                "the only reason the collector cannot slip in and re-read them. Body was:\n$body",
            codeLinesOf(body),
            "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
            "_selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
        )
        assertEquals(
            "with the selection bar back up — the keys alone leave the screen in a state nothing " +
                "can act on. Body was:\n$body",
            listOf("_selectionActive.value = true"),
            codeLinesNaming(body, "_selectionActive"),
        )
        assertLineOrder(
            "⛔ and AFTER the delegation, never before: bulkBatched calls clearSelection() " +
                "synchronously at the call, so a re-selection written earlier is wiped by the very " +
                "line that follows it. Body was:\n$body",
            codeLinesOf(body), "bulkBatched(", "if (split.drifted.isNotEmpty()) {",
        )
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

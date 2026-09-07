package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — same instrument and the same disclaimer as
 * [SelectionTargetsWiringTest]: it reads `InboxViewModel.kt` as text, proves nothing about what
 * happens on screen, and would still pass if the code it points at were broken inside.
 *
 * It exists because the decisions it guards are executed elsewhere — [SelectionTargetsTest],
 * [SelectionUndoTest], [SelectionDeletePlanTest], [SelectionSnoozePlanTest], [BulkOutcomeTest] —
 * while the PLUG that carries the selection into them lives in an `AndroidViewModel` no JVM test
 * here can instantiate (no Robolectric, no instrumented tests). Any of the bulk paths can go back
 * to walking
 * `repo.cachedEmailsByIds(keys)` in one line — the 2026-08-08 bench report: from a search, archive
 * / move / spam / trash / snooze / delete act on the messages that happen to have a cached row and
 * skip the rest with no action, no counted failure and nothing said — and every executable test
 * stays green.
 *
 * Everything is pinned WHOLE — whole lines, or arguments compared as a LIST — never `in` /
 * `contains`: a substring rule accepts anything appended to what it matched, and two of the
 * rewrites it would accept are irreversible (an Undo entry built on a row whose folder is a crawl
 * artefact moves the message into a stale folder AND strips every other folder it is in; an
 * untrusted row let into the destroy partition is destroyed instead of moved to the Trash).
 *
 * What it does NOT do: it reads names, so it can only say a call is written, never that it works.
 */
class BulkSelectionWiringTest {

    // -- A. every path resolves the SELECTION, not the cache's answer ---------------------------

    /**
     * The three arguments each path must hand the shared resolution. `displayed` is what makes a
     * search hit with no local row reachable at all; drop it and the report is back.
     */
    private fun expectedArguments(keys: String) = listOf(
        "keys = $keys",
        "cached = repo.cachedEmailsByIds($keys)",
        "displayed = searchState.value.results",
    )

    @Test fun `the snooze path resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "bulk() must contain exactly one resolveSelectionTargets(...) call — the decision " +
                "SelectionTargetsTest runs. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments("keys"),
            arguments(call),
        )
    }

    @Test fun `the batched path resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "bulkBatched() must contain exactly one resolveSelectionTargets(...) call — it is the " +
                "archive / move / spam / not-spam / trash path. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments("targetKeys"),
            arguments(call),
        )
    }

    @Test fun `the delete path resolves every selected key`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        val call = callArguments(body, "resolveSelectionTargets").singleOrNull()
        checkNotNull(call) {
            "deleteSelected() must contain exactly one resolveSelectionTargets(...) call before it " +
                "decides who is destroyed and who is moved. Body was:\n$body"
        }
        assertEquals(
            "resolveSelectionTargets(...) must be handed exactly these arguments, whole. " +
                "Arguments were:\n$call",
            expectedArguments("keys"),
            arguments(call),
        )
    }

    /**
     * The cache may only be read AS the `cached` argument. A second, direct read left in a body is
     * the defect itself wearing the fix as a hat: the resolution runs, and the loop still walks
     * what the query returned.
     */
    @Test fun `no path reads the cache anywhere but into the resolution`() {
        for ((name, keys) in listOf("bulk" to "keys", "bulkBatched" to "targetKeys", "deleteSelected" to "keys")) {
            assertEquals(
                "$name() must read the cache once, as the 'cached' argument and nowhere else — a " +
                    "direct repo.cachedEmailsByIds(...) short-circuits the resolution and drops " +
                    "every selected message with no local row.",
                listOf("cached = repo.cachedEmailsByIds($keys),"),
                codeLinesNaming(body(INBOX_VIEW_MODEL, name), "repo.cachedEmailsByIds("),
            )
        }
    }

    /**
     * The snooze MAY set a target aside — a row with no local `emails` row cannot be snoozed at
     * all: `SnoozeWorker` resolves the message back through that very table, so the reminder is
     * written, the worker fires, nothing is found, nothing rings, and the `snoozed` row is deleted
     * on the way, taking the message out of "Snoozed" too. What it may never do is set one aside in
     * SILENCE: a refused row is not `unresolved`, so a partition that does not reach `failed`
     * leaves `bulkOutcome` on NONE and the screen says the snooze worked — the 2026-08-08 report
     * restored word for word.
     *
     * So the guard is no longer "never filter", it is "never set aside without counting", and the
     * three lines that make it are pinned WHOLE, as one rule: the line that partitions, the line
     * that walks the snoozeable part ONLY, and the line that counts the refused part as failures.
     * Deleting the count while keeping the partition is exactly the silent drop and reddens here.
     *
     * The trust test itself is not made in this body — [SelectionSnoozePlanTest] executes it — so a
     * raw `folderTrusted` read here would be a partition no executable test watches.
     */
    @Test fun `the snooze never sets a target aside in silence`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        val call = callArguments(body, "planSelectionSnooze").singleOrNull()
        checkNotNull(call) {
            "bulk() must partition through planSelectionSnooze(...) — the decision " +
                "SelectionSnoozePlanTest runs. Body was:\n$body"
        }
        assertEquals(
            "planSelectionSnooze(...) must be handed the WHOLE resolution, whole. " +
                "Arguments were:\n$call",
            listOf("resolved.targets"),
            arguments(call),
        )
        assertEquals(
            "the resolved targets may go nowhere but into that plan: a second walk over " +
                "resolved.targets is the unfiltered write coming back. Body was:\n$body",
            listOf("val plan = planSelectionSnooze(resolved.targets)"),
            codeLinesNaming(body, "resolved.targets"),
        )
        assertEquals(
            "every line the plan touches, whole: it is partitioned, the refused part is COUNTED " +
                "as failures, and only the snoozeable part is written. Body was:\n$body",
            listOf(
                "val plan = planSelectionSnooze(resolved.targets)",
                "var failed = resolved.unresolved.size + plan.refused.size",
                "plan.snooze.forEach { target ->",
            ),
            codeLinesNaming(body, "plan"),
        )
        assertEquals(
            "bulk() must not read folderTrusted itself: the trust decision belongs to " +
                "planSelectionSnooze, which a JVM test executes. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "folderTrusted"),
        )
    }

    /**
     * A message whose account credentials are gone was never written either, and it is not
     * `unresolved` — nothing but this counter stands between that case and total silence.
     */
    @Test fun `a message with no credentials is counted, not skipped`() {
        assertEquals(
            "bulk() must count a target it cannot even reach.",
            listOf("if (credentials == null) { failed++; return@forEach }"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulk"), "credentials == null"),
        )
        assertEquals(
            "bulkBatched() must count that whole account's group as failed.",
            listOf("if (credentials == null) { failedKeys += group.map { it.email.emailKey() }; return@forEach }"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulkBatched"), "credentials == null"),
        )
    }

    @Test fun `the batched path acts on all the targets`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        assertEquals(
            "bulkBatched() must group ALL the targets by account — a .filter { it.folderTrusted } " +
                "here is the report itself, only louder. Body was:\n$body",
            listOf("targets.groupBy { credentialsFor(it.email) }.forEach { (credentials, group) ->"),
            codeLinesNaming(body, "groupBy {"),
        )
        assertEquals(
            "the rows leaving the search snapshot are the ones the batch is about to touch, and " +
                "only the failures come back. Body was:\n$body",
            listOf(
                "dropSearchResults(targets.mapTo(mutableSetOf()) { it.email.emailKey() })",
                "restoreSearchResults(failedKeys)",
            ),
            codeLinesNaming(body, "SearchResults("),
        )
    }

    // -- B. Undo: never built on a folder that is a crawl artefact ------------------------------

    /**
     * `restoreAll` moves a message back with `client.move(..., sourceMailboxId)`, which OVERWRITES
     * `mailboxIds` with `{source: true}` (JmapClient). A source read off a row that only
     * ever existed on screen is frozen at crawl time and may be empty: undoing then files the
     * message into a stale folder and strips every other folder it belongs to, silently and with
     * no way back. So no path may build an [UndoEntry] itself — the decision [SelectionUndoTest]
     * runs is the only place that does, and it drops untrusted rows, drops empty sources, and
     * withholds the WHOLE batch's Undo as soon as one untrusted row went through (half an Undo is
     * a lie; the action itself stays done for everyone).
     */
    @Test fun `neither writing path builds an Undo entry of its own`() {
        for (name in listOf("bulk", "bulkBatched")) {
            val body = body(INBOX_VIEW_MODEL, name)
            assertEquals(
                "$name() must not construct UndoEntry itself: the trust and empty-folder guards " +
                    "live in selectionUndoEntries(). Body was:\n$body",
                emptyList<String>(),
                codeLinesNaming(body, "UndoEntry("),
            )
            assertEquals(
                "$name() must take its Undo entries from the shared decision. Body was:\n$body",
                listOf("val undoEntries = selectionUndoEntries(undoCandidates)"),
                codeLinesNaming(body, "selectionUndoEntries("),
            )
        }
    }

    /**
     * And offer them unchanged, only when there are some. The two bodies diverge here since the
     * batched path's banner counts, so they are pinned apart.
     *
     * The snooze path keeps the plain label: its only caller passes none, so no banner of its ever
     * reaches a screen.
     */
    @Test fun `the snooze path offers its entries under a fixed label`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        assertEquals(
            "bulk() must offer the entries unchanged, only when there are some. Body was:\n$body",
            listOf(
                "if (undoLabel != null && undoEntries.isNotEmpty()) {",
                "_undo.value = UndoAction(undoEntries, undoLabel)",
            ),
            codeLinesNaming(body, "undoEntries") - listOf("val undoEntries = selectionUndoEntries(undoCandidates)"),
        )
    }

    /**
     * The batched path is the one the user reaches with a selection, so its banner is a plural and
     * BOTH halves of it are the batch's business:
     *
     *  - WHETHER there is a banner at all — never one without Undo entries behind it, or a success
     *    bar sits there with no button on it;
     *  - WHAT it counts — the messages the SERVER wrote, not the entries. `selectionUndoEntries`
     *    returns an empty list as soon as one row is untrusted and drops every row naming no
     *    folder, while those messages left the folder all the same: counting entries announces
     *    "4 messages deleted" for ten deleted.
     *
     * Both live in [selectionBanner], which [SelectionUndoTest] executes; this only says the body
     * still asks it, with the candidates, and formats the answer with the count it gave back.
     */
    @Test fun `the batched path offers its entries under a counted plural`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        assertEquals(
            "bulkBatched() must ask selectionBanner() — with the CANDIDATES, which is where the " +
                "count comes from — and offer the entries unchanged under its answer. Body was:\n$body",
            listOf(
                "selectionBanner(undoLabelRes, undoCandidates, undoEntries)?.let { banner ->",
                "_undo.value = UndoAction(undoEntries, label)",
            ),
            codeLinesNaming(body, "undoEntries") - listOf("val undoEntries = selectionUndoEntries(undoCandidates)"),
        )
        assertEquals(
            "and the plural is resolved with that count TWICE: once to pick the CLDR category, " +
                "once to fill %1\$d. Dropping the second argument prints a bare 'messages " +
                "deleted'; passing anything else than banner.count makes the sentence disagree " +
                "with the category. Body was:\n$body",
            listOf("val label = resources.getQuantityString(banner.labelRes, banner.count, banner.count)"),
            codeLinesNaming(body, "getQuantityString"),
        )
        assertEquals(
            "nothing else may read off the banner, and no count of its own may be built here. " +
                "Body was:\n$body",
            listOf(
                "selectionBanner(undoLabelRes, undoCandidates, undoEntries)?.let { banner ->",
                "val label = resources.getQuantityString(banner.labelRes, banner.count, banner.count)",
            ),
            codeLinesNaming(body, "banner"),
        )
    }

    /** What each path collects for that decision: the TARGET (trust included), and the destination. */
    @Test fun `the Undo candidates carry the target itself, not a bare row`() {
        assertEquals(
            "bulk() (the snooze) moves nothing, so its candidates carry no destination. Body was:",
            listOf("val undoCandidates = mutableListOf<UndoCandidate>()", ".onSuccess { undoCandidates += UndoCandidate(target, null) }"),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulk"), "UndoCandidate"),
        )
        assertEquals(
            "bulkBatched() records one candidate per id the batch reported as succeeded, with the " +
                "folder the batch put it in. Body was:",
            listOf(
                "val undoCandidates = mutableListOf<UndoCandidate>()",
                "if (target.email.id in result.succeeded) undoCandidates += UndoCandidate(target, result.dest)",
            ),
            codeLinesNaming(body(INBOX_VIEW_MODEL, "bulkBatched"), "UndoCandidate"),
        )
    }

    // -- C. delete: an untrusted row goes to the Trash, never to the destroy --------------------

    /**
     * `deleteWouldDestroy` reads the row's own folder, which an untrusted row cannot supply: on a
     * row that only exists on screen that folder was frozen at crawl time. The partition is
     * therefore made by [planSelectionDelete], which only ever asks it about a trusted row; an
     * untrusted row is moved to the Trash, or — on an account with no Trash, which the separate
     * account-level probe answers — left alone and counted, because nothing here destroys mail on
     * a supposition.
     */
    @Test fun `the delete partition is the shared plan, fed by the two probes`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        val call = callArguments(body, "planSelectionDelete").singleOrNull()
        checkNotNull(call) {
            "deleteSelected() must partition through planSelectionDelete(...) — the decision " +
                "SelectionDeletePlanTest runs. Body was:\n$body"
        }
        assertEquals(
            "planSelectionDelete(...) must be handed exactly these arguments, whole: all the " +
                "targets, a per-account Trash probe, and the destroy probe. Arguments were:\n$call",
            listOf(
                "targets = resolved.targets",
                "hasTrash = { email -> credentialsFor(email)?.let { c -> runCatching { repo.accountHasTrash(c) }" +
                    ".getOrDefault(false) } ?: false }",
                "wouldDestroy = { email -> credentialsFor(email)?.let { c -> runCatching { repo.deleteWouldDestroy(c, email) }" +
                    ".getOrDefault(false) } ?: false }",
            ),
            arguments(call),
        )
        assertEquals(
            "deleteSelected() must not partition the rows itself: whoever calls partition() here " +
                "is deciding destruction from a row's own folder again. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, ".partition"),
        )
    }

    /** And each leg of the plan goes where it belongs, untouched. */
    @Test fun `only the plan's destroy leg is destroyed, and only its move leg is batched`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        // NO LONGER "whole and unfiltered", and the change is the point. The destroy leg is now
        // cut in two before it is destroyed: the rows still on the line that was TICKED, and the
        // rows whose line a renumbering replaced under the tick since (#99, upstream half). What is
        // whole and unfiltered is the KEPT half — nothing else may be dropped between the plan and
        // the destruction, and the refused half is counted and given back, never silently lost.
        // Which rows fall on which side is pinned, with its arguments, in
        // HeldBackDestroyCarriesItsFolderTest, and EXECUTED in DestroyOnlyWhatWasTickedTest.
        assertEquals(
            "the held-back destroy takes the kept half of the tick split, whole and unfiltered — " +
                "one cut, made by the pure function, and no other. Body was:\n$body",
            listOf("heldBackDestroy(split.kept, getApplication<Application>().getString(R.string.status_message_deleted_forever))"),
            codeLinesNaming(body, "heldBackDestroy("),
        )
        assertEquals(
            "the batched delete takes the plan's move leg. Body was:\n$body",
            listOf("keys = plan.move.mapTo(mutableSetOf()) { it.emailKey() },"),
            codeLinesNaming(body, "plan.move.mapTo"),
        )
        // And the label it delegates stays CONDITIONAL. Dropping `.takeIf` leaves a mixed
        // destroy+move delete showing a counted "3 messages deleted" on top of "Deleting
        // permanently…" on the one snackbar host, under an Undo that covers only the moved leg.
        // Whole line, never `in`: a substring rule would accept the takeIf being dropped.
        //
        // And the condition is the KEPT half of the split, never `plan.destroy`: a destroy leg
        // the split refused entirely schedules nothing and puts no bar up, so reading the planned
        // leg there withholds the move's Undo in favour of a snackbar that never appears — the
        // moved messages lose their one-tap way back for a destruction that did not happen.
        assertEquals(
            "the batched delete's label must be withheld while a destruction is actually pending " +
                "— and pending means the split KEPT one. Body was:\n$body",
            listOf("undoLabelRes = R.plurals.status_selection_deleted.takeIf { split.kept.isEmpty() },"),
            codeLinesNaming(body, "undoLabelRes"),
        )
    }

    /**
     * The archive stopped being a single expression the day its source folders' numbering had to be
     * frozen at the tap (#99), so it is pinned WHOLE here rather than as one delegation line —
     * nothing else in the suite names it, and three separate things must all hold at once.
     *
     * `undoLabelRes = null` there silently drops the archive's banner AND its Undo button (the
     * regression Codeberg #23 closed), and the delete's plural there announces "3 messages deleted"
     * for three archived: both stay green everywhere else. The freeze adds two more. The numbering
     * is read HERE, at the tap, because read at execution it would be whatever the folder had
     * become by then — the defect the whole of #99 is about. And `clearSelection()` is called
     * SYNCHRONOUSLY, before the suspending read: `bulkBatched` clears the selection too, but it now
     * does so a suspension later, and a bar left up in between lets a second tap fire the same
     * batch again on the same keys.
     */
    @Test fun `the archive delegates under its own plural, and offers an Undo`() {
        val body = body(INBOX_VIEW_MODEL, "archiveSelected")
        assertEquals(
            "archiveSelected() must read exactly this. Body was:\n$body",
            listOf(
                "fun archiveSelected() {",
                "val keys = _selectedKeys.value",
                "clearSelection()",
                "viewModelScope.launch {",
                "val numbering = selectionNumbering(keys)",
                "bulkBatched(",
                "undoLabelRes = R.plurals.status_selection_archived,",
                "keys = keys,",
                ") { c, ids -> repo.archiveAll(c, ids, numbering[c.id].orEmpty()) }",
                "}",
                "}",
            ),
            body.lines().map { it.replace(Regex("""\s+"""), " ").trim() },
        )
    }

    // -- D. saying what was not done, once, over the whole selection ----------------------------

    /**
     * `attempted` is the WHOLE selection and `failed` counts everything the snooze did not write —
     * the keys nothing resolved for AND the rows the plan refused — on BOTH sides, otherwise a
     * selection that reached none of its messages lands on `failed <= 0 -> NONE` and says nothing
     * at all, which is the report itself.
     */
    @Test fun `the snooze counts the lost keys on both sides`() {
        val body = body(INBOX_VIEW_MODEL, "bulk")
        assertEquals(
            "the unresolved keys and the refused rows must both be counted as failures, not " +
                "merely reported. Body was:\n$body",
            listOf("var failed = resolved.unresolved.size + plan.refused.size"),
            codeLinesNaming(body, "resolved.unresolved"),
        )
        assertEquals(
            "the outcome must be decided by bulkOutcome over the WHOLE selection — and a snooze " +
                "that went through nine times out of ten must not claim it failed. Body was:\n$body",
            listOf("when (bulkOutcome(attempted = keys.size, failed = failed)) {"),
            codeLinesNaming(body, "bulkOutcome("),
        )
        assertEquals(expectedOutcomeBranches, codeLinesNaming(body, "BulkOutcome."))
    }

    @Test fun `the batched path counts the lost keys on both sides, its delegate's included`() {
        val body = body(INBOX_VIEW_MODEL, "bulkBatched")
        assertEquals(
            "the failures are the batch's rejects, the keys nothing resolved for, and whatever the " +
                "caller already lost before delegating. Body was:\n$body",
            listOf("val failed = failedKeys.size + resolved.unresolved.size + failedBefore"),
            codeLinesNaming(body, "val failed ="),
        )
        assertEquals(
            "and 'attempted' is the whole selection handed in, plus what the caller already " +
                "handled — never the rows the cache happened to return, and never the losses " +
                "again (they are already inside 'attemptedBefore'). Body was:\n$body",
            listOf("when (bulkOutcome(attempted = targetKeys.size + attemptedBefore, failed = failed)) {"),
            codeLinesNaming(body, "bulkOutcome("),
        )
        assertEquals(
            "and the total failure asks the shared decision which sentence it owes — but only " +
                "when a throwable actually escaped a batch. Body was:\n$body",
            expectedBatchedOutcomeBranches,
            codeLinesNaming(body, "BulkOutcome."),
        )
        assertEquals(
            "the throwable it decides on is the FIRST a batch let escape, and it is never " +
                "overwritten: the loop is per account, and the failure that cut the gesture is " +
                "the one that came first. Body was:\n$body",
            listOf(
                "var batchFailure: Throwable? = null",
                "if (batchFailure == null) batchFailure = it",
                "val failure = batchFailure",
            ),
            codeLinesNaming(body, "batchFailure"),
        )
    }

    /**
     * The delete handles part of the selection itself (the held-back destroy) and loses part of it
     * (unresolved keys, and untrusted rows on a Trash-less account). Both travel to the delegate so
     * ONE honest message comes out over the whole delete: two toasts on a `StateFlow` would chase
     * each other and only the last would be seen, and leaving the destroyed messages out of
     * `attempted` turns "99 destroyed, 1 lost" into "Couldn't complete the action".
     */
    @Test fun `the delete hands what it handled and what it lost to the delegate`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        // Two numbers now, not one. `lostBefore` is what the delete lost BEFORE the destroy leg
        // was cut — unresolved keys and untrusted rows on a Trash-less account — and `lost` adds the
        // rows the tick split refused. They are separate because the refused rows are ALREADY
        // inside plan.destroy.size: counting them in `attemptedBefore` too would make the message
        // claim twice as many attempts as there were.
        assertEquals(
            "what the delete lost on its own must be counted, and separately from what the tick " +
                "split refused. Body was:\n$body",
            listOf(
                "val lostBefore = resolved.unresolved.size + plan.untreated.size",
                "val lost = lostBefore + split.drifted.size",
            ),
            codeLinesNaming(body, "val lost"),
        )
        assertEquals(
            "the destroyed messages are attempts too, and the losses are attempts that failed: " +
                "both sides travel, and they are NOT the same number. The refused rows join the " +
                "FAILURES only. Body was:\n$body",
            listOf(
                "attemptedBefore = plan.destroy.size + lostBefore,",
                "failedBefore = lost,",
                "when (bulkOutcome(attempted = keys.size, failed = lost)) {",
            ),
            codeLinesNaming(body, "lost") - listOf(
                "val lostBefore = resolved.unresolved.size + plan.untreated.size",
                "val lost = lostBefore + split.drifted.size",
            ),
        )
    }

    /**
     * And when there is nothing to move, the delegate is not called at all: an empty batch would
     * still drop the sync cursors and re-query the whole folder (`resetSyncState()` + `refresh()`)
     * for an action that wrote nothing — which `main` never did. The message is then printed here,
     * over the whole selection, and there is no second one to chase it.
     */
    @Test fun `an empty move delegates nothing and speaks for itself`() {
        val body = body(INBOX_VIEW_MODEL, "deleteSelected")
        assertEquals(
            "the batched path must run only when it has something to move. Body was:\n$body",
            listOf("if (plan.move.isNotEmpty()) {"),
            codeLinesNaming(body, "plan.move.isNotEmpty()"),
        )
        assertEquals(
            "and the message it then prints itself must be the same three-way one, over the " +
                "WHOLE selection. Body was:\n$body",
            expectedOutcomeBranches,
            codeLinesNaming(body, "BulkOutcome."),
        )
    }

    /**
     * A unified selection spanning two accounts, moved to a folder of account A: the account-B
     * messages cannot go there (a mailbox id of A means nothing in B) so they are set aside. They
     * used to be announced on the spot, and the delegate then wrote a SECOND message on the same
     * `StateFlow` — one whose count knew nothing about them. Two announcements chasing each other
     * (or one truncated and replaced, the race is not decided), and a count that was wrong: the
     * delegate's verdict was taken over the movable subset alone, so a move that left half the
     * selection where it was could land on "nothing failed" and say nothing about that half. The
     * move itself was never mute — it spoke, and its count was the lie.
     *
     * So the set-aside part now travels with the delegation, as attempts AND as failures, and this
     * body writes no message of its own while it delegates. Nothing is delegated when nothing is
     * movable — an empty batch would still drop the sync cursors and re-query the whole folder for
     * an action that wrote nothing — and only then does the move speak for itself.
     */
    @Test fun `the move hands what it set aside to the delegate, on both sides`() {
        val body = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        val call = callArguments(body, "bulkBatched").singleOrNull()
        checkNotNull(call) {
            "moveSelectedTo() must delegate to bulkBatched(...) exactly once. Body was:\n$body"
        }
        assertEquals(
            "bulkBatched(...) must be handed exactly these arguments, whole: the movable part " +
                "only, and the set-aside part counted BOTH as attempts and as failures — dropping " +
                "either counter is a move that left half the selection in place and said it " +
                "worked. Arguments were:\n$call",
            listOf(
                "undoLabelRes = R.plurals.status_selection_moved",
                "keys = movable.toMutableSet()",
                "attemptedBefore = skipped.size",
                "failedBefore = skipped.size",
            ),
            arguments(call),
        )
        assertEquals(
            "every line naming the set-aside part, whole and in order. Body was:\n$body",
            listOf(
                "val (movable, skipped) = keys.partition { it.accountId == moveAccountId }",
                "attemptedBefore = skipped.size,",
                "failedBefore = skipped.size,",
                "} else if (skipped.isNotEmpty()) {",
            ),
            codeLinesNaming(body, "skipped"),
        )
        assertEquals(
            "the delegate runs only when there is something to move, and this body speaks only " +
                "when there is not. Body was:\n$body",
            listOf("if (movable.isNotEmpty()) {", "} else if (skipped.isNotEmpty()) {"),
            codeLinesNaming(body, "isNotEmpty()"),
        )
        assertEquals(
            "and the batch it hands over is the move itself — the trailing lambda sits OUTSIDE " +
                "the parentheses, so the argument list above never sees it, and swapping the repo " +
                "call there sends the selection to another folder entirely. The numbering " +
                "argument is the source folders' frozen UIDVALIDITY (#99, MoveNumberingAtTheGestureTest). " +
                "Body was:\n$body",
            listOf(") { c, batch -> repo.moveAllToMailbox(c, batch, targetMailboxId, numbering[c.id].orEmpty()) }"),
            codeLinesNaming(body, "repo."),
        )
    }

    /**
     * ONE announcement leaves this body, and only on the leg that delegated nothing: pinned as
     * every `_message.value` line of the body, and as the ORDER of the two strings it reads — the
     * other-account sentence BELOW the delegation's Undo label. Putting it back above the
     * delegation, or writing `_message.value` a second time, reddens here.
     *
     * Those two rules see LITERAL writes only. `_message` can also be written from a helper —
     * [InboxViewModel.reportActionFailed] does, 1 700 lines above — so the last two rules close
     * that door the only way a text lint can: no helper of that kind may be named here, and the
     * one statement between the partition and the delegation is pinned whole, so a second message
     * cannot be smuggled onto it either.
     */
    @Test fun `the move announces the other account once, and only when it delegated nothing`() {
        val body = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        assertEquals(
            "moveSelectedTo() must write exactly this one message. Body was:\n$body",
            listOf(
                "_message.value = getApplication<Application>()" +
                    ".getString(R.string.status_move_other_account)",
            ),
            codeLinesNaming(body, "_message.value"),
        )
        assertEquals(
            "and it must come AFTER the delegation, never before it. Body was:\n$body",
            listOf(
                "undoLabelRes = R.plurals.status_selection_moved,",
                "_message.value = getApplication<Application>()" +
                    ".getString(R.string.status_move_other_account)",
            ),
            // `R.` and not `R.string.`: the move's own label is a <plurals> now, so a rule reading
            // `R.string.` alone would see one line, be satisfied by it, and stop saying anything
            // about the order the two are written in.
            codeLinesNaming(body, "R."),
        )
        assertEquals(
            "no message-writing helper may be called here: reportActionFailed() writes _message " +
                "without the literal this rule reads, and one call of it beside the delegation is " +
                "the two racing announcements, back. Body was:\n$body",
            emptyList<String>(),
            codeLinesNaming(body, "reportActionFailed"),
        )
        assertEquals(
            "and the one statement standing between the partition and the delegation is pinned " +
                "whole — it clears the selection, and it does nothing else. Body was:\n$body",
            listOf("clearSelection()"),
            codeLinesNaming(body, "clearSelection"),
        )
    }

    /**
     * And the guard deciding who is movable stays written: without the fallback and the partition,
     * a mailbox id of the selection's account is sent into a sibling account, where an IMAP server
     * with a same-named Archive accepts it silently. [SelectionAccountTest] executes the resolution
     * itself; this only says it is still the one being called.
     */
    @Test fun `the move still resolves one account and partitions on it`() {
        val body = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        assertEquals(
            "the target account is the selection's, falling back to the active one. Body was:\n$body",
            listOf("val moveAccountId = selectionAccount(keys) ?: store.currentId()"),
            codeLinesNaming(body, "selectionAccount("),
        )
        assertEquals(
            "and only that account's messages are movable. Body was:\n$body",
            listOf("val (movable, skipped) = keys.partition { it.accountId == moveAccountId }"),
            codeLinesNaming(body, ".partition"),
        )
    }

    /**
     * bulkBatched's own, kept apart from [expectedOutcomeBranches] on purpose: it is the only one
     * of the four holding the throwable that failed, so it is the only one whose total failure may
     * say "you are offline". The other three keep the constant below word for word — counting
     * failures is not a connectivity reading, and a refusal, an over-quota or an unconfirmed
     * numbering all arrive there with no exception at all.
     */
    private val expectedBatchedOutcomeBranches = listOf(
        "BulkOutcome.NONE -> Unit",
        "BulkOutcome.TOTAL -> _message.value = app.getString(if (failure == null) " +
            "R.string.status_action_failed else actionFailureMessage(failure, online = " +
            "hasUsableNetwork(app)))",
        "BulkOutcome.PARTIAL -> _message.value = getApplication<Application>()" +
            ".getString(R.string.status_action_partly_failed)",
    )

    private val expectedOutcomeBranches = listOf(
        "BulkOutcome.NONE -> Unit",
        "BulkOutcome.TOTAL -> _message.value = getApplication<Application>()" +
            ".getString(R.string.status_action_failed)",
        "BulkOutcome.PARTIAL -> _message.value = getApplication<Application>()" +
            ".getString(R.string.status_action_partly_failed)",
    )

    // -- reading the sources ------------------------------------------------------------------
    // Same readers as SelectionTargetsWiringTest and SelectAllWiringTest, deliberately duplicated
    // rather than shared: a helper these lints agree on is a helper a single edit can loosen for
    // all of them at once.

    /** The code lines of [body] naming [needle], comments dropped, whitespace normalised. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    /** The lines of [file] that are code, with comments taken off. */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

    /** [line] up to its first `//` outside a double-quoted string; `\` escapes the next character. */
    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text. Fails loudly when the
     * declaration is not found — a rename must break these rules rather than satisfy them silently
     * against an empty string.
     */
    private fun body(file: File, name: String): String {
        val lines = codeLines(file)
        val declaration = Regex("""\b(fun|val|var)\s+$name\b""")
        val start = lines.indexOfFirst { declaration.containsMatchIn(it) }
        check(start >= 0) { "${file.name} declares no '$name' — did it get renamed?" }
        val indent = lines[start].indentWidth()
        val out = mutableListOf<String>()
        var closed = false
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            if (closed && i > start && line.indentWidth() <= indent) break
            out += line
            depth += line.count { it == '(' || it == '{' } - line.count { it == ')' || it == '}' }
            closed = depth == 0
        }
        return out.joinToString("\n")
    }

    private fun String.indentWidth() = length - trimStart().length

    /**
     * A call's arguments, one entry each, whitespace normalised: split on the commas at the call's
     * own depth, so a nested call or lambda keeps its own. This is what lets the rules above
     * compare arguments WHOLE — the sole defence against a mutation that leaves the pinned text in
     * place and appends to it.
     */
    private fun arguments(call: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        call.forEach { c ->
            when (c) {
                '(', '{', '[' -> { depth++; current.append(c) }
                ')', '}', ']' -> { depth--; current.append(c) }
                ',' -> if (depth == 0) { out += current.toString(); current.clear() } else current.append(c)
                else -> current.append(c)
            }
        }
        out += current.toString()
        return out.map { it.replace(Regex("""\s+"""), " ").trim() }.filter { it.isNotEmpty() }
    }

    /** The argument text of every call to [name] in [text], parentheses balanced. */
    private fun callArguments(text: String, name: String): List<String> =
        Regex("""\b${Regex.escape(name)}\(""").findAll(text)
            .map { balanced(text, it.range.last, '(', ')') }
            .toList()

    /** [text] from the first [open] at or after [from], up to the [close] that balances it. */
    private fun balanced(text: String, from: Int, open: Char, close: Char): String {
        val start = text.indexOf(open, from).let { if (it < 0) from else it + 1 }
        var depth = 1
        var i = start
        while (i < text.length && depth > 0) {
            when (text[i]) {
                open -> depth++
                close -> depth--
            }
            i++
        }
        return text.substring(start, (i - 1).coerceAtLeast(start)).trim()
    }

    companion object {
        private const val APP_SOURCES = "app/src/main/kotlin"
        private const val INBOX_VIEW_MODEL_PATH = "$APP_SOURCES/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}

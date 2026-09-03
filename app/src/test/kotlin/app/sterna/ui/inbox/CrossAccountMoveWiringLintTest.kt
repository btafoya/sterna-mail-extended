package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the call sites of the decisions [CrossAccountPickerTest]
 * executes (#189). `InboxViewModel`, `MessageViewModel` and the two screens cannot be instantiated
 * in a JVM test, so what is pinned here is the shipped text, WHOLE LINES and never `contains` alone
 * (a `contains` check is blind to every mutation that lengthens the line).
 *
 * What it is here to catch:
 *  1. the move between accounts growing an Undo — one that replays `moveToMailbox` on A with a
 *     folder id of B (#92), on a message that is already in A's bin;
 *  2. the two pickers listing an account other than [pickerAccount]'s, or excluding a folder
 *     other than [pickerExcludedMailbox]'s;
 *  3. a picker closing without putting its account choice back — the next opening would list the
 *     wrong account's folders under the message's own name;
 *  4. a string of the four this change adds missing from one of the nine languages, or the reader's
 *     tap no longer handing the chosen account down to the inbox ViewModel.
 *  5. the LIST's account row losing its [selectionPickerAccounts] scoping — a selection spanning
 *     accounts would be offered another account's folders, while the move itself keeps main's
 *     behaviour and skips everything outside the retained account (spec.md § 1);
 *  6. that same scoping being left to the ROW alone. The account choice is held by the ViewModel
 *     and outlives the sheet it was made in — rotation closes the sheet and keeps the choice — so
 *     the row can be gone while the picker still lists the chosen account's folders and the tap
 *     still moves there. What the picker lists and where the tap goes must come off the
 *     NEUTRALISED flow ([selectionMoveAccount]), not off `_moveAccountId`.
 */
class CrossAccountMoveWiringLintTest {

    @Test fun `the move between accounts has no Undo and never calls the same-account move`() {
        for (name in listOf("moveToAccount", "moveSelectedToAccount")) {
            val body = body(INBOX_VIEW_MODEL, name)
            assertEquals("$name() must not write _undo (A3: no Undo on a two-sided move). Body was:\n$body", emptyList<String>(), codeLinesNaming(body, "_undo"))
            assertEquals("$name() must not build an UndoAction. Body was:\n$body", emptyList<String>(), codeLinesNaming(body, "UndoAction"))
            assertEquals("$name() must not fall back to moveToMailbox: an id of B sent to A (#92). Body was:\n$body", emptyList<String>(), codeLinesNaming(body, "moveToMailbox"))
            assertEquals(
                "$name() must hand the repository exactly source, email, target and the folder id, whole. Body was:\n$body",
                listOf("runCatching { repo.moveToAccount(source, email, target, targetMailboxId) }"),
                codeLinesNaming(body, "repo.moveToAccount("),
            )
        }
    }

    @Test fun `the two moves route on the executed decision`() {
        val one = body(INBOX_VIEW_MODEL, "moveTo")
        assertEquals(
            "moveTo() must route on isCrossAccountMove against the MESSAGE's account. Body was:\n$one",
            listOf("if (isCrossAccountMove(targetAccountId, email.accountId)) {"),
            codeLinesNaming(one, "isCrossAccountMove("),
        )
        assertEquals(
            "and the same-account path must be the one it always was: swipeRemove + moveToMailbox. Body was:\n$one",
            listOf("repo.moveToMailbox(c, id, targetMailboxId)"),
            codeLinesNaming(one, "repo.moveToMailbox("),
        )
        val many = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        assertEquals(
            "moveSelectedTo() must route on isCrossAccountMove against the SELECTION's account. Body was:\n$many",
            listOf("if (isCrossAccountMove(targetAccountId, moveAccountId)) {"),
            codeLinesNaming(many, "isCrossAccountMove("),
        )
    }

    /**
     * ⛔ THE NUMBERING GUARD OF #99, CARRIED TO THE MOVE BETWEEN ACCOUNTS.
     *
     * An IMAP key is `imap:<account>:<folder>:<uid>` and carries nothing that says which numbering
     * that UID belongs to. A server that renumbers the shifting way makes the next background pass
     * `@Upsert` ANOTHER message's envelope under the exact text of a key that is already ticked:
     * the tick does not move, the new message is on screen already selected without a gesture, and
     * a tap on Move copies THAT message to account B and puts the original in A's Trash. There is
     * no Undo on this path (A3), so the user loses the gesture entirely: mail she never pointed at
     * is now in another account, and a message she may have wanted is in a bin she has to find.
     *
     * ⚠ Source lint, and only because `InboxViewModel` needs an `Application` and Room. The
     * decision itself is EXECUTED elsewhere — `UidValidity.destroyableUnderTheNumberingItWasTickedUnder`
     * is run by `DestroyOnlyWhatWasTickedTest`. What can only be read as text is the WIRING: where
     * the stamp is captured, which rows the "now" side is built from, and what is done with the
     * half the split refuses.
     */
    @Test fun `the move between accounts opposes the numbering the rows were ticked under`() {
        val one = body(INBOX_VIEW_MODEL, "moveSelectedTo")
        // What is lost if this line goes: nothing captures the stamp of the tick, the guard below
        // has no earlier fact to oppose, and the swap is invisible again.
        assertEquals(
            "moveSelectedTo() must capture the stamps of the tick beside the keys. Body was:\n$one",
            listOf("val ticked = tickedUnder"),
            codeLinesNaming(one, "val ticked ="),
        )
        // What is lost if the capture moves INSIDE the launch: moveSelectedToAccount calls
        // clearSelection() at its head, the collector on _selectedKeys empties the field, and the
        // captured map is empty — the guard passes everything and the loss is back whole.
        assertTrue(
            "…and BEFORE viewModelScope.launch: read a suspension later it is either empty (the " +
                "clearSelection() at the head of moveSelectedToAccount) or the numbering of the " +
                "very renumbering it exists to refuse. Body was:\n$one",
            one.indexOf("val ticked = tickedUnder") < one.indexOf("viewModelScope.launch"),
        )
        // ⛔ AND THE CAPTURE MUST BE HANDED ON. The capture above and the guard below are two
        // halves that only meet on THIS line, and nothing else in the repository reads it: pass
        // `emptyMap()` here — or drop the argument to a default — and the guard opposes an empty
        // map to every row, refuses nothing ever, and the whole volet is decoration. The user
        // loses exactly what the guard exists to save: a message she never pointed at is copied
        // to account B and hers goes to A's bin, with no Undo on this path.
        assertEquals(
            "moveSelectedTo() must hand the STAMPS IT JUST CAPTURED down to the cross-account " +
                "move — the local `ticked`, not an empty map and not a re-read. Body was:\n$one",
            listOf("moveSelectedToAccount(keys, moveAccountId, requireNotNull(targetAccountId), targetMailboxId, ticked)"),
            codeLinesNaming(one, "moveSelectedToAccount("),
        )

        val many = body(INBOX_VIEW_MODEL, "moveSelectedToAccount")
        val lines = many.lines().map { it.trim() }
        val call = lines.indexOf("val split = UidValidity.destroyableUnderTheNumberingItWasTickedUnder(")
        // What is lost if the cut is written inline here instead: a comparison no test can run,
        // and a second copy of a rule that already has two callers.
        assertTrue(
            "the cut must go through the shared pure function, not a twin written here. " +
                "Body was:\n$many",
            call >= 0,
        )
        // What is lost if an argument changes: hand it the same map twice (`readUnderNow` fed from
        // `ticked`, or `tickedUnder = { null }`) and it can never refuse anything, while every
        // name in the call still reads exactly as it does here.
        assertEquals(
            "and its ARGUMENTS are the rule: the cached rows about to leave, the stamp of the " +
                "TICK, and the stamp the row carries NOW. Body was:\n$many",
            listOf(
                "rows = emails.values.toList(),",
                "tickedUnder = { ticked[it.emailKey()] },",
                "readUnderNow = { email -> credentialsFor(email)?.let { now[it.id to email.id] } },",
                ")",
            ),
            lines.subList(call + 1, call + 5),
        )
        // What is lost if this becomes `repo.recordedUidValidity(...)`: the FOLDER's record is
        // what a background pass realigns the moment it meets a renumbering, so the guard would
        // oppose the new number to itself, agree, and licence the move it exists to refuse.
        assertEquals(
            "the 'now' side must be the per-ROW read, grouped per account. Body was:\n$many",
            listOf("val now = recordedNumbering(crossAccountTargets)"),
            codeLinesNaming(many, "recordedNumbering("),
        )
        // ⛔ AND THE ROWS IT READS ARE THE ROWS ABOUT TO MOVE. This line is one identifier away
        // from reading some other list: `now` would then hold no entry for any row being moved,
        // `readUnderNow` would answer null for every one of them, `drifted` would be empty by
        // construction — a guard that can never refuse is a guard that is not there, and the call
        // above would still read word for word as it does here.
        assertEquals(
            "the 'now' read must be built from the cached rows of the movable selection. " +
                "Body was:\n$many",
            listOf(
                "val crossAccountTargets = emails.values.mapNotNull { e -> credentialsFor(e)?.let " +
                    "{ Triple(it, e.id, e.mailboxId.orEmpty()) } }",
            ),
            codeLinesNaming(many, "crossAccountTargets ="),
        )
        // What is lost if the refused rows are attempted anyway: nothing at all is guarded — this
        // is the one line that turns the split into a refusal. `repo.moveToAccount(` is already
        // pinned to its single call site by the first test of this class, so the refusal cannot
        // be routed around by a second call.
        assertEquals(
            "what the split refused must be answered with a Failed and never attempted: no copy " +
                "on B, no Trash on A. Body was:\n$many",
            listOf(
                "val refused = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
                "if (key in refused) {",
            ),
            codeLinesNaming(many, "refused"),
        )
        // ⛔ AND THE WHOLE BODY OF THAT REFUSAL, `continue` INCLUDED. The two lines above say a
        // Failed is recorded; they say nothing about what happens next. Drop the `continue` and
        // the refused row is recorded as failed AND THEN MOVED ANYWAY — copied to B, original to
        // A's Trash — while the banner counts it as a failure and the give-back puts it back
        // under the tick: the screen would say the exact opposite of what the server did, and the
        // data loss this volet exists to close would be back whole and invisible.
        assertEquals(
            "the refusal must RETURN: a Failed and then `continue`, never a Failed followed by " +
                "the move it was supposed to prevent.",
            listOf(
                "if (key in refused) {",
                "results += CrossAccountMove.Failed(\"renumbered since it was ticked: \${key.emailId}\")",
                "continue",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "if (key in refused) {", 4),
        )
    }

    /**
     * What the split refused goes BACK under the user's eyes, still ticked, with the stamps of the
     * tick — and the stamps first.
     *
     * Given back without their stamps, the collector on `_selectedKeys` sees keys it has never met,
     * reads their numbering AS IT IS NOW, and the second tap on Move sends to B exactly what the
     * first tap saved: the guard would buy one tap. Dropped altogether, the refused messages leave
     * the selection in silence and the screen agrees with a half-done gesture that has no Undo.
     */
    @Test fun `what the cross-account move refused goes back into the selection, stamps first`() {
        val many = body(INBOX_VIEW_MODEL, "moveSelectedToAccount")
        assertEquals(
            "the refused rows must go back into the selection, still ticked, WITH THE STAMPS THEY " +
                "WERE TICKED UNDER. Body was:\n$many",
            listOf(
                "val refused = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
                "if (split.drifted.isNotEmpty()) {",
                "tickedUnder = split.drifted.associate { it.emailKey() to ticked[it.emailKey()] }",
                "_selectedKeys.value = split.drifted.mapTo(mutableSetOf()) { it.emailKey() }",
            ),
            codeLinesNaming(many, "split.drifted"),
        )
        // What is lost if the two lines swap: see above — the second tap moves what the first
        // refused, and nothing on screen ever says so.
        assertTrue(
            "…and the stamps BEFORE the keys: nothing suspends between the two lines, which is " +
                "the only reason the collector cannot slip in and re-read them. Body was:\n$many",
            many.indexOf("tickedUnder = split.drifted.associate") <
                many.indexOf("_selectedKeys.value = split.drifted"),
        )
        // What is lost without it: the keys are back but the selection bar is not, so there is
        // nothing on screen to act on them with.
        assertEquals(
            "with the selection bar back up. Body was:\n$many",
            listOf("_selectionActive.value = true"),
            codeLinesNaming(many, "_selectionActive"),
        )
        // What is lost if it moves earlier: clearSelection() runs at the head of this body, so a
        // re-selection written before the banner is wiped by the very gesture it belongs to.
        assertTrue(
            "⛔ and AFTER the banner, never before: clearSelection() is called at the head of " +
                "this body. Body was:\n$many",
            many.indexOf("val outcome = crossAccountBanner(results, skipped.size)") <
                many.indexOf("if (split.drifted.isNotEmpty()) {"),
        )
    }

    @Test fun `both pickers list the account pickerAccount names`() {
        assertEquals(
            "the list's picker must scope its folders on pickerAccount(chosen, owner), reading the " +
                "chosen account off the NEUTRALISED flow: fed `_moveAccountId`, it lists a stale " +
                "choice's account on a selection that has no account row at all (#189).",
            listOf(
                "private val movePickerAccountId: StateFlow<String?> =",
                "combine(moveAccountId, moveOwnerAccountId) { chosen, owner -> pickerAccount(chosen, owner) }",
            ),
            block(INBOX_VIEW_MODEL, "private val movePickerAccountId", 2),
        )
        assertEquals(
            "the list's folder list must be observed on THAT account and no other.",
            listOf(
                "val selectionMailboxes: StateFlow<List<Mailbox>> =",
                "movePickerAccountId.flatMapLatest { accountId ->",
                "if (accountId == null) flowOf(emptyList()) else repo.observeMailboxes(accountId)",
            ),
            block(INBOX_VIEW_MODEL, "val selectionMailboxes:", 3),
        )
        assertEquals(
            "and its 'only subscribed' flag must be read off THAT account (#174, #92).",
            listOf(
                "val selectionOnlySubscribed: StateFlow<Boolean> =",
                "combine(movePickerAccountId, store.accountsFlow) { accountId, accounts -> showOnlySubscribedFor(accountId, accounts) }",
            ),
            block(INBOX_VIEW_MODEL, "val selectionOnlySubscribed:", 2),
        )
        assertEquals(
            "the reader's picker must scope its folders on pickerAccount(chosen, owner).",
            listOf(
                "private val movePickerAccountId: Flow<String?> =",
                "combine(_moveAccountId, _ownerAccountId) { chosen, owner -> pickerAccount(chosen, owner) }",
            ),
            block(MESSAGE_VIEW_MODEL, "private val movePickerAccountId", 2),
        )
        assertEquals(
            "the reader's excluded folder must be pickerExcludedMailbox's, never the message's folder as such.",
            listOf("combine(_moveAccountId, _ownerAccountId, _mailboxId) { chosen, owner, current -> pickerExcludedMailbox(chosen, owner, current) },"),
            block(MESSAGE_VIEW_MODEL, "combine(_moveAccountId, _ownerAccountId, _mailboxId)", 1),
        )
        assertEquals(
            "the list's excluded folder must be pickerExcludedMailbox's.",
            listOf(
                "val moveExcludedMailbox = pickerExcludedMailbox(moveAccountId, moveOwnerAccountId, ui.selectedMailboxId)",
                "val targets = remember(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed) {",
                "moveTargets(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed)",
            ),
            block(INBOX_SCREEN, "val moveExcludedMailbox =", 3),
        )
    }

    @Test fun `each picker puts its account choice back when it closes`() {
        assertEquals(
            "the list's picker must reset the account choice on dismiss.",
            listOf("onDismissRequest = { showMoveSheet = false; viewModel.chooseMoveAccount(null) },"),
            block(INBOX_SCREEN, "onDismissRequest = { showMoveSheet", 1),
        )
        assertEquals(
            "the reader's picker must reset the account choice on dismiss.",
            listOf("onDismissRequest = { movePicker = false; viewModel.chooseMoveAccount(null) },"),
            block(MESSAGE_SCREEN, "onDismissRequest = { movePicker", 1),
        )
        assertEquals(
            "the list's tap must hand the chosen account to the move, then close, then reset.",
            listOf(
                "viewModel.moveSelectedTo(row.folder.id, moveAccountId)",
                "showMoveSheet = false",
                "viewModel.chooseMoveAccount(null)",
            ),
            block(INBOX_SCREEN, "viewModel.moveSelectedTo(row.folder.id", 3),
        )
        assertEquals(
            "the reader's move must reach the inbox ViewModel with the chosen account.",
            listOf("inboxViewModel.moveTo(email, targetMailboxId, targetAccountId)"),
            block(STERNA_APP, "inboxViewModel.moveTo(", 1),
        )
    }

    /**
     * The choice is neutralised ONCE, where every reader of it passes (#189). The row being hidden
     * is not enough: `_moveAccountId` is only put back by the picker's three exits, and the sheet
     * has other ways out — `showMoveSheet` is a plain `remember`, so a rotation destroys it while
     * the ViewModel keeps the choice. The next opening, on a selection that now spans accounts,
     * would list the chosen account's folders (Inbox/Archive/Trash read the same in both) and send
     * the gesture there.
     */
    @Test fun `the account choice is ignored while the selection resolves no account`() {
        assertEquals(
            "the PUBLIC moveAccountId must be the neutralised flow, not _moveAccountId.asStateFlow(): " +
                "it is what the picker lists on and what InboxScreen hands to moveSelectedTo.",
            listOf(
                "val moveAccountId: StateFlow<String?> =",
                "combine(_moveAccountId, _selectedKeys) { chosen, keys -> selectionMoveAccount(chosen, keys) }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, null)",
            ),
            block(INBOX_VIEW_MODEL, "val moveAccountId:", 3),
        )
        assertEquals(
            "chooseMoveAccount() must write the id it is given, whatever it is — a guard such as " +
                "`if (id != null)` makes the picker's three exits unable to put the choice back, " +
                "and the choice becomes permanent.",
            listOf(
                "fun chooseMoveAccount(id: String?) {",
                "_moveAccountId.value = id",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "fun chooseMoveAccount", 3),
        )
        assertEquals(
            "MoveAccountRow must return on FEWER THAN TWO accounts: since #189 scopes the row on " +
                "the selection it is handed an EMPTY list, a value it never got before, and " +
                "`ordered.first()` on it throws when the picker opens.",
            listOf("if (ordered.size < 2) return"),
            block(MOVE_ACCOUNT_ROW, "if (ordered.size < 2) return", 1),
        )
    }

    /**
     * The account row of the LIST is fed a selection-scoped list, the reader's is not (#189).
     * A selection spanning accounts resolves no account, so `moveOwnerAccountId` falls back to the
     * ACTIVE account (`InboxViewModel.kt:990-992`) and the row would offer every account, while
     * `moveSelectedTo` only ever moves the retained account's messages and counts the rest as
     * skipped (spec.md § 1). [selectionPickerAccounts] is what closes that gap, and it only closes
     * it if the screen actually calls it. The reader has no such fallback — its owner IS the open
     * message's account — so its call site must stay the plain one.
     */
    @Test fun `only the list's account row is scoped on the selection`() {
        assertEquals(
            "the list's picker must feed the row selectionPickerAccounts(accounts, selectedKeys), " +
                "never the raw account list: on a selection spanning accounts the row would offer " +
                "'B › Archive' while the move skips everything that is not the owner's (spec.md § 1).",
            listOf("MoveAccountRow(selectionPickerAccounts(accounts, selectedKeys), moveOwnerAccountId, moveAccountId, viewModel::chooseMoveAccount)"),
            block(INBOX_SCREEN, "MoveAccountRow(", 1),
        )
        assertEquals(
            "the reader's picker must keep the plain account list: it opens on ONE message, its " +
                "owner is that message's account and never a fallback, so scoping it on a " +
                "selection would take the row away from the case #189 exists for.",
            listOf("MoveAccountRow(accounts, moveOwnerAccountId, moveAccountId, viewModel::chooseMoveAccount)"),
            block(MESSAGE_SCREEN, "MoveAccountRow(", 1),
        )
    }

    /**
     * The reader has TWO whole folder lists and they are not the same account's (#189): the
     * picker's follows the account row, the sender rule's stays the open message's own. Crossing
     * them writes a Sieve `fileinto` for A computed on B's folders — the very thing
     * [mailboxFilePath] exists to prevent (#92) — and no JVM test can reach it: `MessageViewModel`
     * needs an `Application`.
     */
    @Test fun `the sender rule keeps the message's own folder list, only the picker follows the row`() {
        assertEquals(
            "MessageViewModel.accountMailboxes must stay scoped on _ownerAccountId: blockSender " +
                "names the account's Trash in a server-side rule from it. Scoped on the PICKER's " +
                "account, a rule of A files A's future mail into a path A does not have (#92).",
            listOf(
                "val accountMailboxes: StateFlow<List<Mailbox>> = _ownerAccountId.flatMapLatest { id ->",
                "if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)",
                "}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())",
            ),
            block(MESSAGE_VIEW_MODEL, "val accountMailboxes:", 3),
        )
        assertEquals(
            "and the picker's own whole list — the one that resolves parent paths (#109) — is " +
                "pickerMailboxes, which is the ONLY one that follows the account row.",
            listOf(
                "val pickerMailboxes: StateFlow<List<Mailbox>> = movePickerAccountId.flatMapLatest { id ->",
                "if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)",
                "}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())",
            ),
            block(MESSAGE_VIEW_MODEL, "val pickerMailboxes:", 3),
        )
        assertEquals(
            "the reader's picker must read pickerMailboxes for its parent paths.",
            listOf("val accountFolders by viewModel.pickerMailboxes.collectAsStateWithLifecycle()"),
            block(MESSAGE_SCREEN, "val accountFolders by", 1),
        )
        val blocked = body(MESSAGE_VIEW_MODEL, "blockSender")
        assertEquals(
            "blockSender() must name the Trash from the OPEN MESSAGE's account list. Body was:\n$blocked",
            listOf("val trashPath = trashFilePath(accountMailboxes.value)"),
            codeLinesNaming(blocked, "trashFilePath("),
        )
        assertEquals(
            "and the participants panel's entry must be decided on that same list.",
            listOf("trashFilePath(accountMailboxes),"),
            block(MESSAGE_SCREEN, "trashFilePath(", 1),
        )
    }

    /**
     * Neither banner may name a string of its own: which sentence goes with which verdict is
     * [crossAccountMessageRes]'s and [crossAccountSelectionPlural]'s, executed by
     * [CrossAccountPickerTest]. Announced as "moved", a copy whose original stayed hides a
     * duplicate, and there is no Undo to take it back.
     */
    @Test fun `both banners take their words from the executed decision`() {
        val one = body(INBOX_VIEW_MODEL, "moveToAccount")
        assertEquals(
            "moveToAccount() must read its sentence off crossAccountMessageRes(result, online). Body was:\n$one",
            listOf("val words = crossAccountMessageRes(result, online = hasUsableNetwork(app))"),
            codeLinesNaming(one, "crossAccountMessageRes("),
        )
        for (hardcoded in listOf(
            "R.string.status_moved_to_account",
            "R.string.status_copied_not_removed",
            "R.string.status_action_offline",
        )) {
            assertEquals(
                "moveToAccount() must not name $hardcoded itself. Body was:\n$one",
                emptyList<String>(),
                codeLinesNaming(one, hardcoded),
            )
        }
        val many = body(INBOX_VIEW_MODEL, "moveSelectedToAccount")
        assertEquals(
            "the selection's two counted banners must take their plural from " +
                "crossAccountSelectionPlural(outcome) — the outcome crossAccountBanner returned, " +
                "never a re-decision here. Body was:\n$many",
            listOf(
                "checkNotNull(crossAccountSelectionPlural(outcome)), results.size, results.size,",
                "checkNotNull(crossAccountSelectionPlural(outcome)), copied, copied, where, accountLabel(ownerAccountId),",
            ),
            codeLinesNaming(many, "crossAccountSelectionPlural("),
        )
        assertEquals(
            "and its plain line from crossAccountSelectionFailedRes(outcome). Body was:\n$many",
            listOf("_message.value = app.getString(checkNotNull(crossAccountSelectionFailedRes(outcome)))"),
            codeLinesNaming(many, "crossAccountSelectionFailedRes("),
        )
        for (hardcoded in listOf(
            "R.plurals.status_selection_moved",
            "R.plurals.status_selection_copied_not_removed",
            "R.string.status_action_partly_failed",
        )) {
            assertEquals(
                "moveSelectedToAccount() must not name $hardcoded itself. Body was:\n$many",
                emptyList<String>(),
                codeLinesNaming(many, hardcoded),
            )
        }
    }

    @Test fun `the four strings of the move between accounts exist in the nine languages`() {
        val expected = setOf(
            "inbox_move_account",
            "status_moved_to_account",
            "status_copied_not_removed",
            "status_selection_copied_not_removed",
        )
        val files = listOf(File(res, "values/strings.xml")) + (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
        assertEquals("nine string files", 9, files.size)
        val missing = files.associate { it.parentFile.name to (expected - keysOf(it)) }.filterValues { it.isNotEmpty() }
        assertEquals("strings of the move between accounts missing in", emptyMap<String, Set<String>>(), missing)
    }

    // ── instrument (copied from BulkSelectionWiringTest / MoveFilterWiringLintTest) ─────────────

    private fun keysOf(file: File): Set<String> =
        Regex("<(?:string|plurals)\\s+name=\"([^\"]+)\"").findAll(file.readText()).map { it.groupValues[1] }.toSet()

    private fun codeLinesNaming(body: String, needle: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .filter { needle in it }

    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trimStart()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }

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

    /** [count] consecutive trimmed code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file).map { it.trim() }
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error("${hits.size} code lines of ${file.name} start with `$prefix` — teach this lint the new shape rather than leave it green over something it never read")
        return lines.subList(at, minOf(at + count, lines.size))
    }

    private companion object {
        private const val APP = "app/src/main"
        private const val INBOX_VIEW_MODEL_PATH = "$APP/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
        val MESSAGE_VIEW_MODEL: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
        val INBOX_SCREEN: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/inbox/InboxScreen.kt") }
        val MOVE_ACCOUNT_ROW: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/inbox/MoveAccountRow.kt") }
        val MESSAGE_SCREEN: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/message/MessageScreen.kt") }
        val STERNA_APP: File by lazy { File(root, "$APP/kotlin/app/sterna/ui/SternaApp.kt") }
        val res: File by lazy { File(root, "$APP/res") }
    }
}

package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — it reads `ComposeViewModel.kt` as text and proves nothing
 * about what runs.
 *
 * It exists because of a blind spot the auditors named: **no test in this module instantiates
 * `ComposeViewModel`** (it is an `AndroidViewModel`, there is no Robolectric and no instrumented
 * test here), and yet that class is where the two facts the leave dialog and the Delete button are
 * drawn from get settled. Both decisions are pure functions with tests of their own
 * ([savedDraftBehindScreen] in [DiscardWordingTest], [draftDeleteOffered] in
 * [DraftDeleteOfferedTest]); what nothing could see is what this class FEEDS them, and when.
 *
 * WHAT IT DOES NOT COVER, said plainly:
 *  - that `prepare` is ever called, or called with the route's real arguments;
 *  - that the flows it writes reach the composables that read them;
 *  - anything about ordering at RUNTIME beyond the order the statements are written in;
 *  - anything at all about what is drawn.
 */
class ComposeViewModelWiringTest {

    @Test fun `what can survive this screen is settled from both facts, not from the route alone`() {
        val body = prepareBody().replace(Regex("""\s+"""), " ")
        assertTrue(
            "prepare() must settle the saved-draft fact through savedDraftBehindScreen(draftId, " +
                "if (restore) outbox.restored.value?.draftEmailId else null) — the whole " +
                "expression. `draftId` alone is the defect (#35): an undone send reopens the " +
                "composer with restore=true and NO draftId in the route, while the draft it was " +
                "sent from is still sitting in Drafts, so the dialog announced the destruction of " +
                "a message that exists. And the restored id must be read BEFORE the restore branch " +
                "below consumes it. Body was:\n$body",
            "_savedDraftBehind.value = savedDraftBehindScreen(draftId, " +
                "if (restore) outbox.restored.value?.draftEmailId else null)" in body,
        )
    }

    @Test fun `the deletable draft is taken only after the draft has actually been read`() {
        // The auditors' mutation, and it is the one a well-meaning reviewer writes: move
        // `_editingDraft.value = …` two lines up, "the cached row is free, take it first". Offline
        // the composer then opens EMPTY, with the prefill-failed warning — and offers a Delete
        // button for a draft whose contents the user has never seen. The clause exists precisely
        // so the button is not offered over a draft we could not read.
        val body = prepareBody()
        val fetch = body.indexOf("repo.fetchEmail(")
        val take = body.indexOf("_editingDraft.value =")
        assertTrue("prepare() no longer fetches the draft — did the reopen path move?", fetch >= 0)
        assertTrue("prepare() no longer records the draft to delete — did it move?", take >= 0)
        assertTrue(
            "the row the Delete button acts on must be taken AFTER repo.fetchEmail(...) has " +
                "returned: the fetch is what fails offline, and everything below it is skipped. " +
                "Above it, the button appears on a composer that shows nothing. Body was:\n$body",
            fetch < take,
        )
        assertTrue(
            "it must come from the cache the Drafts list itself was drawn from (repo.cachedEmail), " +
                "not from the fetched value: the delete needs a row that is really there. " +
                "Body was:\n$body",
            Regex("""_editingDraft\.value = runCatching \{ repo\.cachedEmail\(""").containsMatchIn(body),
        )
    }

    @Test fun `the reopened draft's prefill is handed the cached row, account-scoped`() {
        // The behaviour lives in draftFieldsOf, which is pure and executed by ComposeTextTest; what
        // no test here can see is whether this class still FEEDS it the cached row. Without the
        // second argument the fallback is simply never armed, and every recipient test stays green.
        // The arguments are pinned, not just the call: `credentials.id` is the account scope (#31),
        // and dropping it would let one sub-account's cached row prefill another's draft.
        val body = prepareBody().replace(Regex("""\s+"""), " ")
        assertTrue(
            "prepare() must build the draft prefill as draftFieldsOf(draft, _editingDraft.value): " +
                "the cached row is the only place a recipient the server dropped still exists " +
                "(#96). Body was:\n$body",
            "_prefill.value = draftFieldsOf(draft, _editingDraft.value)" in body,
        )
        val cache = body.indexOf(
            "_editingDraft.value = runCatching { repo.cachedEmail(credentials.id, draftId) }.getOrNull()",
        )
        val prefill = body.indexOf("_prefill.value = draftFieldsOf(")
        assertTrue(
            "the cached row must be read from repo.cachedEmail(credentials.id, draftId) — " +
                "account-scoped (#31). Body was:\n$body",
            cache >= 0,
        )
        assertTrue(
            "the cached row must be taken BEFORE the prefill is built, or the prefill is handed a " +
                "flow that is still null and the fallback is dead code — the composer opens with " +
                "the field the server emptied, which is the whole defect. Body was:\n$body",
            prefill > cache,
        )
    }

    /**
     * ⭐ Who a reply is addressed to is decided in ONE place, [replyRecipient] /
     * [replyAllRecipients], and this class must keep asking it.
     *
     * The notification path is pinned this way already (`QuickReplyWiringTest`), the composer's
     * two were not — and they are the ones the button in the reader goes through. Inlining
     * `original.from.firstOrNull()?.email.orEmpty()` back here is a one-line edit that reads as a
     * harmless simplification, leaves every pure-function test green, and quietly restores the
     * defect: a sender who set `Reply-To` gets the answer at the address they set aside.
     *
     * The WHOLE line is compared, never `contains`: a mutation that lengthens it — adding the
     * From beside the Reply-To, say — slips straight through a substring check.
     */
    @Test fun `the composer asks the one function who a reply answers`() {
        val body = bodyOf("buildPrefill")
        assertEquals(
            "buildPrefill must address replies through ComposeText's replyRecipient/" +
                "replyAllRecipients and nothing else. Reading `original.from` here is the defect: " +
                "Reply-To names where the sender wants the answer, and answering the From posts " +
                "it to an address they deliberately set aside. Body was:\n$body",
            listOf(
                "to = replyAllRecipients(original, selves),",
                "to = replyRecipient(original),",
            ),
            body.lines().map { it.trim() }.filter { it.startsWith("to = ") && it != "to = \"\"," },
        )
    }

    /**
     * ⛔ The queued row a composer was editing is DESTROYED by `consumeEditingOutbox` —
     * `repo.deleteOutbox(id)` takes the row and its staged attachment directory. Since #95 a save
     * can come back having reached nothing: KEPT_ON_DEVICE means the draft is on the phone alone,
     * and no screen shows local drafts yet. Consuming the queued row on that outcome removes the
     * only copy the user can still see, and the files with it.
     *
     * The decision is a pure function executed in `LocalDraftSaveTest`; what nothing here can
     * execute is whether this class still ASKS it. The whole line is compared, so
     * `if (draftSaveConsumesTheQueuedRow(outcome) || true)` does not slip through.
     */
    @Test fun `the queued row is dropped only for a draft that really reached the server`() {
        val body = bodyOf("saveDraft")
        assertEquals(
            "saveDraft must consume the edited outbox row behind " +
                "draftSaveConsumesTheQueuedRow(outcome), and nowhere else in this body. " +
                "Body was:\n$body",
            listOf("if (draftSaveConsumesTheQueuedRow(outcome)) consumeEditingOutbox()"),
            body.lines().map { it.trim() }.filter { "consumeEditingOutbox()" in it },
        )
    }

    /**
     * ⛔ The other half of the same outcome, and the one that decides whether the message is SENT.
     * A row that is not consumed is not "left alone": left in `EDITING` it is on no screen (the
     * Outbox screen and the badge both filter EDITING out) and the next launch reverts it to QUEUED
     * and delivers the PRE-EDIT text, with no undo window, after the user tapped "save as draft".
     *
     * The state is decided by a pure function executed in `LocalDraftSaveTest`; what nothing here
     * can execute is whether this class still ASKS it, and whether it asks BEFORE consuming (the
     * consume clears `editingOutboxId`, so a park written after it would silently do nothing).
     * Whole lines, so a lengthening mutation cannot slip past.
     */
    @Test fun `the queued row that survives the save is parked, and parked before anything drops it`() {
        val body = bodyOf("saveDraft")
        assertEquals(
            "saveDraft must park the edited outbox row behind draftSaveParksTheQueuedRowAs(" +
                "outcome), and nowhere else in this body. Body was:\n$body",
            listOf("draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }"),
            body.lines().map { it.trim() }.filter { "parkEditingOutbox(" in it },
        )
        val park = body.indexOf("parkEditingOutbox(")
        val consume = body.indexOf("consumeEditingOutbox()")
        assertTrue("saveDraft no longer parks the edited row at all", park >= 0)
        assertTrue("saveDraft no longer consumes the edited row at all", consume >= 0)
        assertTrue(
            "the park must be written BEFORE the consume: consumeEditingOutbox() sets " +
                "editingOutboxId to null, so a park below it returns without touching the row and " +
                "the message is left EDITING — the defect, with a line of code that looks right. " +
                "Body was:\n$body",
            park < consume,
        )
    }

    /**
     * ⛔ THE WHOLE BODY, line by line and in order — a filter on one name is blind to the mutation
     * that costs the least to write: MOVE the park line into the `if (draftSaveNeedsNotice(outcome))`
     * block right above it. Nothing is added, nothing is lengthened, every `contains` rule and every
     * "these are the lines naming parkEditingOutbox" rule stays green — and the fix is disarmed
     * outright, because `draftSaveNeedsNotice` is true only for ORIGINAL_KEPT, which never parks
     * anything. Only the ORDERED, COMPLETE list of statements sees a line change nesting.
     *
     * The price is stated: any honest refactor of this function turns this red. That direction of
     * error is loud and one minute to fix; the other direction is a fix that comes silently undone.
     */
    @Test fun `the whole of saveDraft, statement by statement`() {
        val body = bodyOf("saveDraft")
        assertEquals(
            "ComposeViewModel.saveDraft is pinned whole. If you meant to change it, read the two " +
                "⛔ comments in it first: the park must be a statement of the submit block itself " +
                "(not nested in a condition) and must come before the consume. Body was:\n$body",
            listOf(
                "if (!draftSaveAllowed(_pgpMode.value)) {",
                "_notices.tryEmit(R.string.compose_pgp_no_draft)",
                "return",
                "}",
                "if (!hasDraftContent(to, cc, bcc, subject, body)) {",
                "val original = editingDraftId",
                "val originalUidValidity = editingDraftUidValidity",
                // ⛔ The close that accompanies a deletion hands the phone's own row to NOBODY —
                // abandon() would otherwise put it back PENDING and re-arm its upload, sending the
                // pre-edit text of the draft just emptied up to the server (#95 × #69) — and it is
                // said AS AN ARGUMENT. Said by blanking `editingLocalDraftId` first, as it was, a
                // submit() that FAILED left the row EDITING with nobody left to release it: no
                // upload worker will touch it again before the next cold start.
                "abandon(emptying = true)",
                "if (original == null) {",
                "_state.value = ComposeState.Done",
                // ⛔ A `local-draft:` id names nothing repo.discardDraft can destroy: under IMAP
                // numbering it answers an empty list, so the emptied draft survived and went up on
                // the next network while the screen closed as a success. And the row is not taken
                // away on its own either: while it exists it MASKS the server draft it replaces, so
                // consuming it alone brings that draft back with its pre-edit text (#95 × #69).
                "} else if (draftOpenRoute(original) == DraftOpenRoute.LOCAL) {",
                "submit(to) { credentials, _ -> destroyReplacedServerDraft(credentials, original) }",
                "} else {",
                // ⛔ And the server route reads the SAME verdict the local one does: a draft this
                // composer never read whole keeps its server copy and the screen says so
                // (emptiedServerDraftIsKept). Unconditional, this line expunged a draft made of one
                // inline image the instant it was saved — drawn empty, because draftHasContent
                // counts neither inline images nor calendar parts. The notice sits INSIDE the
                // lambda: submit() reaches Done after it returns.
                "submit(to) { credentials, _ ->",
                "if (emptiedServerDraftIsKept(editingDraftLossy)) {",
                "_notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept)",
                "} else {",
                "repo.discardDraft(credentials, original, originalUidValidity)",
                "}",
                "}",
                "}",
                "return",
                "}",
                "submit(to) { credentials, recipients ->",
                "val identity = selectedIdentity()",
                "val outcome = repo.saveDraft(",
                "credentials, recipients, subject, body, parseAddrs(cc), parseAddrs(bcc),",
                "inReplyTo = inReplyTo, references = references,",
                "replacesEmailId = editingDraftId,",
                "replacesUidValidity = editingDraftUidValidity,",
                "attachments = _attachments.value,",
                "bodyIsLossy = editingDraftLossy,",
                "fromName = identity?.name, fromEmail = identity?.email,",
                "requestReceipt = requestReceipt,",
                ")",
                "if (draftSaveNeedsNotice(outcome)) {",
                "_notices.tryEmit(R.string.compose_draft_original_kept)",
                "}",
                "draftSaveParksTheQueuedRowAs(outcome)?.let { parkEditingOutbox(it) }",
                "if (draftSaveConsumesTheQueuedRow(outcome)) consumeEditingOutbox()",
                "}",
            ),
            codeOf(body),
        )
    }

    /**
     * ⛔ And the park has to SURVIVE the screen going away — the whole body again, for the same
     * reason: `viewModelScope.launch` here instead of `appScope.launch` is one word, and it is the
     * defect. The save runs in `viewModelScope` (through `submit`), the user's back gesture during
     * the spinner cancels it, and a park scheduled on that scope never writes: the row stays
     * EDITING and the next launch sends the pre-edit message. `abandon()` uses `appScope` for
     * exactly this, and the two are the same problem.
     *
     * The id is read and cleared BEFORE the launch, synchronously, so the ordering against
     * `consumeEditingOutbox` cannot depend on when a coroutine gets scheduled.
     */
    @Test fun `the park outlives the screen, like abandon does`() {
        val body = bodyOf("parkEditingOutbox")
        assertEquals(
            "parkEditingOutbox must take the row's id synchronously and then write through " +
                "appScope — never viewModelScope, which the back gesture cancels. Body was:\n$body",
            listOf(
                "val id = editingOutboxId ?: return",
                "editingOutboxId = null",
                "_editingOutbox.value = false",
                "appScope.launch {",
                "runCatching { repo.parkOutboxEdit(id, state) }",
                ".onFailure { android.util.Log.w(\"SternaCompose\", \"couldn't park the edited outbox item\", it) }",
                "}",
            ),
            codeOf(body),
        )
    }

    // -- reading the source ---------------------------------------------------------------------

    /** The body of `fun prepare(`, braces balanced, comments cut. */
    private fun prepareBody(): String = bodyOf("prepare")

    /** [body]'s code lines, trimmed and blanks dropped — the shape the whole-body rules compare. */
    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The body of `fun [name](` in `ComposeViewModel.kt`, braces balanced, comments cut. */
    private fun bodyOf(name: String): String {
        val code = codeLines(COMPOSE_VIEW_MODEL).joinToString("\n")
        val at = code.indexOf("fun $name(")
        check(at >= 0) { "ComposeViewModel.kt declares no '$name(' — did it get renamed?" }
        val start = code.indexOf('{', code.indexOf(')', at)) + 1
        var depth = 1
        var i = start
        while (i < code.length && depth > 0) {
            when (code[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return code.substring(start, (i - 1).coerceAtLeast(start))
    }

    /** The lines of [file] that are code, with comments taken off: the comments here name the very
     *  calls and the very ordering the rules above pin. */
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

    companion object {
        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}

package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * ⚠ SOURCE LINT, NOT A BEHAVIOUR TEST. It reads TWO sources as TEXT — `ComposeViewModel.kt`,
 * where the flag is set, and `ComposeScreen.kt`, where it is handed on — and proves nothing
 * whatsoever about what runs: no composer is built here, no `prepare()` is called, no flow is
 * collected. Nothing in this file says the composer is "tested"; it is not, and there was no defect
 * to fix when these rules were written — the wiring they pin is correct today and they exist to
 * keep it that way.
 *
 * What is pinned is the WHOLE chain, as text, link by link: the write in `prepare`, WHERE in
 * `prepare` that write stands, the field it writes to and the flow that exposes it, the line the
 * screen collects that flow in, and the argument handed to the predicate. Cutting ANY one of those
 * links loses the message while every executed test in the repo stays green — which is why they are
 * pinned as text and not inferred from one another.
 *
 * And, since the chain only carries the truth if it is handed one, the chain's PREMISE: that the
 * draft kept for the undo names no queued row. That premise is held by an OMISSION in `sendInternal`
 * — a field simply not named at a construction site — so it is pinned as an omission, negatively.
 * None of it is executed here.
 *
 * Why text is the only instrument available: `ComposeViewModel` is an `AndroidViewModel` reaching
 * for `Application`, `AppContainer` and Room, and this module has neither Robolectric nor
 * `compose-ui-test` — no JVM test in the repo instantiates it. The DECISION that depends on the
 * flag is executed: [ComposeDirty.isDirty] is pure and [ComposeDirtyTest] runs it, including the
 * `onlyCopy = true` case. What no executed test can see is whether this class still FEEDS it the
 * right value, from the one place that knows the fact.
 *
 * The fact in question: reopening after "Undo" on the send banner destroys the outbox row and its
 * staged directory, so the message exists ONLY on this screen and only in RAM. `_onlyCopy = true`
 * is what makes `dirty` true with nothing typed, which is what makes the back gesture ask
 * "Discard message?" instead of closing. Inverting the feed silences that dialog on the single
 * path where it protects anything, and every executed test — [ComposeDirtyTest] included — stays
 * green, because the pure function is still correct; it is just never told.
 *
 * WHAT IT DOES NOT COVER, said plainly:
 *  - that `prepare` is ever called, or called with `restore = true` on the undo path;
 *  - that `outbox.restored.value` carries a null `editingOutboxId` at RUNTIME after an undo. One
 *    rule below reads the one place that builds that record, and reads it as text: it sees that the
 *    field is not named there. It does not see the queue, the hold window, `SendOutbox` itself, any
 *    other construction of a `ComposeDraft`, nor that the record reaching `prepare` is that one;
 *  - that the lambda holding the pinned write ever runs, or runs with a non-null `restored` — the
 *    position rule reads where the line is WRITTEN, which is not where it executes;
 *  - that the verdict `ComposeDirty.isDirty` returns reaches the back handler or the dialog, or
 *    that the dialog is shown at all — the argument handed to that call is pinned below, as text,
 *    and the chain after it is not;
 *  - anything at all about what is drawn, or about the order these lines run in at RUNTIME beyond
 *    the order they are written in.
 */
class OnlyCopyWiringTest {

    /**
     * ⛔ WHOLE LINES, never `contains`. The mutation this rule exists for is
     * `d.editingOutboxId != null` — one character — and the mutation a substring check cannot see
     * is `d.editingOutboxId == null || true`, which LENGTHENS the line and slips through every
     * `"…" in body` written in this repo.
     *
     * The two lines are pinned together and in order because they are two faces of one fact: the id
     * that says which queued row this composer is editing, and the flag that says there is no such
     * row and therefore no other copy. Reading the id and not settling the flag beside it is the
     * same defect written differently.
     */
    @Test fun `the undone send is marked as the only copy, from the id and nothing else`() {
        val body = bodyOf("bindQueuedRow")
        assertEquals(
            "After \"Undo\" on the send banner the queued row and its staged directory are gone: " +
                "the message lives on this screen alone, in memory. These lines are what tells " +
                "the composer so. Invert or weaken the last one and the back gesture closes the " +
                "composer in silence and the message is lost, with no dialog and no draft — and " +
                "ComposeDirtyTest stays green throughout, because the rule it runs is still right, " +
                "it is simply never handed the truth. The observable face of the id " +
                "(_editingOutbox — the title, the leave dialog) is settled beside it, from the " +
                "same field: a composer rebuilt after a process death gets it from here too. " +
                "Body of bindQueuedRow() was:\n$body",
            listOf(
                "_editingOutbox.value = d.editingOutboxId != null",
                "editingOutboxId = d.editingOutboxId",
                "_onlyCopy.value = d.editingOutboxId == null",
            ),
            codeLinesNaming(body, "editingOutboxId"),
        )
        assertEquals(
            "⛔ prepare() names editingOutboxId again. The id and the flag are written ONCE, in " +
                "bindQueuedRow, which the restored lambda of prepare() and the resume after a " +
                "process death both call — a second write here wins by running later",
            emptyList<String>(),
            codeLinesNaming(prepareBody(), "editingOutboxId"),
        )
    }

    /**
     * ⛔ The census the rule above cannot see through. It reads the body of `prepare` only; a
     * `_onlyCopy.value = false` written ANYWHERE else in the file — in `abandon`, in a `reset`, in
     * a text-changed callback — falls outside it and used to pass. So EVERY line of this file that
     * names the flag is listed here, whole and in the order it is written: the declaration, the
     * exposure, and the one write. A second one has to be argued for here before it can exist.
     *
     * ⛔ The filter names `_onlyCopy`, not `_onlyCopy.value`, because the field is a
     * `MutableStateFlow`: `tryEmit(false)`, `emit(false)` and `update { false }` are writes too,
     * they walked straight through a `.value` filter unseen, and they win the way any second write
     * wins — by running later.
     *
     * ⭐ Sweeping the whole name in also catches the link no other rule here can see: swap
     * `_onlyCopy.asStateFlow()` for `MutableStateFlow(false).asStateFlow()` and `prepare` still
     * writes the truth, into a flow nobody collects, while the screen reads false for ever. That
     * line falls out of this list, so this rule reddens.
     */
    @Test fun `every line naming the flag in the file is one of these three`() {
        val lines = codeLines(COMPOSE_VIEW_MODEL)
        assertEquals(
            "⛔ a line naming _onlyCopy was added, moved or removed. This flag is the composer's " +
                "single reason to guard a message that exists nowhere else, and a second write " +
                "elsewhere in the file wins by running later: clearing it on the way out of the " +
                "restore path closes the screen silently on an undone send and loses it. Cutting " +
                "the field from the flow the screen collects loses it just as quietly. One " +
                "declaration, one exposure, and one write in bindQueuedRow(), taken from the " +
                "restored row's id — called by the restored lambda of prepare() and by the " +
                "resume of a composer rebuilt after a process death",
            listOf(
                "private val _onlyCopy = MutableStateFlow(false)",
                "val onlyCopy: StateFlow<Boolean> = _onlyCopy.asStateFlow()",
                "_onlyCopy.value = d.editingOutboxId == null",
            ),
            lines.map { it.trim() }.filter { "_onlyCopy" in it },
        )
    }

    /**
     * ⛔ The link BETWEEN the two files, pinned as a whole line. The rule above watches the flow
     * being exposed and the rule below watches the argument reaching the predicate; neither sees
     * the single line that joins them. Replace it with `val onlyCopy = false` and every other rule
     * in this file stays green, [ComposeDirtyTest] stays green, and a composer reopened by "Undo"
     * on the send banner closes on the back gesture in silence, with the only copy of the message
     * in it.
     *
     * ⛔ WHOLE LINE, never `contains`: `…collectAsStateWithLifecycle().let { false }` LENGTHENS the
     * line and walks through any substring check.
     *
     * ⛔ And a CENSUS of every `val`/`var` named `onlyCopy` on this screen, not just of the one line
     * that reads the view model. The rule below compares the TEXT handed to the verdict, so it
     * cannot tell which declaration that name is bound to — the first hole of `CONTRIBUTING.md`.
     * ⚠ Measured, not assumed: a second `val onlyCopy` in the SAME block is refused by the compiler
     * ("Conflicting declarations"), so that shape needs no lint. What this census adds is the shape
     * the compiler accepts — a declaration of the name somewhere else in the file, which is where a
     * rewrite that wrapped the verdict in a scope of its own would put one. It shows up here as an
     * extra line, and has to be argued for.
     */
    @Test fun `the screen collects the flag from the view model, in this one line`() {
        assertEquals(
            "⛔ the composer screen no longer reads the flag from the view model the way it did. " +
                "This line is the whole join between the field that knows the message exists " +
                "nowhere else and the verdict that guards it: cut it, hard-code it or weaken it " +
                "and the back gesture closes an undone send in silence — no \"Discard message?\", " +
                "no draft, no queued row left to reopen. Every other rule in this file, and " +
                "ComposeDirtyTest, stay green while that happens. A SECOND declaration of the " +
                "name anywhere on this screen is the same loss written differently: the call site " +
                "hands over a name, not a value, and the text everything else reads is unchanged.",
            listOf("val onlyCopy by viewModel.onlyCopy.collectAsStateWithLifecycle()"),
            codeLines(COMPOSE_SCREEN).map { it.trim() }
                .filter { Regex("""\b(val|var)\s+onlyCopy\b""").containsMatchIn(it) },
        )
    }

    /**
     * ⛔ The value the screen HANDS the verdict, parsed out of the call and compared whole.
     * [ComposeDirty.isDirty] is pure and [ComposeDirtyTest] EXECUTES it, `onlyCopy = true` case
     * included — so the rule is watched, and the argument feeding it is not, by anything else. It is
     * parsed and not searched for on purpose: `onlyCopy && false` LENGTHENS the line and walks
     * straight through a `"onlyCopy = onlyCopy" in source`, while here it comes out as a different
     * value.
     */
    @Test fun `the screen still hands the flag to the leave guard`() {
        val call = callsOf(withoutComments(COMPOSE_SCREEN.readText()), "ComposeDirty.isDirty(").single()
        assertEquals(
            "the flag saying this message exists nowhere else must be handed to the " +
                "unsaved-changes verdict. Pass `false`, weaken it, or drop the argument, and a " +
                "composer reopened by \"Undo\" on the send banner closes on the back gesture in " +
                "silence: no \"Discard message?\", no draft, no queued row left to reopen — the " +
                "message is gone. ComposeDirtyTest stays green either way, because the rule it " +
                "runs is still right; it is simply never asked about this message.",
            "onlyCopy",
            call["onlyCopy"],
        )
    }

    /**
     * ⛔ WHERE the write stands, which the rule above cannot see. That rule compares the TEXT of
     * `_onlyCopy.value = d.editingOutboxId == null`; the file census compares the same text again.
     * Both are satisfied by a line that has not changed by one character and has moved out of the
     * `handed?.let { d -> … }` it belongs in — the third hole of `CONTRIBUTING.md`:
     * textual order is not execution order, and a pinned line says nothing about its position in a
     * control structure.
     *
     * ⭐ Why the position carries the meaning: outside that lambda the line runs even when NOTHING
     * was handed over — `restore=true` after the app was killed, an empty composer with nothing to
     * lose. Rewritten to compile out there (`outbox.restored.value?.editingOutboxId == null`, which
     * reads like a tidy-up) it then answers TRUE for the empty screen, and the invariant stated at
     * the head of the restore branch — nothing handed over means an empty composer, so the flag
     * stays false (#96) — is gone. ⚠ Measured, not assumed: THAT rewrite also changes the text of
     * the line, so the two text rules above redden with this one, and here this rule is only a
     * second opinion carrying a message that names the position. It is the mutation below that
     * they cannot see at all.
     *
     * ⛔ The two lines are compared as the LAST two of that block, not merely as members of it: a
     * write buried in a branch inside the lambda — `if (…) { _onlyCopy.value = … }`, the same shape
     * as `CONTRIBUTING.md`'s assignment moved into a `catch` — keeps its text, keeps its order, and
     * would satisfy a membership check. It is a closing brace that gives it away here. The price is
     * that a statement APPENDED to the restore path reddens this rule: that is deliberate, the
     * write must stay the last thing this branch does before the row is consumed.
     *
     * The second assertion pins the boundary the write sits against: `outbox.consumeRestored()` is
     * the very next line after the lambda, so nothing can be slipped between the write and the
     * consume, and the write cannot drift past the consume that clears what it reads.
     *
     * ⛔ This rule pins a POSITION, not the surrounding text: it does not read, and must not be
     * taken to protect, the comment at the head of the restore branch or the one above the write —
     * those two comments ARE the invariant, in prose, and this lint neither states nor rewrites them.
     */
    @Test fun `the flag is written inside the restored draft, just before the row is consumed`() {
        val body = prepareBody()
        val (inside, after) = restoredLambda(body)
        assertEquals(
            "⛔ bindQueuedRow(d, options) is no longer the last statement of the " +
                "`handed?.let { d -> … }` block of prepare(). WHERE it stands is the whole of its " +
                "meaning: outside that lambda the bind runs when nothing at all was handed over — " +
                "a composer reopened after the app was killed, empty, with nothing to lose — and " +
                "marks THAT screen as holding the only copy, so an empty composer starts asking " +
                "\"Discard message?\" while the invariant of the restore branch (#96) is gone. " +
                "Buried in a branch INSIDE the lambda it keeps its text and stops running on the " +
                "very path it guards, so an undone send closes in silence. It is the last thing " +
                "the restore branch does before the restored row is consumed, with `options` — " +
                "the identities just listed — so the From is the one the message was queued " +
                "under. Restore branch of prepare() was:\n$body",
            listOf("bindQueuedRow(d, options)"),
            codeOf(inside).takeLast(1),
        )
        assertEquals(
            "⛔ the write of _onlyCopy is no longer the last statement of bindQueuedRow(), beside " +
                "the id it is read from. Buried in a branch inside it, it keeps its text and its " +
                "order — the two text rules here stay green — and stops running on the path it " +
                "guards. Body of bindQueuedRow() was:\n" + bodyOf("bindQueuedRow"),
            listOf(
                "editingOutboxId = d.editingOutboxId",
                "_onlyCopy.value = d.editingOutboxId == null",
            ),
            codeOf(bodyOf("bindQueuedRow")).takeLast(2),
        )
        assertEquals(
            "⛔ something now stands between the restored lambda and outbox.consumeRestored(). " +
                "The flag is written from the restored record on the last line of that lambda and " +
                "the consume follows immediately: consumeRestored() clears what the write reads, " +
                "so a write that drifts past it reads null and calls an undone send an empty " +
                "screen, and a statement wedged in between gets to run on the restore path having " +
                "been argued for nowhere. (The resume of a composer rebuilt after a process death " +
                "stands AFTER the consume, on purpose — OutboxResumeWiringTest pins it there.)",
            "outbox.consumeRestored()",
            codeOf(after).firstOrNull(),
        )
    }

    /**
     * ⭐ The PREMISE of every rule above, and the only one of them that is held by an OMISSION.
     * `prepare` decides "this is the only copy" from `d.editingOutboxId == null`; that id is null
     * for an undone send only because the draft built for the undo in `sendInternal` DOES NOT NAME
     * the field, and takes the data class default (`SendOutbox.kt`, where the documentation states
     * the rule: null for an undone send, that message lives only on this screen, the row is gone).
     *
     * ⛔ Name the field there — by symmetry with another construction site, which reads as a
     * correction — and `d.editingOutboxId` stops being null, `onlyCopy` becomes false, the back
     * gesture closes an undone send in silence and the message is lost. Every other rule in this
     * file stays green: not one of their lines has moved. That is the first hole of
     * `CONTRIBUTING.md` — an argument name says nothing about what the name is bound to — and here
     * the argument that matters is the one nobody wrote.
     *
     * ⛔ The ONE line allowed to name the field is the lost-body refusal's `holdsStoredDraft`,
     * pinned whole above: it READS the id — a queued row parked `EDITING` behind Outbox → Edit
     * still holds the text, so the refusal owes "close and reopen the message" rather than the
     * destructive "type it again" — and it sits above `consumeEditingOutbox()`, so it cannot put
     * an id anywhere. It is pinned as text, not tolerated as a substring: shortening it, or naming
     * the field on any second line, reddens this rule.
     *
     * ⛔ This rule forbids ONE field at ONE site, and nothing else about that construction:
     *  - `consumeEditingOutbox()` stays ABOVE it — the row taken out for editing is dropped from
     *    the queue rather than given back (#70), which is exactly why the record built below can
     *    name no row. Not asserted here;
     *  - `draftEmailId`, `draftUidValidity`, `draftBodyIsLossy` and `requestReceipt` MUST keep
     *    being named — without them an undone send could no longer destroy the draft it replaces,
     *    or would destroy one it must spare. They are pinned by their own rules elsewhere
     *    (`DraftVerdictWiringTest`, `DraftNumberingCarriedWiringTest`, `ReadReceiptWiringTest`).
     *    ⛔ Do not read this rule as "name nothing here".
     *
     * ⛔ It reads `sendInternal`, not `send`: `fun send(` is expression-bodied, so the brace-walking
     * helper below runs straight past it into the next function that has a body.
     */
    @Test fun `the draft kept for undo names no queued row`() {
        val body = bodyOf("sendInternal")
        assertEquals(
            "⛔ sendInternal names editingOutboxId on a line that is not the refusal's. The rule " +
                "below reads the construction's argument names; this one reads the WHOLE body, " +
                "because the record is built on one line and handed to the hold window on " +
                "another: `outbox.hold(draft = draft.copy(editingOutboxId = id))` puts the row id " +
                "back without the construction changing by one character, and the guard goes out " +
                "with everything green. EXACTLY ONE line may name the field, and it is the one " +
                "below: the wording term of the lost-body refusal, which only READS the id to " +
                "choose between \"reopen the message\" and \"type it again\", sits above " +
                "consumeEditingOutbox(), and writes nothing. Any second line — and any change to " +
                "that one — is the defect this rule exists for. Lines naming it were:",
            listOf(
                "holdsStoredDraft = _editingLocalDraftId.value != null || editingDraftId != null || " +
                    "editingOutboxId != null,",
            ),
            codeLinesNaming(body, "editingOutboxId"),
        )
        val call = callsOf(body, "SendOutbox.ComposeDraft(").single()
        assertFalse(
            "⛔ the draft kept for the undo now names editingOutboxId. It must not: the queued row " +
                "was consumed by consumeEditingOutbox() a few lines above (#70), so once this send " +
                "is undone the message exists on the composer screen alone, in memory, and this " +
                "very omission is what makes prepare() say so (`_onlyCopy = d.editingOutboxId == " +
                "null`). Naming the field here — even with a plausible id, even for symmetry with " +
                "another construction site — puts a row id back into a record whose row is gone, " +
                "turns the only guard that message has off, and closes the composer on the back " +
                "gesture in silence. No other rule in this file, and no executed test, reddens " +
                "when that happens. Arguments were: ${call.keys}",
            "editingOutboxId" in call.keys,
        )
    }

    // -- reading the source ---------------------------------------------------------------------
    // Recopied from ComposeViewModelWiringTest and ReadReceiptWiringTest on purpose: these helpers
    // are duplicated across the source lints of this repo rather than shared, so no rule can be
    // disarmed from a distance.

    /** The body of `fun prepare(`, braces balanced, comments cut. */
    private fun prepareBody(): String = bodyOf("prepare")

    /** [body]'s code lines, trimmed and blanks dropped — the shape a whole-body rule would compare. */
    private fun codeOf(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** [body]'s code lines that name [needle], trimmed — whole lines, in the order they are written. */
    private fun codeLinesNaming(body: String, needle: String): List<String> =
        codeOf(body).filter { needle in it }

    /**
     * [body] split at the `handed?.let { d -> … }` block: what is INSIDE it, and
     * what follows its closing brace. Braces are walked, not searched for, so a line that keeps its
     * text and changes block shows up on the other side of the pair.
     */
    private fun restoredLambda(body: String): Pair<String, String> {
        val at = body.indexOf(RESTORED_LAMBDA)
        check(at >= 0) {
            "the restore branch of prepare() no longer opens '$RESTORED_LAMBDA' — this rule pins " +
                "WHERE the only-copy flag is written, and cannot say so if that block was renamed " +
                "or taken apart. Body of prepare() was:\n$body"
        }
        val start = body.indexOf('{', at) + 1
        var depth = 1
        var i = start
        while (i < body.length && depth > 0) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        check(depth == 0) { "unbalanced braces in the restore branch of prepare() — was:\n$body" }
        return body.substring(start, i - 1) to body.substring(i)
    }

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
     *  flag and the very ordering the rules above pin. */
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
     * The arguments of every `[call]…)` in [body], as name → value expression; positional arguments
     * are keyed `#0`, `#1`, …  Recopied from `ReadReceiptWiringTest`, which reads the very same
     * call for its own argument.
     */
    private fun callsOf(body: String, call: String): List<Map<String, String>> {
        val calls = mutableListOf<Map<String, String>>()
        var from = 0
        while (true) {
            val at = body.indexOf(call, from)
            if (at < 0) break
            val open = at + call.length - 1
            val close = matchingParen(body, open)
            calls += parseArguments(body.substring(open + 1, close))
            from = close
        }
        check(calls.isNotEmpty()) { "no call to '$call' left in this body — did it move?" }
        return calls
    }

    private fun matchingParen(code: String, open: Int): Int {
        var depth = 0
        var i = open
        var inString = false
        while (i < code.length) {
            val c = code[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> {
                    depth--
                    if (depth == 0 && c == ')') return i
                }
            }
            i++
        }
        error("unbalanced call in the source at offset $open")
    }

    private fun parseArguments(text: String): Map<String, String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString && c == '\\' -> { current.append(c).append(text.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; current.append(c) }
                inString -> current.append(c)
                c == '(' || c == '[' || c == '{' -> { depth++; current.append(c) }
                c == ')' || c == ']' || c == '}' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { args += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) args += current.toString()
        return args.mapIndexed { index, raw ->
            val arg = raw.trim().replace(Regex("""\s+"""), " ")
            val eq = arg.indexOf('=')
            if (eq > 0 && arg.take(eq).trim().matches(Regex("""\w+""")) && arg.getOrNull(eq + 1) != '=') {
                arg.take(eq).trim() to arg.substring(eq + 1).trim()
            } else {
                "#$index" to arg
            }
        }.toMap()
    }

    /**
     * [source] with every comment removed, string literals untouched. Recopied from
     * `ReadReceiptWiringTest`, where it is called `codeOf`; renamed here because this file already
     * has a `codeOf` of its own, over the lines of a body.
     */
    private fun withoutComments(source: String): String {
        val out = StringBuilder()
        var i = 0
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString && c == '\\' -> { out.append(c).append(source.getOrElse(i + 1) { ' ' }); i += 2; continue }
                c == '"' -> { inString = !inString; out.append(c) }
                inString -> out.append(c)
                c == '/' && source.getOrNull(i + 1) == '/' -> {
                    while (i < source.length && source[i] != '\n') i++
                    continue
                }
                c == '/' && source.getOrNull(i + 1) == '*' -> {
                    val end = source.indexOf("*/", i + 2)
                    i = if (end < 0) source.length else end + 2
                    continue
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    companion object {
        /** The block of `prepare` that only runs when a draft was really handed back. */
        private const val RESTORED_LAMBDA = "handed?.let { d ->"

        private const val COMPOSE_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt"
        private const val COMPOSE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, COMPOSE_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
        private val COMPOSE_SCREEN: File by lazy { File(root, COMPOSE_SCREEN_PATH) }
    }
}

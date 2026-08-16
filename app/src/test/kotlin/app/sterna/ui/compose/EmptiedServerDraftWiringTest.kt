package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The empty-save of a draft opened straight off the SERVER, on its way from the composer to the
 * expunge — SOURCE LINT, the last resort, for [DraftVerdictWiringTest]'s reason: nothing in this
 * module can instantiate `ComposeViewModel` (an `AndroidViewModel`, no Robolectric here). The
 * decision itself is EXECUTED by [EmptiedServerDraftTest] ([emptiedServerDraftIsKept]); what nothing
 * else can see is whether this route still asks it, and asks it about THIS screen's verdict.
 *
 * ⚠ A test that checks a call EXISTS proves nothing about what is handed to it: arguments are parsed
 * out of the call and compared whole ([callsOf]), and the block is pinned line by line and IN ORDER —
 * a `contains` is blind to every mutation that lengthens a line (`editingDraftLossy && false` reads
 * as harmless) and to a notice moved out of the lambda.
 */
class EmptiedServerDraftWiringTest {

    @Test fun `the empty save asks the decision about this screen's own verdict`() {
        assertEquals(
            "⛔ the decision must be fed `editingDraftLossy`, whole — the verdict `prepare()` " +
                "computed from the draft it fetched. `false` here (or any other term) is the defect " +
                "with a decision bolted on top: the answer is then always \"destroy\", and a draft " +
                "made of one inline image — drawn EMPTY, because draftHasContent counts neither " +
                "inline images nor calendar parts — is expunged on the server without a word.",
            mapOf("#0" to "editingDraftLossy"),
            callsOf(bodyOf("saveDraft"), "emptiedServerDraftIsKept(").single(),
        )
    }

    @Test fun `the kept copy is SAID, and the expunge is what the other branch does`() {
        val lines = codeLines(bodyOf("saveDraft"))
        // ⚠ Exact equality, never a prefix: the LOCAL route one branch above opens with the same
        // words and closes on its own line (`… -> destroyReplacedServerDraft(credentials, original) }`),
        // so a prefix match lands on that one and this test then pins the wrong branch.
        val at = lines.indexOfFirst { it == "submit(to) { credentials, _ ->" }
        assertTrue(
            "saveDraft's empty-save no longer opens the server route on a multi-line lambda — the " +
                "guard and the notice live inside it, so a one-liner here is the defect. Did the " +
                "branch move? " +
                "Body was:\n" + lines.joinToString("\n"),
            at >= 0,
        )
        assertEquals(
            "⛔ the whole block, in order. Three things are pinned together because each alone " +
                "leaves the defect standing: the decision is asked BEFORE anything is destroyed; " +
                "the kept branch says so with the sentence the other route already posts " +
                "(compose_emptied_draft_server_copy_kept — no new string, it exists in the nine " +
                "locales), and it says it INSIDE the lambda, since submit() moves the screen to " +
                "Done only after this returns and a notice emitted afterwards is read by nobody; " +
                "and the destroying branch stays word for word what it was, frozen numbering " +
                "included (#99) — `null` there destroys nothing and the draft comes back on the " +
                "next sync. Body was:\n" + lines.joinToString("\n"),
            listOf(
                "submit(to) { credentials, _ ->",
                "if (emptiedServerDraftIsKept(editingDraftLossy)) {",
                "_notices.tryEmit(R.string.compose_emptied_draft_server_copy_kept)",
                "} else {",
                "repo.discardDraft(credentials, original, originalUidValidity)",
                "}",
                "}",
            ),
            lines.subList(at, (at + 7).coerceAtMost(lines.size)),
        )
    }

    @Test fun `the expunge exists nowhere else in the composer, so nothing goes round the guard`() {
        assertEquals(
            "⛔ exactly one `repo.discardDraft(` in this class, the one under the guard. A second " +
                "call — a helper, an early return \"to be safe\" — is a second way out that reads " +
                "no verdict at all, and every test above stays green. Lines were:",
            listOf("repo.discardDraft(credentials, original, originalUidValidity)"),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "repo.discardDraft(" in it },
        )
    }

    /**
     * And the GATE that decides whether the destroying branch is entered at all.
     *
     * ⛔ **The guard above only runs once "this draft is empty" has been answered**, and that answer
     * is `hasDraftContent`, a one-line wrapper whose body was pinned NOWHERE: only its call site
     * (`ComposeViewModelWiringTest`) and `draftHasContent` itself (`DraftContentTest`, executed) were.
     * Between the two, the sixth argument — "does this composer hold attachments" — could be turned
     * into `false` and every suite stayed green.
     *
     * The damage that mutation buys: a server draft whose only content is its ATTACHMENTS is carried
     * whole, so `editingDraftLossy` is false and the guard above answers "destroy" — the copy is
     * expunged on the server while the attachment chips are on screen and Save is still enabled,
     * because `ComposeScreen` asks `draftHasContent` with its OWN attachment term and the two drift
     * apart in silence. It is the emptied-draft destruction with the word "empty" quietly redefined.
     *
     * Whole line, arguments included: this is the composer's only bridge to the shared rule, and
     * `ComposeScreen`'s call must keep meaning the same thing as this one.
     */
    @Test fun `what counts as empty is the shared rule, holding this screen's own attachments`() {
        assertEquals(
            "⛔ `hasDraftContent` must hand `draftHasContent` this composer's five fields AND its " +
                "own attachment state, whole. Drop the attachments term (or pass `false`) and a " +
                "draft made only of attachments is judged EMPTY: the guard above then answers " +
                "\"destroy\" on a faithful read, and the server copy is expunged with the chips " +
                "still on screen. Lines were:",
            listOf(
                "): Boolean = draftHasContent(to, cc, bcc, subject, body, _attachments.value.isNotEmpty())",
            ),
            codeLines(codeOf(COMPOSE_VIEW_MODEL.readText())).filter { "draftHasContent(" in it },
        )
    }

    // --- reading the source (the same instruments as DraftVerdictWiringTest) ---------------------

    /** The body of `fun [name](` in `ComposeViewModel.kt`, braces balanced, comments cut. */
    private fun bodyOf(name: String): String {
        val code = codeOf(COMPOSE_VIEW_MODEL.readText())
        val at = Regex("""\bfun\s+$name\s*\(""").find(code)
            ?: error("ComposeViewModel.kt declares no 'fun $name(' — did it get renamed?")
        val start = code.indexOf('{', code.indexOf(')', at.range.last)) + 1
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

    private fun codeLines(body: String): List<String> =
        body.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The arguments of every `[call]…)` in [body], as name → value expression (`#n` when positional). */
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
        check(calls.isNotEmpty()) { "no call to '$call' left in saveDraft — did the guard go?" }
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

    /** [source] with every comment removed, string literals untouched: the comments around this
     *  branch name the very call it pins, so prose must not be able to answer for the code. */
    private fun codeOf(source: String): String {
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

    private companion object {
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

        val COMPOSE_VIEW_MODEL: File by lazy { File(root, COMPOSE_VIEW_MODEL_PATH) }
    }
}

package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST. It reads `InboxViewModel.kt` as text and proves nothing about
 * what appears on screen. What the decision COMPUTES is [ActionFailureMessageTest]'s job, against
 * [actionFailureMessage] itself; this file holds the half that cannot be executed — `InboxViewModel`
 * is an `AndroidViewModel` and cannot be instantiated in a JVM test, so the two failure arms are
 * reachable only as source.
 *
 * The defect it closes: a swipe that failed offline put `it.message` straight on the snackbar —
 * `Unable to resolve host "…": No address associated with hostname`, in English whatever the app's
 * language — because the `?:` behind it only fires on a NULL message, which a transport exception
 * never has. Its twin `threadSwipeRemove` was already right, which is how it went unnoticed. The
 * second arm, Undo's: `error = it.message` with no fallback at all, so a null message there is
 * `RefreshNotice.NONE` — a BANNER that says nothing. Not a silent Undo, to be exact: the snackbar
 * "couldn't restore some messages" fires either way, one call further down. And in airplane mode
 * the banner the reader actually gets is the offline one, because `RefreshNotice` puts OFFLINE
 * ahead of ERROR (#65) — an order this fix leaves alone. What this arm owes is therefore narrow
 * and worth stating plainly: no raw resolver text, and never an empty banner.
 *
 * Both arms are compared WHOLE, flattened, not searched for fragments: a `contains` rule is blind
 * to every mutation that makes the line LONGER — `online = hasUsableNetwork(app) && false`, an
 * `?: it.message` tacked on the end — and that is the shape this fix would come undone in. A
 * legitimate refactor of either arm reddens this file; that direction of error is loud and one line
 * to fix, the other direction ships in silence.
 */
class ActionFailureWiringTest {

    @Test fun `the swipe's failure arm is pinned whole`() {
        assertEquals(
            "swipeRemove's onFailure must log the throwable, show the sentence the shared decision " +
                "picks, put the row back in the search results and reconcile the list — in that " +
                "order and nothing else. The `refresh()` and `restoreSearchResults` calls are part " +
                "of the pin on purpose: the failed row was never dropped locally, and dropping " +
                "either leaves the list lying about what is on the server.",
            SWIPE_ARM, arm("private fun swipeRemove("),
        )
    }

    @Test fun `the undo's failure arm is pinned whole`() {
        assertEquals(
            "undo()'s onFailure must count the entries it could not restore, log the throwable and " +
                "put a NON-NULL sentence in the banner. `error = it.message` there was the defect: " +
                "a transport exception's message is raw English, and a null one is " +
                "RefreshNotice.NONE — a banner saying nothing at all.",
            UNDO_ARM, arm("fun undo()"),
        )
    }

    @Test fun `neither arm puts the exception's own text on screen`() {
        for ((name, header) in SITES) {
            val text = arm(header)
            assertFalse(
                "$name must not read `it.message` at all: that string is the platform resolver's, " +
                    "in English whatever the app's language, and it is exactly what this fix takes " +
                    "off the screen. Arm was:\n$text",
                READS_THE_MESSAGE.containsMatchIn(text),
            )
        }
    }

    @Test fun `both sites ask the shared decision, and nowhere else does`() {
        val calls = Regex("""(?<!fun )\bactionFailureMessage\(""").findAll(code()).count()
        assertEquals(
            "actionFailureMessage must be CALLED exactly twice in InboxViewModel — the swipe and " +
                "the Undo. A third caller is another site quietly changing its wording through " +
                "this one; fewer means one of the two went back to its own rule. Found $calls.",
            2, calls,
        )
        for ((name, header) in SITES) {
            val text = arm(header)
            assertTrue(
                "$name must call actionFailureMessage(it, online = hasUsableNetwork(app)) — the " +
                    "failure itself and the device's LIVE connectivity reading. Arm was:\n$text",
                DECIDES_ON_LIVE_CONNECTIVITY.containsMatchIn(text),
            )
        }
    }

    @Test fun `neither site hard-codes whether the device is online`() {
        for ((name, header) in SITES) {
            val text = arm(header)
            assertFalse(
                "$name must not pass a constant for `online`. `online = true` is one word, " +
                    "compiles, and gives every offline swipe the generic sentence again — with " +
                    "the whole suite green if the rule only looked for the call by name. Arm " +
                    "was:\n$text",
                CONSTANT_CONNECTIVITY.containsMatchIn(text),
            )
        }
    }

    @Test fun `the undo banner can never be left empty`() {
        val text = arm("fun undo()")
        assertTrue(
            "undo()'s banner must be fed a resolved string resource. `error = null`, `error = " +
                "it.message` or any expression that can answer null is RefreshNotice.NONE, and " +
                "the banner then carries nothing at all about an Undo that did not happen. Arm " +
                "was:\n$text",
            NON_NULL_BANNER.containsMatchIn(text),
        )
    }

    @Test fun `both sites write the technical text to the log, with the throwable`() {
        for ((name, header) in SITES) {
            val text = arm(header)
            assertTrue(
                "$name must call android.util.Log.w(tag, message, it), passing the THROWABLE as " +
                    "the third argument — not `it.message`, or the stack trace that says which " +
                    "layer failed is gone from the bug report too. The text is not lost by this " +
                    "fix, it changes surface. Arm was:\n$text",
                LOGS_THE_THROWABLE.containsMatchIn(text),
            )
        }
    }

    /** Witness, deliberately out of scope: the twin that was already right must not have moved. */
    @Test fun `the folded-thread twin still shows the generic sentence`() {
        assertTrue(
            "threadSwipeRemove was already showing a translated status_action_failed — it is not " +
                "part of this fix and must stay exactly as it was.",
            TWIN_UNTOUCHED.containsMatchIn(code()),
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /**
     * The `.onFailure { … }` block of the declaration starting at [header], comments stripped and
     * whitespace squeezed to single spaces, braces balanced. Fails loudly when the declaration or
     * its arm cannot be found, so a rename reddens these rules rather than satisfying them against
     * an empty string.
     */
    private fun arm(header: String): String {
        val text = code()
        val start = text.indexOf(header)
        check(start >= 0) { "InboxViewModel.kt declares no '$header' — did it get renamed?" }
        val declaration = balanced(text, start, '{', '}')
        val onFailure = declaration.indexOf(".onFailure {")
        check(onFailure >= 0) { "'$header' has no .onFailure arm any more — where did the failure go?" }
        val second = declaration.indexOf(".onFailure {", onFailure + 1)
        check(second < 0) { "'$header' now has more than one .onFailure arm; this rule pins one." }
        return flat(balanced(declaration, onFailure, '{', '}'))
    }

    /** [text] with every run of whitespace squeezed to one space, so a wrapped call reads as one. */
    private fun flat(text: String): String = text.trim().replace(Regex("\\s+"), " ")

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

    /**
     * The file's code as one string, comments taken off: a line whose first non-blank character
     * opens a comment is dropped whole, and a trailing `//` cuts the rest of its line, the cut only
     * being taken outside a double-quoted run. Load-bearing here — the comments in both arms quote
     * the raw resolver string this fix removes, and left in they would answer for the code.
     */
    private fun code(): String = INBOX_VIEW_MODEL.readLines().mapNotNull { line ->
        val start = line.trimStart()
        if (start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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

        /** The two arms this fix owns, by the declaration each lives in. */
        private val SITES = listOf(
            "swipeRemove" to "private fun swipeRemove(",
            "undo()" to "fun undo()",
        )

        private const val DECISION =
            """app\.getString\(actionFailureMessage\(it, online = hasUsableNetwork\(app\)\)\)"""

        /**
         * The last two lines before `refresh()` are one pair: the row goes back into the search
         * results it was taken out of, AND into the browse list it flew off. Neither is a local
         * eviction — the row was never dropped, the repo is network-first — and the rewind is
         * pinned here whole for the same reason as everything else in this arm: `+ emptySet()` is
         * one word, compiles, and leaves the row invisible for good. What it then DECIDES is
         * [SwipeRewindTest]'s job; that both halves are connected is [SwipeRewindWiringTest]'s.
         */
        private val SWIPE_ARM =
            "val app = getApplication<Application>() " +
                "android.util.Log.w(\"SternaInbox\", \"swipe action failed\", it) " +
                "_message.value = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app))) " +
                "restoreSearchResults(listOf(email.emailKey())) " +
                "_swipeRewind.value = _swipeRewind.value + email.emailKey() " +
                "refresh()"

        private val UNDO_ARM =
            "unrestored += entries.size " +
                "val app = getApplication<Application>() " +
                "android.util.Log.w(\"SternaInbox\", \"undo restore failed\", it) " +
                "status.value = Status(refreshing = false, " +
                "error = app.getString(actionFailureMessage(it, online = hasUsableNetwork(app))))"

        /** Any read of the exception's own text — the thing that used to reach the screen. */
        private val READS_THE_MESSAGE = Regex("""\bit\.message\b""")

        private val DECIDES_ON_LIVE_CONNECTIVITY = Regex(DECISION)

        /** `online = true` / `online = false`, and the two literals on their own anywhere in the arm. */
        private val CONSTANT_CONNECTIVITY = Regex("""online\s*=\s*(true|false)\b""")

        /** The banner is a resolved resource, never an expression that can answer null. */
        private val NON_NULL_BANNER = Regex("""error = $DECISION\)""")

        private val LOGS_THE_THROWABLE = Regex("""android\.util\.Log\.w\("[^"]+", "[^"]+", it\)""")

        /** The folded-thread twin, unchanged: one translated sentence, then a reconcile. */
        private val TWIN_UNTOUCHED = Regex(
            """_message\.value = getApplication<Application>\(\)\.getString\(R\.string\.status_action_failed\)\s*""" +
                """refresh\(\)""",
        )

        private const val INBOX_VIEW_MODEL_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_VIEW_MODEL_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_VIEW_MODEL: File by lazy { File(root, INBOX_VIEW_MODEL_PATH) }
    }
}

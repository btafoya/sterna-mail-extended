package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint: the open message's subject can be long-pressed and copied (#270). `MessageHeader` is
 * a composable, no JVM test can compose it, so this reads its block as text and pins the nesting by
 * counting braces: the subject's `Text` sits inside a `SelectionContainer` that is closed before the
 * sender `Row`, whose `clickable` opens the participants panel and would lose its tap to the
 * selection's long-press if the container reached it.
 *
 * Of the four holes in CONTRIBUTING.md, 1, 2 and 4 do not apply: no argument is pinned and nothing
 * is collected. Hole 3 applies in part: the braces prove nesting, not that no `if` around the
 * subject skips the container. And a lint proves the container is written, never that the
 * long-press reaches it on a device through the header's own gesture handling.
 */
class SubjectSelectableLintTest {

    @Test fun `the subject Text sits alone inside a SelectionContainer closed before the sender row`() {
        val lines = codeLines(block("private fun MessageHeader("))
        val subject = lines.indexOfFirst { "R.string.message_no_subject" in it }
        assertTrue("MessageHeader no longer names message_no_subject — was the subject moved?", subject >= 0)
        val clickable = lines.indexOfFirst {
            it.startsWith(".clickable(onClickLabel = stringResource(R.string.message_participants_title))")
        }
        assertTrue("MessageHeader no longer has the participants row's clickable line", clickable > subject)

        assertEquals(
            "MessageHeader must hold exactly one SelectionContainer, the subject's: a second one around " +
                "the header Column would take the participants row's tap",
            1,
            lines.count { "SelectionContainer" in it },
        )
        val open = (subject downTo 0).firstOrNull { lines[it] == "SelectionContainer {" }
        assertTrue(
            "the subject's Text must be wrapped in `SelectionContainer {` so a long-press copies it " +
                "(#270). Lines before the subject were:\n" +
                lines.subList(maxOf(0, subject - 4), subject + 1).joinToString("\n"),
            open != null,
        )
        assertEquals(
            "the SelectionContainer must hold the subject's Text and nothing else, opened right before it",
            "Text(",
            lines[open!! + 1],
        )
        val close = closingLine(lines, open)
        assertTrue(
            "the SelectionContainer opened at line ${open} of MessageHeader must close after the subject " +
                "(line $subject) and BEFORE the participants row's clickable (line $clickable): " +
                "wrapping that Row, or the header Column, steals its tap. It closes at line $close.",
            close in (subject + 1) until clickable,
        )
    }

    /**
     * The selection is dropped by re-keying the container, at both moments the decision in
     * [SubjectSelectionGuard] names: a touch on the header outside the subject (the sender row first:
     * its panel otherwise closes over a selection still open), and the subject moving up as the
     * header folds (otherwise the toolbar floats over the body once the handles are gone).
     * [SubjectSelectionGuardTest] runs the decision; this pins that its answers reach the key, and
     * with which arguments. It cannot prove the touch observer sits before the row's own gesture.
     */
    @Test fun `the subject's selection is re-keyed on a touch outside it and when the header folds`() {
        val lines = codeLines(block("private fun MessageHeader("))
        val open = lines.indexOf("SelectionContainer {")
        assertTrue("MessageHeader no longer opens `SelectionContainer {` on a line of its own", open > 0)
        assertEquals(
            "the subject's SelectionContainer must be the only child of `key(subjectSelectionEpoch) {`: " +
                "SelectionContainer keeps its selection to itself, a new key is the only way to drop it",
            "key(subjectSelectionEpoch) {",
            lines[open - 1],
        )
        assertTrue(
            "the epoch and the guard must be remembered per message, like showParticipants",
            hasLine(lines, "val subjectSelectionGuard = remember(msg.id) { SubjectSelectionGuard() }") &&
                hasLine(lines, "var subjectSelectionEpoch by remember(msg.id) { mutableIntStateOf(0) }"),
        )

        val touched = lines.indexOf("if (subjectSelectionGuard.touched(onSubject)) subjectSelectionEpoch++")
        assertTrue(
            "a touch on the header must be reported to the guard and its answer must bump the key. " +
                "Lines naming the guard were:\n" + lines.filter { "subjectSelectionGuard" in it }.joinToString("\n"),
            touched in 0 until open,
        )
        assertTrue(
            "the touch observer must see the down before the sender row takes it, without consuming it",
            lines.subList(0, touched).any {
                it == "val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)"
            },
        )
        assertTrue(
            "`onSubject` must be the down tested against the subject's own bounds",
            lines.subList(0, touched).any {
                it == "header.localBoundingBoxOf(subject, clipBounds = false).contains(down.position)"
            },
        )

        val close = closingLine(lines, open - 1)
        val placed = lines.indexOf("if (subjectSelectionGuard.placed(it.positionInRoot().y)) subjectSelectionEpoch++")
        assertTrue(
            "the subject's own placement, its top in the root, must be reported to the guard from " +
                "inside the keyed container and its answer must bump the key. Lines naming the guard " +
                "were:\n" + lines.filter { "subjectSelectionGuard" in it }.joinToString("\n"),
            placed in (open + 1) until close,
        )
    }

    private fun hasLine(lines: List<String>, line: String) = lines.any { it == line }

    /** Index of the line where the braces opened on [lines]`[from]` are all closed again. */
    private fun closingLine(lines: List<String>, from: Int): Int {
        var depth = 0
        for (i in from until lines.size) {
            for (c in lines[i]) {
                if (c == '{') depth++
                if (c == '}') depth--
            }
            if (depth == 0) return i
        }
        return -1
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun block(signature: String): String {
        val text = screen().readText()
        val at = text.indexOf(signature)
        check(at >= 0) { "MessageScreen.kt no longer declares `$signature` — was it renamed or moved out?" }
        val end = text.indexOf("\n/**", at)
        return text.substring(at, if (end < 0) text.length else end)
    }

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}

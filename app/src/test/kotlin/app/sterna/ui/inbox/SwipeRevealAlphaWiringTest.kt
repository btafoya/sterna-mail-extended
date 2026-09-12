package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, not a behaviour test, same instrument and the same disclaimer as every source lint
 * in this tree: it reads `InboxScreen.kt` as text and proves nothing about behaviour.
 * [SwipeRevealAlphaTest] is what runs the decision; this file only pins that the screen asks it,
 * with those arguments, in that place.
 *
 * What it cannot see, on top of the four holes listed in CONTRIBUTING.md:
 * - it cannot hold a value at the point the row reads it. `lift.value` pinned here is satisfied by
 *   a `lift` that never animates, and the reveal would then jump out on the last frame instead of
 *   burning down with the row.
 * - it says nothing about what the compositor does with the alpha, only that it is assigned, nor
 *   about what a screen reader announces: the composition gate pinned below is the only thing
 *   standing between a spent label and TalkBack, and no test here hears it.
 */
class SwipeRevealAlphaWiringTest {

    // -- the reveal's opacity ---------------------------------------------------------------------

    @Test fun `the reveal wears its own alpha, and wears it before it is painted`() {
        assertEquals(
            "the reveal's Box must take the alpha in a graphicsLayer placed BEFORE .background(bg): " +
                "a modifier only dims what is drawn after it, so the same layer moved below the " +
                "background leaves the coloured band at full opacity and fades the label alone. " +
                "Pinned whole, arguments and order included.",
            "Modifier .matchParentSize() .graphicsLayer { alpha = revealAlpha } .background(bg) " +
                ".padding(horizontal = 24.dp), contentAlignment = if (draggingRight) " +
                "Alignment.CenterStart else Alignment.CenterEnd,",
            flat(revealBoxCall()),
        )
    }

    @Test fun `the alpha is asked with the flight distance, the flight clock and the flight flag`() {
        val lines = rowLines().filter { "swipeRevealAlpha(" in it }
        assertEquals(
            "exactly one line, compared whole. Each argument is load-bearing and each has a " +
                "plausible wrong spelling: `rowWidth.toFloat()` is the distance commitSwipe aims " +
                "for, `size.width` is the measured box and drifts from it; `lift.value` is the " +
                "same clock the row's own fade runs on, `0f` freezes the reveal opaque for the " +
                "whole server round trip; `flyDir != 0` is the committed flag, `true` blanks the " +
                "reveal under a finger still on the screen and `false` restores the defect. " +
                "Lines found were:\n${lines.joinToString("\n")}",
            listOf(
                "val revealAlpha = swipeRevealAlpha(offsetX.value, rowWidth.toFloat(), " +
                    "lift.value, flyDir != 0)",
            ),
            lines.map { flat(it) },
        )
    }

    @Test fun `a reveal that is owed nothing is not composed at all`() {
        val lines = rowLines().filter { it.startsWith("if (revealAlpha") }
        assertEquals(
            "the reveal must be gated on its own alpha, not merely painted at it. alpha is a " +
                "draw-phase value and accessibility never reads it, so a label left composed at " +
                "alpha 0 goes on announcing \"Delete\" over a cell that stopped showing it — for " +
                "the whole network-first round trip, which is the defect this branch removes, " +
                "surviving on the audio surface. Gates found were:\n${lines.joinToString("\n")}",
            listOf("if (revealAlpha > 0f) {"),
            lines,
        )
    }

    @Test fun `the screen takes this decision in exactly one place`() {
        assertEquals(
            "swipeRevealAlpha must be CALLED once in InboxScreen, its declaration aside. A second " +
                "caller is a surface deciding the reveal's opacity on its own, which is how the " +
                "two halves of the swipe drifted apart before (SwipeCommit.kt).",
            1, Regex("""(?<!fun )\bswipeRevealAlpha\(""").findAll(code(INBOX_SCREEN)).count(),
        )
    }

    // -- the guard this fix must not have moved ---------------------------------------------------

    @Test fun `the finger's own affordance is still gated on nothing but movement and an action`() {
        val lines = rowLines().filter { "action != SwipeAction.NONE" in it }
        assertEquals(
            "the reveal is composed under exactly this guard, word for word. Tightening it here — " +
                "adding the committed flag, a distance, a threshold — is the wrong place to hide " +
                "the stale band: it would take the label away from a finger still choosing, and " +
                "the colour and threshold feedback with it. The opacity is the alpha's business, " +
                "not this `if`'s. Guards found were:\n${lines.joinToString("\n")}",
            listOf("if (offsetX.value != 0f && action != SwipeAction.NONE) {"),
            lines,
        )
    }

    // -- the arm that plays no animation ----------------------------------------------------------

    @Test fun `the reduced-motion arm says it committed too`() {
        val arm = elseArmLines().filter { "setFlyDir" in it }
        assertEquals(
            "with motion off there is no take-off to play, but the row still slides its full " +
                "width away, so the reveal still has to be told the gesture is committed — " +
                "otherwise reduced motion keeps the defect in full. Pinned whole: " +
                "`setFlyDir(dir)` unguarded would flag a swipe that snaps back (mark-read, flag) " +
                "and blank its reveal on the way home. Lines found were:\n${arm.joinToString("\n")}",
            listOf("if (dismissesRow(action)) setFlyDir(dir)"),
            arm,
        )
    }

    @Test fun `nothing outside commitSwipe's two arms sets the flight direction`() {
        val all = Regex("""(?<!fun )\bsetFlyDir\(""").findAll(code(INBOX_SCREEN)).count()
        val inside = Regex("""\bsetFlyDir\(""").findAll(commitSwipe()).count()
        assertEquals(
            "setFlyDir must be called exactly twice in InboxScreen: once per arm of commitSwipe. " +
                "A third caller is another path claiming a row is in flight, and the reveal would " +
                "vanish on a gesture that never committed.",
            2, all,
        )
        assertEquals(
            "and both calls must be inside commitSwipe itself. Found $inside there.",
            2, inside,
        )
    }

    // -- reading the source ------------------------------------------------------------------------

    /** The body of `SwipeableEmailRow`, comments stripped. */
    private fun row(): String = body(INBOX_SCREEN, "SwipeableEmailRow")

    /** The same, as trimmed lines. */
    private fun rowLines(): List<String> = row().lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The arguments of the `Box(…)` that draws the reveal, balanced. */
    private fun revealBoxCall(): String {
        val text = row()
        val marker = text.indexOf(".matchParentSize()")
        check(marker >= 0) { "SwipeableEmailRow no longer sizes its reveal with matchParentSize" }
        val box = text.lastIndexOf("Box(", marker)
        check(box >= 0) { "no Box( opens the reveal before its matchParentSize" }
        return balanced(text, box, '(', ')')
    }

    /** `commitSwipe`'s declaration and body, as text. Not [body]: it is an extension function. */
    private fun commitSwipe(): String {
        val text = code(INBOX_SCREEN)
        val start = text.indexOf("private fun CoroutineScope.commitSwipe(")
        check(start >= 0) { "InboxScreen.kt declares no CoroutineScope.commitSwipe — renamed?" }
        return balanced(text, text.indexOf(") {", start), '{', '}')
    }

    /** The code lines of commitSwipe's `else` arm, trimmed, comments already off. */
    private fun elseArmLines(): List<String> {
        val text = commitSwipe()
        val arm = text.indexOf("} else {")
        check(arm >= 0) { "commitSwipe has no else arm any more — where did reduced motion go?" }
        return balanced(text, arm + 1, '{', '}').lines().map { it.trim() }.filter { it.isNotEmpty() }
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
     * The lines of [file] that are code, with their comments taken off, a line whose first
     * non-blank character opens or continues a comment dropped entirely.
     */
    private fun codeLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val start = line.trimStart()
        if (start.startsWith("//") || start.startsWith("*") || start.startsWith("/*")) null
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

    /** [file]'s code as one string, so a call the formatter wraps over four lines reads as one. */
    private fun code(file: File): String = codeLines(file).joinToString("\n")

    /**
     * The declaration of `fun`/`val` [name] in [file] and its body, as text: everything up to the
     * first line indented no deeper than the declaration, once the declaration's own brackets have
     * closed.
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

    companion object {

        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
    }
}

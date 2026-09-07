package app.sterna.core.data.mail

/**
 * The lines that opened the blocks still open just before [index] — what a given line of source is
 * ENCLOSED BY.
 *
 * Written once, here, because two different families of lint need the same answer and a second
 * copy would drift. [SignedOutAccountWiringTest] asks it of the thirteen sign-out guards; the IMAP
 * commands that carry a numbering ask it in [NumberedReadsAreNotSwallowedTest]. Both are asking
 * the one question a line-by-line lint cannot answer: not "what does this line say" but "who is
 * standing over it, ready to swallow what it throws".
 *
 * Brace counting on trimmed text, comments dropped. It is deliberately crude: it cannot tell a
 * lambda from a `try`, so its callers filter the answer for what they care about rather than trust
 * it to be a parser. A `{` inside a string literal would fool it — none of the lines it walks has
 * one, and a lint that goes wrong LOUDLY (an unexpected block name in the failure message) is the
 * acceptable failure here.
 */
internal fun openBlocksAt(lines: List<String>, index: Int): List<String> {
    val stack = ArrayDeque<String>()
    lines.take(index).forEach { raw ->
        val line = raw.trim()
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEach
        val delta = line.count { it == '{' } - line.count { it == '}' }
        repeat(maxOf(0, -delta)) { stack.removeLastOrNull() }
        repeat(maxOf(0, delta)) { stack.addLast(line) }
    }
    return stack.toList()
}

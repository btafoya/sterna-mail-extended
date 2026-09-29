package app.sterna.core.data.filter

/** One top-level piece of a script. The concatenation of every [text], in order, is the script byte for byte. */
internal sealed interface Segment {
    val text: String
}

/** Blank lines and comments that belong to no command. */
internal data class Gap(override val text: String) : Segment

/**
 * A command, the `#` lines glued above it ([header]), its indentation, and the rest of its last
 * line ([trailer]: blanks, a `#` comment, the line ending). [form] is set when the rule form can edit it.
 */
internal data class Command(
    val id: Int,
    val header: List<String>,
    val indent: String,
    val statement: String,
    val trailer: String,
    val form: FormBlock? = null,
) : Segment {
    override val text: String get() = header.joinToString("") + indent + statement + trailer
    val keyword: String get() = statement.takeWhile { it.isLetterOrDigit() || it == '_' }.lowercase()
}

/** A V1 rule its marker held without a block. It writes nothing until the script is first rewritten. */
internal data class Unwritten(val id: Int, val rule: FilterRule) : Segment {
    override val text: String get() = ""
}

/** The one reason a script is unreadable: [opener] at [offset] is never closed. */
internal class Undelimited(val opener: String, val offset: Int) : Exception("$opener at $offset is never closed")

internal enum class TokenKind { WORD, TAG, STRING, NUMBER, PUNCT, COMMENT, LITERAL }

/** A lexical token of a statement; a [TokenKind.STRING]'s [text] is already unescaped. */
internal data class SieveToken(val kind: TokenKind, val text: String, val start: Int, val end: Int) {
    fun isWord(word: String) = kind == TokenKind.WORD && text.equals(word, ignoreCase = true)
    fun isPunct(c: Char) = kind == TokenKind.PUNCT && text.length == 1 && text[0] == c
}

/**
 * Cuts Sieve source without judging it: comments `#` and `/* */`, strings with `\"` and `\\`,
 * `text:` literals, nested brackets. It refuses only what it cannot delimit.
 */
internal class SieveScanner(private val s: String) {

    /**
     * The single gate of "unreadable": every construct that runs to the end of the text without
     * its closer comes through here, so a script is never cut at a guess and rewritten.
     */
    private fun closed(end: Int, opener: String, at: Int): Int {
        if (end < 0) throw Undelimited(opener, at)
        return end
    }

    fun split(firstId: Int): List<Segment> {
        val out = mutableListOf<Segment>()
        var id = firstId
        var i = 0
        if (s.startsWith(BULWARK_BEGIN)) {
            i = trailerEnd(closed(blockCommentEnd(0), "/*", 0))
            out += Gap(s.substring(0, i))
        }
        while (i < s.length) {
            val gapStart = i
            val hashes = HashSet<Int>()
            while (i < s.length) {
                when {
                    s[i].isWhitespace() -> i++
                    s[i] == '#' -> { hashes += i; i = lineEnd(i) }
                    s.startsWith("/*", i) -> i = closed(blockCommentEnd(i), "/*", i)
                    else -> break
                }
            }
            if (i >= s.length) {
                out += Gap(s.substring(gapStart))
                break
            }
            val end = statementEnd(i)
            val stop = trailerEnd(end)
            val (top, lineStart) = headerBounds(gapStart, i, hashes)
            if (top > gapStart) out += Gap(s.substring(gapStart, top))
            out += Command(
                id = id++,
                header = linesOf(s.substring(top, lineStart)),
                indent = s.substring(lineStart, i),
                statement = s.substring(i, end),
                trailer = s.substring(end, stop),
            )
            i = stop
        }
        return out
    }

    fun tokens(): List<SieveToken> {
        val out = mutableListOf<SieveToken>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val start = i
            when {
                c.isWhitespace() -> { i++; continue }
                c == '#' -> { i = lineEnd(i); out += SieveToken(TokenKind.COMMENT, s.substring(start, i), start, i) }
                s.startsWith("/*", i) -> {
                    i = closed(blockCommentEnd(i), "/*", i)
                    out += SieveToken(TokenKind.COMMENT, s.substring(start, i), start, i)
                }
                c == '"' -> {
                    i = closed(stringEnd(i), "\"", i)
                    out += SieveToken(TokenKind.STRING, unescape(s.substring(start + 1, i - 1)), start, i)
                }
                isTextLiteral(i) -> {
                    i = closed(textLiteralEnd(i), "text:", i)
                    out += SieveToken(TokenKind.LITERAL, s.substring(start, i), start, i)
                }
                c == ':' -> {
                    i++
                    while (i < s.length && isWordChar(s[i])) i++
                    out += SieveToken(TokenKind.TAG, s.substring(start, i), start, i)
                }
                c.isDigit() -> {
                    while (i < s.length && s[i].isLetterOrDigit()) i++
                    out += SieveToken(TokenKind.NUMBER, s.substring(start, i), start, i)
                }
                isWordChar(c) -> {
                    while (i < s.length && isWordChar(s[i])) i++
                    out += SieveToken(TokenKind.WORD, s.substring(start, i), start, i)
                }
                else -> { i++; out += SieveToken(TokenKind.PUNCT, c.toString(), start, i) }
            }
        }
        return out
    }

    /** The end of the command at [start]: its `;`, or the `}` of its last block (`elsif`/`else` included). */
    private fun statementEnd(start: Int): Int {
        val open = ArrayDeque<Int>()
        var i = start
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> i = closed(stringEnd(i), "\"", i)
                c == '#' -> i = lineEnd(i)
                s.startsWith("/*", i) -> i = closed(blockCommentEnd(i), "/*", i)
                isTextLiteral(i) -> i = closed(textLiteralEnd(i), "text:", i)
                c == '(' || c == '[' || c == '{' -> { open.addLast(i); i++ }
                c == ')' || c == ']' || c == '}' -> {
                    val at = open.removeLastOrNull()
                    // A closer of the wrong kind leaves its opener with no closer we could trust.
                    if (at != null && s[at] != OPENER.getValue(c)) closed(-1, s[at].toString(), at)
                    i++
                    if (at != null && c == '}' && open.isEmpty()) {
                        val next = nextWord(i)
                        if (next != "elsif" && next != "else") return i
                    }
                }
                c == ';' && open.isEmpty() -> return i + 1
                else -> i++
            }
        }
        open.firstOrNull()?.let { closed(-1, s[it].toString(), it) }
        return s.length
    }

    private fun headerBounds(gapStart: Int, command: Int, hashes: Set<Int>): Pair<Int, Int> {
        val lineStart = s.lastIndexOf('\n', command - 1) + 1
        if (lineStart < gapStart || (lineStart until command).any { !s[it].isWhitespace() }) return command to command
        var top = lineStart
        while (top > gapStart) {
            val prev = s.lastIndexOf('\n', top - 2) + 1
            if (prev < gapStart) break
            // Only a real `#` comment glues; a `#` inside a `/* */` or a blank line does not.
            val first = (prev until top).firstOrNull { !s[it].isWhitespace() } ?: break
            if (first !in hashes) break
            top = prev
        }
        return top to lineStart
    }

    private fun trailerEnd(end: Int): Int {
        var j = end
        while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
        if (j < s.length && s[j] == '#') j = lineEnd(j)
        return when {
            s.startsWith("\r\n", j) -> j + 2
            j < s.length && s[j] == '\n' -> j + 1
            j == s.length -> j
            else -> end
        }
    }

    private fun nextWord(from: Int): String {
        var i = from
        while (i < s.length) {
            when {
                s[i].isWhitespace() -> i++
                s[i] == '#' -> i = lineEnd(i)
                s.startsWith("/*", i) -> i = blockCommentEnd(i).takeIf { it >= 0 } ?: return ""
                else -> break
            }
        }
        val start = i
        while (i < s.length && isWordChar(s[i])) i++
        return s.substring(start, i).lowercase()
    }

    private fun lineEnd(i: Int): Int = s.indexOf('\n', i).takeIf { it >= 0 } ?: s.length

    private fun blockCommentEnd(i: Int): Int = s.indexOf("*/", i + 2).let { if (it < 0) -1 else it + 2 }

    private fun stringEnd(i: Int): Int {
        var j = i + 1
        while (j < s.length) {
            when (s[j]) {
                '\\' -> j += 2
                '"' -> return j + 1
                else -> j++
            }
        }
        return -1
    }

    private fun isTextLiteral(i: Int): Boolean =
        s.regionMatches(i, "text:", 0, 5, ignoreCase = true) && (i == 0 || !isWordChar(s[i - 1]) && s[i - 1] != ':')

    /** RFC 5228 multi-line: `text:`, blanks or a comment, a line break, lines, and a line holding a lone dot. */
    private fun textLiteralEnd(i: Int): Int {
        var j = i + 5
        while (j < s.length && (s[j] == ' ' || s[j] == '\t')) j++
        if (j < s.length && s[j] == '#') j = lineEnd(j)
        when {
            s.startsWith("\r\n", j) -> j += 2
            j < s.length && s[j] == '\n' -> j++
            j >= s.length -> return -1
            else -> return j
        }
        while (j < s.length) {
            val e = lineEnd(j)
            if (s.substring(j, e).removeSuffix("\r") == ".") return if (e < s.length) e + 1 else e
            j = e + 1
        }
        return -1
    }

    companion object {
        const val BULWARK_BEGIN = "/* @metadata:begin"
        private val OPENER = mapOf(')' to '(', ']' to '[', '}' to '{')

        private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_'
    }
}

/** [text] cut after each `\n`, the line endings kept. */
internal fun linesOf(text: String): List<String> = buildList {
    var start = 0
    while (start < text.length) {
        val nl = text.indexOf('\n', start)
        if (nl < 0) {
            add(text.substring(start))
            break
        }
        add(text.substring(start, nl + 1))
        start = nl + 1
    }
}

/** `\r\n` when the script has more of them than bare `\n`; blocks Sterna adds take it, nothing else is normalised. */
internal fun dominantLineEnding(text: String): String {
    val crlf = Regex("\r\n").findAll(text).count()
    return if (crlf > text.count { it == '\n' } - crlf) "\r\n" else "\n"
}

internal fun sieveTokens(statement: String): List<SieveToken>? =
    try {
        SieveScanner(statement).tokens()
    } catch (_: Undelimited) {
        null
    }

/** The value of a quoted-string's content, the pendant of `SieveCodec.escape`: a backslash keeps the character after it. */
internal fun unescape(quoted: String): String {
    val out = StringBuilder(quoted.length)
    var i = 0
    while (i < quoted.length) {
        if (quoted[i] == '\\' && i + 1 < quoted.length) i++
        out.append(quoted[i])
        i++
    }
    return out.toString()
}

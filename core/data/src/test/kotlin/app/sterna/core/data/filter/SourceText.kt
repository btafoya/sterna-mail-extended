package app.sterna.core.data.filter

import java.io.File

/**
 * Reading the SHIPPED source as text — the instrument of the source lints, in one place.
 *
 * A source lint is a **last resort**, never the proof: it is used where the decision cannot be
 * executed in a JVM test (`MailRepository` needs a session, a JMAP client and a device), or where
 * what must be held still is the SOURCE itself rather than a value (the Sieve `generate` emits,
 * which every script already on a server was written by).
 *
 * It compares WHOLE lines, never substrings: a `contains` is satisfied by every mutation that
 * makes the line LONGER, and one such mutation already walked straight past the first version of
 * the `MailRepository` lint.
 */
internal object SourceText {

    /** [text] as trimmed code lines: comment lines dropped whole, trailing comments cut. */
    fun codeLines(text: String): List<String> = text.lines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring `//` inside a string literal. */
    fun withoutTrailingComment(line: String): String {
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
     * The source of [relative], resolved from the test's working directory by walking up (Gradle
     * runs the tests from the module directory, a bare `java` run from the repository root).
     */
    fun read(relative: String): String = locate(relative).readText()

    /**
     * The slice of [source] running from [signature] to the next KDoc block at the same indent —
     * i.e. one function, comments included, so a lint can drop them itself.
     *
     * The LAST member of a class or object has no KDoc after it, and that is not a reason to
     * leave it unpinned: `SieveCodec.escape` was exactly that member, and a mutation adding one
     * `replace` to it changed the emitted Sieve while every lint stayed green. So the fall-back is
     * the close of the enclosing declaration — the final `}` in column 0.
     */
    fun functionSource(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "the source no longer declares `$signature`" }
        val end = source.indexOf("\n    /**", start).takeIf { it > start } ?: source.lastIndexOf("\n}")
        check(end > start) { "no declaration follows `$signature` — the slice would be the rest of the file" }
        return source.substring(start, end)
    }

    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}

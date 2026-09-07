package app.sterna.widget

import java.io.File

/**
 * Reading a DECLARATION as text — the mechanics shared by the widgets' source lints, and nothing
 * else. There is no decision in this file: what is pinned, and to what, lives in the lints
 * themselves.
 *
 * It exists because there are now two widgets, and a third would make three copies of the same
 * brace counter. It is not a rule and must never grow one: a helper that decides what is
 * acceptable is a lint no test names.
 */
internal object DeclarationSource {

    /** Repo root, found by walking up from the module's working directory. */
    val ROOT: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/AndroidManifest.xml").isFile }
            ?: error(
                "cannot locate the repo root from ${File("").absolutePath} — these tests read the " +
                    "declaration as text and need a working directory inside the checkout",
            )
    }

    fun file(path: String): File = File(ROOT, path).also {
        if (!it.isFile) error("expected a file at ${it.path} — was it moved?")
    }

    /**
     * The file's lines, comments removed WHOLE — an `<!-- … -->` block is cut out before the split,
     * and a `//` line is dropped. Without that, every rule also matches the prose written to
     * explain it.
     */
    fun codeLines(file: File): List<String> = file.readText()
        .replace(XML_COMMENT, "")
        .lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("//") }

    /**
     * The whole brace-balanced block that opens with [opener] and contains [marker] — so a rule can
     * pin WHERE a line lives and not merely that it exists.
     *
     * Deliberately returns an empty list when [marker] is nowhere in the file: moving the line out
     * of the block is the mutation the caller exists to catch, and an assertion showing the
     * expected lines against `[]` says more than an exception would.
     */
    fun blockContaining(file: File, marker: String, opener: String): List<String> {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { marker in it }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it] == opener }
            ?: error("no `$opener` above `$marker` in ${file.name}")
        val block = mutableListOf<String>()
        var depth = 0
        for (i in start until lines.size) {
            val line = lines[i]
            block += line
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth == 0) return block
        }
        error("`$opener` in ${file.name} never closes")
    }

    /**
     * The XML element that carries [marker], whole: from the `<tag` line above it down to the line
     * that closes the element. Same purpose as [blockContaining] one language over — an attribute
     * exists on SOME element, and which one is the thing worth pinning.
     *
     * Empty when [marker] is nowhere in the file, for the same reason.
     */
    fun element(file: File, marker: String): List<String> {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == marker }
        if (at < 0) return emptyList()
        val start = (at downTo 0).firstOrNull { lines[it].startsWith("<") && !lines[it].startsWith("</") }
            ?: error("no opening tag above `$marker` in ${file.name}")
        val end = (start until lines.size).firstOrNull {
            lines[it].endsWith("/>") || lines[it].startsWith("</")
        } ?: error("the element carrying `$marker` in ${file.name} never closes")
        return lines.subList(start, end + 1)
    }

    /**
     * The same for KOTLIN: KDoc and `//` lines dropped, trailing `//` cut outside string literals.
     * [codeLines] only knows XML comments, and a Kotlin lint run through it pins the prose written
     * to explain the rule as if it were the rule.
     */
    fun kotlinLines(file: File): List<String> = file.readLines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    /** [line] up to its `//` comment, ignoring a `//` inside a string literal. */
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
     * The code lines of the Kotlin function opening with [signature], its own closing brace
     * excluded. Fails loudly when the signature is gone: a rename must redden the rule rather than
     * satisfy it against an empty list.
     */
    fun functionBody(file: File, signature: String): List<String> {
        val lines = kotlinLines(file)
        val start = lines.indexOfFirst { it.startsWith(signature) }
        check(start >= 0) { "${file.name} no longer declares `$signature` — was it renamed?" }
        val body = mutableListOf<String>()
        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            val line = lines[i]
            val next = depth + line.count { it == '{' } - line.count { it == '}' }
            if (opened && next == 0) return body
            body += line
            depth = next
            if (depth > 0) opened = true
        }
        error("`$signature` in ${file.name} never closes — the slice would be the rest of the file")
    }

    private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
}

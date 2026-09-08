package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the stamp factory is PLUGGED in, the source half of [NumberingStampsTest], which runs the
 * decision itself (Codeberg #99).
 */
class NumberingStampsSourceTest {

    private val delegation =
        "return numberingStampsOfRows(credentials, emailIds) { chunk -> " +
            "emailDao.numberingOfRows(credentials.id, chunk) }"

    /** Code lines, comments dropped and whitespace normalised, compared whole, never searched. */
    private fun codeLinesOf(body: String): List<String> =
        body.lines().map { it.replace(Regex("""\s+"""), " ").trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private fun codeLinesNaming(body: String, needle: Regex): List<String> =
        codeLinesOf(body).filter { needle.containsMatchIn(it) }

    /**
     * Any mention of a numbering under any spelling, the same needle as
     * [ImapMoveUnderNumberingTest]. A rule pinned to one identifier is blind to the same value
     * under another name, and Kotlin's shadowing leaves the guarded line byte-identical.
     */
    private val anyNumbering = Regex("(?i)(numbering|validity|frozen|recorded|stamp)")

    @Test fun `the repository hands the whole decision to the extracted function`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "numberingRowsWereReadUnder")
        assertEquals(
            "numberingRowsWereReadUnder must be a DELEGATION and nothing else: every line of it " +
                "that is not the hand-over is a decision no JVM test can execute, which is how " +
                "the protocol guard and the stamp itself shipped untested. Body was:\n$body",
            listOf("{", delegation, "}"),
            codeLinesOf(body),
        )
    }

    @Test fun `nothing else in the repository reads a row numbering unchunked`() {
        val lines = codeLinesOf(DaoQuerySource.mailSource("MailRepository"))
            .filter { "numberingOfRows(" in it }
        assertEquals(
            "emailDao.numberingOfRows must be reached from exactly one line, the delegation: a " +
                "second call site is a second unchunked IN (...) and a second untested guard",
            listOf(delegation),
            lines,
        )
    }

    @Test fun `the hand-over is the only line of it that names a numbering`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "numberingRowsWereReadUnder")
        assertEquals(
            "a second numbering line here is a stamp re-read, re-mapped or overwritten AFTER the " +
                "delegation — `.mapValues { null }`, a local `val frozen = …`, an early return. " +
                "Body was:\n$body",
            listOf(delegation),
            codeLinesNaming(body, anyNumbering),
        )
    }
}

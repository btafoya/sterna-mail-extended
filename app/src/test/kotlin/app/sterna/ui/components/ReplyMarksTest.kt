package app.sterna.ui.components

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Which reply marks a message wears (#259), run on the decision itself. The two flags are separate
 * facts the server keeps: a replied message shown as forwarded, or the other way round, tells the
 * reader something about their own mail that never happened.
 */
class ReplyMarksTest {

    @Test fun `a message neither replied to nor forwarded wears no mark`() {
        assertEquals(emptyList<ReplyMark>(), replyMarks(answered = false, forwarded = false))
    }

    @Test fun `a replied message wears the reply mark alone`() {
        assertEquals(listOf(ReplyMark.ANSWERED), replyMarks(answered = true, forwarded = false))
    }

    @Test fun `a forwarded message wears the forward mark alone`() {
        assertEquals(listOf(ReplyMark.FORWARDED), replyMarks(answered = false, forwarded = true))
    }

    @Test fun `a message both replied to and forwarded wears the two, reply first`() {
        assertEquals(
            listOf(ReplyMark.ANSWERED, ReplyMark.FORWARDED),
            replyMarks(answered = true, forwarded = true),
        )
    }

    @Test fun `each mark is spoken by its own string`() {
        assertEquals(R.string.a11y_answered, ReplyMark.ANSWERED.label)
        assertEquals(R.string.a11y_forwarded, ReplyMark.FORWARDED.label)
    }

    @Test fun `every language names the two marks, and names them apart`() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/res") }
            .first { File(it, "values/strings.xml").isFile }
        val files = (res.listFiles() ?: emptyArray())
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .map { File(it, "strings.xml") }
            .filter { it.isFile }
        assertEquals("values/ plus ten languages", 11, files.size)
        val offenders = files.mapNotNull { file ->
            val answered = valueOf(file, "a11y_answered")
            val forwarded = valueOf(file, "a11y_forwarded")
            if (answered.isNullOrBlank() || forwarded.isNullOrBlank() || answered == forwarded) {
                "${file.parentFile?.name}: \"$answered\" / \"$forwarded\""
            } else {
                null
            }
        }
        assertEquals(emptyList<String>(), offenders)
        val english = File(res, "values/strings.xml")
        assertEquals(listOf("Replied", "Forwarded"), listOf(valueOf(english, "a11y_answered"), valueOf(english, "a11y_forwarded")))
    }

    private fun valueOf(file: File, key: String): String? =
        Regex("<string\\s+name=\"$key\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())?.groupValues?.get(1)
}

package app.sterna.ui.compose

import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The cut behind the composer's "Remove quoted text" entry (#269). Bodies are built the way the
 * composer builds them, through `deepenQuote` and `bodyWithSignature`, so a change of either shape
 * reaches these cases.
 *
 * The rule pinned here, kept out of the production file whose comment count is ratcheted: every line
 * starting with ">" goes, and so does the line just above each quoted block when it ends with a
 * colon, ASCII or full width. The attribution is recognised by that shape and not by
 * `compose_quote_attribution`: it may have been written in another language than the one the app
 * runs in now, or edited. The blank lines touching a cut become one between two kept lines; at the
 * end of the body they all go; at its head the blank lines above the cut stay and those below it go,
 * since that head is the empty first line a reply opens on and the result then has the shape of a
 * new message. Nothing else moves.
 */
class RemoveQuotedTextTest {

    private val attribution = "On 1 Jan 2026, Alice wrote:"

    private fun quote(attribution: String?, vararg original: String): String =
        "\n\n" + listOfNotNull(attribution).plus(original.map(::deepenQuote)).joinToString("\n")

    @Test fun `signature above the quote, with the delimiter, survives alone`() {
        val body = bodyWithSignature(quote(attribution, "Hello", "Bye"), "Carol", delimiter = true)
        assertEquals("\n\n-- \nCarol", removeQuotedText(body))
    }

    @Test fun `signature above the quote, without the delimiter, survives alone`() {
        val body = "Thanks!" + bodyWithSignature(quote(attribution, "Hello"), "Carol", delimiter = false)
        assertEquals("Thanks!\n\nCarol", removeQuotedText(body))
    }

    @Test fun `signature below the quote keeps the typed text above it`() {
        val body = "Thanks!" +
            bodyWithSignature(quote(attribution, "Hello"), "Carol", signatureBelowQuote = true, delimiter = false)
        assertEquals("Thanks!\n\nCarol", removeQuotedText(body))
    }

    @Test fun `a body reduced to its signature below the quote keeps the empty first line`() {
        val body = bodyWithSignature(quote(attribution, "Hello"), "Carol", signatureBelowQuote = true, delimiter = true)
        assertEquals("\n\n-- \nCarol", removeQuotedText(body))
    }

    @Test fun `a reply with nothing but the quote becomes empty`() {
        assertEquals("", removeQuotedText(quote(attribution, "Hello")))
    }

    @Test fun `an inline reply keeps what was typed between the blocks`() {
        val body = "Hi Alice,\n\n$attribution\n> First question?\n\nYes.\n> Second question?\nNo, and here is why.\n\nCarol"
        assertEquals("Hi Alice,\n\nYes.\nNo, and here is why.\n\nCarol", removeQuotedText(body))
    }

    @Test fun `a typed line right above a block that does not end with a colon survives`() {
        assertEquals("I agree\n\nCarol", removeQuotedText("I agree\n> Shall we?\n\nCarol"))
    }

    @Test fun `a deeper level goes with its block`() {
        val body = "Sure." + quote(attribution, "Fine by me.", "On Monday, Bob wrote:", "> Lunch?")
        assertTrue(body.contains("\n>> Lunch?"))
        assertEquals("Sure.", removeQuotedText(body))
    }

    @Test fun `a quote without its attribution is still cut`() {
        assertEquals("Sure.", removeQuotedText("Sure." + quote(null, "Hello")))
    }

    @Test fun `an attribution edited to lose its colon is typed text and stays`() {
        assertEquals("Sure.\n\nAlice said", removeQuotedText("Sure." + quote("Alice said", "Hello")))
    }

    @Test fun `an attribution edited but still ending with a colon goes`() {
        assertEquals("Sure.", removeQuotedText("Sure." + quote("Alice, last week:  ", "Hello")))
    }

    @Test fun `a full-width colon closes an attribution too`() {
        assertEquals("好的", removeQuotedText("好的" + quote("2026年1月1日，张三 写道：", "你好")))
    }

    @Test fun `blank lines away from the cut are left as typed`() {
        assertEquals("a\n\n\n\nb", removeQuotedText("a\n\n\n\nb" + quote(attribution, "Hello")))
    }

    @Test fun `no quoted line, nothing to cut`() {
        val body = "Hi, a > b here\n\n-- \nCarol"
        assertNull(removeQuotedText(body))
        assertFalse(hasQuotedText(body))
    }

    @Test fun `a quoted line anywhere offers the cut`() {
        assertTrue(hasQuotedText("Hi\n\n-- \nCarol\n\n> Hello"))
        assertTrue(hasQuotedText(">Hello"))
    }

    @Test fun `every shipped attribution is recognised and goes with its quote`() {
        val files = (File(DeclarationSource.ROOT, "app/src/main/res").listFiles() ?: emptyArray())
            .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
            .map { File(it, "strings.xml") }
            .filter { it.isFile && ATTRIBUTION in it.readText() }
        assertEquals("the eleven languages each carry a `compose_quote_attribution`", 11, files.size)
        for (file in files) {
            val template = Regex("<string name=\"compose_quote_attribution\">(.*?)</string>")
                .find(file.readText())!!.groupValues[1]
                .replace("\\'", "'").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            val line = String.format(template, "1 Jan 2026 10:00", "Alice <alice@example.org>")
            val body = "Reply" + quote(line, "Hello")
            assertEquals("${file.parentFile.name}: `$line`", "Reply", removeQuotedText(body))
        }
    }

    private companion object {
        const val ATTRIBUTION = "name=\"compose_quote_attribution\""
    }
}

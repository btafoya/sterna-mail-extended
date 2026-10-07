package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What a stored `preview` becomes on its way to a list row or a notification. */
class PreviewTextTest {

    @Test fun `an encoded preheader leaves neither its entities nor its invisibles`() {
        assertEquals("Hello", previewForDisplay("Hello&#847;&zwnj; &#847;&zwnj; &#847;&zwnj;"))
    }

    @Test fun `a preheader already decoded by the server is removed too`() {
        assertEquals("Hello", previewForDisplay("Hello\u034F\u200C\u200B\u200D\uFEFF"))
    }

    @Test fun `a preview made of invisibles and blanks is no preview`() {
        listOf(
            "&#847;&zwnj; &#847;&zwnj; &#847;&zwnj;",
            "\u034F\u200C \u034F\u200C\n\t\u200B\uFEFF",
            "&nbsp;&zwj;&#x200B;&#xFEFF;",
            "&lrm;&rlm;\u202E",
            "",
            "   ",
        ).forEach { raw ->
            assertNull("'$raw' must read as no preview", previewForDisplay(raw))
        }
    }

    @Test fun `entities are decoded`() {
        assertEquals("AT&T — café", previewForDisplay("AT&amp;T &mdash; caf&eacute;"))
    }

    /** One pass: a second one would turn text the sender escaped twice into markup-looking text. */
    @Test fun `a doubly escaped entity is decoded once`() {
        assertEquals("&amp;", previewForDisplay("&amp;amp;"))
        assertEquals("&lt;b&gt;", previewForDisplay("&amp;lt;b&amp;gt;"))
    }

    @Test fun `the joiner of an emoji sequence is removed`() {
        assertEquals("👨👩 home", previewForDisplay("👨\u200D👩 home"))
    }

    @Test fun `an encoded bidi override is caught after decoding`() {
        assertEquals("invoice fdp.exe", previewForDisplay("invoice &#x202E;fdp.exe"))
    }

    @Test fun `a reference cut off at the end of the preview is not shown`() {
        assertEquals("Hello", previewForDisplay("Hello&#847;&zwnj; &#847;&zw")?.trim())
        listOf("&", "&#", "&#84", "&#x", "&#x20", "&z", "&zw", "&zwn", "&zwnj", "&zwj", "&n", "&nb", "&nbs", "&nbsp")
            .forEach { cut -> assertEquals("cut at '$cut'", "Hello", previewForDisplay("Hello$cut")) }
    }

    @Test fun `a preheader cut in the middle of a reference is still no preview`() {
        listOf("&#847;&zwnj; &#84", "&#847;&zwnj; &", "&zwnj;&nbs").forEach { raw ->
            assertNull("'$raw' must read as no preview", previewForDisplay(raw))
        }
    }

    @Test fun `an ampersand that starts no cut reference stays at the end`() {
        assertEquals("R&D", previewForDisplay("R&D"))
        assertEquals("fish &amp", previewForDisplay("fish &amp"))
    }

    @Test fun `only the raw text is trimmed of a cut reference, not what decoding produced`() {
        assertEquals("&zw", previewForDisplay("&amp;zw"))
        assertEquals("a &", previewForDisplay("a &amp;"))
    }

    private val padding = "&#847;&zwnj; "

    /** Removing the padding is what leaves the run: forty blanks between the greeting and the text. */
    @Test fun `the blanks a removed preheader leaves behind are one space`() {
        val raw = "Hello" + padding.repeat(40) + "Your PayID is now registered."
        assertEquals("Hello Your PayID is now registered.", previewForDisplay(raw))
    }

    @Test fun `every run of blanks is one space and the ends carry none`() {
        assertEquals("a b c d", previewForDisplay(" a\tb\nc  d "))
        assertEquals("a b c", previewForDisplay("\n\t a \r\n\t b  c \n"))
        assertEquals("a b", previewForDisplay("a\rb"))
        assertEquals("10 000 EUR", previewForDisplay("10&nbsp;000&nbsp; EUR&nbsp;"))
    }

    /** A filtered character between two blanks must not leave them side by side. */
    @Test fun `blanks brought together by the bidi filter are one space`() {
        assertEquals("invoice fdp.exe", previewForDisplay("invoice \u202E fdp.exe"))
    }

    @Test fun `a reference cut before the server's dots leaves nothing of itself`() {
        listOf("&", "&#", "&#84", "&#x20", "&z", "&zwn", "&zwnj", "&zwj", "&nbs", "&nbsp").forEach { cut ->
            assertEquals("cut at '$cut...'", "Hello ...", previewForDisplay("Hello$padding$cut..."))
            assertEquals("cut at '$cut…'", "Hello …", previewForDisplay("Hello$padding$cut…"))
            assertEquals("cut at '$cut'", "Hello", previewForDisplay("Hello$padding$cut"))
        }
        listOf("Hello$padding&#84...", "Hello$padding&...", "Hello$padding&").forEach { raw ->
            assertFalse("'$raw' must show no ampersand", previewForDisplay(raw).orEmpty().contains('&'))
        }
    }

    /** The end the server returned for the message this was reported on: 256 characters. */
    @Test fun `a preview cut by the server at its limit keeps the dots and loses the ampersand`() {
        val raw = "Hello" + padding.repeat(19) + "&..."
        assertEquals(256, raw.length)
        assertEquals("Hello ...", previewForDisplay(raw))
    }

    @Test fun `a preheader cut by the server is no preview, not its dots alone`() {
        listOf(
            padding.repeat(19) + "&...",
            padding.repeat(19) + "&#84...",
            padding.repeat(19) + "&zw…",
            padding.repeat(19) + "...",
            "&#847;&zwnj;...",
            "&nbsp;&nbsp;&lrm;&nbsp;...",
        ).forEach { raw ->
            assertNull("'$raw' must read as no preview", previewForDisplay(raw))
        }
    }

    @Test fun `dots the sender wrote are a preview`() {
        assertEquals("...", previewForDisplay("..."))
        assertEquals("...", previewForDisplay(" ... "))
    }

    @Test fun `an ampersand that starts no cut reference stays before the server's dots`() {
        assertEquals("R&D...", previewForDisplay("R&D..."))
        assertEquals("R&D…", previewForDisplay("R&D…"))
        assertEquals("fish &amp...", previewForDisplay("fish &amp..."))
    }

    /** The dots are only the server's at the very end, three of them or one ellipsis. */
    @Test fun `a fragment followed by anything but the closing dots is the sender's text`() {
        assertEquals("a &.. b", previewForDisplay("a &.. b"))
        assertEquals("a &....", previewForDisplay("a &...."))
        assertEquals("&zw...", previewForDisplay("&amp;zw..."))
    }
}

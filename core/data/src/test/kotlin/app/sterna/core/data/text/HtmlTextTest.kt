package app.sterna.core.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTextTest {

    // --- htmlToText: block structure survives the flattening ------------------------------------

    @Test fun blockEndsBecomeLineBreaks() {
        assertEquals("one\ntwo", htmlToText("<p>one</p><p>two</p>"))
        assertEquals("one\ntwo", htmlToText("<div>one</div><div>two</div>"))
        assertEquals("one\ntwo", htmlToText("one<br>two"))
    }

    @Test fun tableSignatureKeepsOneLinePerRow() {
        // The classic corporate signature: a one-column-per-field table. Rows must not collapse
        // onto a single line (which is what a <br>-only flattening did).
        val html = """
            <table><tr><td>Alex Rivera</td></tr>
            <tr><td>Acme</td></tr>
            <tr><td>+33 1 23 45 67 89</td></tr></table>
        """.trimIndent()
        assertEquals("Alex Rivera\nAcme\n+33 1 23 45 67 89", htmlToText(html))
    }

    @Test fun deliberateBlankLinesSurvive() {
        assertEquals("one\n\ntwo", htmlToText("one<br><br>two"))
    }

    @Test fun cellsOnTheSameRowStaySeparated() {
        assertEquals("Phone +33 1 23", htmlToText("<table><tr><td>Phone</td><td>+33 1 23</td></tr></table>"))
    }

    @Test fun linksFlattenToTheirLabelOnly() {
        assertEquals("acme.fr", htmlToText("""<a href="https://acme.fr">acme.fr</a>"""))
        assertEquals(
            "Alex Rivera\nacme.fr",
            htmlToText("""<div>Alex Rivera</div><div><a href="https://acme.fr/team">acme.fr</a></div>"""),
        )
    }

    @Test fun importedSignatureWithSourceLineBreakBeforeBrHasNoBlankLine() {
        val html = "Sent via <a href=\"https://sterna.app\">Sterna</a> <a href=\"https://sterna.app\">Mail</a>,\n<BR>Mohamad\n"
        assertEquals("Sent via Sterna Mail,\nMohamad", htmlToText(html))
    }

    @Test fun importedSignatureWithCrlfSourceLineBreakHasNoBlankLine() {
        val html = "Sent via <a href=\"https://sterna.app\">Sterna</a> <a href=\"https://sterna.app\">Mail</a>,\r\n<BR>Mohamad\r\n"
        assertEquals("Sent via Sterna Mail,\nMohamad", htmlToText(html))
    }

    @Test fun sourceLineBreakInsideAParagraphIsASpace() {
        assertEquals("Alex Rivera", htmlToText("<p>Alex\nRivera</p>"))
    }

    @Test fun sourceLineBreakBeforeABrLeavesOneLineBreak() {
        assertEquals("Acme,\nParis", htmlToText("Acme,\n<br>Paris"))
    }

    @Test fun lineBreaksInsidePreSurvive() {
        assertEquals("a\nb", htmlToText("<pre>a\nb</pre>"))
    }

    @Test fun sourceLineBreakAfterABrDoesNotIndentTheNextLine() {
        assertEquals("Name\nTitle\nPhone", htmlToText("Name<br>\nTitle<br>\nPhone"))
    }

    @Test fun signatureDelimiterAfterABrStaysExactlyTwoDashes() {
        assertEquals("Best\n--\nAlex", htmlToText("Best<br>\n-- <br>\nAlex"))
    }

    @Test fun sourceLineBreakAfterABlockEndDoesNotIndentTheNextLine() {
        assertEquals("a\nb", htmlToText("<p>a</p>\nb"))
    }

    @Test fun preIsMatchedWhateverItsCaseAndAttributes() {
        assertEquals("a\nb", htmlToText("<PRE class=\"moz-quote-pre\" wrap=\"\">a\nb</PRE>"))
    }

    @Test fun indentationAfterALineBreakInsidePreSurvives() {
        assertEquals("x\n    y", htmlToText("<pre>x\n    y</pre>"))
    }

    @Test fun scriptAndStyleAreDropped() {
        assertEquals("hi", htmlToText("<style>p{color:red}</style><p>hi</p><script>x()</script>"))
    }

    // --- unescapeEntities ------------------------------------------------------------------------

    @Test fun decodesNumericEntitiesDecimalAndHex() {
        assertEquals("café", unescapeEntities("caf&#233;"))
        assertEquals("café", unescapeEntities("caf&#xE9;"))
        assertEquals("café", unescapeEntities("caf&#Xe9;"))
        assertEquals("€10", unescapeEntities("&#8364;10"))
    }

    @Test fun decodesTheCommonNamedEntities() {
        assertEquals("a — b", unescapeEntities("a &mdash; b"))
        assertEquals("a – b", unescapeEntities("a &ndash; b"))
        assertEquals("café", unescapeEntities("caf&eacute;"))
        assertEquals("École", unescapeEntities("&Eacute;cole"))
        assertEquals("« oui »", unescapeEntities("&laquo; oui &raquo;"))
        assertEquals("© 2026 Acme…", unescapeEntities("&copy; 2026 Acme&hellip;"))
        assertEquals("straße", unescapeEntities("stra&szlig;e"))
    }

    @Test fun keepsTheOldCoreEntities() {
        assertEquals("<>\"'&", unescapeEntities("&lt;&gt;&quot;&apos;&amp;"))
        assertEquals("a b", unescapeEntities("a&nbsp;b"))
    }

    @Test fun decodesTheInvisibleJoinersAndMarks() {
        assertEquals("a\u200Cb", unescapeEntities("a&zwnj;b"))
        assertEquals("a\u200Db", unescapeEntities("a&zwj;b"))
        assertEquals("a\u200Eb", unescapeEntities("a&lrm;b"))
        assertEquals("a\u200Fb", unescapeEntities("a&rlm;b"))
    }

    @Test fun doubleEscapedEntityIsDecodedOnce() {
        // One pass only: "&amp;lt;" is the literal text "&lt;", not "<".
        assertEquals("&lt;", unescapeEntities("&amp;lt;"))
    }

    @Test fun unknownOrOutOfRangeReferencesAreLeftAlone() {
        assertEquals("&notanentity;", unescapeEntities("&notanentity;"))
        assertEquals("&#0;", unescapeEntities("&#0;"))
        assertEquals("&#xD800;", unescapeEntities("&#xD800;"))
        assertEquals("100 & 200", unescapeEntities("100 & 200"))
    }

    @Test fun entitiesAreDecodedWhenFlatteningHtml() {
        assertEquals("Alex — Acme, café", htmlToText("<p>Alex &mdash; Acme, caf&eacute;</p>"))
    }

    // --- misc ------------------------------------------------------------------------------------

    @Test fun looksLikeHtmlOnlyOnTags() {
        assertTrue(looksLikeHtml("<p>hi</p>"))
        assertTrue(looksLikeHtml("<!-- c -->"))
        assertFalse(looksLikeHtml("Alex Rivera\n-- \n1 < 2 > 0"))
        assertFalse(looksLikeHtml("<jean@example.org>"))
        assertFalse(looksLikeHtml("<https://x>"))
        assertTrue(looksLikeHtml("<br/>"))
    }

    @Test fun escapingIsReversedByTheDecoder() {
        val raw = "a < b & c > d"
        assertEquals(raw, unescapeEntities(htmlEscape(raw)))
        assertEquals("a<br>b", htmlEscapeMultiline("a\nb"))
    }
}

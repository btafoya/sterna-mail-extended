package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message whose page declares its own dark background is shown as it is in dark theme, instead of
 * rendered light and inverted into a light page (#302): [declaredDarkPageBackground], executed, and
 * the document [buildHtmlDocument] emits from its answer.
 */
class DarkPageBackgroundTest {

    // --- a. where the document-level background is read -------------------------------------

    @Test fun `bgcolor on body is a document background`() {
        assertEquals(0x111111, declaredDarkPageBackground("""<body bgcolor="#111111"><p>x</p></body>"""))
    }

    @Test fun `unquoted bgcolor on body is read too`() {
        assertEquals(0x000000, declaredDarkPageBackground("<body bgcolor=#000><p>x</p></body>"))
    }

    @Test fun `background-color in the body style attribute is a document background`() {
        assertEquals(
            0x1a1a1a,
            declaredDarkPageBackground("""<body style="margin:0; background-color: #1A1A1A; color:#eee">x</body>"""),
        )
    }

    @Test fun `the background shorthand in the body style attribute is a document background`() {
        assertEquals(0x202020, declaredDarkPageBackground("""<body style='background:#202020 !important'>x</body>"""))
    }

    @Test fun `a style rule on body alone is a document background`() {
        assertEquals(0x121212, declaredDarkPageBackground("<style>body{background-color:#121212}</style><p>x</p>"))
    }

    @Test fun `a style rule on html and body together is a document background`() {
        assertEquals(
            0x0d0d0d,
            declaredDarkPageBackground("<style>p{margin:0} html, body { background: #0d0d0d; }</style><p>x</p>"),
        )
    }

    @Test fun `a style rule whose selector list names anything besides body and html is not`() {
        assertNull(declaredDarkPageBackground("<style>body, .wrapper { background:#000 }</style><p>x</p>"))
        assertNull(declaredDarkPageBackground("<style>body table { background:#000 }</style><p>x</p>"))
    }

    @Test fun `a dark background on an inner element only is not a page background`() {
        assertNull(declaredDarkPageBackground("""<body><div style="background:#000">x</div></body>"""))
    }

    @Test fun `a message with no background at all keeps the invert`() {
        assertNull(declaredDarkPageBackground("<p>Hi</p>"))
    }

    // --- b. which colours are read ------------------------------------------------------------

    @Test fun `rgb and opaque rgba and black are read`() {
        assertEquals(0x0a141e, declaredDarkPageBackground("""<body style="background: rgb(10, 20, 30)">x</body>"""))
        assertEquals(0x0a141e, declaredDarkPageBackground("""<body style="background: rgba(10,20,30,1)">x</body>"""))
        assertEquals(0x000000, declaredDarkPageBackground("""<body bgcolor="black">x</body>"""))
        assertEquals(0x334455, declaredDarkPageBackground("""<body bgcolor="#345">x</body>"""))
    }

    @Test fun `a custom property is not a signal`() {
        assertNull(declaredDarkPageBackground("<style>body{background:var(--bg)}</style>x"))
    }

    @Test fun `a transparent colour is not a signal`() {
        assertNull(declaredDarkPageBackground("""<body style="background: rgba(0,0,0,0.5)">x</body>"""))
        assertNull(declaredDarkPageBackground("""<body style="background: transparent">x</body>"""))
    }

    @Test fun `an image or a gradient or an unknown name is not a signal`() {
        assertNull(declaredDarkPageBackground("""<body style="background: url(cid:bg) #000">x</body>"""))
        assertNull(declaredDarkPageBackground("<style>body{background:linear-gradient(#000,#111)}</style>x"))
        assertNull(declaredDarkPageBackground("""<body bgcolor="midnightblue">x</body>"""))
    }

    @Test fun `a page just under the dark threshold is dark, just over it is not`() {
        // #7b7b7b has a relative luminance of 0.198, #7c7c7c of 0.2016.
        assertEquals(0x7b7b7b, declaredDarkPageBackground("""<body bgcolor="#7b7b7b">x</body>"""))
        assertNull(declaredDarkPageBackground("""<body bgcolor="#7c7c7c">x</body>"""))
    }

    @Test fun `a light page is not dark`() {
        assertNull(declaredDarkPageBackground("""<body bgcolor="#ffffff">x</body>"""))
    }

    // --- c. what disqualifies a dark page -----------------------------------------------------

    @Test fun `a dark page declared only inside a media block is not read`() {
        assertNull(declaredDarkPageBackground("<style>@media (max-width:600px){ body{background:#000} }</style>x"))
    }

    @Test fun `a white card inside a media block still disqualifies the page`() {
        assertNull(
            declaredDarkPageBackground(
                "<style>body{background:#000} @media screen { .card{background:#fff} }</style>x",
            ),
        )
    }

    @Test fun `a white page inside a media block makes the dark page outside it a doubt`() {
        assertNull(
            declaredDarkPageBackground("<style>body{background:#000} @media screen { body{background:#fff} }</style>x"),
        )
    }

    @Test fun `a page background inside any conditional block is a doubt`() {
        assertNull(declaredDarkPageBackground("<style>@supports (display:grid){body{background:#000}}</style>x"))
        assertNull(declaredDarkPageBackground("""<style>@supports (display:grid){body{background:#000}}</style><body bgcolor="#000">x</body>"""))
    }

    @Test fun `a font-face block has no effect on a dark page`() {
        assertEquals(
            0x000000,
            declaredDarkPageBackground("<style>@font-face{font-family:x;src:url(data:,)} body{background:#000}</style>x"),
        )
    }

    @Test fun `a bgcolor without its hash is read, as browsers do`() {
        assertNull(declaredDarkPageBackground("""<body bgcolor="#000"><table bgcolor="FFFFFF"><tr><td>x</td></tr></table></body>"""))
        assertEquals(0x000000, declaredDarkPageBackground("""<body bgcolor="000000">x</body>"""))
    }

    @Test fun `a white shorthand with an image on an inner element disqualifies the page`() {
        assertNull(declaredDarkPageBackground("""<body bgcolor="#000"><table style="background:#ffffff url(x.png)">x</table></body>"""))
    }

    @Test fun `an unreadable inner background is a doubt`() {
        assertNull(declaredDarkPageBackground("""<body bgcolor="#000"><table style="background:whitesmoke">x</table></body>"""))
        assertNull(declaredDarkPageBackground("""<body bgcolor="#000"><table style="background:var(--card)">x</table></body>"""))
    }

    @Test fun `a neutral inner background is ignored`() {
        assertEquals(0x000000, declaredDarkPageBackground("""<body bgcolor="#000"><table style="background:none">x</table></body>"""))
        assertEquals(0x000000, declaredDarkPageBackground("""<body bgcolor="#000"><td style="background-color:transparent">x</td></body>"""))
    }

    @Test fun `a dark inner shorthand with an image and a position is read`() {
        assertEquals(
            0x000000,
            declaredDarkPageBackground("""<body bgcolor="#000"><td style="background:#111 url(x.png) no-repeat center top">x</td></body>"""),
        )
    }

    @Test fun `a white table on a dark page keeps the invert, as in a newsletter`() {
        assertNull(
            declaredDarkPageBackground(
                """<body bgcolor="#222222"><table bgcolor="#ffffff"><tr><td>news</td></tr></table></body>""",
            ),
        )
    }

    @Test fun `a white card set by a style rule keeps the invert`() {
        assertNull(declaredDarkPageBackground("<style>body{background:#000} .card{background-color:#fff}</style>x"))
    }

    @Test fun `a white card set in a style attribute keeps the invert`() {
        assertNull(declaredDarkPageBackground("""<body bgcolor="#000"><div style="background:white">x</div></body>"""))
    }

    @Test fun `an inner background just under the light threshold does not disqualify, just over it does`() {
        // #bbbbbb has a relative luminance of 0.4969, #bcbcbc of 0.5029.
        assertEquals(
            0x000000,
            declaredDarkPageBackground("""<body bgcolor="#000"><td bgcolor="#bbbbbb">x</td></body>"""),
        )
        assertNull(declaredDarkPageBackground("""<body bgcolor="#000"><td bgcolor="#bcbcbc">x</td></body>"""))
    }

    @Test fun `two document declarations that disagree are worth nothing`() {
        assertNull(declaredDarkPageBackground("""<style>body{background:#000}</style><body bgcolor="#111">x</body>"""))
    }

    @Test fun `two document declarations that agree still count`() {
        assertEquals(
            0x111111,
            declaredDarkPageBackground("""<style>body{background:#111}</style><body bgcolor="#111111">x</body>"""),
        )
    }

    // --- the document --------------------------------------------------------------------------

    private val dark = EmailTheme(background = "#101418", text = "#e3e3e3", link = "#a8c7fa", dark = true)
    private val light = EmailTheme(background = "#ffffff", text = "#111111", link = "#0b5fff", dark = false)

    private fun email(html: String): Email = Email(
        id = "1",
        htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
        bodyValues = mapOf("h" to EmailBodyValue(value = html)),
    )

    // Up to `</head>`, not `<body`: the inverted template's own comment says "<body>".
    private fun head(doc: String) = doc.substringBefore("</head>")

    @Test fun `in dark theme a dark page is shown as it is, on its own colour`() {
        val doc = buildHtmlDocument(
            email("""<body style="background-color:#1A1A1A"><p>Hi 😀</p></body>"""),
            theme = dark,
            noContent = "",
        )
        val head = head(doc)
        assertFalse("a dark page must not be inverted:\n$head", "invert(1)" in doc)
        assertTrue("the page must be painted in the colour it declared:\n$head", "html, body { background-color: #1a1a1a; }" in head)
        assertTrue("the bottom spacer must be opaque in the page colour (#124):\n$head", ".s-end { background: #1a1a1a; }" in head)
        assertTrue("text in the app's dark-theme colour:\n$head", "color: #e3e3e3" in head)
        assertTrue("links in the app's dark-theme colour:\n$head", "a { color: #a8c7fa; }" in head)
        assertTrue("rich HTML stays pinned to its light design:\n$head", "content=\"only light\"" in head)
        assertFalse("no emoji counter-filter outside the invert:\n$doc", "s-emo" in doc)
        assertTrue("the quote-fold rule stays in the template", "details.s-quote" in head)
    }

    @Test fun `the dark-mode defang and the quote fold still apply to a dark page`() {
        val doc = buildHtmlDocument(
            email(
                "<style>body{background:#000} @media (prefers-color-scheme: dark){p{color:red}}</style>" +
                    "<p>reply</p><blockquote>old</blockquote>",
            ),
            theme = dark,
            noContent = "",
            quoteLabel = "QUOTE",
        )
        assertFalse(doc, "invert(1)" in doc)
        assertTrue(doc, "prefers-color-scheme:dark) and (max-width:0px" in doc)
        assertTrue(doc, "<details class=\"s-quote\"" in doc)
    }

    @Test fun `in dark theme a dark page with a white table is still inverted`() {
        val doc = buildHtmlDocument(
            email("""<body bgcolor="#222222"><table bgcolor="#ffffff"><tr><td>news</td></tr></table></body>"""),
            theme = dark,
            noContent = "",
        )
        assertTrue(head(doc), "filter: invert(1) hue-rotate(180deg)" in head(doc))
        assertTrue(".s-end { background: transparent; }" in head(doc))
    }

    @Test fun `in light theme a dark page renders exactly as before`() {
        val html = """<body bgcolor="#000000"><p>Hi</p></body>"""
        val doc = buildHtmlDocument(email(html), theme = light, noContent = "")
        assertFalse("invert(1)" in doc)
        assertTrue(head(doc), "html, body { background-color: #ffffff; }" in head(doc))
        assertTrue(head(doc), "color: #111111" in head(doc))
    }

    @Test fun `the body's own text and link colours replace the theme's on a dark page, rewritten`() {
        val doc = buildHtmlDocument(
            email("""<body bgcolor="#000" text="#0F6" link="#ffcc00"><a href="x">x</a></body>"""),
            theme = dark,
            noContent = "",
        )
        val head = head(doc)
        assertFalse(head, "invert(1)" in head)
        assertTrue(head, "color: #00ff66;" in head)
        assertTrue(head, "a { color: #ffcc00; }" in head)
        assertFalse("the theme's text colour must give way:\n$head", "#e3e3e3" in head)
        assertFalse("the message's own spelling must not reach our style:\n$head", "#0F6" in head)
    }

    @Test fun `an unreadable body text colour is not copied, the theme's stays`() {
        val doc = buildHtmlDocument(
            email("""<body bgcolor="#000" text="red;}</style><script>x()</script>">x</body>"""),
            theme = dark,
            noContent = "",
        )
        val head = head(doc)
        assertFalse(head, "invert(1)" in head)
        assertFalse(head, "red;" in head)
        assertFalse(head, "<script>" in head)
        assertEquals(head, 1, Regex("</style>").findAll(head).count())
        assertTrue(head, "color: #e3e3e3" in head)
    }

    @Test fun `body text and link colours are read only from the body tag`() {
        assertEquals(BodyColours(0x00ff66, 0xffcc00), declaredBodyColours("""<body text="00ff66" link='#FC0'>x</body>"""))
        assertEquals(BodyColours(null, null), declaredBodyColours("""<body bgcolor="#000"><font text="#fff">x</font></body>"""))
        assertEquals(BodyColours(null, 0xffcc00), declaredBodyColours("""<body vlink="#fff" link="#ffcc00">x</body>"""))
    }

    @Test fun `a page colour is rewritten by us and nothing of the message's value reaches our style`() {
        val hostile = buildHtmlDocument(
            email("<style>body{background:#000;}</style><body style=\"background:#000;}</style><script>x()</script>\">x</body>"),
            theme = dark,
            noContent = "",
        )
        assertFalse(head(hostile), "<script>" in head(hostile))
        assertTrue(head(hostile), "background-color: #000000;" in head(hostile))

        val unreadable = buildHtmlDocument(
            email("<body bgcolor=\"#000;}</style><script>x()</script>\">x</body>"),
            theme = dark,
            noContent = "",
        )
        assertFalse(head(unreadable), "<script>" in head(unreadable))
        assertTrue("an unreadable colour keeps the invert", "invert(1)" in head(unreadable))
    }

    @Test fun `text we paint ourselves is never treated as a dark page`() {
        val e = Email(
            id = "1",
            textBody = listOf(EmailBodyPart(partId = "t", type = "text/plain")),
            htmlBody = listOf(EmailBodyPart(partId = "h", type = "text/html")),
            bodyValues = mapOf(
                "t" to EmailBodyValue(value = "plain"),
                "h" to EmailBodyValue(value = """<body bgcolor="#000000">x</body>"""),
            ),
        )
        val doc = buildHtmlDocument(e, theme = dark, plainText = true, derivedNotice = "", noContent = "")
        assertTrue(head(doc), "html, body { background-color: #101418; }" in head(doc))
        assertTrue(head(doc), "content=\"dark\"" in head(doc))
    }
}

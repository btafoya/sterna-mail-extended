package app.sterna.ui.message

import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The reading-mode decision (#149), EXECUTED. The reader's composables cannot be instantiated in a
 * JVM test, so the decision itself lives in [readerBody], [showRemoteImages] and
 * [buildHtmlDocument] — all `internal`, which is what lets this file run them — and is pinned here
 * against literal expected values, not against the rule re-implemented in the test, which would
 * stay green with the shipped condition inverted.
 */
class ReaderBodyTest {

    /** The line the reader puts above a flattened body, standing in for the localised string. */
    private val notice = "NOTICE"

    @Test
    fun `a genuine text part is what plain-text mode shows, and it is not called derived`() {
        val email = email(
            text = part("t", "text/plain") to "Bonjour\nligne deux",
            html = part("h", "text/html") to "<p>SOMETHING ELSE</p>",
        )

        val body = readerBody(email, plainText = true, derivedNotice = notice)

        assertEquals("<pre class=\"plain\">Bonjour\nligne deux</pre>", body.fragment)
        assertFalse("a real text/plain part must not be announced as derived", body.derived)
        assertFalse(body.richHtml)
        assertFalse("the HTML part must not leak into the text rendering", "SOMETHING ELSE" in body.fragment)
    }

    @Test
    fun `a textBody the server typed text_html is refused, and the HTML is flattened instead`() {
        // RFC 8621 §4.1.4: with no text/plain part a JMAP server fills textBody with the text/html
        // one. Escaping THAT into a <pre> would show the reader the markup as if it were the mail.
        val email = email(text = part("t", "text/html") to "<p>Hello <b>world</b></p>")

        val body = readerBody(email, plainText = true, derivedNotice = notice)

        assertEquals(
            "<p class=\"s-note\">NOTICE</p><pre class=\"plain\">Hello world</pre>",
            body.fragment,
        )
        assertTrue(body.derived)
        assertFalse(body.richHtml)
        assertFalse("no escaped markup may reach the reader", "&lt;" in body.fragment)
    }

    @Test
    fun `HTML-only mail is flattened with its paragraphs and table rows kept as line breaks`() {
        val email = email(
            html = part("h", "text/html") to
                "<p>One</p><table><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></table>",
        )

        val body = readerBody(email, plainText = true, derivedNotice = notice)

        assertEquals(
            "<p class=\"s-note\">NOTICE</p><pre class=\"plain\">One\nA B\nC D</pre>",
            body.fragment,
        )
        assertTrue(body.derived)
    }

    @Test
    fun `the derived line appears only on a derived body`() {
        val text = email(text = part("t", "text/plain") to "Just text")
        val html = email(html = part("h", "text/html") to "<p>Hi</p>")

        assertFalse(notice in readerBody(text, plainText = true, derivedNotice = notice).fragment)
        assertFalse(notice in readerBody(html, plainText = false, derivedNotice = notice).fragment)
        assertTrue(notice in readerBody(html, plainText = true, derivedNotice = notice).fragment)
        // An empty notice puts no empty paragraph in the document.
        assertEquals(
            "<pre class=\"plain\">Hi</pre>",
            readerBody(html, plainText = true, derivedNotice = "").fragment,
        )
    }

    @Test
    fun `a message that is already text renders identically in both modes`() {
        val email = email(text = part("t", "text/plain") to "Just text")

        assertEquals("<pre class=\"plain\">Just text</pre>", readerBody(email, false, notice).fragment)
        assertEquals("<pre class=\"plain\">Just text</pre>", readerBody(email, true, notice).fragment)
        assertFalse(readerBody(email, true, notice).derived)
    }

    @Test
    fun `text is escaped in both modes, so markup in a text body stays text`() {
        val email = email(text = part("t", "text/plain") to "a < b & c > d")

        assertEquals("<pre class=\"plain\">a &lt; b &amp; c &gt; d</pre>", readerBody(email, false, notice).fragment)
        assertEquals("<pre class=\"plain\">a &lt; b &amp; c &gt; d</pre>", readerBody(email, true, notice).fragment)
    }

    @Test
    fun `the rich-HTML flag says what is rendered, not what the message has`() {
        // It drives the dark-theme page invert. A message that HAS HTML but is being read as text
        // is painted in the app's own colours; inverting that page gives dark text on a light
        // band, which is the "white band in dark theme" bug from the other side.
        val email = email(html = part("h", "text/html") to "<p>Hi</p>")

        val asHtml = readerBody(email, plainText = false, derivedNotice = notice)
        assertEquals("<p>Hi</p>", asHtml.fragment)
        assertTrue(asHtml.richHtml)

        assertFalse(readerBody(email, plainText = true, derivedNotice = notice).richHtml)
    }

    @Test
    fun `a message with neither body falls back to its preview`() {
        val email = Email(id = "1", preview = "a < b")

        assertEquals("<p>a &lt; b</p>", readerBody(email, false, notice).fragment)
        assertEquals("<pre class=\"plain\">a &lt; b</pre>", readerBody(email, true, notice).fragment)
        assertFalse(readerBody(email, true, notice).derived)
    }

    @Test
    fun `remote images are refused in plain-text mode, whatever the rest says`() {
        assertFalse(showRemoteImages(plainText = true, manualShow = true, senderAllowed = true))
        assertFalse(showRemoteImages(plainText = true, manualShow = true, senderAllowed = false))
        assertFalse(showRemoteImages(plainText = true, manualShow = false, senderAllowed = true))
        assertFalse(showRemoteImages(plainText = true, manualShow = false, senderAllowed = false))
    }

    @Test
    fun `in HTML mode either the one-time override or the sender allowlist shows them`() {
        assertTrue(showRemoteImages(plainText = false, manualShow = true, senderAllowed = false))
        assertTrue(showRemoteImages(plainText = false, manualShow = false, senderAllowed = true))
        assertTrue(showRemoteImages(plainText = false, manualShow = true, senderAllowed = true))
        assertFalse(showRemoteImages(plainText = false, manualShow = false, senderAllowed = false))
    }

    // -- the setting and the per-message deviation, resolved (#149, volet B) ----------------------
    //
    // Every expectation below is a LITERAL. Recomputing `override ?: setting ?: something` here
    // would be a copy of the decision: the shipped condition could be inverted and this file would
    // follow it down. Both functions are executed; nothing reads their source.

    // The two halves of the loading window, and they answer differently ON PURPOSE — so they are
    // two test methods. In one, the first assertion to fail hides the second, and the two failures
    // are not the same defect: one is a flash of text on every swipe, the other is a pixel fired.

    @Test
    fun `a setting not yet read renders the default`() {
        assertFalse(
            "while DataStore has not answered, the body must render what the app has always shown. " +
                "The reader builds one view model PER PAGE of the pager, so reading this null as " +
                "'text' flashes plain text on every message swiped past, for everyone who has the " +
                "switch off",
            plainTextForBody(override = null, setting = null),
        )
    }

    @Test
    fun `a setting not yet read still cuts the remote images`() {
        assertTrue(
            "the same null must count as TEXT for the image question: nothing orders the allowlist " +
                "read against this one, and a single frame where the sender is already allowed and " +
                "the setting is not yet known fetches the image. A frame of HTML is a frame; a " +
                "tracking pixel does not come back",
            plainTextForImages(override = null, setting = null),
        )
        // Said end to end, through the decision the WebView is actually handed.
        assertFalse(
            "an allowed sender must not get their images while the reading mode is unknown",
            showRemoteImages(
                plainTextForImages(override = null, setting = null),
                manualShow = false,
                senderAllowed = true,
            ),
        )
    }

    @Test
    fun `with nothing chosen for this message, both answers follow the stored setting`() {
        assertTrue(plainTextForBody(override = null, setting = true))
        assertFalse(plainTextForBody(override = null, setting = false))
        assertTrue(plainTextForImages(override = null, setting = true))
        assertFalse(plainTextForImages(override = null, setting = false))
        // And the setting alone is enough to cut the images of an allowed sender.
        assertFalse(
            showRemoteImages(
                plainTextForImages(override = null, setting = true),
                manualShow = true,
                senderAllowed = true,
            ),
        )
    }

    @Test
    fun `the menu's answer is a deviation, so it also comes back DOWN from the setting`() {
        // The entry reads "Show HTML" on a message the setting opened as text. An `override ||
        // setting` cannot deliver that: the label would promise a rendering that never arrives.
        assertFalse(
            "'Show HTML' on a message opened as text must actually render the HTML",
            plainTextForBody(override = false, setting = true),
        )
        assertFalse(
            "and the images must be decided against the mode the reader is looking at",
            plainTextForImages(override = false, setting = true),
        )
        assertTrue(plainTextForBody(override = true, setting = false))
        assertTrue(plainTextForImages(override = true, setting = false))
        // A deviation is a gesture just made on THIS message; it wins over an unread setting too.
        assertFalse(plainTextForBody(override = false, setting = null))
        assertTrue(plainTextForBody(override = true, setting = null))
        assertFalse(plainTextForImages(override = false, setting = null))
        assertTrue(plainTextForImages(override = true, setting = null))
    }

    // -- is the reading-mode entry worth offering at all? (#149, volet B) -------------------------
    //
    // The entry is a promise that the screen will change. On a mailing-list post or a git
    // notification — mail with no text/html part at all, i.e. the mail of the very people who turn
    // the setting on — both modes build the SAME <pre>, and "Show HTML" changed nothing but its own
    // label. Every case below EXECUTES the predicate against a literal message; none of them
    // recomputes the rule.

    @Test
    fun `a message carrying both a text part and an HTML part is worth switching`() {
        val email = email(
            text = part("t", "text/plain") to "Bonjour",
            html = part("h", "text/html") to "<p>Bonjour <b>en gras</b></p>",
        )

        assertTrue(readingModesDiffer(email, notice))
    }

    @Test
    fun `an HTML-only message is worth switching`() {
        val email = email(html = part("h", "text/html") to "<p>Hello <b>world</b></p>")

        assertTrue(readingModesDiffer(email, notice))
    }

    @Test
    fun `a text-only message is NOT — the entry would be a dead action`() {
        val email = email(text = part("t", "text/plain") to "Bonjour\nligne deux")

        assertFalse(
            "with no HTML part the two modes build the same <pre>: offering 'Show HTML' there is " +
                "an entry that changes nothing but its own label",
            readingModesDiffer(email, notice),
        )
        // Said the other way round: the entry stands down because the documents are equal.
        assertEquals(
            readerBody(email, plainText = false, derivedNotice = notice).fragment,
            readerBody(email, plainText = true, derivedNotice = notice).fragment,
        )
    }

    @Test
    fun `a textBody the server typed text_html IS worth switching, though htmlContent is null`() {
        // ⭐ The case that kills the naive predicate "the message has a text/html part". Over JMAP
        // (RFC 8621 §4.1.4) a server with no text/plain part fills textBody with the text/html one:
        // htmlBody is empty, htmlContent() is null, and yet the two modes differ sharply — HTML
        // mode escapes the markup into a <pre>, text mode refuses the type and flattens it.
        val email = email(text = part("t", "text/html") to "<p>Hello <b>world</b></p>")

        assertTrue(
            "hiding the entry here would remove the toggle exactly where it is most useful: the " +
                "reader is being shown raw markup",
            readingModesDiffer(email, notice),
        )
    }

    @Test
    fun `a message with no body at all is NOT worth switching`() {
        // Both modes show the same preview; only the element WE wrap it in differs, and a wrapper
        // we chose is not a change the reader asked for.
        assertFalse(readingModesDiffer(Email(id = "1", preview = "a < b"), notice))
        assertFalse(readingModesDiffer(Email(id = "1"), notice))
    }

    @Test
    fun `an empty text part IS worth switching, because the preview takes its place`() {
        // Neither mode carries markup here, so the flags agree — what differs is the TEXT: HTML
        // mode renders the empty part, plain-text mode refuses a blank body and falls to the
        // preview. Switching really does put something else on the screen.
        val email = Email(
            id = "1",
            textBody = listOf(part("t", "text/plain")),
            bodyValues = mapOf("t" to EmailBodyValue(value = "")),
            preview = "Something",
        )

        assertTrue(readingModesDiffer(email, notice))
    }

    @Test
    fun `the dark-theme page invert follows the rendered body, not what the message has`() {
        val email = email(html = part("h", "text/html") to "<p>Hi</p>")
        val dark = EmailTheme(background = "#101010", text = "#eeeeee", link = "#8ab4f8", dark = true)

        val asHtml = buildHtmlDocument(email, theme = dark)
        assertTrue(
            "rich HTML in dark theme is rendered light and the whole page inverted",
            "invert(1)" in asHtml,
        )

        val asText = buildHtmlDocument(email, theme = dark, plainText = true, derivedNotice = notice)
        assertFalse(
            "text we paint in the app's own colours must NOT be inverted — that is the white band",
            "invert(1)" in asText,
        )
        assertTrue("the text is painted with the app's dark surface", "background-color: #101010" in asText)
        assertTrue("the derived line reaches the document", notice in asText)
    }

    @Test
    fun `the colour scheme of a text rendering follows the app, not the message's HTML`() {
        val email = email(html = part("h", "text/html") to "<p>Hi</p>")
        val light = EmailTheme(background = "#ffffff", text = "#111111", link = "#0b5fff", dark = false)

        // Rich HTML is pinned to its light design; text we paint follows the app's theme.
        assertTrue("content=\"only light\"" in buildHtmlDocument(email, theme = light))
        assertTrue(
            "content=\"light\"" in
                buildHtmlDocument(email, theme = light, plainText = true, derivedNotice = ""),
        )
    }

    @Test
    fun `plain-text mode loosens none of the document's guards`() {
        val email = email(html = part("h", "text/html") to "<p>Hi</p>")
        val theme = EmailTheme(background = "#ffffff", text = "#111111", link = "#0b5fff", dark = false)

        val doc = buildHtmlDocument(email, theme = theme, plainText = true, derivedNotice = notice)
        assertTrue("the CSP must be on the text document too", "default-src 'none'" in doc)
        assertTrue("frame-src 'none'" in doc)
        assertTrue("object-src 'none'" in doc)
    }

    // buildHtmlDocument defangs the message's own `prefers-color-scheme: dark` and inlines its cid:
    // images. Both undo MARKUP, and HTML-escaping touches neither ':' nor '-', so applied to a body
    // we painted ourselves they mutilate the reader's own text — in the mode whose whole promise is
    // to show the text as it is. One test per rewrite: they share a single guard in the shipped
    // code, and a single test would only ever show whichever assertion comes first.
    private val light = EmailTheme(background = "#ffffff", text = "#111111", link = "#0b5fff", dark = false)
    private val images = mapOf("logo" to "data:image/png;base64,AAAA")

    @Test
    fun `a dark-mode media query in the message TEXT reaches the reader untouched`() {
        val css = "@media (prefers-color-scheme: dark) { body { background: #000 } }"

        val asText = buildHtmlDocument(
            email(text = part("t", "text/plain") to css),
            theme = light, plainText = true, derivedNotice = notice,
        )
        assertTrue("the reader's own text was rewritten: $asText", css in asText)
        assertFalse("max-width:0px" in asText)

        // The same declaration inside the MESSAGE's <style> is still defanged: that document does
        // carry markup, and on a dark device the WebView would otherwise pick the email's dark
        // variant and our invert would turn it light again.
        val asHtml = buildHtmlDocument(
            email(html = part("h", "text/html") to "<style>@media (prefers-color-scheme: dark){body{background:#000}}</style>"),
            theme = light,
        )
        assertTrue("prefers-color-scheme:dark) and (max-width:0px" in asHtml)
    }

    @Test
    fun `a cid reference in the message TEXT stays text and is not turned into an image`() {
        val asText = buildHtmlDocument(
            email(text = part("t", "text/plain") to "the logo lives at cid:logo, ask me"),
            inlineImages = images, theme = light, plainText = true, derivedNotice = notice,
        )
        assertTrue("the reader's own text was rewritten: $asText", "at cid:logo, ask" in asText)
        assertFalse("data:image/png" in asText)

        // In the message's own HTML the reference is markup, and is still inlined.
        val asHtml = buildHtmlDocument(
            email(html = part("h", "text/html") to "<img src=\"cid:logo\">"),
            inlineImages = images, theme = light,
        )
        assertTrue("<img src=\"data:image/png;base64,AAAA\">" in asHtml)
    }

    /**
     * SOURCE CHECK, and the weakest thing in this file. The reader decides the remote-image
     * question TWICE — once at the pager level for the overflow menu, once at the page level for
     * the WebView — and neither site can be reached from a JVM test. What is pinned here is not
     * that a call exists but the ARGUMENTS both sites pass: a site that kept computing its own
     * `manualShow || senderAllowed` would leave a menu offering "Show images" over a body that has
     * none. The decision itself is executed by the two tests above.
     *
     * The pattern matches the WHOLE line, on purpose. Unanchored, it stayed green under
     * `val showRemote = showRemoteImages(plainText, manualShow, senderAllowed) || true`, which makes
     * `blockRemote` always false: remote images fetched for every message and every sender, plain
     * text included, so the tracking pixels fire on open and the per-sender allowlist decides
     * nothing. A source check blind to anything APPENDED to the line it reads is the recurring
     * defect of this repo.
     */
    @Test
    fun `both remote-image sites hand the same three terms to the same decision`() {
        val source = File(repoRoot(), "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

        val calls = Regex(
            """^[ \t]*val showRemote = showRemoteImages\(imageMode, manualShow, senderAllowed\)[ \t]*$""",
            RegexOption.MULTILINE,
        ).findAll(source).count()
        assertEquals("the two showRemote sites must both call showRemoteImages(imageMode, manualShow, senderAllowed)", 2, calls)
        // `imageMode` is the reading mode taken for the IMAGE question, and it is not the one the
        // body is rendered with while the setting is still being read. Handing `plainText` here
        // compiles and is right nine frames out of ten — the tenth is the tracking pixel. The
        // whole-line rule that pins `imageMode`'s own definition is in UnloadedSettingDefaultsTest.
        assertFalse(
            "a site decides the image question against the RENDERED mode instead of the image one",
            Regex("""showRemoteImages\(plainText,""").containsMatchIn(source),
        )
        assertFalse(
            "a site still computes the remote-image verdict itself",
            Regex("""showRemote\s*=\s*manualShow""").containsMatchIn(source),
        )
    }

    /**
     * SOURCE CHECKS, and the weakest things in this file after the one above: both guards are `if`s
     * inside the overflow menu, a Composable no JVM test can instantiate. What is pinned is the
     * ARGUMENTS and the VARIABLE each guard reads, not that a call exists — the decision behind the
     * first is EXECUTED by the six tests above.
     *
     * Two methods, not one, because the two failures are not the same defect: a menu entry that
     * does nothing, and a menu that offers "Show images" and "Stop showing images from this sender"
     * at the same time. In one method the first assertion to fail hides the other defect entirely.
     *
     * Every pattern matches the WHOLE line: unanchored, `if (!imageMode) {` stays green under
     * `if (!imageMode || true) {`, which is this very defect written the other way round.
     */
    @Test
    fun `the menu offers the reading-mode entry only where the two modes differ`() {
        val source = messageScreenSource()

        assertEquals(
            "the reading-mode entry must be offered only when the two renderings of THIS message " +
                "differ, and the verdict computed from the message itself, not from a label",
            1,
            lines(source, """val readingModeUseful = remember\(loaded\.email, derivedNotice\) \{"""),
        )
        assertEquals(
            "the menu must call readingModesDiffer(loaded.email, derivedNotice) — the message the " +
                "toolbar has settled on, and the notice the page actually renders",
            1,
            lines(source, """readingModesDiffer\(loaded\.email, derivedNotice\)"""),
        )
        assertEquals(
            "the entry AND the divider above it hang on that verdict",
            1,
            lines(source, """if \(readingModeUseful\) \{"""),
        )
    }

    @Test
    fun `the image entries are guarded on the same mode showRemote is decided on`() {
        val source = messageScreenSource()

        assertEquals(
            "the image entries must be guarded on imageMode, the variable showRemote is decided " +
                "on: they ask showRemote's question, so they must get showRemote's answer",
            1,
            lines(source, """if \(!imageMode\) \{"""),
        )
        assertFalse(
            "a guard in the menu still reads the RENDERED mode where the image mode is meant — " +
                "the two differ while DataStore has not answered, and there the menu offers " +
                "'Show images' and 'Stop showing images from this sender' side by side",
            Regex("""^[ \t]*if \(!plainText\) \{[ \t]*$""", RegexOption.MULTILINE).containsMatchIn(source),
        )
    }

    private fun messageScreenSource(): String =
        File(repoRoot(), "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readText()

    private fun lines(source: String, pattern: String): Int =
        Regex("^[ \t]*$pattern[ \t]*$", RegexOption.MULTILINE).findAll(source).count()

    private fun part(id: String, type: String) = EmailBodyPart(partId = id, type = type)

    private fun email(
        text: Pair<EmailBodyPart, String>? = null,
        html: Pair<EmailBodyPart, String>? = null,
    ): Email = Email(
        id = "1",
        textBody = listOfNotNull(text?.first),
        htmlBody = listOfNotNull(html?.first),
        bodyValues = listOfNotNull(text, html)
            .associate { (p, v) -> p.partId!! to EmailBodyValue(value = v) },
    )

    private fun repoRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").isFile }
            ?: error("cannot locate the repo root from ${File("").absolutePath}")
}

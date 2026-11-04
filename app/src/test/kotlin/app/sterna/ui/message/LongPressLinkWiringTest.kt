package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: a `BodyWebView` cannot be built in a JVM test, so the long press
 * on a link (#287) is pinned by reading `MessageScreen.kt` as text. What it decides is executed by
 * `OpenableLinkTargetTest`; this only proves both ways into a link reach that one decision.
 *
 * Holes of CONTRIBUTING.md that apply: 2, `if (isAttachedToWindow)` is found as text, whatever
 * expression surrounds it; 3, only the order of the tap's filter and `hasGesture` is checked, not the
 * `when` branch a pinned line sits in beyond the regex, nor when the asynchronous href reply runs;
 * 4, nothing here holds `pendingLink` as the dialog collects it. Hole 1 does not: `stripTracking` is
 * the client's own field, read at the press.
 */
class LongPressLinkWiringTest {

    private val flat by lazy { code(MESSAGE_SCREEN).replace(Regex("""\s+"""), " ") }

    private fun between(start: String, end: String): String {
        val from = flat.indexOf(start)
        assertTrue("cannot find `$start` in MessageScreen.kt", from >= 0)
        val to = flat.indexOf(end, from)
        assertTrue("cannot find `$end` after `$start` in MessageScreen.kt", to >= 0)
        return flat.substring(from, to)
    }

    private val factory by lazy { between("factory = { ctx -> val webView = BodyWebView(ctx)", "FrameLayout(ctx).apply") }

    @Test fun `the long press listener is set on the body WebView inside the factory`() {
        assertTrue(
            "EmailWebView's factory must set the long-click listener on the BodyWebView itself, " +
                "before it is wrapped in the FrameLayout that guards #10",
            factory.contains("setOnLongClickListener {"),
        )
        assertTrue("the listener must read the WebView's hitTestResult", factory.contains("hitTestResult"))
    }

    @Test fun `a plain link takes extra, a linked image takes the href, anything else keeps the default`() {
        assertTrue(
            "SRC_ANCHOR_TYPE: the address is hitTestResult.extra, through client.openableTarget",
            Regex("""WebView\.HitTestResult\.SRC_ANCHOR_TYPE -> \{ val target = hit\.extra\?\.let \{ client\.openableTarget\(Uri\.parse\(it\)\) \} \?: return@setOnLongClickListener false client\.onLongPressLink\(target\) true \}""")
                .containsMatchIn(factory),
        )
        assertTrue(
            "SRC_IMAGE_ANCHOR_TYPE, EMAIL_TYPE, PHONE_TYPE, GEO_TYPE: extra is the IMAGE address or " +
                "the address without its scheme, the link's href must be asked for with " +
                "requestFocusNodeHref and read from the \"url\" key",
            Regex("""WebView\.HitTestResult\.SRC_IMAGE_ANCHOR_TYPE, WebView\.HitTestResult\.EMAIL_TYPE, WebView\.HitTestResult\.PHONE_TYPE, WebView\.HitTestResult\.GEO_TYPE, -> \{.*requestFocusNodeHref\(.*\).*true \}""")
                .containsMatchIn(factory) &&
                Regex("""msg\.data\.getString\("url"\)\?\.let \{ client\.openableTarget\(Uri\.parse\(it\)\) \}\?\.let \{ client\.onLongPressLink\(it\) \}""")
                    .containsMatchIn(factory),
        )
        assertTrue(
            "the asynchronous href reply must be dropped once the body has left the window",
            factory.contains("if (isAttachedToWindow)"),
        )
        assertTrue("any other hit type must return false, keeping text selection", factory.contains("else -> false"))
    }

    @Test fun `extra is read for a plain link only`() {
        assertEquals(
            "hit.extra may be read in the SRC_ANCHOR_TYPE branch alone: for an image, mailto:, tel: " +
                "or geo: link it is not the link's address",
            1,
            Regex("""hit\.extra""").findAll(factory).count(),
        )
    }

    @Test fun `the long press shows the dialog whatever confirmLinks says`() {
        assertEquals(
            "client.onLongPressLink must set pendingLink, unconditionally — a long press never opens",
            1,
            Regex(Regex.escape("client.onLongPressLink = { uri -> pendingLink = uri }")).findAll(flat).count(),
        )
    }

    @Test fun `the safe schemes are declared once and only the shared filter reads them`() {
        assertEquals(1, Regex("""SAFE_OPEN_SCHEMES =""").findAll(flat).count())
        assertEquals(
            "SAFE_OPEN_SCHEMES must be read by openableLinkTarget alone",
            2,
            Regex("""\bSAFE_OPEN_SCHEMES\b""").findAll(flat).count(),
        )
        assertEquals(
            "LinkCleaner.strip must be called by openableLinkTarget alone",
            1,
            Regex("""LinkCleaner\.strip\(""").findAll(flat).count(),
        )
    }

    @Test fun `the brief tap goes through the same filter, its gesture guard kept`() {
        val tap = between("override fun shouldOverrideUrlLoading(", "private companion object")
        assertTrue(
            "shouldOverrideUrlLoading must filter through openableTarget(url) and swallow a refused link",
            tap.contains("val target = openableTarget(url) ?: return true"),
        )
        assertFalse("shouldOverrideUrlLoading must not read the schemes itself", tap.contains("SAFE_OPEN_SCHEMES"))
        assertTrue(
            "the hasGesture guard stays on the brief tap, after the scheme filter",
            tap.indexOf("openableTarget(url)") < tap.indexOf("if (!request.hasGesture()) return true") &&
                tap.indexOf("if (!request.hasGesture()) return true") < tap.indexOf("onOpenUrl(target)"),
        )
        assertTrue(
            "openableTarget must hand the client's live stripTracking to the shared filter",
            flat.contains("fun openableTarget(url: Uri): Uri? = openableLinkTarget(url.toString(), stripTracking)"),
        )
    }

    private fun code(file: File): String = file.readLines().mapNotNull { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) null
        else withoutTrailingComment(line).takeIf { it.isNotBlank() }
    }.joinToString("\n")

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
        return line
    }

    companion object {
        private const val MESSAGE_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, MESSAGE_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val MESSAGE_SCREEN: File by lazy { File(root, MESSAGE_SCREEN_PATH) }
    }
}

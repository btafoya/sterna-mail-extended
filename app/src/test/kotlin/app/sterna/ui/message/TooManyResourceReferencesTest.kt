package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [asksForTooManyResources] executed on the documents the reader is handed: a message past the limit
 * is judged before any WebView sees it, whatever the scheme of its references.
 */
class TooManyResourceReferencesTest {

    @Test fun `two hundred thousand references to one inline image are too many`() {
        assertTrue(asksForTooManyResources("<p>" + "<img src=\"cid:a\">".repeat(200_000) + "</p>"))
    }

    @Test fun `two hundred thousand remote images are too many`() {
        assertTrue(
            "a remote reference costs the document as much as a cid one",
            asksForTooManyResources("<p>" + "<img src=\"https://example.test/p.png\">".repeat(200_000) + "</p>"),
        )
    }

    @Test fun `forty thousand inline references are too many`() {
        assertTrue(asksForTooManyResources("<img src=\"cid:a\">".repeat(40_000)))
    }

    @Test fun `fifty inline references are not`() {
        assertFalse(asksForTooManyResources("<img src=\"cid:a\">".repeat(50)))
    }

    @Test fun `an ordinary message is not`() {
        val html = "<html><body><p>Bonjour, voici les deux photos.</p>" +
            "<img src=\"cid:rouge\" alt=\"rouge\"><img src=\"cid:<bleu>\"></body></html>"
        assertFalse(asksForTooManyResources(html))
    }

    @Test fun `exactly the limit is not too many, one more is`() {
        val ref = "<img src=\"cid:a\">"
        assertFalse(
            "$MAX_RESOURCE_REQUESTS_PER_DOCUMENT references is the limit itself",
            asksForTooManyResources(ref.repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT)),
        )
        assertTrue(
            "${MAX_RESOURCE_REQUESTS_PER_DOCUMENT + 1} references is past it",
            asksForTooManyResources(ref.repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT + 1)),
        )
    }

    @Test fun `every kind of reference counts`() {
        val mixed = listOf(
            "<img srcset=\"cid:a 1x\">",
            "<td background=\"cid:a\"></td>",
            "<video poster=\"https://example.test/p.png\"></video>",
            "<div style=\"background:url(x)\"></div>",
            "<IMG SRC = 'cid:a'>",
            "<img/src=\"cid:a\">",
            "<img alt=\"x\"src=\"cid:a\">",
            "<div style=\"background:URL(x)\"></div>",
            "<svg><image href=\"cid:a\"/></svg>",
            "<svg><image xlink:href=\"cid:a\"/></svg>",
        )
        mixed.forEach { ref ->
            val n = MAX_RESOURCE_REQUESTS_PER_DOCUMENT / 4
            val html = ref.repeat(n) + "<img src=\"cid:a\">".repeat(MAX_RESOURCE_REQUESTS_PER_DOCUMENT - n)
            assertFalse("$ref: ${MAX_RESOURCE_REQUESTS_PER_DOCUMENT} references in all", asksForTooManyResources(html))
            assertTrue("$ref must count as one reference", asksForTooManyResources(html + ref))
        }
    }

    @Test fun `a lazy placeholder attribute does not count`() {
        assertFalse(asksForTooManyResources("<img data-src=\"cid:a\">".repeat(5_000)))
        assertFalse(asksForTooManyResources("<img data-src=\"x\">".repeat(5_000)))
        assertFalse(asksForTooManyResources("<image data-href=\"cid:a\"/>".repeat(5_000)))
    }

    @Test fun `four megabytes under the limit are not too many`() {
        val text = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(80)
        val html = (text + "<img src=\"cid:a\">").repeat(900)
        assertTrue("the document must weigh about 4 MB, got ${html.length} chars", html.length > 4_000_000)
        assertFalse(asksForTooManyResources(html))
    }

    @Test fun `a verdict already kept is never given again, nor taken back`() {
        val monster = "<img src=\"cid:a\">".repeat(200_000)
        assertFalse("a kept verdict stands: nothing to give", givesResourceVerdict(verdictKept = true, html = monster))
        assertTrue(givesResourceVerdict(verdictKept = false, html = monster))
        assertFalse(givesResourceVerdict(verdictKept = false, html = "<p>Bonjour</p><img src=\"cid:rouge\">"))
    }

    @Test fun `no document is not too many`() {
        assertFalse(asksForTooManyResources(null))
        assertFalse(asksForTooManyResources(""))
    }

    /**
     * Source rule, the ViewModel not being instantiable here: every body reaches `_messages` through
     * `publishBody`, which judges it on a worker thread and gives the verdict before publishing.
     */
    @Test fun `every body is judged before it is published`() {
        val source = viewModelSource()
        val published = source.lines().map { it.trim() }.filter { it.startsWith("_messages.value = listOf(") }
        assertEquals(
            "only the header-only repaint and publishBody may put a message in `_messages`",
            listOf(
                "_messages.value = listOf(ThreadMessage(id = cached.id, header = cached))",
                "_messages.value = listOf(message)",
            ),
            published.sorted(),
        )

        val start = source.indexOf("private suspend fun publishBody(")
        assertTrue("MessageViewModel has no publishBody any more", start > 0)
        val body = source.substring(start, source.indexOf("\n    }\n", start))
        val worker = body.indexOf("withContext(Dispatchers.Default) {")
        val judged = body.indexOf(
            "givesResourceVerdict(keepsResourceVerdict(tooManyResourcesFor, emailId, accountId), message.body?.htmlContent())",
        )
        val given = body.indexOf("if (tooMany) gaveUpOnResources(emailId, accountId)")
        val shown = body.indexOf("_messages.value = listOf(message)")
        assertTrue("publishBody must judge off the main thread", worker > 0)
        assertTrue(
            "publishBody must judge the message's own HTML, against the verdict kept for (emailId, accountId)",
            judged > worker,
        )
        assertTrue("publishBody must give the verdict it judged, for (emailId, accountId)", given > judged)
        assertTrue("the verdict must be given before the body is published", shown > given)

        assertEquals(
            "warm-up, open and decrypt each publish their body through publishBody, for their own message",
            3,
            Regex("""publishBody\(emailId, accountId, message\)""").findAll(source).count(),
        )
    }

    private fun viewModelSource(): String =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt") }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("cannot locate MessageViewModel.kt from ${File("").absolutePath}")
}

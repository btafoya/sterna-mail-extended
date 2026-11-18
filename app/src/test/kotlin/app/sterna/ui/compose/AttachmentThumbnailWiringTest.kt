package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rule on the shipped source. `AttachmentThumbnailsTest` executes the key decision and the silent
 * failure; `ComposeViewModel` needs an Application, so nothing but this pins that the composer asks
 * them, stores under the key it was given, and that the thumbnail never reaches the disk.
 */
class AttachmentThumbnailWiringTest {

    @Test fun `every staged part is offered to the thumbnail decoder from the bytes already in memory`() {
        val body = functionBody(viewModelLines(), "private suspend fun stageOutgoing(")
        assertEquals(
            "stageOutgoing must hand the part and its in-memory bytes to rememberThumbnail, once, " +
                "after the part exists. Lines found:",
            listOf("rememberThumbnail(part) { px -> decodeThumbnail(bytes, px) }"),
            body.filter { "rememberThumbnail(" in it || "decodeThumbnail(" in it },
        )
        assertEquals(
            "…and still return the part it staged, decoded or not. Lines found:",
            "return part",
            body.last { it.startsWith("return") },
        )
    }

    @Test fun `the thumbnail is stored under the key the decision gave, and only for an image`() {
        val body = functionBody(viewModelLines(), "private fun rememberThumbnail(")
        assertEquals(
            "the key must come from thumbnailKeyFor on the part and the kind attachmentKind gives it, " +
                "as the attachment row's own look does. Lines found:",
            listOf(
                "val kind = attachmentKind(part.type, part.name, MimeTypeMap.getSingleton()::getMimeTypeFromExtension)",
                "val key = thumbnailKeyFor(part, kind) ?: return",
            ),
            body.filter { it.startsWith("val kind") || it.startsWith("val key") },
        )
        assertEquals(
            "the decoded image must be stored under that key and no other. Lines found:",
            listOf("thumbnailCache.put(key, image)"),
            body.filter { ".put(" in it },
        )
        assertTrue(
            "the decode must run off the main thread: $body",
            body.any { "withContext(Dispatchers.Default)" in it && "decode(" in it },
        )
    }

    @Test fun `the screen reads the thumbnail by the part's own key`() {
        val screen = codeLines(locate("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt").readText())
        assertEquals(
            "Lines found:",
            listOf("val thumbnail = thumbnails[attachmentKey(att)]"),
            screen.filter { "thumbnails[" in it },
        )
    }

    @Test fun `a reopened queued row decodes only a file already on the phone`() {
        val bind = functionBody(viewModelLines(), "private fun bindQueuedRow(")
        assertEquals(
            "Lines found:",
            listOf("rememberStagedThumbnails(d.attachments)"),
            bind.filter { "Thumbnail" in it },
        )
        val staged = functionBody(viewModelLines(), "private fun rememberStagedThumbnails(")
        assertEquals(
            "the file must come from stagedThumbnailSource, off the main thread. Lines found:",
            listOf("val file = withContext(Dispatchers.IO) { stagedThumbnailSource(part, roots) } ?: return@launch"),
            staged.filter { "val file" in it },
        )
        assertEquals(
            "only the Staged outcome may be decoded, from its file; no other branch, no download. Lines found:",
            listOf("rememberThumbnail(part) { px -> decodeThumbnail(file, px) }"),
            staged.filter { "rememberThumbnail(" in it },
        )
        assertEquals(
            "Lines found:",
            emptyList<String>(),
            staged.filter { "download" in it.lowercase() || "repo." in it },
        )
    }

    @Test fun `removing a part lets its thumbnail go`() {
        val body = functionBody(viewModelLines(), "fun removeAttachment(")
        assertTrue("removeAttachment must forget the thumbnail: $body", body.any { "forgetThumbnail(part)" == it })
    }

    @Test fun `the decoder keeps thumbnails in memory only`() {
        val decoder = codeLines(locate("app/src/main/kotlin/app/sterna/ui/AttachmentThumbnails.kt").readText())
        assertEquals(
            "a thumbnail of a decrypted attachment must never be written anywhere. Lines found:",
            emptyList<String>(),
            decoder.filter {
                "cacheAttachment" in it || "writeBytes" in it || "FileOutputStream" in it ||
                    "handAttachmentToViewer" in it || ".compress(" in it || "openOutputStream" in it
            },
        )
        val rememberer = functionBody(viewModelLines(), "private fun rememberThumbnail(")
        assertEquals(
            "Lines found:",
            emptyList<String>(),
            rememberer.filter { "cacheAttachment" in it || "storage." in it || "write" in it.lowercase() },
        )
    }

    @Test fun `a stored or forgotten thumbnail is published to the screen right away`() {
        val remember = functionBody(viewModelLines(), "private fun rememberThumbnail(")
        val put = remember.indexOf("thumbnailCache.put(key, image)")
        assertTrue("rememberThumbnail must store the image: $remember", put >= 0)
        assertEquals(
            "the store must be followed by publishing the cache, or the row never sees the preview",
            "_attachmentThumbnails.value = thumbnailCache.snapshot()",
            remember[put + 1],
        )
        val forget = functionBody(viewModelLines(), "private fun forgetThumbnail(")
        val remove = forget.indexOf("thumbnailCache.remove(key)")
        assertTrue("forgetThumbnail must drop the image: $forget", remove >= 0)
        assertEquals("_attachmentThumbnails.value = thumbnailCache.snapshot()", forget[remove + 1])
    }

    @Test fun `a part is forgotten only once it is off the list`() {
        assertEquals(
            "forgetThumbnail asks whether another row still shares the key, so it must run after the " +
                "filter: before it, the part itself is still on the list and nothing is ever forgotten",
            listOf(
                "_attachments.value = _attachments.value.filterNot { it == part }",
                "_attachmentsTouched.value = true",
                "forgetThumbnail(part)",
            ),
            functionBody(viewModelLines(), "fun removeAttachment(").take(3),
        )
    }

    @Test fun `a draft kept on this phone reopens with its previews`() {
        val body = functionBody(viewModelLines(), "private suspend fun prepareLocalDraft(")
        val bound = body.indexOf("_attachments.value = fields.attachments")
        assertTrue("prepareLocalDraft must still bind the draft's attachments: $body", bound >= 0)
        assertEquals(
            "…and decode the staged ones right after, from their files",
            "rememberStagedThumbnails(fields.attachments)",
            body[bound + 1],
        )
    }

    @Test fun `the row draws the preview when there is one`() {
        val row = attachmentRowLines()
        assertEquals(
            "Lines found:",
            listOf("val thumbnail = thumbnails[attachmentKey(att)]", "if (thumbnail != null) {", "thumbnail,"),
            row.filter { "thumbnail" in it },
        )
        val branch = row.indexOf("if (thumbnail != null) {")
        assertEquals("Image(", row[branch + 1])
        assertTrue("the icon must stay the fallback: $row", "Icon(look.icon, contentDescription = null)" in row)
    }

    @Test fun `the decoder subsamples, turns upright and stays bounded`() {
        val decoder = codeLines(locate("app/src/main/kotlin/app/sterna/ui/AttachmentThumbnails.kt").readText())
        assertEquals(
            "Lines found:",
            listOf("inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxPx)"),
            decoder.filter { "inSampleSize" in it },
        )
        assertEquals(
            "every source must read the EXIF orientation of the same bytes. Lines found:",
            listOf(
                "val orientation = exifOrientation { ExifInterface(ByteArrayInputStream(bytes)) }",
                "val orientation = exifOrientation { ExifInterface(file.path) }",
                "val orientation = exifOrientation { checkNotNull(open()).use { ExifInterface(it) } }",
            ),
            decoder.filter { "val orientation" in it },
        )
        assertEquals(
            "Lines found:",
            listOf("return oriented(centerSquare(decoded, maxPx), exifTransform(orientation)).asImageBitmap()"),
            decoder.filter { "oriented(centerSquare" in it },
        )
        assertEquals(
            "the cache must be sized by the pinned constant. Lines found:",
            listOf("object : LruCache<String, ImageBitmap>(THUMBNAIL_CACHE_BYTES) {"),
            decoder.filter { "LruCache<String, ImageBitmap>(" in it },
        )
    }

    private fun attachmentRowLines(): List<String> {
        val lines = codeLines(locate("app/src/main/kotlin/app/sterna/ui/compose/ComposeScreen.kt").readText())
        val start = lines.indexOf("attachments.forEach { att ->")
        assertTrue("the attachment list must still be drawn by attachments.forEach", start >= 0)
        val end = lines.drop(start).indexOfFirst { it.startsWith("attachmentStatus?.let") }
        assertTrue("attachmentStatus must still follow the attachment list", end > 0)
        return lines.subList(start + 1, start + end)
    }

    private fun viewModelLines(): List<String> =
        locate("app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt").readText().lines()

    private fun functionBody(lines: List<String>, signature: String): List<String> {
        val start = lines.indexOfFirst { it.trim().startsWith(signature) }
        assertTrue("ComposeViewModel must declare $signature", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private ") || it.startsWith("    /**") }
            .let { if (it < 0) rest.size else it }
        return codeLines(rest.take(end).joinToString("\n"))
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("app/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}

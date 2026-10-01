package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rule on the shipped source. `MessageThumbnailsTest` executes the selection and the silent loop;
 * `MessageViewModel` needs an Application and the screen is a `@Composable`, so nothing but this
 * pins that the reader asks them, cancels them, reads the result by the part's own key, and never
 * lets a preview reach the disk or the status line.
 */
class MessageThumbnailWiringTest {

    @Test fun `every published body asks for its previews, once the page is settled on`() {
        val body = functionBody(viewModelRaw(), "private suspend fun publishBody(")
        val shown = body.indexOf("_messages.value = listOf(message)")
        val asked = body.indexOf("if (loadedId == emailId) loadThumbnailsFor(message)")
        assertTrue("publishBody must still show the message: $body", shown >= 0)
        assertTrue(
            "publishBody must ask for the previews right after showing the body, and only for the page " +
                "`load` owns (shown at $shown, asked at $asked): $body",
            asked == shown + 1,
        )
        assertEquals(
            "loadThumbnailsFor must be reached from publishBody and from the page becoming active again, " +
                "for the message `load` owns, and nowhere else. Lines found:",
            listOf(
                "if (loadedId == emailId) loadThumbnailsFor(message)",
                "private fun loadThumbnailsFor(message: ThreadMessage) {",
                "_messages.value.firstOrNull()?.takeIf { it.id == loadedId }?.let { loadThumbnailsFor(it) }",
            ),
            codeLines(viewModelRaw()).filter { "loadThumbnailsFor(" in it },
        )
    }

    @Test fun `the selection runs first, then the loop, in a job that replaces the previous one`() {
        val body = functionBody(viewModelRaw(), "private fun loadThumbnailsFor(message: ThreadMessage) {")
        val order = listOf(
            "val parts = thumbnailsToFetch(message.body?.fileAttachmentParts().orEmpty()) { part ->",
            "attachmentKind(part.type, part.name, MimeTypeMap.getSingleton()::getMimeTypeFromExtension)",
            "if (request == thumbnailRequest) return",
            "if (thumbnailOwner != message.id) forgetThumbnails()",
            "thumbnailJob?.cancel()",
            "thumbnailRequest = request",
            "thumbnailOwner = message.id",
            "thumbnailJob = viewModelScope.launch {",
            "loadThumbnails(",
            "pending,",
            "download = { part ->",
            "withContext(Dispatchers.IO) {",
            "repo.downloadAttachment(credentials, part, ownerId, DownloadLimits.INLINE_IMAGE_MAX_BYTES)",
            "decode = { bytes -> withContext(Dispatchers.Default) { decodeThumbnail(bytes, px) } },",
            "thumbnailCache.put(key, image)",
        )
        val at = order.map { body.indexOf(it) }
        assertTrue("loadThumbnailsFor must carry, in this order, $order. Positions: $at in $body", at.none { it < 0 })
        assertEquals("…in this order: $at", at.sorted(), at)
        assertEquals(
            "the parts handed to the loop are the selection's, less those already on screen. Lines found:",
            listOf("val pending = parts.filter { attachmentKey(it) !in shown }"),
            body.filter { it.startsWith("val pending") },
        )
        val download = body.indexOf("repo.downloadAttachment(credentials, part, ownerId, DownloadLimits.INLINE_IMAGE_MAX_BYTES)")
        assertEquals(
            "the download must sit directly inside withContext(Dispatchers.IO): a `pgp:` part is cut out " +
                "of the decrypted message synchronously, which on Main freezes the reader. $body",
            "withContext(Dispatchers.IO) {",
            body[download - 1],
        )
    }

    @Test fun `what is already on screen comes from the cache and is not fetched again`() {
        val body = functionBody(viewModelRaw(), "private fun loadThumbnailsFor(message: ThreadMessage) {")
        assertEquals(
            "Lines found:",
            listOf("val shown = thumbnailCache.snapshot().keys"),
            body.filter { it.startsWith("val shown") },
        )
    }

    @Test fun `each preview reaches the screen as soon as it is decoded`() {
        val body = functionBody(viewModelRaw(), "private fun loadThumbnailsFor(message: ThreadMessage) {")
        val put = body.indexOf("thumbnailCache.put(key, image)")
        assertTrue("the preview must still be kept: $body", put >= 0)
        assertEquals(
            "the exposed map must be republished right after each put, or the cache fills and the row keeps its icon. $body",
            "_attachmentThumbnails.value = thumbnailCache.snapshot()",
            body[put + 1],
        )
    }

    @Test fun `the preview is decoded at the screen's density`() {
        val body = functionBody(viewModelRaw(), "private fun loadThumbnailsFor(message: ThreadMessage) {")
        assertEquals(
            "40 dp in pixels, not 40 pixels: a 40 px decode is blurred on every modern screen. Lines found:",
            listOf("val px = (THUMBNAIL_DP * getApplication<Application>().resources.displayMetrics.density).roundToInt()"),
            body.filter { it.startsWith("val px") },
        )
    }

    @Test fun `a page swiped away stops its previews and resumes what is missing when it comes back`() {
        assertEquals(
            listOf("thumbnailJob?.cancel()", "thumbnailJob = null", "thumbnailRequest = null"),
            functionBody(viewModelRaw(), "private fun stopThumbnails() {"),
        )
        val active = functionBody(viewModelRaw(), "fun onActiveChanged(isActive: Boolean) {")
        val resume = active.indexOf("_messages.value.firstOrNull()?.takeIf { it.id == loadedId }?.let { loadThumbnailsFor(it) }")
        val otherwise = active.indexOf("} else {")
        val stop = active.indexOf("stopThumbnails()")
        assertTrue(
            "onActiveChanged must resume in its active branch and stop in the other (resume at $resume, " +
                "else at $otherwise, stop at $stop): $active",
            resume in 0 until otherwise && stop == otherwise + 1,
        )
    }

    @Test fun `opening or saving an attachment stops the previews before its own download`() {
        val open = functionBody(viewModelRaw(), "fun openAttachment(part: EmailBodyPart, ownerId: String) {")
        val stop = open.indexOf("stopThumbnails()")
        assertTrue(
            "openAttachment must stop the previews once it holds its guard and before it routes or launches: $open",
            stop == open.indexOf("openingAttachment = true") + 1 &&
                stop < open.indexOf("if (isAttachedMessage(part.type)) { openAttachedMessage(part, ownerId); return }") &&
                stop < open.indexOf("viewModelScope.launch {"),
        )
        val save = functionBody(viewModelRaw(), "fun saveAttachment(uri: Uri, proposedName: String, ownerId: String, partKey: String) {")
        assertEquals("saveAttachment must stop the previews before it launches: $save", listOf("stopThumbnails()", "viewModelScope.launch {"), save.take(2))
        val saveAll = functionBody(viewModelRaw(), "fun saveAllAttachments(treeUri: Uri, ownerId: String) {")
        assertEquals("saveAllAttachments must stop the previews before it launches: $saveAll", listOf("stopThumbnails()", "viewModelScope.launch {"), saveAll.take(2))
    }

    @Test fun `leaving the message cancels the job and forgets its previews`() {
        val forget = functionBody(viewModelRaw(), "private fun forgetThumbnails() {")
        assertEquals(
            listOf(
                "thumbnailJob?.cancel()",
                "thumbnailJob = null",
                "thumbnailRequest = null",
                "thumbnailOwner = null",
                "thumbnailCache.evictAll()",
                "_attachmentThumbnails.value = emptyMap()",
            ),
            forget,
        )
        val load = functionBody(viewModelRaw(), "fun load(emailId: String, accountId: String?, settled: Boolean) {")
        val prologue = load.subList(0, load.indexOf("viewModelScope.launch {"))
        assertEquals(
            "load's prologue must cancel and forget the previous message's previews before anything is " +
                "fetched: an IMAP part key such as \"2\" names a different picture in the next message. " +
                "Lines found:",
            forget,
            prologue.filter { "thumbnail" in it.lowercase() },
        )
    }

    @Test fun `nothing on the preview path writes to disk or to the status line`() {
        val forbidden = listOf(
            "_attachmentStatus", "openingAttachment", "_actionStatus", "cacheAttachment", "handAttachmentToViewer",
            "storage.", "File(", "openOutputStream", "writeBytes", "FileOutputStream",
        )
        val paths = mapOf(
            "loadThumbnailsFor" to functionBody(viewModelRaw(), "private fun loadThumbnailsFor(message: ThreadMessage) {"),
            "MessageThumbnails.kt" to codeLines(locate("app/src/main/kotlin/app/sterna/ui/message/MessageThumbnails.kt").readLines()),
        )
        for ((name, lines) in paths) {
            assertEquals(
                "$name must name none of $forbidden. Lines found:",
                emptyList<String>(),
                lines.filter { line -> forbidden.any { it in line } },
            )
        }
    }

    @Test fun `the reader's row reads the preview by the part's own key`() {
        val screen = codeLines(locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt").readLines())
        assertEquals(
            "Lines found:",
            listOf("val thumbnail = thumbnails[attachmentKey(att)]"),
            screen.filter { "thumbnails[" in it },
        )
        assertEquals(
            "the screen must collect the previews and hand them down to the section. Lines found:",
            listOf(
                "val attachmentThumbnails by viewModel.attachmentThumbnails.collectAsStateWithLifecycle()",
                "attachmentThumbnails = attachmentThumbnails,",
                "attachmentThumbnails = attachmentThumbnails,",
                "attachmentThumbnails,",
            ),
            screen.filter { "attachmentThumbnails" in it && ":" !in it },
        )
    }

    /** The code lines of the member opened by the line trimming to [signature], up to its closing brace. */
    private fun functionBody(raw: List<String>, signature: String): List<String> {
        val start = raw.indexOfFirst { it.trim() == signature }
        assertTrue("MessageViewModel must still declare `$signature`", start >= 0)
        val indent = raw[start].takeWhile { it == ' ' }
        val end = (start + 1 until raw.size).first { raw[it] == "$indent}" }
        return codeLines(raw.subList(start + 1, end))
    }

    private fun viewModelRaw(): List<String> =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt").readLines()

    /** [lines], trimmed, comment-only lines dropped. */
    private fun codeLines(lines: List<String>): List<String> = lines.map { it.trim() }.filterNot {
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

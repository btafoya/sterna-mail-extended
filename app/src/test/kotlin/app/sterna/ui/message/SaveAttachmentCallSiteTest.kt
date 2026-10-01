package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Save" on an attachment row has no decision a JVM test can execute on the app side: the
 * ViewModel needs an `Application` and a `ContentResolver`, the screen is a `@Composable`.
 */
class SaveAttachmentCallSiteTest {

    /**
     * The bytes come from the repository and go into the picked document, and nothing else happens
     */
    @Test fun `the saved bytes come from the server and go into the picked document`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must fetch the part through MailRepository.downloadAttachment, for the " +
                "message the picker was launched for. Lines found:",
            listOf("val bytes = repo.downloadAttachment(credentials, part, ownerId)"),
            body.filter { "downloadAttachment(" in it },
        )
        assertEquals(
            "saveAttachment must open the picked document in \"wt\" mode and write the BYTES. " +
                "Lines found:",
            listOf("getApplication<Application>().contentResolver.openOutputStream(uri, \"wt\")?.use { it.write(bytes) }"),
            body.filter { "openOutputStream(" in it },
        )
    }

    /**
     * And they are refused when there are none of them.
     */
    @Test fun `zero bytes are refused instead of written and announced as saved`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must ask requireAttachmentBytes about the bytes it just downloaded, " +
                "naming the message and the part the picker was launched for — the two keys a bug " +
                "report has to be able to quote. Lines found:",
            listOf("requireAttachmentBytes(ownerId, partKey, bytes)"),
            body.filter { "requireAttachmentBytes" in it },
        )
        val download = body.indexOf("val bytes = repo.downloadAttachment(credentials, part, ownerId)")
        val guard = body.indexOf("requireAttachmentBytes(ownerId, partKey, bytes)")
        val write = body.indexOf("withContext(Dispatchers.IO) {")
        assertTrue("the download must still be in saveAttachment: $body", download >= 0)
        assertTrue("the write must still be in saveAttachment: $body", write >= 0)
        assertTrue(
            "the refusal must sit BETWEEN the download and the write (download at $download, " +
                "refusal at $guard, write at $write): below the write, her document has already " +
                "been truncated to zero bytes when the refusal fires, and `discardDocument` " +
                "runCatchings its deletion — a provider that cannot delete then leaves behind " +
                "exactly the 0-byte file this guard exists to prevent.",
            guard in (download + 1) until write,
        )
    }

    /**
     * The hole every trimmed-line assertion is blind to: wrapping the refusal in an
     * `if (…) { … }` leaves it word for word where it is, one level deeper, and disarms it.
     */
    @Test fun `the refusal sits in the same block as the download and the write`() {
        val raw = saveAttachmentRawLines()
        val refusal = indentOf(raw, "requireAttachmentBytes(ownerId, partKey, bytes)")
        assertEquals(
            "same indentation as the download means same block: nested inside an `if (…) { … }` " +
                "the refusal reads identically, every trimmed-line assertion stays green, and it " +
                "fires on nothing",
            indentOf(raw, "val bytes = repo.downloadAttachment(credentials, part, ownerId)"),
            refusal,
        )
        assertEquals(
            "…and the same indentation as the write it stands in front of",
            indentOf(raw, "withContext(Dispatchers.IO) {"),
            refusal,
        )
    }

    /**
     * And they never touch the app's cache on the way.
     */
    @Test fun `saving never writes a copy into the app's cache`() {
        assertEquals(
            "MessageViewModel must reach the cache in exactly ONE place, the open path, through " +
                "handAttachmentToViewer. A second line here means the save path writes a copy into " +
                "the app's cache too. Lines found:",
            listOf("handAttachmentToViewer(app, storage, part.name, part.type, bytes)"),
            codeLines(viewModel().readText()).filter { "cacheAttachment(" in it || "handAttachmentToViewer(" in it },
        )
        assertEquals(
            "…and saveAttachment itself must name neither the cache, the open path nor a " +
                "FileProvider URI. Lines found:",
            emptyList<String>(),
            saveAttachmentBody().filter {
                "cacheAttachment" in it || "FileProvider" in it || "handAttachmentToViewer" in it
            },
        )
    }

    /**
     * The owner guard, on two keys.
     */
    @Test fun `the picker's result is refused unless it names the very part it was launched for`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must re-resolve the message from _messages by ownerId, the part by " +
                "partId ?: blobId, and refuse anything that does not resolve — including an empty " +
                "key. Lines found:",
            listOf(
                "val part = _messages.value.firstOrNull { it.id == ownerId }",
                "?.body?.fileAttachmentParts()",
                "?.firstOrNull { (it.partId ?: it.blobId) == partKey }",
                "if (ownerId.isEmpty() || partKey.isEmpty() || part == null) {",
            ),
            body.filter {
                "_messages.value" in it || "fileAttachmentParts()" in it ||
                    "blobId) == partKey" in it || "part == null" in it
            },
        )
        val refusal = body.indexOf("if (ownerId.isEmpty() || partKey.isEmpty() || part == null) {")
        val download = body.indexOf("val bytes = repo.downloadAttachment(credentials, part, ownerId)")
        assertTrue(
            "the refusal must come BEFORE the download (refusal at $refusal, download at " +
                "$download): moved after it, a result meant for another part is fetched from the " +
                "server before anyone asks whether it belongs here.",
            refusal in 0 until download,
        )
    }

    /**
     * `CreateDocument` has the provider create the document before the callback runs. So all three
     */
    @Test fun `every way out of a save deletes the document the picker created`() {
        assertEquals(
            "MessageViewModel must declare ONE discardDocument, and it must delete the document. " +
                "Each save path's own discards are counted in its own body, below and in " +
                "[ExportSourceCallSiteTest]. Lines found:",
            listOf(
                "private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {",
                "runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }",
            ),
            codeLines(viewModel().readText()).filter { "fun discardDocument(" in it || "deleteDocument(" in it },
        )
        assertEquals(
            "saveAttachment must discard the document exactly four times — one per way out. " +
                "Lines found:",
            listOf(
                "discardDocument(uri)", "discardDocument(uri)",
                "discardDocument(uri)", "discardDocument(uri)",
            ),
            saveAttachmentBody().filter { "discardDocument(" in it },
        )
    }

    /**
     * The fourth exit, and the one nobody watches: each pager page owns its ViewModel, so swiping
     */
    @Test fun `a save cancelled under its own page still takes the created document with it`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must catch CancellationException on its own, before the generic " +
                "catch, and rethrow it. Swallowed, it is reported as a failure that never " +
                "happened; not caught at all, the created document is never deleted. Lines found:",
            listOf("} catch (cancelled: CancellationException) {", "throw cancelled"),
            body.filter { "CancellationException" in it || "throw cancelled" in it },
        )
        assertEquals(
            "discardDocument must run NonCancellable, or it throws instead of deleting in the " +
                "very case it is there for. Lines found:",
            listOf("private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {"),
            codeLines(viewModel().readText()).filter { "fun discardDocument(" in it },
        )
    }

    /**
     * The mutation that pinning the number of discards cannot see, and the worst one on this
     * path. A FOURTH catch, slipped in just above the general one:
     */
    @Test fun `no fourth catch turns a refusal back into an announced save`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment must carry exactly these three catch clauses, in this order: " +
                "cancellation first (not a failure, and rethrown), then our own too-large refusal " +
                "with its own sentence, then everything else — which discards and reports. A " +
                "fourth clause is a way out that answers the user without either. Lines found:",
            listOf(
                "} catch (cancelled: CancellationException) {",
                "} catch (t: ContentTooLargeException) {",
                "} catch (t: Throwable) {",
            ),
            body.filter { it.startsWith("} catch (") },
        )
        val announcements = body.filter { "message_export_saved" in it }
        assertEquals(
            "saveAttachment must announce a save in exactly ONE place, and it is the line after " +
                "the write. Lines found:",
            listOf("_actionStatus.value = getApplication<Application>().getString(R.string.message_export_saved, name)"),
            announcements,
        )
        val announced = body.indexOf(announcements.first())
        val firstCatch = body.indexOfFirst { it.startsWith("} catch (") }
        assertTrue("saveAttachment must still catch something at all: $body", firstCatch >= 0)
        assertTrue(
            "the save must be announced BEFORE the first catch (announced at $announced, first " +
                "catch at $firstCatch). An announcement emitted from INSIDE a catch says " +
                "\"Saved as rapport.pdf\" about a save that just failed — that is the defect " +
                "itself, not an edge of it.",
            announced in 0 until firstCatch,
        )
    }

    /**
     * And the general catch does not put the exception's own English on her screen. The message
     */
    @Test fun `the failure sentence is translated before the exception's own text is used`() {
        val body = saveAttachmentBody()
        assertEquals(
            "saveAttachment's general catch must consult readFailureStringRes BEFORE falling back " +
                "on the exception's raw text, exactly as exportSource's catch does. Lines found:",
            listOf("_actionStatus.value = readFailureStringRes(t)?.let { getApplication<Application>().getString(it) }"),
            body.filter { "readFailureStringRes" in it },
        )
        val mapped = body.indexOfFirst { "readFailureStringRes" in it }
        val raw = body.indexOfFirst { "status_save_attachment_failed" in it }
        assertTrue("the raw fallback must still exist for everything else: $body", raw >= 0)
        assertTrue(
            "the mapping must come BEFORE the raw fallback (mapped at $mapped, raw at $raw): " +
                "after it, the English text wins and the translation is never reached.",
            mapped in 0 until raw,
        )
        assertEquals(
            "…and the too-large refusal keeps its OWN sentence: a refusal of SIZE is not a missing " +
                "octet, and its catch stays ahead of the general one so it never reaches the " +
                "mapping. Lines found:",
            listOf("_actionStatus.value = getApplication<Application>().getString(R.string.status_attachment_too_large)"),
            body.filter { "status_attachment_too_large" in it },
        )
    }

    /**
     * The screen's half. The picker is registered with a wildcard type, once (the contract freezes
     */
    @Test fun `the row launches a wildcard picker with a sanitised name`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the save picker must be created for the wildcard MIME type, exactly once — a " +
                "narrower type and the picker offers the wrong extension for every other kind of " +
                "attachment. Lines found:",
            listOf("ActivityResultContracts.CreateDocument(\"*/*\"),"),
            lines.filter { it == "ActivityResultContracts.CreateDocument(\"*/*\")," },
        )
        assertEquals(
            "the launcher must be declared once and LAUNCHED with the proposed name — without " +
                "the launch the button does nothing at all. Lines found:",
            listOf(
                "val saveAttachmentLauncher = rememberLauncherForActivityResult(",
                "saveAttachmentLauncher.launch(saveAttachmentName)",
            ),
            lines.filter { "saveAttachmentLauncher" in it },
        )
        assertEquals(
            "the proposed name must go through attachmentFileName: `part.name` raw puts a `/` or " +
                "a `:` into the picker's name box, and loses the extension of a long name. " +
                "Lines found:",
            listOf("saveAttachmentName = attachmentFileName(part.name)"),
            lines.filter { "attachmentFileName(" in it },
        )
    }

    /**
     * And the three keys are `rememberSaveable`, not `remember`. The picker is another activity:
     */
    @Test fun `the three picker keys survive an activity recreated behind the picker`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the message, the part key and the proposed name must each be a rememberSaveable. " +
                "Lines found:",
            listOf(
                "var saveAttachmentFor by rememberSaveable { mutableStateOf(\"\") }",
                "var saveAttachmentPart by rememberSaveable { mutableStateOf(\"\") }",
                "var saveAttachmentName by rememberSaveable { mutableStateOf(\"\") }",
            ),
            lines.filter { it.startsWith("var saveAttachment") },
        )
    }

    /**
     * The row's own tap is untouched: the name still opens the attachment, the icon saves it,
     * as K-9 and Gmail both put the two side by side.
     */
    @Test fun `the row still opens on its body, and saves on its icon`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            "the attachment row must keep its own open-on-tap and gain one save button. " +
                "Lines found:",
            listOf(".clickable { onOpen(att) }", "IconButton(onClick = { onSave(att) }) {"),
            lines.filter { "onOpen(att)" in it || "onSave(att)" in it },
        )
        assertEquals(
            "the section must be handed both lambdas by name, and the header must pass the " +
                "message id to the save one exactly as it does to the open one. Lines found:",
            listOf(
                "onSaveAttachment = { part, ownerId ->",
                "onSaveAttachment: (EmailBodyPart, String) -> Unit,",
                "msg, full, attachmentStatus, onOpenAttachment, onSaveAttachment, calendar, onRespondToInvite,",
                "onSaveAttachment: (EmailBodyPart, String) -> Unit,",
                "onSave = { part -> onSaveAttachment(part, msg.id) },",
            ),
            lines.filter { "onSaveAttachment" in it },
        )
    }

    @Test fun `save all creates each document in the picked tree and writes the server's bytes`() {
        val body = saveAllAttachmentsBody()
        assertEquals(
            "saveAllAttachments must create each document under the picked tree's own document, " +
                "through DocumentsContract, with the part's MIME type and the batch's name. Lines found:",
            listOf(
                "val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))",
                "DocumentsContract.createDocument(resolver, parent, mime, name)",
            ),
            body.filter { "DocumentsContract." in it },
        )
        assertEquals(
            "…fetch the bytes through MailRepository.downloadAttachment for the message the picker " +
                "was launched for, and write them in \"wt\" mode. Lines found:",
            listOf(
                "resolver.openOutputStream(document, \"wt\")?.use { it.write(bytes) } ?: error(\"no output stream\")",
                "repo.downloadAttachment(credentials, part, ownerId)",
            ),
            body.filter { "downloadAttachment(" in it || "openOutputStream(" in it },
        )
        assertEquals(
            "…and hand a document it gives up on to discardDocument, exactly once, from the " +
                "folder's discard. Lines found:",
            listOf("override suspend fun discard(document: Uri) {", "discardDocument(document)"),
            body.filter { "discard" in it },
        )
        assertEquals(
            "…re-resolving the parts from _messages, never from anything the screen handed down. " +
                "Lines found:",
            listOf("_messages.value,", "ownerId,", "folder,"),
            body.dropWhile { it != "val outcome = saveAllAttachmentsInto(" }.drop(1).take(3),
        )
    }

    @Test fun `save all discards the part in flight on failure and on cancellation`() {
        val loop = saveAllLoopBody()
        assertEquals(
            "saveAllAttachmentsInto must carry exactly two catch clauses, cancellation BEFORE the " +
                "generic one: the other way round, a cancelled batch is swallowed as one failed part " +
                "and the loop goes on writing under a dead page. Lines found:",
            listOf("} catch (cancelled: CancellationException) {", "} catch (t: Throwable) {"),
            loop.filter { it.startsWith("} catch (") },
        )
        val cancelCatch = loop.indexOf("} catch (cancelled: CancellationException) {")
        val failCatch = loop.indexOf("} catch (t: Throwable) {")
        assertEquals(
            "each catch must discard the document in flight on its first line, and the " +
                "cancellation one must rethrow. Lines found:",
            listOf("document?.let { folder.discard(it) }", "throw cancelled", "document?.let { folder.discard(it) }"),
            listOf(loop[cancelCatch + 1], loop[cancelCatch + 2], loop[failCatch + 1]),
        )
        val fetch = loop.indexOf("val bytes = fetch(part)")
        val guard = loop.indexOf("requireAttachmentBytes(ownerId, part.partId ?: part.blobId.orEmpty(), bytes)")
        val write = loop.indexOf("folder.write(document, bytes)")
        val create = loop.indexOf("document = folder.create(names[index], \"application/octet-stream\")")
        assertTrue(
            "the document must be created after the bytes are in hand and refused-if-empty, and " +
                "with the neutral type (guard $guard, create $create, write $write): $loop",
            guard >= 0 && create in (guard + 1) until write,
        )
        assertTrue(
            "the zero-byte refusal must sit between the fetch and the write (fetch $fetch, guard " +
                "$guard, write $write): $loop",
            fetch >= 0 && guard in (fetch + 1) until write,
        )
        assertEquals(
            "the count must grow on the line after the write, inside the try. Lines found:",
            "saved++",
            loop[write + 1],
        )
    }

    @Test fun `save all never names the cache`() {
        assertEquals(
            "neither saveAllAttachments nor its loop may name the cache, the open path or a " +
                "FileProvider. Lines found:",
            emptyList<String>(),
            (saveAllAttachmentsBody() + codeLines(saveAllFile().readText())).filter {
                "cacheAttachment" in it || "FileProvider" in it || "handAttachmentToViewer" in it
            },
        )
    }

    @Test fun `save all says one word from the outcome and always clears its progress`() {
        val body = saveAllAttachmentsBody()
        assertEquals(
            "the outcome must map refused, all saved and partly saved to their own sentences. Lines found:",
            listOf(
                "SaveAllOutcome.Refused -> app.getString(R.string.status_action_failed)",
                "app.resources.getQuantityString(R.plurals.message_attachments_all_saved, outcome.count, outcome.count)",
                "R.plurals.message_attachments_partly_saved, outcome.total, outcome.saved, outcome.total,",
            ),
            body.filter { "R.string." in it || "R.plurals." in it }.filterNot { "status_no_saved_account" in it },
        )
        val finally = body.indexOf("} finally {")
        assertTrue("saveAllAttachments must have a finally: $body", finally >= 0)
        assertEquals("_saveAllProgress.value = null", body[finally + 1])
        assertEquals(
            "the outcome expression must stay `saved == parts.size`: anything looser announces " +
                "every attachment saved after one failed. Lines found:",
            listOf("return if (saved == parts.size) SaveAllOutcome.AllSaved(saved) else SaveAllOutcome.PartlySaved(saved, parts.size)"),
            saveAllLoopBody().filter { "SaveAllOutcome.AllSaved(" in it },
        )
    }

    @Test fun `the header's save all launches one folder picker for its own message`() {
        val lines = codeLines(screen().readText())
        assertEquals(
            listOf("ActivityResultContracts.OpenDocumentTree(),"),
            lines.filter { "OpenDocumentTree(" in it },
        )
        assertEquals(
            listOf(
                "var saveAllAttachmentsFor by rememberSaveable { mutableStateOf(\"\") }",
                "val ownerId = saveAllAttachmentsFor",
                "saveAllAttachmentsFor = \"\"",
                "if (saveAllAttachmentsFor.isEmpty()) {",
                "saveAllAttachmentsFor = ownerId",
            ),
            lines.filter { "saveAllAttachmentsFor" in it },
        )
        assertEquals(
            listOf(
                "val saveAllAttachmentsLauncher = rememberLauncherForActivityResult(",
                "saveAllAttachmentsLauncher.launch(null)",
            ),
            lines.filter { "saveAllAttachmentsLauncher" in it },
        )
        assertEquals(
            listOf("if (treeUri != null) viewModel.saveAllAttachments(treeUri, ownerId)"),
            lines.filter { "viewModel.saveAllAttachments(" in it },
        )
        assertEquals(
            "the section must be offered save all for the message it belongs to. Lines found:",
            listOf("onSaveAll = { onSaveAllAttachments(msg.id) },"),
            lines.filter { "onSaveAll = " in it },
        )
    }

    /** The code lines of `saveAllAttachments`, up to the next member declared at class level. */
    private fun saveAllAttachmentsBody(): List<String> {
        val lines = viewModel().readText().lines()
        val start = lines.indexOfFirst { it.trim().startsWith("fun saveAllAttachments(treeUri: Uri, ownerId: String)") }
        assertTrue("MessageViewModel must still declare saveAllAttachments(treeUri, ownerId)", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private ") }
            .let { if (it < 0) rest.size else it }
        return codeLines(rest.take(end).joinToString("\n")).also {
            assertTrue("saveAllAttachments must have a body", it.isNotEmpty())
        }
    }

    /** The code lines of `saveAllAttachmentsInto`, to the end of its file's top-level declaration. */
    private fun saveAllLoopBody(): List<String> {
        val lines = saveAllFile().readText().lines()
        val start = lines.indexOfFirst { it.startsWith("internal suspend fun <D> saveAllAttachmentsInto(") }
        assertTrue("SaveAllAttachments.kt must still declare saveAllAttachmentsInto", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it == "}" }.let { if (it < 0) rest.size else it }
        return codeLines(rest.take(end).joinToString("\n"))
    }

    private fun saveAllFile(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/SaveAllAttachments.kt")

    /**
     * The code lines of `saveAttachment`, from its declaration to the next member declared at class
     * level. Fails loudly when the function is gone rather than passing on an empty list.
     */
    private fun saveAttachmentBody(): List<String> {
        val body = codeLines(saveAttachmentRawLines().joinToString("\n"))
        assertTrue("saveAttachment must have a body", body.isNotEmpty())
        return body
    }

    /**
     * The same span, raw, indentation intact.
     */
    private fun saveAttachmentRawLines(): List<String> {
        val lines = viewModel().readText().lines()
        val start = lines.indexOfFirst {
            it.trim().startsWith("fun saveAttachment(uri: Uri, proposedName: String, ownerId: String, partKey: String)")
        }
        assertTrue("MessageViewModel must still declare saveAttachment(uri, proposedName, ownerId, partKey)", start >= 0)
        val rest = lines.drop(start + 1)
        // `private ` and not `private fun `: the next member is `private suspend fun
        // discardDocument`, and a narrower marker runs the body straight past the end of it.
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private ") }
            .let { if (it < 0) rest.size else it }
        return rest.take(end)
    }

    /** The leading spaces of the one line of [raw] that trims to [needle]. */
    private fun indentOf(raw: List<String>, needle: String): String {
        val line = raw.firstOrNull { it.trim() == needle }
        assertTrue(
            "saveAttachment must still carry the line `$needle`. Lines found: ${raw.map { it.trim() }}",
            line != null,
        )
        return line.orEmpty().takeWhile { it == ' ' }
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

    private fun screen(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")

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

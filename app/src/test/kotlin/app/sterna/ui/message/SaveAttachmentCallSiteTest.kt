package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Save" on an attachment row has no decision a JVM test can execute on the app side: the
 * ViewModel needs an `Application` and a `ContentResolver`, the screen is a `@Composable`.
 * `AttachmentFileNameTest` in `core/data` executes the one decision that is a pure function (the
 * name proposed to the picker); this pins that the app is still WIRED to it, and that the bytes
 * take the route they are supposed to take.
 *
 * Whole trimmed lines compared by list equality, never `contains` on a fragment for the
 * assertion itself — that is blind to every mutation which LENGTHENS the line. Comment lines are
 * dropped, so prose can neither satisfy nor break a rule.
 */
class SaveAttachmentCallSiteTest {

    /**
     * The bytes come from the repository and go into the picked document, and nothing else happens
     * to them. Rewrite the download to `rawSource` and the file is the whole message instead of the
     * attachment; drop the `"wt"` and a re-saved file keeps the tail of a longer one; `write` a
     * String instead of the bytes and every attachment leaves re-encoded as UTF-8 text, which for a
     * PDF or a photo means a corrupt file she cannot open and no warning anywhere.
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
     * And they are REFUSED when there are none of them. `MimeParser.decodeBytes("", …)` answers a
     * zero-length ByteArray without throwing (MimeParser-427), and its "" is what
     * `ImapClient.bodyItem()` yields whenever a `UID FETCH (BODY.PEEK[…])` came back OK with no
     * untagged FETCH at all — measured on the test server for a UID destroyed server-side between
     * opening the message and the Save tap. Unguarded, `openOutputStream(…, "wt")` truncates the
     * picked document to nothing and the next line announces `message_export_saved` with its name:
     * she leaves with a 0-byte `rapport.pdf` she believes is her attachment, and no toast ever
     * hinted otherwise. The DECISION is EXECUTED next door in core/data by
     * `AttachmentCouldNotBeReadTest`; what nothing else can see is whether this call site still
     * asks it, with the right ARGUMENTS, and EARLY ENOUGH.
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
     * THE hole every trimmed-line assertion is blind to: wrapping the refusal in an
     * `if (…) { … }` leaves it word for word where it is, one level deeper, and disarms it.
     * [saveAttachmentBody] trims, so nesting is invisible to it by construction, and the rank
     * assertion above would still read the same order. Indentation is the only witness — and it is
     * compared to the DOWNLOAD's and the WRITE's own indentation, never to a literal count of
     * spaces: a reformat of the whole function must not turn this red, only a change of block must.
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
     * And they never touch the app's cache on the way. `storage.cacheAttachment` belongs to the
     * OPEN path, which needs a file it can hand to a viewer app; saving needs no such file. Routed
     * through it, every attachment she saves would also exist as a second copy inside the app,
     * sitting in the cache until something cleared it — twice the disk, for nothing she asked for.
     * The whole file's `cacheAttachment` lines are pinned, not just this function's: a call added
     * anywhere on this path is the same defect.
     */
    @Test fun `saving never writes a copy into the app's cache`() {
        assertEquals(
            "MessageViewModel must cache an attachment in exactly ONE place, the open path. A " +
                "second line here means the save path writes a copy into the app's cache too. " +
                "Lines found:",
            listOf("val file = storage.cacheAttachment(part.name, bytes)"),
            codeLines(viewModel().readText()).filter { "cacheAttachment(" in it },
        )
        assertEquals(
            "…and saveAttachment itself must name neither the cache nor a FileProvider URI. " +
                "Lines found:",
            emptyList<String>(),
            saveAttachmentBody().filter { "cacheAttachment" in it || "FileProvider" in it },
        )
    }

    /**
     * The owner guard, on TWO keys. The reader is a conversation: the row hands over `msg.id`,
     * which may be another message of the thread than the settled page, so `loadedId` is the wrong
     * question and the message must be re-resolved from `_messages`. An activity recreated behind
     * the picker can come back on another page, or on the same message with another attachment —
     * and part B's bytes written under part A's name is a file in her Downloads that lies about
     * what it holds. An empty key means the saved state was lost, and is refused too.
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
     * `CreateDocument` has the provider create the document BEFORE the callback runs. So all three
     * ways out — the owner refusal, our own "too large" refusal, and any failure — delete it again,
     * or she finds an empty `rapport.pdf` in Downloads and believes the attachment saved. The
     * toast alone would not undo that — and neither would a cancelled scope, which is why there is
     * a FOURTH exit here and why the helper is [NonCancellable]. The whole file's list is pinned:
     * six call sites (two for "Save as .eml", four here) plus the helper and its one deletion.
     */
    @Test fun `every way out of a save deletes the document the picker created`() {
        assertEquals(
            "MessageViewModel must discard the created document on the owner refusal, on the " +
                "too-large refusal and on any failure. One missing and an empty file with a real " +
                "name is left behind in her Downloads. Lines found:",
            listOf(
                // exportSource: owner refusal, then any failure
                "discardDocument(uri)",
                "discardDocument(uri)",
                // saveAttachment: owner refusal, cancellation, too large, any failure
                "discardDocument(uri)",
                "discardDocument(uri)",
                "discardDocument(uri)",
                "discardDocument(uri)",
                "private suspend fun discardDocument(uri: Uri) = withContext(NonCancellable + Dispatchers.IO) {",
                "runCatching { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, uri) }",
            ),
            codeLines(viewModel().readText()).filter { "discardDocument(" in it || "deleteDocument(" in it },
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
     * two messages away mid-download cancels this scope. Caught as an ordinary throwable, the
     * cancellation would take the `discardDocument` down with it — `withContext` throws at once in a
     * cancelled scope — and the empty `rapport.pdf` the picker created would stay in her folder,
     * with no page left to show a toast about it. So: cancellation is caught FIRST, the document is
     * discarded [NonCancellable], and the cancellation is rethrown rather than swallowed.
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
     * THE mutation that pinning the NUMBER of discards cannot see, and the worst one on this
     * path. A FOURTH catch, slipped in just above the general one:
     *
     *     } catch (t: MessageUnavailableException) {
     *         _actionStatus.value = getApplication<Application>()
     *             .getString(R.string.message_export_saved, proposedName)
     *
     * It compiles. The four discards and the eight are still four and eight, the refusal's own line
     * and its indentation are untouched, so every other assertion in this file stays green — and
     * she is handed the whole defect back: the 0-byte `rapport.pdf` stays in her Downloads, under a
     * toast that says "Saved as rapport.pdf". What closes it is not a count but a PAIRING — the
     * exact list of catch clauses, and a success announcement that lives outside every one of them.
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
     * `requireAttachmentBytes` raises names the part key and the message id, in English, for a bug
     * report — interpolated raw into `status_save_attachment_failed` it reads, in French,
     * "Impossible d'enregistrer la pièce jointe : Attachment 2.1 of M8fa4… came back empty", which
     * is exactly what the #159 doctrine at the top of this file forbids. `readFailureStringRes` is
     * the wiring that maps it to the already-translated `status_message_unavailable`, and
     * `exportSource` — the twin this path copies — has consulted it all along.
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
     * The screen's half. The picker is registered with a wildcard type, ONCE (the contract freezes
     * its type at registration, so a per-part type would need a recomposition between the tap and
     * `launch()`); the name it proposes goes through `attachmentFileName`, which is what carries
     * the extension — handed `part.name` raw, a part called `Re: budget/2026.pdf` proposes a name
     * with a `/` in it, which no provider will create.
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
     * the system may recreate this one behind it, and a plain `remember` comes back empty. Empty is
     * what the ViewModel's guard refuses, so the save would be dropped without a word — the guard
     * falling on the wrong side of its own question.
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
     * The row's own tap is untouched: the name still opens the attachment, the icon saves it —
     * K-9 and Gmail both put the two side by side. Fold the save into the row's `clickable` and
     * the reader can no longer open anything.
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
     * The same span, RAW — indentation intact. A sibling of [saveAttachmentBody] rather than a
     * second span of its own: every assertion above compares TRIMMED lines, and the one assertion
     * that has to read the leading spaces would be defeated by that trim. Both views read this,
     * so they cannot drift onto different spans.
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

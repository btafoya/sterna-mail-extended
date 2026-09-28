package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A rule on the shipped source. `ComposedAttachmentSourceTest` and `ComposedAttachmentOpenFailureTest`
 * execute the two decisions; `ComposeViewModel` needs an Application and a repository, so nothing
 * but this pins that `openAttachment` asks them, with these arguments, and hands the bytes to the
 * reader's own path instead of a second copy of it.
 */
class ComposeOpenAttachmentWiringTest {

    @Test fun `the composer asks the decision about its own outgoing directory and the sender's protocol`() {
        val body = openAttachmentBody()
        assertEquals(
            "openAttachment must ask composedAttachmentSource about the part, the SENDING account's " +
                "protocol and the staging roots. Lines found:",
            listOf("composedAttachmentSource(part, credentials.protocol, roots)"),
            body.filter { "composedAttachmentSource(" in it },
        )
        assertEquals(
            "the roots must be cacheDir/outgoing and the local-draft directory, built from " +
                "LOCAL_DRAFT_FILES_DIR as DataFactory builds it: without the second, a reopened local " +
                "draft's attachment is refused as unreadable. Lines found:",
            listOf("val roots = listOf(File(app.cacheDir, \"outgoing\"), File(app.filesDir, LOCAL_DRAFT_FILES_DIR))"),
            body.filter { "val roots" in it },
        )
        assertEquals(
            "only the ServerBlob branch may reach the network, with emailId = \"\". Lines found:",
            listOf("ComposedAttachmentSource.ServerBlob -> repo.downloadAttachment(credentials, part, emailId = \"\")"),
            body.filter { "downloadAttachment(" in it },
        )
        assertEquals(
            "the IMAP-sender refusal must say the sentence the send already says. Lines found:",
            listOf("_attachmentStatus.value = attachmentPreparedForAnotherAccount(part.name)"),
            body.filter { "attachmentPreparedForAnotherAccount" in it },
        )
    }

    @Test fun `the composer hands the bytes to the reader's viewer path and has no copy of its own`() {
        val lines = codeLines(viewModel().readText())
        assertEquals(
            "ComposeViewModel must open through handAttachmentToViewer, once. Lines found:",
            listOf("handAttachmentToViewer(app, storage, part.name, part.type, bytes)"),
            lines.filter { "handAttachmentToViewer(" in it },
        )
        assertEquals(
            "…and never cache, expose or start anything itself: a second path would type the Intent " +
                "or grant the URI differently. Lines found:",
            emptyList<String>(),
            lines.filter {
                "cacheAttachment(" in it || "FileProvider" in it || "startActivity(" in it ||
                    "setDataAndType(" in it
            },
        )
    }

    @Test fun `opening does not touch the attachment list nor mark the draft changed`() {
        assertEquals(
            "openAttachment must not write _attachments or _attachmentsTouched. Lines found:",
            emptyList<String>(),
            openAttachmentBody().filter { "_attachments" in it },
        )
    }

    @Test fun `the double-tap guard is taken before the launch and released in finally`() {
        val body = openAttachmentBody()
        assertEquals(
            "Lines found:",
            listOf("if (openingAttachment) return", "openingAttachment = true", "openingAttachment = false"),
            body.filter { "openingAttachment" in it },
        )
        val release = body.indexOf("openingAttachment = false")
        assertEquals("the release must be the first line of the finally block: $body", "} finally {", body[release - 1])
        assertTrue(
            "the guard must be taken before the coroutine starts: $body",
            body.indexOf("openingAttachment = true") < body.indexOf("viewModelScope.launch {"),
        )
    }

    @Test fun `a failure is worded by the decision, with connectivity asked`() {
        assertEquals(
            "Lines found:",
            listOf("app.getString(composedAttachmentOpenFailure(t, online = hasUsableNetwork(app)), t.message ?: \"error\")"),
            openAttachmentBody().filter { "composedAttachmentOpenFailure(" in it },
        )
    }

    @Test fun `the send and the composer share one sentence for a blob of another account`() {
        val sender = codeLines(locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailSender.kt").readText())
        assertEquals(
            "the sentence must be written once, in attachmentPreparedForAnotherAccount. Lines found:",
            1,
            sender.count { "was prepared for another account" in it },
        )
        assertEquals(
            "…and both send paths must say it through that function. Lines found:",
            2,
            sender.count { "error(attachmentPreparedForAnotherAccount(" in it },
        )
    }

    /**
     * The staged plaintext of an attachment awaiting encryption must never be reachable through the
     * FileProvider: only the copy in `attachments/` is handed out.
     */
    @Test fun `the FileProvider does not expose outgoing`() {
        val paths = locate("app/src/main/res/xml/file_paths.xml").readText()
        assertTrue("file_paths.xml must not name outgoing:\n$paths", "outgoing" !in paths)
    }

    /**
     * The tap is on the attachment's own Row, first in its modifier after the width so the ripple
     * spans the row and the padding stays inside it; Remove keeps its own button.
     */
    @Test fun `the attachment row opens it and Remove still removes`() {
        val row = attachmentRowLines()
        assertEquals(
            "Lines found:",
            listOf(
                "Row(",
                "modifier = Modifier",
                ".fillMaxWidth()",
                ".clickable(",
                "onClickLabel = stringResource(R.string.status_open_attachment),",
                "role = Role.Button,",
                ") { viewModel.openAttachment(att) }",
                ".padding(horizontal = 16.dp, vertical = 6.dp),",
            ),
            row.take(8),
        )
        assertEquals(
            "Lines found:",
            listOf("TextButton(onClick = { viewModel.removeAttachment(att) }) {"),
            row.filter { "TextButton(" in it },
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

    private fun openAttachmentBody(): List<String> {
        val lines = viewModel().readText().lines()
        val start = lines.indexOfFirst { it.trim().startsWith("fun openAttachment(") }
        assertTrue("ComposeViewModel must declare openAttachment", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private fun ") }
            .let { if (it < 0) rest.size else it }
        return codeLines(rest.take(end).joinToString("\n"))
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt")

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

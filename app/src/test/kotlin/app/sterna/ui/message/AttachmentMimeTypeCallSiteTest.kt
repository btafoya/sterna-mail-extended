package app.sterna.ui.message

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A rule on the shipped source, not a behaviour test. `AttachmentMimeTypeTest` executes the
 * decision; nothing but this pins that the reader's open path still asks it. `MessageViewModel`
 * needs an Application and a repository, so no JVM test here can call `openAttachment` and read
 * the Intent back.
 */
class AttachmentMimeTypeCallSiteTest {

    /**
     * The one line of the view model that types the view Intent, whole. Pinned by list equality
     * and not by a fragment: a `contains` stays green on every mutation that only lengthens it.
     */
    @Test fun `the open Intent is typed by the decision, not by what the sender declared`() {
        assertEquals(
            "openAttachment must type the ACTION_VIEW Intent through attachmentMimeType, once and " +
                "whole. Lines found:",
            listOf(TYPED),
            codeLines(viewModel().readText()).filter { "setDataAndType(" in it },
        )
    }

    /**
     * And the sender's word never reaches the Intent on its own. A part declared
     * `application/octet-stream` is then opened by whatever app claims the generic type.
     */
    @Test fun `the declared type is no longer handed to the Intent raw`() {
        assertEquals(
            "part.type must not be passed to setDataAndType. Lines found:",
            emptyList<String>(),
            codeLines(viewModel().readText()).filter {
                "setDataAndType(" in it && ("uri, part.type" in it || "uri, part.type ?:" in it)
            },
        )
    }

    /**
     * The framework half of the decision, pinned by its arguments and not by the name it is given
     * here. A table of our own would answer for the phone, and answer differently on every device.
     */
    @Test fun `the extension is resolved by the framework's own table`() {
        assertEquals(
            "the lookup handed to attachmentMimeType must be MimeTypeMap's own, referenced from " +
                "the singleton. Lines found:",
            listOf(TYPED),
            codeLines(viewModel().readText()).filter { "MimeTypeMap" in it && !it.startsWith("import ") },
        )
        assertEquals(
            "MessageViewModel must import MimeTypeMap. Lines found:",
            listOf("import android.webkit.MimeTypeMap"),
            codeLines(viewModel().readText()).filter { it.startsWith("import ") && "MimeTypeMap" in it },
        )
    }

    /**
     * Exactly one call, so the save path and the attached-message path cannot grow a second,
     * differently argued copy of the same decision without this turning red.
     */
    @Test fun `the decision is asked once in the whole view model`() {
        assertEquals(
            "attachmentMimeType must be called exactly once in MessageViewModel. Lines found:",
            listOf(TYPED),
            codeLines(viewModel().readText()).filter { "attachmentMimeType(" in it },
        )
        assertEquals(
            "…and imported from core:data rather than redeclared here. Lines found:",
            listOf("import app.sterna.core.data.text.attachmentMimeType"),
            codeLines(viewModel().readText()).filter { it.startsWith("import ") && "attachmentMimeType" in it },
        )
    }

    /** [text]'s lines, trimmed, comment-only lines dropped. */
    private fun codeLines(text: String): List<String> = text.lines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private fun viewModel(): File =
        locate("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")

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

    private companion object {
        const val TYPED = ".setDataAndType(uri, attachmentMimeType(part.type, part.name, " +
            "MimeTypeMap.getSingleton()::getMimeTypeFromExtension))"
    }
}

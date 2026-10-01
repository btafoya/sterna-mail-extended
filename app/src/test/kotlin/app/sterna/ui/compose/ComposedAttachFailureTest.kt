package app.sterna.ui.compose

import app.sterna.R
import app.sterna.core.jmap.ContentTooLargeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.UnknownHostException

class ComposedAttachFailureTest {

    @Test fun `over the server's upload limit is said plainly while connected`() {
        assertEquals(
            R.string.status_attachment_too_large,
            composedAttachFailure(ContentTooLargeException("Attachment is 1001 bytes", 1001, 1000), online = true),
        )
    }

    @Test fun `over the limit is never told as offline, even offline with a transport cause`() {
        val t = ContentTooLargeException("Attachment is 1001 bytes", 1001, 1000)
            .apply { initCause(UnknownHostException("mail")) }
        assertEquals(R.string.status_attachment_too_large, composedAttachFailure(t, online = false))
    }

    @Test fun `a transport failure with connectivity down is told as offline`() {
        assertEquals(
            R.string.status_attach_offline,
            composedAttachFailure(IOException("upload", UnknownHostException("mail")), online = false),
        )
    }

    @Test fun `a transport failure while connected keeps the failed sentence`() {
        assertEquals(
            R.string.status_attach_failed,
            composedAttachFailure(UnknownHostException("mail"), online = true),
        )
    }

    @Test fun `a failure that is not the transport keeps the failed sentence, even offline`() {
        assertEquals(
            R.string.status_attach_failed,
            composedAttachFailure(IOException("No space left on device"), online = false),
        )
    }

    /**
     * A rule on the shipped source: `ComposeViewModel` needs an Application and a repository, so
     * nothing but this pins that `attach` words its failure through the decision above, with
     * connectivity asked and the exception text still handed to the failed sentence.
     */
    @Test fun `attach words its failure through the decision`() {
        val body = attachBody()
        assertEquals(
            "Lines found:",
            listOf("_attachmentStatus.value = app.getString(composedAttachFailure(t, online = hasUsableNetwork(app)), t.message ?: \"error\")"),
            body.filter { "composedAttachFailure(" in it },
        )
        assertEquals(
            "attach must not ask isOfflineFailure itself: a second test beside the decision could " +
                "tell a size refusal as offline. Lines found:",
            emptyList<String>(),
            body.filter { "isOfflineFailure(" in it },
        )
    }

    private fun attachBody(): List<String> {
        val lines = locate("app/src/main/kotlin/app/sterna/ui/compose/ComposeViewModel.kt").readText().lines()
        val start = lines.indexOfFirst { it.trim().startsWith("fun attach(") }
        assertTrue("ComposeViewModel must declare attach", start >= 0)
        val rest = lines.drop(start + 1)
        val end = rest.indexOfFirst { it.startsWith("    fun ") || it.startsWith("    private fun ") || it.startsWith("    /**") }
            .let { if (it < 0) rest.size else it }
        return rest.take(end).map { it.trim() }.filterNot {
            it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
        }
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

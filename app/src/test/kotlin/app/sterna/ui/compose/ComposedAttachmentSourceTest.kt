package app.sterna.ui.compose

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.mail.LOCAL_DRAFT_FILES_DIR
import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * The decision executed on a real directory: the containment check goes through canonical paths,
 * which a string-only fixture would not exercise.
 */
class ComposedAttachmentSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private val cache: File by lazy { tmp.newFolder("cache") }
    private val outgoing: File by lazy { File(cache, "outgoing").apply { mkdirs() } }
    private val files: File by lazy { tmp.newFolder("files") }
    private val localDrafts: File by lazy { File(files, LOCAL_DRAFT_FILES_DIR).apply { mkdirs() } }
    private val roots: List<File> by lazy { listOf(outgoing, localDrafts) }

    private fun file(part: String?, blob: String? = null) =
        EmailBodyPart(partId = part, blobId = blob, size = 3, type = "application/pdf", name = "a.pdf")

    @Test fun `a file staged under outgoing is read back from that file`() {
        val staged = File(outgoing, "123-a.pdf").apply { writeText("pdf") }
        assertEquals(
            ComposedAttachmentSource.Staged(staged.canonicalFile),
            composedAttachmentSource(file(staged.absolutePath), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a staged file is read back whatever the sender, JMAP with PGP stages too`() {
        val staged = File(outgoing, "124-a.pdf").apply { writeText("pdf") }
        assertEquals(
            ComposedAttachmentSource.Staged(staged.canonicalFile),
            composedAttachmentSource(file(staged.absolutePath), MailProtocol.JMAP, roots),
        )
    }

    @Test fun `a path that climbs out of outgoing by dot-dot is refused`() {
        val secret = File(cache, "secret.db").apply { writeText("s") }
        val climbing = outgoing.absolutePath + File.separator + ".." + File.separator + secret.name
        assertEquals(
            ComposedAttachmentSource.Unreadable,
            composedAttachmentSource(file(climbing), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a sibling directory whose name starts like outgoing is refused`() {
        val sibling = File(cache, "outgoing-old").apply { mkdirs() }
        val stray = File(sibling, "a.pdf").apply { writeText("x") }
        assertEquals(
            ComposedAttachmentSource.Unreadable,
            composedAttachmentSource(file(stray.absolutePath), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a link inside outgoing that points out of it is refused`() {
        val secret = File(cache, "secret.db").apply { writeText("s") }
        val link = File(outgoing, "link.pdf")
        Files.createSymbolicLink(link.toPath(), secret.toPath())
        assertEquals(
            ComposedAttachmentSource.Unreadable,
            composedAttachmentSource(file(link.absolutePath), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a blob with a JMAP sender is fetched from the server`() {
        assertEquals(
            ComposedAttachmentSource.ServerBlob,
            composedAttachmentSource(file(null, blob = "B1"), MailProtocol.JMAP, roots),
        )
    }

    @Test fun `a blob with an IMAP sender is refused before any fetch`() {
        assertEquals(
            ComposedAttachmentSource.OtherAccount,
            composedAttachmentSource(file(null, blob = "B1"), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a part with neither a staged file nor a blob is unreadable`() {
        assertEquals(
            ComposedAttachmentSource.Unreadable,
            composedAttachmentSource(file(null), MailProtocol.JMAP, roots),
        )
    }

    @Test fun `a reopened local draft's file under the local-draft directory is read back from it`() {
        val kept = File(File(localDrafts, "42"), "a.pdf").apply { parentFile.mkdirs(); writeText("pdf") }
        assertEquals(
            ComposedAttachmentSource.Staged(kept.canonicalFile),
            composedAttachmentSource(file(kept.absolutePath), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a sibling directory whose name starts like the local-draft directory is refused`() {
        val sibling = File(files, "$LOCAL_DRAFT_FILES_DIR-old").apply { mkdirs() }
        val stray = File(sibling, "a.pdf").apply { writeText("x") }
        assertEquals(
            ComposedAttachmentSource.Unreadable,
            composedAttachmentSource(file(stray.absolutePath), MailProtocol.IMAP, roots),
        )
    }

    @Test fun `a path that climbs out of the local-draft directory by dot-dot is refused`() {
        val secret = File(files, "accounts.db").apply { writeText("s") }
        val climbing = localDrafts.absolutePath + File.separator + ".." + File.separator + secret.name
        assertEquals(
            ComposedAttachmentSource.Unreadable,
            composedAttachmentSource(file(climbing), MailProtocol.IMAP, roots),
        )
    }
}

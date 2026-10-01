package app.sterna.ui.compose

import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagedThumbnailSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a file staged under a root is decoded from`() {
        val root = tmp.newFolder("outgoing")
        val file = java.io.File(root, "1-photo.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals(file.canonicalFile, stagedThumbnailSource(EmailBodyPart(partId = file.path), listOf(root)))
    }

    @Test fun `a server blob is never fetched for a preview`() {
        val root = tmp.newFolder("outgoing")
        assertNull(stagedThumbnailSource(EmailBodyPart(blobId = "Gblob"), listOf(root)))
    }

    @Test fun `a path outside the staging roots is not read`() {
        val root = tmp.newFolder("outgoing")
        val outside = tmp.newFile("elsewhere.jpg")
        assertNull(stagedThumbnailSource(EmailBodyPart(partId = outside.path), listOf(root)))
    }

    @Test fun `a path that cannot be canonicalised yields no preview instead of an exception`() {
        val root = tmp.newFolder("outgoing")
        val broken = java.io.File(root, "a\u0000b.jpg").path
        assertNull(stagedThumbnailSource(EmailBodyPart(partId = broken), listOf(root)))
    }
}

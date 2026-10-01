package app.sterna.ui.message

import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Save all" executed against a fake folder: which documents exist at the end, and what the final
 * word counts. The ViewModel only resolves the `Uri`s and turns the outcome into a sentence.
 */
class SaveAllAttachmentsTest {

    /** A folder that keeps what was written, and remembers what was created then discarded. */
    private class FakeFolder(
        private val refuse: Set<String> = emptySet(),
        private val failWrite: Set<String> = emptySet(),
        private val writeGates: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ) : AttachmentFolder<String> {
        val created = mutableListOf<String>()
        val mimes = mutableListOf<String>()
        val written = linkedMapOf<String, ByteArray>()
        val discarded = mutableListOf<String>()

        override suspend fun create(name: String, mime: String): String? {
            if (name in refuse) return null
            created += name
            mimes += mime
            return name
        }

        override suspend fun write(document: String, bytes: ByteArray) {
            if (document in failWrite) error("disk full")
            writeGates[document]?.await()
            written[document] = bytes
        }

        override suspend fun discard(document: String) {
            discarded += document
            written.remove(document)
        }

        /** The documents left in the folder: created and not discarded. */
        val remaining get() = created - discarded.toSet()
    }

    private fun part(name: String, key: String) =
        EmailBodyPart(blobId = key, name = name, type = "application/pdf", size = 10)

    private fun page(ownerId: String, vararg parts: EmailBodyPart) =
        listOf(ThreadMessage(ownerId, Email(id = ownerId), Email(id = ownerId, attachments = parts.toList())))

    private val abc = page("M1", part("a.pdf", "b1"), part("b.pdf", "b2"), part("c.pdf", "b3"))

    @Test fun `names that collide in the batch get a counter, before the extension, case-insensitively`() {
        assertEquals(
            listOf("photo.jpg", "photo (1).jpg", "Photo (2).JPG", "notes", "notes (1)", "other.pdf"),
            distinctFileNames(listOf("photo.jpg", "photo.jpg", "Photo.JPG", "notes", "notes", "other.pdf")),
        )
    }

    @Test fun `a generated name never lands on a name the batch already carries`() {
        val names = distinctFileNames(listOf("a.jpg", "a (1).jpg", "a.jpg"))
        assertEquals(listOf("a.jpg", "a (1).jpg", "a (2).jpg"), names)
        assertEquals(names.size, names.map { it.lowercase() }.toSet().size)
    }

    @Test fun `distinct names pass through untouched`() {
        assertEquals(listOf("a.pdf", "b.pdf", ".bashrc"), distinctFileNames(listOf("a.pdf", "b.pdf", ".bashrc")))
    }

    @Test fun `every part written is every part announced`() = runTest {
        val folder = FakeFolder()
        val progress = mutableListOf<Pair<Int, Int>>()
        val outcome = saveAllAttachmentsInto(abc, "M1", folder, { byteArrayOf(1, 2) }) { c, t -> progress += c to t }
        assertEquals(SaveAllOutcome.AllSaved(3), outcome)
        assertEquals(listOf("a.pdf", "b.pdf", "c.pdf"), folder.remaining)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(List(3) { "application/octet-stream" }, folder.mimes)
    }

    @Test fun `two attachments named photo jpg are written as photo jpg and photo (1) jpg, each with its own bytes`() = runTest {
        val folder = FakeFolder()
        val twins = page("M1", part("photo.jpg", "p1"), part("photo.jpg", "p2"))
        val bytes = mapOf("p1" to byteArrayOf(1, 1), "p2" to byteArrayOf(2, 2, 2))
        val outcome = saveAllAttachmentsInto(twins, "M1", folder, { p -> bytes.getValue(p.blobId!!) }) { _, _ -> }
        assertEquals(SaveAllOutcome.AllSaved(2), outcome)
        assertEquals(listOf("photo.jpg", "photo (1).jpg"), folder.written.keys.toList())
        assertEquals(listOf<Byte>(1, 1), folder.written.getValue("photo.jpg").toList())
        assertEquals(listOf<Byte>(2, 2, 2), folder.written.getValue("photo (1).jpg").toList())
    }

    @Test fun `a part whose download fails mid-batch creates no document, the next ones are still written`() = runTest {
        val folder = FakeFolder()
        val outcome = saveAllAttachmentsInto(abc, "M1", folder, { p ->
            if (p.blobId == "b2") throw ContentTooLargeException("too large", p.size, 1) else byteArrayOf(7)
        }) { _, _ -> }
        assertEquals(SaveAllOutcome.PartlySaved(saved = 2, total = 3), outcome)
        assertEquals(listOf("a.pdf", "c.pdf"), folder.created)
        assertEquals(emptyList<String>(), folder.discarded)
        assertEquals(listOf("a.pdf", "c.pdf"), folder.written.keys.toList())
    }

    @Test fun `zero bytes are a failed part, never an empty file counted as saved`() = runTest {
        val folder = FakeFolder()
        val outcome = saveAllAttachmentsInto(abc, "M1", folder, { p ->
            if (p.blobId == "b1") ByteArray(0) else byteArrayOf(7)
        }) { _, _ -> }
        assertEquals(SaveAllOutcome.PartlySaved(saved = 2, total = 3), outcome)
        assertEquals(listOf("b.pdf", "c.pdf"), folder.created)
        assertEquals(emptyList<String>(), folder.discarded)
    }

    @Test fun `a document the provider refuses to create counts as failed`() = runTest {
        val folder = FakeFolder(refuse = setOf("c.pdf"))
        val outcome = saveAllAttachmentsInto(abc, "M1", folder, { byteArrayOf(7) }) { _, _ -> }
        assertEquals(SaveAllOutcome.PartlySaved(saved = 2, total = 3), outcome)
    }

    @Test fun `a write that throws loses that document and is not counted`() = runTest {
        val folder = FakeFolder(failWrite = setOf("a.pdf"))
        val outcome = saveAllAttachmentsInto(abc, "M1", folder, { byteArrayOf(7) }) { _, _ -> }
        assertEquals(SaveAllOutcome.PartlySaved(saved = 2, total = 3), outcome)
        assertEquals(listOf("a.pdf"), folder.discarded)
        assertEquals(listOf("b.pdf", "c.pdf"), folder.remaining)
    }

    @Test fun `cancelled mid-download, no document is created for the part, the ones written stay`() = runTest {
        val folder = FakeFolder()
        val never = CompletableDeferred<ByteArray>()
        var reached = false
        val job = launch {
            saveAllAttachmentsInto(abc, "M1", folder, { p ->
                if (p.blobId == "b2") { reached = true; never.await() } else byteArrayOf(7)
            }) { _, _ -> }
        }
        runCurrent()
        assertTrue("the second download must be in flight before the cancel", reached)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf("a.pdf"), folder.created)
        assertEquals(emptyList<String>(), folder.discarded)
        assertEquals(listOf("a.pdf"), folder.written.keys.toList())
    }

    @Test fun `cancelled mid-write, the document in flight goes, the ones written stay`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val folder = FakeFolder(writeGates = mapOf("b.pdf" to gate))
        val job = launch {
            saveAllAttachmentsInto(abc, "M1", folder, { byteArrayOf(7) }) { _, _ -> }
        }
        runCurrent()
        assertEquals("the second write must be in flight before the cancel", listOf("a.pdf", "b.pdf"), folder.created)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(listOf("b.pdf"), folder.discarded)
        assertEquals(listOf("a.pdf"), folder.remaining)
        assertEquals(listOf("a.pdf"), folder.written.keys.toList())
    }

    @Test fun `a message no longer on the page creates nothing`() = runTest {
        val folder = FakeFolder()
        var fetched = 0
        val outcome = saveAllAttachmentsInto(abc, "M2", folder, { fetched++; byteArrayOf(7) }) { _, _ -> }
        assertEquals(SaveAllOutcome.Refused, outcome)
        assertEquals(emptyList<String>(), folder.created)
        assertEquals(0, fetched)
    }

    @Test fun `a message that lists no attachment any more creates nothing`() = runTest {
        val folder = FakeFolder()
        val outcome = saveAllAttachmentsInto(page("M1"), "M1", folder, { byteArrayOf(7) }) { _, _ -> }
        assertEquals(SaveAllOutcome.Refused, outcome)
        assertEquals(emptyList<String>(), folder.created)
    }

    @Test fun `an empty owner id is refused even if a message carries it`() = runTest {
        val folder = FakeFolder()
        val outcome = saveAllAttachmentsInto(page("", part("a.pdf", "b1")), "", folder, { byteArrayOf(7) }) { _, _ -> }
        assertEquals(SaveAllOutcome.Refused, outcome)
        assertEquals(emptyList<String>(), folder.created)
    }
}

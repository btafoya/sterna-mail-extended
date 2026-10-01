package app.sterna.ui.message

import app.sterna.core.data.text.attachmentKind
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MessageThumbnailsTest {

    private val mb = 1024L * 1024

    private fun image(id: String, size: Long) = EmailBodyPart(blobId = id, type = "image/jpeg", name = "$id.jpg", size = size)

    private fun select(vararg parts: EmailBodyPart): List<String?> =
        thumbnailsToFetch(parts.toList()) { attachmentKind(it.type, it.name) { null } }.map { it.blobId }

    @Test fun `an image under the ceiling is fetched and a pdf is not`() {
        val pdf = EmailBodyPart(blobId = "doc", type = "application/pdf", name = "doc.pdf", size = 200_000)
        assertEquals(listOf("photo"), select(pdf, image("photo", 300_000)))
    }

    @Test fun `an image of unknown size is not fetched`() {
        assertEquals(
            "a size of 0 is the server not saying; DownloadLimits would let it through",
            listOf("known"),
            select(image("unknown", 0), image("known", 1_000)),
        )
    }

    @Test fun `an image past the inline ceiling is not fetched, and does not stop the next one`() {
        assertEquals(
            listOf("edge", "small"),
            select(
                image("edge", DownloadLimits.INLINE_IMAGE_MAX_BYTES),
                image("big", DownloadLimits.INLINE_IMAGE_MAX_BYTES + 1),
                image("small", 1_000),
            ),
        )
    }

    @Test fun `the third of three 10 MB pictures would pass the message's budget and is not fetched`() {
        assertEquals(
            listOf("a", "b"),
            select(image("a", 10 * mb), image("b", 10 * mb), image("c", 10 * mb), image("d", 1_000)),
        )
    }

    @Test fun `the message's order is kept`() {
        assertEquals(listOf("z", "a", "m"), select(image("z", 10), image("a", 20), image("m", 30)))
    }

    @Test fun `a download that fails on the first picture still lets the second through, saying nothing`() = runBlocking {
        val kept = mutableListOf<Pair<String, String>>()
        val asked = mutableListOf<String?>()
        loadThumbnails(
            listOf(image("first", 10), image("second", 10)),
            download = { part ->
                asked += part.blobId
                if (part.blobId == "first") throw IOException("offline")
                byteArrayOf(1)
            },
            decode = { "decoded" },
            keep = { key, image -> kept += key to image },
        )
        assertEquals(listOf("first", "second"), asked)
        assertEquals(listOf("second" to "decoded"), kept)
    }

    @Test fun `a picture that will not decode keeps its icon and the next one is still tried`() = runBlocking {
        val kept = mutableListOf<String>()
        loadThumbnails(
            listOf(image("broken", 10), image("throws", 10), image("fine", 10)),
            download = { part -> part.blobId!!.toByteArray() },
            decode = { bytes ->
                when (String(bytes)) {
                    "broken" -> null
                    "throws" -> throw OutOfMemoryError()
                    else -> "ok"
                }
            },
            keep = { key, _ -> kept += key },
        )
        assertEquals(listOf("fine"), kept)
    }

    @Test fun `cancellation is not swallowed as a failed picture`() {
        val asked = mutableListOf<String?>()
        try {
            runBlocking {
                loadThumbnails(
                    listOf(image("first", 10), image("second", 10)),
                    download = { part ->
                        asked += part.blobId
                        throw CancellationException("left the message")
                    },
                    decode = { "decoded" },
                    keep = { _, _ -> fail("nothing may be kept after a cancellation") },
                )
            }
            fail("the cancellation must reach the caller")
        } catch (_: CancellationException) {
        }
        assertEquals("the loop must stop at the cancellation", listOf("first"), asked)
    }
}

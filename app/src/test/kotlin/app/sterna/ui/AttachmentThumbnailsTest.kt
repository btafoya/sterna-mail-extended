package app.sterna.ui

import app.sterna.core.data.text.AttachmentKind
import app.sterna.core.jmap.model.EmailBodyPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentThumbnailsTest {

    @Test fun `an image staged on the phone is decoded under its file path`() {
        val part = EmailBodyPart(partId = "/cache/outgoing/1-photo.jpg", type = "image/jpeg", name = "photo.jpg")
        assertEquals("/cache/outgoing/1-photo.jpg", thumbnailKeyFor(part, AttachmentKind.IMAGE))
    }

    @Test fun `an image uploaded to the server is decoded under its blob id`() {
        val part = EmailBodyPart(blobId = "Gblob42", type = "image/png", name = "a.png")
        assertEquals("Gblob42", thumbnailKeyFor(part, AttachmentKind.IMAGE))
    }

    @Test fun `the part's own identity wins over its name and the file path over the blob`() {
        val part = EmailBodyPart(partId = "/cache/outgoing/2-x.png", blobId = "Gblob7", name = "x.png")
        assertEquals("/cache/outgoing/2-x.png", thumbnailKeyFor(part, AttachmentKind.IMAGE))
        assertEquals(
            "the screen looks the thumbnail up by the same key the view model stores it under",
            thumbnailKeyFor(part, AttachmentKind.IMAGE),
            attachmentKey(part),
        )
    }

    @Test fun `anything but an image keeps its icon`() {
        val part = EmailBodyPart(blobId = "Gblob1", type = "application/pdf", name = "a.pdf")
        assertNull(thumbnailKeyFor(part, AttachmentKind.PDF))
        assertNull(thumbnailKeyFor(part, AttachmentKind.OTHER))
    }

    @Test fun `a part with no identity is not decoded`() {
        assertNull(thumbnailKeyFor(EmailBodyPart(type = "image/jpeg", name = "a.jpg"), AttachmentKind.IMAGE))
    }

    @Test fun `a decoder that throws yields no thumbnail instead of an error`() {
        assertNull(decodedOrNull<String> { throw IllegalArgumentException("unknown format") })
        assertNull(decodedOrNull<String> { throw OutOfMemoryError("huge picture") })
        assertEquals("ok", decodedOrNull { "ok" })
    }

    // In the JVM the Android stub throws and the guard swallows it: this pins the guard, not the
    // decoder. The proof of the silent failure is the throwing decoder above.
    @Test fun `arbitrary bytes and a missing file decode to null without throwing`() {
        assertNull(decodeThumbnail(byteArrayOf(1, 2, 3, 4, 5), 120))
        assertNull(decodeThumbnail(ByteArray(0), 120))
        assertNull(decodeThumbnail(File("/nonexistent/thumbnail-source.jpg"), 120))
    }

    @Test fun `a removed part lets its thumbnail go unless another row shares its key`() {
        val photo = EmailBodyPart(blobId = "Gsame", name = "a.jpg")
        val twin = EmailBodyPart(blobId = "Gsame", name = "b.jpg")
        val other = EmailBodyPart(partId = "/cache/outgoing/3-c.jpg", name = "c.jpg")
        assertEquals("Gsame", thumbnailKeyToForget(photo, listOf(other)))
        assertNull(thumbnailKeyToForget(photo, listOf(twin, other)))
        assertNull(thumbnailKeyToForget(EmailBodyPart(name = "x.jpg"), emptyList()))
    }

    @Test fun `a photo is subsampled by its short side`() {
        assertEquals(16, thumbnailSampleSize(4000, 3000, 140))
        assertEquals(16, thumbnailSampleSize(3000, 4000, 140))
        assertEquals(1, thumbnailSampleSize(100, 80, 140))
    }

    @Test fun `a panorama is also bounded by its long side`() {
        val sample = thumbnailSampleSize(100_000, 500, 140)
        assertEquals(128, sample)
        assertTrue(
            "long side decoded at ${100_000 / sample} px, over $MAX_DECODED_ELONGATION x 140",
            100_000 / sample <= MAX_DECODED_ELONGATION * 140,
        )
        assertEquals(8, MAX_DECODED_ELONGATION)
    }

    @Test fun `a target of zero still terminates`() {
        assertEquals(2048, thumbnailSampleSize(4000, 3000, 0))
    }

    @Test fun `each EXIF orientation turns the picture back upright`() {
        assertEquals(ExifTransform(0, false), exifTransform(1))
        assertEquals(ExifTransform(0, true), exifTransform(2))
        assertEquals(ExifTransform(180, false), exifTransform(3))
        assertEquals(ExifTransform(180, true), exifTransform(4))
        assertEquals(ExifTransform(90, true), exifTransform(5))
        assertEquals(ExifTransform(90, false), exifTransform(6))
        assertEquals(ExifTransform(270, true), exifTransform(7))
        assertEquals(ExifTransform(270, false), exifTransform(8))
        assertEquals("undefined or unknown values leave it as stored", ExifTransform(0, false), exifTransform(0))
        assertEquals(ExifTransform(0, false), exifTransform(42))
    }

    @Test fun `the preview cache holds a few megabytes at most`() {
        assertEquals(4 * 1024 * 1024, THUMBNAIL_CACHE_BYTES)
    }
}

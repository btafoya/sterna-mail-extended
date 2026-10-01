package app.sterna.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.sterna.contacts.sampleSize
import app.sterna.core.data.text.AttachmentKind
import app.sterna.core.jmap.model.EmailBodyPart
import java.io.ByteArrayInputStream
import java.io.File

/*
 * Small square previews of image attachments, decoded from bytes or a file already on the phone.
 *
 * Thumbnails live in memory only and nothing here writes a byte anywhere: an attachment may be the
 * plaintext of a message about to be encrypted, and the app promises that decrypted content is
 * never written to disk.
 */

/** Side of a thumbnail on screen, in dp; also the size it is decoded to. */
internal const val THUMBNAIL_DP = 40

/** The identity an attachment row is known by: its staged file, or else its server blob. */
internal fun attachmentKey(part: EmailBodyPart): String? = part.partId ?: part.blobId

/**
 * The key whose thumbnail may go once [part] is off the list, [remaining] being what is left on it;
 * null while another row still shows it, since a JMAP server may hand the same blob id to the same
 * picture attached twice.
 */
internal fun thumbnailKeyToForget(part: EmailBodyPart, remaining: List<EmailBodyPart>): String? {
    val key = attachmentKey(part) ?: return null
    return key.takeIf { remaining.none { attachmentKey(it) == key } }
}

/**
 * Previews bounded by their bytes, about fifty 40 dp squares at the densest screens (140 px, 78 KB
 * each): a message with dozens of photos keeps the most recent ones and the rest show their icon.
 */
internal const val THUMBNAIL_CACHE_BYTES = 4 * 1024 * 1024

internal fun newThumbnailCache(): LruCache<String, ImageBitmap> =
    object : LruCache<String, ImageBitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

/** The key to decode [part] under, or null when its [kind] is not an image or it has no identity. */
internal fun thumbnailKeyFor(part: EmailBodyPart, kind: AttachmentKind): String? =
    if (kind == AttachmentKind.IMAGE) attachmentKey(part) else null

/**
 * [decode]'s result, or null when it throws, out-of-memory included: a thumbnail is a nicety and
 * must never turn into an error on the attachment it stands for.
 */
internal inline fun <T> decodedOrNull(decode: () -> T?): T? = runCatching(decode).getOrNull()

/** [bytes] as a [maxPx]-square thumbnail, or null when they are not a picture this phone can read. */
internal fun decodeThumbnail(bytes: ByteArray, maxPx: Int): ImageBitmap? = decodedOrNull {
    val orientation = exifOrientation { ExifInterface(ByteArrayInputStream(bytes)) }
    decodeTwice(maxPx, orientation) { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }
}

/** [file] as a [maxPx]-square thumbnail, or null when it is missing or not a readable picture. */
internal fun decodeThumbnail(file: File, maxPx: Int): ImageBitmap? = decodedOrNull {
    val orientation = exifOrientation { ExifInterface(file.path) }
    decodeTwice(maxPx, orientation) { options -> BitmapFactory.decodeFile(file.path, options) }
}

/** How far a picture stored sideways must turn, and whether it is mirrored, to stand as it was taken. */
internal data class ExifTransform(val degrees: Int, val mirrored: Boolean)

private val UPRIGHT = ExifTransform(0, false)

/**
 * The turn an EXIF `Orientation` value asks for; rotation first, then the mirror, as the matrix
 * applies them. `BitmapFactory` ignores the tag, so a portrait photo from a camera reads sideways.
 */
internal fun exifTransform(orientation: Int): ExifTransform = when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
    ExifInterface.ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true)
    ExifInterface.ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
    ExifInterface.ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
    ExifInterface.ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
    ExifInterface.ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
    else -> UPRIGHT
}

/** Longest decoded side, as a multiple of the thumbnail's own: bounds what a panorama costs. */
internal const val MAX_DECODED_ELONGATION = 8

/**
 * Power-of-two subsampling for a [width] x [height] picture shown as a [targetPx] square. The short
 * side, which the square crop keeps, is brought near [targetPx]; the long side is then held under
 * [MAX_DECODED_ELONGATION] times it, so a 100000 x 500 panorama is never decoded to 50 MB.
 */
internal fun thumbnailSampleSize(width: Int, height: Int, targetPx: Int): Int {
    val target = targetPx.coerceAtLeast(1)
    val shortest = minOf(width, height)
    var sample = sampleSize(shortest, shortest, target)
    while (maxOf(width, height) / sample > MAX_DECODED_ELONGATION * target) sample *= 2
    return sample
}

// A picture whose EXIF cannot be read is shown as stored rather than not at all.
private inline fun exifOrientation(open: () -> ExifInterface): Int =
    runCatching { open().getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        .getOrDefault(ExifInterface.ORIENTATION_NORMAL)

// The first pass reads the header only, so a 50-megapixel photo is never decoded whole: the second
// subsamples it while reading.
private inline fun decodeTwice(maxPx: Int, orientation: Int, read: (BitmapFactory.Options) -> Bitmap?): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    read(bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, maxPx)
    }
    val decoded = read(options) ?: return null
    return oriented(centerSquare(decoded, maxPx), exifTransform(orientation)).asImageBitmap()
}

private fun centerSquare(source: Bitmap, maxPx: Int): Bitmap {
    val side = minOf(source.width, source.height)
    val square = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    return if (side > maxPx) Bitmap.createScaledBitmap(square, maxPx, maxPx, true) else square
}

private fun oriented(square: Bitmap, transform: ExifTransform): Bitmap {
    if (transform == UPRIGHT) return square
    val matrix = Matrix().apply {
        setRotate(transform.degrees.toFloat())
        if (transform.mirrored) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(square, 0, 0, square.width, square.height, matrix, true)
}

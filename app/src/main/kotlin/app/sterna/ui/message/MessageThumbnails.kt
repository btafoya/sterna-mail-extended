package app.sterna.ui.message

import app.sterna.core.data.text.AttachmentKind
import app.sterna.core.jmap.DownloadLimits
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.attachmentKey
import kotlinx.coroutines.CancellationException

/*
 * Previews of a received message's image attachments, fetched without her asking, as the inline
 * images are and on any network like them.
 *
 * Each picture is downloaded to memory, decoded to its thumbnail and its bytes let go: nothing goes
 * through the attachment cache, which only the open path may fill, because the part may be the
 * plaintext of an encrypted message. A preview that cannot be had leaves the icon and says nothing;
 * the row still opens the real file, and that path reports its own failures.
 *
 * `MessageViewModel` starts the fetch from `publishBody`, the one place every shown body passes
 * through, and only once the page has been settled on (`loadedId`): a neighbour painted from the
 * cache while the pager pre-composes it costs no download.
 */

/**
 * What all the previews of one message may download together. Sizes are as announced: one picture
 * past this stops the list, so the photos shown are the first ones, in the message's order.
 */
internal const val THUMBNAIL_MESSAGE_BUDGET_BYTES = 25L * 1024 * 1024

/**
 * The attachments of [parts] worth a preview: images, by [kindOf], of a known size no larger than
 * an inline image may be. An unknown size is left out rather than trusted, since `DownloadLimits`
 * lets one through and only refuses it once the bytes are already arriving.
 */
internal fun thumbnailsToFetch(
    parts: List<EmailBodyPart>,
    kindOf: (EmailBodyPart) -> AttachmentKind,
): List<EmailBodyPart> {
    val picked = mutableListOf<EmailBodyPart>()
    var total = 0L
    for (part in parts) {
        if (kindOf(part) != AttachmentKind.IMAGE) continue
        if (part.size <= 0L) continue
        if (part.size > DownloadLimits.INLINE_IMAGE_MAX_BYTES) continue
        if (total + part.size > THUMBNAIL_MESSAGE_BUDGET_BYTES) break
        total += part.size
        picked += part
    }
    return picked
}

/**
 * Fetch and decode [parts] one after the other, handing each preview to [keep] under the part's
 * key. A part that fails, to download or to decode, is skipped and the next one tried; cancellation
 * alone stops the loop.
 */
internal suspend fun <T : Any> loadThumbnails(
    parts: List<EmailBodyPart>,
    download: suspend (EmailBodyPart) -> ByteArray,
    decode: suspend (ByteArray) -> T?,
    keep: (String, T) -> Unit,
) {
    for (part in parts) {
        val key = attachmentKey(part) ?: continue
        val image = try {
            decode(download(part))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } ?: continue
        keep(key, image)
    }
}

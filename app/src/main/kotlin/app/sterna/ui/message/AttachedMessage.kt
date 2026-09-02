package app.sterna.ui.message

import app.sterna.core.data.text.htmlToText
import app.sterna.core.imap.MimeAttachment
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.ContentTooLargeException

/** What the sheet shows of a message/rfc822 attachment. */
data class AttachedMessage(
    val from: String?,
    val to: String?,
    val cc: String?,
    val date: String?,
    val subject: String?,
    /** "" when the message has no text nor html. */
    val body: String,
    val attachmentNames: List<String>,
)

/** Which attachment opens as an attached message rather than being handed to a viewer app. */
internal fun isAttachedMessage(type: String?): Boolean =
    type?.substringBefore(';')?.trim().equals("message/rfc822", ignoreCase = true)

/**
 * Parses the downloaded bytes of a message/rfc822 part. The bytes are turned into MimeParser's
 * faithful byte container (ISO-8859-1, one char per wire byte — see MimeParser's KDoc, #128), then
 * [MimeParser.parseBody] applies its own ceilings (MAX_BODY_CHARS, MAX_PARTS, MAX_DEPTH).
 *
 * The headers go through [MimeParser.decodedHeaderOf] and nothing else: it is the one sanitising
 * chain (RFC 2047, raw 8-bit bytes, bidi overrides, control characters), and the headers of an
 * attached message are sender-controlled text bound for the screen, exactly like the envelope's.
 *
 * @throws ContentTooLargeException when parseBody refuses the source (tooLarge).
 */
internal fun attachedMessageOf(bytes: ByteArray): AttachedMessage {
    val raw = bytes.toString(Charsets.ISO_8859_1)
    val parsed = MimeParser.parseBody(raw)
    if (parsed.tooLarge) {
        throw ContentTooLargeException(
            "attached message is too large",
            raw.length.toLong(),
            MimeParser.MAX_BODY_CHARS.toLong(),
        )
    }
    return AttachedMessage(
        from = MimeParser.decodedHeaderOf(raw, "From"),
        to = MimeParser.decodedHeaderOf(raw, "To"),
        cc = MimeParser.decodedHeaderOf(raw, "Cc"),
        date = MimeParser.decodedHeaderOf(raw, "Date"),
        subject = MimeParser.decodedHeaderOf(raw, "Subject"),
        body = parsed.text ?: parsed.html?.let { htmlToText(it) } ?: "",
        attachmentNames = parsed.attachments.filter { it.isFileAttachment() }.map { it.name },
    )
}

/**
 * The reader's own rule (`Email.fileAttachmentParts`), applied to what [MimeParser] collects: an
 * inline image (referenced by its Content-ID), a calendar invite and the PGP/MIME control parts
 * are kept in `attachments` so the reader can fetch them, and filtered out of its file list
 * again. Without the same filter here, a newsletter forwarded as an .eml would read
 * "Attachments: image, image, image" and a signed one "pgp-signature".
 */
private fun MimeAttachment.isFileAttachment(): Boolean = when {
    cid != null && type.startsWith("image/") -> false
    type.startsWith("text/calendar") -> false
    type == "application/pgp-encrypted" || type == "application/pgp-signature" -> false
    else -> true
}

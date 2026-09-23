package app.sterna.core.data.mail

import app.sterna.core.imap.MimeBody
import app.sterna.core.imap.MimeParser
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.core.jmap.model.EmailBodyValue

internal class EntityDisplay(val email: Email, val inlineImages: Map<String, String>)

/**
 * [envelope]'s headers around a body, attachments and inline images read from [entity] alone: what was
 * decrypted, or what a detached signature covers. Nothing else of the source reaches the screen, since a
 * sender can add parts to a `multipart/signed` beside the entity the signature is valid for.
 *
 * Parts are marked "pgp:" and numbered inside [entity]: `pgpAttachmentBytes` slices them out of it, so the
 * caller keeps [entity] next to the result.
 */
internal fun displayOfEntity(envelope: Email, entity: String): EntityDisplay {
    val body = MimeParser.parseBody(entity)
    // Every body field the reader falls back on is emptied: the envelope's were read from the whole source.
    val bare = envelope.copy(htmlBody = emptyList(), textBody = emptyList(), bodyValues = emptyMap(), preview = null)
    val display = bare.withBody(body).run {
        copy(
            attachments = attachments.map { part -> part.copy(partId = part.partId?.let { "pgp:$it" }) },
            hasAttachment = body.attachments.any { it.cid == null },
        )
    }
    val inline = display.inlineImageParts().mapNotNull { part ->
        val section = part.partId?.removePrefix("pgp:") ?: return@mapNotNull null
        val cid = part.cid?.trim()?.trim('<', '>')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val (cte, encoded) = MimeParser.partAt(entity, section) ?: return@mapNotNull null
        val bytes = MimeParser.decodeBytes(encoded, cte)
        cid to "data:${part.type ?: "image/jpeg"};base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
    }.toMap()
    return EntityDisplay(display, inline)
}

internal fun Email.withBody(body: MimeBody): Email {
    val attachments = body.attachments.map {
        val isInlineImage = !it.cid.isNullOrBlank() && it.type.startsWith("image/")
        EmailBodyPart(
            partId = it.section,
            name = it.name,
            type = it.type,
            size = it.size.toLong(),
            cid = it.cid,
            disposition = if (isInlineImage) "inline" else "attachment",
            encoding = it.encoding,
        )
    }
    val html = body.html
    val text = body.text
    return when {
        // The type must be set: htmlContent() only returns a part typed text/html
        // (issue #4 hardening), so a type-less part renders as an empty body.
        !html.isNullOrBlank() -> copy(
            htmlBody = listOf(EmailBodyPart(partId = "html", type = "text/html")),
            bodyValues = mapOf("html" to EmailBodyValue(value = html)),
            attachments = attachments,
        )
        !text.isNullOrBlank() -> copy(
            textBody = listOf(EmailBodyPart(partId = "text", type = "text/plain")),
            bodyValues = mapOf("text" to EmailBodyValue(value = text)),
            attachments = attachments,
        )
        else -> copy(attachments = attachments)
    }
}

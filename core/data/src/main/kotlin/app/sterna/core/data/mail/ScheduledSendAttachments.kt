package app.sterna.core.data.mail

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.jmap.model.EmailBodyPart
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/** A server-held attachment could not be fetched back to be scheduled; the composer names it. */
class ScheduledAttachmentFetchFailed(val name: String?, cause: Throwable) :
    Exception("Couldn't fetch the attachment ${name ?: "attachment"} to schedule it.", cause)

/**
 * Copies every attachment into its own directory under [root], then hands the descriptors to
 * [insert]. The directory is named before the row exists, so the row is written once, already
 * complete: a row inserted first and filled after would, on a process death in between, be
 * re-armed at startup and fire without its attachments. A throw from either half deletes the
 * directory, and no row exists.
 *
 * A blob is fetched back into a file here and never kept as a blob id: RFC 8620 § 6 lets a server
 * drop an unreferenced blob after about an hour, long before most scheduled hours.
 */
internal suspend fun insertScheduledWithAttachments(
    root: File,
    attachments: List<EmailBodyPart>,
    fetchBlob: suspend (EmailBodyPart) -> ByteArray,
    insert: suspend (attachmentsJson: String) -> Long,
): Long {
    val dir = File(root, UUID.randomUUID().toString())
    try {
        val stored = stageScheduledAttachments(dir, attachments, fetchBlob)
        return insert(OutboxAttachments.encode(stored))
    } catch (t: Throwable) {
        dir.deleteRecursively()
        throw t
    }
}

private suspend fun stageScheduledAttachments(
    dir: File,
    attachments: List<EmailBodyPart>,
    fetchBlob: suspend (EmailBodyPart) -> ByteArray,
): List<OutboxAttachment> {
    if (attachments.isEmpty()) return emptyList()
    dir.mkdirs()
    return attachments.map { part ->
        val bytes = when {
            part.partId != null -> runCatching { File(part.partId!!).readBytes() }.getOrNull()
                ?: error("Couldn't read the attachment ${part.name ?: part.partId} to schedule it.")
            part.blobId != null -> try {
                fetchBlob(part)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                throw ScheduledAttachmentFetchFailed(part.name, t)
            }
            // Refused, where the outbox's own staging skips it: skipped, it would fire amputated.
            else -> error("The attachment ${part.name ?: ""} has nothing to schedule.")
        }
        val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
        OutboxAttachment(
            kind = OutboxAttachments.KIND_IMAP_FILE,
            path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
            cid = part.cid, disposition = part.disposition,
        )
    }
}

/**
 * The parts a scheduled row hands to `enqueueSend` at its hour. IMAP names the scheduled files,
 * which `enqueueSend` copies into the outbox; JMAP uploads each one again for a fresh blob id.
 * Any attachment that cannot be read or uploaded throws: the worker counts it as a failed send,
 * never hands over a message short of it.
 */
internal suspend fun scheduledPartsToSend(
    stored: List<OutboxAttachment>,
    protocol: MailProtocol,
    upload: suspend (bytes: ByteArray, attachment: OutboxAttachment) -> EmailBodyPart,
): List<EmailBodyPart> {
    val parts = mutableListOf<EmailBodyPart>()
    for (a in stored) {
        check(a.kind == OutboxAttachments.KIND_IMAP_FILE) { "Unknown scheduled attachment kind ${a.kind}" }
        val path = a.path ?: error("The scheduled attachment ${a.name ?: ""} has no file.")
        if (protocol == MailProtocol.IMAP) {
            check(File(path).isFile) { "Couldn't read the attachment ${a.name ?: path} to send it." }
            parts += EmailBodyPart(
                partId = path, type = a.type, size = a.size, name = a.name,
                disposition = a.disposition ?: "attachment", cid = a.cid,
            )
        } else {
            val bytes = runCatching { File(path).readBytes() }.getOrNull()
                ?: error("Couldn't read the attachment ${a.name ?: path} to send it.")
            parts += upload(bytes, a)
        }
    }
    return parts
}

/**
 * Deletes the directories holding [attachmentsJson]'s files, and only those that sit directly
 * under [root]: the paths come from a database row, and a row is not trusted to name what may be
 * deleted.
 */
internal fun deleteScheduledAttachments(attachmentsJson: String, root: File) {
    val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return
    OutboxAttachments.decode(attachmentsJson)
        .mapNotNull { a -> a.path?.let { File(it).parentFile } }
        .distinct()
        .filter { dir -> runCatching { dir.parentFile?.canonicalPath == rootPath }.getOrDefault(false) }
        .forEach { dir -> runCatching { dir.deleteRecursively() } }
}

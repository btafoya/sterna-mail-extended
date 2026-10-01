package app.sterna.ui.message

import app.sterna.core.data.mail.requireAttachmentBytes
import app.sterna.core.data.text.attachmentFileName
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CancellationException

/** Where "Save all" writes: one document per part, in the folder she picked. [D] is the document's
 *  handle, a `Uri` in the app, anything in a test. */
internal interface AttachmentFolder<D> {
    suspend fun create(name: String, mime: String): D?
    suspend fun write(document: D, bytes: ByteArray)
    suspend fun discard(document: D)
}

internal sealed interface SaveAllOutcome {
    /** Nothing was created: the message is gone from the page, or lists no attachment any more. */
    data object Refused : SaveAllOutcome
    data class AllSaved(val count: Int) : SaveAllOutcome
    data class PartlySaved(val saved: Int, val total: Int) : SaveAllOutcome
}

/**
 * The part of a "Save all" being written, null in `MessageViewModel.saveAllProgress` when none is.
 * `MessageViewModel.load` keeps it across a reload of the same [ownerId]: that batch is still
 * writing, and clearing it would re-enable the button under it. The batch's own `finally` clears it.
 */
data class SaveAllProgress(val ownerId: String, val current: Int, val total: Int)

/**
 * [names] made distinct within the batch, in order: a repeat gets ` (1)`, ` (2)`… before its
 * extension. Compared case-insensitively, because the shared storage most pickers land on folds
 * case, and a provider that renames a clash on its own does so only against files already there,
 * not against the documents of a batch it does not know about.
 */
internal fun distinctFileNames(names: List<String>): List<String> {
    val taken = HashSet<String>()
    return names.map { name ->
        var candidate = name
        if (candidate.lowercase() in taken) {
            val dot = name.lastIndexOf('.')
            val trunk = if (dot > 0) name.substring(0, dot) else name
            val extension = if (dot > 0) name.substring(dot) else ""
            var n = 1
            while (candidate.lowercase() in taken) candidate = "$trunk (${n++})$extension"
        }
        taken += candidate.lowercase()
        candidate
    }
}

/**
 * "Save all": every file attachment of the message [ownerId] on this page, one after the other, each
 * into its own document of [folder]. Re-resolved here and not handed down by the screen, because the
 * folder picker is another activity and the page may have moved on, or died, behind it.
 *
 * A part that fails takes its own document with it and the batch goes on; the outcome counts only
 * the documents actually written. One part's bytes are held at a time.
 */
internal suspend fun <D> saveAllAttachmentsInto(
    messages: List<ThreadMessage>,
    ownerId: String,
    folder: AttachmentFolder<D>,
    fetch: suspend (EmailBodyPart) -> ByteArray,
    onPartFailed: (EmailBodyPart, Throwable) -> Unit = { _, _ -> },
    onProgress: (current: Int, total: Int) -> Unit,
): SaveAllOutcome {
    val parts = messages.firstOrNull { it.id == ownerId }?.body?.fileAttachmentParts().orEmpty()
    if (ownerId.isEmpty() || parts.isEmpty()) return SaveAllOutcome.Refused
    val names = distinctFileNames(parts.map { attachmentFileName(it.name) })
    var saved = 0
    parts.forEachIndexed { index, part ->
        onProgress(index + 1, parts.size)
        var document: D? = null
        try {
            // Fetched before the document exists: created first, it sits empty on disk for the whole download, and a dead process leaves it there.
            val bytes = fetch(part)
            requireAttachmentBytes(ownerId, part.partId ?: part.blobId.orEmpty(), bytes)
            // Never `part.type`: the local storage provider appends the type's extension when the name's differs, `report.csv` sent as text/plain becomes `report.csv.txt`.
            document = folder.create(names[index], "application/octet-stream")
                ?: error("the provider created no document")
            folder.write(document, bytes)
            saved++
        } catch (cancelled: CancellationException) {
            document?.let { folder.discard(it) }
            throw cancelled
        } catch (t: Throwable) {
            document?.let { folder.discard(it) }
            onPartFailed(part, t)
        }
    }
    return if (saved == parts.size) SaveAllOutcome.AllSaved(saved) else SaveAllOutcome.PartlySaved(saved, parts.size)
}

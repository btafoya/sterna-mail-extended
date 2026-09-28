package app.sterna.ui.compose

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.net.isOfflineFailure
import java.io.File

/** Where the bytes of an attachment on the composer's list are read back from to open it. */
internal sealed interface ComposedAttachmentSource {
    /** Staged on this phone, under the composer's own `outgoing/` directory. */
    data class Staged(val file: File) : ComposedAttachmentSource

    /** Uploaded to the sending JMAP account's blob store. */
    data object ServerBlob : ComposedAttachmentSource

    /**
     * A blob while the sender is IMAP: the blob belongs to another account, and the IMAP download
     * would ignore it and fetch nothing that is this attachment.
     */
    data object OtherAccount : ComposedAttachmentSource

    data object Unreadable : ComposedAttachmentSource
}

/**
 * Asked before any byte is read or fetched. A `partId` is honoured only when its canonical path lies
 * under one of [stagingRoots]: the viewer gets a copy of whatever is read here, so a path that
 * climbs out by `..` or a link would hand another app a file the composer never staged. The roots
 * are the composer's `outgoing/` and the local-draft directory a reopened local draft points into.
 */
internal fun composedAttachmentSource(
    part: EmailBodyPart,
    senderProtocol: MailProtocol,
    stagingRoots: List<File>,
): ComposedAttachmentSource {
    part.partId?.let { path ->
        val file = File(path).canonicalFile
        // A separator on the end, so a sibling named like a root ("outgoing-old") is not inside it.
        val inside = stagingRoots.any { root ->
            file.path.startsWith(root.canonicalPath.trimEnd(File.separatorChar) + File.separatorChar)
        }
        return if (inside) ComposedAttachmentSource.Staged(file) else ComposedAttachmentSource.Unreadable
    }
    return when {
        part.blobId == null -> ComposedAttachmentSource.Unreadable
        senderProtocol == MailProtocol.JMAP -> ComposedAttachmentSource.ServerBlob
        else -> ComposedAttachmentSource.OtherAccount
    }
}

/**
 * The sentence for an open that failed. "Offline" only when connectivity confirms it, like `attach`:
 * a server that refused is not the phone being offline.
 */
@StringRes
internal fun composedAttachmentOpenFailure(t: Throwable, online: Boolean): Int =
    when {
        t is ContentTooLargeException -> R.string.status_attachment_too_large
        isOfflineFailure(t, online) -> R.string.status_action_offline
        else -> R.string.status_open_attachment_failed
    }

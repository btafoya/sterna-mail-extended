package app.sterna.ui.compose

import app.sterna.core.data.account.MailProtocol
import app.sterna.core.jmap.model.EmailBodyPart
import java.io.File

/**
 * The file a preview of [part] may be decoded from: only one already staged on this phone under
 * [stagingRoots]. A preview never costs a download, so a server blob keeps its icon; the protocol
 * passed only chooses between outcomes for a blob, none of which is decoded. Null, never an
 * exception, when `composedAttachmentSource` cannot canonicalise the path: a preview that cannot be
 * located is an icon, not a crash of the composer.
 */
internal fun stagedThumbnailSource(part: EmailBodyPart, stagingRoots: List<File>): File? = runCatching {
    (composedAttachmentSource(part, MailProtocol.JMAP, stagingRoots) as? ComposedAttachmentSource.Staged)?.file
}.getOrNull()

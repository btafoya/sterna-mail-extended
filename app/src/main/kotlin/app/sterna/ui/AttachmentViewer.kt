package app.sterna.ui

import android.app.Application
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.sterna.R
import app.sterna.core.data.storage.StorageRepository
import app.sterna.core.data.text.attachmentMimeType

/** [bytes] in the shareable cache (`attachments/`), as the content Uri the FileProvider exposes it under. */
internal suspend fun stageForSharing(app: Application, storage: StorageRepository, name: String?, bytes: ByteArray): android.net.Uri =
    FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", storage.cacheAttachment(name, bytes))

/**
 * Copy [bytes] into the shareable cache and offer the copy to a viewer app, for the reader and the
 * composer alike. Only `attachments/` is exposed by the FileProvider: whatever a caller read the
 * bytes from, no other directory is ever handed out.
 */
internal suspend fun handAttachmentToViewer(
    app: Application,
    storage: StorageRepository,
    name: String?,
    type: String?,
    bytes: ByteArray,
) {
    val uri = stageForSharing(app, storage, name, bytes)
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachmentMimeType(type, name, MimeTypeMap.getSingleton()::getMimeTypeFromExtension))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // unguarded: not a tap. Each caller holds its own double-tap guard over the whole open, taken
    // where the tap lands; there is no composition here to hang the leave guard on.
    app.startActivity(
        Intent.createChooser(view, app.getString(R.string.status_open_attachment)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

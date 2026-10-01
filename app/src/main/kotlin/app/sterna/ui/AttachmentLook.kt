package app.sterna.ui

import android.webkit.MimeTypeMap
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.sterna.R
import app.sterna.core.data.text.AttachmentKind
import app.sterna.core.data.text.attachmentExtensionLabel
import app.sterna.core.data.text.attachmentKind
import app.sterna.core.jmap.model.EmailBodyPart

/** How an attachment row presents a part: its icon, and a word for its kind, empty when there is none. */
internal class AttachmentLook(val icon: ImageVector, val label: String)

/**
 * The one presentation of an attachment, for the reader and the composer alike. The declared MIME
 * type is never shown: only the kind it resolves to, or the file's extension when the kind is unknown.
 */
@Composable
internal fun attachmentLook(part: EmailBodyPart): AttachmentLook {
    val kind = remember(part.type, part.name) {
        attachmentKind(part.type, part.name, MimeTypeMap.getSingleton()::getMimeTypeFromExtension)
    }
    val label = kind.label()?.let { stringResource(it) } ?: attachmentExtensionLabel(part.name).orEmpty()
    return AttachmentLook(kind.icon(), label)
}

/**
 * The line under an attachment's name, in the reader and the composer alike: the kind's [label] and
 * the size, each left out when empty, so a part of unknown size shows only its kind.
 */
internal fun attachmentMeta(label: String, size: Long): String =
    listOf(label, formatSize(size)).filter { it.isNotEmpty() }.joinToString(" · ")

// Kilobytes round to the nearest rather than truncate: the composer shows each row's size above
// their total, and truncated rows add up short of it. The KB branch ends where rounding would
// reach 1024, so "1024 KB" is never shown.
internal fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 - 512 -> "${(bytes + 512) / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun AttachmentKind.icon(): ImageVector = when (this) {
    AttachmentKind.IMAGE -> Icons.Filled.Image
    AttachmentKind.PDF -> Icons.Filled.PictureAsPdf
    AttachmentKind.AUDIO -> Icons.Filled.AudioFile
    AttachmentKind.VIDEO -> Icons.Filled.VideoFile
    AttachmentKind.ARCHIVE -> Icons.Filled.FolderZip
    AttachmentKind.TEXT -> Icons.AutoMirrored.Filled.Article
    AttachmentKind.DOCUMENT -> Icons.Filled.Description
    AttachmentKind.SPREADSHEET -> Icons.Filled.TableChart
    AttachmentKind.PRESENTATION -> Icons.Filled.Slideshow
    AttachmentKind.MESSAGE -> Icons.Filled.Email
    AttachmentKind.CALENDAR -> Icons.Filled.Event
    AttachmentKind.CONTACT -> Icons.Filled.ContactPage
    AttachmentKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@StringRes
private fun AttachmentKind.label(): Int? = when (this) {
    AttachmentKind.IMAGE -> R.string.attachment_kind_image
    AttachmentKind.PDF -> R.string.attachment_kind_pdf
    AttachmentKind.AUDIO -> R.string.attachment_kind_audio
    AttachmentKind.VIDEO -> R.string.attachment_kind_video
    AttachmentKind.ARCHIVE -> R.string.attachment_kind_archive
    AttachmentKind.TEXT -> R.string.attachment_kind_text
    AttachmentKind.DOCUMENT -> R.string.attachment_kind_document
    AttachmentKind.SPREADSHEET -> R.string.attachment_kind_spreadsheet
    AttachmentKind.PRESENTATION -> R.string.attachment_kind_presentation
    AttachmentKind.MESSAGE -> R.string.attachment_kind_message
    AttachmentKind.CALENDAR -> R.string.attachment_kind_calendar
    AttachmentKind.CONTACT -> R.string.attachment_kind_contact
    AttachmentKind.OTHER -> null
}

package app.sterna.ui.compose

import androidx.annotation.StringRes
import app.sterna.R
import app.sterna.core.jmap.ContentTooLargeException
import app.sterna.net.isOfflineFailure

/**
 * The sentence for a pick that could not be attached. The size refusal is asked first: a file over
 * the server's limit stays too large once the phone is back online, so "offline" would send the
 * reader to retry for nothing. [R.string.status_attach_failed] takes the exception text as its
 * argument; the other two take none.
 */
@StringRes
internal fun composedAttachFailure(t: Throwable, online: Boolean): Int =
    when {
        t is ContentTooLargeException -> R.string.status_attachment_too_large
        isOfflineFailure(t, online) -> R.string.status_attach_offline
        else -> R.string.status_attach_failed
    }

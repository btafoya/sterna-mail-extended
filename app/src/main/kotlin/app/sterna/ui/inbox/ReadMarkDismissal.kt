package app.sterna.ui.inbox

import app.sterna.core.data.mail.EmailKey
import app.sterna.ui.message.ReadMarkCleanup
import app.sterna.ui.message.readMarkCleanup

/**
 * The banners a read mark from the list may take down: those of the messages whose write returned.
 * [written] holds only the messages a write was attempted for, so one with no credentials keeps
 * its banner by never being listed. A cancelled write keeps its banner too, without the rethrow
 * the reader does: the list's loop is already over by the time this is asked.
 */
internal fun keysToDismiss(targetSeen: Boolean, written: List<Pair<EmailKey, Result<Unit>>>): List<EmailKey> {
    if (!targetSeen) return emptyList()
    return written
        .filter { (_, write) -> readMarkCleanup(write) == ReadMarkCleanup.DISMISS }
        .map { (key, _) -> key }
}

package app.sterna.core.imap

/**
 * How a purge ended on a server without UIDPLUS. [expunged]: a bare `EXPUNGE` was answered OK, so
 * nothing flagged `\Deleted` is left in the folder. Otherwise [leftFlagged] is what this operation
 * flagged and left for a later purge to erase. [NONE] for a purge done by UID, or none at all.
 */
data class ImapPurge(val expunged: Boolean, val leftFlagged: Set<Long>) {
    companion object {
        val NONE = ImapPurge(expunged = false, leftFlagged = emptySet())
    }
}

package app.sterna.core.data.db

import androidx.room.Entity

/**
 * One message an "Empty trash" is allowed to destroy. Frozen at CONFIRMATION time, not at the
 * held-back destroy (#99): re-reading the folder used to destroy mail moved into Trash during
 * the undo window too. A table, not the worker's [androidx.work.Data] (10 KiB input cap) —
 * fails safe: a destructively rebuilt cache loses the snapshot, so the purge destroys nothing.
 */
@Entity(tableName = "purge_snapshot", primaryKeys = ["purgeId", "accountId", "emailId"])
data class PurgeSnapshotEntity(
    val purgeId: String,
    val accountId: String,
    /** The Trash folder the snapshot was taken from — lets an undo erase it by folder. */
    val mailboxId: String,
    val emailId: String,
    /** Confirmation time, so an abandoned snapshot can be swept instead of lingering forever. */
    val createdAt: Long,
    /** IMAP UIDVALIDITY the folder was enumerated under; null for JMAP or an unobserved
     *  numbering. An IMAP UID only means anything within one numbering — a renumbered folder
     *  makes every id here name a different message. Null means unverifiable: destroy nothing. */
    val uidValidity: Long? = null,
)

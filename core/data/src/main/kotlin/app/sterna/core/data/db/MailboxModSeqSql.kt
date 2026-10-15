package app.sterna.core.data.db

/**
 * [MailboxUidValidityEntity.highestModSeq], the `HIGHESTMODSEQ` a folder's last reconciled read
 * started from (v35). Nullable with no default: a folder recorded before the column has no
 * mod-sequence, which reads "walk it in full".
 */
const val MAILBOX_UIDVALIDITY_ADD_MODSEQ_SQL: String =
    "ALTER TABLE `mailbox_uidvalidity` ADD COLUMN `highestModSeq` INTEGER"

/**
 * Never a REPLACE: writing the row back whole would drop [MailboxUidValidityEntity.highestModSeq]
 * of a folder whose numbering did not change.
 */
const val MAILBOX_UIDVALIDITY_FIRST_SIGHT_SQL: String =
    "INSERT OR IGNORE INTO mailbox_uidvalidity (accountId, mailboxId, uidValidity) " +
        "VALUES (:accountId, :mailboxId, :uidValidity)"

/** A mod-sequence belongs to one numbering (RFC 7162): it goes when the number changes. */
const val MAILBOX_UIDVALIDITY_RENUMBER_SQL: String =
    "UPDATE mailbox_uidvalidity SET " +
        "highestModSeq = CASE WHEN uidValidity = :uidValidity THEN highestModSeq ELSE NULL END, " +
        "uidValidity = :uidValidity WHERE accountId = :accountId AND mailboxId = :mailboxId"

/** v36: a delta never rereads an unchanged message, so only a full walk finds marks set before. */
const val MAILBOX_MODSEQ_FORGET_SQL: String = "UPDATE mailbox_uidvalidity SET highestModSeq = NULL"

const val MAILBOX_MODSEQ_READ_SQL: String =
    "SELECT highestModSeq FROM mailbox_uidvalidity WHERE accountId = :accountId AND mailboxId = :mailboxId"

/** Writes nothing when the folder is no longer numbered [MailboxUidValidityEntity.uidValidity]. */
const val MAILBOX_MODSEQ_WRITE_SQL: String =
    "UPDATE mailbox_uidvalidity SET highestModSeq = :highestModSeq " +
        "WHERE accountId = :accountId AND mailboxId = :mailboxId AND uidValidity = :uidValidity"

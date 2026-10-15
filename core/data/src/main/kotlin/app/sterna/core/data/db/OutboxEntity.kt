package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Lifecycle of an outgoing message held in the persistent outbox. */
enum class OutboxState {
    /** Held back for the undo window (or a scheduled time); not yet eligible to send. */
    HELD,

    /** Ready to send as soon as the worker runs and the network is up. */
    QUEUED,

    /** A send attempt is in flight. */
    SENDING,

    /** Auto-retry gave up; the item stays for manual retry/edit/delete. */
    FAILED,

    /** Reopened and saved as a DRAFT on the phone, no server copy (#95×#70): the send worker never picks it up. */
    KEPT_AS_DRAFT,

    /** Reopened for editing (#70): held from the send worker while open; a process death mid-edit strands it here. */
    EDITING,

    /** A delivery already under way when the process died, parked rather than retried: re-delivery risked an
     *  unrecallable duplicate. */
    INTERRUPTED,
}

/** A message persisted in the outbox: survives the app being killed, delivered by [app.sterna.send]'s WorkManager job.
 *  */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val recipients: String, // comma-separated
    val cc: String? = null, // comma-separated
    val bcc: String? = null, // comma-separated
    val subject: String,
    val textBody: String,
    val htmlBody: String? = null,
    val fromName: String? = null,
    val fromEmail: String? = null,
    val inReplyTo: String? = null, // space-separated message-ids
    val references: String? = null, // space-separated message-ids
    /** Durable attachment descriptors; see [OutboxAttachments]. */
    val attachmentsJson: String = "[]",
    val createdAtMillis: Long,
    /** Don't send before this instant: serves both the undo window and a scheduled time. */
    val notBeforeMillis: Long,
    val state: OutboxState = OutboxState.QUEUED,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptMillis: Long? = null,
    /** OpenPGP mode ("sign"/"encrypt") when the payload is PGP/MIME; null otherwise. */
    val pgpMode: String? = null,
    /** File holding this row's pre-built MIME entity; name is narrower than content, also carries RFC 8098 read
     *  receipts, [pgpMode] says which. */
    val pgpEntityPath: String? = null,
    /** Server draft this replaces (#63); destroyed once send succeeds so Drafts keeps no stale duplicate. */
    val draftEmailId: String? = null,
    /** IMAP numbering [draftEmailId] was read under, frozen at composer time (#99); `null` destroys nothing, always
     *  true on JMAP (#122). */
    val draftUidValidity: Long? = null,
    /** Asks for a read receipt (RFC 8098); `default 0` ([MIGRATION_21_22]), pre-v22 rows read back as "not asked for".
     *  */
    val requestReceipt: Boolean = false,
    val originalAccountId: String? = null,
    val originalEmailId: String? = null,
    val originalMark: String? = null,
)

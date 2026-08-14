package app.sterna.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A message queued to be sent at a future time (Schedule send). */
@Entity(tableName = "scheduled_sends")
data class ScheduledSendEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val recipients: String, // comma-separated
    val cc: String? = null, // comma-separated
    val bcc: String? = null, // comma-separated
    val subject: String,
    val textBody: String,
    val htmlBody: String?,
    val fromName: String?,
    val fromEmail: String?,
    val inReplyTo: String?, // space-separated message-ids
    val references: String?, // space-separated message-ids
    val sendAtMillis: Long,
    /** Server id of the saved draft this message was edited from (#63); destroyed on send. */
    val draftEmailId: String? = null,
    /**
     * The IMAP numbering [draftEmailId] was read under, frozen by the composer (#99). Held here as
     * well as on [OutboxEntity.draftUidValidity] for the same reason as [requestReceipt], and with a
     * heavier consequence: this row waits for hours and only becomes an outbox row when
     * [app.sterna.send.ScheduledSendWorker] fires. A column missing here loses the frozen numbering
     * in that hand-over, the send then destroys the draft under whatever numbering it finds at
     * delivery, and a Drafts folder renumbered meanwhile is expunged on another message.
     *
     * ⛔ `null` destroys nothing — see [OutboxEntity.draftUidValidity].
     */
    val draftUidValidity: Long? = null,
    /**
     * Whether the message asks for a read receipt (RFC 8098). Held here as well as on
     * [OutboxEntity] because a scheduled send waits in this table for hours and only becomes an
     * outbox row when [app.sterna.send.ScheduledSendWorker] fires: a column missing here loses the
     * request between the two, and the message goes out without asking.
     */
    val requestReceipt: Boolean = false,
)

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

    /**
     * Reopened in the composer and then SAVED AS A DRAFT ON THE PHONE, with no server copy to show
     * for it (#95 × #70). The row stays so the message is still somewhere the user can see it, and
     * the send worker never picks it up again on its own: they asked for a save, not for a send.
     *
     * ⛔ Not [FAILED], although it behaves like it in three places out of four. Nothing failed —
     * the save the user asked for succeeded, on the phone — and FAILED is not just a state: it
     * lights the Inbox's "Some messages didn't send. Tap to review." banner right across the list
     * and paints the row in the error colour, a claim nothing would ever retract, because there is
     * no deferred upload that could take it back down.
     *
     * ⛔ Not [EDITING] either, which was the defect: an [EDITING] row is on no screen at all
     * ([OutboxLogic.isWaitingInOutbox] hides it and [OutboxLogic.activeCount] does not count it),
     * and the next launch hands it back to the queue ([OutboxDao.revertEditingToQueued]) and
     * delivers the PRE-EDIT text with no undo window.
     *
     * ⭐ It costs no migration: the `state` column is `TEXT NOT NULL` and Room stores the constant's
     * NAME, so an older build simply never wrote this word. ⚠ A row in this state read back by an
     * OLDER build would fail to map — irrelevant here (downgrades are not a supported path) but
     * worth knowing before this value is written anywhere else.
     */
    KEPT_AS_DRAFT,

    /**
     * Reopened in the composer for editing (#70): held back from the send worker while it is open,
     * so it is neither delivered nor lost. Closing the composer returns it to [QUEUED]; sending,
     * saving or deleting consumes the row. A process death mid-edit strands it here, so startup
     * reverts any [EDITING] row to [QUEUED] — the edit is gone, the message is not.
     */
    EDITING,
}

/**
 * A message persisted in the outbox: the single reliable downstream send path. A row
 * survives the app being killed and is delivered by [app.sterna.send] WorkManager job,
 * with automatic retry. Unlike the disposable cache, this is user data (unsent mail),
 * so its table is migrated additively rather than rebuilt.
 */
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
    /** OpenPGP mode ("SIGN"/"ENCRYPT") when the payload is PGP/MIME; null otherwise. */
    val pgpMode: String? = null,
    /**
     * File holding this row's PRE-BUILT MIME ENTITY — the complete entity that replaces the body on
     * the wire, byte for byte (`OutgoingMessage.prebuiltEntity`).
     *
     * ⚠ **The column name is narrower than what it holds, and that is on purpose.** It was born
     * for PGP/MIME and is still spelled `pgpEntityPath` in SQLite; it now also carries RFC 8098's
     * `multipart/report` read receipt, which has no PGP anywhere near it. Renaming the column would
     * cost a Room migration — a new schema version, a table rebuild, another migration test — and
     * buy nothing but a better word. ⛔ So do not read it as "this row is PGP": the mode is
     * [pgpMode], and that is the only field that says so.
     *
     * For ENCRYPT items textBody/htmlBody/attachmentsJson are emptied: the outbox never stores
     * plaintext of an encrypted message. A SIGNED item, or a receipt, keeps its body in the row —
     * signed text is public, and a receipt's body is the human-readable half of its own entity.
     *
     * ⚠ The path is absolute and stored as written. Nothing looks the file up by name, which is why
     * the file could be renamed from `pgp-entity.mime` to
     * [app.sterna.core.data.mail.PREBUILT_ENTITY_FILE] without touching rows queued by an older
     * build: they still point at theirs.
     */
    val pgpEntityPath: String? = null,
    /**
     * Server id of the saved draft this message was edited from (#63). Destroyed once the
     * send succeeds, so the sent mail doesn't leave a stale duplicate in Drafts; a failed
     * send leaves the draft untouched.
     */
    val draftEmailId: String? = null,
    /**
     * The IMAP numbering (UIDVALIDITY) [draftEmailId] was read under, frozen by the composer when
     * it opened that draft (#99) and carried here because the destroy happens at DELIVERY time —
     * minutes, hours or days later, whenever the network comes back or a scheduled send fires.
     *
     * A `UID EXPUNGE` only names a message inside one UIDVALIDITY. Read at execution instead of
     * carried, this value is whatever a background sync last recorded: if Drafts was renumbered in
     * between, the guard compares the new number with itself, says "same", and expunges a UID from
     * the OLD numbering — i.e. destroys another message in Drafts, silently.
     *
     * ⛔ **On IMAP, `null` destroys nothing**, and that is the safe half of the trade: a row queued
     * before v23 leaves its draft in place, because nothing can be shown to still mean anything. A
     * duplicate in Drafts is visible and can be deleted; an expunge under an unknown numbering
     * cannot be undone.
     *
     * On JMAP it is ALWAYS null, because there is no numbering there to oppose: an `Email/set`
     * destroy names the message by id, and a JMAP id does not change with the folder. ⛔ Do not
     * read the null as "JMAP drafts are left behind": they are not, and "fixing" that is fixing a
     * non-bug.
     *
     * ⛔ Nor as "nothing is opposed on JMAP". That an id survives a move is the reason a destroy
     * fired days later can take the draft out of the folder someone moved it INTO (#122), so
     * `destroyDraft`'s JMAP branch goes through `destroyAll` and opposes the FOLDER: the server is
     * asked where the id is, and an id it does not report in the expected folder ALONE is spared,
     * in silence.
     *
     * ⚠ **And be exact about what that folder is worth.** It is `draftMailboxOf`, i.e. the cached
     * row's own mailbox falling back on the Drafts role — READ at the moment of the destroy, not
     * frozen when the composer read the id. So it catches the move this phone has not learned yet,
     * and NOT the move it has already synced: once a background sync has rewritten the cached row
     * with the new folder, expected and located agree and the destroy goes out there. This is the
     * same live read the emptied-local-draft route has always opposed to its own destroy of the
     * same server copy; closing it means carrying a FROZEN folder on this row, next to
     * [draftUidValidity], and that is a schema change nobody has paid for yet
     * (`findings/finding-dossier-attendu-lu-au-lieu-d-etre-gele.md`).
     */
    val draftUidValidity: Long? = null,
    /**
     * Whether the message asks the recipient's client for a read receipt (RFC 8098's
     * `Disposition-Notification-To:`, addressed to this row's `From`). Persisted because the header
     * is written at DELIVERY time, in the headless worker, long after the composer is gone.
     *
     * A plain non-null `Boolean` with `INTEGER NOT NULL DEFAULT 0` on the column
     * ([app.sterna.core.data.db.MIGRATION_21_22]): the Kotlin default is not a `@ColumnInfo`
     * default, so Room's `TableInfo` carries none on the entity side and skips the default
     * comparison at open time (b/136515305). Rows written before v22 read back as "not asked for",
     * which is what they were.
     */
    val requestReceipt: Boolean = false,
)

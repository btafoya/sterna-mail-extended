package app.sterna.core.data.db

import androidx.room.Entity

/**
 * Lifecycle of a draft written on the phone and not yet on the server.
 *
 * ⛔ There is NO terminal state here, and that is the decision this enum exists to carry. The
 * outbox has `FAILED` and a retry cap ([OutboxLogic.MAX_ATTEMPTS]) because an undeliverable message
 * has to stop being retried and be shown to the user. A draft has no deadline and no recipient
 * waiting: it may sit for weeks on a phone that never sees the network its account is on, and
 * nothing — not a sixth failure, not a hundredth — may delete it or park it out of reach. So
 * [LocalDraftEntity.attemptCount] counts, it does not cap: its only job is to space the next
 * upload attempt out.
 */
enum class LocalDraftState {
    /**
     * The text is written; the attachment bytes are still being copied.
     *
     * ⛔ Deliberately NOT picked up by [LocalDraftDao.pending]: a row is born here and leaves for
     * [PENDING] in the same call, once the files exist and their descriptors are on the row. The
     * window is short, and a process that dies inside it is exactly the case this value exists for:
     * what survives says `attachmentsJson` "[]" while the composer was showing chips, and uploading
     * THAT would send a draft amputated of its files, weeks later, in silence (#70's rule for the
     * outbox, applied here). An amputated send is not recoverable; a stranded row is.
     *
     * ⭐ So it is never UPLOADED here — [LocalDraftDao.pending] and `localDraftUploadIsDue` both keep
     * excluding it — but it is RE-ARMED at the next start:
     * [LocalDraftDao.revertStagedToPendingLossy] brings a [STAGING] row back to [PENDING], WITHOUT
     * its attachments and ⛔ **flagged [LocalDraftEntity.bodyIsLossy]**. Such an orphan is always a
     * FIRST save (a re-save copies the row's own state, see `localDraftStagingRow`), so it carries
     * "[]", and the identity of what was attached — source path, type, `cid`, disposition — lived
     * only in the composer's memory and died with the process; no column holds it, so it cannot be
     * re-staged. What that costs depends on the row, and the two cases are not the same:
     * - written from scratch, the bytes are gone either way and the typed text is not; the row then
     *   shows no attachment, which is the truth rather than a truncation;
     * - replacing a server draft ([LocalDraftEntity.replacesEmailId]), the attachments are still ON
     *   THE SERVER. Sent as a faithful replacement, "[]" would have the original — and its files —
     *   destroyed. The flag is what stops that: `draftReplacementIsFaithful` fails, the original is
     *   kept, and the user is left a duplicate to delete instead of a loss to discover.
     *
     * That is what keeps this enum's promise above — no terminal state, and no state whose rescue
     * costs mail.
     */
    STAGING,

    /** Saved locally, waiting to be uploaded to the server's Drafts folder. */
    PENDING,

    /** An upload attempt is in flight. */
    UPLOADING,

    /**
     * Open in the composer (#70's rule, applied to drafts): held back from the upload worker while
     * it is being edited, so the phone and the server can never race over the same text.
     *
     * ⛔ **Written by the composer, and given back by the composer alone — no sweep takes it back.**
     * ⚠ Not to be read as "nothing else ever writes over it": the upload worker's
     * `setState(UPLOADING)` follows its read of the row without re-checking the state, so a take
     * that lands between the two is overwritten (`findings/le-televerseur-ecrase-un-etat-qu-il-a-lu-avant.md`).
     * A process death
     * mid-edit strands a row here and the next start does NOT take it back
     * ([LocalDraftDao.revertUploadingToPending] rescues [UPLOADING] only): Android restores the back
     * stack, so the composer that was killed comes back and reads this very row a moment later
     * (`localDraftMayBeTakenForEdit` accepts [EDITING] for exactly that), and a sweep running from
     * `appScope` beforehand cannot tell that case from an abandoned row. Flipped to [PENDING] in
     * between, the row is due for `LocalDraftDao.pending`, the upload worker takes it, and a
     * successful upload deletes the row and its attachment directory while the user is typing into
     * them — two drafts on the server and the edit lost.
     *
     * ⚠ The price, named: a row whose composer never comes back (`abandonPlan` withholds the release
     * on a `submit` that failed, and a `giveLocalDraftEditBack` that never runs) waits here instead
     * of being uploaded. The text is on the phone, visible in the Drafts list and openable
     * (`localDraftMayBeTakenForEdit`), so what is delayed is the upload, not the draft.
     */
    EDITING,
}

/**
 * A draft the user wrote on the phone, held here until it reaches the server.
 *
 * ⛔ **Its own table, deliberately outside `emails`.** A draft row with no server id, cached in
 * `emails`, is deleted by four paths that never say a word:
 * - `EmailDao.reconcileMailbox` → `reconcileMailboxRows`: a local id is in no walk's `keepIds`;
 * - `MailRepository.pruneGhostRows` → `pruneServerGone`, which takes the cached BODY with it;
 * - the retention prune (`MailRepository.pruneRetention`);
 * - and on IMAP `saveDraft`'s own `refresh(credentials, drafts)` triggers the first of them.
 *
 * The one protection those paths honour is the recently-mutated spare, a 45-second window
 * (`RECENT_MUTATION_MS`) that no draft path ever arms. A draft waiting for the network outlives
 * that window by definition, so "exempt it from the prunes" would have been four exemptions to
 * remember for ever, on the paths whose failure mode is destroying mail. A table those paths cannot
 * name is one decision instead of four (#95); `LocalDraftsSurviveTheCachePrunesSqlTest` runs all
 * four against a row in each table and shows which one dies.
 *
 * Modelled on [OutboxEntity], the other store of text the user typed and the server has not got:
 * user data, migrated additively, never rebuilt destructively.
 *
 * ⭐ The whole column set is created at once ([LOCAL_DRAFTS_CREATE_SQL], [MIGRATION_22_23]), the
 * columns the first volet does not read included. A published migration is never rewritten in
 * place, so a column added later is another migration, another registration in
 * [SternaDatabase.ALL_MIGRATIONS] and another chance to miss it — and a missed step is not a
 * degraded upgrade, it is `fallbackToDestructiveMigration` dropping every table.
 *
 * Keyed on `(accountId, id)` like [EmailEntity] since issue #31: two accounts on the same server
 * mint ids in their own space, and a single-column key lets one account's row answer for the
 * other's.
 */
@Entity(tableName = "local_drafts", primaryKeys = ["accountId", "id"])
data class LocalDraftEntity(
    val accountId: String,
    /**
     * The draft's local id, minted at creation by [newLocalDraftId].
     *
     * ⛔ It shares a namespace with the ids of cached server messages, and must collide with
     * neither: an `imap:` prefix would have `ImapMailService.uidOf` read a UID out of it and act on
     * a message number the server chose for something else. See [LOCAL_DRAFT_ID_PREFIX].
     */
    val id: String,
    /**
     * The RFC 5322 `Message-ID` of this draft, minted ONCE, here.
     *
     * On `main` it is minted inside `MailRepository.outgoing()` and is therefore a fresh value on
     * every save. Frozen with the row, it becomes the only stable name this draft has on both sides
     * of the network: before appending, the upload can read the newest of the Drafts folder back
     * from an IMAP server and refuse to leave a second copy behind after an answer that was lost in
     * transit (`ImapMailService.draftIsAlreadyThere`).
     */
    val messageId: String,
    /** `To:` addresses, comma-separated, as [OutboxEntity.recipients] holds them. */
    val toAddresses: String,
    /** `Cc:` addresses, comma-separated. */
    val cc: String? = null,
    /** `Bcc:` addresses, comma-separated. */
    val bcc: String? = null,
    val subject: String,
    /**
     * ⛔ NOT NULL. This is the text the user typed — the thing the whole feature exists to keep —
     * and it has no "unknown" state: an empty draft is an empty string, not a null.
     */
    val textBody: String,
    val htmlBody: String? = null,
    val fromName: String? = null,
    val fromEmail: String? = null,
    /** Space-separated message-ids, as [OutboxEntity.inReplyTo]. */
    val inReplyTo: String? = null,
    /** Space-separated message-ids, as [OutboxEntity.references]. */
    val references: String? = null,
    /** Durable attachment descriptors, in [OutboxAttachments]' encoding. */
    val attachmentsJson: String = "[]",
    /**
     * The SERVER draft this row will replace once it is uploaded (#63) — the message the user
     * reopened to edit. Null for a draft written from scratch.
     */
    val replacesEmailId: String? = null,
    /**
     * The UIDVALIDITY [replacesEmailId]'s folder had when the user opened that draft — IMAP only,
     * null on JMAP and null when nothing is being replaced.
     *
     * ⛔ What it exists to prevent: destroying a stranger's mail. An IMAP cache id ends in a UID
     * (`ImapMailService.emailId`), and a UID is only a name inside one numbering: "a list of UIDs
     * without its UIDVALIDITY is a list of numbers that may already mean something else"
     * (`ImapUidSnapshot`, Codeberg #99). A row here is designed to wait weeks for a network, so the
     * folder can be renumbered between the save and the upload — and the upload's last act is to
     * delete the original. Without the numbering this row was written under, that delete lands on
     * whatever message inherited the number.
     *
     * Nothing reads it yet. It is declared with the rest of the table because [MIGRATION_22_23]
     * stops being amendable the moment it ships, and a column added afterwards is a second
     * migration to write, register and not miss.
     */
    val replacesUidValidity: Long? = null,
    /**
     * Whether the body this row carries is known to be a lossy reading of the message it replaces
     * (an HTML original the composer could only show as text). Carried from the save so the upload
     * can apply `draftReplacementIsFaithful` and refuse to destroy an original it cannot reproduce.
     */
    val bodyIsLossy: Boolean = false,
    /** Whether the message asks for a read receipt when it is eventually sent (RFC 8098). */
    val requestReceipt: Boolean = false,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Don't attempt an upload before this instant — the backoff, computed from [attemptCount]. */
    val notBeforeMillis: Long,
    /**
     * How many upload attempts have failed. ⚠ A COUNTER, not a cap: see [LocalDraftState]. Nothing
     * in this store gives up on a draft.
     */
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptMillis: Long? = null,
    val state: LocalDraftState = LocalDraftState.PENDING,
)

/**
 * The prefix every local draft id carries.
 *
 * ⛔ Two collisions to avoid at once, and the colon settles both:
 * - an id starting with `imap:` is read as an IMAP cache id (`ImapMailService.uidOf` /
 *   `mailboxOf`), so its trailing number is taken for a UID;
 * - a JMAP id is `A-Za-z0-9_-` only (RFC 8620 §1.2), so a string containing a `:` cannot be one.
 *
 * `LocalDraftIdTest` executes those two readers on a minted id rather than restating the rule.
 */
const val LOCAL_DRAFT_ID_PREFIX: String = "local-draft:"

/** A fresh local draft id — [LOCAL_DRAFT_ID_PREFIX] plus a UUID, unique without asking anyone. */
fun newLocalDraftId(): String = LOCAL_DRAFT_ID_PREFIX + java.util.UUID.randomUUID()

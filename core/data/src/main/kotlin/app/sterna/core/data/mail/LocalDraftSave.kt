package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.db.OutboxAttachment
import app.sterna.core.data.db.OutboxAttachments
import app.sterna.core.data.db.newLocalDraftId
import app.sterna.core.imap.OutgoingAttachment
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Saving a draft ON THE PHONE FIRST (#95), and every decision that goes with it, as functions a
 * JVM test can execute: [MailRepository] needs Room, an Android context and a live session, so
 * nothing inside it can be reached from one.
 *
 * ⭐ **One path, always, online or off.** The row is written before the first byte goes out, then
 * the upload is attempted in the same call. Online the row lives a second and is consumed; offline
 * — or on any other upload failure — it stays and [MailRepository.saveDraft] does not throw, which
 * is the whole of what issue #95 reported: "could not save the draft" plus the raw transport error,
 * and the typed text dying with the process.
 *
 * ⛔ There is deliberately NO "if there is a network, server, else local" branch. Two paths would be
 * a state machine, and worse, they would not make the text durable BEFORE the attempt — which is the
 * only reason to write first at all. A transport that hangs, a process killed mid-upload and a phone
 * in a lift all land on the same code here.
 */

/** The row a save lands on: its id, and the row already there under it (null for a first save). */
internal data class LocalDraftTarget(val id: String, val existing: LocalDraftEntity?)

/**
 * Which row a save lands on — ⭐ **found by BOTH keys, because a draft has two names.**
 *
 * - A draft reopened from the phone's own store carries a [LOCAL_DRAFT_ID_PREFIX] id: it re-saves
 *   onto its own row, found by `(accountId, id)`.
 * - A draft reopened from the SERVER carries the server's id, and the phone's store has no row under
 *   that name — but it may well hold one that already NAMES it in `replacesEmailId`. That is the
 *   reachable re-save today (open a server draft, edit, save with no network, edit again, save
 *   again), and minting a fresh id for it was worth one extra local row, one extra `APPEND` /
 *   `Email/set`, one extra draft on the server and one extra attempt to destroy the same original,
 *   PER SAVE. So it is looked up by `(accountId, replacesEmailId)` and UPDATED.
 * - Nothing being replaced: a brand-new draft, a fresh id, no lookup.
 *
 * ⛔ Both lookups are account-scoped by their callers (#31): ids collide between two accounts of one
 * server, and so do server draft ids.
 */
internal suspend fun localDraftTarget(
    replacesEmailId: String?,
    byId: suspend (String) -> LocalDraftEntity?,
    forServerDraft: suspend (String) -> LocalDraftEntity?,
    mint: () -> String = ::newLocalDraftId,
): LocalDraftTarget {
    if (replacesEmailId == null) return LocalDraftTarget(mint(), null)
    if (replacesEmailId.startsWith(LOCAL_DRAFT_ID_PREFIX)) {
        return LocalDraftTarget(replacesEmailId, byId(replacesEmailId))
    }
    val existing = forServerDraft(replacesEmailId)
    return LocalDraftTarget(existing?.id ?: mint(), existing)
}

/**
 * The SERVER draft a save replaces, or null when there is none to destroy.
 *
 * ⛔ The guard that matters: a local id is NOT a server id. Handed on as `replacesEmailId` it would
 * reach [MailRepository.finishDraftSave] → `destroyDraft`, i.e. an `Email/set` destroy or a UID
 * STORE \Deleted addressed with a name the server has never issued. On IMAP that name ends in a
 * number, and a number always means SOMETHING in a folder.
 */
internal fun serverDraftReplacedBy(replacesEmailId: String?): String? =
    replacesEmailId?.takeUnless { it.startsWith(LOCAL_DRAFT_ID_PREFIX) }

/**
 * The RFC 5322 `Message-ID` this save writes on the row — ⭐ **a fresh one EVERY TIME THE USER
 * SAVES**, whatever [existing] already carries.
 *
 * ⭐ **It names the VERSION of the text, not the draft**, and that is the whole of it. The name is
 * what a deferred upload looks for in the Drafts folder (`ImapMailService.draftIsAlreadyThere`)
 * before appending a second copy of text the server may already hold, and it reads "found" as "do not
 * append". A name that does not distinguish two versions cannot decide not to send the second one:
 *
 * 1. v1 is appended, the tagged answer is lost, the row survives with a failed attempt on it;
 * 2. the user edits the same draft into v2 and saves with no network;
 * 3. the upload asks about the frozen name, finds the copy of **v1**, skips the append — and
 *    [uploadLocalDraftOnce] consumes the row. v2 is on neither side, and the original is destroyed.
 *
 * ⛔ **Frozen for the RETRIES all the same**, and structurally so: `MailRepository.uploadLocalDraft`
 * never rebuilds a row, so every deferred attempt at one save asks under the name that save wrote.
 * Re-minting there would search for something that has never been on a wire and append a copy per
 * retry — the duplicate the search exists to stop.
 *
 * ⛔ **The residual damage, chosen deliberately** (grid rule 1, nothing irreversible): the copy of an
 * earlier version stays on the server, so Drafts can end up holding a stale twin. That is a
 * duplicate — visible, and deletable by the user — where the alternative was a version of her text
 * that exists nowhere at all. ⚠ Nothing here deletes the stale copy; that is stated debt.
 *
 * ⛔ The row's ID and its creation time are NOT re-minted (see [localDraftTarget] and
 * [localDraftRow]): one draft is still one row. Only its name moves.
 */
// ⛔ [existing] is taken and NOT read, on purpose: it is the answer the old rule gave, and the
// question this function answers is "the row already holds a name — what does this save write?".
// Dropped, the refusal to inherit stops being a decision anything can execute.
internal fun localDraftMessageId(
    @Suppress("UNUSED_PARAMETER") existing: LocalDraftEntity?,
    mint: () -> String,
): String = mint()

/** A fresh `Message-ID` for [username]'s domain — the mint `outgoing()` used to inline. */
internal fun newMessageId(username: String, unique: String = java.util.UUID.randomUUID().toString()): String =
    "$unique@${username.substringAfter('@', "localhost")}"

/**
 * The UIDVALIDITY to freeze on a row that will destroy [replacesEmailId] after a successful upload,
 * read through [recorded] for the folder that id names. Null when nothing is being replaced, and
 * null on JMAP or a local id, where [ImapMailService.mailboxOf] answers null.
 *
 * ⛔ Nothing in this volet READS the value back — the upload volet does. It is filled in here
 * because this is the only moment the information is true: an IMAP cache id ends in a UID, a UID is
 * only a name inside one numbering (#99), and a row designed to wait weeks for a network can easily
 * outlive the numbering it was written under. Recorded later it would describe a folder that has
 * already moved on.
 */
internal suspend fun localDraftUidValidity(
    replacesEmailId: String?,
    recorded: suspend (mailboxId: String) -> Long?,
): Long? {
    val mailboxId = replacesEmailId?.let { ImapMailService.mailboxOf(it) } ?: return null
    return recorded(mailboxId)
}

/**
 * The row to write, built from what the composer handed over plus [existing] (the row a re-save
 * lands on, so its id and creation time survive the re-save — ⛔ its `Message-ID` does not, see
 * [localDraftMessageId]).
 *
 * Addresses are stored comma-separated as [app.sterna.core.data.db.OutboxEntity.recipients] stores
 * them, message-id lists space-separated as the headers themselves are.
 *
 * ⛔ **A malformed address is refused HERE, before the row exists** (#95). The single-line check used
 * to run inside [localDraftOutgoing], i.e. after the row had been written and inside the upload — so
 * `saveDraftLocalFirst`'s catch swallowed it into [DraftSaveOutcome.KEPT_ON_DEVICE] and a typing
 * mistake was displayed as a network outage. Worse, it was asymmetric: on IMAP that draft could
 * never leave the phone (every attempt throws at the same line), while on JMAP the same address went
 * through untouched, because `Email/set` carries structured addressing and builds no header. An
 * error of entry is not an outage, and it must reach the composer as one.
 */
@Suppress("LongParameterList")
internal fun localDraftRow(
    accountId: String,
    id: String,
    messageId: String,
    to: List<String>,
    cc: List<String>,
    bcc: List<String>,
    subject: String,
    body: String,
    html: String? = null,
    fromName: String? = null,
    fromEmail: String? = null,
    inReplyTo: List<String> = emptyList(),
    references: List<String> = emptyList(),
    replacesEmailId: String? = null,
    replacesUidValidity: Long? = null,
    bodyIsLossy: Boolean = false,
    requestReceipt: Boolean = false,
    existing: LocalDraftEntity? = null,
    nowMillis: Long,
): LocalDraftEntity = LocalDraftEntity(
    accountId = accountId,
    id = id,
    messageId = messageId,
    toAddresses = joinAddresses(to).orEmpty(),
    cc = joinAddresses(cc),
    bcc = joinAddresses(bcc),
    subject = subject,
    textBody = body,
    htmlBody = html,
    fromName = fromName,
    fromEmail = fromEmail,
    inReplyTo = inReplyTo.joinToString(" ").ifBlank { null },
    references = references.joinToString(" ").ifBlank { null },
    // Filled in by [saveDraftLocalFirst] once the files are staged: the descriptors have to name
    // files that exist, and they only exist after the copy.
    attachmentsJson = "[]",
    // ⛔ **Written at the row's CREATION and never again** (#95). The link to the server draft this
    // row stands in for belongs to the DRAFT, not to the last save that touched it. A row born from
    // a server draft carries both columns; reopened from the phone's own store the composer holds
    // the LOCAL id, for which `serverDraftReplacedBy` answers null — correctly, it names nothing a
    // server can be asked to destroy — and the re-save then arrived here with both null and rubbed
    // them out. Nothing is destroyed by that (the safe direction), but the mechanism is disarmed:
    // when the network comes back the original stays in Drafts BESIDE the new copy, for good, and
    // the list stops masking it. The two move together or not at all — an id kept beside a
    // numbering read at another moment is the pair #99 exists to refuse.
    replacesEmailId = if (existing == null) replacesEmailId else existing.replacesEmailId,
    replacesUidValidity = if (existing == null) replacesUidValidity else existing.replacesUidValidity,
    bodyIsLossy = bodyIsLossy,
    requestReceipt = requestReceipt,
    createdAtMillis = existing?.createdAtMillis ?: nowMillis,
    updatedAtMillis = nowMillis,
    notBeforeMillis = nowMillis,
    state = LocalDraftState.PENDING,
).also { requireSingleLineAddresses(localDraftHeaderAddresses(it)) }

/**
 * Every address [row] will put in a header, in the exact form the wire will see it — trimmed and
 * blank-dropped, as [joinAddresses] wrote them and [localDraftAddresses] reads them back. Compared
 * on the stored form and not on the composer's raw strings so a pasted trailing newline, which the
 * join strips anyway, is not rejected as an injection.
 */
internal fun localDraftHeaderAddresses(row: LocalDraftEntity): List<String> =
    localDraftAddresses(row.toAddresses) + localDraftAddresses(row.cc) + localDraftAddresses(row.bcc)

private fun joinAddresses(addresses: List<String>): String? =
    addresses.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",").ifEmpty { null }

/** The addresses a row's comma-separated column holds, blanks dropped. */
internal fun localDraftAddresses(column: String?): List<String> =
    column.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The MIME an IMAP `APPEND` carries for [row] — built FROM THE ROW, not from the composer's
 * arguments, and that is the point rather than a detail.
 *
 * ⭐ **The `Message-ID` on the wire is the row's.** It cannot be dropped at a call site because
 * there is no call site left to drop it at: the row is the only source of the value. On `main` the
 * id was minted inside `outgoing()` at the moment of the APPEND, so nothing the phone kept named
 * what the server received.
 *
 * The rest reproduces `outgoing()` exactly — [formatFromAddress] for the display name, the
 * single-line check on every address before any of it becomes a header (CRLF injection), the first
 * `In-Reply-To` and the joined `References`.
 *
 * ⚠ JMAP has no equivalent: `JmapClient.saveDraft` builds an `Email/set` create and the server mints
 * the `Message-ID`. The row's value is still frozen there — it just does not reach the wire, so a
 * JMAP re-upload cannot be de-duplicated by header. Stated, not fixed here.
 */
internal fun localDraftOutgoing(
    row: LocalDraftEntity,
    loginAddress: String,
    attachments: List<OutgoingAttachment>,
    nowMillis: Long,
): OutgoingMessage {
    val from = formatFromAddress(row.fromName, row.fromEmail) ?: loginAddress
    val to = localDraftAddresses(row.toAddresses)
    val cc = localDraftAddresses(row.cc)
    val bcc = localDraftAddresses(row.bcc)
    requireSingleLineAddresses(listOf(from) + to + cc + bcc)
    return OutgoingMessage(
        from = from,
        to = to,
        cc = cc,
        bcc = bcc,
        subject = row.subject,
        body = row.textBody,
        html = row.htmlBody,
        inReplyTo = row.inReplyTo?.split(" ")?.firstOrNull { it.isNotBlank() },
        references = row.references,
        messageId = row.messageId,
        dateMillis = nowMillis,
        attachments = attachments,
        requestReceipt = row.requestReceipt,
    )
}

/** The per-draft directory name for [id] — the id with anything a file name cannot hold replaced. */
internal fun localDraftDirName(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

/**
 * The directory under `filesDir` holding every local draft's staged bytes.
 *
 * ⭐ A constant because it now has TWO readers — the repository that writes there, and the sign-out
 * that has to clear it. Two literals would be one rename away from a purge that sweeps an empty
 * directory and reports success.
 */
const val LOCAL_DRAFT_FILES_DIR: String = "local-drafts"

/**
 * Copy the composer's staged attachment bytes into [dir] so they outlive the cache the composer
 * sends from, and describe them in [OutboxAttachments]' encoding.
 *
 * ⛔ **Refuses rather than shortens**, like `MailRepository.persistAttachments` (#70) and
 * `OutboxEdit.take`: a part whose bytes cannot be read makes this THROW. The alternative — staging
 * fewer parts than the composer was showing — writes a row that a later upload would send amputated,
 * silently, weeks after the file went away. [saveDraftLocalFirst] turns the throw into a rollback,
 * so a failed staging leaves no row at all.
 *
 * ⭐ Its OWN directory, not the outbox's: an outbox item's files die with the send, a draft's wait
 * for as long as the draft does, and the two purges have nothing to say to each other.
 */
internal fun stageLocalDraftAttachments(dir: File, attachments: List<EmailBodyPart>): List<OutboxAttachment> {
    if (attachments.isEmpty()) return emptyList()
    dir.mkdirs()
    return attachments.mapNotNull { part ->
        when {
            part.blobId != null -> OutboxAttachment(
                kind = OutboxAttachments.KIND_JMAP_BLOB,
                blobId = part.blobId, type = part.type, name = part.name, size = part.size,
                cid = part.cid, disposition = part.disposition,
            )
            part.partId != null -> {
                val source = part.partId!!
                val bytes = runCatching { File(source).readBytes() }.getOrNull()
                    ?: error("Couldn't read the attachment ${part.name ?: source} to keep it with the draft.")
                val safe = (part.name ?: "attachment").replace(Regex("[^A-Za-z0-9._-]"), "_")
                // Unique name, so two inline images sharing a file name don't overwrite each other.
                val dest = File(dir, "${System.nanoTime()}-$safe").apply { writeBytes(bytes) }
                OutboxAttachment(
                    kind = OutboxAttachments.KIND_IMAP_FILE,
                    path = dest.absolutePath, type = part.type, name = part.name, size = bytes.size.toLong(),
                    cid = part.cid, disposition = part.disposition,
                )
            }
            else -> null
        }
    }
}

/**
 * The FIRST write of a save: the text, at once — and ⛔ **nothing of [existing] taken away.**
 *
 * - **A first save** ([existing] null) has nothing to protect. It goes down in
 *   [LocalDraftState.STAGING], with the `attachmentsJson` "[]" [localDraftRow] built, which every
 *   reader of the QUEUE skips: a process killed before the files are copied leaves a row nobody
 *   uploads amputated (#70), and there was nothing else to lose — the next start hands that row
 *   back as [LocalDraftState.PENDING], attachment-less and flagged `bodyIsLossy`
 *   (`revertStagedToPendingLossy`), which is all there is left to save of it.
 * - **A re-save** lands on a row that is already whole, and may already be uploadable. So it writes
 *   the new text — that half is the promise of the volet and is not negotiable — and KEEPS what the
 *   row carries: the descriptors of the files staged by the save that worked, and its state. Copied
 *   over with "[]" and [LocalDraftState.STAGING], as this used to do, a process killed at exactly
 *   this instant turned a draft that was going to leave with its files into a row the re-arm hands
 *   back saying "[]" — i.e. the attachments of a save that HAD worked, dropped, and the draft sent
 *   amputated. ⭐ This is also why a stranded [LocalDraftState.STAGING] row is always a first save,
 *   and therefore always one whose attachments never existed on the row at all.
 *
 * The new descriptors replace them only once [stageLocalDraftAttachments] has actually written the
 * new bytes, in [saveDraftLocalFirst]'s second [upsert].
 */
internal fun localDraftStagingRow(row: LocalDraftEntity, existing: LocalDraftEntity?): LocalDraftEntity =
    if (existing == null) {
        row.copy(state = LocalDraftState.STAGING)
    } else {
        row.copy(attachmentsJson = existing.attachmentsJson, state = existing.state)
    }

/**
 * ⭐ **The order of this volet, and it is the whole fix.**
 *
 * 1. [upsert] the row — the text is on the phone before anything is attempted — through
 *    [localDraftStagingRow], which downgrades nothing an earlier save left;
 * 2. [stage] the attachments, [stageOrRollback]-style: a staging that throws takes the row with it
 *    (#70) ⛔ **only when THIS save created it** ([existing] null), because an orphan row carrying
 *    `attachmentsJson` "[]" would be re-armed later and uploaded amputated;
 * 3. [upsert] it again, now describing the staged files and in [LocalDraftState.PENDING] — the row
 *    becomes uploadable at that instant and not before;
 * 4. [upload] it, and ONLY on success [discard] the row and its staged files;
 * 5. on failure, [schedule] the deferred upload the attempt books and then [record] it.
 *
 * ⛔ **The three writes that finish whatever happens to the caller** ([NonCancellable]): making the
 * row uploadable, undoing a save that never happened, and consuming a row the server now holds. Each
 * of them is the second half of something already half done — bytes staged, a row created, a draft
 * appended — and each is reached from the COMPOSER's scope, which is cancelled by closing a screen.
 * Everything else on this path may be cut where it stands.
 *
 * ⛔ **[existing] is the row that was there BEFORE this save, and the rollback turns on it.** The
 * rollback is [discard], i.e. `deleteById` PLUS the per-DRAFT directory — not the per-save one,
 * there is no such thing. Run unconditionally it deleted the row, the text and the staged bytes of
 * an EARLIER save the moment one attachment of THIS one could not be read, which is reachable
 * without anything going wrong on the phone: the composer's sources live in `cacheDir`, which
 * Android evicts, which "clear cache" empties and which signing another account out empties too. A
 * re-save that fails to stage therefore leaves the saved draft exactly as it was and lets the error
 * out; only a save that created its own row rolls it back.
 *
 * ⭐ **Why the text is still written first, and the state carries the risk instead.** The typed text
 * is the one thing that cannot be refabricated, so it goes down before the bytes and before the
 * network — that is the whole promise of the volet. What [LocalDraftState.STAGING] closes is the
 * other end: a process killed BETWEEN the two upserts (the `catch` only ever covered the exception
 * case) left a row saying "[]" that a later upload would have sent amputated, silently. Now such a
 * row is not picked up by any upload, and the next start hands it back as
 * [LocalDraftState.PENDING] without attachments and flagged `bodyIsLossy`
 * (`revertStagedToPendingLossy`): the descriptors died with the process, so the row must never be
 * taken for a faithful replacement of a server draft whose attachments are still up there.
 *
 * ⛔ [discard] is on the success path and nowhere else. Moved to a `finally`, or run before the
 * outcome is known, it would be strictly worse than the bug this fixes: the screen would say the
 * draft was saved and nothing at all would be left.
 *
 * ⛔ An upload failure is swallowed into [DraftSaveOutcome.KEPT_ON_DEVICE] — the caller must NOT
 * throw offline. A [CancellationException] is not a failure and is always rethrown: the caller is
 * going away.
 *
 * ⭐ **A cancelled attempt books the deferred upload before it lets the stop through**, and that is
 * the hole measured at the bench (#95). The interactive attempt runs in the COMPOSER's scope and is
 * bounded by nothing, so closing the composer cancels it routinely — and from the instant a
 * coroutine is cancelled every suspension point in it throws. The row was therefore left `PENDING`
 * with `attemptCount` 0, no cause and no work item: seventeen minutes of healthy network went by
 * without the draft leaving, and only the startup re-arm (`revertUnfinishedLocalDrafts`) sent it,
 * after a force-stop. So [schedule] runs FIRST, before the rethrow.
 *
 * ⛔ **[schedule] does not suspend, and that is load-bearing rather than a style**: a suspending
 * seam works here only by accident — because its body happens never to suspend — and the first
 * `yield()` or Room write put behind it would book nothing at all, in silence, on this exact path.
 *
 * ⛔ **At delay 0, and writing NOTHING on the row.** A cancellation is not a failure: no attempt
 * counted, no cause invented, no backoff armed. An attempt written here would make
 * [localDraftMayAlreadyBeOnServer] true for a row that has never been on a wire, and every deferred
 * upload would then pay a folder read (`UID SEARCH ALL` plus envelopes) to be told no. And nothing on
 * this path calls `finishDraftSave`, so the server draft being replaced stays intact.
 *
 * ⭐ **The failure is also RECORDED**, through the same [record] the deferred worker uses
 * ([uploadLocalDraftOnce]): the attempt counter, the cause, and the instant the backoff moves the
 * row to. Swallowed without recording — as it was in the first pass — `notBeforeMillis` never
 * moves, `LocalDraftDao.pending` hands the row straight back, and against a server that is refusing
 * rather than absent the worker retries as fast as it is allowed to, on battery. ⛔ It records and
 * nothing else: no cap, no terminal state, no delete.
 *
 * ⭐ **And it is SCHEDULED**, at the instant the attempt computed — before [record] writes it, so a
 * cancellation landing on that write cannot take the booking with it. This is what makes the promise
 * of the volet true: the draft rejoins the server on its own when the network comes back, without
 * the app being started again. Before it, `LocalDraftUploads.enqueue` had two callers — the startup
 * re-arm and the worker re-booking itself — so a draft saved in a lift waited for the process to
 * DIE and come back, and nothing on screen said so. ⛔ Not at delay 0 either: the row's own backoff
 * is what a server that refuses (rather than one that is absent) is protected by.
 *
 * Note what does NOT happen on failure: nothing calls `finishDraftSave`, so the SERVER draft this
 * one replaces is untouched. Offline the original is intact and stays intact.
 */
@Suppress("LongParameterList")
internal suspend fun saveDraftLocalFirst(
    row: LocalDraftEntity,
    existing: LocalDraftEntity?,
    nowMillis: Long,
    upsert: suspend (LocalDraftEntity) -> Unit,
    stage: suspend (LocalDraftEntity) -> List<OutboxAttachment>,
    discard: suspend (LocalDraftEntity) -> Unit,
    upload: suspend (LocalDraftEntity) -> DraftSaveOutcome,
    record: suspend (LocalDraftEntity, LocalDraftAttempt) -> Unit,
    schedule: (LocalDraftUploadJob) -> Unit,
): DraftSaveOutcome {
    upsert(localDraftStagingRow(row, existing))
    // ⛔ Only what THIS save created is rolled back. See the note on [existing] above: the
    // directory the rollback removes belongs to the draft, not to one save of it.
    // ⛔ [NonCancellable]: this rollback runs from a coroutine that may ALREADY be cancelled, and
    // `discardLocalDraft` is two `runCatching` — the first swallows the CancellationException the
    // Room delete throws, the second (a plain file delete) goes through regardless. What survives
    // is a row in [LocalDraftState.STAGING] saying "[]" whose staged bytes have just been removed —
    // a save the user was told failed, that the next start's `revertStagedToPendingLossy` hands
    // back as PENDING and that then leaves without a single one of the files she attached. Half a
    // delete is worse than none.
    val staged = stageOrRollback(
        rollback = { withContext(NonCancellable) { if (existing == null) discard(row) } },
    ) { stage(row) }
    val durable = row.copy(
        attachmentsJson = OutboxAttachments.encode(staged),
        state = LocalDraftState.PENDING,
    )
    // ⛔ [NonCancellable], and this is the write the whole volet rests on. [stage] does not suspend
    // (`stageLocalDraftAttachments` is plain file copying), so a cancellation arriving while the
    // attachment bytes are copied is FIRST SEEN HERE — on the write that turns a staged row into an
    // uploadable one, and outside the `try` below, so nothing catches it. Cut here, a first save
    // leaves a STAGING row saying "[]" with its bytes on disk: no upload takes it, and the next
    // start's `revertStagedToPendingLossy` sends the text WITHOUT those bytes, which are orphaned in
    // the draft's directory until it is discarded; a re-save leaves the NEW text beside the
    // descriptors and the state of the
    // OLD one — including the [LocalDraftState.EDITING] lease the composer took when it reopened
    // the draft, which `localDraftUploadIsDue` reads as "not mine to send".
    withContext(NonCancellable) { upsert(durable) }
    val outcome = try {
        upload(durable)
    } catch (cancelled: CancellationException) {
        // ⛔ BOOKED FIRST, and only then let the stop through. See the note above: from here on
        // every suspension point throws, so the booking has to be the first thing that runs and it
        // has to be able to run at all — which is why [schedule] does not suspend.
        // ⛔ Delay 0, and NOTHING written on the row: a cancellation is not a failure.
        schedule(LocalDraftUploadJob(durable.accountId, durable.id, 0L))
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        val attempt = localDraftAttemptAfterFailure(durable, failure, nowMillis)
        // ⛔ BOOKED BEFORE IT IS WRITTEN DOWN, and the order is the whole of it. [record] suspends,
        // so a cancellation that arrives with the failure (the composer is closing while the socket
        // gives up) throws THERE — and a booking placed after it would never run at all, on a row
        // that has just failed. Booked first, the worst case is a row that keeps the backoff it
        // already had and an appointment that finds it due: a resumption instead of nothing. The
        // instant booked is the attempt's own, computed above and independent of the write.
        schedule(
            LocalDraftUploadJob(
                durable.accountId,
                durable.id,
                localDraftScheduleDelayMillis(attempt.notBeforeMillis, nowMillis),
            ),
        )
        record(durable, attempt)
        return DraftSaveOutcome.KEPT_ON_DEVICE
    }
    // ⛔ [NonCancellable]: by here the draft IS on the server and the original it replaced has been
    // destroyed. `discardLocalDraft` swallows the cancellation its Room delete throws and deletes
    // the staged files all the same, so a stop here leaves a row describing bytes that no longer
    // exist — which the deferred worker then uploads a second time, amputated.
    withContext(NonCancellable) { discard(durable) }
    return outcome
}

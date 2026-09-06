package app.sterna.core.data.mail

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.text.Block
import app.sterna.core.data.text.Inline
import app.sterna.core.data.text.Link
import app.sterna.core.data.text.RichBody
import app.sterna.core.data.text.Span
import app.sterna.core.data.text.richBodyFrom
import app.sterna.core.jmap.model.EmailBodyPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/*
 * Reopening a draft the phone holds and the server has not got (#95), as functions a JVM test can
 * EXECUTE: `ComposeViewModel` is an `AndroidViewModel` and `MailRepository` needs Room, an Android
 * context and a live session, so nothing inside either can be reached from one.
 *
 * The table has been written on every save since the first volets of #95 and displayed since the
 * last one, and NOTHING opened it: the row was drawn with its "not on the server yet" pill and the
 * tap did nothing at all. Everything here exists to end that — and to do so without letting the
 * reopen ask a server about a draft no server has ever heard of.
 */

/** Which store holds the draft a composer is being opened on — the whole of the routing. */
enum class DraftOpenRoute {
    /** No draft: a fresh compose, a reply, a forward, a restored undone send. */
    NONE,

    /** A row of `local_drafts`: this phone is the only place the text exists. */
    LOCAL,

    /** A message the server holds, in Drafts. */
    SERVER,
}

/**
 * Which store [draftId] belongs to.
 *
 * **A decision, not a null check, and that is the point.** `ComposeViewModel.prepare` used to
 * branch on `draftId != null` alone, so a local id went down the SERVER path: `repo.fetchEmail`
 * first, which for a `local-draft:` id is `error("Message is not in the cache.")` on IMAP and an
 * unknown id on JMAP — and offline it does not even fail fast, it blocks on the connect timeout.
 * The user then gets an EMPTY composer with `compose_prefill_failed` over the only copy of her
 * text. Answered here so a `when` over three values replaces an ordering nobody could test.
 */
fun draftOpenRoute(draftId: String?): DraftOpenRoute = when {
    draftId == null -> DraftOpenRoute.NONE
    draftId.startsWith(LOCAL_DRAFT_ID_PREFIX) -> DraftOpenRoute.LOCAL
    else -> DraftOpenRoute.SERVER
}

/**
 * A row of `local_drafts` as the composer's fields — the projection, whole, in one value.
 *
 * Built by [localDraftPrefill] and handed over field by field, so a JVM test reads what the screen
 * will show instead of a lint watching a call happen.
 */
data class LocalDraftPrefill(
    /**
     * What the composer must remember as the draft it is editing — **the row's OWN id**, never
     * the server draft it stands in for. `localDraftTarget` looks a `local-draft:` id up by id and
     * re-saves onto that very row; anything else here mints a second row for the same draft at the
     * next save, with a second `Message-ID`, a second upload and a second copy in Drafts.
     */
    val editingDraftId: String,
    val to: String,
    val cc: String,
    val bcc: String,
    val subject: String,
    val body: String,
    /**
     * The inline styling [body] carries (#131), read back out of the row's `htmlBody`, and EMPTY
     * for a draft that has none — which is every draft written before #131 and every plain one
     * since. Beside [body] and not inside it: the composer holds the text in a `TextFieldValue`
     * and lays the spans over it, so both halves travel as they are stored.
     */
    val bodyRanges: Map<Inline, List<Span>> = emptyMap(),
    /**
     * The list blocks [body] carries (#131), read back out of the same `htmlBody` as the spans,
     * and EMPTY for a draft that has none. Dropped here, a draft holding a list reopens without
     * it and the next save stores the removal.
     */
    val bodyBlocks: List<Block> = emptyList(),
    /** The links [body] carries (#131), read back out of the same `htmlBody` as the spans. */
    val bodyLinks: List<Link> = emptyList(),
    /** Reveal the Cc/Bcc row, so a draft that carried them does not hide them on reopen. */
    val expand: Boolean,
    /** The address the draft was written as — the identity to pre-select (#31). */
    val fromEmail: String?,
    val inReplyTo: List<String>,
    val references: List<String>,
    /** Rebuilt from the durable descriptors: the bytes are in the draft's own directory. */
    val attachments: List<EmailBodyPart>,
    /**
     * Whether the body on the row is known to be a lossy reading of the message it replaces.
     * Carried through the reopen so a re-save or a send cannot LAUNDER it: `false` here is
     * "destroy authorised" for an original this composer cannot reproduce (#63).
     */
    val bodyIsLossy: Boolean,
    /**
     * The SERVER draft this row stands in for (`replacesEmailId`) — the message the Drafts list
     * takes out for as long as the row exists (`replacedServerIds` / `hidesServerRow`, in the app
     * module's `ui/inbox/LocalDraftRows.kt`; only where the row stands for itself, a
     * conversation's representative is drawn anyway) — and null for a draft written from scratch,
     * which stands in for nothing.
     *
     * It is NOT "the draft being edited", and it must never be armed as one: [editingDraftId] is
     * the row's own id, and putting a server id there mints a second row per save. This is carried
     * for one purpose — emptying the draft has to be able to name the server copy it is replacing,
     * because consuming the row alone UNMASKS it and puts the pre-edit text back in Drafts for good
     * (#95 × #69).
     */
    val replacedServerDraftId: String?,
    /**
     * The IMAP numbering [replacedServerDraftId] was named under, frozen when the row was written
     * (`localDraftUidValidity`) and **never re-read** — that re-reading is the whole of #99.
     * Null on JMAP and null when nothing is replaced.
     *
     * A field of its own, beside the id and never merged with the composer's own
     * `editingDraftUidValidity`: a numbering that sits next to an id the server never issued is a
     * licence to expunge a UID under a numbering it does not belong to.
     */
    val replacedServerDraftUidValidity: Long?,
)

/**
 * What the composer shows when it opens [row] — **read off the row and nothing else.**
 *
 * There is no fallback to the cache and none to the network, deliberately: the id names a row no
 * server has ever heard of, so `fetchEmail`/`cachedEmail` can only fail, and offline they fail
 * slowly. The row is the whole truth about this draft.
 *
 * `htmlBody` IS projected since #131, and it is read FIRST: a draft this app styled stores
 * its bold as an HTML part, the text column holds the same letters stripped of it, so a reopen
 * that read the text would show the letters and silently drop every span. `richBodyFrom` parses
 * the HTML when it is ours and falls back to the stored text otherwise — an HTML written
 * elsewhere, or one carrying a signature imported as is, reopens as text exactly as it did before.
 * (The KDoc here used to say the column was structurally always null. It was, until `saveDraft`
 * gained its `html` parameter.)
 *
 * One column is deliberately NOT projected: `requestReceipt`, stated debt — the composer owns
 * that box in a `rememberSaveable` and the reopen of a SERVER draft does not re-arm it either
 * (D1). Reopening therefore clears a request the draft carries — the same debt, now reachable
 * from one more screen.
 */
fun localDraftPrefill(row: LocalDraftEntity): LocalDraftPrefill {
    val cc = localDraftAddresses(row.cc)
    val bcc = localDraftAddresses(row.bcc)
    // The HTML first, the stored text as the fallback — and BOTH halves out of the one answer:
    // taking the text from `row.textBody` here and the spans from the parse would let a row whose
    // two columns disagree open with spans laid over the wrong letters.
    val rich: RichBody = richBodyFrom(row.htmlBody) { row.textBody }
    return LocalDraftPrefill(
        editingDraftId = row.id,
        to = localDraftAddresses(row.toAddresses).joinToString(", "),
        cc = cc.joinToString(", "),
        bcc = bcc.joinToString(", "),
        subject = row.subject,
        body = rich.text,
        bodyRanges = rich.ranges,
        bodyBlocks = rich.blocks,
        bodyLinks = rich.links,
        expand = cc.isNotEmpty() || bcc.isNotEmpty(),
        fromEmail = row.fromEmail,
        inReplyTo = localDraftMessageIds(row.inReplyTo),
        references = localDraftMessageIds(row.references),
        attachments = localDraftUploadAttachments(row.attachmentsJson),
        bodyIsLossy = row.bodyIsLossy,
        replacedServerDraftId = row.replacesEmailId,
        replacedServerDraftUidValidity = row.replacesUidValidity,
    )
}

/** A space-separated message-id column as the headers hold it, blanks dropped. */
private fun localDraftMessageIds(column: String?): List<String> =
    column.orEmpty().split(" ").map { it.trim() }.filter { it.isNotEmpty() }

/**
 * What a composer asking for one of this phone's own drafts gets back (#95) — **three answers,
 * because the two failures are NOT the same failure and must not say the same sentence.**
 *
 * A row that is GONE is gone: the upload worker consumed it between the list being drawn and the
 * tap landing, or the user threw it away. A row that is BUSY is on the phone, whole, and will be
 * openable again in a moment — someone else simply owns it right now. Collapsed into one `null`,
 * as they were, the screen could only tell the user one thing about two different situations.
 */
sealed interface LocalDraftEdit {
    /** The lease is taken: [row] is the draft, and it is now [LocalDraftState.EDITING]. */
    data class Taken(val row: LocalDraftEntity) : LocalDraftEdit

    /** No row under that id at all. Nothing was written, and there is nothing to give back. */
    object Gone : LocalDraftEdit

    /**
     * The row is there, and its files are being staged or uploaded right now
     * ([localDraftMayBeTakenForEdit]). Nothing was written, and nothing may be given back
     * either: giving back a row this composer never held hands someone else's draft to the upload
     * worker.
     */
    object Busy : LocalDraftEdit
}

/**
 * Whether a composer may take the `EDITING` lease on a row in [state] — **everything but the two
 * states that have a REAL, concurrent owner**: [LocalDraftState.STAGING] and
 * [LocalDraftState.UPLOADING]. The decision the lease used to take blind: it wrote `EDITING` over
 * whatever it found, while its own twin [giveLocalDraftEditBack] tests its state and the model its
 * documentation claims (`MailRepository.takeOutboxForEdit`) tests its own too.
 *
 * What the two refusals cost, and neither is theoretical — both are a row the user can tap in the
 * Drafts list right now:
 *  - [LocalDraftState.UPLOADING] belongs to the worker, which on success DESTROYS the row and its
 *    attachment directory (`discardLocalDraft`). Opened, the composer is filled from a row that is
 *    deleted under it a second later: the "Save" that follows lands on a fresh row with no files,
 *    beside the copy the server has just accepted;
 *  - [LocalDraftState.STAGING] still carries `attachmentsJson = "[]"` while its files are being
 *    copied. Opened, it shows a draft AMPUTATED of its attachments — and the next save writes that
 *    amputation onto the row, for good.
 *
 * **[LocalDraftState.EDITING] is TAKEN, and that is the hot recovery this whole branch exists
 * for.** It looks like someone else's lease and it is not one: `ComposeViewModel` reaches a local
 * draft by one route only (`SternaApp.onEditDraft`), from the Drafts list, which sits UNDER the
 * composer in a single back stack — so two composers cannot hold one row, and an `EDITING` row
 * found at an open is this phone's own lease, left behind by a process that died holding it.
 *
 * Refusing it re-opens the defect the branch was written against, and on the very path it exists
 * to survive. Process killed with the composer open: the row stays `EDITING` — the startup sweep
 * does not take it back any more ([LocalDraftDao.revertUploadingToPending]), precisely so that the
 * restored composer finds it exactly where it left it. Refused, it is answered
 * [LocalDraftEdit.Busy] and returns BEFORE arming `editingDraftId` / `editingLocalDraftId` /
 * `replacesEmailId`; but `applied` is a `rememberSaveable` that survived the process, so
 * `draftReopenView` draws `Editor` — the sentence below is never shown — and the user is in a
 * normal composer DETACHED from its row. "Save" then mints a SECOND local row; "Send" consumes
 * nothing, and the worker later files a copy of the already-sent message in the server's Drafts,
 * permanently.
 *
 * And the sentence the refusal shows (`compose_draft_load_failed`, "… Close this screen and open
 * it again.") becomes exactly true again: the two remaining states are transitory for good, so
 * reopening really does work.
 *
 * Exhaustive over the four states, deliberately, in the shape [localDraftUploadIsDue] next door
 * already has: a fifth state has to be answered out loud here rather than inherit an `else`.
 */
fun localDraftMayBeTakenForEdit(state: LocalDraftState): Boolean = when (state) {
    LocalDraftState.PENDING, LocalDraftState.EDITING -> true
    LocalDraftState.STAGING, LocalDraftState.UPLOADING -> false
}

/**
 * Take the row [id] out of the upload worker's way while a composer holds it, and hand it back to
 * the caller — the writer [LocalDraftState.EDITING] never had.
 *
 * **The state everything already respects and nothing ever wrote.** `LocalDraftDao.pending`
 * excludes it and `localDraftUploadIsDue` excludes it — but while the row was unreachable nothing
 * ever set it. And nothing but [giveLocalDraftEditBack] ever unsets it: the startup sweep
 * (`LocalDraftDao.revertUploadingToPending`) leaves the state alone, so this write is a promise the
 * composer alone can keep. Reopening the row opens exactly the race it was written for: the
 * network comes back while the composer is open, the worker uploads, and `discardLocalDraft` takes
 * the row AND its attachment directory away under the user. Her "Save" then lands on a fresh row
 * with no files, and the server keeps a copy of the text she was still editing.
 *
 * **And only over a row that is free** ([localDraftMayBeTakenForEdit]). A refused row is
 * answered [LocalDraftEdit.Busy] and is **not written at all**: no [setState], nothing. Marking
 * it would be the very theft the state exists to prevent, in the other direction.
 *
 * A row that is not there is NOT marked either: [setState] is scoped by `(accountId, id)` and
 * would be a silent no-op, but answering [LocalDraftEdit.Gone] without writing is what tells the
 * composer there is nothing to give back later.
 */
internal suspend fun takeLocalDraftEdit(
    id: String,
    load: suspend (String) -> LocalDraftEntity?,
    setState: suspend (LocalDraftState) -> Unit,
): LocalDraftEdit {
    val row = load(id) ?: return LocalDraftEdit.Gone
    if (!localDraftMayBeTakenForEdit(row.state)) return LocalDraftEdit.Busy
    setState(LocalDraftState.EDITING)
    return LocalDraftEdit.Taken(row)
}

/**
 * Take the lease, remember it, and **give it back yourself if the screen goes while the take is
 * still out** (#95).
 *
 * The composer's back gesture and its X both pop the `NavBackStackEntry` the view model is scoped
 * to, so `viewModelScope` dies where it stands — and it can die INSIDE [take], after Room has
 * written `EDITING` and before the caller has resumed to remember the id. The row was then leased
 * to a screen that no longer exists and to nobody who remembers it: `abandon()` finds a null field
 * and gives nothing back, the worker's `pending` excludes the row, `localDraftUploadIsDue` excludes
 * it — so the draft is **never uploaded again** until the app is next started cold, and not a word
 * is said. Whoever takes gives back.
 *
 * **The give-back is sheltered from cancellation**, the shape [commitThenConsumeLocalDraft]
 * already uses: it runs on a coroutine that has just been cancelled, so an unsheltered suspend
 * inside it (a Room write is one) would throw before writing anything and change nothing at all.
 *
 * **And only on the cancelled path.** [arm] does not suspend, so nothing can be cancelled
 * between a successful [take] and it: once armed, the id is remembered and `abandon()` is the one
 * that gives the row back. Releasing here as well would put it back to `PENDING` twice and book a
 * second upload for nothing.
 *
 * [giveBack] may run over a row the cancellation caught BEFORE `EDITING` was written. That is
 * accepted and costs a read: `giveLocalDraftEditBack` refuses every state but `EDITING`, so a row
 * still `PENDING` is left exactly as it is. We cannot know which side of the write we were on, and
 * the other choice — do nothing and hope — is the defect.
 */
suspend fun takeLocalDraftEditOrGiveItBack(
    id: String,
    take: suspend (String) -> LocalDraftEdit,
    arm: (LocalDraftEntity) -> Unit,
    giveBack: suspend (String) -> Unit,
): LocalDraftEdit {
    val lease = try {
        take(id)
    } catch (cancelled: CancellationException) {
        // The screen went while the take was out. The row may already be EDITING with nobody left
        // to remember it, and this coroutine is the only one that ever knew — so it gives it back
        // before it dies, out of reach of the very cancellation that is killing it.
        withContext(NonCancellable) { giveBack(id) }
        throw cancelled
    }
    // Past here nothing suspends before [arm], so the id cannot fail to be remembered: from now on
    // the release belongs to `abandon()`, once.
    if (lease is LocalDraftEdit.Taken) arm(lease.row)
    return lease
}

/**
 * Give a row taken with [takeLocalDraftEdit] back to the upload worker, and re-arm the job that
 * would have uploaded it — **the only way out of [LocalDraftState.EDITING] there is.**
 *
 * The startup sweep does not touch that state ([LocalDraftDao.revertUploadingToPending]): a cold
 * start cannot tell a row a restored composer is about to take from one nobody will ever claim, and
 * guessing wrong hands the text being typed to the upload worker. So there is no net under this —
 * a screen that leaves without calling it leaves the draft held for ever, and `abandonPlan` is where
 * that is decided, path by path.
 *
 * **Only an `EDITING` row**, exactly as `MailRepository.releaseOutboxEdit` does: any other state
 * means this composer no longer owns the row — a save has already re-written it `PENDING`, or an
 * upload is in flight — and writing over it would fight whoever does own it. Applied to an
 * `UPLOADING` row it would hand the worker's own row back to itself mid-flight.
 *
 * [schedule] is handed **what is LEFT of the row's own backoff** ([localDraftScheduleDelayMillis],
 * the delay every other booking in this file is made with), and the delay is computed HERE so a
 * test can read the value that goes out. A re-arm at 0 is not a detail: a draft whose last attempt
 * a server REFUSED would retry the instant its composer is opened and closed, as fast as the worker
 * is allowed to, on battery — and opening and closing is free. The re-arm itself is not optional
 * either: the work item booked for this draft may well have fired while the composer held the row,
 * found it not pending and gone away, so without a fresh one the draft waits for the next START of
 * the app.
 */
internal suspend fun giveLocalDraftEditBack(
    id: String,
    nowMillis: Long,
    load: suspend (String) -> LocalDraftEntity?,
    setState: suspend (LocalDraftState) -> Unit,
    schedule: suspend (Long) -> Unit,
) {
    val row = load(id) ?: return
    if (row.state != LocalDraftState.EDITING) return
    setState(LocalDraftState.PENDING)
    schedule(localDraftScheduleDelayMillis(row.notBeforeMillis, nowMillis))
}

/**
 * The `draftEmailId` a SEND may carry to the outbox (or to the scheduled table) — **never one of
 * this phone's own draft ids** (#95). The twin of [serverDraftReplacedBy] on the other route, and
 * deliberately the same decision rather than a second copy of it.
 *
 * That id is what the delivery worker destroys once the message is on its way: on JMAP an
 * `Email/set` destroy naming something the server never issued, on IMAP a name that ends in a
 * number — and a number always means SOMETHING in a folder. A reopened local draft made the
 * composer carry exactly such an id, and the only thing standing between it and a destroy was the
 * server refusing the name. That is not a guard.
 *
 * The local row is not forgotten by answering null here: it is consumed on the phone, by
 * [commitThenConsumeLocalDraft], which is where a draft that has just been sent belongs.
 */
fun sendDraftEmailId(draftEmailId: String?): String? = serverDraftReplacedBy(draftEmailId)

/**
 * The draft a SEND destroys once the message is on its way: the id, and the IMAP numbering that id
 * was named under — **one answer, never two decisions** (#95 × #99).
 *
 * The two halves are only meaningful together. An id taken from one place beside a numbering taken
 * from another is the worst of both: on IMAP an expunge is a UID opposed to a `UIDVALIDITY`, so a
 * row's id under the composer's numbering deletes ANOTHER message of Drafts, and the composer's id
 * under the row's numbering deletes nothing while the app believes it did. Null takes its numbering
 * with it, for the same reason.
 */
data class SendDraftTarget(
    /** What the delivery worker destroys — never one of this phone's own ids ([sendDraftEmailId]). */
    val emailId: String?,
    /** The numbering [emailId] was read under, frozen at the open and never re-read (#99). */
    val uidValidity: Long?,
)

/**
 * Which server draft this send replaces, and under which numbering.
 *
 * Two composers reach this, and they hold their id in different fields:
 *  - reopened from the SERVER — [editingDraftId] is the server's own id, and the pair the composer
 *    froze when it read it is the answer;
 *  - reopened from a row of `local_drafts` — [editingDraftId] is the ROW's id (that is deliberate:
 *    `localDraftTarget` re-saves onto it), for which [sendDraftEmailId] answers null, correctly. The
 *    server draft is then named by [replacedServerDraftId], carried off the row's `replacesEmailId`
 *    with its frozen `replacesUidValidity`.
 *
 * **The second case is a defect closed, not a refinement.** Without it NOBODY named the server
 * draft on a send: the row is consumed by the send, the mask the Drafts list draws over the server
 * copy (`replacedServerIds` / `hidesServerRow`) lifts with it, and the draft comes back at the top
 * of Drafts carrying the text from BEFORE the edit — beside the message that has just gone out, and
 * permanently.
 *
 * [replacedServerDraftId] goes through [sendDraftEmailId] too, in belt: nothing but
 * `localDraftTarget` writes that column, but a local id reaching a destroy is a name ending in a
 * number and a number always means something in a folder.
 *
 * This does NOT decide whether the destroy is DESERVED — that is the fidelity verdict
 * (`bodyIsLossy` / `editingDraftLossy`), read further down the line by `enqueueSend` and by the
 * scheduled row's own `takeUnless`. The two questions are separate and neither replaces the other.
 */
fun sendDraftTarget(
    editingDraftId: String?,
    editingDraftUidValidity: Long?,
    replacedServerDraftId: String?,
    replacedServerDraftUidValidity: Long?,
): SendDraftTarget {
    // The composer's own pair, whole, whenever the id it holds is one a server issued.
    sendDraftEmailId(editingDraftId)?.let { return SendDraftTarget(it, editingDraftUidValidity) }
    // Otherwise the row's pair, whole — or nothing at all, numbering included.
    val replaced = sendDraftEmailId(replacedServerDraftId)
        ?: return SendDraftTarget(null, null)
    return SendDraftTarget(replaced, replacedServerDraftUidValidity)
}

/**
 * The same pair, withheld WHOLE when the composer cannot show the message reproduces the draft
 * (#63) — the scheduled row's own guard, and a different question from [sendDraftTarget]'s.
 *
 * The scheduled table has no column for the verdict, so the id itself carries it: an id on the row
 * means "destroy authorised", no id means "keep the original, live with the duplicate in Drafts".
 * `ScheduledSendWorker` relies on exactly that when it re-enters `enqueueSend` with
 * `bodyIsLossy = false`.
 *
 * The numbering goes with the id, in the one place that decides. A number beside a null id
 * destroys nothing today, so this is not a bug being fixed — it is the STATEMENT: the pair is one
 * decision, and half a pair left on a row that waits for hours is the shape the next reader copies.
 */
fun SendDraftTarget.unlessBodyIsLossy(bodyIsLossy: Boolean): SendDraftTarget =
    if (bodyIsLossy) SendDraftTarget(null, null) else this

/**
 * The same pair, withheld WHOLE when the draft was read under ONE account and the message is being
 * written under ANOTHER — a REFUSAL, and the only one on this branch (#31 × #63).
 *
 * Everywhere else an account mismatch was a destroy sent to the wrong server, and the fix was to
 * re-address it to the right one ([SendDraftTarget] carries the numbering for exactly that reason).
 * Here it cannot be: the "From" picker offers the identities of EVERY account, so a draft opened in
 * A and sent — or re-saved, or scheduled — as B puts the NEW copy on B while the original lives on
 * A. Destroying the original would need one transaction spanning two servers, and there is none. So
 * the id is not carried at all: nothing is destroyed, the original stays in A's Drafts where the
 * user can see it and delete it by hand, and the duplicate is the price.
 *
 * **Not a change of behaviour — a change of REASON.** Uncarried is already what happens, by
 * accident: on IMAP `draftAddressingIsCarried` reads the draft under B, finds nothing, and refuses;
 * on JMAP `destroyAll` spares an id the server never issued. Both accidents hold only as long as
 * the id names nothing in B — and an id IS a number in a folder on IMAP, where two accounts on one
 * server can share a UIDVALIDITY. This states it instead, once, where the row is written.
 *
 * **A null [draftAccountId] does NOT refuse, and that laissez-passer RESTS ON AN INVARIANT
 * HELD ELSEWHERE.** Null is read as "no server draft is open behind this composer" — a brand-new
 * message, a reply, a local row that replaces nothing — which is the ordinary route. That reading
 * is only true while **a server draft id never travels without the account it was read under**:
 * `ComposeViewModel.prepare()` arms `editingDraftId` and `editingDraftAccountId` in one gesture, at
 * the entry of the reopen branch, before the coroutine and before anything that can fail
 * (`DraftNumberingFreezeWiringTest` pins that order, and says what is lost if it drifts).
 *
 * It was NOT always true, and the day it was not this function was the hole. While the account
 * was frozen after `repo.fetchEmail` and the id before it, any failed reopen — offline, the
 * process killed and `prepare()` re-run under a bar the composer had already redrawn, or the early
 * `credentials() ?: return` — left an armed id beside a null account. The pair applied here was
 * then `(D, null)`, not `(null, null)`: switching "From" to another account and saving walked
 * straight through, and the save landed on a row `forServerDraft` matched under the WRONG account
 * — server draft ids collide between two accounts of one server (#31, `LocalDraftSave`).
 *
 * Refusing on null instead would be free of consequence today and a trap the day it is not: it is
 * the ordinary route's own shape.
 *
 * A null [writingAccountId] DOES refuse: the two cannot be shown to be the same account, and an
 * unproven sameness is exactly what this exists to stop.
 *
 * Not a `Boolean` parameter and not a `Boolean` result: [SendDraftTarget] exists because the id
 * and its numbering are meaningful only together, and a caller handed a "may destroy" flag can
 * still write the id on the row and leave the numbering behind — half a pair, which is the shape
 * every defect on this path has taken.
 */
fun SendDraftTarget.unlessDraftBelongsElsewhere(
    draftAccountId: String?,
    writingAccountId: String?,
): SendDraftTarget =
    if (draftAccountId != null && draftAccountId != writingAccountId) SendDraftTarget(null, null)
    else this

/**
 * Which account the draft a composer carries was read under — the account [unlessDraftBelongsElsewhere]
 * opposes to the one being written as.
 *
 * **One function for the three sites, because it answers the same question [sendDraftTarget]
 * does, with the same discriminator.** A composer holds its draft id in one of two shapes, and the
 * account lives in a different field for each:
 *  - a SERVER id ([sendDraftEmailId] answers it) — reopened from Drafts, or restored from an undone
 *    send. The account is [serverDraftAccountId] (`editingDraftAccountId`), frozen beside the id
 *    and its numbering at the open;
 *  - a `local-draft:` id — reopened from a row of this phone's own store. That route arms no server
 *    account at all, deliberately: what answers is the account the ROW was LEASED under
 *    ([localRowAccountId], `editingLocalDraftAccountId`), which is also the account of the server
 *    draft the row stands in for (`replacesEmailId` is written by `localDraftTarget`, scoped to
 *    that same account), i.e. of the pair [sendDraftTarget] takes off the row.
 *
 * **Not `serverDraftAccountId ?: localRowAccountId`.** The two are mutually exclusive today —
 * one composer takes one route — and a `?:` would be right by that coincidence alone, silently, the
 * day a route arms both. The discriminator is the id itself, which is what the pair is taken from.
 *
 * Null means "unknown", not "same", and [unlessDraftBelongsElsewhere] lets the pair through on
 * it — deliberately, see its on a null draft account. The hole that leaves is the record an
 * undone send keeps: it files the account the "From" picker was SHOWING, while the guard opposed the
 * account the message actually went out under, and those two part company when the shown account has
 * no credentials to resolve. Narrow — it wants an account listed but unusable, an undone send, and
 * that account gaining its tokens afterwards — and stated rather than closed, because closing it
 * means moving what an undone send restores its identity from.
 */
fun carriedDraftAccountId(
    editingDraftId: String?,
    serverDraftAccountId: String?,
    localRowAccountId: String?,
): String? =
    if (sendDraftEmailId(editingDraftId) != null) serverDraftAccountId else localRowAccountId

/**
 * What leaving the composer without committing the message anywhere gives back, in values — the
 * queued outbox row (#70) and the phone's own draft row (#95), decided together and independently.
 */
data class AbandonPlan(
    /** The `local_drafts` row to put back from EDITING to PENDING, and re-arm the upload of. */
    val localDraftToRelease: String?,
    /** The outbox row to put back from EDITING to QUEUED, and re-arm delivery of. */
    val outboxToRelease: Long?,
    /**
     * The outbox row to PARK instead — FAILED at the cap, "editing was interrupted" — because the
     * composer closed over a body it lost. Never the same row as [outboxToRelease]: one row, one door.
     */
    val outboxToPark: Long?,
)

/**
 * The close button, the back gesture, "Discard" — what goes back to whom.
 *
 * **[sending] gives back NOTHING**, and that is a defect closed. A send in flight owns the
 * message: it consumes the outbox row (`consumeEditingOutbox`) and the local row
 * ([commitThenConsumeLocalDraft]) itself when it lands. Releasing the local row from under it — as
 * closing the screen did, because the release sat ABOVE the guard — puts the row back to PENDING,
 * the upload worker takes it, and a copy of the message that has just gone out is filed in the
 * server's Drafts, permanently.
 *
 * **And the two are independent**, which is why the release cannot simply be moved to the bottom
 * of `abandon()` instead: every reopened local draft has a null outbox id, so written under
 * `editingOutboxId ?: return` the release would never run at all and the row would stay EDITING for
 * ever — held back from the upload worker, and no longer rescued by anything: the startup sweep
 * leaves `EDITING` alone on purpose, so this release is the only way out. The place is the middle
 * one: under the [sending] guard, above the outbox
 * `?: return`.
 *
 * **[emptying] withholds the phone's own row, and only that one** (#95 × #69). The close that
 * accompanies a deletion may not hand the row to the upload worker: it would go back to `PENDING`
 * with its upload re-armed at the row's own instant — for a draft saved with no network, NOW — and
 * the pre-edit text the user has just erased would race the delete to the server and be filed in
 * Drafts. The queued outbox row is a different question and keeps its answer: nothing is being
 * saved, so a message reopened from the outbox is not being moved anywhere and goes back to the
 * queue — or is parked, see [parkOutbox] — rather than evaporating on an empty save (#70).
 *
 * **Why it is an argument here and not a null slipped into [localDraftId] by the caller**, which
 * is how it was written: one field cannot carry two decisions. `editingLocalDraftId` is "which row
 * this composer holds", and blanking it to mean "give nothing back this once" also erased the
 * answer to the first question — so a `submit` that FAILED left the row `EDITING` with nobody left
 * to release it. Held back from the upload worker and rescued by nothing — the startup sweep leaves
 * `EDITING` alone — the draft is then never uploaded again at all, on the very path where the user
 * has been told her deletion failed.
 * And "give it back on the failure" is NOT the fix: the composer is still open, she still holds
 * the row, and `EDITING` is the true state. Nothing is lost by keeping it — the text is on the
 * phone — while releasing it sends the server the text she has just erased.
 *
 * **[parkOutbox] sends the queued row through the OTHER door** — parked, not released. The
 * composer closed over a body it had lost and never got back (`closingParksQueuedRow`, on the raw
 * body of the editor): released, the row goes back to QUEUED and is re-armed at 0, and the message
 * the screen had just told her to close and reopen is delivered in under a second. Parked
 * (`MailRepository.parkInterruptedOutboxEdit`), it stays in the Outbox as FAILED, says why, and
 * reopens. The two doors are exclusive — [AbandonPlan.outboxToRelease] and [AbandonPlan.outboxToPark]
 * never both name the row — and [sending] still beats both: a send in flight owns the row and
 * consumes it, so it is neither parked nor given back. [emptying] is not consulted for it: that flag
 * only ever decides the phone's own row.
 */
fun abandonPlan(
    sending: Boolean,
    emptying: Boolean,
    parkOutbox: Boolean,
    localDraftId: String?,
    outboxId: Long?,
): AbandonPlan {
    if (sending) return AbandonPlan(null, null, null)
    val release = if (parkOutbox) null else outboxId
    val park = if (parkOutbox) outboxId else null
    if (emptying) return AbandonPlan(null, release, park)
    return AbandonPlan(localDraftId, release, park)
}

/**
 * Who gets the phone's own draft row back once a SAVE is over (#95 × #31) — the row to put from
 * `EDITING` back to `PENDING`, or null when nobody may have it. The exact twin of
 * `localDraftLeaseAfterDelete`, with the boolean the other way round.
 *
 * **Until this existed a save answered the question NOWHERE, and that is the defect.**
 * `abandon` is the only other release, and only the CANCEL of the screen reaches it: a save closes
 * the composer on `ComposeState.Done` without passing through, on the understanding that the save
 * has rewritten the row itself. It has — unless the "From" picker was moved to another account,
 * which it may be for any draft, the picker covering every account on the phone. Then
 * [unlessDraftBelongsElsewhere] answers `SendDraftTarget(null, null)`, `localDraftTarget` mints a
 * NEW row under the account being written as, and the row that was reopened is left `EDITING` for
 * ever — the startup sweep leaves `EDITING` alone, deliberately. It sits at the top of its own
 * account's Drafts under its "not on the server yet" pill, masking for good the server draft it
 * replaces, and it never goes up. Nothing is said, nothing is logged.
 *
 *  - [saved] true — the row goes back to the upload worker. On a save under the SAME account this
 *    is a real no-op: the save has already rewritten the row `PENDING`, or consumed it after an
 *    upload that got through, and [giveLocalDraftEditBack] refuses any state that is not
 *    `EDITING`. On a save under ANOTHER account the save never touched that row — it created a
 *    fresh one under the account being written as — and this is the only thing left that can
 *    release it;
 *  - [saved] false — NOTHING is given back, and that is not a precaution: the composer is still
 *    open, the user still holds the row, and `EDITING` is the true state. Released here, the
 *    upload worker takes the text she is in the middle of typing up to the server and
 *    `discardLocalDraft` then deletes the row with its attachment folder, under a banner that says
 *    the save failed. Word for word the argument [abandonPlan] already carries: giving it back on
 *    the failure is NOT the fix.
 *
 * [lease] is read AS IT STANDS at the end of the gesture, never captured at the start — the same
 * sentence as `localDraftLeaseAfterDelete`, and sharper here: on the empty-save route the row is
 * consumed INSIDE the gesture, so an id captured beforehand is stale, and released from it the row
 * goes back to `PENDING` and the upload worker files in the server's Drafts the draft that has just
 * been deleted, permanently.
 *
 * **What is left behind is stated and kept, not a defect to fix here** — and it is ONE draft per
 * account, not two in either. A keeps what its row holds, i.e. the text from BEFORE the edit that
 * was saved under B; B receives a fresh copy carrying the edit. [unlessDraftBelongsElsewhere] cuts
 * the link between them on purpose: no transaction spans two servers, so nothing here may claim the
 * edit reaches A, and nothing here may destroy A's copy on B's behalf. Releasing the row is what
 * lets it finally go up, and its upload then carries its own `replacesEmailId` — so on A the row
 * takes the place of the server draft it was masking, rather than piling a second one on top of it.
 */
fun localDraftLeaseAfterSave(lease: String?, saved: Boolean): String? =
    if (saved) lease else null

/**
 * Commit the composer's message somewhere else — an outbox row, a scheduled-send row — and ONLY
 * THEN consume the local draft row it was reopened from (#95).
 *
 * **The order is half the correction, and it is grid rule 1 (nothing irreversible).** The local
 * row is the only copy of the text; [commit] is what creates the other one. Consumed first — or in
 * a `finally` — a commit that throws (no Drafts folder, a full disk, a `PgpInteractionNeeded` on
 * the way out) leaves the message on neither side. So the consume is on the success path and
 * nowhere else, which is exactly the shape `discardLocalDraft` already has on the upload path.
 *
 * And it must HAPPEN. Left behind, the row stays at the top of Drafts with its "not on the server
 * yet" pill after the message has gone — in `EDITING`, which no sweep takes back any more — and it
 * stays there over restarts, hiding the server draft it names (`replacesEmailId`) behind it. Reopen
 * and close that ghost once and the release hands it to the upload worker, which files a copy of an
 * already-sent message in the server's Drafts, for good.
 *
 * **Which is why the pair is SHELTERED FROM CANCELLATION, and both halves together.** The caller
 * is `viewModelScope`, and that scope dies with the screen: the composer's X stays live during a
 * send (closing an editor is not blocked — K-9 and Gmail do not block it either), it pops the
 * `NavBackStackEntry` the view model is scoped to, and everything below the tap is cancelled where
 * it stands. That point can fall INSIDE [commit], after the outbox row has been inserted and while
 * `enqueueSend` is still staging attachment files and booking the worker: the message is going out,
 * the row is not consumed, and the paragraph above happens. Sheltering the consume alone would not
 * close it — the window is inside the commit — so the shelter holds both.
 *
 * **And no wider than both.** A cancellation that arrives BEFORE the commit has started is
 * honoured, and must be: nothing has gone out, so the draft is exactly what has to survive, and the
 * row goes back to the upload worker to reach the server later. Stretched over the wait in front of
 * it, this would commit a message the user closed out of.
 *
 * Stated debt, bounded and accepted: under this shelter an `enqueueSend` that is waiting on its
 * addressing proof (a bounded budget, `MailRepository`) can no longer be interrupted by closing the
 * screen. The screen is already gone; what is delayed is a background write.
 *
 * Null [localDraftId] — every composer that is not on a reopened local draft — commits and consumes
 * nothing.
 */
suspend fun <T> commitThenConsumeLocalDraft(
    localDraftId: String?,
    commit: suspend () -> T,
    consume: suspend (String) -> Unit,
): T {
    // The boundary, read BEFORE the shelter is entered: nothing committed yet, so a screen already
    // gone takes nothing with it and the draft survives on the phone.
    coroutineContext.ensureActive()
    return withContext(NonCancellable) {
        val committed = commit()
        if (localDraftId != null) consume(localDraftId)
        committed
    }
}

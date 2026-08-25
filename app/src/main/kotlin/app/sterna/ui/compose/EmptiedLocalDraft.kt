package app.sterna.ui.compose

import app.sterna.mail.MessageDestroyWorker
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/*
 * Emptying a draft the phone kept for a SERVER draft it has not managed to replace yet
 * (#95 × #69) — as functions a JVM test EXECUTES: `ComposeViewModel` is an `AndroidViewModel` and
 * nothing in this module can instantiate it, so a decision left inside it is a decision no test
 * ever runs.
 *
 * The defect they end: a server draft S reopened with no network leaves a local row R naming it in
 * `replacesEmailId`, and S is taken out of the Drafts list for as long as R exists
 * (`replacedServerIds` / `hidesServerRow`, `ui/inbox/LocalDraftRows.kt`). Emptying the draft
 * consumed R and nothing else — so nobody named S any more, the mask lifted, and S came back at the
 * top of Drafts carrying the text from BEFORE the edit, for good. The rule of #69 ("the original is
 * deleted so no empty shell lingers in Drafts, nor reappears on sync") simply stopped being kept the
 * moment the draft went through the local row.
 *
 * ⚠ The mask is not total, and the correction does not depend on it being: `hidesServerRow` only
 * hides a row that stands for ITSELF (`row.threadCount == 1`). A server draft that is the
 * representative of a conversation is drawn beside its local row — the accepted duplicate, decided
 * where that function is written, because hiding a representative hides the whole conversation.
 */

/**
 * What the emptying of a local row does about the server draft behind it.
 *
 * Two answers rather than one nullable order, because "nothing to destroy" and "kept on purpose,
 * and the user is told so" are different events with the same null order.
 */
data class EmptiedLocalDraftPlan(
    /** The destroy to make durable, or null when nothing may be destroyed. */
    val order: MessageDestroyWorker.FolderDestroy?,
    /**
     * True when the server copy is deliberately KEPT because this phone never read all of it — and
     * the screen has to say so, or the app quietly does the opposite of what was asked.
     */
    val tellServerCopyKept: Boolean,
)

/**
 * The plan the emptying of a local row produces.
 *
 * ⛔ **The whole point is WHAT the order carries.** A destroy that reaches the worker without its
 * folder or without the numbering it was frozen under destroys NOTHING, in silence, on both
 * protocols: on JMAP an absent `mailboxId` spares every id (#122), on IMAP an absent `uidValidity`
 * answers an empty destroyable list (#99). That failure looks exactly like success from the screen —
 * which is why this is a value a test can read, rather than a call a lint can see happen.
 *
 * ⭐ **[bodyIsLossy] keeps the server copy, and that beats the rule of #69.** The row's body is what
 * this phone MANAGED to read of the draft: attachments never downloaded, HTML flattened. The user
 * empties what she SEES; destroying the server copy on the strength of it would destroy, for ever,
 * what she was never shown. The irreversible option loses (arbitration rule 1), so the copy stays
 * exactly as `ORIGINAL_KEPT` keeps it on a lossy save — and [tellServerCopyKept] carries the word
 * the screen owes her.
 *
 * [replacedServerDraftUidValidity] is handed on AS IT STANDS, straight off the row, and nothing here
 * looks one up: null is legitimate on JMAP (an id there survives anything a folder can go through)
 * and means "nothing to oppose" on IMAP, where the worker then destroys nothing and the re-query
 * brings the draft back — the safe end of an order that cannot be checked.
 *
 * ⭐ **[addressingIsProvenEmpty] keeps it too, and for the same kind of damage** — see
 * [emptiedLocalDraftKeepsServerCopy], where the term is argued. It is NOT computed here: the caller
 * reads it, exactly as it reads [mailboxId], because establishing it means asking the server.
 *
 * Empty plans, all deliberate:
 *  - [replacedServerDraftId] null — the ordinary case, a draft written from scratch that stands in
 *    for nothing at all. There is nothing to destroy, nothing to evict and nothing to say;
 *  - lossy, or an addressing not proven empty — the copy is kept, and said;
 *  - no folder ([draftDestroyFolder] answered neither) — an order with no folder would be enqueued,
 *    run, destroy nothing and evict the draft from the phone all the same. ⚠ Silent, unlike the
 *    lossy case: the copy is kept and the screen says nothing. Known debt, not a second string.
 */
fun planForEmptiedLocalDraft(
    replacedServerDraftId: String?,
    replacedServerDraftUidValidity: Long?,
    mailboxId: String?,
    bodyIsLossy: Boolean,
    addressingIsProvenEmpty: Boolean,
): EmptiedLocalDraftPlan {
    if (emptiedLocalDraftKeepsServerCopy(replacedServerDraftId, bodyIsLossy, addressingIsProvenEmpty)) {
        return EmptiedLocalDraftPlan(null, tellServerCopyKept = true)
    }
    val serverDraft = replacedServerDraftId ?: return EmptiedLocalDraftPlan(null, tellServerCopyKept = false)
    val folder = mailboxId?.takeIf { it.isNotEmpty() } ?: return EmptiedLocalDraftPlan(null, tellServerCopyKept = false)
    return EmptiedLocalDraftPlan(
        MessageDestroyWorker.FolderDestroy(folder, listOf(serverDraft), replacedServerDraftUidValidity),
        tellServerCopyKept = false,
    )
}

/**
 * Is the copy on the server KEPT by this gesture? — the one fact the app owes the reader about a
 * draft it is taking away, and it is owed TWICE.
 *
 * ⛔ **The two gestures ask it at two different moments, and the second one is the correction.**
 * The empty-save asks it afterwards, to post the notice ([EmptiedLocalDraftPlan.tellServerCopyKept]).
 * The delete button has to ask it BEFORE the finger: its confirmation used to say "this draft hasn't
 * reached the server … this can't be undone" over a row that MASKS a server copy this phone never
 * read whole — the copy stays (that is [planForEmptiedLocalDraft]'s own rule, and rule 1 of the
 * grid), the mask lifts with the row, and the draft from BEFORE the edit comes back at the top of
 * Drafts. The dialog was announcing the opposite of what the app does, and the only correction on
 * offer was a toast, after the fact, naming a gesture the user had not made.
 *
 * ⭐ A function, executed, and NOT an `if` inside the composable: `ComposeScreen` is a composable and
 * `ComposeViewModel` an `AndroidViewModel` — a decision left in either is a decision no JVM test
 * here ever runs, and this one decides whether a warning is shown before an irreversible tap.
 *
 * ⭐ **[addressingIsProvenEmpty] is the term the ordinary save has always had and this gesture had
 * not.** A save may replace a server copy only once the replacement PROVABLY carries the addresses
 * the original was addressed to (`draftReplacementIsFaithful`, term `addressingIsCarried`); the
 * emptying, which destroys that copy and leaves not even the duplicate a save leaves, was deciding
 * on the body verdict alone. The composer cannot tell a draft that has no Cc from a Cc it never
 * managed to read (a cache row older than the v21 columns and never back-filled, a read that
 * failed, an address the envelope gave back unreadable), so "the fields are empty on screen" says
 * nothing about the copy being destroyed.
 *
 * ⛔ **The unknown KEEPS.** `addressingIsProvenEmpty == false` covers "there were addresses" AND
 * "the read did not answer", and the two are deliberately not distinguished: an unknown is not a
 * void. It is the same rule, in the same direction, as `draftAddressingIsCarried(original = null)`
 * answering false — the copy on the server is then the only thing still holding those recipients.
 *
 * The three terms, and no one of them is the question on its own:
 *  - no [replacedServerDraftId] — the ordinary draft written from scratch. Nothing is behind it, so
 *    "the copy on the server stays" would name a message that does not exist;
 *  - not [bodyIsLossy] — this phone read the draft whole, so on that count the copy behind it is
 *    destroyed by the same gesture and saying it stays is the same lie the other way round;
 *  - [addressingIsProvenEmpty] — the server itself said the original was copied to nobody, so the
 *    destroy takes no recipient with it.
 */
fun emptiedLocalDraftKeepsServerCopy(
    replacedServerDraftId: String?,
    bodyIsLossy: Boolean,
    addressingIsProvenEmpty: Boolean,
): Boolean = replacedServerDraftId != null && (bodyIsLossy || !addressingIsProvenEmpty)

/**
 * Who gets the phone's own draft row back once a DELETION asked for is over (#95 × #127) — the row
 * to put from `EDITING` back to `PENDING`, or null when nobody may have it.
 *
 * ⛔ **The lease is a decision, not a field to blank.** Blanked up front — as this gesture was
 * written — the composer answered "I hold no row" to everything downstream: a `submit` that threw
 * left the row `EDITING` with nobody left to release it and the close gave nothing back, so the row
 * sat in the Drafts list as a ghost of a draft the user had just deleted — no sweep takes `EDITING`
 * back — and the first reopen-and-close handed it to the upload worker, which filed it in the
 * server's Drafts. The same defect `abandonPlan` was written to end, one gesture further along.
 *
 * ⭐ **The failure is atomic: nothing happened.** Everything that can throw sits IN FRONT of any
 * destruction (resolving the account the row is leased under, `draftMailboxOf`, enqueuing
 * `destroyDurably`) and
 * [destroyThenConsumeEmptiedLocalDraft] consumes only after a destroy that came back. So:
 *
 *  - [destroyed] false — the row is exactly what it was, the user has been told the deletion
 *    failed, and the row goes back to the upload worker so it still reaches the server;
 *  - [destroyed] true — the row and its files are gone, consumed inside the gesture, and there is
 *    nothing left to give back to anybody. Releasing here is what re-arms the upload of a draft that
 *    has just been destroyed.
 *
 * [lease] is read AS IT STANDS when the gesture ends, never captured at the start: the close button
 * may have given the row back in between (it is live during the destroy), and releasing a row this
 * composer no longer holds is the #127 double-gesture defect.
 */
fun localDraftLeaseAfterDelete(lease: String?, destroyed: Boolean): String? =
    if (destroyed) null else lease

/**
 * Make the destroy of the server draft DURABLE, take it off the phone, say what was kept, and only
 * then consume the local row it was standing behind.
 *
 * ⛔ **The order is the correction, and it is guaranteed here rather than promised in a comment** —
 * the same shape as `commitThenConsumeLocalDraft`, for the same reason. The local row is the ONLY
 * thing that still names the server draft: consumed first, a process death in the interval leaves
 * the server draft alive, unmasked, and nothing anywhere will ever destroy it. So [destroy] runs
 * first and a [destroy] that throws consumes nothing — the row survives, the mask holds, and the
 * user is told the save failed.
 *
 * The eviction sits between the two so the draft leaves Drafts at the instant of the gesture
 * (`InboxViewModel.heldBackDestroy` does exactly this): the order is already durable when the rows
 * go, and if the worker gives up for good its `resetSyncState` brings back whatever it did not
 * destroy.
 *
 * [tell] runs before [consume], i.e. before the composer reaches `Done`: the screen is closing, and
 * a notice emitted after it is a notice nobody reads.
 *
 * An empty plan — nothing was being replaced — consumes the row and does nothing else, which is what
 * the composer has always done and is already right.
 *
 * ⛔ **And the three acts are SHELTERED FROM CANCELLATION, together** — the shape
 * `commitThenConsumeLocalDraft` carries for the same reason, one gesture away. The caller is
 * `viewModelScope`, which dies with the `NavBackStackEntry` the composer is scoped to: the back
 * gesture, in the second this runs. Cancelled between the destroy and the consume, the server copy
 * is destroyed AND the local row survives — held in `EDITING`, which no startup sweep takes back, so
 * it stays in the Drafts list as the draft the user deleted, and the first reopen-and-close hands it
 * to the upload worker, which files it in the server's Drafts brand new. Cancelled between the
 * eviction and the consume, the same. Both halves of the window
 * are inside these three lines, so the shelter holds all three.
 *
 * ⛔ **And no wider.** A cancellation that arrives BEFORE anything is destroyed is honoured, and must
 * be: nothing has gone, so the draft is exactly what has to survive — the row stays where the user
 * can still see it and the lease goes back to the upload worker ([localDraftLeaseAfterDelete]).
 * That boundary is read here rather than left to chance: [destroy] may not suspend at all (enqueuing
 * WorkManager work does not have to), and a body with no suspension point never notices it is
 * running on a scope that is already dead.
 */
suspend fun destroyThenConsumeEmptiedLocalDraft(
    localDraftId: String,
    plan: EmptiedLocalDraftPlan,
    destroy: suspend (MessageDestroyWorker.FolderDestroy) -> Unit,
    evict: suspend (List<String>) -> Unit,
    consume: suspend (String) -> Unit,
    tell: suspend () -> Unit,
) {
    // The boundary, read BEFORE the shelter is entered: nothing destroyed yet, so a screen already
    // gone takes nothing with it and the draft survives on the phone.
    coroutineContext.ensureActive()
    withContext(NonCancellable) {
        plan.order?.let {
            destroy(it)
            evict(it.emailIds)
        }
        if (plan.tellServerCopyKept) tell()
        consume(localDraftId)
    }
}

/**
 * Whose credentials destroy the SERVER draft a reopened local row stands in front of: the account
 * the ROW was leased under ([leasedUnderAccountId]), or null when there is nobody to act for.
 *
 * ⛔ **Never [composingAsAccountId].** The "From" picker offers the identities of EVERY account and
 * switching it changes nothing about the row: the server id being destroyed and the numbering it
 * was frozen under were both read off the draft of the account it was opened from. Handed the
 * writing account's credentials instead, the destroy went to a server that never issued that id and
 * failed in silence — while the local row was consumed correctly under its own account all the
 * same, so the mask it held over the server copy lifted and the text from BEFORE the edit came back
 * at the top of Drafts, for good. It is the other half of the reason the lease itself is scoped
 * (#31): ids collide between two accounts of one server, so the wrong account does not even reliably
 * fail — it can destroy the neighbour's message.
 *
 * ⭐ [composingAsAccountId] is a parameter so that the refusal is EXECUTED, not merely read out of
 * the source by a lint: a rule taking the lease alone could be re-broken at the call site with no
 * test able to see it. It is never consulted, and that is the whole decision.
 *
 * Null is the safe end, and both roads to it destroy nothing: no row is held at all, or the account
 * that held it was removed from the phone during the edit. ⛔ There is NO fallback — the fallback is
 * the defect. The caller turns the null into the failure banner it already draws, which leaves a
 * draft that is still exactly a draft.
 */
fun <T : Any> credentialsDestroyingReplacedServerDraft(
    leasedUnderAccountId: String?,
    @Suppress("UNUSED_PARAMETER") composingAsAccountId: String?,
    lookup: (String) -> T?,
): T? = leasedUnderAccountId?.let(lookup)

package app.sterna.ui.compose

/*
 * Sending a composer whose BODY IS GONE — as a function a JVM test EXECUTES: `ComposeViewModel` is
 * an `AndroidViewModel`, nothing in this module can instantiate it (no Robolectric, no
 * compose-ui-test here), so a decision left inside it is a decision no test ever runs and a lint
 * can only read the text that contains it — never what is HANDED to it.
 *
 * The defect it ends: a draft composed with no network lives only in the phone's `local_drafts`
 * row (`replacesEmailId == null`), which is the ONLY copy of the text. Reopened over a saved-state
 * parcel that could not give the body back, the editor is redrawn EMPTY — `To` and the subject come
 * back intact from their own saveables, so the screen looks whole — and the composer raises its
 * lost-body flag (`onComposerBodyLost`). Both ways out stayed lit: neither `canSend`
 * (recipients, validity, keys) nor `scheduleSendAllowed` (PGP mode, attachments) weighs the body at
 * all. One tap on Send ran `commitThenConsumeLocalDraft`, which DESTROYS the row and its staged
 * files, and what left carried a body cut down to the signature. Nothing on screen said a word, and
 * there was no way back: the record kept for the undo window is built AFTER the row is consumed and
 * carries the same empty body, so "Undo" reopened the composer just as empty.
 *
 * ⛔ It is asked in the composer, in front of the commit — NOT in `sendDraftTarget`'s guards
 * (`unlessBodyIsLossy`, `unlessDraftBelongsElsewhere`). Those decide what happens to a SERVER draft;
 * on the offline route `sendDraftTarget` answers `(null, null)` and a rule written there decides
 * nothing at all.
 */

/**
 * May this composer send THIS body? — the one question the two send routes never asked.
 *
 * Two terms, and neither is the question on its own:
 *
 *  - [bodyWasLost] — this composer lost the text it was holding, and it is nowhere: what the resume
 *    slot could not park is not on a server, not in the row, not in memory. Weighed ALONE it would
 *    lock the composer out for good: the flag is raised once and never lowered (there is no gesture
 *    that makes a lost body come back), so a user who retyped her message would be refused for ever
 *    on a screen holding the text she had just written.
 *  - [body] being blank — nothing has been typed since. Weighed ALONE it would refuse a message
 *    deliberately sent with no text at all: an empty mail with a subject, or one carrying only
 *    attachments, both of which are legitimate and neither of which has lost anything.
 *
 * Refused only where BOTH hold: the text was lost AND nothing replaced it. Blank rather than empty,
 * as every other body verdict here reads a body (`draftHasContent`, `localDraftBodyToWrite`):
 * whitespace and newlines are not text, and a signature-shaped body of blank lines is not a message
 * either.
 *
 * ⛔ **[body] is the RAW body of the composer — what the editor holds — never a body that has been
 * through `bodiesForSend`.** That one appends the identity's signature, so it is never blank: handed
 * it, this function answers "yes" on every message ever composed and the guard becomes dead code
 * that reads exactly like a working one. [lostBodySendWording] is what the two routes ask, and it
 * hands this one the RAW body of each: `sendInternal` its `args.body`, `scheduleSend` its own
 * `body` parameter, and both do so BEFORE anything is committed.
 *
 * ⚠ **It says nothing about SAVING, and what it used to say about it was half true.** Of the
 * phone's own draft row, yes: `localDraftBodyToWrite` refuses to write the empty body OVER the
 * stored one, so a save leaves the last text that reached the disk where it is. Of the SERVER draft,
 * yes, but NOT by the guard the send routes use: `saveDraft` never calls `unlessBodyIsLossy` (that
 * one has a single caller, `scheduleSend`). It hands the same loss down as `bodyIsLossy =
 * editingDraftLossy`, and `draftReplacementIsFaithful` (`DraftSave.kt:135-146`) is `!bodyIsLossy &&
 * …`, so the save answers ORIGINAL_KEPT and the copy in Drafts is never destroyed. Of the QUEUED
 * OUTBOX ROW, no — and that sentence is what
 * made the save route look covered while it destroyed the row and its staged files. That question is
 * [lostBodySaveDestroysQueuedRow], and it is a different one.
 */
internal fun composerMaySendBody(bodyWasLost: Boolean, body: String): Boolean =
    !(bodyWasLost && body.isBlank())

/**
 * What a refused send has to SAY, which is not the same sentence on the two routes it can come
 * from — the shape of [DiscardWording]/`discardWording`, for the same reason: the wording is a
 * decision, so it is decided here, where a test runs it, and turned into an `R.string` by the
 * caller.
 */
internal enum class LostBodyWording {
    /**
     * A draft is stored behind this screen, so the text is NOT gone: leave and open it again.
     *
     * Local draft: the row in `local_drafts` still holds the last body that reached the disk
     * (`localDraftBodyToWrite` refuses to write an empty one over it), leaving runs
     * `cancel()` → `abandon()` → `releaseLocalDraftEdit`, which only puts the row back from
     * `EDITING` to `PENDING`, and reopening goes through `prepareLocalDraft` → `localDraftPrefill`,
     * which returns the body whole. Server draft: the copy on the server was never touched — the
     * send that would have replaced it is the one being refused — and `unlessBodyIsLossy` is what
     * keeps this composer from overwriting it. Outbox row: a queued message reopened by Outbox →
     * Edit waits in the queue marked `EDITING` with the text it was queued with, and the sentence
     * is true BECAUSE closing PARKS it: leaving runs `cancel()` → `abandon(body)`, whose
     * [closingParksQueuedRow] reads the same lost, empty body this refusal did and sends the row to
     * `parkInterruptedOutboxEdit` — FAILED, at the cap, "editing was interrupted" — without
     * rebuilding a line of it, and reopening reads it again through `composeDraftOf`.
     * ⛔ NOT because `releaseOutboxEdit` gives it back to the queue: it did, re-armed at 0, and the
     * message she had just been told to reopen was delivered in under a second (2/2 on the bench).
     * The sentence prescribed the gesture that sent it. Either way the text is there and the row is
     * reopenable, which is all the sentence promises.
     */
    REOPEN_DRAFT,

    /** Nothing is stored anywhere: this composer never held a draft, so retyping is all there is. */
    RETYPE,
}

/**
 * Whether this send is refused for a body that was lost, and what to tell the user when it is.
 * `null` means the send goes on.
 *
 * [bodyWasLost] and [body] are weighed by [composerMaySendBody] and nowhere else, so the rule that
 * refuses is stated once.
 *
 * [holdsStoredDraft] decides only the WORDING, and it is the term that was missing when this
 * refusal shipped saying "Type it again." on every route. On the very route this guard exists to
 * protect — an offline draft reopened over a resume slot that lost the body — retyping is the ONE
 * action that destroys the text: three characters make [body] non-blank, the guard opens, and
 * `commitThenConsumeLocalDraft` deletes the row that still holds it. Where a draft is stored, the
 * true instruction is to leave and open it again.
 *
 * ⛔ **All THREE stores count, not just the phone's own.** A SERVER draft reopened online whose
 * body this composer lost is intact on the server, and reopens whole. So is the QUEUED OUTBOX ROW
 * behind Outbox → Edit (#70): it is parked in the queue marked `EDITING` with the text it was
 * queued with, nothing has been rebuilt from this screen, and `abandon(body)` →
 * `parkInterruptedOutboxEdit` parks it whole the moment the composer closes ([closingParksQueuedRow]:
 * FAILED, reopenable, and NOT back on its way). "Type it again." is the wrong advice on all three, because on all
 * three the text is still somewhere and the sentence sends her to retype what she already has.
 * ⚠ It is not equally DESTRUCTIVE on all three: three characters open [composerMaySendBody], and
 * the send then consumes the phone's own draft row and the outbox row, while the SERVER draft is
 * spared — `editingDraftLossy` is true whenever the body was lost, so `unlessBodyIsLossy` holds
 * the id back and the original stays in Drafts as a duplicate (#63). The caller passes
 * `_editingLocalDraftId.value != null || editingDraftId != null || editingOutboxId != null`.
 *
 * ⛔ **What the third term does NOT cover: the UNDO route.** An undone send comes back through the
 * same `restore = true` block, but its `SendOutbox.ComposeDraft` carries no `editingOutboxId` (the
 * queued row was dropped by the undo, and the field defaults to null): the message then lives ONLY
 * on this screen, there is nothing to close and reopen, and [LostBodyWording.RETYPE] is the true
 * sentence.
 */
internal fun lostBodySendWording(
    bodyWasLost: Boolean,
    body: String,
    holdsStoredDraft: Boolean,
): LostBodyWording? = when {
    composerMaySendBody(bodyWasLost, body) -> null
    holdsStoredDraft -> LostBodyWording.REOPEN_DRAFT
    else -> LostBodyWording.RETYPE
}

/**
 * Would THIS save destroy the queued row — the only store a save can take away?
 *
 * Not the send question restated: [composerMaySendBody] weighs whether a message may LEAVE, this
 * one weighs whether a "Save as draft" is about to consume a row in the outbox. They meet on the
 * two body terms and nowhere else, so the reading of a lost body stays stated once, in
 * [composerMaySendBody]; the third term is what makes this a save decision.
 *
 * The defect it ends: a queued message reopened by Outbox → Edit, redrawn EMPTY over a resume slot
 * that could not give the body back (`To` and the subject come back from their own saveables, so the
 * screen looks whole), and the CAUTIOUS gesture taken — Save as draft rather than Send. `saveDraft`
 * asked nothing, wrote the empty draft, and `consumeEditingOutbox()` then deleted the queued row and
 * its durable attachment directory. The text existed nowhere else. The screen was at that moment
 * telling her, from the send route, to close and reopen the message; the button beside it destroyed
 * what she would have reopened.
 *
 * Three terms, all needed:
 *
 *  - [bodyWasLost] — the composer lost the text it was holding. ALONE it would refuse every save
 *    for ever: the flag is raised once and never lowered, so a user who retyped her message could
 *    no longer save it either.
 *  - [body] being blank — nothing has been typed since. ALONE it would refuse the ordinary save of
 *    a queued message whose text was deliberately emptied, which loses nothing.
 *  - [holdsQueuedRow] — a queued row is being edited (`editingOutboxId != null` at the call site).
 *    ALONE it would refuse every save made from Outbox → Edit.
 *
 * ⛔ **Why the third term, and why it is not written wider.** The queued row is the only store
 * this save DESTROYS. The phone's own draft row is protected one layer down by
 * `localDraftBodyToWrite`, which will not write an empty body over the stored one; the server draft
 * by `draftReplacementIsFaithful`, which is `!bodyIsLossy && …` and is handed `editingDraftLossy` —
 * true the moment the body is lost — so the save keeps the original instead of replacing it
 * (ORIGINAL_KEPT). ⛔ NOT by `unlessBodyIsLossy`: that guard belongs to `scheduleSend`, its only
 * caller, and naming it here would be a mechanism this route never runs. On those
 * routes nothing is lost by saving, and refusing there would take away the last chance to keep a
 * subject or a recipient just typed — which is the whole point of the gesture.
 *
 * ⛔ **[body] is the RAW body of the composer**, the `body` parameter `saveDraft` was handed —
 * never a body that has been through `bodiesForSend`. That one appends the identity's signature and
 * is therefore never blank: handed it, this function answers "no" on every save ever made and the
 * guard is dead code that reads exactly like a working one.
 *
 * ⚠ **The row after a PROCESS DEATH.** `editingOutboxId` is not persisted: the composer Android
 * rebuilds gets it back from the id its route carries, once the startup sweep has parked the row
 * (`ComposeViewModel.resumeOutboxEdit`). Between the rebuild and that resume it is still null: this
 * answers false, the save goes through — and there is nothing to destroy, because
 * `consumeEditingOutbox()` reads the same null and returns at once. Once the row is reattached the
 * term is true again and this guard is whole; no term for the gap is needed, it would refuse saves
 * that destroy nothing.
 */
internal fun lostBodySaveDestroysQueuedRow(
    bodyWasLost: Boolean,
    body: String,
    holdsQueuedRow: Boolean,
): Boolean = holdsQueuedRow && !composerMaySendBody(bodyWasLost, body)

/**
 * Does CLOSING this composer park the queued row rather than give it back to the queue?
 *
 * The defect it ends: the send route refuses a lost body over a queued row with "Text lost. Close
 * this screen and reopen the message." — and closing (the X, the back gesture, "Discard changes")
 * ran `abandon()` → `releaseOutboxEdit` → `stateAfterEdit(attemptCount)` = QUEUED + `schedule(id, 0)`.
 * The row left in under a second (2/2 on the bench): the sentence prescribed the gesture that sent
 * what it said to reopen. When this answers true, `abandon` sends the row through
 * `parkInterruptedOutboxEdit` instead — FAILED at the cap, "editing was interrupted, not sent" —
 * and the row is still there to reopen.
 *
 * It is the SAME reading of a lost body as the refusal to send and the refusal to save, stated once
 * in [composerMaySendBody] and asked here a third time: the text was lost AND nothing was typed
 * back. A body retyped and then abandoned answers false, and the row goes back to the queue exactly
 * as #70 has it; an empty body nobody lost is a deliberate emptying and answers false too.
 *
 * ⛔ NOT a flag saying "a refusal was shown". The decision does not depend on what the user saw:
 * closing an empty screen after a loss is the same gesture with or without a refusal before it,
 * and a flag raised by the notice would let a close made BEFORE tapping Send queue the row anyway.
 *
 * ⛔ **[body] is the RAW body of the composer** — `body.text` of the editor at the moment of the
 * close, the value `cancel` hands `abandon` — never a body that has been through `bodiesForSend`.
 * That one appends the identity's signature and is never blank: handed it, this answers false on
 * every close and the row is queued again, the defect back with a guard that reads like a working one.
 *
 * Whether the composer HOLDS a queued row is not a term here: `abandonPlan` carries the id, and a
 * null id parks nothing. After a PROCESS DEATH the id comes back from the route once the startup
 * sweep is over (`ComposeViewModel.resumeOutboxEdit`); a close made before that holds nothing to
 * park, and the row is already parked FAILED by the sweep itself.
 */
internal fun closingParksQueuedRow(bodyWasLost: Boolean, body: String): Boolean =
    !composerMaySendBody(bodyWasLost, body)

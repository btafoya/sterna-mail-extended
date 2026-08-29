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
 * ⚠ It says nothing about SAVING. A save that carries a lost body is already handled, one layer
 * down and in the other direction: `localDraftBodyToWrite` refuses to write the empty body OVER the
 * stored one, so the row keeps the last text that reached the disk. This function is what stops the
 * same tap from DESTROYING that row.
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
     * Edit waits in the queue marked `EDITING` with the text it was queued with, leaving runs
     * `cancel()` → `abandon()` → `releaseOutboxEdit`, which only takes the row OUT of `EDITING` —
     * back to `QUEUED`, or to `FAILED` where the attempts were already spent
     * (`OutboxLogic.stateAfterEdit`) — without rebuilding a line of it, and reopening reads it
     * again through `composeDraftOf`. Either way the text is there and the row is reopenable,
     * which is all the sentence promises.
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
 * queued with, nothing has been rebuilt from this screen, and `abandon()` → `releaseOutboxEdit`
 * hands it back whole the moment the composer closes — to `QUEUED`, or to `FAILED` where the
 * attempts were already spent. "Type it again." is the wrong advice on all three, because on all
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

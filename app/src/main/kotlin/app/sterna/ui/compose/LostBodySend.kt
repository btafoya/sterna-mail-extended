package app.sterna.ui.compose

/*
 * Rules for sending, saving or closing a composer whose body was lost, kept out of
 * `ComposeViewModel` so a JVM test can run them: nothing in this module can instantiate an
 * `AndroidViewModel`.
 *
 * They are asked in the composer, in front of the commit, not in `sendDraftTarget`'s guards: those
 * decide the fate of a SERVER draft, and on the offline route `sendDraftTarget` answers
 * `(null, null)`.
 */

/**
 * May this composer send THIS body? Refused only where both terms hold: the text was lost AND
 * nothing replaced it. [bodyWasLost] alone would lock the composer out for good (the flag is
 * raised once and never lowered); a blank [body] alone would refuse a deliberately empty message.
 * Blank, not empty, like every other body verdict here.
 *
 * [body] is the RAW composer body, never one that went through `bodiesForSend`: that one
 * appends the signature, so it is never blank and this guard would always answer yes (#63).
 */
internal fun composerMaySendBody(bodyWasLost: Boolean, body: String): Boolean =
    !(bodyWasLost && body.isBlank())

/**
 * What a refused send has to say, which differs by route. The wording is a decision, so it is made
 * here and turned into an `R.string` by the caller.
 */
internal enum class LostBodyWording {
    /**
     * A draft is stored behind this screen, so the text is not gone: leave and open it again.
     * True of all three stores — the `local_drafts` row, the server copy, and a queued outbox row,
     * which closing PARKS ([closingParksQueuedRow]) instead of handing back to the queue.
     */
    REOPEN_DRAFT,

    /** Nothing is stored anywhere: this composer never held a draft, so retyping is all there is. */
    RETYPE,
}

/**
 * Whether this send is refused for a lost body, and what to tell the user. `null` means the send
 * goes on. [bodyWasLost] and [body] are weighed by [composerMaySendBody] and nowhere else.
 *
 * [holdsStoredDraft] decides only the wording, and counts all THREE stores — local row, server
 * draft, queued outbox row (#70). Where a draft is stored, "type it again" is the one instruction
 * that destroys the text: three characters open [composerMaySendBody] and the commit consumes the
 * row still holding it. The undo route carries no stored row and is [LostBodyWording.RETYPE].
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
 * Not the send question restated: the two meet on the body terms only, and those stay stated once in
 * [composerMaySendBody]. [holdsQueuedRow] is the third term, not written wider on purpose — the local
 * row is protected by `localDraftBodyToWrite` and the server draft by `draftReplacementIsFaithful`.
 *
 * [body] is the RAW body `saveDraft` was handed. `editingOutboxId` is null between a process death
 * and `resumeOutboxEdit`, and `consumeEditingOutbox()` reads the same null, so nothing is destroyed.
 */
internal fun lostBodySaveDestroysQueuedRow(
    bodyWasLost: Boolean,
    body: String,
    holdsQueuedRow: Boolean,
): Boolean = holdsQueuedRow && !composerMaySendBody(bodyWasLost, body)

/**
 * Does CLOSING this composer park the queued row rather than give it back to the queue? When true,
 * `abandon` routes the row through `parkInterruptedOutboxEdit` (FAILED at the cap) instead of
 * `releaseOutboxEdit`, which would re-arm it at 0 and send what the refusal told her to reopen.
 *
 * The same reading of a lost body as [composerMaySendBody], asked a third time. Not a flag saying "a
 * refusal was shown": a close made before tapping Send would then queue the row anyway. [body] is
 * the RAW editor body `cancel` hands `abandon`.
 */
internal fun closingParksQueuedRow(bodyWasLost: Boolean, body: String): Boolean =
    !composerMaySendBody(bodyWasLost, body)

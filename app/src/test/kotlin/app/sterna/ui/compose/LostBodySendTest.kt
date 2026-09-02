package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [composerMaySendBody], EXECUTED — the decision itself, not the text that contains it.
 *
 * ⛔ Every expectation below is written down as a literal. Nothing here recomputes the rule to
 * choose what it replays: a test that re-derives `!(lost && blank)` to pick its own answer is a copy
 * of the code, and the shipped condition could then be inverted with every line of it still green.
 *
 * WHAT IT DOES NOT COVER: that anything CALLS this — `ComposeViewModel` cannot be instantiated in
 * this module. The two call sites are pinned as source in `LocalDraftReopenWiringTest`.
 */
class LostBodySendTest {

    /**
     * ⭐ THE DEFECT, in one line: the composer lost its text, nothing was retyped, and the tap that
     * follows consumes the only copy of a draft the server has never seen.
     */
    @Test fun `a composer that lost its body and holds nothing may not send`() {
        assertEquals(
            "a lost body with nothing typed back must REFUSE the send: the row in local_drafts is " +
                "the only copy of the text, commitThenConsumeLocalDraft destroys it, and what " +
                "leaves carries a body cut down to the signature",
            false,
            composerMaySendBody(bodyWasLost = true, body = ""),
        )
    }

    /**
     * ⛔ …and whitespace is not text. `"\n\n"` is what a composer redrawn empty over a signature
     * separator holds, and counted as a body it lets the very tap this refuses straight through.
     */
    @Test fun `blank is not a body`() {
        assertEquals(false, composerMaySendBody(bodyWasLost = true, body = " "))
        assertEquals(false, composerMaySendBody(bodyWasLost = true, body = "\n\n"))
        assertEquals(
            "spaces, tabs and newlines are not text — the same reading localDraftBodyToWrite and " +
                "draftHasContent already use",
            false,
            composerMaySendBody(bodyWasLost = true, body = "  \t \r\n "),
        )
    }

    /**
     * ⛔ THE OTHER HALF, and the one a rule written on the flag alone would lose: the flag is raised
     * once and NEVER lowered, so weighed on its own it locks the composer out for good — the user
     * retypes her message, taps Send, and is refused on a screen holding the text she just wrote.
     */
    @Test fun `a body typed back after the loss sends`() {
        assertEquals(
            "the lost-body flag is never lowered; refusing on it alone would make the loss " +
                "permanent for a message that has been rewritten",
            true,
            composerMaySendBody(bodyWasLost = true, body = "typed again"),
        )
        assertEquals(true, composerMaySendBody(bodyWasLost = true, body = " x "))
    }

    /**
     * ⛔ And an empty body that was never lost is a message, not an accident: a mail with a subject
     * and no text, or one carrying only attachments. Refusing it would be a second defect wearing
     * the same wording.
     */
    @Test fun `an empty body nobody lost sends`() {
        assertEquals(
            "an empty body with no loss behind it is a deliberate message — refusing it turns " +
                "this guard into a rule about emptiness, which it is not",
            true,
            composerMaySendBody(bodyWasLost = false, body = ""),
        )
        assertEquals(true, composerMaySendBody(bodyWasLost = false, body = "   "))
    }

    @Test fun `the ordinary send is untouched`() {
        assertEquals(true, composerMaySendBody(bodyWasLost = false, body = "hello"))
    }

    /**
     * The whole truth table in one place, as a list of literals — so that a change to the condition
     * has to move a VALUE here, and cannot hide in the shape of one of the cases above.
     */
    @Test fun `the truth table, whole`() {
        val cases = listOf(
            Triple(true, "", false),
            Triple(true, "   \n", false),
            Triple(true, "text", true),
            Triple(false, "", true),
            Triple(false, "   \n", true),
            Triple(false, "text", true),
        )
        assertEquals(
            "the four corners of the decision, and the two blank readings beside them. Refused in " +
                "ONE corner only: the body was lost AND nothing was typed back.",
            cases.map { (lost, body, allowed) -> "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} -> $allowed" },
            cases.map { (lost, body, _) ->
                "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} -> ${composerMaySendBody(lost, body)}"
            },
        )
    }

    // -- what the refusal SAYS, executed too -----------------------------------------------------

    /**
     * ⭐ THE SECOND DEFECT, and it was in the shipped sentence, not in the condition: refused on the
     * route this guard exists to protect, the app said "Type it again." — and following that
     * instruction is the ONE action that destroys the text. Three characters make the body
     * non-blank, the guard opens, `commitThenConsumeLocalDraft` deletes the row that still holds
     * the last body that reached the disk, and the message leaves as the signature.
     */
    @Test fun `a composer holding a stored draft is told to reopen it, not to retype`() {
        assertEquals(
            "a draft is stored behind this screen, so the text is NOT gone: leaving runs " +
                "releaseLocalDraftEdit (EDITING -> PENDING, nothing destroyed) and reopening goes " +
                "through localDraftPrefill, which returns the body whole. Telling the user to type " +
                "it again is telling her to perform the one gesture that destroys it.",
            LostBodyWording.REOPEN_DRAFT,
            lostBodySendWording(bodyWasLost = true, body = "", holdsStoredDraft = true),
        )
    }

    /**
     * ⛔ And the term is ALL THREE stores, which is why this case exists on its own: a SERVER draft
     * reopened online whose body this composer lost is untouched on the server — the send that
     * would have replaced it is the one being refused — so it reopens whole as well, and so does
     * the QUEUED OUTBOX ROW behind Outbox → Edit, parked `EDITING` with the text it was queued
     * with. A rule reading `_editingLocalDraftId` alone answers RETYPE on all of them, i.e. the
     * advice that sends her to retype text she still has.
     */
    @Test fun `a stored draft is a stored draft, wherever it is stored`() {
        assertEquals(
            "holdsStoredDraft is `_editingLocalDraftId.value != null || editingDraftId != null " +
                "|| editingOutboxId != null` — the caller ORs all three, and this function must " +
                "give the same answer whichever of them raised it",
            LostBodyWording.REOPEN_DRAFT,
            lostBodySendWording(bodyWasLost = true, body = "   \n", holdsStoredDraft = true),
        )
    }

    /**
     * ⚠ …and "type it again" stays RIGHT where it is true: a composer that never held a draft has
     * nothing to reopen, and nothing to destroy either. Telling that user to close the screen and
     * open a draft would send her looking for something that does not exist.
     */
    @Test fun `a composer holding no draft at all is told to retype`() {
        assertEquals(
            "no draft anywhere behind this screen: there is nothing to reopen, so retyping is the " +
                "only true instruction left",
            LostBodyWording.RETYPE,
            lostBodySendWording(bodyWasLost = true, body = "", holdsStoredDraft = false),
        )
    }

    /**
     * The wording is asked only of a send that is REFUSED. `null` is the send going on, and it must
     * stay null on every accepted case — a wording returned there is a notice shown over a message
     * that is leaving.
     */
    @Test fun `an accepted send has nothing to say`() {
        assertEquals(null, lostBodySendWording(bodyWasLost = true, body = "typed again", holdsStoredDraft = true))
        assertEquals(null, lostBodySendWording(bodyWasLost = true, body = "typed again", holdsStoredDraft = false))
        assertEquals(null, lostBodySendWording(bodyWasLost = false, body = "", holdsStoredDraft = true))
        assertEquals(
            "an empty body nobody lost is a deliberate message on either route; the wording must " +
                "not appear because a draft happens to be open",
            null,
            lostBodySendWording(bodyWasLost = false, body = "", holdsStoredDraft = false),
        )
    }

    /**
     * The whole table of the wording, literals again — the eight corners of three booleans, with
     * the blank readings beside them. Inverting the `holdsStoredDraft` branch, or answering a
     * wording where the send is accepted, has to move a VALUE here.
     */
    @Test fun `the wording table, whole`() {
        val cases = listOf(
            Triple(true, "", true) to LostBodyWording.REOPEN_DRAFT,
            Triple(true, "  \t ", true) to LostBodyWording.REOPEN_DRAFT,
            Triple(true, "", false) to LostBodyWording.RETYPE,
            Triple(true, "  \t ", false) to LostBodyWording.RETYPE,
            Triple(true, "text", true) to null,
            Triple(true, "text", false) to null,
            Triple(false, "", true) to null,
            Triple(false, "", false) to null,
            Triple(false, "text", true) to null,
            Triple(false, "text", false) to null,
        )
        assertEquals(
            "REOPEN_DRAFT only where the send is refused AND something holds the text; RETYPE " +
                "only where the send is refused and nothing does; null everywhere the send goes on.",
            cases.map { (input, wording) ->
                val (lost, body, held) = input
                "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} stored=$held -> $wording"
            },
            cases.map { (input, _) ->
                val (lost, body, held) = input
                "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} stored=$held -> " +
                    "${lostBodySendWording(lost, body, held)}"
            },
        )
    }
    // -- and what a SAVE would destroy, executed too ----------------------------------------------

    /**
     * ⭐ THE DEFECT THIS TIME, and it was reached by the CAUTIOUS gesture: a queued message
     * reopened by Outbox → Edit, redrawn empty over a resume slot that lost the body, and saved as
     * a draft rather than sent. `saveDraft` asked nothing, wrote the empty draft, and
     * `consumeEditingOutbox()` then deleted the queued row and its durable attachment directory —
     * the only copy of the text.
     */
    @Test fun `saving an empty composer over a queued row destroys it`() {
        assertEquals(
            "the queued row is the only copy of the text: the save writes an empty draft and " +
                "consumeEditingOutbox() then deletes the row AND its staged attachment directory",
            true,
            lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "", holdsQueuedRow = true),
        )
    }

    /** ⛔ …and whitespace is not text here either, for the same reason it is not on the send. */
    @Test fun `a blank body over a queued row destroys it too`() {
        assertEquals(true, lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = " ", holdsQueuedRow = true))
        assertEquals(
            "a composer redrawn empty over a signature separator holds newlines, and counted as a " +
                "body they let the destroying save straight through",
            true,
            lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "\n\n", holdsQueuedRow = true),
        )
    }

    /**
     * ⛔ FIRST TERM ALONE WOULD BE A TRAP: the lost-body flag is raised once and never lowered, so
     * a rule that ignored the body would refuse to save the message the user has just retyped —
     * over a queued row, i.e. exactly where losing it costs the most.
     */
    @Test fun `a body typed back after the loss saves`() {
        assertEquals(
            "text was typed after the loss: the save carries a real body, and refusing it would " +
                "make the loss permanent on the one route with nowhere else to put the text",
            false,
            lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "typed again", holdsQueuedRow = true),
        )
        assertEquals(false, lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = " x ", holdsQueuedRow = true))
    }

    /**
     * ⛔ …and the body is read WHOLE, never a first line of it. A message retyped starting with a
     * newline — Enter, then the text — is a body, and any reading that stops at the first line
     * calls it blank. The flag is never lowered, so on this route that user could never save her
     * reprise again: the trap the second term exists to prevent, reintroduced one line lower.
     * ⛔ This is a SURVIVING mutation made to fail (`body.substringBefore('\n')` left every other
     * case in this file green): it is a real reading of a real body, not a test written to close a
     * mutant.
     */
    @Test fun `a body typed back below a blank first line saves`() {
        assertEquals(
            "the whole body is weighed, not its first line: pressing Enter before typing is how " +
                "a great many messages begin, and reading only the first line calls that blank",
            false,
            lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "\nBonjour", holdsQueuedRow = true),
        )
        assertEquals(false, lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "\n\n  \ntyped again", holdsQueuedRow = true))
    }

    /**
     * ⛔ SECOND TERM ALONE would refuse a deliberate emptying: a queued message whose text the user
     * removed on purpose is still saveable as a draft, and nothing about it was lost.
     */
    @Test fun `an empty body nobody lost saves, queued row or not`() {
        assertEquals(
            "no loss behind this empty body: the user emptied the message herself, and the save " +
                "is the ordinary one",
            false,
            lostBodySaveDestroysQueuedRow(bodyWasLost = false, body = "", holdsQueuedRow = true),
        )
        assertEquals(false, lostBodySaveDestroysQueuedRow(bodyWasLost = false, body = "   ", holdsQueuedRow = true))
    }

    /**
     * ⛔ THIRD TERM, and the reason it is not written wider: with no queued row behind the screen
     * this save destroys NOTHING. The phone's own draft row is held by `localDraftBodyToWrite`,
     * which will not write an empty body over the stored one; the server draft by
     * `unlessBodyIsLossy`, since `editingDraftLossy` is true the moment the body is lost. Refusing
     * here would take away the last chance to keep a subject or a recipient just typed.
     */
    @Test fun `a save that has no queued row behind it destroys nothing`() {
        assertEquals(
            "no outbox row is being edited, so this save takes nothing away: the local row is " +
                "protected by localDraftBodyToWrite and the server copy by unlessBodyIsLossy. " +
                "Refusing here would only cost the user a subject or a recipient she just typed.",
            false,
            lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "", holdsQueuedRow = false),
        )
        assertEquals(false, lostBodySaveDestroysQueuedRow(bodyWasLost = true, body = "  \n ", holdsQueuedRow = false))
    }

    /**
     * The whole table, literals again — the eight corners of three booleans with the blank readings
     * beside them. TRUE in one corner only, and any term dropped from the condition has to move a
     * VALUE in this list.
     */
    @Test fun `the save table, whole`() {
        val cases = listOf(
            Triple(true, "", true) to true,
            Triple(true, "  \t ", true) to true,
            Triple(true, "text", true) to false,
            Triple(false, "", true) to false,
            Triple(false, "  \t ", true) to false,
            Triple(false, "text", true) to false,
            Triple(true, "", false) to false,
            Triple(true, "  \t ", false) to false,
            Triple(true, "text", false) to false,
            Triple(false, "", false) to false,
            Triple(false, "text", false) to false,
        )
        assertEquals(
            "destroyed in ONE corner only: the body was lost, nothing was typed back, and a " +
                "queued row is being edited. Everywhere else the save goes on.",
            cases.map { (input, destroys) ->
                val (lost, body, queued) = input
                "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} queued=$queued -> $destroys"
            },
            cases.map { (input, _) ->
                val (lost, body, queued) = input
                "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} queued=$queued -> " +
                    "${lostBodySaveDestroysQueuedRow(lost, body, queued)}"
            },
        )
    }

    // -- and what CLOSING does to the queued row, executed too ------------------------------------

    /**
     * ⭐ THE DEFECT THIS TIME, and it was reached by following the app's own advice. The send route
     * refuses a lost body over a queued row with "Close this screen and reopen the message" — and
     * closing ran `abandon()` → `releaseOutboxEdit`, which put the row back to QUEUED and re-armed
     * it at 0: the message left in under a second (2/2 on the bench), the very message the sentence
     * had just told her to reopen. Closing over a lost, empty body must PARK the row, never queue it.
     */
    @Test fun `closing a composer that lost its body and holds nothing parks the queued row`() {
        assertEquals(
            "the body was lost and nothing was typed back: the close that follows the refusal must " +
                "park the queued row as FAILED, not hand it back to the queue where it leaves on " +
                "its own — the sentence on screen told her to close and reopen, and closing sent it",
            true,
            closingParksQueuedRow(bodyWasLost = true, body = ""),
        )
    }

    /** ⛔ …and whitespace is not text here either — the same reading as the send and the save. */
    @Test fun `a blank body over a lost one parks the queued row too`() {
        assertEquals(true, closingParksQueuedRow(bodyWasLost = true, body = " "))
        assertEquals(
            "a composer redrawn empty over a signature separator holds newlines, and counted as a " +
                "body they let the close hand the row back to the queue",
            true,
            closingParksQueuedRow(bodyWasLost = true, body = "\n\n"),
        )
    }

    /**
     * ⛔ The flag alone would be a trap: it is never lowered, so a rule on it alone would park the
     * row on EVERY close after a loss — including "Discard changes" over a message she has retyped,
     * which today gives the row back whole (#70) and must go on doing so.
     */
    @Test fun `a body typed back after the loss goes back to the queue on close, as before`() {
        assertEquals(
            "text was typed after the loss: the ordinary close hands the row back to the queue, " +
                "exactly as #70 has it — nothing here may change that",
            false,
            closingParksQueuedRow(bodyWasLost = true, body = "typed again"),
        )
        assertEquals(false, closingParksQueuedRow(bodyWasLost = true, body = "\nBonjour"))
    }

    /** ⛔ And an empty body nobody lost is a deliberate emptying; closing over it is the #70 close. */
    @Test fun `an empty body nobody lost goes back to the queue on close`() {
        assertEquals(
            "no loss behind this empty body: the close is the ordinary one and the row goes back " +
                "to the queue",
            false,
            closingParksQueuedRow(bodyWasLost = false, body = ""),
        )
        assertEquals(false, closingParksQueuedRow(bodyWasLost = false, body = "   "))
    }

    @Test fun `the ordinary close is untouched`() {
        assertEquals(false, closingParksQueuedRow(bodyWasLost = false, body = "hello"))
    }

    /**
     * The whole table, literals again — the four corners with the blank readings beside them. TRUE
     * in one corner only, and it is the SAME corner the send refuses and the save refuses: the three
     * gestures read one loss, stated once in [composerMaySendBody].
     */
    @Test fun `the close table, whole`() {
        val cases = listOf(
            Triple(true, "", true),
            Triple(true, "  \t \n", true),
            Triple(true, "text", false),
            Triple(false, "", false),
            Triple(false, "  \t \n", false),
            Triple(false, "text", false),
        )
        assertEquals(
            "parked in ONE corner only: the body was lost AND nothing was typed back. Everywhere " +
                "else the close gives the row back to the queue (#70).",
            cases.map { (lost, body, parks) -> "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} -> $parks" },
            cases.map { (lost, body, _) ->
                "lost=$lost body=${body.trim().ifEmpty { "<blank>" }} -> ${closingParksQueuedRow(lost, body)}"
            },
        )
    }
}

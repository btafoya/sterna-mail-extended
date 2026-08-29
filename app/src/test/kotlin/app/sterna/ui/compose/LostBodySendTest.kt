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
}

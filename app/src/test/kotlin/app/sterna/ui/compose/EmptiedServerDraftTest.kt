package app.sterna.ui.compose

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emptying a draft opened straight off the SERVER (#69 × #63), as the decision RUNS — not as a lint
 * watching a call happen.
 *
 * The defect: this route read nothing at all. `saveDraft` sent it to `repo.discardDraft`, an expunge,
 * while the twin route (a draft the phone was holding) already kept the server copy of a draft this
 * composer had not read whole and said so on screen. And "empty" is `draftHasContent`'s answer, which
 * counts neither an inline image nor a calendar part: a draft written elsewhere, made of one inline
 * image, is drawn EMPTY here — the user saves without typing anything and the original goes, silently.
 *
 * ⛔ Nothing here recomputes the rule to choose what it replays: each case hands a fixed verdict in
 * and pins the answer that comes out.
 */
class EmptiedServerDraftTest {

    @Test fun `a draft this composer never read whole is KEPT on the server`() {
        assertTrue(
            "⛔ IRREVERSIBLE, and it beats the rule of #69. The verdict says the screen showed less " +
                "than the draft holds — HTML flattened, inline images and calendar parts never " +
                "carried, an attachment not brought down, or a read that failed outright. The user " +
                "emptied what she was SHOWN, and on this route that may have been an empty editor " +
                "for a draft that was never empty: expunging on the strength of it destroys, for " +
                "ever, what she was never shown (arbitration rule 1)",
            emptiedServerDraftIsKept(bodyIsLossy = true),
        )
    }

    @Test fun `a draft this composer read whole and the user emptied by hand is still destroyed`() {
        assertFalse(
            "⛔ the guard may not become \"never destroy anything\": that is the rule of #69 — the " +
                "original is deleted so no empty shell lingers in Drafts, nor reappears on sync. " +
                "Answering \"kept\" here leaves the pre-edit draft at the top of Drafts after every " +
                "hand-emptied save, which is the symptom #69 was opened for",
            emptiedServerDraftIsKept(bodyIsLossy = false),
        )
    }

    @Test fun `the verdict is the WHOLE decision, so the two answers cannot collapse into one`() {
        // Both cases at once: a fix that hard-codes either answer passes one test and fails this.
        assertTrue(
            "kept when the read was lossy, destroyed when it was faithful — the two, from the one " +
                "verdict. A constant here is a fix that either destroys everything (the defect) or " +
                "destroys nothing (#69, reopened)",
            emptiedServerDraftIsKept(bodyIsLossy = true) && !emptiedServerDraftIsKept(bodyIsLossy = false),
        )
    }
}

package app.sterna.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // -- whose account the expunge is addressed to -----------------------------------------------

    /**
     * ⛔ The draft is emptied under whatever identity the "From" picker is showing, and that picker
     * covers EVERY account. These four cases EXECUTE the choice; nothing here recomputes it — each
     * hands two fixed account ids and a store that answers for whichever it is asked about, and pins
     * WHAT came back and WHO was asked, because a look-up that never happens is the whole fix.
     */
    @Test fun `the expunge is addressed to the account the draft was read from, not the one written as`() {
        val asked = mutableListOf<String>()
        val credentials = credentialsDestroyingEmptiedServerDraft(
            openedUnderAccountId = "acc-A",
            composingAsAccountId = "acc-B",
            lookup = { id -> asked += id; "creds-of-$id" },
        )
        assertEquals(
            "⛔ the draft's id and the numbering it was frozen under were read off account A. Sent " +
                "to B they name a message B's server never issued: JMAP spares it (the emptied " +
                "draft stays in A's Drafts and returns on the next sync) and IMAP compares two " +
                "NUMBERS, so an equal UIDVALIDITY expunges in B's Drafts instead. The screen closes " +
                "as a success either way. Credentials returned were:",
            "creds-of-acc-A",
            credentials,
        )
        assertEquals(
            "…and the store is asked about that account and no other: a second look-up here is a " +
                "fallback waiting to be written, and it is the fallback that destroys under the " +
                "wrong account. Accounts asked about were:",
            listOf("acc-A"),
            asked,
        )
    }

    @Test fun `an account removed during the edit expunges nothing, and never falls back on the writer`() {
        val asked = mutableListOf<String>()
        val credentials = credentialsDestroyingEmptiedServerDraft(
            openedUnderAccountId = "acc-A",
            composingAsAccountId = "acc-B",
            lookup = { id -> asked += id; if (id == "acc-A") null else "creds-of-$id" },
        )
        assertNull(
            "⛔ the account that held the draft is gone and B's credentials are RIGHT THERE — that " +
                "is exactly the fallback the fix removes. Refusing destroys nothing: the caller " +
                "throws, submit() draws its banner, and the draft is still a draft. Credentials " +
                "returned were:",
            credentials,
        )
        assertEquals(
            "…and B was never even looked up. Accounts asked about were:",
            listOf("acc-A"),
            asked,
        )
    }

    @Test fun `no draft opened is nothing to expunge, whoever the composer is writing as`() {
        val asked = mutableListOf<String>()
        assertNull(
            "no server draft was opened, so this gesture has nothing on any server to touch — and " +
                "an account taken from the picker would be a destroy addressed at a guess. " +
                "Credentials returned were:",
            credentialsDestroyingEmptiedServerDraft(
                openedUnderAccountId = null,
                composingAsAccountId = "acc-B",
                lookup = { id -> asked += id; "creds-of-$id" },
            ),
        )
        assertEquals("…and nothing was looked up at all. Accounts asked about were:", emptyList<String>(), asked)
    }

    @Test fun `writing as the account the draft came from is the ordinary case, and still expunges`() {
        assertEquals(
            "the rule must not refuse the case that is 99% of the traffic — one account, its own " +
                "draft, emptied and saved. Answering null there leaves an empty shell at the top " +
                "of Drafts after every hand-emptied save (#69). Credentials returned were:",
            "creds-of-acc-A",
            credentialsDestroyingEmptiedServerDraft(
                openedUnderAccountId = "acc-A",
                composingAsAccountId = "acc-A",
                lookup = { "creds-of-$it" },
            ),
        )
    }
}

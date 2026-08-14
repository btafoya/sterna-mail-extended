package app.sterna.ui.compose

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The decision a composer opened on a saved draft is drawn from, EXECUTED — not read as text.
 *
 * Offline, `repo.fetchEmail` does not fail fast: it blocks on the connection timeout (~10 s) and
 * then throws. The composer used to answer that with a blank, editable screen titled "Draft", down
 * to a live Save button — indistinguishable from a new message, and saving from it put a SECOND
 * draft on the server while the first stayed there, intact and invisible. [draftReopenView] is the
 * rule that stops that screen from existing, and [draftLoadNoticeFor] is what it says instead.
 *
 * Every case below is a LITERAL pair, input and expected output written out. Nothing here
 * re-derives the rule to decide what to expect: a test that recomputes the condition it is checking
 * stays green when the shipped condition is inverted, and this repo has caught that shape three
 * times.
 */
class DraftReopenViewTest {

    // -- what a draft that could not be read says -----------------------------------------------

    /**
     * ⭐ THE TWO SENTENCES, PINNED ON THEIR RESOURCE. They ask for opposite things: offline, the
     * text is waiting behind a connection and the reader has only to come back with one; over a
     * live link it is a real fault. Telling the first with the second's wording sends someone
     * hunting a server problem that does not exist, so "it returns some Int" is not good enough —
     * WHICH string is the whole content of the function.
     */
    @Test fun `offline and a live link do not get the same sentence`() {
        assertEquals(R.string.compose_draft_offline, draftLoadNoticeFor(offline = true))
        assertEquals(R.string.compose_draft_load_failed, draftLoadNoticeFor(offline = false))
    }

    /** Belt and braces against a mutation that returns the same id whatever it is asked. */
    @Test fun `the two sentences are two`() {
        assertNotEquals(draftLoadNoticeFor(offline = true), draftLoadNoticeFor(offline = false))
    }

    // -- which of the three screens is drawn ----------------------------------------------------

    /** Nothing out, nothing failed: the ordinary composer, exactly as every other entry gets it. */
    @Test fun `no fetch in flight and no failure is the editor`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(loading = false, failureNotice = null, prefillApplied = false),
        )
    }

    /** The fetch is out: no fields, no actions, nothing to type into and nothing to save. */
    @Test fun `a fetch in flight is a wait`() {
        assertEquals(
            DraftReopenView.Waiting,
            draftReopenView(loading = true, failureNotice = null, prefillApplied = false),
        )
    }

    /**
     * ⭐ LOADING WINS. This is the case an "obvious" simplification gets wrong — test the failure
     * first, it is the more specific state. It is not a matter of taste: a notice left from an
     * earlier attempt would then be shown as the verdict of the fetch now in flight, and the reader
     * would be told the draft is unreachable while it is on its way.
     */
    @Test fun `a fetch in flight outranks a notice left from an earlier attempt`() {
        assertEquals(
            DraftReopenView.Waiting,
            draftReopenView(
                loading = true,
                failureNotice = R.string.compose_draft_offline,
                prefillApplied = false,
            ),
        )
    }

    /** The dead end, and the sentence it must carry to the screen. */
    @Test fun `a failure is a dead end that carries its own sentence`() {
        assertEquals(
            DraftReopenView.DeadEnd(R.string.compose_draft_load_failed),
            draftReopenView(
                loading = false,
                failureNotice = R.string.compose_draft_load_failed,
                prefillApplied = false,
            ),
        )
    }

    /**
     * ⭐ THE NOTICE IS TRANSPORTED, NOT RE-CHOSEN. Asserted with an id that is not either of the
     * app's two, so nothing but "hand back what you were given" can satisfy it: a version that
     * re-derived a sentence of its own from the flags would answer one of the real ids here and
     * fail. That property is what lets the screen draw `stringResource(reopen.notice)` with no rule
     * of its own, and lets this JVM test pin the very resource that lands in front of the reader.
     */
    @Test fun `the dead end hands back the exact notice it was given`() {
        assertEquals(
            DraftReopenView.DeadEnd(4242),
            draftReopenView(loading = false, failureNotice = 4242, prefillApplied = false),
        )
    }

    // -- once the draft HAS been shown, nothing may take it off the screen ----------------------

    /**
     * ⭐ A SCREEN THAT ALREADY SHOWED THE DRAFT MAY NOT HIDE IT AGAIN, EVEN WHILE A FETCH IS OUT.
     *
     * This is not a theoretical ordering: `body`, `initialBody` and `applied` are `rememberSaveable`
     * and come back after the process is killed; the ViewModel does not, so `prepare` re-fetches.
     * Without this case, a draft opened online, typed into, and killed came back as a bare spinner
     * — over text still sitting in memory, editable nowhere.
     *
     * It also buys the ordinary case: `carryDraftAttachments` runs after the prefill is emitted and
     * before the `finally` that lowers `loading`, so the editor returns as soon as the text is
     * there rather than after a multi-MB attachment has been downloaded and re-uploaded.
     */
    @Test fun `a draft already shown stays on screen while a fetch is out`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(loading = true, failureNotice = null, prefillApplied = true),
        )
    }

    /**
     * ⭐ AND EVEN WHEN THAT FETCH FAILED. The dead end's whole claim is "there is nothing here to
     * show"; over restored text that claim is false, and acting on it is what made the X offer to
     * save (ComposeDirty compares against the restored baseline and sees the edits) and drop a
     * SECOND draft on the server — the exact symptom this screen exists to end.
     */
    @Test fun `a draft already shown stays on screen even after the fetch failed`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(
                loading = false,
                failureNotice = R.string.compose_draft_offline,
                prefillApplied = true,
            ),
        )
    }

    /** Both at once, since `loading` and a leftover notice can be true together. */
    @Test fun `a draft already shown outranks a fetch and a notice together`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(
                loading = true,
                failureNotice = R.string.compose_draft_load_failed,
                prefillApplied = true,
            ),
        )
    }

    /**
     * The plain case, spelled out so the flag cannot be read as "always the editor": with nothing
     * shown yet and nothing in flight, `prefillApplied = true` and `false` agree, and everything
     * above already pins the three cases where `false` still gates the screen.
     */
    @Test fun `a draft already shown is still the editor when nothing is happening`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(loading = false, failureNotice = null, prefillApplied = true),
        )
    }
}

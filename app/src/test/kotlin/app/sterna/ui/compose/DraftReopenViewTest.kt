package app.sterna.ui.compose

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The decision a composer opened on a saved draft is drawn from, executed, not read as text.
 */
class DraftReopenViewTest {

    // -- what a draft that could not be read says -----------------------------------------------

    /**
     * The two sentences, pinned on their resource.
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
     * Loading wins. This is the case an "obvious" simplification gets wrong, test the failure
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
     * The notice is transported, not re-chosen.
     */
    @Test fun `the dead end hands back the exact notice it was given`() {
        assertEquals(
            DraftReopenView.DeadEnd(4242),
            draftReopenView(loading = false, failureNotice = 4242, prefillApplied = false),
        )
    }

    // -- once the draft HAS been shown, nothing may take it off the screen ----------------------

    /**
     * A screen that already showed the DRAFT may not hide it again, even while a FETCH is out.
     */
    @Test fun `a draft already shown stays on screen while a fetch is out`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(loading = true, failureNotice = null, prefillApplied = true),
        )
    }

    /**
     * And even when that FETCH failed.
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
     */
    @Test fun `a draft already shown is still the editor when nothing is happening`() {
        assertEquals(
            DraftReopenView.Editor,
            draftReopenView(loading = false, failureNotice = null, prefillApplied = true),
        )
    }
}

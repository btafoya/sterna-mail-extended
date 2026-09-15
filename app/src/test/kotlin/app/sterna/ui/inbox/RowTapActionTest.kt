package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a tap on a list row does. */
class RowTapActionTest {

    // -- the defect: a draft whose conversation can unfold ---------------------------------------

    @Test
    fun `a draft in Drafts edits even when its conversation can unfold`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = false, selectionActive = false, expandable = true, fromSearch = false, rowRole = "drafts", hitRole = null),
        )
    }

    @Test
    fun `a flat draft in Drafts edits`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = "drafts", hitRole = null),
        )
    }

    // -- everywhere else the tap opens the reader -------------------------------------------------

    @Test
    fun `a foldable conversation in the Inbox opens`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = true, fromSearch = false, rowRole = "inbox", hitRole = null),
        )
    }

    @Test
    fun `a flat row in the Inbox opens`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = "inbox", hitRole = null),
        )
    }

    @Test
    fun `a row of unknown role opens`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = false, rowRole = null, hitRole = null),
        )
    }

    // -- a search result, judged by its own folder even when the index lags, never the screen's (#216)
    // A stale hit falls on the composer's guards: failed load, lossy verdict without the draft flag, `destroyAll` sparing a moved id.
    @Test
    fun `a search hit filed in Drafts edits, from a screen that is not Drafts`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = true, rowRole = "inbox", hitRole = "drafts"),
        )
    }

    @Test
    fun `a search run from Drafts opens a hit filed in the Inbox`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = true, rowRole = "drafts", hitRole = "inbox"),
        )
    }

    @Test
    fun `a search run from Drafts opens a hit whose folder cannot be said`() {
        assertEquals(
            RowTap.OPEN,
            rowTapAction(localDraft = false, selectionActive = false, expandable = false, fromSearch = true, rowRole = "drafts", hitRole = null),
        )
    }

    @Test
    fun `during a selection a search hit filed in Drafts toggles itself`() {
        assertEquals(
            RowTap.SELECT_ROW,
            rowTapAction(localDraft = false, selectionActive = true, expandable = false, fromSearch = true, rowRole = "inbox", hitRole = "drafts"),
        )
    }

    @Test
    fun `the search hit decision edits Drafts only`() {
        assertEquals(RowTap.EDIT_DRAFT, searchHitTapAction("drafts"))
        assertEquals(RowTap.OPEN, searchHitTapAction("inbox"))
        assertEquals(RowTap.OPEN, searchHitTapAction("sent"))
        assertEquals(RowTap.OPEN, searchHitTapAction("archive"))
        assertEquals(RowTap.OPEN, searchHitTapAction("trash"))
        assertEquals(RowTap.OPEN, searchHitTapAction(null))
    }

    // -- a live selection toggles, the whole conversation when it can unfold ----------------------

    @Test
    fun `during a selection a foldable draft toggles its whole conversation`() {
        assertEquals(
            RowTap.SELECT_THREAD,
            rowTapAction(localDraft = false, selectionActive = true, expandable = true, fromSearch = false, rowRole = "drafts", hitRole = null),
        )
    }

    @Test
    fun `during a selection a flat draft toggles itself`() {
        assertEquals(
            RowTap.SELECT_ROW,
            rowTapAction(localDraft = false, selectionActive = true, expandable = false, fromSearch = false, rowRole = "drafts", hitRole = null),
        )
    }

    // -- a draft only this phone holds wins before the selection (#95) ----------------------------

    @Test
    fun `a local draft edits even during a selection, and outside Drafts`() {
        assertEquals(
            RowTap.EDIT_DRAFT,
            rowTapAction(localDraft = true, selectionActive = true, expandable = false, fromSearch = false, rowRole = "inbox", hitRole = null),
        )
    }

    // -- a child of an unfolded conversation, judged by its own folder ----------------------------

    @Test
    fun `a child filed in Drafts edits`() {
        assertEquals(RowTap.EDIT_DRAFT, childTapAction("drafts"))
    }

    @Test
    fun `a child filed in Sent opens`() {
        assertEquals(RowTap.OPEN, childTapAction("sent"))
    }

    @Test
    fun `a child of unknown role opens`() {
        assertEquals(RowTap.OPEN, childTapAction(null))
    }
}

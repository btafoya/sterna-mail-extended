package app.sterna.ui.inbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a folder row of the drawer is bold, executed ([folderLabelBold]): the call site sits
 * inside a Composable nothing in this module can build, so the decision is run here. What it is
 * handed there is pinned by `BoldUnreadFoldersWiringTest`.
 */
class FolderLabelBoldTest {

    @Test fun `a row showing a count is bold while the setting is on`() {
        assertTrue(folderLabelBold(unread = 1, enabled = true))
        assertTrue(folderLabelBold(unread = 240, enabled = true))
    }

    @Test fun `a row showing no count is never bold`() {
        assertFalse(folderLabelBold(unread = 0, enabled = true))
        assertFalse(folderLabelBold(unread = 0, enabled = false))
    }

    @Test fun `the setting turned off leaves every row as it was, count or not`() {
        assertFalse(folderLabelBold(unread = 1, enabled = false))
        assertFalse(folderLabelBold(unread = 240, enabled = false))
    }

    @Test fun `a negative count shows no number, so it is not bold either`() {
        assertFalse(folderLabelBold(unread = -1, enabled = true))
    }
}

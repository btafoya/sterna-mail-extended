package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs [openOnSelection], the rule deciding which view a launch opens on (#185, #179), as the pure
 * function it is. A `null` answer is not "no view": it is the fallback both readers of
 * [restoredSelection] already seed, the current account's inbox.
 */
class OpenOnViewTest {

    /**
     * These three strings sit in the preferences of every install that touches the setting.
     * Renaming one reads back as a value this build does not know, and silently puts that reader
     * back on the default.
     */
    @Test
    fun `the three stored values are these literals`() {
        assertEquals(
            listOf("last", "inbox", "unified"),
            listOf(OPEN_ON_LAST, OPEN_ON_INBOX, OPEN_ON_UNIFIED),
        )
    }

    /** Every install that has never opened the setting, and the whole of 1.5.3. */
    @Test
    fun `no value stored opens on the view that was left`() {
        assertEquals(Sel.Folder("5"), openOnSelection(null, Sel.Folder("5"), true))
        assertEquals(Sel.Unified, openOnSelection(null, Sel.Unified, true))
        assertEquals(Sel.Unread, openOnSelection(null, Sel.Unread, false))
        assertNull(openOnSelection(null, null, true))
    }

    @Test
    fun `the default value opens on the view that was left`() {
        assertEquals(Sel.Folder("5"), openOnSelection(OPEN_ON_LAST, Sel.Folder("5"), true))
        assertEquals(Sel.Unified, openOnSelection(OPEN_ON_LAST, Sel.Unified, true))
        assertNull(openOnSelection(OPEN_ON_LAST, null, true))
    }

    /**
     * A downgrade, or preferences carried over from another install, hands back a value this build
     * never wrote, and that is no reason to move someone's launch view. The grammar stored under
     * the neighbouring key ([encodeSelection]) is one such unknown word among others.
     */
    @Test
    fun `a value this build does not know opens on the view that was left`() {
        assertEquals(Sel.Folder("5"), openOnSelection("first-unread", Sel.Folder("5"), true))
        assertEquals(Sel.Folder("5"), openOnSelection("", Sel.Folder("5"), true))
        assertEquals(Sel.Folder("5"), openOnSelection("acc-1\nu\n", Sel.Folder("5"), true))
    }

    /** The setting wins over the memory, which goes on being written for the day it is turned off. */
    @Test
    fun `the inbox value ignores a memory naming another view`() {
        assertNull(openOnSelection(OPEN_ON_INBOX, Sel.Folder("5"), true))
        assertNull(openOnSelection(OPEN_ON_INBOX, Sel.Unified, true))
        assertNull(openOnSelection(OPEN_ON_INBOX, Sel.Unread, true))
    }

    /**
     * The same answer without a unified view to fall back from. Asserted apart because the single
     * account install is both the population that asked for this setting and the one a guard
     * borrowed from the unified branch would silently exempt.
     */
    @Test
    fun `the inbox value ignores the memory where the app has no unified view`() {
        assertNull(openOnSelection(OPEN_ON_INBOX, Sel.Folder("5"), false))
        assertNull(openOnSelection(OPEN_ON_INBOX, Sel.Unread, false))
        assertNull(openOnSelection(OPEN_ON_INBOX, null, false))
    }

    @Test
    fun `the all inboxes value opens on the unified view, whatever was left`() {
        assertEquals(Sel.Unified, openOnSelection(OPEN_ON_UNIFIED, Sel.Folder("5"), true))
        assertEquals(Sel.Unified, openOnSelection(OPEN_ON_UNIFIED, null, true))
    }

    /**
     * `AllInboxesView.existsFor` is the bound the drawer posts its entry under: without this guard
     * a single-account install opens on a list it offers no way back into.
     */
    @Test
    fun `the all inboxes value falls back on the inbox where the app has no unified view`() {
        assertNull(openOnSelection(OPEN_ON_UNIFIED, Sel.Folder("5"), false))
        assertNull(openOnSelection(OPEN_ON_UNIFIED, Sel.Unified, false))
    }
}

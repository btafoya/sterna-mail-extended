package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Runs the two rules the "Open on" row is made of, as the pure functions they are: what the row
 * offers ([openOnOptions]) and what it shows as picked ([openOnShown]). The row itself is Compose,
 * which nothing in this build executes, so anything decided inside it is decided by nobody.
 */
class OpenOnRowTest {

    /**
     * "All inboxes" is a view a single-account install does not have: [openOnSelection] falls back
     * on the inbox when it is stored anyway, so a row offering it would be offering a choice the
     * launch ignores without a word.
     */
    @Test
    fun `all inboxes is offered only where the unified view exists`() {
        assertEquals(listOf(OPEN_ON_LAST, OPEN_ON_INBOX, OPEN_ON_UNIFIED), openOnOptions(true))
        assertEquals(listOf(OPEN_ON_LAST, OPEN_ON_INBOX), openOnOptions(false))
    }

    /** Every install that has never opened the setting, and the whole of 1.5.3. */
    @Test
    fun `nothing stored shows the view that was left`() {
        assertEquals(OPEN_ON_LAST, openOnShown(null, true))
        assertEquals(OPEN_ON_LAST, openOnShown(null, false))
    }

    /**
     * A downgrade, or preferences carried over from another install. The row says what the launch
     * will do with that value, and the launch does nothing with it.
     */
    @Test
    fun `a value this build does not know shows the view that was left`() {
        assertEquals(OPEN_ON_LAST, openOnShown("first-unread", true))
        assertEquals(OPEN_ON_LAST, openOnShown("", true))
        assertEquals(OPEN_ON_LAST, openOnShown("acc-1\nu\n", false))
    }

    @Test
    fun `each stored value shows itself where the app can honour it`() {
        assertEquals(OPEN_ON_LAST, openOnShown(OPEN_ON_LAST, true))
        assertEquals(OPEN_ON_INBOX, openOnShown(OPEN_ON_INBOX, true))
        assertEquals(OPEN_ON_INBOX, openOnShown(OPEN_ON_INBOX, false))
        assertEquals(OPEN_ON_UNIFIED, openOnShown(OPEN_ON_UNIFIED, true))
    }

    /**
     * The install that signs out its second account keeps "unified" on disk. The row may not go on
     * announcing a view the launch no longer opens: the reader would read "All inboxes", land in
     * the inbox, and have nothing to blame but the app.
     */
    @Test
    fun `all inboxes stored without a unified view shows the inbox, which is what opens`() {
        assertEquals(OPEN_ON_INBOX, openOnShown(OPEN_ON_UNIFIED, false))
        assertNull(openOnSelection(OPEN_ON_UNIFIED, Sel.Folder("5"), false))
    }

    /** Picking an option and reopening the screen must not move the tick somewhere else. */
    @Test
    fun `an option offered is the option the row then shows`() {
        for (unifiedViewExists in listOf(true, false)) {
            for (option in openOnOptions(unifiedViewExists)) {
                assertEquals(
                    "option $option, unifiedViewExists=$unifiedViewExists",
                    option,
                    openOnShown(option, unifiedViewExists),
                )
            }
        }
    }

    /**
     * The invariant that binds the row to the launch: the option shown names the view the app will
     * open on. The three labels are given their meaning here, once, and [openOnSelection] is then
     * asked what it opens; the two rules live apart and may not be composed (a second call site of
     * [openOnSelection] is itself pinned elsewhere), so this is the only thing keeping them level.
     */
    @Test
    fun `the option shown names the view a launch then opens on`() {
        val remembered = Sel.Folder("5")
        for (unifiedViewExists in listOf(true, false)) {
            for (stored in STORED_VALUES) {
                val shown = openOnShown(stored, unifiedViewExists)
                val announced = when (shown) {
                    OPEN_ON_LAST -> remembered
                    OPEN_ON_UNIFIED -> Sel.Unified
                    else -> null
                }
                assertEquals(
                    "stored=$stored, unifiedViewExists=$unifiedViewExists: the row shows $shown",
                    announced,
                    openOnSelection(stored, remembered, unifiedViewExists),
                )
            }
        }
    }

    private companion object {
        /** The three this build writes, plus the three shapes disk hands back that it never wrote. */
        val STORED_VALUES = listOf(
            OPEN_ON_LAST,
            OPEN_ON_INBOX,
            OPEN_ON_UNIFIED,
            null,
            "",
            "first-unread",
        )
    }
}

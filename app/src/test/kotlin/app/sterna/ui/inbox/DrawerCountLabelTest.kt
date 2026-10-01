package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The drawer's Scheduled and Outbox labels, executed ([countedLabel]): the call site sits inside a
 * Composable nothing in this module can build, so the decision is run here.
 */
class DrawerCountLabelTest {

    private val format: (String, Int) -> String = { name, count -> "$name ($count)" }

    @Test fun `an empty list shows its bare name`() {
        assertEquals("Scheduled", countedLabel("Scheduled", 0, format))
    }

    @Test fun `a list holding rows shows how many`() {
        assertEquals("Scheduled (1)", countedLabel("Scheduled", 1, format))
        assertEquals("Outbox (12)", countedLabel("Outbox", 12, format))
    }

    @Test fun `a negative count is shown as nothing, never as a number`() {
        assertEquals("Outbox", countedLabel("Outbox", -1, format))
    }
}

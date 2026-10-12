package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeEditAsNewRouteTest {

    @Test fun `the route carries the message under its own argument, and the account`() {
        assertEquals(
            "compose?editAsNew=m-42&accountId=acc-1",
            composeEditAsNewRoute("m-42", "acc-1", IDENTITY),
        )
    }

    /**
     * The three arguments that would each open something else on the same destination: `replyTo`
     * prefills a reply and threads it, `mode` opens a quoted reply for any value it does not know,
     * and `draftId` arms the licence to destroy the message it names.
     */
    @Test fun `the route names none of the arguments that open a reply or a draft`() {
        val names = composeEditAsNewRoute("m-42", "acc-1", IDENTITY)
            .substringAfter('?').split('&').map { it.substringBefore('=') }
        assertEquals(
            "an Edit as new that travels under replyTo, mode or draftId is a reply or a draft " +
                "reopen: the send is threaded onto the original, or destroys it",
            listOf("editAsNew", "accountId"),
            names,
        )
    }

    @Test fun `both values go through the encoder`() {
        assertEquals(
            "compose?editAsNew=[m&1]&accountId=[a b]",
            composeEditAsNewRoute("m&1", "a b") { "[$it]" },
        )
    }

    private companion object {
        /** The app passes `Uri.encode`, which the JVM's android.jar stub cannot run. */
        val IDENTITY: (String) -> String = { it }
    }
}

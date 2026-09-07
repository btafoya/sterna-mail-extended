package app.sterna.ui.inbox

import app.sterna.R
import app.sterna.core.data.mail.ImapNumberingUnconfirmed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A move the server would not confirm the numbering of is not an outage, and the list must never
 * report it as one (Codeberg #99).
 *
 * Since `ImapMailService` refuses such a move, five ordinary gestures can raise
 * `ImapNumberingUnconfirmed` on the list: swipe-archive, swipe-delete, the reader's
 * archive/delete/move, "report spam" and "not spam" — plus the Undo that puts any of them back. The
 * refusal's own message is a hard-coded English sentence naming a folder, a numbering and a
 * "destroy", for what the user asked to be an archive; what the snackbar says instead of it is not
 * a detail, it is the whole of what the user is told.
 *
 * WHY THIS FILE SITS BESIDE [ActionFailureMessageTest], WHICH EXERCISES THE SAME FUNCTION. That
 * one holds a decision about the TRANSPORT: which of two sentences a dead link gets, and how the
 * "you are offline" claim is earned by asking the device. This one holds a claim about a type it
 * has never heard of — a refusal is not a transport failure, so it takes the generic sentence
 * WHATEVER the device answers. The offline sentence there would be a lie told to somebody plainly
 * online, which is defect #65 again, on the most ordinary gesture in the app.
 *
 * NOT REDUNDANT WITH THE TYPE MERELY BEING ABSENT FROM `isNetworkFailure` TODAY. "The server
 * stopped answering properly" reads as network-shaped, and adding this type to that list is one
 * plausible line; the assertions below are what makes that line loud instead of silent.
 *
 * The list's other half — that both failure arms ASK this decision, and that the exception's own
 * text goes to logcat rather than the snackbar — is [ActionFailureWiringTest]'s, and it holds for
 * every failure at once, which is why this branch left that arm to `main` and kept only the claim
 * above. The reader's half is a different sentence on a different surface, and lives in
 * `RefusedMoveKeepsItsJargonOffTheScreenTest`.
 */
class MoveRefusalIsNotOfflineTest {

    @Test fun `a refusal gets the generic sentence, whatever the device answers`() {
        assertEquals(
            "a refused move on a device with a live link must say what every other failed action " +
                "of this list says",
            R.string.status_action_failed,
            actionFailureMessage(REFUSAL, online = true),
        )
        assertEquals(
            "and the same on a device with no link at all: a server that will not state a " +
                "numbering has nothing to do with the radio, and the swipe reached it to be told " +
                "so — telling that user they are offline is #65 over again",
            R.string.status_action_failed,
            actionFailureMessage(REFUSAL, online = false),
        )
    }

    /**
     * The structural half of the same claim, and the reason the one above cannot be got round from
     * a distance: `isNetworkFailure` walks the CAUSE CHAIN, so a refusal carrying a dead socket
     * underneath would be read as an outage without this type being named anywhere. It cannot,
     * because `ImapNumberingUnconfirmed` has no constructor that takes one.
     *
     * ASSERTED ON THE CLASS, NOT ON AN INSTANCE THIS TEST BUILT. `assertNull(REFUSAL.cause)` reads
     * like the same rule and is worth nothing: `cause: Throwable? = null` added to the declaration
     * and passed at the two `throw` sites leaves the two-argument call below answering null for
     * ever, green, while every refusal in production carries the session's dead socket. The
     * constructors are what the throw sites can reach, so the constructors are what is held.
     */
    @Test fun `no constructor lets the refusal carry a cause for the walk to find`() {
        val carriers = ImapNumberingUnconfirmed::class.java.constructors.filter { constructor ->
            constructor.parameterTypes.any { Throwable::class.java.isAssignableFrom(it) }
        }
        assertEquals(
            "ImapNumberingUnconfirmed can now be given a cause: " + carriers.joinToString { it.toString() } +
                ". Whatever a throw site puts there is walked by isNetworkFailure, and a transport " +
                "failure underneath turns every refused move into \"you are offline\" on a phone " +
                "whose link is fine — while this file's other assertions stay green, because they " +
                "build the refusal without one.",
            emptyList<Any>(),
            carriers,
        )
        assertNull("and the refusal as the service raises it carries nothing either", REFUSAL.cause)
    }

    /**
     * The vacuity guard: were the two sentences one resource, everything above would hold whatever
     * the decision did.
     */
    @Test fun `the generic sentence is not the offline one`() {
        assertNotEquals(R.string.status_action_failed, R.string.status_action_offline)
    }

    private companion object {
        /** The refusal exactly as `ImapMailService` raises it for a swiped archive of an INBOX
         *  whose server stated no numbering: message "The server stated no UIDVALIDITY for INBOX;
         *  a destroy frozen under null was refused". */
        val REFUSAL = ImapNumberingUnconfirmed("INBOX", null)
    }
}

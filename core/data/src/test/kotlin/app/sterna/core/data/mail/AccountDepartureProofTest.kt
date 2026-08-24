package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXECUTES the proof that lets a caller destroy the only copy of a message, and pins all FOUR
 * combinations by value. Where each site calls it, and with which arguments, is held by the source
 * lints that guard those arms ([app.sterna.ui.scheduled.ScheduledCancelScopeWiringTest] for the
 * cancel path, `app.sterna.send.ScheduledSendFireWiringTest` for the worker): neither the ViewModel
 * nor the worker can be built in a JVM test.
 *
 * ⛔ The expectations are written out as literal `true`/`false`, never recomputed from the same
 * expression: a test that rebuilds the rule to decide what to expect passes on an inverted rule too.
 */
class AccountDepartureProofTest {

    /**
     * The only state that proves a departure: storage answered, and the id is not in what it
     * answered. This is the arm the callers are allowed to destroy on, kept deliberately.
     */
    @Test fun `a readable list without the account proves the departure`() {
        assertTrue(
            "the one proven departure no longer reads as proven. Cancelling a scheduled send would " +
                "then refuse to clear a row nothing can ever send, and the worker would retry it " +
                "forever against an account that really is gone.",
            accountDepartureIsProven(accountsUnreadable = false, accountStillListed = false),
        )
    }

    /**
     * The whole point of the volet. An unreadable blob makes `accounts()` answer an EMPTY list, so
     * the id is missing for a reason that has nothing to do with the user.
     */
    @Test fun `an unreadable account blob proves nothing`() {
        assertFalse(
            "a storage failure is being read as a departure. The scheduled row is the ONLY copy of " +
                "the text (scheduleSend writes no draft), so a Keystore key regenerated overnight " +
                "destroys a message the user was waiting to see leave — silently, and for good.",
            accountDepartureIsProven(accountsUnreadable = true, accountStillListed = false),
        )
    }

    /**
     * The account is right there in the list: whatever made `credentials` answer `null` (a secret
     * that will not decode, a `pw_` slot never written for a linked sub-account, a slot withdrawn
     * on an OAuth→app-password conversion), it is not a departure.
     */
    @Test fun `an account still in the list is not gone, whatever credentials answered`() {
        assertFalse(
            "a live account whose secret merely would not decode is being read as gone. Its " +
                "scheduled message is destroyed, and the screen announces a cause that is false.",
            accountDepartureIsProven(accountsUnreadable = false, accountStillListed = true),
        )
    }

    /**
     * Incoherent input — an unreadable blob cannot list anything — pinned all the same: the rule
     * must not answer `true` on any input a caller could reach by asking the two questions a
     * moment apart, with a write landing in between.
     */
    @Test fun `an unreadable blob that still lists the account proves nothing either`() {
        assertFalse(
            "the two halves have been combined so that one can outvote the other; a caller that " +
                "read its two booleans a moment apart could then destroy on a storage failure.",
            accountDepartureIsProven(accountsUnreadable = true, accountStillListed = true),
        )
    }

    /** The four arms in one comparison, so no arm can quietly swap with another. */
    @Test fun `only one of the four combinations is a proof`() {
        assertEquals(
            "the shape of the rule changed: exactly one input — readable storage, id absent — may " +
                "answer true, and every other input must answer false, because unproven means " +
                "nothing is destroyed.",
            listOf(true, false, false, false),
            listOf(
                accountDepartureIsProven(accountsUnreadable = false, accountStillListed = false),
                accountDepartureIsProven(accountsUnreadable = true, accountStillListed = false),
                accountDepartureIsProven(accountsUnreadable = false, accountStillListed = true),
                accountDepartureIsProven(accountsUnreadable = true, accountStillListed = true),
            ),
        )
    }
}

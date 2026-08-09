package app.sterna.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⭐ The startup routing, EXECUTED. `RootViewModel` needs an `Application`, the account store and
 * the Keystore, so the decision was extracted into [RootRoute] to be run here rather than read off
 * the source; [RootRefreshWiringTest] holds the other end — that `refresh()` calls it, with these
 * arguments, instead of restating the rule.
 *
 * What it is for. The account store now refuses to write over a list it could not read (that write
 * is what deleted four accounts on the bench). An unreadable list still reads back EMPTY, so the
 * old routing offered "add an account" — a gesture the store would refuse, silently, leaving the
 * new account gone at the next launch. The four rows below are the whole rule.
 */
class RootRouteTest {

    @Test fun `a signed-in account opens its mailbox`() {
        assertEquals(
            RootState.Authenticated(ACCOUNT),
            RootRoute.resolve(currentAccountId = ACCOUNT, accountsUnreadable = false),
        )
    }

    @Test fun `no account and a readable store is a normal first run`() {
        assertEquals(
            "a fresh install, or one signed out, must reach the welcome and the connect screen — " +
                "this is the only path on which anyone ever creates a first account",
            RootState.NeedAccount,
            RootRoute.resolve(currentAccountId = null, accountsUnreadable = false),
        )
    }

    @Test fun `no account and an unreadable store is the alert, never the add-account invitation`() {
        assertEquals(
            "routed to NeedAccount, so the user is invited to add an account the store will " +
                "refuse to save: it appears to work and is gone at the next launch",
            RootState.AccountsUnreadable,
            RootRoute.resolve(currentAccountId = null, accountsUnreadable = true),
        )
    }

    /**
     * THE ORDER OF THE TWO TESTS, pinned by the one input pair that can tell them apart.
     *
     * Production is not expected to produce it (an unreadable blob decodes to nothing, so no
     * account can be current), which is exactly why it has to be pinned here: nothing else in the
     * suite distinguishes "account first, then unreadable" from the reverse, and the reverse is the
     * rule "unreadable beats everything" — the shape that puts an alert screen in front of a user
     * who has a working mailbox.
     */
    @Test fun `an account that IS signed in wins over the unreadable flag`() {
        assertEquals(
            "the unreadable flag is tested before the current account: a signed-in user is thrown " +
                "onto the alert screen",
            RootState.Authenticated(ACCOUNT),
            RootRoute.resolve(currentAccountId = ACCOUNT, accountsUnreadable = true),
        )
    }

    private companion object {
        const val ACCOUNT = "acc-jordan"
    }
}

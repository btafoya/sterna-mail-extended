package app.sterna.core.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The account order's moves, executed on real lists. A rank is counted among the accounts
 *  screen's blocks: a login with its shared mailboxes, or a shared mailbox whose login is gone. */
class AccountOrderTest {

    private fun login(id: String, pending: Boolean = false) = StoredAccount(
        id = id,
        server = "jmap.example.test",
        username = "$id@example.test",
        importPending = pending,
    )

    private fun share(id: String, loginId: String) = login(id).copy(loginId = loginId)

    private fun ids(accounts: List<StoredAccount>) = accounts.map { it.id }

    @Test fun `one place up and one place down swap two neighbours`() {
        val list = listOf(login("A"), login("B"), login("C"))
        assertEquals(
            "moving C up did not answer [A, C, B].",
            listOf("A", "C", "B"),
            ids(withAccountMoved(list, "C", up = true)),
        )
        assertEquals(
            "moving A down did not answer [B, A, C].",
            listOf("B", "A", "C"),
            ids(withAccountMoved(list, "A", up = false)),
        )
    }

    @Test fun `a drop lands the block at the rank it is dropped on, the others closing up`() {
        val list = listOf(login("A"), login("B"), login("C"), login("D"))
        assertEquals(
            "dropping A at rank 2 did not answer [B, C, A, D].",
            listOf("B", "C", "A", "D"),
            ids(withAccountDropped(list, "A", 2)),
        )
        assertEquals(
            "dropping D at rank 0 did not answer [D, A, B, C].",
            listOf("D", "A", "B", "C"),
            ids(withAccountDropped(list, "D", 0)),
        )
    }

    @Test fun `nothing that cannot move is written, the very list comes back`() {
        val list = listOf(login("A"), share("A1", "A"), login("B"))
        assertSame("the first going up answered a new list.", list, withAccountMoved(list, "A", up = true))
        assertSame("the last going down answered a new list.", list, withAccountMoved(list, "B", up = false))
        assertSame("an unknown id answered a new list.", list, withAccountMoved(list, "Z", up = true))
        assertSame("a dropped-in-place answered a new list.", list, withAccountDropped(list, "B", 1))
        assertSame("a rank past the end answered a new list.", list, withAccountDropped(list, "A", 2))
        assertSame("a negative rank answered a new list.", list, withAccountDropped(list, "B", -1))
    }

    @Test fun `a login moves with its shared mailboxes, in their own order, and ahead of the next login`() {
        val list = listOf(login("A"), share("A2", "A"), login("B"), share("A1", "A"), login("C"))
        assertEquals(
            "moving B up did not carry A's two shares along under A, in the order they were stored: " +
                "a shared mailbox was moved as a block of its own and left its login.",
            listOf("B", "A", "A2", "A1", "C"),
            ids(withAccountMoved(list, "B", up = true)),
        )
        assertEquals(
            "dropping A at rank 1 did not keep its two shares right under it, ahead of C.",
            listOf("B", "A", "A2", "A1", "C"),
            ids(withAccountDropped(list, "A", 1)),
        )
    }

    @Test fun `a share's own id heads no block, it moves only with its login`() {
        val list = listOf(login("A"), share("A1", "A"), login("B"))
        assertSame(
            "moving a share that hangs under its login moved something: a share is not a block.",
            list,
            withAccountMoved(list, "A1", up = false),
        )
        assertSame(
            "dropping a share that hangs under its login moved something: a share is not a block.",
            list,
            withAccountDropped(list, "A1", 1),
        )
    }

    @Test fun `a list whose blocks do not refill its places is left as it is, no account lost`() {
        // A duplicated login heads two blocks that both carry its share: poured back, they would
        // overrun the visible places, writing A1 twice and dropping B off the end.
        val list = listOf(login("A"), login("A"), share("A1", "A"), login("B"))
        assertSame(
            "a move over a duplicated id answered a new list, which loses an account.",
            list,
            withAccountMoved(list, "A", up = false),
        )
    }

    @Test fun `a share whose login is gone moves alone`() {
        val list = listOf(login("A"), share("X1", "gone"), share("X2", "gone"), login("B"))
        // Blocks as shown: [A], [B], [X1], [X2].
        assertEquals(
            "the orphan X2 did not move up past X1 alone.",
            listOf("A", "B", "X2", "X1"),
            ids(withAccountMoved(list, "X2", up = true)),
        )
    }

    @Test fun `an orphan never crosses the logins, since the screen draws it after them all`() {
        val list = listOf(login("A"), share("X1", "gone"), login("B"), share("X2", "gone"))
        // Blocks as shown: [A], [B], [X1], [X2].
        assertSame(
            "the first orphan moving up over the last login answered a new list: the screen puts " +
                "it back at the bottom, a write with no visible effect.",
            list,
            withAccountMoved(list, "X1", up = true),
        )
        assertSame(
            "the last login moving down onto an orphan answered a new list.",
            list,
            withAccountMoved(list, "B", up = false),
        )
        assertSame(
            "an orphan dropped at rank 0, above every login, answered a new list.",
            list,
            withAccountDropped(list, "X2", 0),
        )
        assertEquals(
            "two orphans could no longer swap places.",
            listOf("A", "B", "X2", "X1"),
            ids(withAccountDropped(list, "X2", 2)),
        )
    }

    @Test fun `a record awaiting its sign-in keeps its index, whatever moves around it`() {
        val list = listOf(login("A"), login("P", pending = true), login("B"), login("C"))
        assertEquals(
            "the pending record P left index 1, or vanished, when C was dropped at the top.",
            listOf("C", "P", "A", "B"),
            ids(withAccountDropped(list, "C", 0)),
        )
        val pendingFirst = listOf(login("P", pending = true), login("A"), login("B"))
        assertEquals(
            "the pending record P left index 0, or vanished, when B moved up.",
            listOf("P", "B", "A"),
            ids(withAccountMoved(pendingFirst, "B", up = true)),
        )
    }

    @Test fun `a move keeps every record, untouched, only its place changes`() {
        val list = listOf(
            login("A").copy(color = 7),
            share("A1", "A"),
            login("P", pending = true),
            share("X", "gone"),
            login("B").copy(folderOrder = listOf("Inbox")),
        )
        val after = withAccountDropped(list, "B", 0)
        assertEquals(
            "a move added, dropped or altered a record: only positions may change.",
            list.associateBy { it.id },
            after.associateBy { it.id },
        )
        assertEquals("a move duplicated a record.", list.size, after.size)
    }

    @Test fun `two moves in a row both land, the second from where the first left it`() {
        val list = listOf(login("A"), login("B"), login("C"))
        val once = withAccountMoved(list, "C", up = true)
        val twice = withAccountMoved(once, "C", up = true)
        assertEquals(
            "two taps on 'Monter C' did not bring C to the top: the second move did not start " +
                "from the list the first one wrote.",
            listOf("C", "A", "B"),
            ids(twice),
        )
    }

    @Test fun `the accounts screen draws the same blocks a rank is counted in`() {
        val list = listOf(login("A"), share("X", "gone"), share("A1", "A"), login("P", pending = true), login("B"))
        assertEquals(
            "accountsScreenRows no longer draws the blocks the moves count ranks in.",
            listOf("A" to false, "A1" to true, "B" to false, "X" to false),
            StoredAccount.accountsScreenRows(list).map { it.account.id to it.underLogin },
        )
    }
}

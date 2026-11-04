package app.sterna.ui.settings

import app.sterna.core.data.account.StoredAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The accounts' reorder mode (#285): when it is offered, which arrows each row carries, and the
 *  rank among blocks a drag hands `AccountStore.moveAccountTo`. */
class AccountReorderRowsTest {

    private fun login(id: String) = StoredAccount(id = id, server = "https://mail.example.org", username = "$id@example.org")

    private fun shared(id: String, loginId: String) = StoredAccount(
        id = id,
        server = "https://mail.example.org",
        username = "$loginId@example.org",
        accountName = "$id@example.org",
        loginId = loginId,
        jmapAccountId = id,
    )

    private fun pending(id: String) = login(id).copy(importPending = true)

    private fun arrows(all: List<StoredAccount>) =
        accountReorderRows(all).map { it.account.id to it.moves?.let { m -> m.mayMoveUp to m.mayMoveDown } }

    @Test fun `one login and its shared mailboxes are one block, no Reorder button`() {
        assertFalse(mayReorderAccounts(listOf(login("a"), shared("a1", "a"), shared("a2", "a"))))
    }

    @Test fun `a single account, or none, has no Reorder button, an account waiting for its sign-in not counted`() {
        assertFalse(mayReorderAccounts(emptyList()))
        assertFalse(mayReorderAccounts(listOf(login("a"))))
        assertFalse(mayReorderAccounts(listOf(login("a"), pending("p"))))
    }

    @Test fun `two logins, or two orphans, offer the button`() {
        assertTrue(mayReorderAccounts(listOf(login("a"), login("b"))))
        assertTrue(mayReorderAccounts(listOf(shared("o1", "gone"), shared("o2", "gone"))))
    }

    @Test fun `a login and an orphan are two blocks with no move between them, no Reorder button`() {
        assertFalse(mayReorderAccounts(listOf(login("a"), shared("o", "gone"))))
        assertFalse(mayReorderAccounts(listOf(login("a"), shared("a1", "a"), shared("o", "gone"))))
    }

    @Test fun `the first block cannot go up, the last cannot go down, a shared mailbox under its login has no arrows`() {
        val all = listOf(login("a"), shared("a1", "a"), login("b"), login("c"))

        assertEquals(
            listOf(
                "a" to (false to true),
                "a1" to null,
                "b" to (true to true),
                "c" to (true to false),
            ),
            arrows(all),
        )
    }

    @Test fun `the rows are the screen's rows, shared mailboxes under their login whatever the stored order`() {
        val all = listOf(shared("b1", "b"), login("a"), pending("p"), login("b"))

        assertEquals(listOf("a", "b", "b1"), accountReorderRows(all).map { it.account.id })
        assertEquals(listOf(false, false, true), accountReorderRows(all).map { it.underLogin })
        assertEquals(listOf(0, 1, 1), accountReorderRows(all).map { it.rank })
    }

    @Test fun `an orphan never crosses the line of the logins, nor does the last login onto an orphan`() {
        val all = listOf(login("a"), login("b"), shared("o1", "gone"), shared("o2", "gone"))

        assertEquals(
            listOf(
                "a" to (false to true),
                "b" to (true to false),
                "o1" to (false to true),
                "o2" to (true to false),
            ),
            arrows(all),
        )
    }

    @Test fun `a login is carried with its shared mailboxes, a shared mailbox under it carries itself alone`() {
        val rows = accountReorderRows(listOf(login("a"), shared("a1", "a"), shared("a2", "a"), login("b")))

        assertEquals(listOf(3, 1, 1, 1), rows.indices.map { accountBlockSpan(rows, it) })
    }

    @Test fun `a drop is written as the rank among the blocks, not the row index`() {
        val rows = accountReorderRows(listOf(login("a"), shared("a1", "a"), login("b"), login("c")))

        // Row 3 (c) lifted and landing at row 0: c is the first block.
        assertEquals(0, accountDrop(rows, 3, 0))
        // a with a1 (two rows) landing at row 2, after b and c: the last of three blocks.
        assertEquals(2, accountDrop(rows, 0, 2))
        // a with a1 landing at row 1, after b: the second block.
        assertEquals(1, accountDrop(rows, 0, 1))
        // b (row 2) landing at row 3, after c: rank 2, though the row index is 3.
        assertEquals(2, accountDrop(rows, 2, 3))
    }

    @Test fun `a drop between a login and its shared mailbox is refused`() {
        val rows = accountReorderRows(listOf(login("a"), shared("a1", "a"), login("b")))

        assertNull(accountDrop(rows, 2, 1))
    }

    @Test fun `a drop where the block stood, from a shared row, or out of range is refused`() {
        val rows = accountReorderRows(listOf(login("a"), shared("a1", "a"), login("b")))

        assertNull(accountDrop(rows, 2, 2))
        assertNull(accountDrop(rows, 1, 0))
        assertNull(accountDrop(rows, 2, 7))
        assertNull(accountDrop(rows, 9, 0))
    }

    @Test fun `an orphan dropped among the logins, or a login among the orphans, is refused`() {
        val rows = accountReorderRows(listOf(login("a"), login("b"), shared("o1", "gone"), shared("o2", "gone")))

        assertNull(accountDrop(rows, 2, 0))
        assertNull(accountDrop(rows, 2, 1))
        assertNull(accountDrop(rows, 1, 3))
        assertEquals(3, accountDrop(rows, 2, 3))
        assertEquals(0, accountDrop(rows, 1, 0))
    }
}

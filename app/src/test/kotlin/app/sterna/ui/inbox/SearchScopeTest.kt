package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scope chip's decisions, executed. The view only picks the value a search opens with. */
class SearchScopeTest {

    @Test fun `a search opens on every account from a view of every account, on this one otherwise`() {
        assertEquals(SearchScope.ALL_ACCOUNTS, initialSearchScope(Sel.Unified))
        assertEquals(SearchScope.ALL_ACCOUNTS, initialSearchScope(Sel.Unread(allAccounts = true)))
        assertEquals(SearchScope.THIS_ACCOUNT, initialSearchScope(Sel.Unread(allAccounts = false)))
        assertEquals(SearchScope.THIS_ACCOUNT, initialSearchScope(Sel.Folder("1")))
        assertEquals(SearchScope.THIS_ACCOUNT, initialSearchScope(null))
    }

    @Test fun `the accounts searched follow the chip, whatever view the search was opened from`() {
        val every = { listOf("accA", "accB") }
        val current = { "accA" }
        assertEquals(
            "'This account' from 'All inboxes' must ask the current account alone",
            listOf("accA"),
            accountsInScope(SearchScope.THIS_ACCOUNT, every, current),
        )
        assertEquals(listOf("accA", "accB"), accountsInScope(SearchScope.ALL_ACCOUNTS, every, current))
        assertEquals(
            "no current account: nothing is asked, never every account",
            emptyList<String>(),
            accountsInScope(SearchScope.THIS_ACCOUNT, every) { null },
        )
    }

    @Test fun `a tap flips the scope both ways`() {
        assertEquals(SearchScope.ALL_ACCOUNTS, SearchScope.THIS_ACCOUNT.flipped())
        assertEquals(SearchScope.THIS_ACCOUNT, SearchScope.ALL_ACCOUNTS.flipped())
    }

    @Test fun `the chip is offered from two accounts on`() {
        assertFalse(searchScopeChipShown(0))
        assertFalse(searchScopeChipShown(1))
        assertTrue(searchScopeChipShown(2))
    }
}

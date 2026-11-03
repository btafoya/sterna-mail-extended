package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.AccountGoneException
import app.sterna.core.data.mail.ListFilter
import app.sterna.core.jmap.model.Mailbox
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unread view of every account (#235), run: which view the drawer's "Unread" entry opens, what
 * it pages, the number the entry carries, and what the rest of the app takes it for. Every expected
 * value below is a literal, never derived from the function under test.
 */
class EveryAccountUnreadTest {

    private fun folder(id: String, role: String?, unread: Int, subscribed: Boolean = true) =
        Mailbox(id = id, name = id, role = role, unreadForList = unread, isSubscribed = subscribed)

    private fun account(
        id: String,
        hidden: Set<String> = emptySet(),
        onlySubscribed: Boolean = false,
    ) = StoredAccount(
        id = id,
        server = "https://mail.example.test",
        username = "$id@example.test",
        inboxId = "1",
        hiddenFolders = hidden,
        showOnlySubscribedFolders = onlySubscribed,
    )

    /**
     * Two accounts of one server, which numbers folders per account: the same ids on both sides
     * (#121/#31). A hides nothing and shows every folder; B hides "2" and shows only subscribed
     * folders, so its unsubscribed "4" is out. Each side's Trash ("3") is never paged.
     */
    private val accounts = listOf(
        account("A"),
        account("B", hidden = setOf("2"), onlySubscribed = true),
    )
    private val folders = mapOf(
        "A" to listOf(
            folder("1", "inbox", 1),
            folder("2", null, 2),
            folder("3", "trash", 4),
            folder("4", null, 8, subscribed = false),
        ),
        "B" to listOf(
            folder("1", "inbox", 16),
            folder("2", null, 32),
            folder("3", "trash", 64),
            folder("4", null, 128, subscribed = false),
        ),
    )

    // -- which view the entry opens (M1) ---------------------------------------------------------

    @Test
    fun `from All inboxes the entry opens the unread mail of every account`() {
        assertEquals(
            "Tapped under 'All inboxes', the 'Unread' entry must open every account's unread " +
                "mail: that is the whole of #235. Answering the current account alone is the " +
                "symptom the reporter described.",
            Sel.Unread(allAccounts = true),
            unreadSelectionReplacing(Sel.Unified, unifiedViewExists = true),
        )
    }

    @Test
    fun `from a folder the entry opens the current account's unread mail, as before`() {
        assertEquals(Sel.Unread(allAccounts = false), unreadSelectionReplacing(Sel.Folder("2"), unifiedViewExists = true))
        assertEquals(Sel.Unread(allAccounts = false), unreadSelectionReplacing(Sel.Folder(null), unifiedViewExists = true))
    }

    @Test
    fun `a second tap on the entry keeps the view it is on`() {
        assertEquals(Sel.Unread(allAccounts = true), unreadSelectionReplacing(Sel.Unread(allAccounts = true), unifiedViewExists = true))
        assertEquals(Sel.Unread(allAccounts = false), unreadSelectionReplacing(Sel.Unread(allAccounts = false), unifiedViewExists = true))
    }

    @Test
    fun `with a single account the mode is never set`() {
        assertEquals(
            "One account has no 'All inboxes' to open from; a Sel.Unified left over from a signed-" +
                "out second account must not open a mode the drawer can no longer name.",
            Sel.Unread(allAccounts = false),
            unreadSelectionReplacing(Sel.Unified, unifiedViewExists = false),
        )
    }

    @Test
    fun `the icon's shortcut opens every account's unread mail from a folder too`() {
        assertEquals(
            "An ordinary start lands on one account's inbox: from there the 'Unread' shortcut must " +
                "still open every account's unread mail when there are several (#266).",
            Sel.Unread(allAccounts = true),
            unreadSelectionForShortcut(unifiedViewExists = true),
        )
        assertEquals(Sel.Unread(allAccounts = false), unreadSelectionReplacing(Sel.Folder("2"), unifiedViewExists = true))
    }

    @Test
    fun `with a single account the shortcut opens that account's unread mail`() {
        assertEquals(Sel.Unread(allAccounts = false), unreadSelectionForShortcut(unifiedViewExists = false))
    }

    // -- what the list pages (M2, M3) ------------------------------------------------------------

    @Test
    fun `every account's unread view forces the unread filter`() {
        assertTrue(
            "The view of every account must force the unread criterion like the one-account " +
                "view: otherwise it lists read mail, and 'Select all' then 'Delete' destroys what " +
                "the list was hiding (#126).",
            listFilter(Sel.Unread(allAccounts = true), ListFilter.NONE).unreadOnly,
        )
    }

    @Test
    fun `each account's folders are paired with that account and filtered by its own settings`() {
        assertEquals(
            "Every pair must carry the account its folder belongs to, and each account must be " +
                "judged by ITS hide registry and ITS subscription setting: B's hidden '2' and its " +
                "unsubscribed '4' are out, A's are in. Pairing B's folders with A, or applying A's " +
                "settings to B, reads, deletes or moves B's mail on A.",
            listOf("A" to "1", "A" to "2", "A" to "4", "B" to "1"),
            everyAccountUnreadScopes(folders, accounts),
        )
    }

    @Test
    fun `an account whose folder list has not arrived pages nothing yet`() {
        assertEquals(listOf("A" to "1", "A" to "2", "A" to "4"), everyAccountUnreadScopes(mapOf("A" to folders.getValue("A")), accounts))
    }

    @Test
    fun `the pager takes the scope of the view on screen`() {
        val account = listOf("A" to "1")
        val every = listOf("A" to "1", "B" to "1")
        assertEquals(every, unreadScopesFor(Sel.Unread(allAccounts = true), account, every))
        assertEquals(account, unreadScopesFor(Sel.Unread(allAccounts = false), account, every))
        assertEquals(account, unreadScopesFor(Sel.Unified, account, every))
        assertEquals(account, unreadScopesFor(Sel.Folder("1"), account, every))
    }

    @Test
    fun `the chips count Sent replies in every account the view pages`() {
        val scopes = listOf("A" to "1", "A" to "2", "B" to "1")
        assertEquals(listOf("A", "B"), unreadViewAccounts(Sel.Unread(allAccounts = true), scopes, "A"))
        assertEquals(listOf("A"), unreadViewAccounts(Sel.Unread(allAccounts = false), scopes, "A"))
        assertEquals(emptyList<String>(), unreadViewAccounts(Sel.Unread(allAccounts = false), scopes, null))
    }

    // -- the number on the entry (M4) ------------------------------------------------------------

    @Test
    fun `the count sums each pair in its own account's list`() {
        assertEquals(
            "A '1' 1 + A '2' 2 + B '1' 16 = 19. Looking B's '1' up in A's list counts 1 in place " +
                "of 16.",
            19,
            everyAccountUnreadCount(listOf("A" to "1", "A" to "2", "B" to "1"), folders),
        )
        assertEquals(0, everyAccountUnreadCount(listOf("C" to "1"), folders))
    }

    /** [accounts] plus C, whose folder list holds nothing, and D, whose only unread mail is in
     *  its Trash. */
    private val fourAccounts = accounts + account("C") + account("D")
    private val fourFolders = folders + mapOf(
        "C" to emptyList(),
        "D" to listOf(folder("1", "inbox", 0), folder("3", "trash", 256)),
    )

    @Test
    fun `the count is split by account, never by folder id`() {
        assertEquals(
            "A: '1' 1 + '2' 2 + '4' 8 = 11, its Trash's 4 out. B: '1' 16, its hidden '2', its " +
                "unsubscribed '4' and its Trash out. C lists no folder: absent. D's only unread " +
                "mail is in its Trash: present at 0. Grouped by folder id, A's and B's '1' merge, " +
                "as both accounts live on one server.",
            mapOf("A" to 11, "B" to 16, "D" to 0),
            everyAccountUnreadByAccount(everyAccountUnreadScopes(fourFolders, fourAccounts), fourFolders),
        )
    }

    @Test
    fun `the entry's number is the sum of the split`() {
        val scopes = everyAccountUnreadScopes(fourFolders, fourAccounts)
        assertEquals(
            "One rule for the drawer's 'Unread' and the per-account split: 11 + 16 + 0 = 27.",
            listOf(27, 27),
            listOf(everyAccountUnreadCount(scopes, fourFolders), everyAccountUnreadByAccount(scopes, fourFolders).values.sum()),
        )
    }

    @Test
    fun `the split follows a folder hidden after it was first read`() = runTest {
        val stored = MutableStateFlow(fourAccounts)
        val seen = mutableListOf<Map<String, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            everyFolderUnreadByAccount(stored) { id -> flowOf(fourFolders.getValue(id)) }.toList(seen)
        }
        stored.value = listOf(account("A", hidden = setOf("2"))) + fourAccounts.drop(1)
        assertEquals(
            "Hiding A's '2' (2 unread) changes no account id and no folder list: only the " +
                "settings carried by the accounts flow can take it out of the count.",
            listOf(mapOf("A" to 11, "B" to 16, "D" to 0), mapOf("A" to 9, "B" to 16, "D" to 0)),
            seen,
        )
    }

    @Test
    fun `the split follows a folder list that changes after it was first read`() = runTest {
        val lists = mapOf(
            "A" to MutableStateFlow(listOf(folder("1", "inbox", 1), folder("2", null, 0), folder("3", "trash", 4))),
            "B" to MutableStateFlow(folders.getValue("B")),
        )
        val seen = mutableListOf<Map<String, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            everyFolderUnreadByAccount(flowOf(accounts)) { id -> lists.getValue(id) }.toList(seen)
        }
        lists.getValue("A").value = listOf(folder("1", "inbox", 1), folder("2", null, 3), folder("3", "trash", 4))
        assertEquals(
            "Three new unread messages in A's '2', outside the inbox: the widget must go from 1 " +
                "to 4. Reading each folder list once leaves it on its first number.",
            listOf(mapOf("A" to 1, "B" to 16), mapOf("A" to 4, "B" to 16)),
            seen,
        )
    }

    @Test
    fun `the split follows the subscribed-only setting ticked after it was first read`() = runTest {
        val stored = MutableStateFlow(accounts)
        val seen = mutableListOf<Map<String, Int>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            everyFolderUnreadByAccount(stored) { id -> flowOf(folders.getValue(id)) }.toList(seen)
        }
        stored.value = listOf(account("A", onlySubscribed = true)) + accounts.drop(1)
        assertEquals(
            "Showing only A's subscribed folders takes its unsubscribed '4' (8 unread) out: " +
                "11 becomes 3. The hide registry alone does not carry that setting.",
            listOf(mapOf("A" to 11, "B" to 16), mapOf("A" to 3, "B" to 16)),
            seen,
        )
    }

    @Test
    fun `under All inboxes and in every account's view the entry carries every account's number`() {
        assertEquals(
            "The number on 'Unread' must be what a tap on it opens: under 'All inboxes' that is " +
                "every account's unread view, so the current account's count lies.",
            19,
            unreadEntryCount(Sel.Unified, accountCount = 3, everyAccountCount = 19),
        )
        assertEquals(19, unreadEntryCount(Sel.Unread(allAccounts = true), accountCount = 3, everyAccountCount = 19))
    }

    @Test
    fun `anywhere else the entry carries the current account's number`() {
        assertEquals(3, unreadEntryCount(Sel.Unread(allAccounts = false), accountCount = 3, everyAccountCount = 19))
        assertEquals(3, unreadEntryCount(Sel.Folder("2"), accountCount = 3, everyAccountCount = 19))
    }

    // -- what the rest of the app takes the view for ---------------------------------------------

    @Test
    fun `every account's unread view is a view of every account, the other is not`() {
        assertTrue(showsEveryAccount(Sel.Unread(allAccounts = true)))
        assertTrue(showsEveryAccount(Sel.Unified))
        assertFalse(showsEveryAccount(Sel.Unread(allAccounts = false)))
        assertFalse(showsEveryAccount(Sel.Folder("1")))
        assertFalse(showsEveryAccount(null))
    }

    @Test
    fun `a notification tapped from every account's view is routed as from All inboxes`() {
        assertTrue(unifiedViewByChoice(Sel.Unread(allAccounts = true), openedBySetting = false))
        assertFalse(unifiedViewByChoice(Sel.Unread(allAccounts = true), openedBySetting = true))
        assertFalse(unifiedViewByChoice(Sel.Unread(allAccounts = false), openedBySetting = false))
    }

    @Test
    fun `Back from every account's view returns to All inboxes, from one account's to its Inbox`() {
        assertTrue(backLandsOnUnified(Sel.Unread(allAccounts = true)))
        assertFalse(backLandsOnUnified(Sel.Unread(allAccounts = false)))
        assertFalse(backLandsOnUnified(Sel.Folder("2")))
        assertFalse(backLandsOnUnified(Sel.Unified))
        assertFalse(isAtInbox(Sel.Unread(allAccounts = true)) { "1" })
    }

    @Test
    fun `the rows name their account in every account's view`() {
        val ui = MailUi(
            accountId = "A",
            accountName = UNIFIED_LABEL,
            mailboxName = UNIFIED_LABEL,
            unreadCount = 0,
            selectedMailboxId = null,
            unified = false,
            unreadView = true,
            unreadAllAccounts = true,
            mailboxes = folders.getValue("A"),
            refreshing = false,
            error = null,
        )
        assertTrue(rowsNameTheirAccount(ui))
        assertTrue(rowsNameTheirAccount(ui.copy(unreadView = false, unreadAllAccounts = false, unified = true)))
        assertFalse(rowsNameTheirAccount(ui.copy(unreadAllAccounts = false)))
    }

    // -- memory and header -----------------------------------------------------------------------

    @Test
    fun `the mode is remembered under its own code and comes back only while All inboxes exists`() {
        assertEquals("A\na\n", encodeSelection("A", Sel.Unread(allAccounts = true)))
        assertEquals("A\nn\n", encodeSelection("A", Sel.Unread(allAccounts = false)))
        assertEquals(Sel.Unread(allAccounts = true), restoreSelection("A\na\n", "A", unifiedViewExists = true))
        assertEquals(Sel.Unread(allAccounts = false), restoreSelection("A\na\n", "A", unifiedViewExists = false))
        assertNull(restoreSelection("A\na\n", "B", unifiedViewExists = true))
    }

    @Test
    fun `every account's view claims the unified header, one account's keeps its own`() {
        assertEquals(
            Meta(UNIFIED_LABEL, UNIFIED_LABEL, 9),
            restoredMeta(Sel.Unread(allAccounts = true), "1", "a@example.test", "Inbox", 4, 9),
        )
        assertEquals(
            Meta("a@example.test", "Inbox", 4),
            unreadViewMeta(Sel.Unread(allAccounts = false), "a@example.test", "Inbox", 4, 9),
        )
    }

    // -- refresh ---------------------------------------------------------------------------------

    @Test
    fun `the refresh fails only when no account came back`() {
        val down = IllegalStateException("A down")
        val alsoDown = IllegalStateException("B down")
        assertSame(down, everyAccountRefreshFailure(EveryAccountPass(refreshed = 0, failures = listOf(down, alsoDown))))
        assertNull(
            "One account came back: its mail is fresh, and an offline banner over it is #65.",
            everyAccountRefreshFailure(EveryAccountPass(refreshed = 1, failures = listOf(down))),
        )
        assertNull(everyAccountRefreshFailure(EveryAccountPass(refreshed = 2, failures = emptyList())))
        assertNull(everyAccountRefreshFailure(EveryAccountPass(refreshed = 0, failures = emptyList())))
    }

    @Test
    fun `an account signed out mid-pass is skipped, and the accounts after it are still refreshed`() = runTest {
        val visited = mutableListOf<String>()
        val outcome = try {
            refreshEachAccount(listOf("A", "B", "C")) { id ->
                visited += id
                if (id == "B") throw AccountGoneException(id)
            }
        } catch (c: CancellationException) {
            c
        }
        assertEquals(
            "AccountGoneException is a CancellationException. Let through, it abandons C and " +
                "leaves the refresh indicator spinning for ever; counted as a failure, it raises " +
                "the offline banner for a gesture the reader made on purpose.",
            listOf(listOf("A", "B", "C"), EveryAccountPass(refreshed = 2, failures = emptyList())),
            listOf(visited, outcome),
        )
    }

    @Test
    fun `a failing account is counted, and the accounts after it are still refreshed`() = runTest {
        val visited = mutableListOf<String>()
        val down = IOException("B down")
        val pass = refreshEachAccount(listOf("A", "B", "C")) { id ->
            visited += id
            if (id == "B") throw down
        }
        assertEquals(
            "One server down must neither stop the pass nor vanish: it is counted, and the " +
                "others are refreshed.",
            listOf(listOf("A", "B", "C"), EveryAccountPass(refreshed = 2, failures = listOf(down))),
            listOf(visited, pass),
        )
    }

    @Test
    fun `a real cancellation stops the pass and goes up`() = runTest {
        val visited = mutableListOf<String>()
        val cancelled = CancellationException("superseded")
        val thrown = try {
            refreshEachAccount(listOf("A", "B", "C")) { id ->
                visited += id
                if (id == "B") throw cancelled
            }
            null
        } catch (c: CancellationException) {
            c
        }
        assertEquals(
            "A superseding refresh cancels this one: swallowed, the cancelled pass would record a " +
                "false success (#65).",
            listOf(cancelled, listOf("A", "B")),
            listOf(thrown, visited),
        )
    }

    // -- the folder lists it reads ---------------------------------------------------------------

    @Test
    fun `every account's folder list is filed under that account, and only while wanted`() = runTest {
        val wanted = MutableStateFlow(true)
        val flow = everyAccountFoldersFlow(flowOf(accounts), wanted) { id -> flowOf(folders.getValue(id)) }
        assertEquals(folders, flow.first())
        wanted.value = false
        assertEquals(emptyMap<String, List<Mailbox>>(), flow.first())
    }
}

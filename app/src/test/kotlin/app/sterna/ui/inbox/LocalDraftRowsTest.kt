package app.sterna.ui.inbox

import app.sterna.core.data.db.LOCAL_DRAFT_ID_PREFIX
import app.sterna.core.data.db.LocalDraftEntity
import app.sterna.core.data.db.LocalDraftState
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.data.mail.InboxRow
import app.sterna.core.data.mail.ListFilter
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions behind "a draft the server has not got is visible in the Drafts folder, and inert"
 */
class LocalDraftRowsTest {

    // -- which folder shows the phone's own drafts ----------------------------------------------

    @Test
    fun `the Drafts folder of the account shows them, and asks the store for that account only`() = runTest {
        val asked = mutableListOf<String>()
        val flow = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter.NONE),
            localDrafts = { accountId -> asked += accountId; flowOf(listOf(row(id = LOCAL + "d1", subject = "Half a letter"))) },
        )
        val local = flow.first()
        assertEquals(listOf(LOCAL + "d1"), local.rows.map { it.email.id })
        assertEquals(listOf("Half a letter"), local.rows.map { it.email.subject })
        assertEquals("the store is read for the SELECTED account and no other", listOf("acc-1"), asked)
    }

    @Test
    fun `no other folder shows them, whatever role it carries`() = runTest {
        for (role in listOf("inbox", "sent", "trash", "archive", "junk")) {
            val local = localDraftRowsFlow(
                currentAccountId = flowOf("acc-1"),
                selectedMailboxId = flowOf("mb-x"),
                folderRoles = flowOf(mapOf(("acc-1" to "mb-x") to role)),
                filter = flowOf(ListFilter.NONE),
                localDrafts = { flowOf(listOf(row())) },
            ).first()
            assertEquals("a folder with role $role must show no local draft", emptyList<InboxRow>(), local.rows)
            assertEquals(emptySet<String>(), local.replacedServerIds)
        }
    }

    @Test
    fun `a folder with no role at all, and the unified inbox, show none`() = runTest {
        val roleless = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-custom"),
            folderRoles = flowOf(emptyMap()),
            filter = flowOf(ListFilter.NONE),
            localDrafts = { flowOf(listOf(row())) },
        ).first()
        assertEquals("a plain user folder has no role and must show nothing", emptyList<InboxRow>(), roleless.rows)

        // The unified inbox has no selected folder id, and it is not one account's Drafts.
        val unified = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf(null),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter.NONE),
            localDrafts = { flowOf(listOf(row())) },
        ).first()
        assertEquals(emptyList<InboxRow>(), unified.rows)
    }

    @Test
    fun `the Drafts folder of ANOTHER account shows none - the role map is account-qualified`() = runTest {
        // Two accounts of one server number their folders in their own space (#31): "mb-drafts"
        // means Drafts for acc-2 and something else entirely for acc-1.
        val local = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-2" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter.NONE),
            localDrafts = { flowOf(listOf(row())) },
        ).first()
        assertEquals(emptyList<InboxRow>(), local.rows)
    }

    @Test
    fun `leaving the Drafts folder empties the list again`() = runTest {
        val selected = MutableStateFlow<String?>("mb-drafts")
        val flow = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = selected,
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter.NONE),
            localDrafts = { flowOf(listOf(row(id = LOCAL + "d1"))) },
        )
        assertEquals(listOf(LOCAL + "d1"), flow.first().rows.map { it.email.id })
        selected.value = "mb-inbox"
        assertEquals(emptyList<InboxRow>(), flow.first().rows)
    }

    @Test
    fun `a STAGING row is shown like any other`() = runTest {
        // Deliberate: its text is on the phone. Hiding it re-opens the very silence this closes.
        val local = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter.NONE),
            localDrafts = {
                flowOf(
                    listOf(
                        row(id = LOCAL + "staging", state = LocalDraftState.STAGING),
                        row(id = LOCAL + "pending", state = LocalDraftState.PENDING),
                        row(id = LOCAL + "editing", state = LocalDraftState.EDITING),
                    ),
                )
            },
        ).first()
        assertEquals(
            listOf(LOCAL + "staging", LOCAL + "pending", LOCAL + "editing"),
            local.rows.map { it.email.id },
        )
    }

    @Test
    fun `showsLocalDrafts answers the folder role, and nothing else`() {
        val roles = mapOf(("acc-1" to "mb-drafts") to "drafts", ("acc-1" to "mb-inbox") to "inbox")
        assertTrue(showsLocalDrafts("acc-1", "mb-drafts", roles, ListFilter.NONE))
        assertFalse(showsLocalDrafts("acc-1", "mb-inbox", roles, ListFilter.NONE))
        assertFalse(showsLocalDrafts("acc-1", "mb-unknown", roles, ListFilter.NONE))
        assertFalse(showsLocalDrafts("acc-2", "mb-drafts", roles, ListFilter.NONE))
        assertFalse(showsLocalDrafts(null, "mb-drafts", roles, ListFilter.NONE))
        assertFalse(showsLocalDrafts("acc-1", null, roles, ListFilter.NONE))
    }

    @Test
    fun `the unread-only funnel is a refusal - a local row is read by construction`() {
        // The row carries $seen and InboxRow.unread = false (see the projection test below), so
        // under a funnel that promises unread mail it is the one row on screen that lies.
        val roles = mapOf(("acc-1" to "mb-drafts") to "drafts")
        assertFalse(showsLocalDrafts("acc-1", "mb-drafts", roles, ListFilter(unreadOnly = true)))
        assertTrue("and the funnel is the ONLY thing that changed", showsLocalDrafts("acc-1", "mb-drafts", roles, ListFilter.NONE))
    }

    @Test
    fun `turning the unread-only funnel on takes the local rows off the list`() = runTest {
        val unread = MutableStateFlow(ListFilter.NONE)
        val flow = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = unread,
            localDrafts = { flowOf(listOf(row(id = LOCAL + "d1", replacesEmailId = "imap:mb-drafts:41"))) },
        )
        assertEquals(listOf(LOCAL + "d1"), flow.first().rows.map { it.email.id })
        unread.value = ListFilter(unreadOnly = true)
        val filtered = flow.first()
        assertEquals(emptyList<InboxRow>(), filtered.rows)
        assertEquals(
            "and it must hide nothing either — a funnel that drops the local row while still " +
                "taking the server one out of the list loses the draft altogether",
            emptySet<String>(),
            filtered.replacedServerIds,
        )
    }

    // -- the server copy a local row stands in for ----------------------------------------------

    @Test
    fun `the ids a local row replaces are the ones the list must hide`() = runTest {
        val local = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter.NONE),
            localDrafts = {
                flowOf(
                    listOf(
                        row(id = LOCAL + "d1", replacesEmailId = "imap:mb-drafts:41"),
                        row(id = LOCAL + "d2", replacesEmailId = null),
                        row(id = LOCAL + "d3", replacesEmailId = "Mdeadbeef"),
                    ),
                )
            },
        ).first()
        assertEquals(setOf("imap:mb-drafts:41", "Mdeadbeef"), local.replacedServerIds)
        assertEquals("a draft written from scratch replaces nothing", 3, local.rows.size)
    }

    @Test
    fun `replacedServerIds keeps the server ids and drops the drafts written from scratch`() {
        assertEquals(
            setOf("imap:mb-drafts:41"),
            replacedServerIds(listOf(row(replacesEmailId = "imap:mb-drafts:41"), row(replacesEmailId = null))),
        )
        assertEquals(emptySet<String>(), replacedServerIds(emptyList()))
    }

    @Test
    fun `only the server row a local draft replaces is hidden`() {
        val replaced = setOf("imap:mb-drafts:41")
        assertTrue(hidesServerRow(serverRow("imap:mb-drafts:41"), replaced))
        assertFalse(hidesServerRow(serverRow("imap:mb-drafts:42"), replaced))
        assertFalse("with nothing replaced, no server row is hidden", hidesServerRow(serverRow("imap:mb-drafts:41"), emptySet()))
    }

    @Test
    fun `a row that speaks for a whole conversation is never hidden`() {
        // Conversation view is the default and Drafts is paged through it like any other folder, so
        // this row can be the representative of a group. Hiding it hides every message behind it:
        // a server draft that is perfectly present, listed nowhere at all. The duplicate is the
        // lesser error and it is the one taken.
        val replaced = setOf("imap:mb-drafts:41")
        assertFalse(
            "a representative of 2 messages must stay on screen even though a local draft replaces it",
            hidesServerRow(serverRow("imap:mb-drafts:41", threadCount = 2), replaced),
        )
        assertFalse(hidesServerRow(serverRow("imap:mb-drafts:41", threadCount = 7), replaced))
        assertTrue(
            "a row that stands for itself alone is still hidden, or the draft is listed twice",
            hidesServerRow(serverRow("imap:mb-drafts:41", threadCount = 1), replaced),
        )
    }

    // -- what "Select all" may take ---------------------------------------------------------------

    @Test
    fun `a hidden server row is out of reach of Select all`() {
        // #126 again, from our own side: the row is out of the LIST but still in `emails`, so
        // selectableIds hands it over. Left in, the counter says N+1 and "Delete" moves to the
        // Trash a message the user was never shown.
        val folder = listOf(key("imap:mb-drafts:41"), key("imap:mb-drafts:42"), key("Mdeadbeef"))
        assertEquals(
            listOf(key("imap:mb-drafts:42"), key("Mdeadbeef")),
            selectableKeysMinusHidden(folder, setOf("imap:mb-drafts:41")),
        )
        assertEquals("with nothing hidden the folder is selected whole", folder, selectableKeysMinusHidden(folder, emptySet()))
        assertEquals(
            emptyList<EmailKey>(),
            selectableKeysMinusHidden(folder, setOf("imap:mb-drafts:41", "imap:mb-drafts:42", "Mdeadbeef")),
        )
        assertEquals(
            "the id is matched whole, never by prefix",
            folder,
            selectableKeysMinusHidden(folder, setOf("imap:mb-drafts:4")),
        )
    }

    // -- the order the rows go in ----------------------------------------------------------------

    @Test
    fun `insertHeaderItem is fed oldest first, so the newest draft ends up on top`() {
        // Each call prepends one item. Fed newest first, the list would come out upside down.
        val newest = serverRow("newest")
        val middle = serverRow("middle")
        val oldest = serverRow("oldest")
        assertEquals(
            listOf(oldest, middle, newest),
            headerInsertionOrder(listOf(newest, middle, oldest)),
        )
    }

    // -- what a gesture may do to such a row -----------------------------------------------------

    @Test
    fun `a local draft row is not swipeable at all - the refusal cannot wait for the animation`() {
        // The guard has to answer before the gesture runs: commitSwipe plays the whole take-off
        // (the row leaves by the edge, tilts, fades to nothing) and only then calls onSwipe, and
        // the left swipe is DELETE by default. A refusal further down arrives after the row has
        // been destroyed on screen, with no snackbar and no Undo, over a draft still in the table.
        assertFalse(rowGesturesEnabled(LOCAL + "d1", selectionActive = false))
        assertFalse(rowGesturesEnabled(LOCAL + "d1", selectionActive = true))
        assertTrue("a server row is swipeable as it always was", rowGesturesEnabled("imap:mb-drafts:41", selectionActive = false))
        assertTrue(rowGesturesEnabled("Mdeadbeef", selectionActive = false))
        assertFalse(
            "and the pre-existing half survives: no swipe while a selection is up (#126)",
            rowGesturesEnabled("imap:mb-drafts:41", selectionActive = true),
        )
    }

    @Test
    fun `a local draft row gets no star`() {
        // toggleFlag over a local id touches no row on IMAP (imapTarget gives back null, the star
        // never fills) and raises "Action failed" on JMAP. A tap, on every row, in TalkBack too.
        assertFalse(showsFavouriteStar(LOCAL + "d1"))
        assertTrue(showsFavouriteStar("imap:mb-drafts:41"))
        assertTrue(showsFavouriteStar("Mdeadbeef"))
        assertTrue(showsFavouriteStar(null))
    }

    @Test
    fun `only a local draft id is inert`() {
        assertTrue(isLocalDraftRow(LOCAL + "e2f1"))
        assertFalse("an IMAP cache id carries a UID and IS actionable", isLocalDraftRow("imap:mb-drafts:41"))
        assertFalse("a JMAP id is actionable", isLocalDraftRow("Mdeadbeef"))
        assertFalse(isLocalDraftRow(null))
        assertFalse("the prefix must be at the START, not anywhere in the id", isLocalDraftRow("M$LOCAL"))
    }

    // -- what such a row looks like ---------------------------------------------------------------

    @Test
    fun `a local draft is projected onto the list row it draws`() {
        val projected = row(
            id = LOCAL + "d1",
            subject = "Re: the boat",
            replacesEmailId = "imap:mb-drafts:41",
        ).copy(
            accountId = "acc-1",
            toAddresses = "ana@example.org, bo@example.org",
            cc = "cc@example.org",
            bcc = "bcc@example.org",
            textBody = "  Hello\n\n  there  ",
            updatedAtMillis = 1_770_000_060_000L,
            createdAtMillis = 1_770_000_000_000L,
        ).toInboxRow("mb-drafts")

        val email = projected.email
        assertEquals(LOCAL + "d1", email.id)
        assertEquals("acc-1", email.accountId)
        assertEquals("the row must claim the folder it is shown in, or nothing can label it", "mb-drafts", email.mailboxId)
        assertEquals("Re: the boat", email.subject)
        assertEquals("the preview is the typed text, whitespace collapsed", "Hello there", email.preview)
        assertEquals("the row dates itself by the LAST edit, not the creation", "2026-02-02T02:41:00Z", email.receivedAt)
        assertEquals(listOf(EmailAddress(email = "ana@example.org"), EmailAddress(email = "bo@example.org")), email.to)
        assertEquals(listOf(EmailAddress(email = "cc@example.org")), email.cc)
        assertEquals(listOf(EmailAddress(email = "bcc@example.org")), email.bcc)
        assertTrue("the existing (Draft) chip follows the \$draft keyword", email.isDraft)
        assertTrue("a draft you have just typed is not unread mail", email.isSeen)
        assertFalse(projected.unread)
        assertEquals(1, projected.threadCount)
        assertFalse("a local draft is in no conversation the cache knows", projected.threadExpandable)
    }

    @Test
    fun `an addressless draft projects an empty recipient list rather than a blank address`() {
        val email = row().copy(toAddresses = "", cc = null, bcc = " , ").toInboxRow("mb-drafts").email
        assertEquals(emptyList<EmailAddress>(), email.to)
        assertEquals(emptyList<EmailAddress>(), email.cc)
        assertEquals(emptyList<EmailAddress>(), email.bcc)
    }

    @Test
    fun `the starred filter is a refusal - a local row never wears a star`() {
        val roles = mapOf(("acc-1" to "mb-drafts") to "drafts")
        assertFalse(showsLocalDrafts("acc-1", "mb-drafts", roles, ListFilter(flaggedOnly = true)))
        assertFalse(
            "combined with the attachment filter it still refuses",
            showsLocalDrafts("acc-1", "mb-drafts", roles, ListFilter(flaggedOnly = true, withAttachment = true)),
        )
        assertTrue(
            "the attachment filter alone lets the folder show them",
            showsLocalDrafts("acc-1", "mb-drafts", roles, ListFilter(withAttachment = true)),
        )
    }

    @Test
    fun `a local row wears the paperclip exactly when it carries a staged part`() {
        assertTrue(row(attachmentsJson = ONE_FILE).toInboxRow("mb-drafts").email.hasAttachment)
        assertFalse(row().toInboxRow("mb-drafts").email.hasAttachment)
    }

    @Test
    fun `under the attachment filter only the drafts carrying one are listed`() = runTest {
        val filter = MutableStateFlow(ListFilter.NONE)
        val flow = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = filter,
            localDrafts = {
                flowOf(
                    listOf(
                        row(id = LOCAL + "clip", replacesEmailId = "imap:mb-drafts:1", attachmentsJson = ONE_FILE),
                        row(id = LOCAL + "bare", replacesEmailId = "imap:mb-drafts:2"),
                    ),
                )
            },
        )
        assertEquals(listOf(LOCAL + "clip", LOCAL + "bare"), flow.first().rows.map { it.email.id })
        filter.value = ListFilter(withAttachment = true)
        val filtered = flow.first()
        assertEquals(listOf(LOCAL + "clip"), filtered.rows.map { it.email.id })
        assertEquals(
            "a draft filtered out still hides its server copy (#95): that copy is the stale text of " +
                "the same draft, and dropping the local row must not bring it back in its place",
            setOf("imap:mb-drafts:1", "imap:mb-drafts:2"),
            filtered.replacedServerIds,
        )
        filter.value = ListFilter(flaggedOnly = true)
        assertEquals(LocalDraftRows(), flow.first())
    }

    @Test
    fun `a local draft the attachment filter drops keeps its stale server copy hidden`() = runTest {
        val local = localDraftRowsFlow(
            currentAccountId = flowOf("acc-1"),
            selectedMailboxId = flowOf("mb-drafts"),
            folderRoles = flowOf(mapOf(("acc-1" to "mb-drafts") to "drafts")),
            filter = flowOf(ListFilter(withAttachment = true)),
            localDrafts = { flowOf(listOf(row(id = LOCAL + "bare", replacesEmailId = "Mstale"))) },
        ).first()
        assertEquals("the local row has no paperclip, so it is off the list", emptyList<InboxRow>(), local.rows)
        assertEquals(
            "and the server copy it replaced stays hidden (#95), or the older text reappears",
            setOf("Mstale"),
            local.replacedServerIds,
        )
    }

    private companion object {
        const val LOCAL = LOCAL_DRAFT_ID_PREFIX

        fun row(
            id: String = LOCAL + "d1",
            subject: String = "Untitled",
            replacesEmailId: String? = null,
            state: LocalDraftState = LocalDraftState.PENDING,
            attachmentsJson: String = "[]",
        ) = LocalDraftEntity(
            accountId = "acc-1",
            id = id,
            messageId = "<$id@sterna>",
            toAddresses = "ana@example.org",
            subject = subject,
            textBody = "body",
            replacesEmailId = replacesEmailId,
            createdAtMillis = 1_770_000_000_000L,
            updatedAtMillis = 1_770_000_000_000L,
            notBeforeMillis = 0L,
            state = state,
            attachmentsJson = attachmentsJson,
        )

        const val ONE_FILE = "[{\"kind\":\"imapFile\",\"path\":\"/x/a.pdf\",\"name\":\"a.pdf\"}]"

        fun serverRow(id: String, threadCount: Int = 1) =
            InboxRow(Email(id = id), threadCount = threadCount, unread = false)

        fun key(id: String, accountId: String = "acc-1") = EmailKey(accountId, id)
    }
}

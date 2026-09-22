package app.sterna.ui.settings

import app.sterna.core.data.account.folderOrderAfterMove
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.inbox.mailboxTree
import app.sterna.ui.inbox.shownFolders
import app.sterna.ui.inbox.visibleFolders
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which Up and Down buttons the reorder mode of the folder screen draws, and which fratrie a tap
 * reorders, executed on [folderManagementRows]: the screen only reads `row.moves`.
 */
class FolderReorderRowsTest {

    private fun folder(id: String, role: String? = null, parentId: String? = null) =
        Mailbox(id = id, name = id.substringAfterLast('/'), role = role, parentId = parentId, isSubscribed = true)

    private val tree = listOf(
        folder("INBOX", role = "inbox"),
        folder("Sent", role = "sent"),
        folder("Drafts", role = "drafts"),
        folder("Trash", role = "trash"),
        folder("Junk", role = "junk"),
        folder("Archive", role = "archive"),
        folder("Alpha"),
        folder("Bravo"),
        folder("Bravo/Sous1"),
        folder("Bravo/Sous2"),
        folder("Charlie"),
    )

    private fun moves(mailboxes: List<Mailbox> = tree, order: List<String> = emptyList()) =
        folderManagementRows(mailboxes, false, emptySet(), emptySet(), "INBOX", order)
            .associate { it.mailbox.id to it.moves }

    private fun ids(mailboxes: List<Mailbox> = tree, order: List<String>) =
        folderManagementRows(mailboxes, false, emptySet(), emptySet(), "INBOX", order).map { it.mailbox.id }

    @Test fun `a folder with a role carries no button`() {
        val byId = moves()
        assertEquals(
            "a standard folder carries Up or Down in reorder mode. They are drawn by role ahead of " +
                "every other folder whatever the order holds, so the tap would write a move the " +
                "screen never shows.",
            listOf(null, null, null, null, null, null),
            listOf("INBOX", "Sent", "Drafts", "Trash", "Junk", "Archive").map { byId[it] },
        )
    }

    @Test fun `the first of a fratrie has no Up, the last no Down, the fratrie its parent's own`() {
        val byId = moves()
        val top = listOf("Alpha", "Bravo", "Charlie")
        val under = listOf("Bravo/Sous1", "Bravo/Sous2")
        assertEquals(
            "the buttons of the top-level folders and of Bravo's children are not the ones their " +
                "fratrie calls for: first without Up, last without Down, and each fratrie made " +
                "of one parent's own folders, standard folders left out.",
            listOf(
                FolderMoves(top, mayMoveUp = false, mayMoveDown = true),
                FolderMoves(top, mayMoveUp = true, mayMoveDown = true),
                FolderMoves(top, mayMoveUp = true, mayMoveDown = false),
                FolderMoves(under, mayMoveUp = false, mayMoveDown = true),
                FolderMoves(under, mayMoveUp = true, mayMoveDown = false),
            ),
            listOf("Alpha", "Bravo", "Charlie", "Bravo/Sous1", "Bravo/Sous2").map { byId[it] },
        )
    }

    @Test fun `the fratrie follows the order already chosen`() {
        assertEquals(
            "a row's fratrie is not in the order the screen draws: Up would then trade places with " +
                "a folder that is not the one above it.",
            FolderMoves(listOf("Charlie", "Alpha", "Bravo"), mayMoveUp = false, mayMoveDown = true),
            moves(order = listOf("Charlie", "Alpha"))["Charlie"],
        )
    }

    @Test fun `Down on a subfolder moves it among its siblings and leaves the top level alone`() {
        val fratrie = moves()["Bravo/Sous1"]?.siblings
            ?: error("Bravo/Sous1 carries no button: its fratrie is not its parent's")
        val order = folderOrderAfterMove(emptyList(), fratrie, "Bravo/Sous1", up = false)
        assertEquals(
            "Down on Bravo/Sous1 did not put it under Bravo/Sous2 with the top level untouched: " +
                "the move was handed another parent's fratrie.",
            listOf("INBOX", "Drafts", "Sent", "Trash", "Junk", "Archive", "Alpha", "Bravo", "Bravo/Sous2", "Bravo/Sous1", "Charlie"),
            ids(order = order),
        )
    }

    @Test fun `a JMAP subfolder, parented by id, moves within its own parent`() {
        val jmap = listOf(
            folder("m-inbox", role = "inbox"),
            folder("m1"),
            folder("m2"),
            folder("m3", parentId = "m1"),
            folder("m4", parentId = "m1"),
        )
        assertEquals(
            "a folder nested by JMAP parentId was not given its parent's fratrie.",
            FolderMoves(listOf("m3", "m4"), mayMoveUp = true, mayMoveDown = false),
            moves(jmap)["m4"],
        )
    }

    @Test fun `a folder alone under its parent carries no button`() {
        val lone = listOf(folder("INBOX", role = "inbox"), folder("Alpha"), folder("Alpha/Seul"))
        assertEquals(
            "a folder with no sibling to trade places with carries a button that does nothing.",
            listOf(null, null),
            listOf("Alpha", "Alpha/Seul").map { moves(lone)[it] },
        )
    }

    @Test fun `a folder hidden on this device is out of the reorder mode, and a move shows in the drawer`() {
        val withHidden = listOf(
            folder("INBOX", role = "inbox"),
            folder("A"),
            folder("M"),
            folder("M/Sous"),
            folder("C"),
        )
        val hidden = setOf("M")
        fun rows(reordering: Boolean, order: List<String> = emptyList()) =
            folderManagementRows(withHidden, false, hidden, emptySet(), "INBOX", order, reordering)
        fun drawer(order: List<String>) =
            mailboxTree(shownFolders(visibleFolders(withHidden, false), hidden, "INBOX"), emptySet(), order)
                .map { it.mailbox.id }

        assertEquals(
            "outside the reorder mode the screen must still list the hidden folder and its " +
                "subfolder: it is the only place the user can show them again.",
            listOf("INBOX", "A", "M", "M/Sous", "C"),
            rows(reordering = false).map { it.mailbox.id },
        )
        val reorder = rows(reordering = true)
        assertEquals(
            "the reorder mode lists a folder hidden on this device, or one under a hidden parent: " +
                "the drawer does not draw them, and the mode shows no switch to tell they are hidden.",
            listOf("INBOX", "A", "C"),
            reorder.map { it.mailbox.id },
        )
        val moves = reorder.single { it.mailbox.id == "C" }.moves
        assertEquals(
            "C's fratrie in the reorder mode is not the drawer's [A, C]: Up would trade places " +
                "with the hidden M, and the drawer would not move.",
            FolderMoves(listOf("A", "C"), mayMoveUp = true, mayMoveDown = false),
            moves,
        )
        val order = folderOrderAfterMove(emptyList(), moves!!.siblings, "C", up = true)
        assertEquals(
            "Up on C did not bring it above A in the drawer.",
            listOf("INBOX", "C", "A"),
            drawer(order),
        )
        assertEquals(
            "after the move the reorder mode no longer draws the drawer's order.",
            drawer(order),
            rows(reordering = true, order = order).map { it.mailbox.id },
        )
    }
}

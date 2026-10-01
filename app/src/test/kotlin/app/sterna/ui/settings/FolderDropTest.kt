package app.sterna.ui.settings

import app.sterna.core.data.account.folderOrderAfterDrop
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.components.movedBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which drags the reorder mode of the folder screen accepts, and what a drop writes, executed on
 * the rows [folderManagementRows] builds: the screen hands [folderDrop] to the column as its
 * `canMove`, and writes what it answers. A drop is named as the column names it: the row carried,
 * and the index its row lands at once it and its subfolders are moved as one block.
 */
class FolderDropTest {

    private fun folder(id: String, role: String? = null) =
        Mailbox(id = id, name = id.substringAfterLast('/'), role = role, isSubscribed = true)

    private val tree = listOf(
        folder("INBOX", role = "inbox"),
        folder("Sent", role = "sent"),
        folder("Archive", role = "archive"),
        folder("Alpha"),
        folder("Bravo"),
        folder("Bravo/Sous1"),
        folder("Bravo/Sous2"),
        folder("Bravo/Sous3"),
        folder("Charlie"),
    )

    private fun rowsWith(order: List<String> = emptyList()) =
        folderManagementRows(tree, false, emptySet(), emptySet(), "INBOX", order, reordering = true)

    private val rows = rowsWith()

    private fun at(id: String) = rows.indexOfFirst { it.mailbox.id == id }.also { check(it >= 0) { id } }

    private fun drop(from: String, to: Int) = folderDrop(rows, at(from), to, emptyList(), ::rowsWith)

    private val top = listOf("Alpha", "Bravo", "Charlie")
    private val under = listOf("Bravo/Sous1", "Bravo/Sous2", "Bravo/Sous3")

    @Test fun `a parent carries its subfolders`() {
        assertEquals(
            "a parent is not carried with the subfolders drawn under it: dragged, it leaves them " +
                "behind on screen, and they rejoin it only once the write is drawn.",
            listOf(1, 4, 1, 1),
            listOf("Alpha", "Bravo", "Bravo/Sous1", "Charlie").map { folderSubtreeSize(rows, at(it)) },
        )
    }

    @Test fun `a drop between a parent and its first subfolder, or across fratries, is refused`() {
        assertEquals(
            "a drop was accepted where the drawer can never draw the folder: between Bravo and its " +
                "first subfolder, among another parent's folders, or out of its own parent.",
            listOf(null, null, null, null),
            listOf(
                drop("Alpha", at("Bravo/Sous1") - 1),
                drop("Bravo/Sous1", at("Alpha")),
                drop("Bravo/Sous3", at("Charlie")),
                drop("Charlie", at("Bravo/Sous2")),
            ),
        )
    }

    @Test fun `a folder lands between two siblings' subtrees, a parent with its subfolders`() {
        assertEquals(
            "a drop between two of its siblings' subtrees was refused, or written at another place " +
                "in the fratrie than the one it was let go at.",
            listOf(
                FolderDrop(top, "Alpha", 1),
                FolderDrop(top, "Bravo", 0),
                FolderDrop(top, "Bravo", 2),
                FolderDrop(under, "Bravo/Sous1", 2),
                FolderDrop(top, "Charlie", 0),
            ),
            listOf(
                drop("Alpha", at("Bravo/Sous3")),
                drop("Bravo", at("Alpha")),
                drop("Bravo", rows.size - 4),
                drop("Bravo/Sous1", at("Bravo/Sous3")),
                drop("Charlie", at("Alpha")),
            ),
        )
    }

    @Test fun `a standard folder neither moves nor gives up its place`() {
        assertEquals(
            "a drag onto or from a folder with a role was accepted: those are drawn by role ahead " +
                "of the rest, whatever the order holds, so the write would show nowhere.",
            listOf(null, null, null),
            listOf(drop("Charlie", at("Archive")), drop("Charlie", at("INBOX")), drop("Sent", at("Alpha"))),
        )
    }

    @Test fun `a folder dropped where it stands, or past the end, writes nothing`() {
        assertNull(drop("Bravo", at("Bravo")))
        assertNull(drop("Alpha", rows.size))
        assertNull(drop("Bravo", rows.size - 3))
    }

    @Test fun `every accepted drop is drawn after the write exactly as the column showed it, subfolders included`() {
        val accepted = rows.indices.flatMap { from -> rows.indices.map { to -> from to to } }
            .mapNotNull { (from, to) -> folderDrop(rows, from, to, emptyList(), ::rowsWith)?.let { Triple(from, to, it) } }
        val mismatches = accepted.mapNotNull { (from, to, write) ->
            val shownAtRelease = rows.movedBlock(from, folderSubtreeSize(rows, from), to).map { it.mailbox.id }
            val drawnAfter = rowsWith(folderOrderAfterDrop(emptyList(), write.siblings, write.folderId, write.toIndex))
                .map { it.mailbox.id }
            if (shownAtRelease == drawnAfter) null else "${write.folderId} to row $to: shown $shownAtRelease, drawn $drawnAfter"
        }
        assertEquals(
            "a drop wrote another list than the one the column displayed when the finger lifted: " +
                "the folder lands elsewhere than where it was let go, or its subfolders do.",
            emptyList<String>(),
            mismatches,
        )
        assertEquals(
            "not every place a folder can take among its siblings is reachable by exactly one drop.",
            (top.flatMap { f -> top.indices.filter { it != top.indexOf(f) }.map { FolderDrop(top, f, it) } } +
                under.flatMap { f -> under.indices.filter { it != under.indexOf(f) }.map { FolderDrop(under, f, it) } }).toSet(),
            accepted.map { it.third }.toSet(),
        )
        assertEquals("two drops write the same move", 12, accepted.size)
    }
}

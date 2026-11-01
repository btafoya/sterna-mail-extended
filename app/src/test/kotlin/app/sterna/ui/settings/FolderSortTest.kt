package app.sterna.ui.settings

import app.sterna.core.data.account.folderOrderAfterSort
import app.sterna.core.jmap.model.Mailbox
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What "Sort A to Z" on the folder screen writes (#279), executed on [sortedFratries] and read back
 * through [folderManagementRows], the rows the reorder mode draws for the order written.
 */
class FolderSortTest {

    private fun folder(id: String, role: String? = null, parentId: String? = null) =
        Mailbox(id = id, name = id.substringAfterLast('/'), role = role, parentId = parentId, isSubscribed = true)

    private val tree = listOf(
        folder("INBOX", role = "inbox"),
        folder("Sent", role = "sent"),
        folder("Trash", role = "trash"),
        folder("Archive", role = "archive"),
        folder("zebra"),
        folder("Banque"),
        folder("Banque/zz"),
        folder("Banque/Aa"),
        folder("Banque/mm"),
        folder("alpha"),
        folder("mangue"),
        folder("Archives-QA"),
    )
    private val hidden = setOf("mangue", "Banque/mm")
    private val english: Collator = Collator.getInstance(Locale.ENGLISH)

    private fun sorted(
        mailboxes: List<Mailbox> = tree,
        order: List<String> = listOf("zebra", "Banque", "alpha"),
        label: (Mailbox) -> String = { it.name },
    ) = sortedFratries(mailboxes, false, order, label, english)

    @Test fun `each fratrie is sorted on its own, by the collator, hidden folders with their siblings`() {
        assertEquals(
            "the sort did not answer the top level and Banque's children each in collator order. " +
                "Sorted by String.compareTo, 'Archives-QA' and 'Banque' come before 'alpha'; a " +
                "standard folder in a fratrie is a place the drawer never draws it at; a hidden " +
                "folder left out keeps its old place the day it is shown again; one list for the " +
                "whole tree puts a subfolder among another parent's folders.",
            setOf(
                listOf("alpha", "Archives-QA", "Banque", "mangue", "zebra"),
                listOf("Banque/Aa", "Banque/mm", "Banque/zz"),
            ),
            sorted().filter { it.isNotEmpty() }.toSet(),
        )
    }

    @Test fun `the sort reads the label the screen prints, not the folder's own name`() {
        val two = listOf(folder("INBOX", role = "inbox"), folder("a1"), folder("b1"))
        assertEquals(
            "the sort is not decided on the label it is handed: a folder whose printed name is " +
                "not its server name would land out of the order the user reads.",
            listOf("b1", "a1"),
            sorted(two, emptyList(), label = { if (it.id == "a1") "z" else "y" }).single { it.isNotEmpty() },
        )
    }

    @Test fun `folders with the same label keep the order they had`() {
        val twins = listOf(folder("INBOX", role = "inbox"), folder("p"), folder("q"))
        assertEquals(
            "two folders printed alike swapped places on a sort: the tap moves what the user " +
                "cannot tell apart.",
            listOf("q", "p"),
            sorted(twins, listOf("q", "p"), label = { "same" }).single { it.isNotEmpty() },
        )
    }

    @Test fun `the order written draws the rows in alphabetical order, standard folders first`() {
        val order = folderOrderAfterSort(listOf("zebra", "Banque", "alpha"), sorted())
        assertEquals(
            "after a sort the reorder mode does not draw the user's folders A to Z under the " +
                "standard ones, each subfolder under its own parent.",
            listOf("INBOX", "Sent", "Trash", "Archive", "alpha", "Archives-QA", "Banque", "Banque/Aa", "Banque/zz", "zebra"),
            folderManagementRows(tree, false, hidden, emptySet(), "INBOX", order, reordering = true).map { it.mailbox.id },
        )
        assertEquals(
            "after a sort the hidden folders are not in alphabetical order the day they are shown.",
            listOf("INBOX", "Sent", "Trash", "Archive", "alpha", "Archives-QA", "Banque", "Banque/Aa", "Banque/mm", "Banque/zz", "mangue", "zebra"),
            folderManagementRows(tree, false, emptySet(), emptySet(), "INBOX", order, reordering = true).map { it.mailbox.id },
        )
    }
}

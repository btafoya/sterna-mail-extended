package app.sterna.ui.inbox

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.jmap.model.Mailbox
import app.sterna.ui.settings.folderManagementRows
import app.sterna.ui.settings.specialFolderRows
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user's folder order applied where folders are drawn: the drawer tree, the folder screen, the
 * special-folder picker and the move picker. Executed, never recomputed: every expectation is a
 * literal list, and the order handed in is a literal too.
 */
class FolderOrderRenderTest {

    private fun mailbox(id: String, role: String? = null, name: String = id, parentId: String? = null) =
        Mailbox(id = id, name = name, role = role, parentId = parentId)

    private fun ids(nodes: List<MailboxNode>) = nodes.map { it.mailbox.id }

    private val flat = listOf(
        mailbox("Zulu"),
        mailbox("Sent", role = "sent"),
        mailbox("Alpha"),
        mailbox("INBOX", role = "inbox"),
        mailbox("Mike"),
        mailbox("Trash", role = "trash"),
    )

    // ── the drawer tree ─────────────────────────────────────────────────────────────────────────

    @Test fun `the drawer orders the personal folders by the registry`() {
        assertEquals(
            "mailboxTree ignored the account's folder order: the personal folders came out in the " +
                "listing's order, so reordering them in settings would change nothing on screen.",
            listOf("INBOX", "Sent", "Trash", "Mike", "Alpha", "Zulu"),
            ids(mailboxTree(flat, emptySet(), listOf("Mike", "Alpha", "Zulu"))),
        )
    }

    @Test fun `a folder the registry does not name follows the named ones, in listing order`() {
        assertEquals(
            listOf("INBOX", "Sent", "Trash", "Mike", "Zulu", "Alpha"),
            ids(mailboxTree(flat, emptySet(), listOf("Mike"))),
        )
    }

    @Test fun `the registry can never lift a personal folder above a standard one`() {
        assertEquals(
            "the registry was allowed to outrank the role order: a personal folder at the head of " +
                "the stored order, or a standard folder named in it, moved the Inbox off the top.",
            listOf("INBOX", "Sent", "Trash", "Mike", "Zulu", "Alpha"),
            ids(mailboxTree(flat, emptySet(), listOf("Mike", "Trash", "Sent", "INBOX"))),
        )
    }

    @Test fun `a folder pinned to a role stays with the standard folders`() {
        // `Projets` carries `archive` because the user pinned it (#227): the pin reaches the role
        // the cache hands the drawer, and the registry naming it first does not move it.
        val pinned = listOf(
            mailbox("Alpha"),
            mailbox("Projets", role = "archive"),
            mailbox("INBOX", role = "inbox"),
            mailbox("Spam"),
        )
        assertEquals(
            listOf("INBOX", "Projets", "Spam", "Alpha"),
            ids(mailboxTree(pinned, emptySet(), listOf("Projets", "Spam", "Alpha"))),
        )
    }

    @Test fun `a sub-fratrie's order moves its own children and nothing at the first level`() {
        val nested = listOf(
            mailbox("INBOX", role = "inbox"),
            mailbox("pa", name = "ProjectA"),
            mailbox("x", name = "X", parentId = "pa"),
            mailbox("y", name = "Y", parentId = "pa"),
            mailbox("z", name = "Z", parentId = "pa"),
            mailbox("pb", name = "ProjectB"),
            mailbox("q", name = "Q", parentId = "pb"),
        )
        assertEquals(
            listOf(
                Triple("INBOX", 0, false),
                Triple("pa", 0, true),
                Triple("z", 1, false),
                Triple("x", 1, false),
                Triple("y", 1, false),
                Triple("pb", 0, true),
                Triple("q", 1, false),
            ),
            mailboxTree(nested, emptySet(), listOf("z", "x")).map { Triple(it.mailbox.id, it.depth, it.hasChildren) },
        )
        assertEquals(
            "a whole subtree must travel with its parent when the first level is reordered",
            listOf("INBOX", "pb", "q", "pa", "x", "y", "z"),
            ids(mailboxTree(nested, emptySet(), listOf("pb", "pa"))),
        )
    }

    @Test fun `an empty registry leaves the tree exactly as the role order alone draws it`() {
        assertEquals(
            listOf("INBOX", "Sent", "Trash", "Zulu", "Alpha", "Mike"),
            ids(mailboxTree(flat, emptySet(), emptyList())),
        )
    }

    // ── the folder screen and the special-folder picker ─────────────────────────────────────────

    @Test fun `the folder screen lists the folders in the drawer's order`() {
        assertEquals(
            listOf("INBOX", "Sent", "Trash", "Mike", "Alpha", "Zulu"),
            folderManagementRows(flat, false, emptySet(), emptySet(), "INBOX", listOf("Mike", "Alpha", "Zulu"))
                .map { it.mailbox.id },
        )
    }

    @Test fun `the special-folder picker offers the folders in the drawer's order`() {
        val offered = specialFolderRows(flat, false, emptyMap(), "INBOX", idsArePaths = true, order = listOf("Mike", "Alpha", "Zulu"))
            .first().options.mapNotNull { it.folderId }
        assertEquals(listOf("Sent", "Trash", "Mike", "Alpha", "Zulu"), offered)
    }

    // ── the move picker ─────────────────────────────────────────────────────────────────────────

    private val english: Collator = Collator.getInstance(Locale.ENGLISH)

    @Test fun `the move picker orders the personal folders by the registry, standard ones still lead`() {
        assertEquals(
            "moveTargets ignored the account's folder order: the picker kept its alphabetical tail " +
                "while the drawer showed the user's order.",
            listOf("INBOX", "Sent", "Trash", "Zulu", "Mike", "Alpha"),
            moveTargets(flat, null, order = listOf("Zulu", "Mike", "INBOX"), collator = english).map { it.id },
        )
    }

    @Test fun `the move picker keeps a subtree under its parent and orders it within`() {
        val nested = listOf(
            mailbox("Work"),
            mailbox("Work/2025", name = "2025"),
            mailbox("Work/2026", name = "2026"),
            mailbox("Home"),
            mailbox("Home/Bills", name = "Bills"),
        )
        assertEquals(
            listOf("Work", "Work/2026", "Work/2025", "Home", "Home/Bills"),
            moveTargets(nested, null, order = listOf("Work", "Work/2026"), collator = english).map { it.id },
        )
        assertEquals(
            "the folder the message is in is not offered, yet it still heads its children",
            listOf("Work/2026", "Work/2025", "Home", "Home/Bills"),
            moveTargets(nested, "Work", order = listOf("Work", "Work/2026"), collator = english).map { it.id },
        )
    }

    @Test fun `two unlisted parents at one level each keep their own children in the move picker`() {
        // Neither `Projets` nor `Archives2023` is listed (an IMAP `\Noselect` parent): both fratries
        // are keyed by their path, and a key shared between them would drop one whole group. No stored
        // order ranks an unlisted parent, even one holding its name.
        val unlisted = listOf(
            mailbox("Projets/A", name = "A"),
            mailbox("Projets/B", name = "B"),
            mailbox("Archives2023/X", name = "X"),
            mailbox("Archives2023/Y", name = "Y"),
            mailbox("Zeta"),
        )
        assertEquals(
            listOf("Zeta", "Archives2023/Y", "Archives2023/X", "Projets/B", "Projets/A"),
            moveTargets(unlisted, null, order = listOf("Projets", "Zeta", "Projets/B", "Archives2023/Y"), collator = english)
                .map { it.id },
        )
    }

    @Test fun `the move picker and the drawer agree on the personal folders`() {
        val order = listOf("Mike", "Zulu", "Alpha")
        assertEquals(
            ids(mailboxTree(flat, emptySet(), order)).filter { it in order },
            moveTargets(flat, null, order = order, collator = english).map { it.id }.filter { it in order },
        )
    }

    @Test fun `an empty registry leaves the move picker alphabetical`() {
        assertEquals(
            listOf("INBOX", "Sent", "Trash", "Alpha", "Mike", "Zulu"),
            moveTargets(flat, null, order = emptyList(), collator = english).map { it.id },
        )
    }

    // ── which account's order ───────────────────────────────────────────────────────────────────

    @Test fun `an order is read off the one record the id names`() {
        val accounts = listOf(
            StoredAccount(id = "a", server = "s", username = "a@example.test", folderOrder = listOf("Travail", "Perso")),
            StoredAccount(id = "b", server = "s", username = "b@example.test", folderOrder = listOf("Perso")),
        )
        assertEquals(FolderOrdering("b", listOf("Perso")), folderOrderingOf("b", accounts))
        assertEquals(FolderOrdering("zz", emptyList()), folderOrderingOf("zz", accounts))
        assertEquals(FolderOrdering(null, emptyList()), folderOrderingOf(null, accounts))
    }

    @Test fun `an order meets only the list of its own account`() {
        val ordering = FolderOrdering("a", listOf("Travail", "Perso"))
        assertEquals(ordering, ordering.forList("a"))
        assertEquals(
            "account b's list was sorted by account a's order: on IMAP the ids are paths, and a " +
                "namesake folder on b would take a's place.",
            FolderOrdering("b", emptyList()),
            ordering.forList("b"),
        )
    }
}

package app.sterna.ui.settings

import app.sterna.core.data.account.SPECIAL_FOLDER_ROLES
import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the special-folder lines offer and what a tap writes (#227), the rule, executed. Nothing
 * here composes and nothing here recomputes the rule: every assertion pins what the screen is
 * handed.
 *
 * The listing is the account that motivated the lot: a server-declared `Junk` beside a `Spam` the
 * election dispossessed, two folders the translated role name prints alike.
 */
class SpecialFolderRowsTest {

    private fun folder(
        id: String,
        role: String? = null,
        name: String = id,
        parentId: String? = null,
        subscribed: Boolean = true,
        lostClaim: Boolean = false,
    ) = Mailbox(
        id = id,
        name = name,
        role = role,
        parentId = parentId,
        isSubscribed = subscribed,
        lostRoleClaim = lostClaim,
    )

    private val listing = listOf(
        folder("INBOX", role = "inbox"),
        folder("Junk", role = "junk"),
        folder("Spam", lostClaim = true),
        folder("Trash", role = "trash"),
        folder("Sent", role = "sent"),
        folder("Drafts", role = "drafts"),
        folder("Archives", role = "archive"),
        folder("Travail"),
        folder("Travail/2026", name = "2026", parentId = "Travail"),
        folder("Vieux", subscribed = false),
    )

    private fun rows(
        mailboxes: List<Mailbox> = listing,
        showOnlySubscribed: Boolean = false,
        chosen: Map<String, String> = emptyMap(),
        inboxId: String? = "INBOX",
        idsArePaths: Boolean = true,
    ) = specialFolderRows(mailboxes, showOnlySubscribed, chosen, inboxId, idsArePaths)

    private fun List<SpecialFolderRow>.role(role: String) = single { it.role == role }

    @Test fun `the five lines are the five roles a folder may be pinned to, in order`() {
        assertEquals(SPECIAL_FOLDER_ROLES, rows().map { it.role })
    }

    @Test fun `a line names the folder that carries its role now, by the name the server gave it`() {
        assertEquals(SpecialFolderLabel.Detected("Junk"), rows().role("junk").label)
        assertEquals(SpecialFolderLabel.Detected("Archives"), rows().role("archive").label)
    }

    @Test fun `the picker names every folder as the server does, never by its role`() {
        assertEquals(
            listOf("", "Drafts", "Sent", "Trash", "Junk", "Archives", "Spam", "Travail", "2026", "Vieux"),
            rows().role("junk").options.map { it.name },
        )
    }

    @Test fun `the inbox is never offered, by role, by the account's own id, or by the reserved path`() {
        assertEquals(emptyList<String?>(), rows().role("trash").options.mapNotNull { it.folderId }.filter { it == "INBOX" })
        val noRole = listOf(folder("INBOX"), folder("Trash", role = "trash"))
        assertEquals(
            listOf(null, "Trash"),
            rows(mailboxes = noRole, inboxId = "INBOX").role("trash").options.map { it.folderId },
        )
        assertEquals(
            listOf(null, "Trash"),
            rows(mailboxes = noRole, inboxId = null).role("trash").options.map { it.folderId },
        )
        // A JMAP id is opaque: a mailbox whose id happens to spell INBOX is an ordinary folder.
        assertEquals(
            listOf(null, "Trash", "INBOX"),
            rows(mailboxes = noRole, inboxId = null, idsArePaths = false).role("trash").options.map { it.folderId },
        )
        // And where ids are opaque AND the server announced no inbox role, the account's own id is
        // the only thing left that knows which folder the inbox is.
        assertEquals(
            listOf(null, "Trash"),
            rows(mailboxes = noRole, inboxId = "INBOX", idsArePaths = false).role("trash").options.map { it.folderId },
        )
    }

    @Test fun `every role can go back to automatic, even where the server announces nothing`() {
        val bare = listOf(folder("Boîte"), folder("Autre"))
        val bareRows = rows(mailboxes = bare, inboxId = null)
        assertEquals(SPECIAL_FOLDER_ROLES, bareRows.map { it.role })
        assertEquals(
            "every line must open on an Automatic entry, first and selected",
            SPECIAL_FOLDER_ROLES.map { listOf(true, false, false) },
            bareRows.map { row -> row.options.map { it.selected } },
        )
        assertTrue(bareRows.all { it.options.first().folderId == null && it.label == SpecialFolderLabel.None })
    }

    @Test fun `a pinned role reads off the listing the choice already moved, and ticks its folder`() {
        // The cache the choice was applied to: Spam took the role, Junk lost it.
        val moved = listing.map {
            when (it.id) {
                "Junk" -> it.copy(role = null, lostRoleClaim = true)
                "Spam" -> it.copy(role = "junk", lostRoleClaim = false)
                else -> it
            }
        }
        val row = rows(mailboxes = moved, chosen = mapOf("junk" to "Spam")).role("junk")
        assertEquals(
            "a designated folder must read as designated, or the line says the choice did nothing",
            SpecialFolderLabel.Chosen("Spam"),
            row.label,
        )
        assertEquals(listOf("Spam"), row.options.filter { it.selected }.map { it.folderId })
    }

    @Test fun `a folder already carrying another role says so, and the role's own holder does not`() {
        val trash = rows().role("trash").options
        assertEquals("junk", trash.single { it.folderId == "Junk" }.heldRole)
        assertNull(trash.single { it.folderId == "Trash" }.heldRole)
        assertNull(trash.single { it.folderId == "Travail" }.heldRole)
    }

    @Test fun `a role the app cannot name is not annotated at all`() {
        val exotic = listOf(folder("INBOX", role = "inbox"), folder("Modeles", role = "templates"))
        val options = rows(mailboxes = exotic).role("trash").options
        assertNull(
            "an RFC 8621 role the app has no name for would print a raw server token in a " +
                "translated sentence",
            options.single { it.folderId == "Modeles" }.heldRole,
        )
    }

    @Test fun `a choice the listing no longer carries reads as automatic, and can be handed back`() {
        val row = rows(chosen = mapOf("junk" to "Parti")).role("junk")
        assertEquals(SpecialFolderLabel.Detected("Junk"), row.label)
        assertEquals(listOf<String?>(null), row.options.filter { it.selected }.map { it.folderId })
    }

    @Test fun `the picker offers what the account's own setting lists, and carries the tree depth`() {
        val subscribed = rows(showOnlySubscribed = true).role("archive").options
        assertEquals(emptyList<String?>(), subscribed.mapNotNull { it.folderId }.filter { it == "Vieux" })
        assertEquals(1, subscribed.single { it.folderId == "Travail/2026" }.depth)
        assertEquals(0, subscribed.single { it.folderId == "Travail" }.depth)
    }

    @Test fun `a tap writes the role it names, and a role no folder may be pinned to writes nothing`() {
        assertEquals(
            SPECIAL_FOLDER_ROLES.map { SpecialFolderWrite(it, "Spam") },
            SPECIAL_FOLDER_ROLES.map { specialFolderWrite(it, "Spam") },
        )
        assertEquals(SpecialFolderWrite("trash", null), specialFolderWrite("trash", null))
        assertNull(specialFolderWrite("inbox", "INBOX"))
        assertNull(specialFolderWrite("all", "Archives"))
        assertNull(specialFolderWrite("", "Archives"))
    }
}

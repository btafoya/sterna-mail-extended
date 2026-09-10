package app.sterna.ui.settings

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the folder-management screen draws (#207), the rule, executed. Nothing here composes, and
 * nothing here recomputes the rule: every assertion pins the arguments the screen is handed.
 */
class FolderManagementRowsTest {

    private fun folder(
        id: String,
        role: String? = null,
        name: String = id,
        parentId: String? = null,
        subscribed: Boolean = true,
    ) = Mailbox(id = id, name = name, role = role, parentId = parentId, isSubscribed = subscribed)

    private val tree = listOf(
        folder("INBOX", role = "inbox"),
        folder("Travail"),
        folder("Travail/2026", name = "2026", parentId = "Travail"),
        folder("Sent", role = "sent"),
        folder("Drafts", role = "drafts"),
        folder("Trash", role = "trash"),
        folder("Junk", role = "junk"),
        folder("Archive", role = "archive"),
        folder("Vieux", subscribed = false),
    )

    private fun rows(
        mailboxes: List<Mailbox> = tree,
        showOnlySubscribed: Boolean = false,
        hidden: Set<String> = emptySet(),
        watched: Set<String> = emptySet(),
        inboxId: String? = "INBOX",
    ) = folderManagementRows(mailboxes, showOnlySubscribed, hidden, watched, inboxId)

    @Test fun `the screen lists the drawer's own order, children under their parent, nothing folded`() {
        assertEquals(
            "the screen's rows are not the drawer's tree: a second ordering was written, or a " +
                "folder was folded away. Every folder must stay on screen, or a hidden one cannot " +
                "be brought back.",
            listOf(
                "INBOX" to 0,
                "Drafts" to 0,
                "Sent" to 0,
                "Trash" to 0,
                "Junk" to 0,
                "Archive" to 0,
                "Travail" to 0,
                "Travail/2026" to 1,
                "Vieux" to 0,
            ),
            rows().map { it.mailbox.id to it.depth },
        )
    }

    @Test fun `a folder the subscription setting already hides is not listed`() {
        assertEquals(
            "the screen was populated from the raw folder list. With 'subscribed folders only' on " +
                "(#174) an unsubscribed folder must not be listed at all: its switch would say " +
                "'shown' and ticking it would bring nothing back.",
            listOf("INBOX", "Drafts", "Sent", "Trash", "Junk", "Archive", "Travail", "Travail/2026"),
            rows(showOnlySubscribed = true).map { it.mailbox.id },
        )
    }

    // ── the "show this folder" switch ────────────────────────────────────────────────────────────

    @Test fun `the switch reads the registry, and the Inbox reads checked and inert`() {
        val byId = rows(hidden = setOf("Travail", "INBOX")).associateBy { it.mailbox.id }
        assertEquals(
            "the hidden folder's switch does not read the registry.",
            false,
            byId.getValue("Travail").shown,
        )
        assertEquals(
            "a folder nothing hides must read as shown.",
            true,
            byId.getValue("Sent").shown,
        )
        assertEquals(
            "the Inbox read as hidden because a stale key names it. Nothing can hide it, so the " +
                "switch must say so.",
            true to false,
            byId.getValue("INBOX").shown to byId.getValue("INBOX").showToggleEnabled,
        )
        assertEquals(
            "an ordinary folder's switch must be settable, or the screen does nothing.",
            true,
            byId.getValue("Sent").showToggleEnabled,
        )
    }

    @Test fun `a folder hidden by its own key stays settable, or nothing can be brought back`() {
        val row = rows(hidden = setOf("Travail")).single { it.mailbox.id == "Travail" }
        assertEquals(
            "a folder hidden by its own key was left inert, so the switch that hid it cannot undo " +
                "it and the folder is gone for good. This screen exists to be the way back.",
            false to true,
            row.shown to row.showToggleEnabled,
        )
    }

    @Test fun `a child hidden by its own key AND by its parent keeps that key, and stays inert`() {
        val both = rows(hidden = setOf("Travail/2026", "Travail")).single { it.mailbox.id == "Travail/2026" }
        assertEquals(
            "a child that carries its own key under a hidden parent was left settable. Flipping it " +
                "there writes 'shown' over the choice the reader made, the row then reads checked " +
                "and inert while the folder is still off the drawer, and her own preference is gone " +
                "with nobody having asked. The question the guard asks is about the ANCESTORS; the " +
                "folder's own key is no part of it.",
            false to false,
            both.shown to both.showToggleEnabled,
        )
        val parentShown = rows(hidden = setOf("Travail/2026")).single { it.mailbox.id == "Travail/2026" }
        assertEquals(
            "unhiding the parent must hand the child back its switch, still reading hidden by its " +
                "own key, which nothing has touched.",
            false to true,
            parentShown.shown to parentShown.showToggleEnabled,
        )
    }

    @Test fun `a child of a hidden parent is inert, and its own key is untouched`() {
        val child = rows(hidden = setOf("Travail")).single { it.mailbox.id == "Travail/2026" }
        assertEquals(
            "the child of a hidden parent was left settable. Hiding takes the subtree with it, so " +
                "flipping the child's switch writes a key and changes nothing on screen. Its own " +
                "value must stay what the registry says, so unhiding the parent brings it back " +
                "without anything having been written under it.",
            false to true,
            child.showToggleEnabled to child.shown,
        )
        val free = rows(hidden = setOf("Sent")).single { it.mailbox.id == "Travail/2026" }
        assertEquals(
            "a child whose ancestors are all shown must stay settable.",
            true,
            free.showToggleEnabled,
        )
    }

    // ── the "notify about new mail" switch (#16) ─────────────────────────────────────────────────

    @Test fun `the notify switch is offered on a folder, never on the roles that would be noise`() {
        val offered = rows(watched = setOf("Travail")).filter { it.mayWatch }.map { it.mailbox.id }
        assertEquals(
            "the #16 guard did not travel with the switch. On the Inbox, Sent, Drafts, Trash and " +
                "Junk the row must not exist at all: the Inbox is always watched, and the other " +
                "four would notify the reader about her own mail.",
            listOf("Archive", "Travail", "Travail/2026", "Vieux"),
            offered,
        )
        assertEquals(
            "the notify switch does not read the watched set.",
            listOf("Travail"),
            rows(watched = setOf("Travail")).filter { it.watched }.map { it.mailbox.id },
        )
    }

    @Test fun `an Inbox the server gave no role is still not offered the notify switch`() {
        val noRoles = listOf(folder("Courrier", role = null), folder("Travail"))
        assertEquals(
            "the Inbox was found by its role alone. A server listing no inbox role leaves the " +
                "account's Inbox id on the first folder listed, and that folder is always watched.",
            listOf("Travail"),
            folderManagementRows(noRoles, false, emptySet(), emptySet(), inboxId = "Courrier")
                .filter { it.mayWatch }.map { it.mailbox.id },
        )
    }

    @Test fun `hiding a folder does not stop it notifying, and it stays on the screen`() {
        val row = rows(hidden = setOf("Travail"), watched = setOf("Travail")).single { it.mailbox.id == "Travail" }
        assertEquals(
            "the two switches stopped being orthogonal: a hidden folder must still notify, and it " +
                "must stay listed here, or nothing can ever unwatch or unhide it.",
            Triple(false, true, true),
            Triple(row.shown, row.watched, row.mayWatch),
        )
    }
}

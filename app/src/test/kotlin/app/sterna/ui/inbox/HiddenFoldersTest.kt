package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which folders survive the account's own hide registry (#207), the rule, executed, never read as
 * text. The second filter: it runs on what [visibleFolders] answered (#174).
 */
class HiddenFoldersTest {

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

    // ── the Inbox is not hideable, on either witness ─────────────────────────────────────────────

    @Test fun `the folder carrying the inbox role may not be hidden`() {
        assertFalse(
            "the Inbox may not be hidden: it is the folder every screen falls back to, and hiding " +
                "it leaves the account with no default list at all.",
            folderMayBeHidden(folder("INBOX", role = "inbox"), inboxId = "INBOX"),
        )
        assertFalse(
            "the role witness was dropped: an account record carries no inboxId until its first " +
                "sync writes one, and until then the id test alone leaves the Inbox hideable.",
            folderMayBeHidden(folder("mbx-1", role = "inbox"), inboxId = null),
        )
    }

    @Test fun `the fallback Inbox may not be hidden when no folder carries the role`() {
        // The account's Inbox id is the elected inbox role, and the first folder listed when the
        // server elected none.
        val fallback = folder("Courrier", role = null)
        assertFalse(
            "the folder the account records as its Inbox was declared hideable because it carries " +
                "no role. A server listing no inbox role at all leaves that id on the first folder " +
                "listed, and the role test alone would hide the only default list the account has.",
            folderMayBeHidden(fallback, inboxId = "Courrier"),
        )
    }

    @Test fun `any other folder may be hidden, role or not`() {
        assertTrue(
            "an ordinary folder must be hideable, or the switch does nothing.",
            folderMayBeHidden(folder("Travail"), inboxId = "INBOX"),
        )
        assertTrue(
            "a folder with a role other than inbox must be hideable.",
            folderMayBeHidden(folder("Archive", role = "archive"), inboxId = "INBOX"),
        )
    }

    // ── the subtree walk ─────────────────────────────────────────────────────────────────────────

    @Test fun `a child of a hidden parent is caught, by parentId and by IMAP path`() {
        val byParentId = listOf(folder("m1"), folder("m2", parentId = "m1")).associateBy { it.id }
        assertTrue(
            "a child whose parent is hidden was not caught. Hiding must take the subtree with it: " +
                "visibleFolders walks the ancestors of every survivor back in, so hiding the named " +
                "folder alone is inert the moment it has a visible child.",
            hiddenBySubtree(byParentId.getValue("m2"), byParentId, setOf("m1")),
        )
        val byPath = listOf(folder("Travail"), folder("Travail/2026", name = "2026")).associateBy { it.id }
        assertTrue(
            "a child named by an IMAP path under a hidden parent was not caught.",
            hiddenBySubtree(byPath.getValue("Travail/2026"), byPath, setOf("Travail")),
        )
    }

    @Test fun `a cyclic parentId does not spin`() {
        val cycle = listOf(
            folder("a", parentId = "b"),
            folder("b", parentId = "a"),
        ).associateBy { it.id }
        assertFalse(
            "a parentId cycle, which a server can answer, must end the walk instead of spinning.",
            hiddenBySubtree(cycle.getValue("a"), cycle, setOf("z")),
        )
        assertTrue(
            "the cycle guard swallowed the answer: a folder in the registry is hidden, cycle or no.",
            hiddenBySubtree(cycle.getValue("a"), cycle, setOf("b")),
        )
    }

    @Test fun `a child whose parent is not in the list is not caught`() {
        val orphan = listOf(folder("m2", parentId = "gone")).associateBy { it.id }
        assertFalse(
            "an unplaceable child is drawn at the root, exactly as visibleFolders leaves it, so a " +
                "hidden id it cannot be attached to must not take it away.",
            hiddenBySubtree(orphan.getValue("m2"), orphan, setOf("gone")),
        )
    }

    // ── the list ─────────────────────────────────────────────────────────────────────────────────

    @Test fun `an empty registry returns the very same list`() {
        val folders = listOf(folder("INBOX", role = "inbox"), folder("Travail"), folder("Perso"))
        assertSame(
            "with nothing hidden the list must come back untouched, an identity and not a rebuild.",
            folders,
            shownFolders(folders, emptySet(), inboxId = "INBOX"),
        )
    }

    @Test fun `hiding a parent removes its children too, and the order is the order it came in`() {
        val folders = listOf(
            folder("INBOX", role = "inbox"),
            folder("Travail"),
            folder("Travail/2026", name = "2026"),
            folder("Travailleur"),
            folder("Perso"),
        )
        assertEquals(
            "hiding a parent left its child on screen, or the list was reordered. The subtree goes " +
                "with the parent, and the entry order is never a sort.",
            listOf("INBOX", "Travailleur", "Perso"),
            shownFolders(folders, setOf("Travail"), inboxId = "INBOX").map { it.id },
        )
    }

    @Test fun `the Inbox is never removed, by its own key or by an ancestor's`() {
        val folders = listOf(folder("INBOX", role = "inbox"), folder("Travail"))
        assertEquals(
            "a stale key in the registry took the Inbox away: an account with no default folder " +
                "list left. The Inbox is not hideable, so it is not removable here either.",
            listOf("INBOX", "Travail"),
            shownFolders(folders, setOf("INBOX"), inboxId = "INBOX").map { it.id },
        )
        assertEquals(
            "the role witness was dropped from the list filter: before the first sync the account " +
                "record names no Inbox, and the folder holding the role is all there is to go on.",
            listOf("mbx-1"),
            shownFolders(listOf(folder("mbx-1", role = "inbox")), setOf("mbx-1"), inboxId = null).map { it.id },
        )
        val nested = listOf(
            folder("shared", name = "shared"),
            folder("shared/INBOX", name = "INBOX", parentId = "shared", lostClaim = true),
            folder("shared/Travail", name = "Travail", parentId = "shared"),
        )
        assertEquals(
            "hiding an ancestor took the Inbox with it. The folder the account records as its " +
                "Inbox stays on screen whatever a parent key says, role or no role.",
            listOf("shared/INBOX"),
            shownFolders(nested, setOf("shared"), inboxId = "shared/INBOX").map { it.id },
        )
    }

    // ── what the surfaces do with a registry, run rather than read off the source ────────────────

    @Test fun `the folder on screen becomes unreachable when an ancestor is hidden`() {
        val folders = listOf(
            folder("INBOX", role = "inbox"),
            folder("Travail"),
            folder("Travail/2026", name = "2026", parentId = "Travail"),
        )
        assertTrue(
            "hiding a parent left the list parked in a child no drawer offers any more. The " +
                "registry must be judged here, exactly as the subscription setting is (#174).",
            selectionIsUnreachable("Travail/2026", folders, false, setOf("Travail"), "INBOX"),
        )
        assertFalse(
            "a stale key sent the Inbox itself back to the Inbox, which is the fallback: a loop.",
            selectionIsUnreachable("INBOX", folders, false, setOf("INBOX"), "INBOX"),
        )
        assertFalse(
            "a folder nothing hides was declared unreachable.",
            selectionIsUnreachable("Travail", folders, false, setOf("Perso"), "INBOX"),
        )
    }

    @Test fun `a notification for a hidden folder does not move the list`() {
        val folders = listOf(folder("INBOX", role = "inbox"), folder("Travail"))
        assertEquals(
            "a tapped notification switched the list to a folder the account hides.",
            null,
            notificationFolderToShow("Travail", folders, false, setOf("Travail"), "INBOX"),
        )
        assertEquals(
            "a tapped notification stopped reaching a folder nothing hides.",
            "Travail",
            notificationFolderToShow("Travail", folders, false, setOf("Perso"), "INBOX"),
        )
    }

    @Test fun `a move picker offers neither a hidden folder nor its children`() {
        val folders = listOf(
            folder("INBOX", role = "inbox"),
            folder("Travail"),
            folder("Travail/2026", name = "2026", parentId = "Travail"),
            folder("Perso"),
        )
        assertEquals(
            "the move picker still offers a hidden folder, or one of its children.",
            listOf("INBOX", "Perso"),
            moveTargets(folders, currentMailboxId = null, hidden = setOf("Travail"), inboxId = "INBOX")
                .map { it.id },
        )
    }

    // ── the crossing with #174 ───────────────────────────────────────────────────────────────────

    @Test fun `the two filters composed in this order answer the folders a drawer draws`() {
        val folders = listOf(
            folder("INBOX", role = "inbox"),
            folder("Travail"),
            folder("Travail/2026", name = "2026", parentId = "Travail"),
            folder("Vieux", subscribed = false),
        )
        assertSame(
            "visibleFolders stopped answering the same instance with the setting off — the hide " +
                "filter was folded into it instead of being applied after it.",
            folders,
            visibleFolders(folders, false),
        )
        assertEquals(
            "the two filters must compose: the subscription setting drops the unsubscribed folder, " +
                "the registry drops the hidden subtree.",
            listOf("INBOX"),
            shownFolders(visibleFolders(folders, true), setOf("Travail"), inboxId = "INBOX").map { it.id },
        )
    }
}

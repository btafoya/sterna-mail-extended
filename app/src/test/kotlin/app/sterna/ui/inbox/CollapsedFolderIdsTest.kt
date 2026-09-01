package app.sterna.ui.inbox

import app.sterna.core.jmap.model.Mailbox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The resolution — registry vs default — EXECUTED. It is the single place the stored per-folder
 * choice and [defaultCollapsedFolderIds] become the set [mailboxTree] folds, and it is a function
 * precisely so a test can run it: in the drawer it would sit inside a Composable, which nothing in
 * this module can run.
 *
 * Values are pinned in full, never recomputed from the arguments under test: re-deriving the rule
 * here would keep this file green with the shipped condition inverted.
 *
 * The third argument is the account's answer to "can a folder row here badge unread at all"
 * (`MailRepository.folderRowsBadgeUnread`, false on IMAP). It reaches the DEFAULT and nothing else;
 * the cases at the bottom of this file are what says so.
 */
class CollapsedFolderIdsTest {

    private fun mailbox(id: String, role: String? = null, name: String = id, parentId: String? = null) =
        Mailbox(id = id, name = name, role = role, parentId = parentId)

    /** A flat list: nothing has children, so the default folds nothing and only the registry speaks. */
    private val flat = listOf(mailbox("a"), mailbox("b"), mailbox("Travail"), mailbox("Perso"), mailbox("Vieux"))

    /** `Travail` has a child, so the default would fold it; `Perso` is a leaf and it would not. */
    private val nested = listOf(mailbox("Travail"), mailbox("Travail/Client A"), mailbox("Perso"))

    @Test fun `only the folders the user FOLDED are folded`() {
        assertEquals(
            "the derivation returned the keys of the registry rather than the folded ones. The " +
                "registry stores both answers — true = folded, false = the user opened it by hand " +
                "— so handing over every key folds the folders the user explicitly UNFOLDED.",
            setOf("a"),
            collapsedFolderIds(flat, mapOf("a" to true, "b" to false), rowsBadgeUnread = true),
        )
    }

    @Test fun `a registry of nothing but false folds nothing`() {
        assertEquals(
            "folders the user explicitly unfolded came back as folded.",
            emptySet<String>(),
            collapsedFolderIds(flat, mapOf("a" to false, "b" to false), rowsBadgeUnread = true),
        )
    }

    @Test fun `an empty registry folds nothing when no folder has children`() {
        assertEquals(
            "with nobody having decided anything and nothing to hide, the tree must be drawn " +
                "entirely open: the default only ever folds a folder that has children.",
            emptySet<String>(),
            collapsedFolderIds(flat, emptyMap(), rowsBadgeUnread = true),
        )
    }

    @Test fun `every folded folder comes through, and only those`() {
        assertEquals(
            "a folded folder was dropped, or an unfolded one came through.",
            setOf("Travail", "Vieux"),
            collapsedFolderIds(flat, mapOf("Travail" to true, "Perso" to false, "Vieux" to true), rowsBadgeUnread = true),
        )
    }

    @Test fun `an untouched folder takes the default`() {
        assertEquals(
            "a folder nobody has touched must take the default (#185): a user folder with " +
                "children starts folded, a leaf does not.",
            setOf("Travail"),
            collapsedFolderIds(nested, emptyMap(), rowsBadgeUnread = true),
        )
    }

    /**
     * ⭐ The trap this branch has already paid for once, seen from the default. `false` is not
     * "not stored": it is the user having opened that folder by hand, and it has to win over a
     * default that would fold it — at every launch, for ever.
     */
    @Test fun `false in the registry keeps open a folder the default would fold`() {
        assertEquals(
            "a folder the user unfolded by hand folded itself again. That is the registry losing " +
                "to the default, and it happens at EVERY cold start: the chevron can be opened " +
                "and never stays open.",
            emptySet<String>(),
            collapsedFolderIds(nested, mapOf("Travail" to false), rowsBadgeUnread = true),
        )
    }

    @Test fun `true in the registry folds a folder the default would not`() {
        assertEquals(
            "the registry must win in both directions. Perso is a leaf the default leaves open, " +
                "and Travail is untouched so it keeps the default's answer.",
            setOf("Perso", "Travail"),
            collapsedFolderIds(nested, mapOf("Perso" to true), rowsBadgeUnread = true),
        )
    }

    @Test fun `a folded id the list does not hold survives`() {
        assertEquals(
            "a fold decided on a folder momentarily out of the drawn list was dropped, so it " +
                "comes back unfolded when the folder returns.",
            setOf("Vieux", "Travail"),
            collapsedFolderIds(nested, mapOf("Vieux" to true), rowsBadgeUnread = true),
        )
    }

    // ── V5: an account whose rows carry no unread count (IMAP) ──────────────────────────────────

    /**
     * ⭐ The heart of V5, and the thing it must NOT break. Suspending the default on an account
     * with no badges is one line; collapsing this resolution into "the set of folded folders"
     * while doing it is the trap this branch has already paid for once, and on IMAP it would be
     * worse, not better — the ids are paths, the fold is remembered per path, and the memory was
     * promised publicly on 2026-08-31 for every account.
     *
     * ⛔ The registry keeps winning in BOTH directions with no badge in sight: a `true` still
     * folds, a `false` still opens. Only the third bullet — the untouched folder — changes.
     */
    @Test fun `with no badge on any row, a folder the user FOLDED stays folded`() {
        assertEquals(
            "the user's own fold was dropped because the account cannot badge unread. V5 suspends " +
                "the DEFAULT, never the registry: a chevron the user closed by hand must still be " +
                "closed at the next cold start, on IMAP as on JMAP. Perso is a leaf the default " +
                "never folds and Travail is one it would have — both were folded by hand, both " +
                "must come back.",
            setOf("Perso", "Travail"),
            collapsedFolderIds(nested, mapOf("Perso" to true, "Travail" to true), rowsBadgeUnread = false),
        )
    }

    /**
     * ⛔ The mutation this case exists for: `if (!rowsBadgeUnread) return choices.keys`. It reads
     * like "obey the registry and nothing else" and it folds every folder the user OPENED by hand.
     * Every other case in this file stays green under it.
     */
    @Test fun `with no badge on any row, a folder the user OPENED stays open`() {
        assertEquals(
            "a folder carrying an explicit false came back folded. The registry stores both " +
                "answers; handing over its keys folds exactly what the user opened.",
            emptySet<String>(),
            collapsedFolderIds(nested, mapOf("Travail" to false), rowsBadgeUnread = false),
        )
    }

    /**
     * ⭐ The one behaviour V5 changes, pinned against its own control: the SAME list and the SAME
     * empty registry, answered both ways. `Travail` has a child, so with a badge available it
     * starts folded; with none, nobody has decided anything about it and nothing may fold it.
     */
    @Test fun `an untouched folder takes the default, and a badge-less account has none`() {
        assertEquals(
            "the control: with a badge on the row, an untouched user folder with children still " +
                "starts folded. If this is empty the feature is dead on every account.",
            setOf("Travail"),
            collapsedFolderIds(nested, emptyMap(), rowsBadgeUnread = true),
        )
        assertEquals(
            "an untouched folder folded itself on an account where no row can show what it hides. " +
                "That is mail disappearing from the drawer with nothing on screen saying so, which " +
                "is precisely what the folded row's badge exists to prevent — and on this account " +
                "the badge is a hard 0 by a decision written in MailRepository.",
            emptySet<String>(),
            collapsedFolderIds(nested, emptyMap(), rowsBadgeUnread = false),
        )
    }

    /**
     * A fold decided on a folder that is momentarily out of the drawn list survives on this
     * account too — the registry is untouched by V5, and the id is handed back although
     * [defaultCollapsedFolderIds] answers nothing at all here.
     */
    @Test fun `with no badge on any row, a folded id the list does not hold still survives`() {
        assertEquals(
            "a fold decided on a folder out of the drawn list was dropped. Travail is the " +
                "control: untouched, and with no badge it must NOT come back.",
            setOf("Vieux"),
            collapsedFolderIds(nested, mapOf("Vieux" to true), rowsBadgeUnread = false),
        )
    }
}

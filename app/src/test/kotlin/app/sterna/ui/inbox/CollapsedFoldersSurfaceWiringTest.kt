package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [SubscribedFoldersSurfaceWiringTest],
 * copied. The decisions of this branch are EXECUTED next door
 * ([app.sterna.core.data.account.CollapsedFoldersTest], [DefaultCollapsedFoldersTest],
 * [CollapsedFolderIdsTest]); what no JVM test in this module can run is the Compose drawer and an
 * `AndroidViewModel` reaching the account store, and that is where the memory is either kept per
 * account or lost.
 *
 * Whole lines are compared, never `contains`: a `contains` is blind to every mutation that
 * LENGTHENS a line, and three regressions have walked through that hole in this repo. The
 * ARGUMENTS are the point — a lint that only sees the call `viewModel.setFolderCollapsed(…)` stays
 * green when the fold and the unfold are swapped.
 *
 * What it is here to catch:
 *  1. the drawer keeping its own `remember`ed collapse state again — the whole defect: every
 *     rotation, every trip into a message, every cold start forgets the chevrons;
 *  2. the "reveal the new child" write folding the parent instead of unfolding it, or going back
 *     to a screen-local write (which would forget itself on the next launch);
 *  3. the registry not being re-read on an account switch — on IMAP the ids are paths, so the
 *     neighbouring account's namesake folder is drawn folded with nobody having asked (#92/#121);
 *  4. the flow being touched from `init`, which NPEs during ViewModel construction;
 *  5. the row's unread badge going back to the folder's own count, which makes the mail under a
 *     folder folded by default vanish before anyone has touched a chevron;
 *  6. the account's badge-availability flag (`MailRepository.folderRowsBadgeUnread`) not reaching
 *     the derivation, or not being re-read on an account switch — on an account whose folder rows
 *     carry no unread count at all (IMAP, measured 2026-09-01) the default must fold NOTHING, and
 *     a flag stuck on the departing account's answer is that rule applied to the wrong account.
 */
class CollapsedFoldersSurfaceWiringTest {

    @Test fun `the drawer reads the stored registry, and holds no collapse state of its own`() {
        assertEquals(
            "the drawer's collapse state must come from the ViewModel's persisted registry. A " +
                "screen-local remember() is the shipped defect: it is re-created on a rotation, a " +
                "locale change, a cold start and every return from a message (InboxScreen is a " +
                "NavHost destination), so every chevron the user touched is forgotten.",
            listOf("val collapsedFolders by viewModel.collapsedFolders.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val collapsedFolders by", 1),
        )
        assertEquals(
            "InboxScreen writes the collapse state into a local variable again. Every write must " +
                "go through viewModel.setFolderCollapsed, or the choice dies with the Composable. " +
                "A named ARGUMENT is not a write and is told apart by its trailing comma: since " +
                "#103 volet 6 the drawer content is a function of its own and is HANDED the " +
                "collected registry — which the rule below pins, so the exemption cannot be used " +
                "to smuggle a screen-local one back in.",
            emptyList<String>(),
            codeLines(INBOX_SCREEN).filter {
                it.startsWith("var collapsedFolders") ||
                    (it.startsWith("collapsedFolders =") && !it.endsWith(","))
            },
        )
        assertEquals(
            "the drawer content must be handed the ViewModel's registry, once per envelope — the " +
                "modal sheet under 1 200 dp and the permanent sheet above it (#103 volet 6). A " +
                "branch that passes something else to one of the two forgets the chevrons on that " +
                "window size alone, which is exactly the shape nobody would look for.",
            listOf("collapsedFolders = collapsedFolders,", "collapsedFolders = collapsedFolders,"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("collapsedFolders =") },
        )
    }

    @Test fun `the registry becomes the folded set in ONE place, and the tree still draws the filtered list`() {
        assertEquals(
            "the drawer no longer derives what is folded through collapsedFolderIds, or no longer " +
                "builds the tree from MailUi.visibleMailboxes. The derivation is the single point " +
                "that decides what an untouched folder does, and it is resolved against the SAME " +
                "list the tree is built from on the next line: handed the account's whole list it " +
                "would put a chevron on a parent whose only child the 'only subscribed folders' " +
                "setting hides (#174), and folding it would hide nothing. The THIRD argument is " +
                "the account's answer to 'can a folder row here badge unread at all': drop it and " +
                "the derivation goes back to folding by default on an account where nothing on " +
                "screen can say what a folded row hides.",
            listOf(
                "val collapsedIds = collapsedFolderIds(ui.visibleMailboxes, collapsedFolders, folderRowsBadgeUnread)",
                "mailboxTree(ui.visibleMailboxes, collapsedIds).forEach { node ->",
            ),
            block(INBOX_SCREEN, "val collapsedIds =", 2),
        )
        assertEquals(
            "the chevron's own state must be read from the same derived set the tree was built " +
                "from, or the glyph and the tree disagree.",
            listOf("val collapsed = mailbox.id in collapsedIds"),
            block(INBOX_SCREEN, "val collapsed = mailbox.id", 1),
        )
    }

    /**
     * ⭐ The badge, pinned with its ARGUMENTS and with the branch that reads it.
     *
     * [drawerUnreadCount] is EXECUTED next door ([DrawerUnreadCountTest]); what only this lint can
     * hold is WHICH list and WHICH folded set the row asks it about, and both are invisible to
     * every behavioural test:
     *
     *  - handed `ui.mailboxes` instead of `ui.visibleMailboxes`, a folded parent advertises unread
     *    sitting in a descendant the "only subscribed folders" setting hides (#174) — a number no
     *    gesture in this drawer can reach;
     *  - handed anything but `collapsedIds`, the badge and the tree disagree about what is hidden,
     *    so a row sums mail that is drawn right underneath it, or hides mail it drew nothing for;
     *  - `mailbox.unreadForList` back in either place is the shipped defect itself.
     *
     * The `if (unread > 0)` branch is pinned with it: it is what keeps a quiet folder's row showing
     * the bare name, with no "(0)" after it.
     */
    @Test fun `the row's badge is the folded-aware count, asked about the drawn list and the same folded set`() {
        assertEquals(
            "the drawer's unread badge changed shape. It must be drawerUnreadCount(mailbox, " +
                "ui.visibleMailboxes, collapsedIds) — the list the tree is built from and the set " +
                "the tree hides children with — and the label must read THAT number, on both " +
                "branches. Back on mailbox.unreadForList it is the defect this volet closes: a " +
                "folder folded on the first run shows only its own unread, and everything under it " +
                "is on no row at all. Nothing in this module can run a Composable, so these lines " +
                "are the only thing standing over that wiring.",
            listOf(
                "val unread = drawerUnreadCount(mailbox, ui.visibleMailboxes, collapsedIds)",
                "val label = if (unread > 0) {",
                "stringResource(R.string.inbox_folder_unread, displayName, unread)",
                "} else {",
                "displayName",
            ),
            block(INBOX_SCREEN, "val unread = ", 5),
        )
    }

    /**
     * ⭐ The two writes, pinned with their ARGUMENTS and in source order.
     *
     * The second is the chevron. The FIRST is the one nobody would think to protect: creating a
     * subfolder under a folded parent must reveal it, or the folder the user just made is nowhere
     * on screen. It must write `false` — an explicit "unfolded" — and not remove the key: an
     * absent key means "nobody decided", which is not what just happened.
     */
    @Test fun `both writes go through the ViewModel, with the argument each one owes`() {
        assertEquals(
            "the drawer's writes changed. Creating a subfolder must UNFOLD its parent " +
                "(`false`, never `true`, and never a key removal), and the chevron must write the " +
                "opposite of what it currently shows. Swap either argument and the drawer folds " +
                "what the user opened, with every behavioural test still green.",
            listOf(
                "viewModel.setFolderCollapsed(parent.id, false) // reveal the new child",
                "viewModel.setFolderCollapsed(mailbox.id, !collapsed)",
            ),
            codeLines(INBOX_SCREEN).filter { it.startsWith("viewModel.setFolderCollapsed(") },
        )
    }

    @Test fun `the flow is initialised inline from the CURRENT account`() {
        assertEquals(
            "the registry flow must be seeded inline, from the current account's stored registry. " +
                "Seeded from the init block it NPEs (init runs before this declaration's " +
                "initialiser); seeded from a constant, the first drawer opened after a cold start " +
                "shows every folder unfolded whatever the user chose.",
            listOf(
                "private val _collapsedFolders =",
                "MutableStateFlow(store.currentId()?.let { store.collapsedFolders(it) } ?: emptyMap())",
            ),
            block(INBOX_VIEW_MODEL, "private val _collapsedFolders", 2),
        )
        assertEquals(
            "InboxViewModel.setFolderCollapsed changed shape. It must resolve the CURRENT account " +
                "(and give up when there is none), write through the store, and re-read. Writing " +
                "under a remembered account id files one account's fold under another's; skipping " +
                "the re-read leaves the drawer showing the state it had before the tap.",
            listOf(
                "fun setFolderCollapsed(mailboxId: String, collapsed: Boolean) {",
                "val accountId = store.currentId() ?: return",
                "store.setFolderCollapsed(accountId, mailboxId, collapsed)",
                "refreshCollapsedFolders()",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "fun setFolderCollapsed(", 5),
        )
    }

    /**
     * ⭐ The account switch. `InboxScreen` switches accounts IN PLACE, so without this re-read the
     * departing account's registry stays on screen — and IMAP mailbox ids are paths, so a folder
     * named "Travail" on the arriving account inherits the fold decided on the other one (#92/#121).
     */
    @Test fun `the registry is re-read on an account switch, and REPLACED not merged`() {
        assertEquals(
            "an account switch no longer re-reads the collapse registry. The screen re-points at " +
                "the new account in place, so the previous account's folds stay on screen — and on " +
                "IMAP the ids are paths, so a namesake folder folds itself (#92/#121).",
            listOf(
                "folderSnapshot.value = null to emptyList()",
                "meta.value = Meta(store.accountLabel(), store.inboxMailboxName(), store.unreadCount())",
                "refreshWatchedFolders()",
                "refreshCollapsedFolders()",
            ),
            block(INBOX_VIEW_MODEL, "folderSnapshot.value = null to emptyList()", 4),
        )
        assertEquals(
            "the re-read REPLACES the registry with the arriving account's, and does not merge it " +
                "into what was on screen. Merging keeps the departing account's folds — and on " +
                "IMAP the ids are paths, so the namesake folder on the account being opened is " +
                "drawn folded with nobody having asked (#92/#121), which is the very defect this " +
                "branch closes. An empty body passes every other assertion in this file and kills " +
                "the feature outright: the chevron stops moving. BOTH statements are the rule: the " +
                "second re-reads whether the ARRIVING account's rows can badge unread at all, and " +
                "without it a JMAP account opened after an IMAP one keeps folding nothing, or an " +
                "IMAP account opened after a JMAP one folds by default with no badge anywhere to " +
                "say what went. Nothing executes this method — no test in this module can build an " +
                "InboxViewModel — so these lines are the only thing standing over it.",
            listOf(
                "private fun refreshCollapsedFolders() {",
                "val accountId = store.currentId()",
                "_collapsedFolders.value = accountId?.let { store.collapsedFolders(it) } ?: emptyMap()",
                "_folderRowsBadgeUnread.value = accountId?.let { repo.folderRowsBadgeUnread(it) } ?: false",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "private fun refreshCollapsedFolders(", 5),
        )
        assertEquals(
            "the registry is re-read from somewhere new, or no longer from all of the places it " +
                "must be. THREE call sites, pinned as a number and not as a count taken from the " +
                "file itself: the account switch above, setFolderCollapsed's own re-read, and the " +
                "folder RENAME — on IMAP the id is the path, MailRepository.renameFolder re-keys " +
                "the stored registry (AccountStore.replaceCollapsedFolder) beside the watch flags, " +
                "and without this re-read the flow keeps the OLD key: the renamed folder reads as " +
                "'nobody decided' and the default folds it on the spot, closing the folder the " +
                "user had opened by hand. ⛔ Still NOT the folder delete — nothing prunes the " +
                "registry, so a re-read there answers the same thing and only looks like a " +
                "cleanup that does not exist (#185, arbitration A7).",
            3,
            codeLines(INBOX_VIEW_MODEL).count { it == "refreshCollapsedFolders()" },
        )
    }

    /**
     * ⭐ V5's flag, end to end, in the two places nothing else can see it.
     *
     * The decision itself is EXECUTED next door ([DefaultCollapsedFoldersTest],
     * [CollapsedFolderIdsTest]) on real arguments. What no JVM test in this module can run is the
     * `AndroidViewModel` that asks the repository, nor the Composable that collects the answer —
     * and a perfect rule handed a constant `true` is the shipped defect untouched.
     *
     *  - seeded from `init` it NPEs during construction (init runs before this declaration's
     *    initialiser), which is why the seed is pinned inline and from the CURRENT account;
     *  - seeded from a literal `true`, the drawer folds by default on an IMAP account at every
     *    cold start with no badge anywhere to say what it hid;
     *  - collected but not passed on, the derivation next door never sees it.
     */
    @Test fun `the badge-availability flag is seeded inline, and reaches the screen`() {
        assertEquals(
            "InboxViewModel's badge-availability flag must be seeded INLINE from the current " +
                "account, by asking the repository — not from init (which NPEs), and not from a " +
                "constant (which pins every account to the same answer and puts the IMAP default " +
                "back). It is the account's property, so it is read per account id.",
            listOf(
                "private val _folderRowsBadgeUnread =",
                "MutableStateFlow(store.currentId()?.let { repo.folderRowsBadgeUnread(it) } ?: false)",
            ),
            block(INBOX_VIEW_MODEL, "private val _folderRowsBadgeUnread", 2),
        )
        assertEquals(
            "the drawer no longer collects the badge-availability flag from the ViewModel. " +
                "Computed in the Composable instead it would be out of reach of every test here; " +
                "not collected at all, the derivation below is handed something that does not " +
                "follow the account switch.",
            listOf("val folderRowsBadgeUnread by viewModel.folderRowsBadgeUnread.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val folderRowsBadgeUnread by", 1),
        )
    }

    /**
     * ⭐ The two lines that EXPOSE the flows, pinned whole — the weakest link of the chain above.
     *
     * Everything else in this file watches how the private flows are seeded and re-read, and the
     * decision itself is executed next door. None of it reads what the SCREEN is actually handed:
     * between the private flow and the Composable sits one assignment per flow, and rewriting
     * either of them as a constant is a one-line mutation that compiles, keeps every other
     * assertion here green, and ships the defect whole.
     *
     *  - `= MutableStateFlow(true)` on the badge-availability flow puts the IMAP default back: a
     *    user folder with children folds itself at every cold start on an account where not one row
     *    can badge what it hides;
     *  - `= MutableStateFlow(emptyMap())` on the registry forgets every chevron again, which is the
     *    whole defect this branch closes.
     *
     * The private flow keeps being written by `refreshCollapsedFolders`, so neither mutation even
     * raises an unused warning.
     */
    @Test fun `both flows are exposed as themselves, never as a constant`() {
        assertEquals(
            "the fold registry no longer reaches the screen as itself. Handed a constant or a " +
                "rebuilt flow, the drawer stops seeing what refreshCollapsedFolders writes and " +
                "every chevron is forgotten again — the shipped defect, with this whole file " +
                "still green over it.",
            listOf("val collapsedFolders: StateFlow<Map<String, Boolean>> = _collapsedFolders"),
            block(INBOX_VIEW_MODEL, "val collapsedFolders: StateFlow", 1),
        )
        assertEquals(
            "the badge-availability flag no longer reaches the screen as itself. Exposed as a " +
                "constant true it never answers false, so the default folds on an IMAP account " +
                "with no badge anywhere to say what went — V5 undone by one line, and nothing " +
                "else in this module can see it.",
            listOf("val folderRowsBadgeUnread: StateFlow<Boolean> = _folderRowsBadgeUnread"),
            block(INBOX_VIEW_MODEL, "val folderRowsBadgeUnread: StateFlow", 1),
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single code line starting with [needle]; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String): Int {
        val hits = lines.indices.filter { lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$needle` — this lint reads the shipped source " +
                    "and must be taught the new shape rather than left green over something it " +
                    "never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val INBOX_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        val INBOX_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        }
    }
}

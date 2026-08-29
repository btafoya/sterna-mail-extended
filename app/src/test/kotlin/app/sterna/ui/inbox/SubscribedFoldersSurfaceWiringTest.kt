package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SOURCE LINT, NOT A BEHAVIOUR TEST — the instrument of [app.sterna.ui.settings] copied once more.
 * The rule itself is EXECUTED next door ([VisibleFoldersTest], [SubscribedFolderSurfacesTest]);
 * what no JVM test in this module can run is Compose code and an `AndroidViewModel` reaching into
 * the app container, and that is exactly where the four surfaces are wired.
 *
 * Whole lines are compared, never `contains`: a `contains` check is blind to every mutation that
 * LENGTHENS the line, and three regressions have walked through that hole in this repo.
 *
 * What it is here to catch:
 *  1. the drawer drawing the WHOLE list again (the filter silently does nothing);
 *  2. the picker being handed a pre-filtered list for BOTH of its uses of it, which costs a child
 *     the parent segment of its displayed path (#109);
 *  3. the path label resolving against the offered targets instead of the whole list;
 *  4. `MailUi.mailboxes` being replaced by the filtered list — `subfolderIdsOf` reads it, and a
 *     folder delete that misses a hidden child leaves orphaned mail behind (data loss);
 *  5. #91's parked notification folder being judged against the filtered list, or replayed every
 *     time the setting changes; and the Inbox fallback losing one of its two causes — a folder
 *     DELETED (#89) or one the setting just hid (#174, arbitration of 2026-08-25);
 *  6. the filter migrating to the repository, where it would poison every reader of the folder list.
 */
class SubscribedFoldersSurfaceWiringTest {

    @Test fun `the drawer draws the FILTERED list`() {
        assertEquals(
            "the drawer tree is built from something other than MailUi.visibleMailboxes — from the " +
                "whole list, the setting hides nothing at all; from a list built here, the rule " +
                "lives in a Composable no test can run.",
            listOf("mailboxTree(ui.visibleMailboxes, collapsedFolders).forEach { node ->"),
            block(INBOX_SCREEN, "mailboxTree(", 1),
        )
    }

    @Test fun `the list picker filters its TARGETS and resolves paths against the whole list`() {
        assertEquals(
            "the list's move picker no longer passes the account's whole folder list plus the flag. " +
                "Filtering at the call site instead hands moveTargets the same filtered list twice, " +
                "and a child whose parent is hidden loses a segment of its path (#109). The three " +
                "remember() keys are pinned too: the targets are memoized so typing in the filter " +
                "field (#182) does not re-sort the whole account on every keystroke, and a key " +
                "dropped from that list freezes the picker on a stale folder list.",
            listOf(
                "val targets = remember(moveTargetMailboxes, ui.selectedMailboxId, moveTargetsOnlySubscribed) {",
                "moveTargets(moveTargetMailboxes, ui.selectedMailboxId, moveTargetsOnlySubscribed)",
                "}",
            ),
            block(INBOX_SCREEN, "val targets = remember(", 3),
        )
        assertEquals(
            "the picker's second line under a folder name must be resolved against the WHOLE list. " +
                "It is lifted OUT of the painting loop and memoized on that list (#182): the filter " +
                "field re-runs the block on every keystroke, and mailboxPathLabel rebuilds a map " +
                "over the whole account each call — but the list it is handed must still be the " +
                "whole one, or a child whose parent is hidden loses a segment of its path (#109).",
            listOf(
                "val movePaths = remember(targets, moveTargetMailboxes) {",
                "targets.map { folder -> mailboxPathLabel(folder, moveTargetMailboxes) }",
                "}",
            ),
            block(INBOX_SCREEN, "val movePaths = remember(", 3),
        )
        assertEquals(
            "the flag the picker uses must come from the SELECTION's account, not from a screen-" +
                "local guess: in the unified inbox the selected message can belong to a sibling " +
                "account with a different answer (#92).",
            listOf("val moveTargetsOnlySubscribed by viewModel.selectionOnlySubscribed.collectAsStateWithLifecycle()"),
            block(INBOX_SCREEN, "val moveTargetsOnlySubscribed by", 1),
        )
    }

    @Test fun `the reader's picker filters on the OPEN MESSAGE's account`() {
        assertEquals(
            "the reader's move picker changed shape. It must filter on the account that owns the " +
                "open message (#92), and hand moveTargets the whole folder list plus the flag.",
            listOf(
                "val moveTargets: StateFlow<List<Mailbox>> = combine(",
                "_ownerAccountId.flatMapLatest { id ->",
                "if (id == null) flowOf(emptyList()) else repo.observeMailboxes(id)",
                "},",
                "_mailboxId,",
                "combine(store.accountsFlow, _ownerAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) },",
                ") { folders, current, onlySubscribed -> moveTargets(folders, current, onlySubscribed) }",
            ),
            block(MESSAGE_VIEW_MODEL, "val moveTargets: StateFlow<List<Mailbox>> = combine(", 7),
        )
    }

    @Test fun `the unread view's scope is recomputed on the list AND on the setting`() {
        assertEquals(
            "the 'unread' view's scope no longer follows both the folder list and the setting. " +
                "Recomputed only when the list arrives, ticking the box leaves the view paging the " +
                "folders it hides; recomputed from a second collector of [mailboxes], #89 and #91 " +
                "are replayed on a setting change.",
            listOf(
                "private val unreadScopes: StateFlow<List<Pair<String, String>>> =",
                "combine(folderSnapshot, showOnlySubscribed) { (accountId, folders), onlySubscribed ->",
                "unreadViewScopes(accountId, visibleFolders(folders, onlySubscribed))",
                "}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())",
            ),
            block(INBOX_VIEW_MODEL, "private val unreadScopes:", 4),
        )
    }

    /**
     * ⭐ WHICH account each surface asks. Nothing else in this file reaches these two flows, and a
     * source lint is all there is: `InboxViewModel` cannot be instantiated on this bench.
     *
     * Two mutations survive everything else without these blocks, and both are silent:
     *
     *  - `showOnlySubscribedFor(curId, accounts)` in the picker's flow — from the unified inbox a
     *    message of account B would be offered B's folders under A's setting, so B's hidden folders
     *    come back as move destinations, or A's tick hides folders on an account that never asked;
     *  - `accounts.any { it.showOnlySubscribedFolders }` in the drawer's flow — one account ticking
     *    the box hides folders in EVERY account. That is the very defect
     *    `ShowOnlySubscribedFoldersTest` closes for the WRITE; this is its reading half.
     */
    @Test fun `each surface reads the setting of the account it is showing`() {
        assertEquals(
            "the drawer's flag must come from the CURRENT account, and from that one alone: " +
                "an aggregate over every account lets one tick hide folders in all of them.",
            listOf(
                "private val showOnlySubscribed: StateFlow<Boolean> =",
                "combine(store.accountsFlow, currentAccountId) { accounts, id -> showOnlySubscribedFor(id, accounts) }",
                ".stateIn(viewModelScope, SharingStarted.Eagerly, false)",
            ),
            block(INBOX_VIEW_MODEL, "private val showOnlySubscribed:", 3),
        )
        assertEquals(
            "the list picker's flag must come from the SELECTION's account, falling back to the " +
                "current one — never straight from the current one (#92): mailbox ids collide " +
                "between two accounts of one server, and the two accounts' answers differ.",
            listOf(
                "val selectionOnlySubscribed: StateFlow<Boolean> =",
                "combine(selectionAccountId, currentAccountId, store.accountsFlow) { selId, curId, accounts ->",
                "showOnlySubscribedFor(selId ?: curId, accounts)",
                "}.stateIn(viewModelScope, SharingStarted.Eagerly, false)",
            ),
            block(INBOX_VIEW_MODEL, "val selectionOnlySubscribed:", 4),
        )
    }

    @Test fun `the side effects of the folder flow are NOT replayed on a setting change`() {
        assertEquals(
            "#91's parked notification folder must be judged against the folder list as it arrives, " +
                "unfiltered, and only when it arrives.",
            listOf("applyNotificationFolder(folders)"),
            block(INBOX_VIEW_MODEL, "applyNotificationFolder(folders)", 1),
        )
        assertEquals(
            "the folder a notification switches to must go through notificationFolderToShow, with " +
                "the WHOLE list and the current account's flag: resolve() is given the whole list on " +
                "purpose (a hidden folder is not an unknown one) and it is its RESULT that is judged. " +
                "Selecting resolve()'s answer directly parks the list in a folder no drawer offers, " +
                "which [inboxFallback] then bounces out of — intermittently, since the snapshot " +
                "conflates equal folder lists (#91 vs #174, arbitration of 2026-08-25).",
            listOf(
                "val show = notificationFolderToShow(target, folders, showOnlySubscribed.value) ?: return",
                "folders.firstOrNull { it.id == show }?.let(::select)",
            ),
            block(INBOX_VIEW_MODEL, "val show = notificationFolderToShow(", 2),
        )
        assertEquals(
            "the folder flow grew a line. It carries side effects and is collected ONCE: anything " +
                "judged in here runs when a LIST ARRIVES and at no other moment, which is why the " +
                "Inbox fallback no longer lives here (see the next test) and why the setting must " +
                "never be read here — the box being ticked does not make this flow emit. The third " +
                "line is the restored view's missing folder name, filled in from this cached list " +
                "and pinned in ORDER by RestoredHeaderWiringLintTest: it must stay AFTER the " +
                "notification's folder, whose select() the memory then stands down for.",
            listOf(
                "}.onEach { folders ->",
                "applyNotificationFolder(folders)",
                "applyRestoredMeta(folders)",
                "folderSnapshot.value = currentAccountId.value to folders",
                "}",
            ),
            block(INBOX_VIEW_MODEL, "}.onEach { folders ->", 5),
        )
        assertEquals(
            "the folder list must be recorded WITH the account it belongs to. Recomputing the scope " +
                "later against `currentAccountId.value` pairs the new account with the previous " +
                "account's folder ids (#121/#31).",
            listOf("folderSnapshot.value = currentAccountId.value to folders"),
            block(INBOX_VIEW_MODEL, "folderSnapshot.value = currentAccountId", 1),
        )
    }

    /**
     * ⭐ The Inbox fallback, and WHAT it is judged against — the 2026-08-25 arbitration, which
     * reverses what #174 first shipped ("hidden is not deleted") under rule 3, WYSIWYG: the bench
     * left the screen titled `Vieux`, showing `Vieux`, while the setting said only subscribed
     * folders are shown and neither the drawer nor "move to" would still offer it. A trap, too:
     * no way back in without unticking the box.
     *
     * The decision itself is [selectionIsUnreachable]'s and is EXECUTED next door
     * ([SelectionIsUnreachableTest]). What only a source lint can hold here is that it is fed BOTH
     * causes — the folder list and the setting — from the recorded snapshot rather than from a
     * second collection of [mailboxes], which would replay #91's parked notification folder.
     *
     * Two mutations this is the only thing standing between:
     *  - the fallback judged on `visibleFolders(folders, onlySubscribed)` instead, which spends
     *    both of [selectionIsGone]'s conservative guards on the filtered list;
     *  - `combine(folderSnapshot, showOnlySubscribed)` reduced back to the folder list alone,
     *    which is exactly the shipped defect: ticking the box makes no folder list arrive.
     */
    @Test fun `the Inbox fallback follows the folder list AND the setting`() {
        assertEquals(
            "the Inbox fallback must be derived from the recorded snapshot AND the setting, and " +
                "must hand selectionIsUnreachable the WHOLE list plus the flag — the filtering is " +
                "that function's own business, and it needs both lists to keep #89 (deleted) apart " +
                "from a list nobody has loaded yet.",
            listOf(
                "private val inboxFallback: Job =",
                "combine(folderSnapshot, showOnlySubscribed) { (_, folders), onlySubscribed ->",
                "selectionIsUnreachable((selection.value as? Sel.Folder)?.id, folders, onlySubscribed)",
                "}.onEach { unreachable -> if (unreachable) showInbox() }",
                ".launchIn(viewModelScope)",
            ),
            block(INBOX_VIEW_MODEL, "private val inboxFallback:", 5),
        )
        assertEquals(
            "the selection rule must be applied in ONE place. A second call site is a second " +
                "answer to give when they disagree.",
            1,
            codeLines(INBOX_VIEW_MODEL).count { "selectionIsUnreachable(" in it },
        )
    }

    @Test fun `the state carries BOTH lists, and the whole one is still the whole one`() {
        assertEquals(
            "the filtered list must be a SECOND field. Replacing MailUi.mailboxes with it breaks " +
                "subfolderIdsOf (which reads state.value.mailboxes): a folder deleted with a hidden " +
                "child leaves that child's mail behind, unreachable — data loss.",
            listOf(NEW_BASE_COPY),
            block(INBOX_VIEW_MODEL, "base.copy(", 1),
        )
        val lines = codeLines(INBOX_VIEW_MODEL)
        assertEquals(
            "MailUi.mailboxes must still be fed the account's whole folder list.",
            1,
            lines.count { it == "mailboxes = base.mailboxes," },
        )
        assertEquals(
            "MailUi must still carry the filtered list next to it.",
            1,
            lines.count { it == "visibleMailboxes = base.visibleMailboxes," },
        )
        assertEquals(
            "subfolderIdsOf must keep reading the WHOLE list, or a folder delete misses its hidden " +
                "children and orphans their mail.",
            listOf("val all = state.value.mailboxes"),
            block(INBOX_VIEW_MODEL, "val all = state.value.mailboxes", 1),
        )
    }

    @Test fun `the filter is applied at the screen, never at the source`() {
        val callers = MAIN_SOURCES.filter { file ->
            codeLines(file).any { "visibleFolders(" in it }
        }.map { it.name }.sorted()
        assertEquals(
            "the subscription filter moved. It belongs to the three files below and nowhere else — " +
                "in the repository or in the folder cache it would take with it every reader of the " +
                "list: subfolderIdsOf (a delete that misses hidden children), the reader's parent " +
                "paths, the rule editor, the watched folders.",
            listOf("FolderSelection.kt", "InboxViewModel.kt", "SubscribedFolders.kt"),
            callers,
        )
    }

    // ── instrument (copied from ShowOnlySubscribedFoldersWiringTest) ─────────────────────────────

    /** [count] consecutive code lines from the ONE line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val at = only(lines, prefix, exact = false)
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** The index of the single matching code line; fails loudly on none or several. */
    private fun only(lines: List<String>, needle: String, exact: Boolean = true): Int {
        val hits = lines.indices.filter { if (exact) lines[it] == needle else lines[it].startsWith(needle) }
        return hits.singleOrNull()
            ?: error(
                "${hits.size} code lines match `$needle` — this lint reads the shipped source and " +
                    "must be taught the new shape rather than left green over something it never read",
            )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        /** ⚠ Whole line, arguments included — the shape `PageKeyWiringLintTest` and
         *  `UnreadViewWiringLintTest` pin from their own angle. */
        private const val NEW_BASE_COPY =
            "base.copy(accountId = accountId, unreadViewCount = unreadViewCount(accountId, " +
                "scopes, base.mailboxes), visibleMailboxes = visibleFolders(base.mailboxes, " +
                "onlySubscribed))"

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
        val MESSAGE_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        }
        val MAIN_SOURCES: List<File> by lazy {
            File(root, "app/src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        }
    }
}

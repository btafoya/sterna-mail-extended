package app.sterna.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Source lint, and a last resort: what the folder screen DECIDES is executed next door
 * ([FolderManagementRowsTest]), and nothing in this module composes ([CONTRIBUTING.md]). What only
 * this can see is that the screen is reachable, that it is handed the account it manages, and that
 * the drawer's overflow button stopped offering a menu it no longer fills.
 *
 * Whole trimmed lines, by equality, never `contains`, which is blind to every mutation that
 * lengthens a line.
 */
class FolderManagementWiringLintTest {

    @Test fun `the drawer opens the screen for the account it is showing`() {
        assertEquals(
            "the drawer's entry into the folder screen changed shape. It must sit after 'New " +
                "folder' and carry the CURRENT account: with no argument the screen opens on " +
                "whatever the store answers, and on a second account that is the wrong folder list " +
                "with the wrong registry (#92/#121).",
            listOf("onManageFolders = { entry.navigateOnce { nav.navigate(\"folders?accountId=\${Uri.encode(currentAccountId)}\") } },"),
            block(STERNA_APP, "onManageFolders = {", 1),
        )
        assertEquals(
            "the drawer's row moved, changed shape, or stopped closing the drawer behind it. Its " +
                "PLACE is pinned with it: right after 'New folder', which is where the reader was " +
                "shown it, and above the divider that separates the folder actions from Settings. " +
                "Pinned as one run of lines, because the six lines of the row alone are the same " +
                "six wherever the block is moved to.",
            listOf(
                "icon = { Icon(Icons.Filled.CreateNewFolder, contentDescription = null) },",
                "label = { Text(stringResource(R.string.inbox_new_folder)) },",
                "selected = false,",
                "onClick = onCreateFolder,",
                "modifier = Modifier.padding(horizontal = 12.dp),",
                ")",
                "NavigationDrawerItem(",
                "icon = { Icon(Icons.Filled.Tune, contentDescription = null) },",
                "label = { Text(stringResource(R.string.folders_manage_title)) },",
                "selected = false,",
                "onClick = {",
                "onManageFolders()",
                "scope.launch { drawerState.close() }",
                "},",
                "modifier = Modifier.padding(horizontal = 12.dp),",
                ")",
                "HorizontalDivider()",
            ),
            block(INBOX_SCREEN, "icon = { Icon(Icons.Filled.CreateNewFolder,", 17),
        )
    }

    @Test fun `the route carries the account and both navigations go through the guard`() {
        assertEquals(
            "the folder screen's route changed shape. The account travels as an optional argument " +
                "on the pattern of 'settings?accountId={accountId}'; the screen is handed the id " +
                "the route carries, and back goes through navigateOnce, or a double tap pops two " +
                "screens.",
            listOf(
                "route = \"folders?accountId={accountId}\",",
                "arguments = listOf(",
                "navArgument(\"accountId\") { type = NavType.StringType; nullable = true; defaultValue = null },",
                "),",
                "enterTransition = {",
                "if (!motionEnabled) EnterTransition.None else slideInHorizontally(tween(SCREEN_SLIDE_MS)) { it }",
                "},",
                "popExitTransition = {",
                "if (!motionEnabled) ExitTransition.None else slideOutHorizontally(tween(SCREEN_SLIDE_MS)) { it }",
                "},",
                ") { entry ->",
                "FolderManagementScreen(",
                "accountId = entry.arguments?.getString(\"accountId\")?.ifBlank { null }.orEmpty(),",
                "onBack = { entry.navigateOnce { nav.popBackStack() } },",
                ")",
            ),
            block(STERNA_APP, "route = \"folders?accountId={accountId}\",", 15),
        )
    }

    /**
     * The overflow button's guard, the one piece of the drawer that had no test at all. Its three
     * remaining items are all `role == null`, so the guard and the items must say the same thing
     * once: on an Archive, an All, or any JMAP role nobody normalised, the old role list opened an
     * empty menu.
     */
    @Test fun `the overflow button is offered on a user folder alone, and its items are not guarded twice`() {
        assertEquals(
            "the drawer's overflow button no longer asks the question its three items answer. " +
                "Written as a list of roles it opens an EMPTY menu on Archive, on All, and on every " +
                "JMAP role RFC 8621 allows and nothing normalises.",
            listOf("badge = if (mailbox.role == null) {"),
            block(INBOX_SCREEN, "badge = if (", 1),
        )
        assertEquals(
            "the folder menu must hold exactly its three management items: the watch checkbox moved " +
                "to the folder screen (#207), where the roles it must not appear on are decided by " +
                "folderManagementRows and executed.",
            listOf(
                "DropdownMenu(folderMenu, onDismissRequest = { folderMenu = false }, shape = MaterialTheme.shapes.medium) {",
                "DropdownMenuItem(",
                "text = { Text(stringResource(R.string.inbox_new_subfolder)) },",
                "onClick = { folderMenu = false; onAddSubfolder(mailbox) },",
                ")",
                "DropdownMenuItem(",
                "text = { Text(stringResource(R.string.inbox_rename)) },",
                "onClick = { folderMenu = false; onRenameFolder(mailbox) },",
                ")",
                "DropdownMenuItem(",
                "text = { Text(stringResource(R.string.inbox_delete)) },",
                "onClick = { folderMenu = false; onDeleteFolder(mailbox) },",
                ")",
                "}",
            ),
            block(INBOX_SCREEN, "DropdownMenu(folderMenu,", 14),
        )
        assertEquals(
            "the drawer must not read the watch registry any more, and no folder row may write it: " +
                "the switch lives on the folder screen, which writes it per account.",
            emptyList<String>(),
            codeLines(INBOX_SCREEN).filter { "watchedFolders" in it || "setFolderWatched(" in it },
        )
    }

    @Test fun `the screen draws the rows the rule answers, and writes both switches per account`() {
        assertEquals(
            "the screen no longer builds its rows from folderManagementRows with the account's own " +
                "setting, registry, watch set, Inbox id, order and the reorder mode it is in. " +
                "Recomputing any of them here would put the rule in a Composable no test can run; " +
                "dropping the mode lists hidden folders among the ones Up and Down trade places with.",
            listOf(
                "val rowsFor = { order: List<String> -> folderManagementRows(folders, showOnlySubscribedFoldersOf(account), " +
                    "account.hiddenFolders, account.watchedFolders, account.inboxId, order, reordering) }",
                "val rows = rowsFor(account.folderOrder)",
            ),
            block(SCREEN, "val rowsFor =", 2),
        )
        assertEquals(
            "the 'show this folder' switch must write the NEGATION of what it reads, on the account " +
                "the screen manages, and must be inert exactly when the rule says so.",
            listOf(
                "checked = row.shown,",
                "onCheckedChange = { viewModel.setFolderHidden(accountId, row.mailbox.id, !it) },",
                "enabled = row.showToggleEnabled,",
            ),
            block(SCREEN, "checked = row.shown,", 3),
        )
        assertEquals(
            "the notify switch must exist only where the rule says it may, and write the watch " +
                "registry for the account the screen manages (#16).",
            listOf(
                "if (row.mayWatch) {",
                "SettingSwitch(",
                "title = stringResource(R.string.inbox_folder_watch),",
                "subtitle = stringResource(R.string.folders_manage_watch_subtitle),",
                "checked = row.watched,",
                "onCheckedChange = { viewModel.setFolderWatched(accountId, row.mailbox.id, it) },",
            ),
            block(SCREEN, "if (row.mayWatch) {", 6),
        )
    }

    /**
     * The special-folder lines (#227). What they offer is executed next door
     * ([SpecialFolderRowsTest]); what only this can see is where the names they print come from and
     * which door the tap goes through.
     */
    @Test fun `the five role lines are built from the account's own choices and folder list`() {
        assertEquals(
            "the special-folder lines no longer come from specialFolderRows with the account's own " +
                "setting, choices, Inbox id and protocol. Recomputing any of them here would put " +
                "the rule in a Composable no test can run, and a protocol read wrong offers the " +
                "reserved INBOX path as a target for a role.",
            listOf(
                "val specialRows = specialFolderRows(folders, showOnlySubscribedFoldersOf(account), " +
                    "account.specialFolders, account.inboxId, account.protocol == MailProtocol.IMAP, account.folderOrder)",
            ),
            block(SCREEN, "val specialRows =", 1),
        )
        assertEquals(
            "a tap on a picker entry must write the role of the line it belongs to, on the account " +
                "the screen manages, and the entry's own folder — null being the Automatic entry.",
            listOf(
                "SpecialFolderChoiceRow(",
                "row = row,",
                "onChoose = { viewModel.setSpecialFolder(accountId, row.role, it) },",
                ")",
            ),
            block(SCREEN, "SpecialFolderChoiceRow(", 4),
        )
        assertEquals(
            "the picker must print the name the SERVER gave a folder. Painted with the role name, " +
                "the account that motivated the lot offers \"Spam\" twice and is designated at " +
                "random — which is the whole reason this picker exists (#227). The role a folder " +
                "already carries is named beside it, never instead of it.",
            listOf(
                "option.folderId == null -> stringResource(R.string.folders_special_automatic)",
                "option.heldRole != null -> stringResource(",
                "R.string.folders_special_option_role,",
                "option.name,",
                "mailboxDisplayName(option.heldRole, option.heldRole),",
                ")",
                "else -> option.name",
            ),
            block(SCREEN, "option.folderId == null ->", 7),
        )
        assertEquals(
            "the line under the role's name must read the label the rule decided, branch for " +
                "branch. Printed as a detected folder, a designated one tells the user her choice " +
                "did nothing.",
            listOf(
                "when (val label = row.label) {",
                "SpecialFolderLabel.None -> stringResource(R.string.folders_special_automatic)",
                "is SpecialFolderLabel.Chosen -> label.name",
                "is SpecialFolderLabel.Detected ->",
                "stringResource(R.string.folders_special_automatic_named, label.name)",
                "},",
            ),
            block(SCREEN, "when (val label = row.label) {", 6),
        )
    }

    @Test fun `the folder choice goes through the door that also rewrites the cached role`() {
        assertEquals(
            "the choice must be written through MailFolders, the one door that applies it to the " +
                "folder cache at once. Written into the account record alone (store.setSpecialFolder) " +
                "the drawer, the search and the delete go on naming the old folder until the next " +
                "sync, and on viewModelScope the back gesture that usually follows the tap cancels " +
                "it (#174).",
            listOf(
                "fun setSpecialFolder(id: String, role: String, folderId: String?) {",
                "val write = specialFolderWrite(role, folderId) ?: return",
                "appScope.launch {",
                "runCatching { mail.folders.setSpecialFolder(id, write.role, write.folderId) }",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun setSpecialFolder(", 4),
        )
    }

    @Test fun `the screen reads and writes the account it was opened for, never the current one`() {
        assertEquals(
            "the folder list handed to the screen must be the managed account's. Read from the " +
                "current account instead and the screen lists one account's folders while its " +
                "switches write another's registry — and on IMAP the ids are paths that collide " +
                "between two accounts of one server (#92/#121).",
            listOf("fun folders(id: String): Flow<List<Mailbox>> = mail.observeMailboxes(id)"),
            block(ACCOUNTS_VIEW_MODEL, "fun folders(", 1),
        )
        assertEquals(
            "the hide write must land on the account the screen manages, and on the folder the row " +
                "names.",
            listOf(
                "fun setFolderHidden(id: String, folderId: String, hidden: Boolean) {",
                "store.setFolderHidden(id, folderId, hidden)",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun setFolderHidden(", 2),
        )
    }

    @Test fun `unwatching a folder drops its baseline and re-arms push, as the drawer's menu did`() {
        assertEquals(
            "the watch write lost a step it had in the drawer. Without the baseline drop a later " +
                "re-watch reseeds from a stale diff and notifies about mail already read; without " +
                "the re-arm the push subscription still asks for the folder just unwatched, or " +
                "stops asking for the one just watched until something else re-arms it (#16).",
            listOf(
                "fun setFolderWatched(id: String, folderId: String, watched: Boolean) {",
                "store.setFolderWatched(id, folderId, watched)",
                "if (!watched) NewMailNotifier.clear(getApplication(), id, folderId)",
                "PushController.apply(getApplication(), userInitiated = true)",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun setFolderWatched(", 4),
        )
    }

    /**
     * The reorder mode. Which buttons a row carries and the fratrie it reorders are executed in
     * [FolderReorderRowsTest]; what only this can see is that the tap hands the store that same
     * fratrie, for the account the screen manages, and that each button is drawn on its own flag.
     */
    @Test fun `a move tap hands the store the row's own fratrie, on the account the screen manages`() {
        assertEquals(
            "the move tap no longer hands the fratrie the rule computed for THIS row. Any other list " +
                "(the top level, a list rebuilt here) moves a subfolder among folders it is not " +
                "drawn with.",
            listOf("FolderReorderRow(row, handle) { moves, up -> viewModel.moveFolder(accountId, moves.siblings, row.mailbox.id, up) }"),
            block(SCREEN, "FolderReorderRow(row, handle) {", 1),
        )
        assertEquals(
            "the Up and Down buttons are no longer drawn on their own flag, or no longer say which " +
                "way they move and which folder, to a screen reader.",
            listOf(
                "MoveSlot(moves.mayMoveUp, Icons.Filled.KeyboardArrowUp, stringResource(R.string.folders_reorder_up, name)) { onMove(moves, true) }",
                "MoveSlot(moves.mayMoveDown, Icons.Filled.KeyboardArrowDown, stringResource(R.string.folders_reorder_down, name)) { onMove(moves, false) }",
            ),
            block(SCREEN, "MoveSlot(moves.mayMoveUp,", 2),
        )
        assertEquals(
            "the move no longer goes to AccountStore.moveFolder, the one write that reads the order " +
                "under the store's lock: two quick taps would lose one.",
            listOf(
                "fun moveFolder(id: String, siblings: List<String>, folderId: String, up: Boolean) {",
                "store.moveFolder(id, siblings, folderId, up)",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun moveFolder(", 2),
        )
    }

    /**
     * The drag. What [folderDrop] accepts and writes is executed in [FolderDropTest], and the
     * column's placing in `ReorderDropTest`. Holes 1 and 2 of `CONTRIBUTING.md` apply: `rows` is
     * the value `val rows =` above pins only because no other `rows` is in scope, and nothing here
     * sees an expression wrapped around the calls. Hole 4 too: what the column hands back as
     * `from` and `to` is never observed here.
     */
    @Test fun `a drop is judged and written by the rule, on the rows drawn, for the account managed`() {
        assertEquals(
            "the reorder mode no longer hands the column the rows it draws with folderDrop as its " +
                "judge, or no longer writes what folderDrop answers through moveFolderTo on the " +
                "account the screen manages. A judge of its own here, or one that stops at the " +
                "standard folders, lets a subfolder be written among another parent's folders; " +
                "a write of `to` itself puts the folder at a row index, not a place in its fratrie; " +
                "and without `span` a parent is carried without its subfolders, so the list shown " +
                "at the release is not the one drawn after the write.",
            listOf(
                "ReorderableColumn(",
                "items = rows,",
                "key = { it.mailbox.id },",
                "canMove = { from, to -> folderDrop(rows, from, to, account.folderOrder, rowsFor) != null },",
                "onMove = { from, to -> folderDrop(rows, from, to, account.folderOrder, rowsFor)?.let { viewModel.moveFolderTo(accountId, it.siblings, it.folderId, it.toIndex) } },",
                "span = { folderSubtreeSize(rows, it) },",
            ),
            block(SCREEN, "ReorderableColumn(", 6),
        )
        assertEquals(
            "the drop no longer goes to AccountStore.moveFolderTo, the one write that reads the " +
                "order under the store's lock, or no longer hands it the index it was given.",
            listOf(
                "fun moveFolderTo(id: String, siblings: List<String>, folderId: String, toIndex: Int) {",
                "store.moveFolderTo(id, siblings, folderId, toIndex)",
            ),
            block(ACCOUNTS_VIEW_MODEL, "fun moveFolderTo(", 2),
        )
        assertEquals(
            "the drag handle no longer carries the modifier the column hands it, or no longer says " +
                "what it is to a screen reader: without the modifier a long press starts nothing.",
            listOf(
                "Icons.Filled.DragHandle,",
                "contentDescription = stringResource(R.string.folders_reorder_drag),",
                "modifier = handle.size(48.dp).padding(12.dp),",
            ),
            block(SCREEN, "Icons.Filled.DragHandle,", 3),
        )
    }

    // ── instrument (copied from SubscribedFoldersSurfaceWiringTest) ──────────────────────────────

    /** [count] consecutive code lines from the one line starting with [prefix]. */
    private fun block(file: File, prefix: String, count: Int): List<String> {
        val lines = codeLines(file)
        val hits = lines.indices.filter { lines[it].startsWith(prefix) }
        val at = hits.singleOrNull()
            ?: error(
                "${hits.size} code lines start with `$prefix` in ${file.name} — this lint reads the " +
                    "shipped source and must be taught the new shape rather than left green over " +
                    "something it never read",
            )
        return lines.subList(at, minOf(at + count, lines.size))
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule is satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        private const val STERNA_APP_PATH = "app/src/main/kotlin/app/sterna/ui/SternaApp.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, STERNA_APP_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val STERNA_APP: File by lazy { File(root, STERNA_APP_PATH) }
        val INBOX_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        }
        val ACCOUNTS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/AccountsViewModel.kt")
        }
        val SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/FolderManagementScreen.kt")
        }
    }
}

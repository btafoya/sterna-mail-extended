package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: where the Scheduled, Snoozed and Outbox lists are opened from,
 * which count each entry is drawn on, and what those counts are read from. Which entries are drawn
 * and in what order is run by `DrawerListsToShowTest`, the labels by `DrawerCountLabelTest`; this
 * pins that the drawer asks them, with which count. Holes (CONTRIBUTING.md, Tests):
 *  1. `viewModel.scheduledCount` and `viewModel.snoozedCount` are names: the ViewModel rule below
 *     pins what each is bound to, one layer down, and `MailSender.scheduledSendsFlow` and
 *     `MailRepository.snoozedFlow` the layer under that. `outboxQueuedCount` is pinned by
 *     `OutboxCountWiringTest`.
 *  3. The block is pinned whole from the Unread entry to the folder tree, so each entry sitting in
 *     its own branch of the loop over `drawerListsToShow`, and no entry drawn outside it, are held.
 *  4. Nothing here holds the value the composable collects, nor proves the drawer is drawn.
 *  5. The Snoozed rule pins that the count goes through `pendingSnoozes`, the filter the Snoozed
 *     screen lists with, at the moment the rows are emitted. It does not prove the count drops
 *     when a snooze reaches its deadline: no clock re-runs it, only a change to the rows does.
 */
class DrawerListsWiringLintTest {

    @Test
    fun `the three lists sit under Unread, each drawn only on its own count, and close the drawer`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }.filter { it.isNotEmpty() }
        val at = lines.indexOf("viewModel.selectUnread()")
        check(at >= 0) { "InboxScreen.kt no longer calls viewModel.selectUnread() on its own line" }
        assertEquals(
            "Between the Unread entry and the folder tree the drawer must hold, whole: the three " +
                "lists drawn only through drawerListsToShow, each on its own count (scheduledCount, " +
                "snoozedCount, outboxQueuedCount), each entry in its own branch and labelled with " +
                "that same count, each closing the drawer. An entry drawn outside the loop is back " +
                "in an empty drawer; a count passed to the wrong list hides a snoozed message or " +
                "shows an empty list. Lines found were:\n" +
                lines.subList(at, minOf(at + EXPECTED_DRAWER_BLOCK.size, lines.size)).joinToString("\n"),
            EXPECTED_DRAWER_BLOCK,
            lines.subList(at, minOf(at + EXPECTED_DRAWER_BLOCK.size, lines.size)),
        )
    }

    @Test
    fun `each list is opened from the drawer alone, never from the overflow menu`() {
        val text = codeText(INBOX_SCREEN)
        val menuItems = Regex("""\bDropdownMenuItem\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .toList()
        val drawerItems = Regex("""\bNavigationDrawerItem\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .toList()
        OPENERS.forEach { opener ->
            assertEquals(
                "'$opener' must be called from exactly one place in InboxScreen.kt: the drawer. " +
                    "Left in the overflow menu as well, the list has two doors and the menu " +
                    "keeps the entries this change took out of it.",
                1,
                Regex("""\b${Regex.escape(opener)}""").findAll(text).count(),
            )
            assertEquals(
                "No overflow menu entry may call '$opener'. Entries calling it were:\n" +
                    menuItems.filter { opener in it }.joinToString("\n"),
                emptyList<String>(),
                menuItems.filter { opener in it },
            )
            assertEquals(
                "Exactly one drawer entry must call '$opener'.",
                1,
                drawerItems.count { opener in it },
            )
        }
    }

    /** The overflow test above filters the menus it finds; this one says the drawer has none, so a
     *  menu reappearing on a folder row cannot hide behind a filter that only looks for list openers. */
    @Test
    fun `the drawer draws no menu at all`() {
        val text = codeText(INBOX_SCREEN)
        val start = text.indexOf("private fun DrawerContent(")
        val end = text.indexOf("fun countedDrawerLabel(", start)
        check(start >= 0 && end > start) {
            "InboxScreen.kt no longer declares DrawerContent followed by countedDrawerLabel; teach this lint the new shape"
        }
        assertEquals(
            "DrawerContent opens a DropdownMenu again. Its folder menu (New subfolder, Rename, " +
                "Delete) moved to the folder screen with #302, where the rows it may appear on are " +
                "decided by folderManagementRows; drawn here it is a second door, guarded by a rule " +
                "of its own. Menus found in the drawer:\n" +
                Regex("""\bDropdownMenu(Item)?\s*\(""").findAll(text.substring(start, end))
                    .map { callAt(text, start + it.range.first) }.joinToString("\n"),
            0,
            Regex("""\bDropdownMenu(Item)?\s*\(""").findAll(text.substring(start, end)).count(),
        )
        val leftovers = Regex("""\bbadge\s*=|\bMoreVert\b""").findAll(text.substring(start, end))
            .map { it.value }.toList()
        assertEquals(
            "DrawerContent draws a folder row badge or a MoreVert icon again: the button that " +
                "opened the folder menu, which #302 moved to the folder screen. Found: $leftovers",
            emptyList<String>(),
            leftovers,
        )
    }

    @Test
    fun `the Scheduled and Snoozed counts are counted off the observed queries`() {
        val vm = codeLines(INBOX_VIEW_MODEL).map { it.trim() }.filter { it.isNotEmpty() }
        assertDeclaration(
            vm,
            "val scheduledCount",
            EXPECTED_VM_SCHEDULED,
            "InboxViewModel.scheduledCount must count the rows of repo.sending.scheduledSendsFlow(), " +
                "the observed query. A one-shot read (scheduledSends(), a flow { emit(...) }) " +
                "compiles and shows the right number once, then keeps '1' in the drawer after the " +
                "message has gone out.",
        )
        assertDeclaration(
            vm,
            "val snoozedCount",
            EXPECTED_VM_SNOOZED,
            "InboxViewModel.snoozedCount must count, off repo.snoozedFlow(), what the Snoozed screen " +
                "lists: the rows pendingSnoozes keeps at emission time. A bare it.size also counts a " +
                "snooze past its deadline that the wake-up has not removed yet, and the drawer then " +
                "offers 'Snoozed (1)' onto an empty screen. This rule does not prove the count drops " +
                "at the deadline itself.",
        )
        val sender = codeLines(MAIL_SENDER).map { it.trim() }
        assertEquals(
            "MailSender.scheduledSendsFlow must stay the DAO's observed query, whole: it is the " +
                "layer under scheduledCount, and all() behind this name freezes the count the same way.",
            listOf(EXPECTED_SENDER_FLOW),
            sender.filter { it.startsWith("fun scheduledSendsFlow(") },
        )
        val repository = codeLines(MAIL_REPOSITORY).map { it.trim() }
        assertEquals(
            "MailRepository.snoozedFlow must stay the DAO's observed query, whole: it is the " +
                "layer under snoozedCount, and a one-shot read behind this name freezes the count.",
            listOf(EXPECTED_SNOOZED_FLOW),
            repository.filter { it.startsWith("fun snoozedFlow(") },
        )
    }

    private fun assertDeclaration(vm: List<String>, declaration: String, expected: List<String>, why: String) {
        val at = vm.indexOfFirst { it.startsWith(declaration) }
        check(at >= 0) { "InboxViewModel.kt declares no '$declaration'" }
        val found = vm.subList(at, minOf(at + expected.size, vm.size))
        assertEquals("$why Lines found were:\n" + found.joinToString("\n"), expected, found)
    }

    /** The whole call starting at [from], up to the `)` that balances its first `(`. */
    private fun callAt(text: String, from: Int): String {
        val open = text.indexOf('(', from)
        check(open >= 0) { "no argument list opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
    }

    private fun codeText(file: File): String = codeLines(file).joinToString(" ")
        .replace(Regex("""\s+"""), " ").trim()

    /** Same scanner as `FilterEntryWiringLintTest`'s: a `//` inside a string literal is not a comment. */
    private fun codeLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlockComment = false
        for (raw in file.readLines()) {
            val code = StringBuilder()
            var inString = false
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                when {
                    inBlockComment -> if (c == '*' && raw.getOrNull(i + 1) == '/') {
                        inBlockComment = false
                        i++
                    }
                    inString -> {
                        code.append(c)
                        when {
                            c == '\\' -> raw.getOrNull(i + 1)?.let { code.append(it); i++ }
                            c == '"' -> inString = false
                        }
                    }
                    c == '"' -> {
                        code.append(c)
                        inString = true
                    }
                    c == '/' && raw.getOrNull(i + 1) == '*' -> {
                        inBlockComment = true
                        i++
                    }
                    c == '/' && raw.getOrNull(i + 1) == '/' -> i = raw.length
                    else -> code.append(c)
                }
                i++
            }
            out += code.toString()
        }
        return out
    }

    companion object {
        private val OPENERS = listOf("onOpenScheduled()", "onOpenSnoozed()", "onOpenOutbox()")

        private val EXPECTED_DRAWER_BLOCK = listOf(
            "viewModel.selectUnread()",
            "scope.launch { drawerState.close() }",
            "},",
            "modifier = Modifier.padding(horizontal = 12.dp),",
            ")",
            "val scheduledCount by viewModel.scheduledCount.collectAsStateWithLifecycle()",
            "val snoozedCount by viewModel.snoozedCount.collectAsStateWithLifecycle()",
            "val outboxQueuedCount by viewModel.outboxQueuedCount.collectAsStateWithLifecycle()",
            "drawerListsToShow(",
            "scheduled = scheduledCount,",
            "snoozed = snoozedCount,",
            "outbox = outboxQueuedCount,",
            ").forEach { list ->",
            "when (list) {",
            "DrawerList.SCHEDULED -> NavigationDrawerItem(",
            "icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },",
            "label = { Text(countedDrawerLabel(R.string.inbox_scheduled, scheduledCount)) },",
            "selected = false,",
            "onClick = {",
            "onOpenScheduled()",
            "scope.launch { drawerState.close() }",
            "},",
            "modifier = Modifier.padding(horizontal = 12.dp),",
            ")",
            "DrawerList.SNOOZED -> NavigationDrawerItem(",
            "icon = { Icon(Icons.Filled.Snooze, contentDescription = null) },",
            "label = { Text(countedDrawerLabel(R.string.inbox_snoozed, snoozedCount)) },",
            "selected = false,",
            "onClick = {",
            "onOpenSnoozed()",
            "scope.launch { drawerState.close() }",
            "},",
            "modifier = Modifier.padding(horizontal = 12.dp),",
            ")",
            "DrawerList.OUTBOX -> NavigationDrawerItem(",
            "icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },",
            "label = { Text(countedDrawerLabel(R.string.inbox_outbox, outboxQueuedCount)) },",
            "selected = false,",
            "onClick = {",
            "onOpenOutbox()",
            "scope.launch { drawerState.close() }",
            "},",
            "modifier = Modifier.padding(horizontal = 12.dp),",
            ")",
            "}",
            "}",
            "val collapsedIds = collapsedFolderIds(ui.visibleMailboxes, collapsedFolders, folderRowsBadgeUnread)",
        )

        private val EXPECTED_VM_SCHEDULED = listOf(
            "val scheduledCount: StateFlow<Int> = repo.sending.scheduledSendsFlow()",
            ".map { it.size }",
            ".stateIn(",
        )

        private val EXPECTED_VM_SNOOZED = listOf(
            "val snoozedCount: StateFlow<Int> = repo.snoozedFlow()",
            ".map { pendingSnoozes(it, System.currentTimeMillis()).size }",
            ".stateIn(",
        )

        private const val EXPECTED_SNOOZED_FLOW =
            "fun snoozedFlow(): Flow<List<SnoozedListRow>> = snoozedDao.observeAll()"

        private const val EXPECTED_SENDER_FLOW =
            "fun scheduledSendsFlow(): Flow<List<ScheduledSendEntity>> = scheduledSendDao.observeAll()"

        private const val INBOX_SCREEN_PATH =
            "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt"

        private val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, INBOX_SCREEN_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        private val INBOX_SCREEN: File by lazy { File(root, INBOX_SCREEN_PATH) }
        private val INBOX_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        }
        private val MAIL_REPOSITORY: File by lazy {
            File(root, "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        }
        private val MAIL_SENDER: File by lazy {
            File(root, "core/data/src/main/kotlin/app/sterna/core/data/mail/MailSender.kt")
        }
    }
}

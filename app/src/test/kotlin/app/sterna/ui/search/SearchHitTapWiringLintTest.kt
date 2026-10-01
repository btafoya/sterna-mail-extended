package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test (#216, #234). It reads `SearchScreen.kt`, `SearchViewModel.kt`
 * and `SternaApp.kt` as text, comments stripped, and pins that the advanced search's result tap
 * asks the list's decision with the screen's selection state, that its draft exit reaches the
 * composer, and that its selection runs on the inbox's gestures with its own "select all".
 *
 * What it does not cover: the decisions themselves, which `RowTapActionTest` and
 * `CriteriaSelectionTest` execute; whether a search hit arrives with its `mailboxId` filled, which
 * only a device run shows; whether the composer actually loads the draft it is handed; and what
 * the selection bar draws, which only a device run shows.
 */
class SearchHitTapWiringLintTest {

    @Test
    fun `a result row's tap edits a hit filed in Drafts and opens anything else in the reader`() {
        val lines = codeLines(SEARCH_SCREEN).map { it.trim() }
        val row = lines.indexOfFirst { it.startsWith("EmailListItem(") }
        assertTrue("SearchScreen no longer draws its results with EmailListItem(", row >= 0)
        val onClick = lines.drop(row).firstOrNull { it.startsWith("onClick") }
        assertEquals(
            "The result row's onClick must open a block that asks the list search's decision, not " +
                "call the reader outright: a hit filed in Drafts then opens in a reader that cannot " +
                "edit it. onClick line found was:",
            "onClick = {",
            onClick,
        )
        assertEquals(
            "The tap must be decided by criteriaHitTap with the screen's own selection state and the " +
                "hit's OWN folder, with the account-qualified role map: in selection it ticks, outside " +
                "it EDIT_DRAFT goes through onEditDraft. Compared whole and in order. Body of the " +
                "row's onClick was:\n" +
                block(SEARCH_SCREEN, "onClick = {", after = "EmailListItem(").joinToString("\n"),
            listOf(
                "when (criteriaHitTap(isLocalDraftRow(email.id), selecting, messageFolderRole(email.accountId, email.mailboxId, folderRoles))) {",
                "RowTap.SELECT_ROW, RowTap.SELECT_THREAD -> inbox.toggleSelect(email)",
                "RowTap.EDIT_DRAFT -> onEditDraft(email.id, email.accountId)",
                "RowTap.OPEN -> onOpenEmail(email.id, email.accountId)",
                "}",
            ),
            block(SEARCH_SCREEN, "onClick = {", after = "EmailListItem("),
        )
        assertEquals(
            "SearchScreen must read the role map from its ViewModel, once:",
            listOf("val folderRoles by viewModel.folderRoles.collectAsStateWithLifecycle()"),
            lines.filter { "folderRoles" in it && it.startsWith("val ") },
        )
    }

    @Test
    fun `the Drafts rule is written once, in the shared decision`() {
        for (file in listOf(SEARCH_SCREEN, SEARCH_VIEW_MODEL)) {
            assertEquals(
                "${file.name} must not spell the Drafts role itself: a second copy of the rule " +
                    "drifts from searchHitTapAction. Lines found:",
                emptyList<String>(),
                codeLines(file).map { it.trim() }.filter { "\"drafts\"" in it.lowercase() },
            )
        }
    }

    @Test
    fun `the search ViewModel shares the roles of every account like the inbox does`() {
        assertEquals(
            "SearchViewModel.folderRoles must be scoped off accountsFlow, since the search spans " +
                "every account, and shared with WhileSubscribed(5_000) and no " +
                "replayExpirationMillis, like InboxViewModel.folderRoles. Compared whole. Lines were:\n" +
                member(SEARCH_VIEW_MODEL, "folderRoles").joinToString("\n"),
            listOf(
                "val folderRoles: StateFlow<Map<Pair<String, String>, String>> =",
                "folderRolesFlow(store.accountsFlow) { accountIds -> repo.observeFolderRoles(accountIds) }",
                ".stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())",
            ),
            member(SEARCH_VIEW_MODEL, "folderRoles"),
        )
    }

    @Test
    fun `the search route hands the draft exit to the composer and the other to the reader`() {
        assertEquals(
            "SternaApp must hand SearchScreen these three exits, each under entry.navigateOnce: " +
                "onEditDraft to compose?draftId= with the hit's account, onOpenEmail to the " +
                "reader. An onEditDraft pointed at the reader, or left empty, is #216 again. " +
                "Compared whole and in order. Arguments found were:\n" +
                callArguments(STERNA_APP, "SearchScreen(").joinToString("\n"),
            listOf(
                "onBack = { entry.navigateOnce { nav.popBackStack() } },",
                "onOpenEmail = { id, accountId ->",
                "entry.navigateOnce {",
                "inboxViewModel.startFreshReading()",
                "nav.navigate(\"message/\${Uri.encode(id)}?accountId=\${Uri.encode(accountId.orEmpty())}\")",
                "}",
                "},",
                "onEditDraft = { id, accountId ->",
                "entry.navigateOnce {",
                "nav.navigate(\"compose?draftId=\${Uri.encode(id)}&accountId=\${Uri.encode(accountId.orEmpty())}\")",
                "}",
                "},",
                "inbox = inboxViewModel,",
            ),
            callArguments(STERNA_APP, "SearchScreen("),
        )
    }

    @Test
    fun `the result selection runs on the inbox's gestures, and select all takes this screen's rows`() {
        val lines = codeLines(SEARCH_SCREEN).map { it.trim() }
        assertEquals(
            "The screen must read whether it is selecting from the inbox's selection as THIS screen " +
                "draws it (shownSelectionActive), once: selectionActive is the inbox's own, and read " +
                "here it would show the list's selection over these rows. Lines found:",
            listOf("val selecting by inbox.shownSelectionActive.collectAsStateWithLifecycle()"),
            lines.filter { it.startsWith("val selecting") },
        )
        assertEquals(
            "The screen must join the inbox's selection sources with its own results. Lines found:",
            listOf("LaunchedEffect(viewModel, inbox) { inbox.attachShownResults(viewModel.shown) }"),
            lines.filter { "attachShownResults" in it },
        )
        assertEquals(
            "A long press on a result must enter the selection with this screen as its origin. " +
                "Lines found:",
            listOf("onLongClick = { inbox.enterShownSelection(email) },"),
            lines.filter { it.startsWith("onLongClick") },
        )
        assertEquals(
            "Back must leave the selection before the screen. Lines found:",
            listOf("BackHandler(enabled = selecting) { inbox.clearSelection() }"),
            lines.filter { it.startsWith("BackHandler(") },
        )
        assertEquals(
            "The selection bar is the list's own composable, fed this screen's state, and its " +
                "select all is selectAllShown: the drawn rows, never the folder behind the inbox. " +
                "Compared whole and in order. Arguments were:\n" +
                callArguments(SEARCH_SCREEN, "SelectionTopBar(").joinToString("\n"),
            listOf(
                "viewModel = inbox,",
                "count = selectedKeys.size,",
                "selectionAllRead = selectionAllRead,",
                "currentRole = null,",
                "inboxId = null,",
                "trash = false,",
                "onMove = { showMoveSheet = true },",
                "onSelectAll = inbox::selectAllShown,",
            ),
            callArguments(SEARCH_SCREEN, "SelectionTopBar("),
        )
        assertEquals(
            "Nothing on this screen may reach the list's own select all, which reads the folder. " +
                "Lines found:",
            emptyList<String>(),
            lines.filter { Regex("""\bselectAll\b""").containsMatchIn(it) },
        )
    }

    // -- reading the sources --------------------------------------------------------------------

    /** The trimmed lines between the brace opened by the first line equal to [opener] (trimmed),
     *  searched from the first line containing [after], and the brace that closes it. */
    private fun block(file: File, opener: String, after: String): List<String> {
        val lines = codeLines(file)
        val from = lines.indexOfFirst { after in it }
        check(from >= 0) { "${file.name} has no line containing '$after'" }
        val start = (from until lines.size).firstOrNull { lines[it].trim() == opener }
            ?: return emptyList()
        return balanced(lines, start, '{', '}')
    }

    /** The trimmed argument lines of the first call whose line contains [call], closing line excluded. */
    private fun callArguments(file: File, call: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { call in it && !it.trim().startsWith("import ") }
        check(start >= 0) { "${file.name} has no line containing '$call'" }
        return balanced(lines, start, '(', ')')
    }

    private fun balanced(lines: List<String>, start: Int, open: Char, close: Char): List<String> {
        var depth = 0
        val out = mutableListOf<String>()
        for (i in start until lines.size) {
            val next = depth + lines[i].count { it == open } - lines[i].count { it == close }
            if (i > start) {
                if (next <= 0) return out
                out += lines[i].trim()
            }
            depth = next
        }
        return out
    }

    /** The member `val` [name] in [file]: its line, then every line indented deeper or opening
     *  with an operator, trimmed. */
    private fun member(file: File, name: String): List<String> {
        val lines = codeLines(file)
        val start = lines.indexOfFirst { Regex("""\bval\s+$name\b""").containsMatchIn(it) }
        if (start < 0) return emptyList()
        val indent = lines[start].indexOfFirst { !it.isWhitespace() }
        val out = mutableListOf(lines[start].trim())
        for (line in lines.drop(start + 1)) {
            val here = line.indexOfFirst { !it.isWhitespace() }
            if (here <= indent && !CONTINUATION.containsMatchIn(line)) break
            out += line.trim()
        }
        return out
    }

    /** [file]'s non-blank lines, indentation kept, comments taken out; a `//` inside a
     *  double-quoted string is code, so a URL in a route is not cut. */
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
            if (code.isNotBlank()) out += code.toString().trimEnd()
        }
        return out
    }

    private companion object {
        val CONTINUATION = Regex("""^\s*(\.|\?:|\?\.|\+|&&|\|\||,|\))""")

        val SEARCH_SCREEN: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/search/SearchScreen.kt") }
        val SEARCH_VIEW_MODEL: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/search/SearchViewModel.kt") }
        val STERNA_APP: File by lazy { repoFile("app/src/main/kotlin/app/sterna/ui/SternaApp.kt") }

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}

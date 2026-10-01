package app.sterna.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint for the search history's wiring: when a search is kept, when it is not, and who
 * clears it. It reads the sources as text and proves nothing about what is drawn. The decisions
 * themselves (what a search does to the list, what is offered, the switch read at every write) are
 * executed in `:core:data`, by `SearchHistoryTest` and `SearchHistoryRepositoryTest`; what is left
 * here is which gesture calls them, and those gestures live in view models and composables that no
 * JVM test of this module can construct.
 */
class SearchHistoryWiringTest {

    // -- the inbox's search bar ------------------------------------------------------------------

    @Test fun `typing in the bar keeps nothing`() {
        for (signature in listOf("fun setSearchQuery(query: String) {", "fun typeSearchQuery(query: String) {")) {
            val body = functionBody(INBOX_VM, signature)
            val offenders = body.filter { "recordSearch" in it || "searchHistory" in it }
            assertEquals(
                "`$signature` runs on every keystroke, blank included: a search kept from here fills " +
                    "the history with \"e\", \"ec\", \"eco\". Found:\n" + offenders.joinToString("\n"),
                emptyList<String>(),
                offenders,
            )
        }
    }

    @Test fun `a keystroke in the bar marks the text as being typed, and only a keystroke does`() {
        assertEquals(
            listOf(
                "fun typeSearchQuery(query: String) {",
                "setSearchQuery(query)",
                "searchState.value = searchState.value.copy(typing = true)",
                "}",
            ),
            functionBody(INBOX_VM, "fun typeSearchQuery(query: String) {"),
        )
        assertEquals(
            "the field's keystrokes must go through typeSearchQuery; the clear button and a picked " +
                "suggestion are not typing",
            listOf("onValueChange = viewModel::typeSearchQuery,"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("onValueChange = viewModel::") && "SearchQuery" in it },
        )
        assertEquals(
            listOf("searchState.value = searchState.value.copy(typing = true)"),
            codeLines(INBOX_VM).filter { "typing = true" in it },
        )
    }

    @Test fun `the bar's suggestions follow the shared decision, over the selection too`() {
        val code = codeLines(INBOX_VM)
        val at = code.indexOf("val searchSuggestions: StateFlow<List<String>> =")
        assertTrue("InboxViewModel.searchSuggestions is gone or renamed", at >= 0)
        assertEquals(
            listOf(
                "val searchSuggestions: StateFlow<List<String>> =",
                "combine(searchHistory.offered, searchState, selectionActive) { kept, search, selecting ->",
                "barSuggestions(kept, search, selecting)",
                "}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())",
            ),
            code.subList(at, minOf(at + 4, code.size)),
        )
    }

    @Test fun `the bar keeps the text on screen at the moment of the gesture`() {
        assertEquals(
            listOf(
                "fun recordSearch() {",
                "val typed = searchState.value.query",
                "searchState.value = searchState.value.copy(typing = false)",
                "viewModelScope.launch { searchHistory.record(typed) }",
                "}",
            ),
            functionBody(INBOX_VM, "fun recordSearch() {"),
        )
    }

    @Test fun `the keyboard's Search key keeps the search and still folds the keyboard`() {
        assertEquals(
            listOf("keyboardActions = KeyboardActions(onSearch = { viewModel.recordSearch(); focusManager.clearFocus() }),"),
            codeLines(INBOX_SCREEN).filter { it.startsWith("keyboardActions = KeyboardActions(onSearch") },
        )
    }

    @Test fun `opening a result keeps the search, and so does opening a draft found by it`() {
        val code = codeLines(INBOX_SCREEN)
        for ((branch, gesture) in listOf(
            "RowTap.EDIT_DRAFT -> {" to "onEditDraft(email.id, email.accountId)",
            "RowTap.OPEN -> {" to "viewModel.onEmailOpened(email.id)",
        )) {
            val at = code.indexOf(branch)
            assertEquals(
                "the row's `$branch` branch must keep the search, guarded by fromSearch, before it leaves the list",
                listOf(branch, "if (fromSearch) viewModel.recordSearch()", gesture),
                if (at >= 0) code.subList(at, minOf(at + 3, code.size)) else emptyList(),
            )
        }
        assertEquals(2, code.count { "recordSearch" in it && "fromSearch" in it })
    }

    @Test fun `choosing a suggestion runs it through the live search, then keeps it`() {
        assertEquals(
            "a pick replaces the list: a selection kept across it would act on rows no longer shown",
            listOf(
                "fun searchFromHistory(text: String) {",
                "clearSelection()",
                "setSearchQuery(text)",
                "recordSearch()",
                "}",
            ),
            functionBody(INBOX_VM, "fun searchFromHistory(text: String) {"),
        )
    }

    @Test fun `the bar calls recordSearch from nowhere else`() {
        val calls = codeLines(INBOX_SCREEN).filter { "recordSearch" in it }
        assertEquals(
            "closing the bar keeps nothing: a search abandoned half-typed is not a search",
            listOf(
                "keyboardActions = KeyboardActions(onSearch = { viewModel.recordSearch(); focusManager.clearFocus() }),",
                "if (fromSearch) viewModel.recordSearch()",
                "if (fromSearch) viewModel.recordSearch()",
            ),
            calls,
        )
        val inVm = codeLines(INBOX_VM).filter { "recordSearch()" in it && !it.startsWith("fun ") }
        assertEquals(listOf("recordSearch()"), inVm)
    }

    // -- what never keeps a search -----------------------------------------------------------------

    @Test fun `a search replayed after process death, or opened from a sender, is not kept`() {
        assertEquals(
            "recordSearch is the only place the bar keeps a search: the restore in `init` and " +
                "openSearchFrom run criteria the user did not search again",
            1,
            codeLines(INBOX_VM).count { "searchHistory.record" in it },
        )
        assertEquals(
            listOf(
                "fun openSearchFrom(address: String) {",
                "clearSelection()",
                "setSearchActive(true)",
                "barSearch.ask(SearchQuery(from = address), AddressNames())",
                "}",
            ),
            functionBody(INBOX_VM, "fun openSearchFrom(address: String) {"),
        )
    }

    // -- removing an account ---------------------------------------------------------------------

    @Test fun `signing out clears the history on the app's scope, after the account has left the store`() {
        val code = functionBody(ACCOUNTS_VM, "fun signOut(id: String) {")
        val removal = code.indexOf("val removed = store.removeCascading(id).ifEmpty { toRemove }")
        val clear = code.indexOf("appScope.launch { runCatching { searchHistory.clear() } }")
        assertTrue("signOut no longer removes the account as expected:\n" + code.joinToString("\n"), removal >= 0)
        assertTrue(
            "signOut must clear the search history on appScope, never viewModelScope: signing out " +
                "the last account can destroy this ViewModel and cancel the write. And only after " +
                "`store.removeCascading(id)`, which is what stops a sync in flight (#121):\n" +
                code.joinToString("\n"),
            clear > removal,
        )
        assertEquals(1, code.count { "searchHistory" in it })
    }

    /**
     * Every call that removes an account, and what it does about the history. The two undoable
     * dismissals of an imported account that was never signed in leave it: such an account has
     * never had mail on this phone, so no search can be about it, and its Undo cannot give a
     * cleared history back.
     */
    @Test fun `every path that removes an account is accounted for`() {
        val found = (sourceFiles("app/src/main/kotlin") + sourceFiles("core/data/src/main/kotlin"))
            .flatMap { file ->
                codeLines(file).filter { REMOVAL.containsMatchIn(it) }.map { "${file.name}: $it" }
            }
        assertEquals(
            "a new call removing an account: clear the search history there, or record here why not",
            listOf(
                "AccountsViewModel.kt: store.remove(id)" to "dismissImport, never signed in, undoable",
                "AccountsViewModel.kt: val removed = store.removeCascading(id).ifEmpty { toRemove }" to "signOut, clears",
                "ConnectViewModel.kt: container.accountStore.remove(id)" to "dismissImportAccount, never signed in, undoable",
            ).map { it.first },
            found.sorted(),
        )
        assertEquals(
            listOf("store.remove(id)"),
            functionBody(ACCOUNTS_VM, "fun dismissImport(id: String) {").filter { REMOVAL.containsMatchIn(it) },
        )
    }

    // -- the store, the switch and the purge ------------------------------------------------------

    @Test fun `the app builds one history, bound to the switch, and hands it to the data layer`() {
        val code = codeLines(APP)
        assertEquals(
            listOf(
                "val searchHistoryRepository: SearchHistoryRepository =",
                "SearchHistoryRepository(context.applicationContext, settingsRepository.searchHistory)",
            ),
            code.indexOfFirst { it.startsWith("val searchHistoryRepository") }.let { code.subList(it, it + 2) },
        )
        assertEquals(listOf("searchHistory = searchHistoryRepository,"), code.filter { it.startsWith("searchHistory =") })
    }

    @Test fun `the settings export never reads the history`() {
        val body = functionBody(SETTINGS_VM, "fun exportSettings(uri: Uri, onResult: (Boolean) -> Unit) {")
        assertEquals(emptyList<String>(), body.filter { "searchHistory" in it || "SearchHistory" in it })
    }

    @Test fun `the switch starts from the shared default`() {
        assertEquals(
            listOf("initialValue = SEARCH_HISTORY_DEFAULT,"),
            codeLines(SETTINGS_VM).filter { "SEARCH_HISTORY_DEFAULT" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the privacy screen carries the switch and a confirmed clear`() {
        val code = functionBody(SETTINGS_SCREEN, "private fun PrivacySecurityScreen(")
        val switch = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_search_history_title),",
            "subtitle = stringResource(R.string.settings_search_history_subtitle),",
            "checked = searchHistory,",
            "onCheckedChange = viewModel::setSearchHistory,",
            ")",
        )
        val at = code.indexOf(switch[1]) - 1
        assertEquals(switch, if (at >= 0) code.subList(at, minOf(at + switch.size, code.size)) else code)
        assertTrue(
            "the clear button is greyed only when there is nothing to clear, never by the switch",
            "enabled = !searchHistoryEmpty," in code,
        )
        val confirm = code.indexOf("viewModel.clearSearchHistory()")
        assertEquals(
            "the clear must be the confirm of the shared dialog, not a direct tap",
            listOf("ClearSearchHistoryDialog(", "onConfirm = {", "confirmClearHistory = false", "viewModel.clearSearchHistory()"),
            if (confirm >= 3) code.subList(confirm - 3, confirm + 1) else code,
        )
        assertEquals(1, code.count { "viewModel.clearSearchHistory()" in it })
    }

    @Test fun `the one clear dialog asks with its title and confirms with its confirm button`() {
        val body = functionBody(ROWS, "fun ClearSearchHistoryDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {")
        assertEquals(
            listOf(
                "AlertDialog(",
                "onDismissRequest = onDismiss,",
                "title = { Text(stringResource(R.string.settings_search_history_clear_dialog_title)) },",
                "text = { Text(stringResource(R.string.settings_search_history_clear_dialog_body)) },",
                "confirmButton = {",
                "TextButton(onClick = onConfirm) {",
            ),
            body.subList(1, minOf(7, body.size)),
        )
        assertEquals(
            "a second copy of the dialog would drift from this one",
            listOf("SearchHistoryRows.kt"),
            sourceFiles("app/src/main/kotlin")
                .filter { f -> codeLines(f).any { "settings_search_history_clear_dialog_title" in it } }
                .map { it.name },
        )
    }

    // -- the bar's recent searches: header, cross, and a tap that brings them back ---------------

    @Test fun `the bar's Clear goes through the shared dialog, and nothing else clears`() {
        val rows = functionBody(ROWS, "fun SearchHistoryRows(")
        assertEquals(
            listOf("ClearSearchHistoryDialog(", "onConfirm = {", "confirmClear = false", "onClear()"),
            rows.indexOf("onClear()").let { if (it >= 3) rows.subList(it - 3, it + 1) else rows },
        )
        assertEquals(1, rows.count { "onClear" in it && "onClear: () -> Unit" !in it })
        assertEquals(
            listOf("fun clearSearchHistory() {", "viewModelScope.launch { searchHistory.clear() }", "}"),
            functionBody(INBOX_VM, "fun clearSearchHistory() {"),
        )
        assertEquals(
            listOf("viewModelScope.launch { searchHistory.remove(text) }"),
            codeLines(INBOX_VM).filter { "searchHistory.remove" in it },
        )
    }

    @Test fun `both places the bar shows its recent searches can remove one and clear them`() {
        val code = codeLines(INBOX_SCREEN)
        assertEquals(2, code.count { it.startsWith("SearchHistoryRows(") })
        assertEquals(2, code.count { it == "onRemove = viewModel::removeFromSearchHistory," })
        assertEquals(2, code.count { it == "onClear = viewModel::clearSearchHistory," })
    }

    @Test fun `a tap on the field brings the suggestions back, a focus request does not`() {
        val code = codeLines(INBOX_SCREEN)
        assertEquals(
            "only a press on the field may reopen them: the bar requests focus by itself on every " +
                "return from a message",
            listOf("fieldInteractions.interactions.collect { if (it is PressInteraction.Release) viewModel.tapSearchField() }"),
            code.filter { "tapSearchField" in it },
        )
        assertEquals(listOf("interactionSource = fieldInteractions,"), code.filter { "= fieldInteractions" in it })
        assertEquals(
            listOf("fun tapSearchField() {", "searchState.value = afterFieldTap(searchState.value)", "}"),
            functionBody(INBOX_VM, "fun tapSearchField() {"),
        )
    }

    // -- reading the files -----------------------------------------------------------------------

    /** The function opening with [signature], by brace depth, trimmed code lines only. */
    private fun functionBody(file: File, signature: String): List<String> {
        val code = codeLines(file)
        val start = code.indexOfFirst { it.startsWith(signature) }
        assertTrue("`$signature` not found in ${file.name}", start >= 0)
        var depth = 0
        var opened = false
        val out = mutableListOf<String>()
        for (i in start until code.size) {
            val line = code[i]
            depth += line.count { it == '{' } - line.count { it == '}' }
            if ('{' in line) opened = true
            out += line
            if (opened && depth <= 0) break
        }
        return out
    }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private fun sourceFiles(dir: String): List<File> =
        File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private companion object {
        val REMOVAL = Regex("""\b(store|accountStore)\.remove(Cascading)?\(""")

        const val UI = "app/src/main/kotlin/app/sterna/ui"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, UI).isDirectory }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val INBOX_VM: File by lazy { File(root, "$UI/inbox/InboxViewModel.kt") }
        val INBOX_SCREEN: File by lazy { File(root, "$UI/inbox/InboxScreen.kt") }
        val ROWS: File by lazy { File(root, "$UI/search/SearchHistoryRows.kt") }
        val ACCOUNTS_VM: File by lazy { File(root, "$UI/settings/AccountsViewModel.kt") }
        val SETTINGS_VM: File by lazy { File(root, "$UI/settings/SettingsViewModel.kt") }
        val SETTINGS_SCREEN: File by lazy { File(root, "$UI/settings/SettingsScreen.kt") }
        val APP: File by lazy { File(root, "app/src/main/kotlin/app/sterna/SternaApplication.kt") }
    }
}

package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint for #277: the search bar's field, its hint and its Clear button read the field text,
 * which `SearchFieldTextTest` runs, and not `MailUi.searchQuery`, which reaches the screen through
 * the reply marks' database read and so arrives a frame or more after the keystroke.
 *
 * Holes of the lint, as `CONTRIBUTING.md` numbers them: 1 (the name `fieldText` says nothing about
 * what it is bound to beyond the one `collectAsStateWithLifecycle` line pinned here), 3 (a pinned
 * line says nothing about its place in the bar's control structure) and 4 (nothing here holds the
 * value the composable collects, nor proves that Compose hands it back to the field in time). The
 * bar is found between `} else if (ui.searching) {` and the `} else {` at its indent: a bar moved
 * out of that branch fails here instead of being skipped.
 */
class SearchFieldWiringTest {

    @Test fun `the screen collects the field text from the ViewModel`() {
        assertEquals(
            listOf("val fieldText by viewModel.searchFieldText.collectAsStateWithLifecycle()"),
            codeLines(INBOX_SCREEN).filter { "searchFieldText" in it },
        )
    }

    @Test fun `the field, its hint and its Clear button read the field text and nothing else`() {
        val bar = searchBar()
        assertEquals(
            "In the search bar, the hint, the TextField's value and the Clear button must read " +
                "fieldText. On ui.searchQuery the field gets its text back late and the cursor " +
                "falls behind (\"Sterna\" typed comes out \"Sernat\"). Bar was:\n${bar.joinToString("\n")}",
            listOf(
                "if (fieldText.isEmpty()) {",
                "value = fieldText,",
                "if (fieldText.isNotEmpty()) {",
            ),
            bar.filter { Regex("""\bfieldText\b""").containsMatchIn(it) },
        )
        assertEquals(
            "the search bar must not read ui.searchQuery. Lines were:",
            emptyList<String>(),
            bar.filter { "searchQuery" in it },
        )
        assertTrue(
            "the TextField fed by fieldText must still hand its keystrokes to typeSearchQuery",
            bar.indexOf("value = fieldText,") + 1 == bar.indexOf("onValueChange = viewModel::typeSearchQuery,"),
        )
    }

    @Test fun `the field text is read off the search state alone`() {
        assertEquals(
            listOf("val searchFieldText: StateFlow<String> = fieldTextOf(searchState, viewModelScope)"),
            codeLines(INBOX_VIEW_MODEL).filter { Regex("""\bsearchFieldText\b""").containsMatchIn(it) },
        )
        assertEquals(
            "fieldTextOf must not wait on anything: any operator, dispatcher or database read added " +
                "here lets the field get its text back late.",
            listOf(
                "internal fun fieldTextOf(state: StateFlow<SearchUi>, scope: CoroutineScope): StateFlow<String> =",
                "state.map { it.query }",
                ".stateIn(scope, SharingStarted.WhileSubscribed(5_000), state.value.query)",
            ),
            declaration(BAR_SEARCH, "internal fun fieldTextOf("),
        )
    }

    /** The lines of the search `TopAppBar` branch, trimmed, comments off. */
    private fun searchBar(): List<String> {
        val lines = INBOX_SCREEN.readLines()
        val starts = lines.indices.filter { lines[it].trim() == "} else if (ui.searching) {" }
        assertEquals("InboxScreen is expected to open exactly one '} else if (ui.searching) {' branch", 1, starts.size)
        val start = starts.single()
        val indent = lines[start].indentWidth()
        val end = (start + 1 until lines.size).first { lines[it].trim().startsWith("}") && lines[it].indentWidth() == indent }
        return codeLines(lines.subList(start + 1, end))
    }

    /** From the line starting with [head] to the next blank line. */
    private fun declaration(file: File, head: String): List<String> {
        val lines = file.readLines()
        val at = lines.indexOfFirst { it.startsWith(head) }
        check(at >= 0) { "${file.name} declares no '$head'" }
        val end = (at until lines.size).firstOrNull { lines[it].isBlank() } ?: lines.size
        return codeLines(lines.subList(at, end))
    }

    private fun String.indentWidth() = length - trimStart().length

    private fun codeLines(file: File): List<String> = codeLines(file.readLines())

    private fun codeLines(lines: List<String>): List<String> =
        lines.map { it.trim() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    private companion object {
        val INBOX_SCREEN = repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        val INBOX_VIEW_MODEL = repoFile("app/src/main/kotlin/app/sterna/ui/inbox/InboxViewModel.kt")
        val BAR_SEARCH = repoFile("app/src/main/kotlin/app/sterna/ui/inbox/BarSearch.kt")

        fun repoFile(path: String): File =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, path) }
                .firstOrNull { it.isFile }
                ?: error("cannot find $path from ${File("").absolutePath}")
    }
}

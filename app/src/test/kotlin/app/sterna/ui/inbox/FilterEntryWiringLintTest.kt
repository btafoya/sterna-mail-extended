package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. What the list screen does with the toggles: the chip row it
 * asks for and draws, the ViewModel's toggles, and the empty state's scene, sentence and "Clear
 * filters". The decisions are run by `FilterChipRowTest` and `EmptyListSceneTest`; this pins that
 * the screen asks them. Holes (CONTRIBUTING.md, Tests):
 *  1. `ui.filterToggles` and `selectionActive` are names: nothing here proves they hold the toggles
 *     the pager reads, or the selection `selectAll` fills.
 *  2. Whole calls are pinned, not what wraps them: a `?.let` dropped for a `!!` still reads the same
 *     call, and nothing here says the row is drawn only when the function answers.
 *  3. The row's place is pinned as source order between the banners and `PullToRefreshBox`, not as
 *     a position in the layout: a `Box` wrapped around it would put it over the list unseen.
 *  4. Nothing here holds the value the composable collects, nor proves a chip is drawn checked or
 *     disabled: `FilterChip` is trusted.
 *  5. That the overflow button carries no filter dot is `OutboxCountWiringTest`'s rule, which pins
 *     that button's content whole.
 */
class FilterEntryWiringLintTest {

    @Test
    fun `the chip row is asked once, with the view, the selection, the typed query and the toggles`() {
        val text = codeText(INBOX_SCREEN)
        val calls = Regex("""\bfilterChipRow\s*\(""").findAll(text).map { callAt(text, it.range.first) }.toList()
        assertEquals(
            "The row must be asked with the screen's own view flag (no Unread chip in the unread " +
                "view), the selection (chips inert while selectAll reads the filter) and the typed " +
                "query (search results are not filtered by the toggles). 'queryTyped = ui.searching' " +
                "compiles and hides the chips over the empty search field, whose list they still filter. " +
                "Calls found were:\n${calls.joinToString("\n")}",
            listOf(EXPECTED_ROW_QUESTION),
            calls,
        )
    }

    @Test
    fun `the chip row is drawn once, each chip flipping its own toggle`() {
        val text = codeText(INBOX_SCREEN)
        val calls = Regex("""(?<!fun )\bFilterChipRow\s*\(""").findAll(text).map { callAt(text, it.range.first) }.toList()
        assertEquals(
            "Each chip must show the state of the toggle it flips and flip only it: a crossed arm of " +
                "the when compiles, and a chip then lies about the list. 'enabled = true' compiles " +
                "and lets a chip change what a selection covers. Calls found were:" +
                "\n${calls.joinToString("\n")}",
            listOf(EXPECTED_ROW_DRAWN),
            calls,
        )
    }

    @Test
    fun `the chip row sits under the banners, above the pulled list, outside the bar`() {
        val text = codeText(INBOX_SCREEN)
        val body = text.indexOf("Column(Modifier.fillMaxSize().padding(padding))")
        val banner = text.indexOf("OutboxFailureBanner(onClick = onOpenOutbox)")
        val row = text.indexOf("FilterChipRow(")
        val pulled = text.indexOf("PullToRefreshBox(")
        assertEquals(
            "Source order must be: the body's Column, the outbox banner, the chip row, the " +
                "PullToRefreshBox. Inside the box the row would scroll away with the list; in the " +
                "bar it would enter inboxBarHeight. Offsets were: column $body, banner $banner, " +
                "row $row, box $pulled.",
            true,
            body in 0 until banner && banner < row && row < pulled,
        )
        assertEquals(
            "No funnel and no menu entry may survive beside the chips: a second control for the " +
                "same toggle is how starred-only ended up unreachable from the funnel.",
            emptyList<String>(),
            Regex("""\bFilterMenuItem\b|\bIcons\.Filled\.FilterList\b|\bIconToggleButton\b""").findAll(text).map { it.value }.toList(),
        )
    }

    @Test
    fun `each ViewModel toggle flips its own criterion`() {
        val lines = codeLines(INBOX_VIEW_MODEL).map { it.trim() }
        EXPECTED_TOGGLE_BODIES.forEach { (declaration, body) ->
            val at = lines.indexOf(declaration)
            check(at >= 0) { "InboxViewModel.kt no longer declares '$declaration'" }
            assertEquals(
                "'$declaration' must hold exactly this one statement: toggleFlaggedOnly and " +
                    "toggleWithAttachment swapped compile, and each chip then flips the other " +
                    "filter under its own tick.",
                listOf(body, "}"),
                lines.subList(at + 1, at + 3),
            )
        }
    }

    @Test
    fun `the empty state asks the scene with every toggle, names its filters, and clears them all`() {
        val text = codeText(INBOX_SCREEN)
        val scenes = Regex("""\bemptyListScene\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .toList()
        assertEquals(
            "The scene must be handed the toggles whole: handed the unread flag alone, a starred " +
                "filter empties the list under the words 'you have read everything'. Calls found were:" +
                "\n${scenes.joinToString("\n")}",
            listOf(EXPECTED_SCENE_CALL),
            scenes,
        )
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""\bscene\.(body|activeFilters)\b|\bmatched\b""").containsMatchIn(it) }
        assertEquals(
            "The sentence must name the scene's own filters with the chips' own words. Whole " +
                "lines, in source order. Lines found were:\n${lines.joinToString("\n")}",
            EXPECTED_BODY_LINES,
            lines,
        )
        val label = text.indexOf("stringResource(R.string.empty_unread_action)")
        check(label >= 0) { "InboxScreen.kt no longer draws empty_unread_action" }
        val button = text.lastIndexOf("Button(", label)
        assertEquals(
            "Clear filters must call clearListFilters(), which lifts the three toggles " +
                "(afterShowAll). toggleUnreadOnly() compiles there and, under starred alone, turns " +
                "unread ON: the list stays empty under a button called Clear filters.",
            EXPECTED_SHOW_ALL,
            callAt(text, button),
        )
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

    /** [file] as one line of code, comments out and whitespace runs collapsed. */
    private fun codeText(file: File): String = codeLines(file).joinToString(" ")
        .replace(Regex("""\s+"""), " ").trim()

    /** Same scanner as `UnreadViewWiringLintTest`'s: a `//` inside a string literal is not a comment. */
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
        private const val EXPECTED_ROW_QUESTION =
            "filterChipRow( unreadView = ui.unreadView, selectionActive = selectionActive, " +
                "queryTyped = ui.searching && ui.searchQuery.isNotBlank(), toggles = ui.filterToggles, )"

        private const val EXPECTED_ROW_DRAWN =
            "FilterChipRow( entries = row.filters.map { filter -> FilterChipEntry( " +
                "label = stringResource(filterLabel(filter.criterion)), selected = filter.checked, " +
                "enabled = row.enabled, onClick = { when (filter.criterion) { " +
                "ListCriterion.UNREAD -> viewModel.toggleUnreadOnly() " +
                "ListCriterion.FLAGGED -> viewModel.toggleFlaggedOnly() " +
                "ListCriterion.ATTACHMENT -> viewModel.toggleWithAttachment() } }, ) }, )"

        private val EXPECTED_BODY_LINES = listOf(
            "val matched = scene.activeFilters.map { stringResource(filterLabel(it)) }" +
                ".joinToString(stringResource(R.string.filter_list_separator))",
            "body = if (matched.isEmpty()) stringResource(scene.body) else stringResource(scene.body, matched),",
        )

        /** Declaration line to its single statement, in pairs so no sort can cross them. */
        private val EXPECTED_TOGGLE_BODIES = listOf(
            "fun toggleUnreadOnly() {" to "filterToggles.value = filterToggles.value.toggled(ListCriterion.UNREAD)",
            "fun toggleFlaggedOnly() {" to "filterToggles.value = filterToggles.value.toggled(ListCriterion.FLAGGED)",
            "fun toggleWithAttachment() {" to "filterToggles.value = filterToggles.value.toggled(ListCriterion.ATTACHMENT)",
            "fun clearListFilters() {" to "filterToggles.value = filterToggles.value.afterShowAll()",
        )

        private const val EXPECTED_SCENE_CALL =
            "emptyListScene( unreadView = ui.unreadView, toggles = ui.filterToggles, unified = ui.unified, " +
                "selectedMailboxId = ui.selectedMailboxId, folderRole = role, )"

        private const val EXPECTED_SHOW_ALL = "Button(onClick = { viewModel.clearListFilters() })"

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
    }
}

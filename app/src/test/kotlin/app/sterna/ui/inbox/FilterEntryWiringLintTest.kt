package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. What the list screen does with the toggles: the bar's funnel
 * and the menu it opens, the search's own chip row, the ViewModel's toggles, and the empty state's
 * scene, sentence and "Clear filters". The decisions are run by `FilterMenuTest` and
 * `EmptyListSceneTest`; this pins that the screen asks them. Holes (CONTRIBUTING.md, Tests):
 *  1. `ui.filterToggles` and `ui.unreadView` are names: nothing here proves they hold the toggles
 *     the pager reads, or the view the list pages.
 *  2. Whole calls and whole blocks are pinned, not what wraps the funnel's block: an `if` around it
 *     would hide the funnel and still read the same block.
 *  3. The funnel's place is pinned as source order inside the browse bar's `actions`, not as a
 *     position on screen; that the menu stays open after a tick is pinned as the absence of a
 *     closing assignment, not as what `DropdownMenu` does on a click.
 *  4. Nothing here holds the value the composable collects, nor proves an item is drawn ticked or a
 *     dot drawn: `DropdownMenuItem`, `Checkbox` and `Badge` are trusted.
 *  The overflow button's own content is `OutboxCountWiringTest`'s rule.
 */
class FilterEntryWiringLintTest {

    @Test
    fun `the funnel's menu is asked once, with the view and the toggles`() {
        val text = codeText(INBOX_SCREEN)
        val calls = Regex("""\bfilterMenu\s*\(""").findAll(text).map { callAt(text, it.range.first) }.toList()
        assertEquals(
            "The menu must be asked with the screen's own view flag (no Unread entry in the unread " +
                "view, and no dot for the view's own unread) and the toggles the pager reads (#126): " +
                "one filter state, two places to read it is how a funnel came to lie about the list. " +
                "Calls found were:\n${calls.joinToString("\n")}",
            listOf(EXPECTED_MENU_QUESTION),
            calls,
        )
    }

    @Test
    fun `the funnel is drawn whole, lit by the menu's answer, each entry flipping its own toggle`() {
        val text = codeText(INBOX_SCREEN)
        val icons = Regex("""\bIcons\.Filled\.FilterList\b""").findAll(text).toList()
        assertEquals("Exactly one funnel in the screen, the browse bar's.", 1, icons.size)
        val funnel = balancedFrom(text, text.lastIndexOf("Box {", icons.single().range.first))
        assertEquals(
            "The funnel must be boxed with its menu (#74), say 'Filters' and, when lit, 'Filter on'; " +
                "its dot and tint must read menu.active, never the raw toggles (the unread view's " +
                "own unread would light it). Each entry must show the tick of the toggle it flips and " +
                "flip only it: a crossed arm of the when compiles, and an entry then lies about the " +
                "list. Block found was:\n$funnel",
            EXPECTED_FUNNEL,
            funnel,
        )
        val items = Regex("""(?<!fun )\bFilterMenuItem\s*\(""").findAll(text).map { callAt(text, it.range.first) }.toList()
        assertEquals("One FilterMenuItem call, the funnel's.", listOf(EXPECTED_ITEM), items)
        val writes = Regex("""(\w+ = \{ )?\bfilterOpen\s*=(?!=)\s*[^,;})]+""").findAll(text).map { it.value.trim() }.toList()
        assertEquals(
            "A tick must leave the menu open: the filters combine, so the funnel's menu is opened " +
                "by its button and closed only by its dismissal (tap outside, Back). Writes found were:" +
                "\n${writes.joinToString("\n")}",
            listOf("onClick = { filterOpen = true", "onDismissRequest = { filterOpen = false"),
            writes,
        )
        assertEquals(
            "A funnel entry must not close the overflow menu either.",
            false,
            Regex("""\boverflowOpen\b""").containsMatchIn(items.single()),
        )
    }

    @Test
    fun `each menu entry carries its checkbox semantics and its own icon`() {
        val text = codeText(INBOX_SCREEN)
        val item = text.indexOf("private fun FilterMenuItem(")
        check(item >= 0) { "InboxScreen.kt no longer declares FilterMenuItem" }
        assertEquals(
            "The item, not the Checkbox, carries the state: 'onCheckedChange = null' leaves the box " +
                "without semantics, so a dropped Role.Checkbox or toggleableState leaves TalkBack " +
                "reading a plain menu entry with no tick.",
            EXPECTED_ITEM_DECLARATION,
            balancedFrom(text, item),
        )
        val icon = text.indexOf("private fun filterIcon(")
        check(icon >= 0) { "InboxScreen.kt no longer declares filterIcon" }
        assertEquals(
            "Each filter has its own icon; two arms swapped compile.",
            EXPECTED_ICON_DECLARATION,
            balancedFrom(text, icon),
        )
    }

    @Test
    fun `the funnel comes first in the browse bar, before sort, search and the overflow`() {
        val actions = browseBarActions(codeText(INBOX_SCREEN))
        val offsets = listOf(
            "Icons.Filled.FilterList",
            "Icons.AutoMirrored.Filled.Sort",
            "Icons.Filled.Search",
            "Icons.Filled.MoreVert",
        ).map { actions.indexOf(it) }
        assertEquals(
            "Order in the browse bar's actions must be funnel, sort, search, overflow. Offsets " +
                "were $offsets in:\n$actions",
            true,
            offsets.all { it >= 0 } && offsets.zipWithNext().all { (a, b) -> a < b },
        )
    }

    @Test
    fun `the list draws no chip row of its own, only the open search does`() {
        val text = codeText(INBOX_SCREEN)
        val outside = Regex("""(?<!fun )\bFilterChipRow\s*\(""").findAll(text)
            .filterNot { hit -> searchingBlocks(text).any { hit.range.first in it } }
            .map { callAt(text, it.range.first) }
            .toList()
        assertEquals(
            "A FilterChipRow outside 'if (ui.searching)' is the band of chips the funnel replaced: " +
                "a second control for the same toggles. Calls found outside were:" +
                "\n${outside.joinToString("\n")}",
            emptyList<String>(),
            outside,
        )
        val calls = Regex("""(?<!fun )\bFilterChipRow\s*\(""").findAll(text).map { callAt(text, it.range.first) }.toList()
        assertEquals(
            "The search's row must show the state of the criterion each chip flips. Calls found were:" +
                "\n${calls.joinToString("\n")}",
            listOf(EXPECTED_SEARCH_ROW_DRAWN),
            calls,
        )
    }

    @Test
    fun `the scope chip comes first, flips the scope it shows, and only from two accounts on`() {
        val text = codeText(INBOX_SCREEN)
        val at = text.indexOf("val scopeChip = ")
        check(at >= 0) { "InboxScreen.kt no longer builds the search row's scope chip in a local named scopeChip" }
        val condition = callAt(text, text.indexOf("if (", at))
        val entry = callAt(text, text.indexOf("FilterChipEntry(", at))
        assertEquals(
            "Shown on searchScopeChipShown of the account count, drawn with the label of the scope " +
                "in force, and flipping that scope: a chip naming one scope while the legs ask the " +
                "other is the lie this chip exists to end.",
            listOf(
                "if (searchScopeChipShown(accounts.size))",
                "FilterChipEntry( label = stringResource(searchScopeLabel(ui.searchScope)), selected = true, " +
                    "enabled = !selectionActive, onClick = { viewModel.toggleSearchScope() }, )",
            ),
            listOf(condition, entry),
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

    /** The ranges of every `if (ui.searching) { … }` body in [text]. */
    private fun searchingBlocks(text: String): List<IntRange> =
        Regex("""\bif \(ui\.searching\) \{""").findAll(text).map { hit ->
            val block = balancedFrom(text, hit.range.first)
            hit.range.first until hit.range.first + block.length
        }.toList()

    /** The `actions = { … }` lambda of the browse bar: the `TopAppBar` after `titleSwitchTargets`. */
    private fun browseBarActions(text: String): String {
        val anchor = text.indexOf("val titleSwitchTargets")
        check(anchor >= 0) { "InboxScreen.kt no longer declares titleSwitchTargets above the browse bar" }
        val bar = text.indexOf("TopAppBar(", anchor)
        check(bar >= 0) { "no TopAppBar after titleSwitchTargets" }
        val call = callAt(text, bar)
        val at = call.indexOf("actions =")
        check(at >= 0) { "the browse bar has no actions block:\n$call" }
        return balancedFrom(call, at)
    }

    /** [text] from [from] up to the `}` that balances the first `{` at or after it. */
    private fun balancedFrom(text: String, from: Int): String {
        val open = text.indexOf('{', from)
        check(open >= 0) { "no block opens after offset $from" }
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
            if (depth == 0) break
        }
        return text.substring(from, i).trim()
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
        private const val EXPECTED_MENU_QUESTION = "filterMenu(unreadView = ui.unreadView, toggles = ui.filterToggles)"

        private const val EXPECTED_ITEM =
            "FilterMenuItem( label = stringResource(filterLabel(filter.criterion)), " +
                "icon = filterIcon(filter.criterion), checked = filter.checked, onClick = { " +
                "when (filter.criterion) { " +
                "ListCriterion.UNREAD -> viewModel.toggleUnreadOnly() " +
                "ListCriterion.FLAGGED -> viewModel.toggleFlaggedOnly() " +
                "ListCriterion.ATTACHMENT -> viewModel.toggleWithAttachment() } }, )"

        private const val EXPECTED_FUNNEL =
            "Box { IconButton( onClick = { filterOpen = true }, " +
                "modifier = if (menu.active) Modifier.semantics { stateDescription = filterOnLabel } else Modifier, ) { " +
                "val dotColor = LocalContentColor.current " +
                "BadgedBox(badge = { if (menu.active) Badge(containerColor = dotColor) }) { " +
                "Icon( Icons.Filled.FilterList, contentDescription = stringResource(R.string.search_filters), " +
                "tint = if (menu.active) MaterialTheme.colorScheme.primary else LocalContentColor.current, ) } } " +
                "DropdownMenu(expanded = filterOpen, onDismissRequest = { filterOpen = false }, " +
                "shape = MaterialTheme.shapes.medium) { menu.filters.forEach { filter -> " +
                EXPECTED_ITEM + " } } }"

        private const val EXPECTED_ITEM_DECLARATION =
            "private fun FilterMenuItem(label: String, icon: ImageVector, checked: Boolean, onClick: () -> Unit) { " +
                "DropdownMenuItem( text = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null) }, " +
                "trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) }, onClick = onClick, " +
                "modifier = Modifier.semantics { role = Role.Checkbox toggleableState = ToggleableState(checked) }, ) }"

        private const val EXPECTED_ICON_DECLARATION =
            "private fun filterIcon(criterion: ListCriterion): ImageVector = when (criterion) { " +
                "ListCriterion.UNREAD -> Icons.Filled.MarkEmailUnread " +
                "ListCriterion.FLAGGED -> Icons.Filled.Star " +
                "ListCriterion.ATTACHMENT -> Icons.Filled.AttachFile }"

        private const val EXPECTED_SEARCH_ROW_DRAWN =
            "FilterChipRow( entries = scopeChip + SearchToggle.entries.map { toggle -> FilterChipEntry( " +
                "label = stringResource(searchToggleLabel(toggle)), selected = ui.searchCriteria.has(toggle), " +
                "enabled = !selectionActive, onClick = { viewModel.toggleSearchCriterion(toggle) }, ) }, )"

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

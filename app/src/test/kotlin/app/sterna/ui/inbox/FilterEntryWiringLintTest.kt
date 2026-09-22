package app.sterna.ui.inbox

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test. What the screen does with the list toggles: the funnel's and
 * the overflow button's dots and spoken state, the two menu entries, the ViewModel's toggles, and
 * the empty state's scene and "Show all". The decisions are run by `ListFilterIndicatorTest` and
 * `EmptyListSceneTest`; this pins that the screen asks them. Holes (CONTRIBUTING.md, Tests):
 *  1. `ui.filterToggles` is a name: nothing here proves it holds the toggles the pager reads.
 *  2. Whole lines are pinned, but not what spans them: the `else` before `if (overflowDot)` hangs
 *     off the outbox's `if`, so the outbox dot hides the filter dot, and nothing here says so.
 *  3. A pinned line says nothing about the `if (!ui.unreadView)` around the funnel, which
 *     `UnreadViewWiringLintTest` pins, nor about which menu the entries are drawn in.
 *  4. Nothing here holds the value the composable collects, nor proves a dot is drawn or a
 *     checked state is spoken: `IconToggleButton` and the item's semantics are trusted.
 */
class FilterEntryWiringLintTest {

    @Test
    fun `the funnel's dot, accent and spoken state all read the any-toggle decision`() {
        val lines = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""\b(filterOn|filterOnLabel|dotColor|overflowDot)\b""").containsMatchIn(it) }
        assertEquals(
            "The funnel's dot and accent must read anyActive() over the three toggles, and the " +
                "overflow button's dot and stateDescription must read dotOnOverflow(ui.unreadView). " +
                "'ui.unreadOnly' compiles in its place and leaves starred-only with no sign at all. " +
                "The dot takes the bar's content colour, not an accent: it reads by its shape. " +
                "Whole lines, in source order. Lines found were:\n${lines.joinToString("\n")}",
            EXPECTED_SIGN_LINES,
            lines,
        )
    }

    @Test
    fun `the funnel speaks its own toggle, and the others only when they are on`() {
        val text = codeText(INBOX_SCREEN)
        val toggles = Regex("""\bIconToggleButton\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .toList()
        assertEquals(
            "The funnel is a checkable button whose checked state is the unread toggle: a stateDescription " +
                "saying 'filter on' over an unread toggle that is off is what this replaced. Calls found " +
                "were:\n${toggles.joinToString("\n")}",
            listOf(EXPECTED_FUNNEL_CALL),
            toggles,
        )
        val names = codeLines(INBOX_SCREEN).map { it.trim() }
            .filter { Regex("""R\.string\.inbox_unread_only(_other_filters)?\b|\bnarrowsBeyondUnread\b""").containsMatchIn(it) }
        assertEquals(
            "The funnel's name adds 'other filters on' only when starred or attachment is on. Lines " +
                "found were:\n${names.joinToString("\n")}",
            EXPECTED_FUNNEL_NAME_LINES,
            names,
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
                    "toggleWithAttachment swapped compile, and each menu entry then flips the other " +
                    "filter under its own tick.",
                listOf(body, "}"),
                lines.subList(at + 1, at + 3),
            )
        }
    }

    @Test
    fun `the two menu entries are bound to their own toggle and their own state`() {
        val text = codeText(INBOX_SCREEN)
        val calls = Regex("""(?<!fun )\bFilterMenuItem\s*\(""").findAll(text)
            .map { callAt(text, it.range.first) }
            .toList()
        assertEquals(
            "Each entry must show the state of the toggle it flips: a crossed pair compiles, and " +
                "its box then lies about the list. Whole calls, in source order. Calls found were:" +
                "\n${calls.joinToString("\n")}",
            EXPECTED_MENU_CALLS,
            calls,
        )
        val lines = codeLines(INBOX_SCREEN)
        val start = lines.indexOfFirst { it.startsWith("private fun FilterMenuItem(") }
        check(start >= 0) { "InboxScreen.kt no longer declares FilterMenuItem at top level" }
        val end = (start until lines.size).first { lines[it] == "}" }
        val body = lines.subList(start, end + 1).map { it.trim() }
        assertEquals(
            "FilterMenuItem must be exactly this: the tap forwarded (onClick = onClick; '{}' compiles " +
                "and makes both entries inert), and the checked state carried by the item with its " +
                "checkbox role, since the box has no click of its own. Whole lines. Body was:" +
                "\n${body.joinToString("\n")}",
            EXPECTED_MENU_ITEM_BODY,
            body,
        )
    }

    @Test
    fun `the empty state asks the scene with every toggle, and Show all lifts them all`() {
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
        val label = text.indexOf("stringResource(R.string.empty_unread_action)")
        check(label >= 0) { "InboxScreen.kt no longer draws empty_unread_action" }
        val button = text.lastIndexOf("Button(", label)
        assertEquals(
            "Show all must call clearListFilters(), which lifts the three toggles (afterShowAll). " +
                "toggleUnreadOnly() compiles there and, under starred alone, turns unread ON: the " +
                "list stays empty under a button called Show all.",
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
        private val EXPECTED_SIGN_LINES = listOf(
            "val filterOnLabel = stringResource(R.string.inbox_filter_active)",
            "val dotColor = LocalContentColor.current",
            "val filterOn = ui.filterToggles.anyActive()",
            "BadgedBox(badge = { if (filterOn) Badge(containerColor = dotColor) }) {",
            "tint = if (filterOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,",
            "val overflowDot = ui.filterToggles.dotOnOverflow(ui.unreadView)",
            "modifier = if (overflowDot) Modifier.semantics { stateDescription = filterOnLabel } else Modifier,",
            "} else if (overflowDot) {",
            "Badge(containerColor = dotColor)",
        )

        private const val EXPECTED_FUNNEL_CALL =
            "IconToggleButton( checked = ui.filterToggles.unreadOnly, " +
                "onCheckedChange = { viewModel.toggleUnreadOnly() }, )"

        private val EXPECTED_FUNNEL_NAME_LINES = listOf(
            "contentDescription = if (ui.filterToggles.narrowsBeyondUnread()) {",
            "stringResource(R.string.inbox_unread_only_other_filters)",
            "stringResource(R.string.inbox_unread_only)",
        )

        /** Declaration line to its single statement, in pairs so no sort can cross them. */
        private val EXPECTED_TOGGLE_BODIES = listOf(
            "fun toggleUnreadOnly() {" to "filterToggles.value = filterToggles.value.toggled(ListCriterion.UNREAD)",
            "fun toggleFlaggedOnly() {" to "filterToggles.value = filterToggles.value.toggled(ListCriterion.FLAGGED)",
            "fun toggleWithAttachment() {" to "filterToggles.value = filterToggles.value.toggled(ListCriterion.ATTACHMENT)",
            "fun clearListFilters() {" to "filterToggles.value = filterToggles.value.afterShowAll()",
        )

        private val EXPECTED_MENU_CALLS = listOf(
            "FilterMenuItem( label = stringResource(R.string.inbox_filter_flagged), icon = Icons.Filled.Star, " +
                "checked = ui.filterToggles.flaggedOnly, " +
                "onClick = { viewModel.toggleFlaggedOnly(); overflowOpen = false }, )",
            "FilterMenuItem( label = stringResource(R.string.inbox_filter_attachment), icon = Icons.Filled.AttachFile, " +
                "checked = ui.filterToggles.withAttachment, " +
                "onClick = { viewModel.toggleWithAttachment(); overflowOpen = false }, )",
        )

        private val EXPECTED_MENU_ITEM_BODY = listOf(
            "private fun FilterMenuItem(label: String, icon: ImageVector, checked: Boolean, onClick: () -> Unit) {",
            "DropdownMenuItem(",
            "text = { Text(label) },",
            "leadingIcon = { Icon(icon, contentDescription = null) },",
            "trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },",
            "onClick = onClick,",
            "modifier = Modifier.semantics {",
            "role = Role.Checkbox",
            "toggleableState = ToggleableState(checked)",
            "},",
            ")",
            "}",
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

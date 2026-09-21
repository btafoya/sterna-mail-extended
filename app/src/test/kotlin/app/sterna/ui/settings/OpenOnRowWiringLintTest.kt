package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test, it reads two source files as text and proves nothing about
 * what is drawn. Same instrument and disclaimer as [UnreadTintWiringTest] and [PureBlackWiringTest].
 *
 * `OpenOnRowTest` runs the two decisions themselves (which options exist, which one is shown).
 * What no test in this module can reach is the row: `SettingsScreen` is Compose, and this module
 * has no Robolectric and no instrumented tests. Four silent edits this file exists to catch:
 *
 *  1. **the value shown read straight off the disk.** A single-account install with "unified"
 *     stored opens on its inbox, and a row reading the stored string announces "All inboxes" for a
 *     launch that does not do that, the very disagreement this setting was built to avoid;
 *  2. **the bound handed to the screen written as a constant.** `unifiedViewExists = true` offers
 *     "All inboxes" to a single-account install, which stores it and reads it back as "Inbox" for
 *     ever after;
 *  3. **the row hidden behind a condition**, which takes the setting off the screen while the
 *     value it stands for stays in force;
 *  4. **the write dropped**: the tick moves, nothing reaches the store, and the launch goes on
 *     doing what it did.
 *
 * Of the four holes `CONTRIBUTING.md` lists, two apply. (3) execution order: nothing here says
 * when, or whether, the composable runs, only where the lines sit. (4) nothing here can hold the
 * value the screen actually collects, only the line that collects it. Hole (1) is closed for
 * `unifiedViewExists` and `openOnView`, both pinned to the expression they are bound to at the
 * call site, and what is left of it is `accountsViewModel.accounts`, a name this file compares as
 * text and cannot run. Hole (2) is closed by comparing whole arguments and whole blocks, the
 * wrapping line included.
 */
class OpenOnRowWiringLintTest {

    // -- 1. the row on the Appearance screen -----------------------------------------------------

    /**
     * The row, whole. `selected` must be the rule's answer and never the stored string, `options`
     * must carry the bound, and `onSelect` must hand the store the literal the rules read back,
     * never the label the reader just saw, which is translated and which no rule can recognise.
     */
    @Test fun `the message list section carries the row, argument by argument`() {
        val section = messageListSection()
        val at = rowAt(section)
        assertTrue(
            "the 'Message list' section of AppearanceScreen must contain a SettingChoiceRow " +
                "reading settings_open_on_title — the setting is otherwise stored, read and " +
                "unreachable. Section was:\n" + section.joinToString("\n"),
            at >= 0,
        )
        val found = section.subList(at, section.size)
        val mismatches = EXPECTED_ROW.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == EXPECTED_ROW[i]) null
            else "line ${i + 1} of the row: expected '${EXPECTED_ROW[i]}' but found '$actual'"
        }
        assertEquals(
            "the row is pinned WHOLE, line by line and in order: an argument that drifts is one of " +
                "the ways this setting goes quietly inert. Mismatches:\n" +
                mismatches.joinToString("\n") + "\nat index $at",
            emptyList<String>(),
            mismatches,
        )
    }

    /**
     * [rowAt] anchors on the title line, and a second row carrying the same title would make the
     * rules above read one of them and leave the other unseen, in a file of some 2 500 lines.
     */
    @Test fun `exactly one row carries this title`() {
        val titles = codeLines(SETTINGS_SCREEN).filter { it == EXPECTED_ROW[1] }
        assertEquals(
            "${titles.size} code lines in SettingsScreen.kt read '${EXPECTED_ROW[1]}' — this lint " +
                "anchors every rule it has on that line, and reads the FIRST one. Two of them and " +
                "the second is pinned by nothing at all.",
            1,
            titles.size,
        )
    }

    /** The negative screen for the row itself: nothing may dim it or make it conditional. */
    @Test fun `the row is neither greyed out nor hidden behind a condition`() {
        val section = messageListSection()
        val start = rowAt(section)
        check(start >= 0) { "no open-on row in the Message list section of SettingsScreen.kt" }
        val block = section.subList(start, minOf(start + EXPECTED_ROW.size, section.size))
        assertTrue(
            "the open-on row must carry no 'enabled =' argument and no 'if (': a row dimmed or " +
                "hidden while the value it stands for is still in force is the WYSIWYG lie " +
                "SettingsScreenHonestyTest was written for. Block was:\n" + block.joinToString("\n"),
            block.none { "enabled =" in it } && block.none { "if (" in it },
        )
        // The block above is the row's own seven lines, compared trimmed; a condition wrapping it
        // sits outside them and re-indents them, which trimming hides. Same instrument as
        // SettingsScreenHonestyTest.enclosingLine(), and the defect PureBlackWiringTest was
        // written for after the Android-12 guard swallowed a switch.
        val enclosing = enclosingLineOfRow()
        assertTrue(
            "the line that WRAPS the open-on row must not be a condition: nested in one — " +
                "'if (unifiedViewExists)' first of all — the row disappears from Appearance for " +
                "every single-account install, which is most of them, while the stored value goes " +
                "on deciding what opens. Enclosing line was:\n$enclosing",
            "if (" !in enclosing && "when (" !in enclosing,
        )
    }

    /**
     * Two of the three labels are the ones the drawer and the folder list already use. A twin
     * string would be a second translation of "Inbox" to keep level with the first, in ten
     * languages, and the day they disagree the row names a view by a name nothing else uses.
     */
    @Test fun `the inbox and unified labels are borrowed, not copied`() {
        assertEquals(
            "openOnLabel() must map the two stored literals onto the existing labels " +
                "R.string.folder_inbox and R.string.inbox_all_inboxes, and only the 'view you " +
                "left' option owes a string of its own.",
            EXPECTED_LABEL,
            declarationOf("private fun openOnLabel"),
        )
    }

    // -- 2. what the screen is handed, and what it collects ---------------------------------------

    /**
     * The bound is what makes the offer honest, and `unifiedViewExists` is an argument name: a
     * literal, or a second copy of `accounts.size > 1`, offers "All inboxes" where the launch
     * ignores it. Pinned with the line that collects the accounts, which is what makes it live:
     * read once into a `val` at the graph's top, signing an account in would not reach it.
     */
    @Test fun `the screen is handed the bound of the live account set`() {
        assertBlock(
            SETTINGS_SCREEN,
            EXPECTED_APPEARANCE_ROUTE,
            "the Appearance route",
            "a single-account install would be offered 'All inboxes', store it, and read it back " +
                "as 'Inbox' on every launch after that",
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "AppearanceScreen must read the state through " +
                "'val openOnView by viewModel.openOnView.collectAsStateWithLifecycle()' — the " +
                "whole line, like every other setting on the screen. A constant here, or a second " +
                "read of the store, leaves a row that shows one thing while another is stored.",
            listOf("val openOnView by viewModel.openOnView.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val openOnView by") },
        )
    }

    // -- 3. the write path ------------------------------------------------------------------------

    /**
     * The half of the setting no other test here touches: **storing** what the user chose. Nothing
     * executes `setOpenOnView`, so dropping the store write while keeping the state update leaves
     * the tick moving on screen, nothing on disk, and the whole suite green.
     */
    @Test fun `picking an option stores it, and not only on screen`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            EXPECTED_SETTER,
            "the view model's setter",
            "the tick would move and spring back on the next visit, with nothing ever written for " +
                "the launch to read",
        )
        assertEquals(
            "SettingsViewModel.kt must write through the account store, synchronously, in exactly " +
                "one place: 'store.setOpenOnView(value)'. The launch reads this preference off " +
                "the disk before any ViewModel exists, so a write routed through " +
                "settingsRepository would be a second, later answer beside the one already used.",
            listOf("store.setOpenOnView(value)"),
            codeLines(SETTINGS_VIEW_MODEL).filter { "setOpenOnView(" in it && !it.startsWith("fun ") },
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /**
     * The nearest enclosing line of the open-on row, the first line above it indented less than it
     * is. Copied from [ListMonogramWiringTest]'s `enclosingLineOfSwitch()`, indentation and all,
     * which is why it reads the file again untrimmed: the rest of this class compares trimmed
     * lines, and indentation is the only thing that says what wraps what.
     */
    private fun enclosingLineOfRow(): String {
        val lines = SETTINGS_SCREEN_RAW
        val title = lines.indexOfFirst { it.trim() == EXPECTED_ROW[1] }
        check(title >= 0) {
            "no '${EXPECTED_ROW[1]}' in SettingsScreen.kt — the open-on row is gone, and this rule " +
                "must be taught the new shape rather than left green over nothing"
        }
        val start = (title - 1 downTo 0).first { lines[it].trim() == "SettingChoiceRow(" }
        val indent = lines[start].indentWidth()
        return (start - 1 downTo 0)
            .map { lines[it] }
            .firstOrNull { it.isNotBlank() && it.indentWidth() < indent }
            ?: error("the row is at the top level of SettingsScreen.kt — has the file moved?")
    }

    private fun String.indentWidth() = length - trimStart().length

    /** Where the pinned row starts inside the section body, found by its own title string. */
    private fun rowAt(section: List<String>): Int =
        section.indexOfFirst { it == EXPECTED_ROW[1] }.let { if (it <= 0) -1 else it - 1 }

    /**
     * The body of `SettingsSection(stringResource(R.string.settings_message_list_section)) {`, and
     * only it: the block is closed by counting braces, not by waiting for the next section (see
     * [UnreadTintWiringTest] for the section that swallowed three hundred unrelated lines).
     */
    private fun messageListSection(): List<String> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "SettingsSection(stringResource(R.string.settings_message_list_section)) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) {
            "no '$opener' in SettingsScreen.kt — the section moved or was reshaped, and this lint " +
                "must be taught the new shape rather than left green over a section it never read"
        }
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line
        }
        error("the 'Message list' section is never closed in SettingsScreen.kt")
    }

    /** [name]'s declaration, from its own line down to the first closing brace, on one line. */
    private fun declarationOf(name: String): String {
        val lines = codeLines(SETTINGS_SCREEN)
        val starts = lines.filter { it.startsWith(name) }
        assertEquals(
            "${starts.size} code lines in SettingsScreen.kt start with '$name' — this rule reads " +
                "the first and would leave any other unpinned.",
            1,
            starts.size,
        )
        val start = lines.indexOfFirst { it.startsWith(name) }
        val end = lines.drop(start).indexOfFirst { it == "}" } + start
        return lines.subList(start, end + 1).joinToString(" ")
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String, cost: String) {
        val lines = codeLines(file)
        val anchors = lines.filter { it == expected.first() }
        assertEquals(
            "${anchors.size} code lines in ${file.name} read '${expected.first()}' — this rule " +
                "anchors on it and reads the first. What the user would see if the other one " +
                "drifted: $cost",
            1,
            anchors.size,
        )
        val at = lines.indexOfFirst { it == expected.first() }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned WHOLE, line by line and in order — nothing in this repo executes " +
                "these lines. What the user would see if this drifted: $cost. Mismatches:\n" +
                mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val EXPECTED_ROW = listOf(
            "SettingChoiceRow(",
            "title = stringResource(R.string.settings_open_on_title),",
            "options = openOnOptions(unifiedViewExists),",
            "selected = openOnShown(openOnView, unifiedViewExists),",
            "optionLabel = { openOnLabel(context, it) },",
            "onSelect = viewModel::setOpenOnView,",
            ")",
        )

        val EXPECTED_APPEARANCE_ROUTE = listOf(
            "composable(\"appearance\") { entry ->",
            "val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()",
            "AppearanceScreen(",
            "viewModel = viewModel,",
            "unifiedViewExists = AllInboxesView.existsFor(accounts),",
            "onBack = { entry.navigateOnce { nav.popBackStack() } },",
            ")",
            "}",
        )

        val EXPECTED_SETTER = listOf(
            "fun setOpenOnView(value: String) {",
            "store.setOpenOnView(value)",
            "_openOnView.value = value",
            "}",
        )

        const val EXPECTED_LABEL =
            "private fun openOnLabel(context: Context, value: String): String = when (value) { " +
                "OPEN_ON_INBOX -> context.getString(R.string.folder_inbox) " +
                "OPEN_ON_UNIFIED -> context.getString(R.string.inbox_all_inboxes) " +
                "else -> context.getString(R.string.settings_open_on_last) }"

        const val SETTINGS_SCREEN_PATH = "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt"

        /** Repo root, walked up from the module's working directory. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_SCREEN_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this test reads " +
                        "the source as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_SCREEN: File by lazy { File(root, SETTINGS_SCREEN_PATH) }

        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }

        /** SettingsScreen.kt with its indentation intact, see `enclosingLineOfRow`. Comment-only
         *  lines are dropped whole, as in the sibling lints, so prose can never wrap anything. */
        val SETTINGS_SCREEN_RAW: List<String> by lazy {
            SETTINGS_SCREEN.readLines().filterNot {
                val code = it.trimStart()
                code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
            }
        }
    }
}

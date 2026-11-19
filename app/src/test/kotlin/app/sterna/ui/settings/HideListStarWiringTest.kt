package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads six source files as text and proves nothing about
 * what is drawn. Same instrument and disclaimer as [ListMonogramWiringTest].
 *
 * `SettingsBackupHideListStarTest` runs the decision (the default, the key, the backup field).
 * The plumbing is Compose or Android code this module cannot run (no Robolectric, no
 * `compose-ui-test`). Silent edits this file exists to catch:
 *
 *  1. the guard at the row inverted or dropped: nothing executes `EmailListItem`, so
 *     `if (!LocalHideListStar.current)` hides every star from whoever kept them;
 *  2. the end gap missing from either starless arm, or the Row's end padding grown instead: the
 *     date and the paperclip then stick to the edge (or, grown, a star row loses 12 dp);
 *  3. the default written as a literal in the activity or the CompositionLocal;
 *  4. the setting collected and never provided, so the switch does nothing;
 *  5. the switch missing from Appearance, conditional, or off its place next to the
 *     sender-initials one;
 *  6. the setting missing from the backup snapshot or the restore.
 *
 * What it does not see: whether the gap reads right on screen, and the local draft arm, which
 * [app.sterna.ui.inbox.LocalDraftListWiringLintTest] owns; only its gap is pinned here.
 *
 * Every rule compares whole trimmed lines; `contains` appears only where something must be absent.
 */
class HideListStarWiringTest {

    // -- 1 & 2. the row itself -------------------------------------------------------------------

    @Test fun `the row draws its star only while the setting is off, and leaves a gap instead`() {
        val lines = codeLines(EMAIL_LIST_ITEM)
        val at = lines.indexOfFirst { it == "if (onToggleFavourite != null) {" }
        check(at >= 0) { "no 'if (onToggleFavourite != null) {' in EmailListItem.kt" }
        assertEquals(
            "the star block must open on the setting, the hidden arm drawing only the end gap: " +
                "inverted, every reader who kept the star loses it; without the Spacer the date " +
                "sticks to the row's 4 dp end padding.",
            listOf(
                "if (onToggleFavourite != null) {",
                "if (LocalHideListStar.current) {",
                "Spacer(Modifier.width(StarlessEndGap))",
                "} else {",
                "val favLabel = stringResource(",
            ),
            lines.subList(at, minOf(at + 5, lines.size)),
        )
        val end = closingLine(lines, at)
        assertEquals(
            "a row with no callback at all (a local draft, the search list) must end on the same " +
                "gap, right after the star block closes.",
            listOf("} else {", "Spacer(Modifier.width(StarlessEndGap))", "}"),
            lines.subList(end, minOf(end + 3, lines.size)),
        )
    }

    @Test fun `the setting is read at the row once, through the CompositionLocal`() {
        assertEquals(
            "EmailListItem.kt must read the setting on exactly one line, the guard itself. A " +
                "second read, or a parameter, would let the three call sites drift apart.",
            listOf("if (LocalHideListStar.current) {"),
            codeLines(EMAIL_LIST_ITEM).filter { "LocalHideListStar" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the end gap makes up sixteen dp with a padding left as the star needs it`() {
        val lines = codeLines(EMAIL_LIST_ITEM)
        assertEquals(
            "the gap is declared once, at 12 dp: with the Row's 4 dp end padding it gives the " +
                "16 dp the start has.",
            listOf("private val StarlessEndGap = 12.dp"),
            lines.filter { "StarlessEndGap =" in it },
        )
        assertEquals(
            "the gap is drawn in the two starless arms and nowhere else: a third one adds 12 dp " +
                "to a row that still shows its star.",
            2,
            lines.count { it == "Spacer(Modifier.width(StarlessEndGap))" },
        )
        assertEquals(
            "the Row keeps its 4 dp end padding: grown to 16, a row with a star loses 12 dp of " +
                "width to nothing.",
            listOf(".padding(start = 16.dp, end = 4.dp, top = rowPadding, bottom = rowPadding),"),
            lines.filter { it.startsWith(".padding(start = 16.dp") },
        )
    }

    // -- 3 & 4. the default, in its three copies, and the provider --------------------------------

    @Test fun `the repository states the default once, and it shows the star`() {
        assertEquals(
            "SettingsRepository.kt must declare 'const val HIDE_LIST_STAR_DEFAULT = false': flipped, " +
                "the update that ships this takes the star away from everyone.",
            listOf("const val HIDE_LIST_STAR_DEFAULT = false"),
            codeLines(SETTINGS_REPOSITORY).filter { "HIDE_LIST_STAR_DEFAULT =" in it },
        )
    }

    @Test fun `the activity's first frame reads the shared default, and provides the setting`() {
        assertEquals(
            "MainActivity.kt must collect the setting with the shared default, the whole line.",
            listOf("val hideListStar by settings.hideListStar.collectAsState(initial = HIDE_LIST_STAR_DEFAULT)"),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("val hideListStar by") },
        )
        assertEquals(
            "MainActivity.kt must provide it: collected and never provided, the switch does nothing.",
            listOf("LocalHideListStar provides hideListStar,"),
            codeLines(MAIN_ACTIVITY).filter { "LocalHideListStar" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the CompositionLocal falls back to the shared default too`() {
        assertEquals(
            "LocalListDensity.kt must declare the CompositionLocal on the shared default.",
            listOf("val LocalHideListStar = compositionLocalOf { HIDE_LIST_STAR_DEFAULT }"),
            codeLines(LOCAL_LIST_DENSITY).filter { "LocalHideListStar" in it },
        )
    }

    @Test fun `no copy of the default outside the repository is spelled out as a boolean`() {
        val offenders = listOf(MAIN_ACTIVITY, LOCAL_LIST_DENSITY, SETTINGS_VIEW_MODEL, EMAIL_LIST_ITEM)
            .flatMap { file ->
                codeLines(file)
                    .filter { "hideListStar" in it || "HideListStar" in it || "HIDE_LIST_STAR" in it }
                    .filter { Regex("""\b(true|false)\b""").containsMatchIn(it) }
                    .map { "${file.name}: $it" }
            }
        assertEquals(
            "no line about this setting outside SettingsRepository.kt may write 'true' or " +
                "'false'; each copy reads HIDE_LIST_STAR_DEFAULT. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the view model reads the shared default and passes the choice through`() {
        val lines = codeLines(SETTINGS_VIEW_MODEL)
        val expectedState = listOf(
            "val hideListStar = settings.hideListStar.stateIn(",
            "scope = viewModelScope,",
            "started = SharingStarted.WhileSubscribed(5_000),",
            "initialValue = HIDE_LIST_STAR_DEFAULT,",
            ")",
        )
        val at = lines.indexOfFirst { it == expectedState.first() }
        check(at >= 0) { "no '${expectedState.first()}' in SettingsViewModel.kt" }
        assertEquals(expectedState, lines.subList(at, minOf(at + expectedState.size, lines.size)))
        val expectedSetter = listOf(
            "fun setHideListStar(enabled: Boolean) {",
            "viewModelScope.launch { settings.setHideListStar(enabled) }",
            "}",
        )
        val set = lines.indexOfFirst { it == expectedSetter.first() }
        check(set >= 0) { "no '${expectedSetter.first()}' in SettingsViewModel.kt" }
        assertEquals(
            "the setter passes the argument through, and nothing else: no widget redraw, the " +
                "home-screen widget draws no star.",
            expectedSetter,
            lines.subList(set, minOf(set + expectedSetter.size, lines.size)),
        )
    }

    // -- 5. the switch on the Appearance screen ---------------------------------------------------

    /**
     * Placed directly above the sender-initials switch, its neighbour as the other setting that
     * takes something off the row. Not below it: `BoldUnreadFoldersWiringTest` pins the bold-folders
     * switch directly under the sender-initials one, and `StarredInDrawerWiringTest` pins the drawer
     * switch directly under that.
     */
    @Test fun `the switch sits right above the sender-initials one, with its own two strings`() {
        val section = messageListSection()
        val title = section.indexOfFirst { it == EXPECTED_SWITCH[1] }
        assertTrue(
            "the 'Message list' section must contain a SettingSwitch reading " +
                "settings_hide_list_star_*. Section was:\n" + section.joinToString("\n"),
            title > 0,
        )
        val expected = EXPECTED_SWITCH + SENDER_INITIALS_SWITCH
        val from = title - 1
        assertEquals(
            "the switch is pinned whole, line by line, directly above the sender-initials switch.",
            expected,
            section.subList(from, minOf(from + expected.size, section.size)),
        )
    }

    @Test fun `the switch is neither greyed out nor hidden behind a condition`() {
        val raw = SETTINGS_SCREEN_RAW
        val title = raw.indexOfFirst { it.trim() == EXPECTED_SWITCH[1] }
        check(title > 0) { "no '${EXPECTED_SWITCH[1]}' in SettingsScreen.kt" }
        val start = (title - 1 downTo 0).first { raw[it].trim() == "SettingSwitch(" }
        val block = raw.subList(start, minOf(start + EXPECTED_SWITCH.size, raw.size)).map { it.trim() }
        assertTrue(
            "the switch must carry no 'enabled =' and no 'if ('. Block was:\n" + block.joinToString("\n"),
            block.none { "enabled =" in it } && block.none { "if (" in it },
        )
        val indent = raw[start].indentWidth()
        val enclosing = (start - 1 downTo 0).map { raw[it] }
            .firstOrNull { it.isNotBlank() && it.indentWidth() < indent }
            ?: error("the switch is at the top level of SettingsScreen.kt")
        assertTrue(
            "the line that wraps the switch must not be a condition. Enclosing line was:\n$enclosing",
            "if (" !in enclosing && "when (" !in enclosing,
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            listOf("val hideListStar by viewModel.hideListStar.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val hideListStar by") },
        )
    }

    @Test fun `the search index lists the switch under Appearance, right above the sender initials`() {
        val lines = codeLines(SETTINGS_INDEX_FILE)
        val at = lines.indexOfFirst { it == "\"hide_list_star\", APPEARANCE, R.string.settings_hide_list_star_title," }
        check(at > 0) { "no hide_list_star entry in SettingsIndex.kt" }
        assertEquals(
            listOf(
                "SettingEntry(",
                "\"hide_list_star\", APPEARANCE, R.string.settings_hide_list_star_title,",
                "R.string.settings_hide_list_star_subtitle,",
                "),",
                "SettingEntry(\"list_monogram\", APPEARANCE, R.string.settings_list_monogram_title, " +
                    "R.string.settings_list_monogram_subtitle),",
            ),
            lines.subList(at - 1, minOf(at + 4, lines.size)),
        )
    }

    // -- the read and the write path ---------------------------------------------------------------

    @Test fun `the flow is fed by this setting's own decision function`() {
        assertEquals(
            "a neighbouring decision function here (::starredInDrawerFrom is one identifier away) " +
                "would make the drawer switch hide the stars.",
            listOf("val hideListStar: Flow<Boolean> = dataStore.data.map(::hideListStarFrom)"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("val hideListStar") },
        )
    }

    @Test fun `flipping the switch stores what the user chose, not a constant`() {
        assertEquals(
            listOf("dataStore.edit { it[KEY_HIDE_LIST_STAR] = enabled }"),
            codeLines(SETTINGS_REPOSITORY).filter { "it[KEY_HIDE_LIST_STAR]" in it },
        )
    }

    // -- 6. the backup ----------------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry the setting, or a restore on a new device puts every star back.",
            listOf("hideListStar = hideListStar.first(),"),
            repository.filter { it.startsWith("hideListStar = ") },
        )
        assertEquals(
            "restoreBackup must apply it with '?.let': an absent field means 'leave as is'.",
            listOf("backup.hideListStar?.let { setHideListStar(it) }"),
            repository.filter { it.startsWith("backup.hideListStar") },
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /**
     * The index of the line whose brace closes the block [opener] opens, counted character by
     * character: a `} else {` closes the block and opens another on the same line.
     */
    private fun closingLine(lines: List<String>, opener: Int): Int {
        var depth = 0
        for (i in opener until lines.size) {
            for (c in lines[i]) {
                if (c == '{') depth++
                if (c == '}') depth--
                if (depth == 0 && i > opener) return i
            }
        }
        error("the block opened at '${lines[opener]}' is never closed in EmailListItem.kt")
    }

    private fun String.indentWidth() = length - trimStart().length

    private fun messageListSection(): List<String> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "SettingsSection(stringResource(R.string.settings_message_list_section)) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) { "no '$opener' in SettingsScreen.kt" }
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line
        }
        error("the 'Message list' section is never closed in SettingsScreen.kt")
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val EXPECTED_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_hide_list_star_title),",
            "subtitle = stringResource(R.string.settings_hide_list_star_subtitle),",
            "checked = hideListStar,",
            "onCheckedChange = viewModel::setHideListStar,",
            ")",
        )

        val SENDER_INITIALS_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_list_monogram_title),",
            "subtitle = stringResource(R.string.settings_list_monogram_subtitle),",
            "checked = listMonogram,",
            "onCheckedChange = viewModel::setListMonogram,",
            ")",
        )

        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
        val MAIN_ACTIVITY: File by lazy { File(root, "app/src/main/kotlin/app/sterna/MainActivity.kt") }
        val LOCAL_LIST_DENSITY: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/components/LocalListDensity.kt")
        }
        val EMAIL_LIST_ITEM: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/components/EmailListItem.kt")
        }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }
        val SETTINGS_INDEX_FILE: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsIndex.kt")
        }

        /** SettingsScreen.kt with its indentation intact, comment-only lines dropped. */
        val SETTINGS_SCREEN_RAW: List<String> by lazy {
            SETTINGS_SCREEN.readLines().filterNot {
                val code = it.trimStart()
                code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
            }
        }
    }
}

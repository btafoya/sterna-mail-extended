package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads seven source files as text and proves nothing about
 * what is drawn. Same instrument as [ListMonogramWiringTest], whose shape it copies.
 *
 * The decision is run elsewhere: `FolderLabelBoldTest` executes `folderLabelBold`, and
 * `SettingsBackupBoldUnreadFoldersTest` the default, the key and the backup field. This file pins
 * what nothing in this module can execute: the drawer's label line, `MainActivity`, the
 * CompositionLocal, the view model and the Appearance screen.
 *
 * Holes (CONTRIBUTING.md, Tests):
 *  1. `unread` and `boldUnreadFolders` on the label line are names. `unread` is tied to the count
 *     the label shows by `CollapsedFoldersSurfaceWiringTest`, which pins its declaration and both
 *     branches of the label; `boldUnreadFolders` is tied to the CompositionLocal by the rule below.
 *  2. The label line is compared whole, so the expression around the call is held on that line.
 *     Nothing holds the rest of the `NavigationDrawerItem`.
 *  3. Nothing says the read of the CompositionLocal sits in `DrawerContent` rather than in another
 *     composable of `InboxScreen.kt`; only that the file holds exactly one, spelled this way.
 *  4. Nothing here holds the value a composable collects, nor proves a label is drawn bold.
 *
 * Every rule compares whole trimmed lines. `contains` appears only where a rule screens for
 * something that must be absent.
 */
class BoldUnreadFoldersWiringTest {

    // -- the drawer --------------------------------------------------------------------------------

    @Test fun `a folder row is bold on the count it shows and on the setting, and carries no weight otherwise`() {
        assertEquals(
            "the folder row's label in InboxScreen.kt must be '$EXPECTED_LABEL' — the whole line. " +
                "The weight is decided by folderLabelBold on `unread`, the number the label shows, " +
                "and on the setting; the other branch is `null`, not FontWeight.Normal, so the " +
                "setting turned off draws the row exactly as it was before the setting existed.",
            listOf(EXPECTED_LABEL),
            codeLines(INBOX_SCREEN).filter { it.startsWith("label = { Text(label") },
        )
    }

    @Test fun `the drawer reads the setting from the CompositionLocal, once`() {
        assertEquals(
            "InboxScreen.kt must read the setting as '$EXPECTED_DRAWER_READ', once. A parameter on " +
                "DrawerContent instead would have to be threaded through both envelopes, and a " +
                "literal here makes the switch do nothing.",
            listOf(EXPECTED_DRAWER_READ),
            codeLines(INBOX_SCREEN).filter { "LocalBoldUnreadFolders" in it && !it.startsWith("import ") },
        )
        assertEquals(
            "`boldUnreadFolders` must be named on two lines of InboxScreen.kt only: where it is " +
                "read and where the label uses it. A third line is a reassignment or a shadow.",
            listOf(EXPECTED_DRAWER_READ, EXPECTED_LABEL),
            codeLines(INBOX_SCREEN).filter { Regex("""\bboldUnreadFolders\b""").containsMatchIn(it) },
        )
    }

    // -- the default, in its copies, and the provider ---------------------------------------------

    @Test fun `the repository states the default once, and it is on`() {
        assertEquals(
            "SettingsRepository.kt must declare 'const val BOLD_UNREAD_FOLDERS_DEFAULT = true', the " +
                "one definition every other copy reads.",
            listOf("const val BOLD_UNREAD_FOLDERS_DEFAULT = true"),
            codeLines(SETTINGS_REPOSITORY).filter { "BOLD_UNREAD_FOLDERS_DEFAULT =" in it },
        )
    }

    @Test fun `the activity's first frame reads the shared default, and provides the setting`() {
        assertEquals(
            "MainActivity.kt must collect the setting as '$EXPECTED_ACTIVITY_COLLECT' — the whole " +
                "line. A literal here is a second copy of the default: once it disagrees with the " +
                "repository, the drawer opened right after launch changes weight under the eye.",
            listOf(EXPECTED_ACTIVITY_COLLECT),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("val boldUnreadFolders by") },
        )
        assertEquals(
            "MainActivity.kt must hand it down through '$EXPECTED_ACTIVITY_PROVIDES'. Collected " +
                "and never provided, the drawer falls back to the CompositionLocal's default and " +
                "the switch does nothing.",
            listOf(EXPECTED_ACTIVITY_PROVIDES),
            codeLines(MAIN_ACTIVITY).filter { "LocalBoldUnreadFolders" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the CompositionLocal falls back to the shared default too`() {
        assertEquals(
            "LocalListDensity.kt must declare '$EXPECTED_LOCAL' — the whole line. It is what every " +
                "composable outside MainActivity's provider gets.",
            listOf(EXPECTED_LOCAL),
            codeLines(LOCAL_LIST_DENSITY).filter { "LocalBoldUnreadFolders" in it },
        )
    }

    /** The negative screen, a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `no copy of the default is spelled out as a boolean`() {
        val offenders = listOf(MAIN_ACTIVITY, LOCAL_LIST_DENSITY, INBOX_SCREEN).flatMap { file ->
            codeLines(file)
                .filter { "oldUnreadFolders" in it || "BOLD_UNREAD_FOLDERS" in it }
                .filter { Regex("""\b(true|false)\b""").containsMatchIn(it) }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "no line about this setting in MainActivity.kt, LocalListDensity.kt or InboxScreen.kt " +
                "may write 'true' or 'false': all read BOLD_UNREAD_FOLDERS_DEFAULT or the provided " +
                "value. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test fun `the settings screen's first frame reads the shared default too`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val boldUnreadFolders = settings.boldUnreadFolders.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = BOLD_UNREAD_FOLDERS_DEFAULT,",
                ")",
            ),
            "the view model's boldUnreadFolders state",
            "the switch would show the wrong position for the first frames after the Appearance " +
                "screen opens",
        )
    }

    // -- the switch on the Appearance screen -------------------------------------------------------

    @Test fun `the switch sits right after the sender-initials one, with its own two strings`() {
        val section = messageListSection()
        val title = section.indexOfFirst { it == EXPECTED_SWITCH[1] }
        assertTrue(
            "the 'Message list' section of AppearanceScreen must contain a SettingSwitch reading " +
                "settings_bold_unread_folders_* — the setting is otherwise stored, read and " +
                "unreachable. Section was:\n" + section.joinToString("\n"),
            title > 0,
        )
        val expected = SENDER_INITIALS_SWITCH + EXPECTED_SWITCH
        val from = title - 1 - SENDER_INITIALS_SWITCH.size
        val found = if (from < 0) emptyList() else section.subList(from, minOf(from + expected.size, section.size))
        assertEquals(
            "the switch is pinned whole, line by line, directly under the sender-initials switch: " +
                "its two string keys, the state it shows and the setter it calls.",
            expected,
            found,
        )
    }

    @Test fun `the switch is neither greyed out nor hidden behind a condition`() {
        val raw = SETTINGS_SCREEN_RAW
        val title = raw.indexOfFirst { it.trim() == EXPECTED_SWITCH[1] }
        check(title > 0) { "no '${EXPECTED_SWITCH[1]}' in SettingsScreen.kt" }
        val start = (title - 1 downTo 0).first { raw[it].trim() == "SettingSwitch(" }
        val block = raw.subList(start, minOf(start + EXPECTED_SWITCH.size, raw.size)).map { it.trim() }
        assertTrue(
            "the switch must carry no 'enabled =' argument and no 'if (': dimmed or hidden while " +
                "the value it stands for is still in force, the screen lies. Block was:\n" +
                block.joinToString("\n"),
            block.none { "enabled =" in it } && block.none { "if (" in it },
        )
        val indent = raw[start].indentWidth()
        val enclosing = (start - 1 downTo 0).map { raw[it] }
            .firstOrNull { it.isNotBlank() && it.indentWidth() < indent }
            ?: error("the switch is at the top level of SettingsScreen.kt")
        assertTrue(
            "the line that wraps the switch must not be a condition, or the row disappears for " +
                "whoever fails it. Enclosing line was:\n$enclosing",
            "if (" !in enclosing && "when (" !in enclosing,
        )
    }

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "AppearanceScreen must read the state through the view model, the whole line.",
            listOf("val boldUnreadFolders by viewModel.boldUnreadFolders.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val boldUnreadFolders by") },
        )
    }

    // -- the read and the write path ---------------------------------------------------------------

    @Test fun `the flow is fed by this setting's own decision function`() {
        assertEquals(
            "SettingsRepository.kt must expose the setting as " +
                "'val boldUnreadFolders: Flow<Boolean> = dataStore.data.map(::boldUnreadFoldersFrom)' " +
                "— the function reference included. It is the only link between the stored key and " +
                "the app, and a neighbouring function (::listMonogramFrom is one identifier away) " +
                "makes the sender-initials switch drive the drawer.",
            listOf("val boldUnreadFolders: Flow<Boolean> = dataStore.data.map(::boldUnreadFoldersFrom)"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("val boldUnreadFolders") },
        )
    }

    @Test fun `flipping the switch stores what the user chose, not a constant`() {
        assertEquals(
            "SettingsRepository.kt must write the argument under this setting's key, on one line " +
                "only. The body under its signature is pinned by SettingsWriteWiringTest.",
            listOf("dataStore.edit { it[KEY_BOLD_UNREAD_FOLDERS] = enabled }"),
            codeLines(SETTINGS_REPOSITORY).filter { "it[KEY_BOLD_UNREAD_FOLDERS]" in it },
        )
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "fun setBoldUnreadFolders(enabled: Boolean) {",
                "viewModelScope.launch { settings.setBoldUnreadFolders(enabled) }",
                "}",
            ),
            "the view model's setBoldUnreadFolders",
            "the switch would flip on screen and spring back at the next emission",
        )
    }

    // -- the backup --------------------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'boldUnreadFolders = boldUnreadFolders.first(),' — without " +
                "it the switch is absent from every export.",
            listOf("boldUnreadFolders = boldUnreadFolders.first(),"),
            repository.filter { it.startsWith("boldUnreadFolders = boldUnreadFolders") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.boldUnreadFolders?.let { setBoldUnreadFolders(it) }'. " +
                "The '?.let' matters: an absent field means 'leave as is', not 'off'.",
            listOf("backup.boldUnreadFolders?.let { setBoldUnreadFolders(it) }"),
            repository.filter { it.startsWith("backup.boldUnreadFolders") },
        )
    }

    // -- reading the sources -----------------------------------------------------------------------

    private fun String.indentWidth() = length - trimStart().length

    /** The body of the 'Message list' section and only it, closed by counting braces. */
    private fun messageListSection(): List<String> {
        val lines = codeLines(SETTINGS_SCREEN)
        val opener = "SettingsSection(stringResource(R.string.settings_message_list_section)) {"
        val at = lines.indexOfFirst { it == opener }
        check(at >= 0) { "no '$opener' in SettingsScreen.kt: teach this lint the new shape" }
        var depth = 1
        val body = mutableListOf<String>()
        for (line in lines.drop(at + 1)) {
            depth += line.count { it == '{' } - line.count { it == '}' }
            if (depth <= 0) return body
            body += line
        }
        error("the 'Message list' section is never closed in SettingsScreen.kt")
    }

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String, cost: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        val found = if (at < 0) emptyList() else lines.subList(at, minOf(at + expected.size, lines.size))
        assertEquals(
            "$what is pinned whole, line by line and in order, in ${file.name}. What the user " +
                "would see if this drifted: $cost.",
            expected,
            found,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        const val EXPECTED_LABEL =
            "label = { Text(label, fontWeight = if (folderLabelBold(unread, boldUnreadFolders)) FontWeight.Bold else null) },"
        const val EXPECTED_DRAWER_READ = "val boldUnreadFolders = LocalBoldUnreadFolders.current"
        const val EXPECTED_ACTIVITY_COLLECT =
            "val boldUnreadFolders by settings.boldUnreadFolders.collectAsState(initial = BOLD_UNREAD_FOLDERS_DEFAULT)"
        const val EXPECTED_ACTIVITY_PROVIDES = "LocalBoldUnreadFolders provides boldUnreadFolders,"
        const val EXPECTED_LOCAL =
            "val LocalBoldUnreadFolders = compositionLocalOf { BOLD_UNREAD_FOLDERS_DEFAULT }"

        val SENDER_INITIALS_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_list_monogram_title),",
            "subtitle = stringResource(R.string.settings_list_monogram_subtitle),",
            "checked = listMonogram,",
            "onCheckedChange = viewModel::setListMonogram,",
            ")",
        )

        val EXPECTED_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_bold_unread_folders_title),",
            "subtitle = stringResource(R.string.settings_bold_unread_folders_subtitle),",
            "checked = boldUnreadFolders,",
            "onCheckedChange = viewModel::setBoldUnreadFolders,",
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
        val INBOX_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/inbox/InboxScreen.kt")
        }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }

        /** SettingsScreen.kt with its indentation intact, comment-only lines dropped: indentation
         *  is the only thing that says what wraps what. */
        val SETTINGS_SCREEN_RAW: List<String> by lazy {
            SETTINGS_SCREEN.readLines().filterNot {
                val code = it.trimStart()
                code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")
            }
        }
    }
}

package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source and resource lint, not a behaviour test: it reads seven source files and the eleven string
 * files as text and proves nothing about what is drawn. Same instrument as
 * [BoldUnreadFoldersWiringTest], whose shape it copies, for the switch that adds a Starred entry
 * to the folder drawer (#255).
 *
 * The decision is run elsewhere: `SettingsBackupStarredInDrawerTest` executes
 * `starredInDrawerFrom`, the default, the key and the backup field. The entry the switch reveals
 * and the search it opens are pinned by `StarredDrawerEntryWiringLintTest`. This file pins what
 * nothing in this module can execute: `MainActivity`, the CompositionLocal, the view model, the
 * Appearance screen, the search index and the two strings.
 *
 * Holes (CONTRIBUTING.md, Tests):
 *  1. `starredInDrawer` is a name wherever it is read. It is tied to the stored key by the flow
 *     line, to the CompositionLocal by the activity's two lines, and to the drawer by the read
 *     pinned in `StarredDrawerEntryWiringLintTest`; nothing ties those three together beyond the
 *     spelling.
 *  2. Every positive rule compares whole lines, so the expression around a call is held on that
 *     line and nowhere else.
 *  3. The switch is pinned directly under the bold-folders one, inside the 'Message list' section.
 *     The activity's two lines are not pinned in place: provided outside `CompositionLocalProvider`
 *     they would not compile, collected outside `setContent` neither.
 *  4. Nothing here holds the value a composable collects.
 *
 * What it does not see: whether a title fits its row in any language, and whether a translation
 * says what the English says. The wording rule only checks that each language names the entry by
 * the word its own Starred filter chip carries.
 */
class StarredInDrawerWiringTest {

    // -- the default, in its copies ----------------------------------------------------------------

    @Test fun `the repository states the default once, off, and reads the key against it`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "SettingsRepository.kt must declare 'const val STARRED_IN_DRAWER_DEFAULT = false', the " +
                "one definition every other copy reads. On by default, the drawer of every " +
                "install grows an entry nobody asked for.",
            listOf("const val STARRED_IN_DRAWER_DEFAULT = false"),
            repository.filter { "STARRED_IN_DRAWER_DEFAULT =" in it },
        )
        assertEquals(
            "the key must be named on three lines only: where it is declared, where the setter " +
                "writes the argument under it, and where the reader falls back to the default.",
            listOf(
                "dataStore.edit { it[KEY_STARRED_IN_DRAWER] = enabled }",
                "internal val KEY_STARRED_IN_DRAWER = booleanPreferencesKey(\"starred_in_drawer\")",
                "prefs[KEY_STARRED_IN_DRAWER] ?: STARRED_IN_DRAWER_DEFAULT",
            ),
            repository.filter { "KEY_STARRED_IN_DRAWER" in it }.sorted(),
        )
    }

    @Test fun `the flow is fed by this setting's own decision function`() {
        assertEquals(
            "SettingsRepository.kt must expose the setting through ::starredInDrawerFrom. A " +
                "neighbouring reference (::boldUnreadFoldersFrom is one identifier away) makes " +
                "the bold-folders switch draw the entry.",
            listOf("val starredInDrawer: Flow<Boolean> = dataStore.data.map(::starredInDrawerFrom)"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("val starredInDrawer") },
        )
    }

    @Test fun `the activity's first frame reads the shared default, and provides the setting`() {
        assertEquals(
            "MainActivity.kt must collect the setting as '$EXPECTED_ACTIVITY_COLLECT', the whole line.",
            listOf(EXPECTED_ACTIVITY_COLLECT),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("val starredInDrawer by") },
        )
        assertEquals(
            "MainActivity.kt must hand it down through '$EXPECTED_ACTIVITY_PROVIDES'. Collected " +
                "and never provided, the drawer falls back to the CompositionLocal's default and " +
                "the switch does nothing.",
            listOf(EXPECTED_ACTIVITY_PROVIDES),
            codeLines(MAIN_ACTIVITY).filter { "LocalStarredInDrawer" in it && !it.startsWith("import ") },
        )
    }

    @Test fun `the CompositionLocal falls back to the shared default too`() {
        assertEquals(
            listOf("val LocalStarredInDrawer = compositionLocalOf { STARRED_IN_DRAWER_DEFAULT }"),
            codeLines(LOCAL_LIST_DENSITY).filter { "LocalStarredInDrawer" in it },
        )
    }

    /** The negative screen, a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `no copy of the default is spelled out as a boolean`() {
        val files = listOf(MAIN_ACTIVITY, LOCAL_LIST_DENSITY, INBOX_SCREEN, SETTINGS_VIEW_MODEL, SETTINGS_SCREEN)
        val named = files.flatMap { file ->
            codeLines(file)
                .filter { "tarredInDrawer" in it || "STARRED_IN_DRAWER" in it }
                .map { "${file.name}: $it" }
        }
        assertTrue(
            "this rule screens the lines naming the setting outside the repository, and found " +
                "none: it would be true of them by vacuity",
            named.isNotEmpty(),
        )
        assertEquals(
            "no line about this setting may write 'true' or 'false' outside the repository: all " +
                "read STARRED_IN_DRAWER_DEFAULT or the provided value.",
            emptyList<String>(),
            named.filter { Regex("""\b(true|false)\b""").containsMatchIn(it) },
        )
    }

    @Test fun `the settings screen's first frame reads the shared default too`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val starredInDrawer = settings.starredInDrawer.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = STARRED_IN_DRAWER_DEFAULT,",
                ")",
            ),
            "the view model's starredInDrawer state",
        )
    }

    @Test fun `flipping the switch stores what the user chose`() {
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "fun setStarredInDrawer(enabled: Boolean) {",
                "viewModelScope.launch { settings.setStarredInDrawer(enabled) }",
                "}",
            ),
            "the view model's setStarredInDrawer",
        )
    }

    // -- the switch on the Appearance screen -------------------------------------------------------

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            listOf("val starredInDrawer by viewModel.starredInDrawer.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val starredInDrawer by") },
        )
    }

    @Test fun `the switch sits right after the bold-folders one, with its own two strings`() {
        val section = messageListSection()
        val title = section.indexOfFirst { it == EXPECTED_SWITCH[1] }
        val expected = BOLD_FOLDERS_SWITCH + EXPECTED_SWITCH
        val from = title - 1 - BOLD_FOLDERS_SWITCH.size
        assertEquals(
            "the 'Message list' section of AppearanceScreen must hold this switch, pinned whole, " +
                "directly under the bold-folders one: its two string keys, the state it shows " +
                "and the setter it calls. Without it the setting is stored, read and unreachable.",
            expected,
            if (from < 0) emptyList() else section.subList(from, minOf(from + expected.size, section.size)),
        )
    }

    @Test fun `the switch is neither greyed out nor hidden behind a condition`() {
        val raw = SETTINGS_SCREEN_RAW
        val title = raw.indexOfFirst { it.trim() == EXPECTED_SWITCH[1] }
        assertTrue("no '${EXPECTED_SWITCH[1]}' in SettingsScreen.kt", title > 0)
        val start = (title - 1 downTo 0).first { raw[it].trim() == "SettingSwitch(" }
        val block = raw.subList(start, minOf(start + EXPECTED_SWITCH.size, raw.size)).map { it.trim() }
        assertTrue(
            "the switch must carry no 'enabled =' argument and no 'if (': the entry it stands " +
                "for is drawn on every account set. Block was:\n" + block.joinToString("\n"),
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

    // -- the search index --------------------------------------------------------------------------

    @Test fun `the settings search finds the switch on the Appearance screen`() {
        val index = codeLines(SETTINGS_INDEX)
        val at = index.indexOfFirst { it == EXPECTED_INDEX[3] }
        assertEquals(
            "SettingsIndex.kt must list the switch under APPEARANCE, right after the bold-folders " +
                "entry, with its own title and subtitle.",
            EXPECTED_INDEX,
            if (at < 3) emptyList() else index.subList(at - 3, minOf(at - 3 + EXPECTED_INDEX.size, index.size)),
        )
    }

    // -- the backup --------------------------------------------------------------------------------

    @Test fun `the setting is exported and restored like every other preference`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            "snapshotBackup must carry 'starredInDrawer = starredInDrawer.first(),' or the switch " +
                "is absent from every export.",
            listOf("starredInDrawer = starredInDrawer.first(),"),
            repository.filter { it.startsWith("starredInDrawer = starredInDrawer") },
        )
        assertEquals(
            "restoreBackup must apply it. The '?.let' matters: an absent field means 'leave as " +
                "is', not 'off'.",
            listOf("backup.starredInDrawer?.let { setStarredInDrawer(it) }"),
            repository.filter { it.startsWith("backup.starredInDrawer") },
        )
        assertEquals(
            listOf("val starredInDrawer: Boolean? = null,"),
            codeLines(SETTINGS_BACKUP).filter { it.startsWith("val starredInDrawer") },
        )
    }

    // -- the two strings ---------------------------------------------------------------------------

    @Test fun `every language names the entry by the word of its own Starred chip`() {
        val files = stringFiles()
        assertTrue("only ${files.size} string files found; the app ships ten", files.size >= 10)
        val wrong = files.flatMap { file ->
            val locale = file.parentFile.name
            val text = file.readText()
            val chip = stringOf(text, "filter_starred") ?: return@flatMap listOf("$locale: no filter_starred")
            listOf("settings_starred_in_drawer_title", "settings_starred_in_drawer_subtitle").mapNotNull { key ->
                val value = stringOf(text, key)
                when {
                    value == null -> "$locale: no $key"
                    chip !in value -> "$locale: $key does not name the entry '$chip'"
                    else -> null
                }
            }
        }
        assertEquals(
            "the switch must name the entry exactly as the drawer draws it, which is the label " +
                "of the Starred filter chip in that language: a second word for it sends the " +
                "reader looking for an entry that is not there.",
            emptyList<String>(),
            wrong,
        )
    }

    @Test fun `no translation is the English text left in place`() {
        val english = File(root, "$RES/values/strings.xml").readText()
        val copied = stringFiles().filter { it.parentFile.name != "values" }.flatMap { file ->
            val text = file.readText()
            listOf("settings_starred_in_drawer_title", "settings_starred_in_drawer_subtitle")
                .filter { stringOf(text, it) == stringOf(english, it) }
                .map { "${file.parentFile.name}: $it" }
        }
        assertEquals(emptyList<String>(), copied)
    }

    /** A negative screen: what the two English strings must not promise. */
    @Test fun `the English text promises a search, not a folder nor a complete list`() {
        val english = File(root, "$RES/values/strings.xml").readText()
        val shown = listOf("settings_starred_in_drawer_title", "settings_starred_in_drawer_subtitle")
            .map { stringOf(english, it).orEmpty() }
        assertTrue("the subtitle must say the entry opens a search: $shown", "search" in shown[1])
        assertTrue("the subtitle must say the search asks the server: $shown", "server" in shown[1])
        val banned = listOf("Starred folder", "server only", "starred conversations", "all your starred")
        assertEquals(
            "the entry opens a search that asks the server; it is not a folder, it is not limited " +
                "to the server, and it lists messages, not conversations, with no promise of all " +
                "of them.",
            emptyList<String>(),
            banned.filter { ban -> shown.any { ban in it } },
        )
    }

    // -- reading the sources -----------------------------------------------------------------------

    private fun String.indentWidth() = length - trimStart().length

    private fun stringOf(text: String, key: String): String? =
        Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)

    private fun stringFiles(): List<File> = (File(root, RES).listFiles() ?: emptyArray())
        .filter { it.isDirectory && (it.name == "values" || it.name.startsWith("values-")) }
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .sortedBy { it.parentFile.name }

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
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        assertEquals(
            "$what is pinned whole, line by line and in order, in ${file.name}.",
            expected,
            if (at < 0) emptyList() else lines.subList(at, minOf(at + expected.size, lines.size)),
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        const val EXPECTED_ACTIVITY_COLLECT =
            "val starredInDrawer by settings.starredInDrawer.collectAsState(initial = STARRED_IN_DRAWER_DEFAULT)"
        const val EXPECTED_ACTIVITY_PROVIDES = "LocalStarredInDrawer provides starredInDrawer,"

        val BOLD_FOLDERS_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_bold_unread_folders_title),",
            "subtitle = stringResource(R.string.settings_bold_unread_folders_subtitle),",
            "checked = boldUnreadFolders,",
            "onCheckedChange = viewModel::setBoldUnreadFolders,",
            ")",
        )

        val EXPECTED_SWITCH = listOf(
            "SettingSwitch(",
            "title = stringResource(R.string.settings_starred_in_drawer_title),",
            "subtitle = stringResource(R.string.settings_starred_in_drawer_subtitle),",
            "checked = starredInDrawer,",
            "onCheckedChange = viewModel::setStarredInDrawer,",
            ")",
        )

        val EXPECTED_INDEX = listOf(
            "R.string.settings_bold_unread_folders_subtitle,",
            "),",
            "SettingEntry(",
            "\"starred_in_drawer\", APPEARANCE, R.string.settings_starred_in_drawer_title,",
            "R.string.settings_starred_in_drawer_subtitle,",
            "),",
        )

        const val RES = "app/src/main/res"
        private const val SETTINGS = "core/data/src/main/kotlin/app/sterna/core/data/settings"
        private const val APP = "app/src/main/kotlin/app/sterna"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "$SETTINGS/SettingsRepository.kt").isFile }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, "$SETTINGS/SettingsRepository.kt") }
        val SETTINGS_BACKUP: File by lazy { File(root, "$SETTINGS/SettingsBackup.kt") }
        val MAIN_ACTIVITY: File by lazy { File(root, "$APP/MainActivity.kt") }
        val LOCAL_LIST_DENSITY: File by lazy { File(root, "$APP/ui/components/LocalListDensity.kt") }
        val INBOX_SCREEN: File by lazy { File(root, "$APP/ui/inbox/InboxScreen.kt") }
        val SETTINGS_VIEW_MODEL: File by lazy { File(root, "$APP/ui/settings/SettingsViewModel.kt") }
        val SETTINGS_SCREEN: File by lazy { File(root, "$APP/ui/settings/SettingsScreen.kt") }
        val SETTINGS_INDEX: File by lazy { File(root, "$APP/ui/settings/SettingsIndex.kt") }

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

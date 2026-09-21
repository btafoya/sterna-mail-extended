package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads three source files as text and proves nothing about
 * what runs. `SettingsBackupReadingPaneTest` executes the decisions (`readingPaneModeFrom`,
 * `readingPaneModeOrNull`); what nothing builds is the flow, the backup lines, the view model and
 * the row that spend them, so those are pinned here as whole lines, as for the neighbouring
 * settings in `NotificationContentBackupWiringTest` and `PlainTextReadingWiringTest`.
 *
 * Holes from `CONTRIBUTING.md` that apply: (1) `selected = readingPaneMode` names a state whose
 * binding is pinned only as the line that collects it; (3) nothing here says when those lines run;
 * (4) nothing here can hold the value the screen actually collects. Hole (2) is closed by
 * comparing whole lines and the whole row.
 */
class ReadingPaneModeWiringTest {

    // -- the stored setting -----------------------------------------------------------------------

    @Test fun `the flow is fed by this setting's own decision function`() {
        assertEquals(
            "SettingsRepository.kt must expose the setting as " +
                "'val readingPaneMode: Flow<ReadingPaneMode> = dataStore.data.map(::readingPaneModeFrom)', " +
                "the whole line, the function reference included. It is the only link between the " +
                "stored key and the app, and no test builds a SettingsRepository. Another decision " +
                "function here leaves the choice springing back by itself.",
            listOf("val readingPaneMode: Flow<ReadingPaneMode> = dataStore.data.map(::readingPaneModeFrom)"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("val readingPaneMode") },
        )
    }

    @Test fun `picking a position stores what the user chose, not a constant`() {
        assertEquals(
            "SettingsViewModel.kt must pass the argument through: " +
                "'viewModelScope.launch { settings.setReadingPaneMode(mode) }'. A constant here " +
                "makes the setting unsettable: the tick moves on screen and springs back.",
            listOf("viewModelScope.launch { settings.setReadingPaneMode(mode) }"),
            codeLines(SETTINGS_VIEW_MODEL).filter { "settings.setReadingPaneMode(" in it },
        )
    }

    // -- the backup, both ways --------------------------------------------------------------------

    @Test fun `the position is exported like every other preference`() {
        assertEquals(
            "snapshotBackup must carry 'readingPaneMode = readingPaneMode.first().name,'. Without " +
                "it the position is absent from every export, and a restore on a new device " +
                "silently puts the message back beside the list for a reader who had refused it.",
            listOf("readingPaneMode = readingPaneMode.first().name,"),
            codeLines(SETTINGS_REPOSITORY).filter { it.startsWith("readingPaneMode = readingPaneMode") },
        )
    }

    @Test fun `the import applies the position it read, and nothing else`() {
        assertEquals(
            "restoreBackup must apply exactly " +
                "'readingPaneModeOrNull(backup.readingPaneMode)?.let { setReadingPaneMode(it) }'. " +
                "No line at all means the position is exported forever and never read back; a " +
                "constant in the call makes every import impose it; reading the field without " +
                "readingPaneModeOrNull lets a name from a later version throw mid-restore. An " +
                "empty list below means this lint matched nothing, which is itself the failure.",
            listOf("readingPaneModeOrNull(backup.readingPaneMode)?.let { setReadingPaneMode(it) }"),
            codeLines(SETTINGS_REPOSITORY).filter { "backup.readingPaneMode" in it || "setReadingPaneMode(it)" in it },
        )
    }

    // -- the settings screen ----------------------------------------------------------------------

    @Test fun `the screen collects the setting from the view model`() {
        assertEquals(
            "AppearanceScreen must read the state through " +
                "'val readingPaneMode by viewModel.readingPaneMode.collectAsStateWithLifecycle()', " +
                "the whole line, like every other setting on the screen.",
            listOf("val readingPaneMode by viewModel.readingPaneMode.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val readingPaneMode") },
        )
    }

    @Test fun `the row offers the three positions and hands the pick to the view model`() {
        val lines = codeLines(SETTINGS_SCREEN)
        val titles = lines.indices.filter { lines[it] == EXPECTED_ROW[1] }
        assertEquals(
            "${titles.size} code lines in SettingsScreen.kt read '${EXPECTED_ROW[1]}'; this rule " +
                "anchors on exactly one.",
            1,
            titles.size,
        )
        val at = titles.single() - 1
        val found = lines.subList(at, minOf(at + EXPECTED_ROW.size, lines.size))
        val mismatches = EXPECTED_ROW.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == EXPECTED_ROW[i]) null
            else "line ${i + 1} of the row: expected '${EXPECTED_ROW[i]}' but found '$actual'"
        }
        assertEquals(
            "the row is pinned whole, line by line and in order: a position missing from 'options' " +
                "cannot be chosen, and an 'onSelect' other than viewModel::setReadingPaneMode " +
                "moves the tick and stores nothing. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val EXPECTED_ROW = listOf(
            "SettingChoiceRow(",
            "title = stringResource(R.string.settings_reading_pane_title),",
            "options = listOf(ReadingPaneMode.ALWAYS, ReadingPaneMode.LANDSCAPE, ReadingPaneMode.NEVER),",
            "selected = readingPaneMode,",
            "optionLabel = { readingPaneLabel(context, it) },",
            "onSelect = viewModel::setReadingPaneMode,",
            ")",
        )

        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory, the rules read both modules. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath}: this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
        val SETTINGS_VIEW_MODEL: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsViewModel.kt")
        }
        val SETTINGS_SCREEN: File by lazy {
            File(root, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt")
        }
    }
}

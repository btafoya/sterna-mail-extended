package app.sterna.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Source lint, not a behaviour test: it reads seven source files as text and proves nothing about
 * what a key press does. It exists for the part of the volume-keys setting (#250) that no JVM test
 * can run: `MainActivity.dispatchKeyEvent`, the composition that registers a message screen, the
 * settings switch and the backup lines. The rule itself is run by `VolumeKeyDecisionTest`, the
 * step and the default by `MessageTextSizeStepTest`.
 *
 * Whole trimmed lines, comment-only lines dropped, never `contains` on a positive rule: a fragment
 * check is blind to anything appended to the line.
 *
 * Of the four holes `CONTRIBUTING.md` lists, all four apply. (1) `volumeTextSize` and
 * `messageShown` are pinned by name where they are handed to `volumeKeyDecision`, and by the line
 * that assigns each; nothing ties the two beyond that. (2) The expressions around the calls are
 * pinned only because the whole line is. (3) `dispatchKeyEvent` is compared as one ordered block,
 * which is as far as position goes; the `DisposableEffect` is pinned as a block but not as the
 * first statement of `MessageScreen`, and one moved into `MessagePage` with the same five lines
 * stays green while a page leaving the pager unregisters the screen. (4) Nothing here holds the
 * value the activity's field carries when a key arrives.
 *
 * What it does not see: whether the platform delivers a volume key to `dispatchKeyEvent` before
 * the system takes it, whether a dialog or a sheet over the message keeps the keys for its own
 * window, and whether the text is redrawn. Those are bench questions.
 */
class VolumeTextSizeWiringTest {

    // -- 1. the default ---------------------------------------------------------------------------

    @Test fun `the default is stated once, off, and every waiting value names it`() {
        assertEquals(
            listOf("const val VOLUME_TEXT_SIZE_DEFAULT = false"),
            codeLines(VOLUME_TEXT_SIZE).filter { "VOLUME_TEXT_SIZE_DEFAULT =" in it },
        )
        assertEquals(
            "MainActivity.kt must start its copy on the shared constant. Until the stored value " +
                "is read, this field decides whether a volume press is swallowed.",
            listOf("private var volumeTextSize = VOLUME_TEXT_SIZE_DEFAULT"),
            codeLines(MAIN_ACTIVITY).filter { it.startsWith("private var volumeTextSize") },
        )
        assertBlock(
            SETTINGS_VIEW_MODEL,
            listOf(
                "val volumeTextSize = settings.volumeTextSize.stateIn(",
                "scope = viewModelScope,",
                "started = SharingStarted.WhileSubscribed(5_000),",
                "initialValue = VOLUME_TEXT_SIZE_DEFAULT,",
                ")",
            ),
            "the view model's volumeTextSize state",
        )
    }

    /** The negative screen, a `contains`, deliberately: it looks for what must be absent. */
    @Test fun `no copy of the default is spelled out as a boolean`() {
        val offenders = listOf(MAIN_ACTIVITY, SETTINGS_VIEW_MODEL).flatMap { file ->
            codeLines(file)
                .filter { "volumeTextSize" in it || "VOLUME_TEXT_SIZE" in it }
                .filter { Regex("""\b(true|false)\b""").containsMatchIn(it) }
                .map { "${file.name}: $it" }
        }
        assertEquals(
            "no line about this setting may write 'true' or 'false' outside the repository: a " +
                "literal that agrees with VOLUME_TEXT_SIZE_DEFAULT today is the one that silently " +
                "disagrees with it tomorrow. Found:\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    // -- 2, 3, 4. the activity's door --------------------------------------------------------------

    @Test fun `the activity names the two volume keys and no other key`() {
        assertEquals(
            "MainActivity.kt may name exactly two key codes, on the one line that sends every " +
                "other key to super before anything is decided. A third code here is a key the " +
                "app takes from the system for the whole process: mute, back, the media keys.",
            listOf(EXPECTED_DOOR[1]),
            codeLines(MAIN_ACTIVITY).filter { "KEYCODE_" in it },
        )
    }

    @Test fun `a key the rule does not consume goes to super`() {
        assertEquals(
            "the unconsumed branch must be 'return super.dispatchKeyEvent(event)'. 'return false' " +
                "compiles and silences both volume keys everywhere the setting is off, which is " +
                "every install by default.",
            listOf(EXPECTED_DOOR[6]),
            codeLines(MAIN_ACTIVITY).filter { "decision.consumed" in it },
        )
    }

    @Test fun `only the first down steps, and the activity hands the repeat count over raw`() {
        assertEquals(
            "VolumeTextSizeKeys.kt must read repeatCount on the step line and nowhere else. " +
                "Without '&& repeatCount == 0' a held key steps on every repeat.",
            listOf(
                "repeatCount: Int,",
                "val step = if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) direction else 0",
            ),
            codeLines(VOLUME_KEYS).filter { "repeatCount" in it },
        )
        assertEquals(
            "the activity must pass event.repeatCount as it is, once, to volumeKeyDecision",
            listOf(EXPECTED_DOOR[5]),
            codeLines(MAIN_ACTIVITY).filter { "repeatCount" in it },
        )
    }

    @Test fun `dispatchKeyEvent is the pinned block, whole and in order`() {
        assertBlock(MAIN_ACTIVITY, EXPECTED_DOOR, "MainActivity.dispatchKeyEvent")
    }

    @Test fun `the activity follows the stored switch and provides its relay`() {
        assertEquals(
            listOf("lifecycleScope.launch { settings.volumeTextSize.collect { volumeTextSize = it } }"),
            codeLines(MAIN_ACTIVITY).filter { "settings.volumeTextSize" in it },
        )
        assertEquals(
            "the relay the activity reads must be the one the composition is given",
            listOf(
                "private val volumeTextSizeRelay = VolumeTextSizeRelay()",
                "LocalVolumeTextSizeRelay provides volumeTextSizeRelay,",
                EXPECTED_DOOR[4],
            ),
            codeLines(MAIN_ACTIVITY).filter { "volumeTextSizeRelay" in it },
        )
    }

    // -- the screen that registers ----------------------------------------------------------------

    @Test fun `a message screen registers on entering composition and unregisters on leaving`() {
        assertBlock(
            MESSAGE_SCREEN,
            listOf(
                "val volumeTextSizeRelay = LocalVolumeTextSizeRelay.current",
                "DisposableEffect(volumeTextSizeRelay) {",
                "volumeTextSizeRelay?.enter()",
                "onDispose { volumeTextSizeRelay?.leave() }",
                "}",
            ),
            "MessageScreen's registration with the volume relay",
        )
        assertEquals(
            "exactly one registration in MessageScreen.kt: a second one, in a pager page, comes " +
                "and goes with the neighbours of the message being read",
            listOf("volumeTextSizeRelay?.enter()", "onDispose { volumeTextSizeRelay?.leave() }"),
            codeLines(MESSAGE_SCREEN).filter { ".enter()" in it || ".leave()" in it },
        )
    }

    // -- the switch and the backup ----------------------------------------------------------------

    @Test fun `the switch shows the stored value and writes through its own setter`() {
        assertEquals(
            listOf("val volumeTextSize by viewModel.volumeTextSize.collectAsStateWithLifecycle()"),
            codeLines(SETTINGS_SCREEN).filter { it.startsWith("val volumeTextSize by") },
        )
        assertBlock(
            SETTINGS_SCREEN,
            listOf(
                "title = stringResource(R.string.settings_volume_text_size_title),",
                "subtitle = stringResource(R.string.settings_volume_text_size_subtitle),",
                "checked = volumeTextSize,",
                "onCheckedChange = viewModel::setVolumeTextSize,",
                ")",
            ),
            "the volume-keys switch",
        )
        assertEquals(
            listOf("viewModelScope.launch { settings.setVolumeTextSize(enabled) }"),
            codeLines(SETTINGS_VIEW_MODEL).filter { "settings.setVolumeTextSize(" in it },
        )
    }

    @Test fun `the setting is exported and restored`() {
        val repository = codeLines(SETTINGS_REPOSITORY)
        assertEquals(
            listOf("volumeTextSize = volumeTextSize.first(),"),
            repository.filter { it.startsWith("volumeTextSize = volumeTextSize") },
        )
        assertEquals(
            "restoreBackup must apply it: 'backup.volumeTextSize?.let { setVolumeTextSize(it) }'. " +
                "Exported and never read back, the switch is lost on every import.",
            listOf("backup.volumeTextSize?.let { setVolumeTextSize(it) }"),
            repository.filter { it.startsWith("backup.volumeTextSize") },
        )
    }

    // -- reading the sources ----------------------------------------------------------------------

    /** Locates [expected]'s first line in [file] and compares the block that follows it, whole. */
    private fun assertBlock(file: File, expected: List<String>, what: String) {
        val lines = codeLines(file)
        val at = lines.indexOfFirst { it == expected.first() }
        check(at >= 0) { "no '${expected.first()}' in ${file.name} — $what is gone" }
        val found = lines.subList(at, minOf(at + expected.size, lines.size))
        val mismatches = expected.indices.mapNotNull { i ->
            val actual = found.getOrNull(i)
            if (actual == expected[i]) null
            else "line ${i + 1} of $what: expected '${expected[i]}' but found '$actual'"
        }
        assertEquals(
            "$what is pinned whole, line by line and in order. Nothing in this repo executes " +
                "these lines. Mismatches:\n" + mismatches.joinToString("\n"),
            emptyList<String>(),
            mismatches,
        )
    }

    /** [file]'s lines, trimmed, comment-only lines dropped so no rule can be satisfied by prose. */
    private fun codeLines(file: File): List<String> = file.readLines().map { it.trim() }.filterNot {
        it.isEmpty() || it.startsWith("//") || it.startsWith("*") || it.startsWith("/*")
    }

    private companion object {
        val EXPECTED_DOOR = listOf(
            "override fun dispatchKeyEvent(event: KeyEvent): Boolean {",
            "if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {",
            "return super.dispatchKeyEvent(event)",
            "}",
            "val messageShown = volumeTextSizeRelay.messageShown && !application.container.appLock.locked.value",
            "val decision = volumeKeyDecision(event.action, event.keyCode, event.repeatCount, volumeTextSize, messageShown)",
            "if (!decision.consumed) return super.dispatchKeyEvent(event)",
            "if (decision.step != 0) {",
            "lifecycleScope.launch { application.container.settingsRepository.stepMessageTextSize(decision.step) }",
            "}",
            "return true",
            "}",
        )

        private const val SETTINGS_REPOSITORY_PATH =
            "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"

        /** Repo root, walked up from the module's working directory, the rules read both modules. */
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, SETTINGS_REPOSITORY_PATH).isFile }
                ?: error(
                    "cannot locate the repo root from ${File("").absolutePath} — this lint reads " +
                        "the sources as text and needs a working directory inside the checkout",
                )
        }

        private const val APP = "app/src/main/kotlin/app/sterna"
        val SETTINGS_REPOSITORY: File by lazy { File(root, SETTINGS_REPOSITORY_PATH) }
        val VOLUME_TEXT_SIZE: File by lazy {
            File(root, "core/data/src/main/kotlin/app/sterna/core/data/settings/VolumeTextSize.kt")
        }
        val MAIN_ACTIVITY: File by lazy { File(root, "$APP/MainActivity.kt") }
        val VOLUME_KEYS: File by lazy { File(root, "$APP/ui/message/VolumeTextSizeKeys.kt") }
        val MESSAGE_SCREEN: File by lazy { File(root, "$APP/ui/message/MessageScreen.kt") }
        val SETTINGS_VIEW_MODEL: File by lazy { File(root, "$APP/ui/settings/SettingsViewModel.kt") }
        val SETTINGS_SCREEN: File by lazy { File(root, "$APP/ui/settings/SettingsScreen.kt") }
    }
}

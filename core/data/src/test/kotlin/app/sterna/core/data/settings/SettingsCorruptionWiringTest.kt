package app.sterna.core.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Reads the shipped `SettingsRepository.kt` and compares whole trimmed lines. The recovery itself is
 * executed by `SettingsCorruptionRecoveryTest`, on `settingsCorruptionHandler`; what no JVM test can
 * reach is the `Context` delegate, so only this file notices the handler being dropped from it.
 */
class SettingsCorruptionWiringTest {

    @Test fun `the one settings DataStore is declared with the named corruption handler`() {
        assertEquals(
            "the settings DataStore no longer passes settingsCorruptionHandler: an unreadable " +
                "settings.preferences_pb then kills the app at its first read, and the only way out " +
                "is clearing its data, accounts included.",
            listOf(DECLARATION),
            codeLinesNaming("preferencesDataStore("),
        )
    }

    @Test fun `the named handler replaces an unreadable file with the quietest notification content`() {
        assertEquals(
            "settingsCorruptionHandler no longer answers NONE for the notification content: after a " +
                "reset, a user set to 'Sender only' or 'None' gets the subject on the lock screen.",
            listOf(
                "internal val settingsCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> = " +
                    "ReplaceFileCorruptionHandler { preferencesOf(KEY_NOTIFICATION_CONTENT to " +
                    "NotificationContent.NONE.name) }",
            ),
            codeLinesNaming("settingsCorruptionHandler:"),
        )
    }

    @Test fun `the repository reads the DataStore that carries the handler`() {
        assertEquals(
            "SettingsRepository no longer reads Context.settingsDataStore: whatever store it builds " +
                "instead has no corruption handler, and an unreadable file kills the app again.",
            listOf("private val dataStore = context.applicationContext.settingsDataStore"),
            codeLinesNaming("val dataStore"),
        )
    }

    @Test fun `no other main source declares a DataStore`() {
        val root = repoRoot()
        val mains = listOf(File(root, "app/src/main")) +
            (File(root, "core").listFiles() ?: emptyArray()).filter { it.isDirectory }
                .map { File(it, "src/main") }
        val declarations = mains.filter { it.isDirectory }
            .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }
            .sortedBy { it.path }
            .flatMap { file ->
                codeLines(file)
                    .filter { "preferencesDataStore(" in it || "DataStoreFactory.create" in it }
                    .map { "${file.relativeTo(root).invariantSeparatorsPath}: $it" }
            }
        assertEquals(
            "a DataStore is declared outside the one in SettingsRepository.kt: it does not pass " +
                "settingsCorruptionHandler, and an unreadable file behind it kills the app at its first read.",
            listOf("$REPOSITORY_PATH: $DECLARATION"),
            declarations,
        )
    }

    /** Every trimmed code line of `SettingsRepository.kt` naming [fragment]. The selection is by
     *  fragment, the comparison by whole line, so an appended argument or a second declaration both
     *  show up as a different list. */
    private fun codeLinesNaming(fragment: String): List<String> =
        codeLines(File(repoRoot(), REPOSITORY_PATH)).filter { fragment in it }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private fun repoRoot(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, REPOSITORY_PATH).isFile }
        ?: error("cannot locate the repo root from ${File("").absolutePath}")

    private companion object {
        const val REPOSITORY_PATH = "core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt"
        const val DECLARATION = "private val Context.settingsDataStore: DataStore<Preferences> by " +
            "preferencesDataStore(name = \"settings\", corruptionHandler = settingsCorruptionHandler)"
    }
}

package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The search-history switch: its default, its stored key, its trip through a settings backup, and
 * the one thing that backup must never carry, the searches themselves. The export is a plain JSON
 * file the user puts wherever she likes; her searches go nowhere.
 */
class SettingsBackupSearchHistoryTest {

    @Test fun `searches are remembered until somebody turns it off`() {
        assertTrue(
            "an install where nobody touched the switch must remember searches",
            searchHistoryFrom(emptyPreferences()),
        )
        assertTrue(SEARCH_HISTORY_DEFAULT)
        assertFalse(searchHistoryFrom(preferencesOf(booleanPreferencesKey("search_history") to false)))
        assertTrue(searchHistoryFrom(preferencesOf(booleanPreferencesKey("search_history") to true)))
        // A neighbouring switch must not answer for it.
        assertTrue(searchHistoryFrom(preferencesOf(booleanPreferencesKey("unread_tint") to false)))
    }

    @Test fun `the switch is carried by a backup, off included`() {
        val encoded = SettingsBackupCodec.encode(SettingsBackup(searchHistory = false))
        assertTrue("exported: $encoded", encoded.contains("\"searchHistory\": false"))
        assertEquals(false, SettingsBackupCodec.decode(encoded)?.searchHistory)
    }

    @Test fun `a backup written before the switch existed leaves it alone`() {
        val old = SettingsBackupCodec.decode("""{"version":1,"themeMode":"DARK"}""")!!
        assertNull(old.searchHistory)
    }

    @Test fun `the only search field of a backup is the switch`() {
        val descriptor = SettingsBackup.serializer().descriptor
        val fields = (0 until descriptor.elementsCount).map {
            descriptor.getElementName(it) to descriptor.getElementDescriptor(it)
        }
        assertEquals(
            "a backup field about search that is not the boolean switch is the history leaving " +
                "the phone in a plain JSON file",
            listOf("searchHistory" to "kotlin.Boolean?"),
            fields.filter { "search" in it.first.lowercase() }.map { it.first to it.second.serialName },
        )
        assertEquals(
            "a new list in the backup is where a copy of the searches would sit; if it is " +
                "something else, add it here knowingly",
            listOf("imageAllowlist", "accounts"),
            fields.filter { it.second.kind == StructureKind.LIST }.map { it.first },
        )
    }

    @Test fun `the export reads the switch and restores it, and never opens the history`() {
        val code = codeLines(SETTINGS_REPOSITORY)
        assertEquals(listOf("searchHistory = searchHistory.first(),"), code.filter { it.startsWith("searchHistory =") })
        assertEquals(
            listOf("backup.searchHistory?.let { setSearchHistory(it) }"),
            code.filter { it.startsWith("backup.searchHistory") },
        )
        val leaks = listOf(SETTINGS_REPOSITORY, SETTINGS_BACKUP).flatMap { file ->
            codeLines(file).filter { line -> HISTORY_READERS.any { it in line } }.map { "${file.name}: $it" }
        }
        assertEquals(
            "the settings store and the backup format must not reach the search history at all:",
            emptyList<String>(),
            leaks,
        )
    }

    private fun codeLines(file: File): List<String> = file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private companion object {
        /** What reading the searches would have to name. */
        val HISTORY_READERS = listOf("SearchHistoryRepository", "decodeSearchHistory", "searchHistoryDataStore", "KEY_SEARCHES")

        const val DIR = "core/data/src/main/kotlin/app/sterna/core/data/settings"

        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, DIR).isDirectory }
                ?: error("cannot locate the repo root from ${File("").absolutePath}")
        }
        val SETTINGS_REPOSITORY: File by lazy { File(root, "$DIR/SettingsRepository.kt") }
        val SETTINGS_BACKUP: File by lazy { File(root, "$DIR/SettingsBackup.kt") }
    }
}

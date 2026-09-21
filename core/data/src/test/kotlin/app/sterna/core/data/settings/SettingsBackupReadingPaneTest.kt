package app.sterna.core.data.settings

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading-pane position (#228): its default, its stored key, and its trip through an export.
 * `snapshotBackup`/`restoreBackup` need a real DataStore; what they spend is executed here instead:
 * [readingPaneModeFrom] is what the snapshot reads, [readingPaneModeOrNull] is what the restore
 * applies, and the codec carries the name between them.
 */
class SettingsBackupReadingPaneTest {

    @Test fun everyPositionSurvivesAnExportAndItsImport() {
        for (mode in ReadingPaneMode.entries) {
            val stored = preferencesOf(stringPreferencesKey("reading_pane_mode") to mode.name)
            val exported = SettingsBackupCodec.encode(SettingsBackup(readingPaneMode = readingPaneModeFrom(stored).name))
            val imported = SettingsBackupCodec.decode(exported)
            assertEquals(
                "a device set to $mode must come back as $mode after an export and its import; " +
                    "exported: $exported",
                mode,
                readingPaneModeOrNull(imported?.readingPaneMode),
            )
        }
    }

    @Test fun aBackupWrittenBeforeTheSettingExistedLeavesTheDefault() {
        val old = SettingsBackupCodec.decode("""{"version":1,"themeMode":"DARK","listDensity":"COMPACT"}""")
        assertNull(old?.readingPaneMode)
        assertNull(
            "an absent field restores nothing, the device keeps what it has",
            readingPaneModeOrNull(old?.readingPaneMode),
        )
        assertTrue(old!!.isPlausible())
    }

    @Test fun anUnknownPositionIsSkippedOnImportAndReadAsAlways() {
        assertNull(
            "a position written by a later version must not be applied",
            readingPaneModeOrNull("SIDEWAYS"),
        )
        assertEquals(
            "an unreadable stored position must read as the default, the pane beside the list",
            ReadingPaneMode.ALWAYS,
            readingPaneModeFrom(preferencesOf(stringPreferencesKey("reading_pane_mode") to "SIDEWAYS")),
        )
    }

    @Test fun aDeviceNobodyConfiguredKeepsThePaneBesideTheList() {
        assertEquals(ReadingPaneMode.ALWAYS, readingPaneModeFrom(emptyPreferences()))
    }

    @Test fun thePositionIsReadBackFromTheKeyItWasWrittenTo() {
        // Spelled out rather than imported: renaming the key drops every stored choice.
        assertEquals(
            ReadingPaneMode.NEVER,
            readingPaneModeFrom(preferencesOf(stringPreferencesKey("reading_pane_mode") to "NEVER")),
        )
        assertEquals(
            "a neighbouring enum setting must not answer for it",
            ReadingPaneMode.ALWAYS,
            readingPaneModeFrom(preferencesOf(stringPreferencesKey("list_density") to "NEVER")),
        )
    }
}

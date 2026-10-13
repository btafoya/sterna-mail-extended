package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch that adds a Starred entry to the folder drawer (#255): its default, its stored key,
 * and its trip through a backup. The decision is run ([starredInDrawerFrom]); what reads it on
 * screen is pinned by `StarredInDrawerWiringTest`.
 */
class SettingsBackupStarredInDrawerTest {

    @Test fun theSwitchSurvivesABackupFile() {
        // The value, not the key: the codec writes with encodeDefaults, so a field the backup lost
        // is still exported, as null.
        assertTrue(reExported("true"), reExported("true").contains("\"starredInDrawer\": true"))
        assertTrue(reExported("false"), reExported("false").contains("\"starredInDrawer\": false"))
    }

    @Test fun aBackupWrittenBeforeItExistedLeavesTheSwitchAlone() {
        // Absent is "leave as is", which restoreBackup reads as null. A field declared as a plain
        // Boolean would turn the entry off on every import of an older backup.
        val old = checkNotNull(SettingsBackupCodec.decode("""{"version":1,"themeMode":"DARK"}"""))
        assertTrue(old.isPlausible())
        val exported = SettingsBackupCodec.encode(old)
        assertTrue(exported, exported.contains("\"starredInDrawer\": null"))
    }

    @Test fun theDrawerHasNoStarredEntryUntilSomebodyAsksForIt() {
        // Preferences where the key is absent: every existing install on the update that ships this.
        assertFalse(STARRED_IN_DRAWER_DEFAULT)
        assertFalse(starredInDrawerFrom(emptyPreferences()))
        assertTrue(
            "a reader who turned the entry on must find it at the next launch",
            starredInDrawerFrom(preferencesOf(storedKey to true)),
        )
        assertFalse(starredInDrawerFrom(preferencesOf(storedKey to false)))
    }

    @Test fun aNeighbouringSwitchDoesNotAnswerForIt() {
        assertFalse(starredInDrawerFrom(preferencesOf(booleanPreferencesKey("bold_unread_folders") to true)))
        assertFalse(starredInDrawerFrom(preferencesOf(booleanPreferencesKey("list_monogram") to true)))
    }

    private fun reExported(value: String): String = SettingsBackupCodec.encode(
        checkNotNull(SettingsBackupCodec.decode("""{"version":1,"starredInDrawer":$value}""")),
    )

    // Spelled out rather than imported: the key names persisted user data, so renaming it silently
    // takes the entry away from everyone who had turned it on.
    private val storedKey = booleanPreferencesKey("starred_in_drawer")
}

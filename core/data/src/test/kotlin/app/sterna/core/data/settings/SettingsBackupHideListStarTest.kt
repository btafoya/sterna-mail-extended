package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The switch that takes the star off the end of each message-list row: its default, its stored
 * key, and its trip through a backup. The decision is run ([hideListStarFrom]); what reads it on
 * screen is pinned by `HideListStarWiringTest`.
 */
class SettingsBackupHideListStarTest {

    @Test fun theSwitchSurvivesABackupFile() {
        // The value, not the key: the codec writes with encodeDefaults, so a field the backup lost
        // is still exported, as null.
        assertTrue(reExported("true"), reExported("true").contains("\"hideListStar\": true"))
        assertTrue(reExported("false"), reExported("false").contains("\"hideListStar\": false"))
    }

    @Test fun aBackupWrittenBeforeItExistedLeavesTheSwitchAlone() {
        // Absent is "leave as is", which restoreBackup reads as null. A field declared as a plain
        // Boolean would put the star back on every import of an older backup.
        val old = checkNotNull(SettingsBackupCodec.decode("""{"version":1,"themeMode":"DARK"}"""))
        assertTrue(old.isPlausible())
        val exported = SettingsBackupCodec.encode(old)
        assertTrue(exported, exported.contains("\"hideListStar\": null"))
    }

    @Test fun everyRowKeepsItsStarUntilSomebodyAsksToHideIt() {
        // Preferences where the key is absent: every existing install on the update that ships this.
        assertFalse(HIDE_LIST_STAR_DEFAULT)
        assertFalse(hideListStarFrom(emptyPreferences()))
        assertTrue(
            "a reader who hid the star must find it hidden at the next launch",
            hideListStarFrom(preferencesOf(storedKey to true)),
        )
        assertFalse(hideListStarFrom(preferencesOf(storedKey to false)))
    }

    @Test fun aNeighbouringSwitchDoesNotAnswerForIt() {
        assertFalse(hideListStarFrom(preferencesOf(booleanPreferencesKey("starred_in_drawer") to true)))
        assertFalse(hideListStarFrom(preferencesOf(booleanPreferencesKey("list_monogram") to true)))
    }

    private fun reExported(value: String): String = SettingsBackupCodec.encode(
        checkNotNull(SettingsBackupCodec.decode("""{"version":1,"hideListStar":$value}""")),
    )

    // Spelled out rather than imported: the key names persisted user data, so renaming it silently
    // puts the star back for everyone who had hidden it.
    private val storedKey = booleanPreferencesKey("hide_list_star")
}

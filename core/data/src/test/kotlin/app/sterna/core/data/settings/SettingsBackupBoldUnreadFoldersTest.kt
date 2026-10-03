package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bold-unread-folders switch of the folder drawer: its default, its stored key, and its trip
 * through a backup. The decision is run ([boldUnreadFoldersFrom]); what reads it on screen is
 * pinned by `BoldUnreadFoldersWiringTest`.
 */
class SettingsBackupBoldUnreadFoldersTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(boldUnreadFolders = false)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(false, back.boldUnreadFolders)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so a backup written
        // before this setting existed keeps whatever the device has.
        val old = """{"version":1,"themeMode":"DARK","listMonogram":false}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.boldUnreadFolders)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsFalseWhenExplicitlyOff() {
        // The value, not the key: the codec writes with encodeDefaults, so an unset field is
        // exported as `"boldUnreadFolders": null` and a check on the key alone is true of every
        // export, including one that lost the setting.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(boldUnreadFolders = false))
        assertTrue("exported: $encoded", encoded.contains("\"boldUnreadFolders\": false"))
        val on = SettingsBackupCodec.encode(SettingsBackup(boldUnreadFolders = true))
        assertTrue("exported: $on", on.contains("\"boldUnreadFolders\": true"))
    }

    @Test fun foldersWithUnreadAreBoldUntilSomebodyTurnsItOff() {
        // Run on Preferences where the key is absent: the state of every existing install on the
        // update that ships this.
        assertTrue(
            "a drawer nobody has configured must bold its folders showing unread mail",
            boldUnreadFoldersFrom(emptyPreferences()),
        )
        assertTrue(boldUnreadFoldersFrom(preferencesOf(storedKey to true)))
        assertFalse(
            "a reader who turned the bold off must not get it back",
            boldUnreadFoldersFrom(preferencesOf(storedKey to false)),
        )
    }

    @Test fun theSwitchIsReadBackFromTheKeyItWasWrittenTo() {
        // Spelled out rather than imported: the key names persisted user data, so renaming it
        // silently turns the bold back on for everyone who had removed it.
        assertFalse(
            boldUnreadFoldersFrom(preferencesOf(booleanPreferencesKey("bold_unread_folders") to false)),
        )
        // A neighbouring switch must not answer for it.
        assertTrue(boldUnreadFoldersFrom(preferencesOf(booleanPreferencesKey("list_monogram") to false)))
    }

    private val storedKey = booleanPreferencesKey("bold_unread_folders")
}

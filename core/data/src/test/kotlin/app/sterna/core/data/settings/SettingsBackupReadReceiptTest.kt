package app.sterna.core.data.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-receipt switch (#148) must travel in a settings backup like every other preference —
 * the same three checks as [SettingsBackupReplyBarTest], and here they matter more than usual.
 *
 * `AndroidManifest.xml` and `res/xml/backup_rules.xml` exclude `datastore/` from Android's own
 * backup, so this export is the ONLY way a preference survives a reinstall. A reader who turned
 * this on, exported, reinstalled and imported would find it off again with nothing said — and
 * "off" is the position where the app ignores a sender's request entirely, so there is not even a
 * banner to notice the loss by.
 *
 * The direction that matters is the opposite of the reply bar's: the default here is FALSE, so
 * "on" is the answer a backup has to carry.
 */
class SettingsBackupReadReceiptTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun theSwitchSurvivesAJsonRoundTrip() {
        val backup = SettingsBackup(askReadReceipt = true)
        val back = json.decodeFromString(
            SettingsBackup.serializer(),
            json.encodeToString(SettingsBackup.serializer(), backup),
        )
        assertEquals(true, back.askReadReceipt)
    }

    @Test fun aBackupWrittenBeforeItExistedStillImports() {
        // Absent = "leave as is": restoreBackup only applies non-null fields, so importing an older
        // backup leaves the switch where the reader put it rather than turning it off.
        val old = """{"version":1,"themeMode":"DARK","confirmLinks":true}"""
        val backup = json.decodeFromString(SettingsBackup.serializer(), old)
        assertNull(backup.askReadReceipt)
        assertTrue(backup.isPlausible())
    }

    @Test fun itIsCarriedAsTrueWhenExplicitlyOn() {
        // The VALUE, not the key: the codec writes with encodeDefaults and explicitNulls, so an
        // unset field is exported as `"askReadReceipt": null` and the key is always there — a
        // `contains("askReadReceipt")` would be true of an export that had lost the setting.
        val encoded = SettingsBackupCodec.encode(SettingsBackup(askReadReceipt = true))
        assertTrue("exported: $encoded", encoded.contains("\"askReadReceipt\": true"))
    }

    @Test fun aFileCarryingOnlyThisSwitchIsStillARecognisableBackup() {
        // isPlausible() is what stands between "an unrelated JSON file" and "import nothing,
        // silently". A field left out of it is a field that cannot, on its own, prove the file is
        // ours — and the smallest export this app can produce is one someone made after touching
        // exactly one setting.
        assertTrue(SettingsBackup(askReadReceipt = true).isPlausible())
    }
}

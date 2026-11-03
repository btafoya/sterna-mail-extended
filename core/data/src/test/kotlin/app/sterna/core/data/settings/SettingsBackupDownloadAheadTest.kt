package app.sterna.core.data.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "download messages in advance" switch and its period: their defaults, their stored keys, and
 * their trip through a backup. `snapshotBackup`/`restoreBackup` need a real DataStore; what they
 * spend is executed here instead: [downloadAheadFrom] and [downloadAheadPeriodFrom] are what the
 * snapshot reads, [downloadAheadPeriodOrNull] is what the restore applies.
 */
class SettingsBackupDownloadAheadTest {

    @Test fun aDeviceNobodyConfiguredDownloadsInAdvance() {
        assertTrue(DOWNLOAD_AHEAD_DEFAULT)
        assertTrue(
            "the key is absent on every existing install on the update that ships this: it must read as on",
            downloadAheadFrom(emptyPreferences()),
        )
        assertFalse(
            "a reader who turned it off must find it off at the next launch",
            downloadAheadFrom(preferencesOf(switchKey to false)),
        )
        assertTrue(downloadAheadFrom(preferencesOf(switchKey to true)))
    }

    @Test fun anAbsentPeriodReadsAsSevenDays() {
        assertEquals(DownloadAheadPeriod.SEVEN_DAYS, DOWNLOAD_AHEAD_PERIOD_DEFAULT)
        assertEquals(DownloadAheadPeriod.SEVEN_DAYS, downloadAheadPeriodFrom(emptyPreferences()))
    }

    @Test fun anUnreadablePeriodReadsAsSevenDays() {
        assertEquals(
            "a stored name this version does not know, a later version's or a damaged one, must read as the default",
            DownloadAheadPeriod.SEVEN_DAYS,
            downloadAheadPeriodFrom(preferencesOf(periodKey to "garbage")),
        )
        assertEquals(
            DownloadAheadPeriod.SEVEN_DAYS,
            downloadAheadPeriodFrom(preferencesOf(periodKey to "")),
        )
        assertNull("an unknown period must not be applied on import", downloadAheadPeriodOrNull("garbage"))
        assertNull(downloadAheadPeriodOrNull(null))
    }

    @Test fun everyStoredNameReadsBackAsItsOwnPeriod() {
        val expected = mapOf(
            "SEVEN_DAYS" to DownloadAheadPeriod.SEVEN_DAYS,
            "ONE_MONTH" to DownloadAheadPeriod.ONE_MONTH,
            "THREE_MONTHS" to DownloadAheadPeriod.THREE_MONTHS,
            "SIX_MONTHS" to DownloadAheadPeriod.SIX_MONTHS,
        )
        assertEquals(expected.values.toList(), DownloadAheadPeriod.entries.toList())
        for ((name, period) in expected) {
            assertEquals(name, period, downloadAheadPeriodFrom(preferencesOf(periodKey to name)))
        }
    }

    @Test fun eachPeriodSpansItsNumberOfDays() {
        assertEquals(
            listOf(7, 30, 90, 180),
            DownloadAheadPeriod.entries.map { it.days },
        )
    }

    @Test fun aNeighbouringSettingDoesNotAnswerForThem() {
        assertTrue(downloadAheadFrom(preferencesOf(booleanPreferencesKey("starred_in_drawer") to false)))
        assertEquals(
            DownloadAheadPeriod.SEVEN_DAYS,
            downloadAheadPeriodFrom(preferencesOf(stringPreferencesKey("preview_lines") to "SIX_MONTHS")),
        )
    }

    @Test fun bothSurviveAnExportAndItsImport() {
        for (enabled in listOf(true, false)) {
            for (period in DownloadAheadPeriod.entries) {
                val stored = preferencesOf(switchKey to enabled, periodKey to period.name)
                val exported = SettingsBackupCodec.encode(
                    SettingsBackup(
                        downloadAhead = downloadAheadFrom(stored),
                        downloadAheadPeriod = downloadAheadPeriodFrom(stored).name,
                    ),
                )
                val imported = checkNotNull(SettingsBackupCodec.decode(exported))
                assertTrue(imported.isPlausible())
                assertEquals(exported, enabled, imported.downloadAhead)
                assertEquals(exported, period, downloadAheadPeriodOrNull(imported.downloadAheadPeriod))
            }
        }
    }

    @Test fun aBackupWrittenBeforeTheyExistedLeavesTheDefaults() {
        // Absent is "leave as is", which restoreBackup reads as null. A field declared as a plain
        // Boolean would turn the switch off on every import of an older backup.
        val old = checkNotNull(SettingsBackupCodec.decode("""{"version":1,"themeMode":"DARK"}"""))
        assertTrue(old.isPlausible())
        assertNull(old.downloadAhead)
        assertNull(old.downloadAheadPeriod)
        assertNull(downloadAheadPeriodOrNull(old.downloadAheadPeriod))
        val exported = SettingsBackupCodec.encode(old)
        assertTrue(exported, exported.contains("\"downloadAhead\": null"))
        assertTrue(exported, exported.contains("\"downloadAheadPeriod\": null"))
    }

    // Spelled out rather than imported: the keys name persisted user data, so renaming one silently
    // drops every stored choice.
    private val switchKey = booleanPreferencesKey("download_ahead")
    private val periodKey = stringPreferencesKey("download_ahead_period")
}

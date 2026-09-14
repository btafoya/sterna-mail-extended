package app.sterna.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * Executes `settingsCorruptionHandler` itself on a real file-backed DataStore. The `Context`
 * delegate that passes it is out of JVM reach and pinned by `SettingsCorruptionWiringTest`.
 *
 * The reset costs settings only: accounts and their secrets are kept by `AccountStore` in its own
 * SharedPreferences, never in `settings.preferences_pb`.
 *
 * The notification content is reset to `NONE` and not to `NOTIFICATION_CONTENT_DEFAULT`: the choice
 * that was lost may have been "Sender only" or "None", and a fallback on that setting never reveals
 * more on the lock screen than the choice it replaces.
 */
class SettingsCorruptionRecoveryTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the fixture is a real corruption for a store without a handler`() = runBlocking {
        val file = garbageFile()
        withStore(file, handler = null) { store ->
            try {
                store.data.first()
                fail("the garbage file parsed; every other test in this class would then pass without a handler")
            } catch (expected: CorruptionException) {
            }
        }
    }

    @Test fun `an unreadable settings file reads as the quietest notification content and nothing else`() = runBlocking {
        val file = garbageFile()
        withStore(file) { store ->
            val prefs = store.data.first()
            assertEquals(preferencesOf(KEY_NOTIFICATION_CONTENT to "NONE"), prefs)
            assertEquals(NotificationContent.NONE, notificationContentFrom(prefs))
        }
    }

    @Test fun `a setting written after the reset is read back by the next store on that file`() = runBlocking {
        val file = garbageFile()
        withStore(file) { store -> store.edit { it[KEY] = "DARK" } }
        withStore(file) { store -> assertEquals("DARK", store.data.first()[KEY]) }
    }

    @Test fun `a healthy settings file is read intact`() = runBlocking {
        val file = File(tmp.root, "settings.preferences_pb")
        withStore(file, handler = null) { store -> store.edit { it[KEY] = "LIGHT" } }
        withStore(file) { store -> assertEquals("LIGHT", store.data.first()[KEY]) }
    }

    /**
     * DataStore refuses two active instances on one file, and releases the file only once the
     * scope's job has completed, so the job is joined and not merely cancelled.
     */
    private suspend fun withStore(
        file: File,
        handler: ReplaceFileCorruptionHandler<Preferences>? = settingsCorruptionHandler,
        block: suspend (DataStore<Preferences>) -> Unit,
    ) {
        val job = Job()
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = handler,
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(store)
        } finally {
            job.cancel()
            job.join()
        }
    }

    /**
     * Random bytes are not reliably corrupt: a leading end-group tag, as `Random(64)` happens to
     * draw, ends the message and parses as empty preferences. A zero first byte is a tag with field
     * number 0, which the parser always rejects.
     */
    private fun garbageFile(): File = File(tmp.root, "settings.preferences_pb").apply {
        writeBytes(Random(64).nextBytes(64).also { it[0] = 0 })
    }

    private companion object {
        val KEY = stringPreferencesKey("theme_mode")
    }
}

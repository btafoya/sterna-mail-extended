package app.sterna.core.data.search

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/**
 * [SearchHistoryRepository] on a real file-backed DataStore. The `Context` delegate that names the
 * file `search_history` is out of JVM reach; everything behind it runs here.
 */
class SearchHistoryRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val switch = MutableStateFlow(true)

    @Test fun `a recorded search is read back, most recent first`() = runBlocking {
        withRepository { history ->
            history.record("loyer")
            history.record(" facture ")
            assertEquals(listOf("facture", "loyer"), history.entries.first())
            assertEquals(listOf("facture", "loyer"), history.offered.first())
        }
    }

    @Test fun `with the setting off, nothing is recorded`() = runBlocking {
        switch.value = false
        withRepository { history ->
            history.record("facture")
            assertEquals(
                "the setting is off: the search must not be kept, whatever the caller did",
                emptyList<String>(),
                history.entries.first(),
            )
        }
    }

    @Test fun `turning the setting off keeps the searches but offers none`() = runBlocking {
        withRepository { history ->
            history.record("loyer")
            switch.value = false
            assertEquals(listOf("loyer"), history.entries.first())
            assertEquals(emptyList<String>(), history.offered.first())
            switch.value = true
            assertEquals(listOf("loyer"), history.offered.first())
        }
    }

    @Test fun `clearing empties the list, the setting off or on`() = runBlocking {
        withRepository { history ->
            history.record("loyer")
            switch.value = false
            history.clear()
            switch.value = true
            assertEquals(emptyList<String>(), history.entries.first())
        }
    }

    @Test fun `the searches survive the store being reopened`() = runBlocking {
        val file = File(tmp.root, "search_history.preferences_pb")
        withRepository(file) { it.record("loyer") }
        withRepository(file) { assertEquals(listOf("loyer"), it.entries.first()) }
    }

    @Test fun `an unreadable file reads as an empty history and can be written again`() = runBlocking {
        val file = File(tmp.root, "search_history.preferences_pb").apply {
            // A zero first byte is a field-number-0 tag, which the protobuf parser always rejects.
            writeBytes(Random(7).nextBytes(64).also { it[0] = 0 })
        }
        withRepository(file) { history ->
            assertEquals(emptyList<String>(), history.entries.first())
            history.record("loyer")
            assertEquals(listOf("loyer"), history.entries.first())
        }
    }

    /**
     * DataStore refuses two active instances on one file and releases it only once the scope's job
     * has completed, so the job is joined and not merely cancelled.
     */
    private suspend fun withRepository(
        file: File = File(tmp.root, "search_history.preferences_pb"),
        handler: ReplaceFileCorruptionHandler<Preferences> = searchHistoryCorruptionHandler,
        block: suspend (SearchHistoryRepository) -> Unit,
    ) {
        val job = Job()
        val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            corruptionHandler = handler,
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { file },
        )
        try {
            block(SearchHistoryRepository(store, switch))
        } finally {
            job.cancel()
            job.join()
        }
    }
}

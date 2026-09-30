package app.sterna.core.data.search

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** An unreadable file costs the recent searches and nothing else: they are shortcuts, not data. */
internal val searchHistoryCorruptionHandler: ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { emptyPreferences() }

// A file of its own under `datastore/`, which `backup_rules.xml` and `data_extraction_rules.xml`
// already keep off every backup and device transfer. Not the `settings` file: that one is what the
// settings export mirrors, and the searches must never be in it.
private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history", corruptionHandler = searchHistoryCorruptionHandler)

private val KEY_SEARCHES = stringPreferencesKey("searches")

/**
 * The searches typed on this phone, most recent first, shared by every account. [enabled] is the
 * "Search history" setting: off, nothing is written and nothing is offered, but what was kept stays
 * until [clear].
 */
class SearchHistoryRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val enabled: Flow<Boolean>,
) {
    constructor(context: Context, enabled: Flow<Boolean>) :
        this(context.applicationContext.searchHistoryDataStore, enabled)

    /** Everything kept, whatever the setting says: the clear button needs to know there is something. */
    val entries: Flow<List<String>> = dataStore.data.map { decodeSearchHistory(it[KEY_SEARCHES]) }

    /** What the search fields may offer. */
    val offered: Flow<List<String>> = combine(entries, enabled) { kept, on -> if (on) kept else emptyList() }

    /**
     * Keeps [typed] at the head of the list. The setting is read here, at every write, so no caller
     * can keep a search while it is off. Call it on a gesture that says the text is final (a Search
     * key, a result opened, a suggestion chosen), never from a field that searches as it is typed:
     * that one sees every prefix on the way, and the list would fill with "e", "ec", "eco".
     */
    suspend fun record(typed: String) {
        if (!enabled.first()) return
        dataStore.edit { it[KEY_SEARCHES] = encodeSearchHistory(withSearch(decodeSearchHistory(it[KEY_SEARCHES]), typed)) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_SEARCHES) }
    }
}

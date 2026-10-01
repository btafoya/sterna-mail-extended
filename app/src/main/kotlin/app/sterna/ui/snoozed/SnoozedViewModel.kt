package app.sterna.ui.snoozed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.db.SnoozedListRow
import app.sterna.snooze.Snoozes
import app.sterna.ui.components.PickInstantOpen
import app.sterna.ui.message.MessagePaging
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the "Snoozed" screen (Codeberg #82): the messages currently hidden by a snooze, with
 * their deadline, so a snooze can be undone or moved instead of being a hidden state.
 */
class SnoozedViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository

    val pickInstant = PickInstantOpen({ key -> savedState.get<String>(key) }, { key, value -> savedState[key] = value })

    val items = repo.snoozedFlow()
        .map { pendingSnoozes(it, System.currentTimeMillis()) }
        .onEach { rows -> if (PickInstantOpen.rowGone(pickInstant.target.value, rows.map(::snoozedRowKey))) pickInstant.close() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList<SnoozedListRow>(),
        )

    /**
     * Cancel a snooze: drop the row so the message re-appears in its list at once, and cancel
     */
    fun cancel(accountId: String, emailId: String) {
        Snoozes.cancel(getApplication(), accountId, emailId)
        viewModelScope.launch { repo.unsnooze(accountId, emailId) }
    }

    /** Move a snooze to a new deadline: same write as snoozing, and the unique work is replaced. */
    fun reschedule(emailId: String, accountId: String, until: Long) {
        viewModelScope.launch {
            repo.snooze(emailId, accountId, until)
            Snoozes.enqueue(getApplication(), emailId, accountId, until)
        }
    }
}

internal fun snoozedRowKey(row: SnoozedListRow): String = MessagePaging.entryKey(row.emailId, row.accountId)

/**
 * What the screen lists: only snoozes still pending at [now] (a lapsed row is a message already
 * back in its list, the same `until > now` predicate the list SQL uses), soonest first.
 */
internal fun pendingSnoozes(rows: List<SnoozedListRow>, now: Long): List<SnoozedListRow> =
    rows.filter { it.until > now }.sortedBy { it.until }

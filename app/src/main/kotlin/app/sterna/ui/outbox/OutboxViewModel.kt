package app.sterna.ui.outbox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.db.OutboxEntity
import app.sterna.send.Outbox
import app.sterna.send.composeDraftOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/** Backs the Outbox screen: lists queued/failed sends and offers retry, edit and delete. */
class OutboxViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val sendOutbox = application.container.sendOutbox

    val items = repo.outboxFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList<OutboxEntity>(),
    )

    /**
     * The id of the item just staged for editing, so the screen can open compose on it — `null`
     * when there is none. The id, and not a flag: the screen puts it in the composer's route, which
     * is the one place that survives a process death (see [edit]).
     */
    private val _readyToEdit = MutableStateFlow<Long?>(null)
    val readyToEdit: StateFlow<Long?> = _readyToEdit.asStateFlow()

    /** Re-queue an item for an immediate retry. */
    fun retry(id: Long) {
        viewModelScope.launch { repo.retryOutbox(id) }
    }

    /** Delete an item: cancel its worker and drop the row + its persistent attachments. */
    fun delete(id: Long) {
        Outbox.cancel(getApplication(), id)
        viewModelScope.launch { repo.deleteOutbox(id) }
    }

    /**
     * Reopen an item in compose for editing (#70): stage it into compose (re-staging IMAP
     * attachments into the cache) and mark the row EDITING so the send worker leaves it alone while
     * the composer holds it. Sending from compose consumes the row and enqueues the edited message;
     * closing the composer instead flips the same row back to QUEUED. The row never leaves the
     * database, so nothing rides in RAM and a process death costs the edit, not the message.
     *
     * And the composer FINDS the row again after such a death: the draft handed over through
     * [sendOutbox] dies with the process, but the id published in [readyToEdit] goes into the
     * composer's route (`compose?restore=true&outboxId=…`), which Android restores. Once the
     * startup sweep has parked the row FAILED, the rebuilt composer takes it back by that id
     * (`ComposeViewModel.resumeOutboxEdit`), and Send consumes the original instead of queueing a
     * second copy beside it.
     */
    fun edit(id: Long) {
        viewModelScope.launch {
            val staging = File(getApplication<Application>().cacheDir, "outgoing")
            // takeOutboxForEdit throws rather than reopen an item whose attachment can't be read, so
            // the row and its durable files survive as a still-queued send. Swallow that here: the
            // edit simply doesn't open instead of crashing, and the item stays queued as it was.
            val draft = runCatching { repo.takeOutboxForEdit(id, staging) }
                .onFailure { android.util.Log.w("SternaOutbox", "couldn't reopen outbox item $id for edit", it) }
                .getOrNull() ?: return@launch
            // The mapping is a pure function ([composeDraftOf]) so a JVM test can execute it: what
            // it drops is not missing from the screen, it is CANCELLED — re-editing enqueues a new
            // row from the composer's state.
            sendOutbox.reopen(composeDraftOf(draft))
            _readyToEdit.value = id
        }
    }

    fun consumeEdit() {
        _readyToEdit.value = null
    }
}

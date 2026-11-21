package app.sterna.ui.dav

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.R
import app.sterna.container
import app.sterna.core.data.db.DavObjectEntity
import app.sterna.core.data.db.DavPendingEntity
import app.sterna.core.jmap.JmapType
import app.sterna.dav.Dav
import app.sterna.ui.handAttachmentToViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

/** The folder tree of one account's Files, read from Room and written through the queue. Shared by the screen and the composer's picker. */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModel(private val app: Application, val accountId: String) : AndroidViewModel(app) {
    private val dav = app.container.davRepository
    private val type = JmapType.FILE_NODE

    private val _stack = MutableStateFlow<List<Crumb>>(emptyList())
    val stack: StateFlow<List<Crumb>> = _stack.asStateFlow()

    /** The current folder's contents: folders first (the row's isTask is true for a node with no blob), then by name. */
    val rows: StateFlow<List<DavObjectEntity>> = _stack
        .flatMapLatest { dav.watchGroup(accountId, type, it.groupId()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: Flow<Int> = dav.watchPendingCount(accountId)
    // Only this screen's type: a failed calendar write is not a file's to show.
    val failed: Flow<List<DavPendingEntity>> = dav.watchFailed(accountId).map { list -> list.filter { it.type == type.name } }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    private var opening = false

    fun openFolder(row: DavObjectEntity) { _stack.value = _stack.value + Crumb(row.id, row.name) }

    /** One folder up; false when already at the root, so the caller can leave the screen instead. */
    fun up(): Boolean {
        if (_stack.value.isEmpty()) return false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    fun goTo(depth: Int) { _stack.value = _stack.value.take(depth) }

    fun nameProblem(name: String, renaming: String? = null): FileNameProblem? =
        fileNameProblem(name, rows.value.map { it.name }, renaming)

    private fun parentField(): Map<String, JsonPrimitive> =
        _stack.value.groupId().takeIf { it.isNotEmpty() }?.let { mapOf("parentId" to JsonPrimitive(it)) }.orEmpty()

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            Dav.enqueue(app)
        }
    }

    fun createFolder(name: String) = write {
        dav.create(accountId, type, JsonObject(parentField() + ("name" to JsonPrimitive(name.trim()))))
    }

    fun rename(row: DavObjectEntity, name: String) = write {
        dav.update(accountId, type, row.id, JsonObject(mapOf("name" to JsonPrimitive(name.trim()))))
    }

    /** A folder takes its contents with it; the server removes the subtree. */
    fun delete(row: DavObjectEntity) = write { dav.destroy(accountId, type, row.id) }

    /** Copies each picked document into app storage, where the queue can find it after a restart, and queues the upload. */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    stageAndQueue(uri)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not stage an upload", t)
                    _message.value = app.getString(R.string.files_upload_failed, t.message ?: "error")
                }
            }
            Dav.enqueue(app)
        }
    }

    private suspend fun stageAndQueue(uri: Uri) {
        val resolver = app.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
        val staged = File(File(app.filesDir, "dav_uploads").apply { mkdirs() }, UUID.randomUUID().toString())
        val size = withContext(Dispatchers.IO) {
            (resolver.openInputStream(uri) ?: error(app.getString(R.string.status_read_file_failed))).use { input ->
                staged.outputStream().use { input.copyTo(it) }
            }
        }
        dav.uploadFile(accountId, _stack.value.groupId().ifEmpty { null }, name, mime, staged.path, size)
    }

    /** Downloads [row] to the shareable cache and offers it to a viewer app. */
    fun open(row: DavObjectEntity) {
        if (opening) return
        opening = true
        viewModelScope.launch {
            try {
                if (row.id.startsWith("local-")) error("not uploaded yet")
                val credentials = app.container.accountStore.credentials(accountId) ?: error(app.getString(R.string.status_no_saved_account))
                val bytes = dav.downloadFile(credentials, row)
                handAttachmentToViewer(app, app.container.storageRepository, row.name, null, bytes)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                Log.w(TAG, "Could not open a file", t)
                _message.value = app.getString(R.string.files_open_failed, t.message ?: "error")
            } finally {
                // Released once the chooser is up: the file is the user's to open again.
                opening = false
            }
        }
    }

    fun dismissFailed(id: Long) { viewModelScope.launch { dav.dismissFailed(id) } }

    private companion object {
        const val TAG = "FilesViewModel"
    }
}

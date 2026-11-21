package app.sterna.ui.dav

import android.app.Application
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.sterna.R
import app.sterna.core.data.db.DavObjectEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A node with no blob is a folder (the row's `isTask` is how Room stores that, so folders sort first). */
internal val DavObjectEntity.isFolder: Boolean get() = isTask

private fun DavObjectEntity.sizeBytes(): Long? =
    runCatching { Json.parseToJsonElement(json).jsonObject["size"]?.jsonPrimitive?.longOrNull }.getOrNull()

@Composable
internal fun rememberFilesViewModel(accountId: String, key: String): FilesViewModel {
    val app = LocalContext.current.applicationContext as Application
    return viewModel(key = key, factory = viewModelFactory { initializer { FilesViewModel(app, accountId) } })
}

private enum class NameMode { CREATE, RENAME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(accountId: String, onBack: () -> Unit) {
    val vm = rememberFilesViewModel(accountId, "files-$accountId")
    val stack by vm.stack.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val pending by vm.pendingCount.collectAsStateWithLifecycle(0)
    val failed by vm.failed.collectAsStateWithLifecycle(emptyList())
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { vm.upload(it) }
    val leave = { if (!vm.up()) onBack() }
    BackHandler(enabled = stack.isNotEmpty()) { vm.up() }

    var dialog by remember { mutableStateOf<NameMode?>(null) }
    var target by remember { mutableStateOf<DavObjectEntity?>(null) }
    var deleting by remember { mutableStateOf<DavObjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stack.joinToString(" / ") { it.name }.ifEmpty { stringResource(R.string.files_root) }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(if (stack.isEmpty()) R.string.dav_back else R.string.files_up))
                    }
                },
                actions = {
                    IconButton(onClick = { target = null; dialog = NameMode.CREATE }) {
                        Icon(Icons.Filled.CreateNewFolder, contentDescription = stringResource(R.string.files_new_folder))
                    }
                    IconButton(onClick = { upload.launch("*/*") }) {
                        Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.files_upload))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (pending > 0) {
                Text(
                    stringResource(R.string.dav_waiting_to_send),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            failed.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.dav_failed_title) + f.lastError?.let { ": $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.dismissFailed(f.id) }) { Text(stringResource(R.string.dav_dismiss)) }
                }
            }
            FilesRows(
                rows = rows,
                onFolder = vm::openFolder,
                onFile = vm::open,
                modifier = Modifier.weight(1f),
            ) { row ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.files_more, row.name))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_rename)) },
                            onClick = { menu = false; target = row; dialog = NameMode.RENAME },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.dav_delete)) },
                            onClick = { menu = false; deleting = row },
                        )
                    }
                }
            }
        }
    }

    dialog?.let { mode ->
        val renaming = target?.takeIf { mode == NameMode.RENAME }
        NameDialog(
            title = stringResource(if (renaming == null) R.string.files_new_folder else R.string.files_rename_title),
            initial = renaming?.name.orEmpty(),
            confirm = stringResource(if (renaming == null) R.string.files_create else R.string.dav_save),
            problem = { vm.nameProblem(it, renaming?.name) },
            onConfirm = { name -> if (renaming == null) vm.createFolder(name) else vm.rename(renaming, name) },
            onDismiss = { dialog = null },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.files_delete_title, row.name)) },
            text = { Text(stringResource(if (row.isFolder) R.string.files_delete_folder_body else R.string.files_delete_file_body)) },
            confirmButton = { TextButton(onClick = { vm.delete(row); deleting = null }) { Text(stringResource(R.string.dav_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.dav_cancel)) } },
        )
    }
}

/** The rows of one folder; [actions] draws the trailing menu of a row, none in the picker. */
@Composable
internal fun FilesRows(
    rows: List<DavObjectEntity>,
    onFolder: (DavObjectEntity) -> Unit,
    onFile: (DavObjectEntity) -> Unit,
    modifier: Modifier = Modifier,
    actions: (@Composable (DavObjectEntity) -> Unit)? = null,
) {
    if (rows.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { Text(stringResource(R.string.files_empty)) }
        return
    }
    val context = LocalContext.current
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
        items(rows, key = { it.id }) { row ->
            ListItem(
                modifier = Modifier.clickable { if (row.isFolder) onFolder(row) else onFile(row) },
                leadingContent = {
                    if (row.isFolder) Icon(Icons.Filled.Folder, contentDescription = stringResource(R.string.files_folder_icon))
                    else Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null)
                },
                headlineContent = { Text(row.name, maxLines = 1) },
                supportingContent = row.sizeBytes()?.takeIf { !row.isFolder }?.let { size ->
                    { Text(Formatter.formatShortFileSize(context, size)) }
                },
                trailingContent = actions?.let { draw -> { draw(row) } },
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    problem: (String) -> FileNameProblem?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    val p = problem(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; touched = true },
                label = { Text(stringResource(R.string.files_folder_name)) },
                singleLine = true,
                isError = touched && p != null,
                supportingText = if (touched && p != null) { { Text(stringResource(p.message())) } } else null,
            )
        },
        confirmButton = {
            TextButton(enabled = p == null, onClick = { onConfirm(name); onDismiss() }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dav_cancel)) } },
    )
}

private fun FileNameProblem.message(): Int = when (this) {
    FileNameProblem.EMPTY -> R.string.files_err_empty
    FileNameProblem.FORBIDDEN_CHAR -> R.string.files_err_chars
    FileNameProblem.TOO_LONG -> R.string.files_err_long
    FileNameProblem.TAKEN -> R.string.files_err_taken
}

/** A dialog over the account's Files for choosing one file (the composer's "Attach from Files"); folders open in place. */
@Composable
fun FilesPicker(accountId: String, onPick: (DavObjectEntity) -> Unit, onDismiss: () -> Unit) {
    val vm = rememberFilesViewModel(accountId, "files-picker-$accountId")
    val stack by vm.stack.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.isNotEmpty()) {
                    IconButton(onClick = { vm.up() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_up))
                    }
                }
                Text(stack.lastOrNull()?.name ?: stringResource(R.string.files_pick_title), maxLines = 1)
            }
        },
        text = {
            FilesRows(
                // A file still waiting to upload has no blob to download.
                rows = rows.filterNot { it.id.startsWith("local-") && !it.isFolder },
                onFolder = vm::openFolder,
                onFile = onPick,
                modifier = Modifier.heightIn(max = 360.dp),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_pick_close)) } },
    )
}

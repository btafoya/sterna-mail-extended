package app.sterna.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.showOnlySubscribedFoldersOf
import app.sterna.core.jmap.model.Mailbox
import app.sterna.folders.FolderDeletion
import app.sterna.folders.folderDeletion
import app.sterna.folders.folderNameMayBeSubmitted
import app.sterna.ui.components.ReorderableColumn
import app.sterna.ui.inbox.mailboxDisplayName
import app.sterna.ui.inbox.mailboxLabel

/**
 * The folders of one account, each with the two switches that govern it (#207, #16), under the five
 * roles the account may pin a folder to (#227). The rows, the state of every switch and what each
 * picker offers are decided in [folderManagementRows] and [specialFolderRows]; this draws them.
 */
@Composable
internal fun FolderManagementScreen(
    accountId: String,
    onBack: () -> Unit,
    viewModel: AccountsViewModel = viewModel(),
) {
    // The live list: the account can be removed from another screen while this one is open, and the
    // empty scaffold below is what it then shows.
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    if (account == null) {
        DetailScaffold(title = stringResource(R.string.folders_manage_title), onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }
    val folders by remember(accountId) { viewModel.folders(accountId) }
        .collectAsStateWithLifecycle(emptyList())
    val specialRows = specialFolderRows(folders, showOnlySubscribedFoldersOf(account), account.specialFolders, account.inboxId, account.protocol == MailProtocol.IMAP, account.folderOrder)
    // Saveable: a rotation recreates the activity, and the user is in the middle of a task.
    var reordering by rememberSaveable(accountId) { mutableStateOf(false) }
    val rowsFor = { order: List<String> -> folderManagementRows(folders, showOnlySubscribedFoldersOf(account), account.hiddenFolders, account.watchedFolders, account.inboxId, order, reordering) }
    val rows = rowsFor(account.folderOrder)
    val context = LocalContext.current
    BackHandler(enabled = reordering) { reordering = false }
    // Saveable, like the special-folder pickers: an open dialog is the middle of a gesture. Folder
    // ids are saved, never the Mailbox, which is looked up again in the list of this account.
    var creatingFolder by rememberSaveable(accountId) { mutableStateOf(false) }
    var subfolderOf by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable(accountId) { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val folderMessage by viewModel.folderMessage.collectAsStateWithLifecycle()
    val pendingFolderDelete by viewModel.pendingFolderDelete.collectAsStateWithLifecycle()
    val undoLabel = stringResource(R.string.inbox_undo)
    LaunchedEffect(folderMessage) {
        val m = folderMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.clearFolderMessage()
    }
    // Indefinite: the ViewModel clears the pending delete when its hold-back ends, which cancels
    // this effect and takes the snackbar down with it. The window is timed on the ViewModel's own
    // scope, so leaving the screen lets the delete go, as the snackbar running out does: it was
    // confirmed, and the work is already scheduled.
    LaunchedEffect(pendingFolderDelete) {
        val pending = pendingFolderDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = pending.label,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Indefinite,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteFolder()
    }
    DetailScaffold(
        title = stringResource(R.string.folders_manage_title),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            TextButton(onClick = { reordering = !reordering }) {
                Text(stringResource(if (reordering) R.string.folders_reorder_done else R.string.folders_reorder_start))
            }
        },
    ) { padding ->
        if (reordering) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                Text(
                    stringResource(R.string.folders_reorder_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                TextButton(
                    onClick = { viewModel.sortFolders(accountId, sortedFratries(folders, showOnlySubscribedFoldersOf(account), account.folderOrder, { mailboxLabel(context, it.role, it.name) })) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(stringResource(R.string.folders_reorder_sort))
                }
                ReorderableColumn(
                    items = rows,
                    key = { it.mailbox.id },
                    canMove = { from, to -> folderDrop(rows, from, to, account.folderOrder, rowsFor) != null },
                    onMove = { from, to -> folderDrop(rows, from, to, account.folderOrder, rowsFor)?.let { viewModel.moveFolderTo(accountId, it.siblings, it.folderId, it.toIndex) } },
                    span = { folderSubtreeSize(rows, it) },
                    modifier = Modifier.weight(1f),
                ) { row, handle ->
                    FolderReorderRow(row, handle) { moves, up -> viewModel.moveFolder(accountId, moves.siblings, row.mailbox.id, up) }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSection(stringResource(R.string.folders_special_title)) {
                    Text(
                        stringResource(R.string.folders_special_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    specialRows.forEach { row ->
                        SpecialFolderChoiceRow(
                            row = row,
                            onChoose = { viewModel.setSpecialFolder(accountId, row.role, it) },
                        )
                    }
                }
                NewFolderRow(onClick = { creatingFolder = true })
                rows.forEach { row ->
                    Column(Modifier.padding(start = (row.depth * 16).dp)) {
                        val name = mailboxDisplayName(row.mailbox.role, row.mailbox.name)
                        SettingsSection(
                            title = name,
                            trailing = if (row.mayManage) {
                                {
                                    FolderOptionsButton(
                                        name = name,
                                        onAddSubfolder = { subfolderOf = row.mailbox.id },
                                        onRename = { renaming = row.mailbox.id },
                                        onDelete = { deleting = row.mailbox.id },
                                    )
                                }
                            } else {
                                null
                            },
                        ) {
                            SettingSwitch(
                                title = stringResource(R.string.folders_manage_show_title),
                                subtitle = stringResource(R.string.folders_manage_show_subtitle),
                                checked = row.shown,
                                onCheckedChange = { viewModel.setFolderHidden(accountId, row.mailbox.id, !it) },
                                enabled = row.showToggleEnabled,
                            )
                            if (row.mayWatch) {
                                SettingSwitch(
                                    title = stringResource(R.string.inbox_folder_watch),
                                    subtitle = stringResource(R.string.folders_manage_watch_subtitle),
                                    checked = row.watched,
                                    onCheckedChange = { viewModel.setFolderWatched(accountId, row.mailbox.id, it) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val byId = { id: String? -> id?.let { wanted -> folders.firstOrNull { it.id == wanted } } }
    if (creatingFolder) {
        FolderNameDialog(
            title = stringResource(R.string.inbox_new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.inbox_create),
            onConfirm = { viewModel.createFolder(accountId, it, null); creatingFolder = false },
            onDismiss = { creatingFolder = false },
        )
    }
    byId(subfolderOf)?.let { parent ->
        FolderNameDialog(
            title = stringResource(R.string.inbox_new_subfolder_in, mailboxDisplayName(parent.role, parent.name)),
            initial = "",
            confirmLabel = stringResource(R.string.inbox_create),
            onConfirm = { viewModel.createFolder(accountId, it, parent.id); subfolderOf = null },
            onDismiss = { subfolderOf = null },
        )
    }
    byId(renaming)?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.inbox_rename_folder),
            initial = folder.name,
            confirmLabel = stringResource(R.string.inbox_rename),
            onConfirm = { viewModel.renameFolder(accountId, folder.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
    byId(deleting)?.let { folder ->
        FolderDeleteDialog(
            folder = folder,
            folders = folders,
            onConfirm = { viewModel.deleteFolder(accountId, it); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

/** "New folder", at the top of the folder list: the only way to create one at the root. */
@Composable
private fun NewFolderRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.CreateNewFolder, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.inbox_new_folder), style = MaterialTheme.typography.bodyLarge)
    }
}

/** The folder's own menu, on the line of its name: New subfolder, Rename, Delete. */
@Composable
private fun FolderOptionsButton(name: String, onAddSubfolder: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_folder_options_for, name))
        }
        DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.inbox_new_subfolder)) },
                onClick = { open = false; onAddSubfolder() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.inbox_rename)) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.inbox_delete)) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/** A name to create or rename a folder with. The typed name is saveable: a rotation must not
 *  throw away what the user wrote. The trim is `FolderActions`'s. */
@Composable
private fun FolderNameDialog(title: String, initial: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val submit = { if (folderNameMayBeSubmitted(name)) onConfirm(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.inbox_folder_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = folderNameMayBeSubmitted(name)) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.inbox_cancel)) } },
    )
}

/** One confirmation: "Also delete subfolders?" when [folderDeletion] finds any in [folders], the
 *  list of the account this screen manages, the plain one otherwise. */
@Composable
private fun FolderDeleteDialog(folder: Mailbox, folders: List<Mailbox>, onConfirm: (FolderDeletion) -> Unit, onDismiss: () -> Unit) {
    val deletion = folderDeletion(folder, folders)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (deletion.asksAboutSubfolders) R.string.inbox_delete_folder_recursive_title else R.string.inbox_delete_folder_title))
        },
        text = {
            Text(stringResource(if (deletion.asksAboutSubfolders) R.string.inbox_delete_folder_recursive_body else R.string.inbox_delete_folder_body, folder.name))
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deletion) }) {
                Text(stringResource(R.string.inbox_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.inbox_cancel)) } },
    )
}

/** One folder in the reorder mode: the handle to drag it by, its name at its depth, and the Up and
 *  Down its row may take. The buttons stay beside the handle: a screen reader cannot drag. */
@Composable
private fun FolderReorderRow(row: FolderManagementRow, handle: Modifier, onMove: (FolderMoves, Boolean) -> Unit) {
    val name = mailboxDisplayName(row.mailbox.role, row.mailbox.name)
    val moves = row.moves
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (moves != null) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = stringResource(R.string.folders_reorder_drag),
                modifier = handle.size(48.dp).padding(12.dp),
            )
        } else {
            Spacer(Modifier.size(48.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = (row.depth * 16).dp),
        )
        if (moves != null) {
            MoveSlot(moves.mayMoveUp, Icons.Filled.KeyboardArrowUp, stringResource(R.string.folders_reorder_up, name)) { onMove(moves, true) }
            MoveSlot(moves.mayMoveDown, Icons.Filled.KeyboardArrowDown, stringResource(R.string.folders_reorder_down, name)) { onMove(moves, false) }
        }
    }
}

/** A 48 dp move button, disabled rather than removed when the row may not move that way: the node
 *  under the accessibility focus outlives the press that brings its row to the edge of the list,
 *  and the arrows of every row stay in one column. */
@Composable
internal fun MoveSlot(enabled: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = description)
    }
}

/**
 * One role and the folder carrying it, opening on the folders of this account plus "Automatic".
 *
 * The role's own translated name titles the line, the folder under it is named as the SERVER named
 * it: an account with a `Junk` and a `Spam` reads "Spam" twice otherwise, on the line and in the
 * picker both, and there is nothing left to choose between (#227). No trailing control, so the
 * whole width is the label's: five role names, ten languages, 360 dp.
 */
@Composable
private fun SpecialFolderChoiceRow(row: SpecialFolderRow, onChoose: (String?) -> Unit) {
    // Saveable, not remembered: an open picker is the middle of a gesture, and a rotation recreates
    // the activity. The role is the SAVE key, `key = `, not a positional input: the five lines are
    // one call site, whose generated composition keys the restore would otherwise share out by
    // order alone.
    var picking by rememberSaveable(key = row.role) { mutableStateOf(false) }
    val title = mailboxDisplayName(row.role, row.role)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { picking = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                when (val label = row.label) {
                    SpecialFolderLabel.None -> stringResource(R.string.folders_special_automatic)
                    is SpecialFolderLabel.Chosen -> label.name
                    is SpecialFolderLabel.Detected ->
                        stringResource(R.string.folders_special_automatic_named, label.name)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!picking) return
    AlertDialog(
        // The system back gesture arrives here, and closes the picker alone: the screen behind it
        // is where the user still is.
        onDismissRequest = { picking = false },
        title = { Text(title) },
        text = {
            // The list must scroll: an account can hold hundreds of folders, and Material's `text`
            // slot hands down a finite maxHeight a non-scrolling Column spends until the last rows
            // measure ~0 and render as bare radio circles that take no taps.
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                row.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.selected,
                                role = Role.RadioButton,
                                onClick = {
                                    onChoose(option.folderId)
                                    picking = false
                                },
                            )
                            .padding(
                                start = (minOf(option.depth, PICKER_INDENT_LEVELS) * 16).dp,
                                top = 12.dp,
                                bottom = 12.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        RadioButton(selected = option.selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(
                            when {
                                option.folderId == null -> stringResource(R.string.folders_special_automatic)
                                option.heldRole != null -> stringResource(
                                    R.string.folders_special_option_role,
                                    option.name,
                                    mailboxDisplayName(option.heldRole, option.heldRole),
                                )
                                else -> option.name
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { picking = false }) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/** How far a picker entry is indented, at most. Past this the text column of a 360 dp screen is
 *  what pays, between the indent, the radio button and an annotation that wraps. */
private const val PICKER_INDENT_LEVELS = 3

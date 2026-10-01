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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.account.MailProtocol
import app.sterna.core.data.account.showOnlySubscribedFoldersOf
import app.sterna.ui.components.ReorderableColumn
import app.sterna.ui.inbox.mailboxDisplayName

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
    BackHandler(enabled = reordering) { reordering = false }
    DetailScaffold(
        title = stringResource(R.string.folders_manage_title),
        onBack = onBack,
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
                rows.forEach { row ->
                    Column(Modifier.padding(start = (row.depth * 16).dp)) {
                        SettingsSection(mailboxDisplayName(row.mailbox.role, row.mailbox.name)) {
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

/** A 48 dp move button, or an empty slot of the same size when the row may not move that way: the
 *  arrows of every row then stay in one column. */
@Composable
private fun MoveSlot(shown: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {
    if (!shown) {
        Spacer(Modifier.size(48.dp))
        return
    }
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
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

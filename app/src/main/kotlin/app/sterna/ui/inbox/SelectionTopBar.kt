package app.sterna.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.canSnoozeIn
import app.sterna.ui.isOutgoingFolder
import app.sterna.ui.components.PickInstantOpen
import app.sterna.ui.message.snoozePresets

/**
 * The selection's title bar, one for the inbox and the advanced search, so the two offer the same
 * actions through the same [InboxViewModel] gestures. [currentRole] is the role of the folder on
 * screen, null where the rows come from any folder; [trash] is decided by the caller from the same
 * view. [onSelectAll] is the one action each screen answers for itself: "all" is what that screen
 * draws, never the other's list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    viewModel: InboxViewModel,
    count: Int,
    selectionAllRead: Boolean,
    currentRole: String?,
    inboxId: String?,
    trash: Boolean,
    onMove: () -> Unit,
    onSelectAll: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = { viewModel.clearSelection() }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.inbox_cancel_selection))
            }
        },
        title = {
            // A check + the count: compact and language-proof. The full localized
            // label is kept for screen readers.
            val countLabel = stringResource(R.string.inbox_selected_count, count)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clearAndSetSemantics { contentDescription = countLabel },
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(count.toString(), maxLines = 1)
            }
        },
        actions = {
            // Toggle read/unread, keeping the selection (only the state changes).
            IconButton(onClick = { viewModel.toggleSelectedRead() }) {
                Icon(
                    if (selectionAllRead) Icons.Filled.MarkEmailUnread else Icons.Filled.DoneAll,
                    contentDescription = stringResource(if (selectionAllRead) R.string.inbox_mark_unread else R.string.inbox_mark_read),
                )
            }
            if (currentRole == "archive" || currentRole == "all") {
                IconButton(
                    onClick = { inboxId?.let { viewModel.moveSelectedTo(it, targetAccountId = null, fromPicker = false) } },
                    enabled = inboxId != null,
                ) {
                    Icon(Icons.Filled.Unarchive, contentDescription = stringResource(R.string.inbox_unarchive))
                }
            } else {
                IconButton(onClick = { viewModel.archiveSelected() }) {
                    Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.inbox_archive))
                }
            }
            IconButton(onClick = onMove) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.inbox_move_to_folder))
            }
            IconButton(onClick = { viewModel.deleteSelected() }) {
                // In Trash the button destroys, not moves-to-Trash, so a Trash-can
                // icon is misleading (#23). The selection bar acts on many rows and
                // has no single row to be judged by, so it keeps the view's role.
                Icon(
                    if (trash) Icons.Filled.DeleteForever else Icons.Filled.Delete,
                    contentDescription = stringResource(if (trash) R.string.inbox_delete_forever else R.string.inbox_delete),
                )
            }
            // Overflow: snooze + report/not-spam for the whole selection.
            var selMenu by remember { mutableStateOf(false) }
            var selSnooze by remember { mutableStateOf(false) }
            val selContext = LocalContext.current
            // Boxed with its button for the anchoring reason spelled out on the
            // browse-bar overflow menu (#74). This one swaps its whole content for
            // the snooze presets, so unboxed it could jump sideways while open.
            Box {
                IconButton(onClick = { selMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.inbox_more))
                }
                DropdownMenu(
                    expanded = selMenu,
                    onDismissRequest = { selMenu = false; selSnooze = false },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (selSnooze) {
                        snoozePresets(selContext).forEach { (label, until) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = { selMenu = false; selSnooze = false; viewModel.snoozeSelected(until) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.schedule_pick_date_time)) },
                            onClick = { selMenu = false; selSnooze = false; viewModel.pickInstant.open(PickInstantOpen.SELECTION) },
                        )
                    } else {
                        val inJunk = currentRole == "junk"
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.inbox_select_all)) },
                            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                            onClick = { selMenu = false; onSelectAll() },
                        )
                        // Spam-reporting acts on incoming mail; in Drafts and Sent
                        // the selection is the user's own, so it is not offered (#82).
                        if (!isOutgoingFolder(currentRole)) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (inJunk) R.string.message_not_spam else R.string.message_report_spam))
                                },
                                leadingIcon = { Icon(Icons.Filled.Report, contentDescription = null) },
                                onClick = {
                                    selMenu = false
                                    if (inJunk) viewModel.notSpamSelected() else viewModel.reportSpamSelected()
                                },
                            )
                        }
                        // Snoozing is a promise to come back to a message, so it is
                        // also gone in Spam and in the Trash (#82).
                        if (canSnoozeIn(currentRole)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.message_snooze)) },
                                leadingIcon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
                                onClick = { selSnooze = true },
                            )
                        }
                    }
                }
            }
        },
    )
}

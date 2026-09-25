package app.sterna.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.EmailKey

/**
 * Move-to-folder picker for the current selection, the inbox's and the advanced search's. The
 * offered folders (and their order) come from [moveTargets], shared with the reader's own picker
 * (#73). [currentMailboxId] is the folder on screen, left out of the list; null where the rows
 * come from any folder.
 */
@Composable
internal fun SelectionMoveDialog(
    viewModel: InboxViewModel,
    accounts: List<StoredAccount>,
    selectedKeys: Set<EmailKey>,
    currentMailboxId: String?,
    onDismiss: () -> Unit,
) {
    // Folders the move-to-folder picker offers: the selected message's account's, not the active
    // account's (#73). Empty until something is selected.
    val moveTargetMailboxes by viewModel.selectionMailboxes.collectAsStateWithLifecycle()
    // …and whether that account hides its unsubscribed folders (#174). Read next to the list, not
    // folded into it: moveTargets needs the whole list for the parent paths it shows (#109).
    val moveTargetsOnlySubscribed by viewModel.selectionOnlySubscribed.collectAsStateWithLifecycle()
    val moveTargetsHiding by viewModel.selectionHiding.collectAsStateWithLifecycle()
    val moveTargetsOrder by viewModel.selectionFolderOrder.collectAsStateWithLifecycle()
    // The picker's account row (#189): the account the selection belongs to, and the one chosen
    // on the row, if any, both live in the ViewModel so the choice survives a rotation.
    val moveOwnerAccountId by viewModel.moveOwnerAccountId.collectAsStateWithLifecycle()
    val moveAccountId by viewModel.moveAccountId.collectAsStateWithLifecycle()
    // The filter field's text (#182). Held inside the dialog's own composition, so leaving the
    // dialog drops the group and the field comes back empty next time.
    var moveQuery by remember { mutableStateOf("") }
    // The folder on screen is left out of the list only while the list is the selection's own
    // account's: listing another account's, its id names nothing there, or a homonym (#92).
    val moveExcludedMailbox = pickerExcludedMailbox(moveAccountId, moveOwnerAccountId, currentMailboxId)
    val targets = remember(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed, moveTargetsHiding, moveTargetsOrder) {
        moveTargets(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed, moveTargetsHiding.ids, moveTargetsHiding.inboxId, moveTargetsOrder)
    }
    // Nested folders sharing a leaf are one and the same row without their parent path (#109).
    // Resolved against the whole folder list, not the offered subset. Memoized like [targets]:
    // the filter field re-runs this block on every keystroke (#182).
    val movePaths = remember(targets, moveTargetMailboxes) {
        targets.map { folder -> mailboxPathLabel(folder, moveTargetMailboxes) }
    }
    // Painted over the whole offered list and never over the filtered one: what the filter
    // compares is what the row shows, and the rows painted must not depend on what was typed.
    val moveRows = ArrayList<FolderPickerRow>(targets.size)
    for ((folder, path) in targets.zip(movePaths)) {
        moveRows += FolderPickerRow(folder, mailboxDisplayName(folder.role, folder.name), path)
    }
    val shownMoveRows = filterFolderRows(moveRows, moveQuery)
    AlertDialog(
        onDismissRequest = { onDismiss(); viewModel.chooseMoveAccount(null) },
        title = { Text(stringResource(R.string.inbox_move_to_folder)) },
        text = {
            Column {
                // The account whose folders are listed, and the way to list another's (#189).
                // Not drawn at all when the selection spans accounts, where the move only takes
                // the owner's messages (selectionPickerAccounts).
                MoveAccountRow(selectionPickerAccounts(accounts, selectedKeys), moveOwnerAccountId, moveAccountId, viewModel::chooseMoveAccount)
                // The filter field (#182), outside the scroller so it stays put while the list
                // moves under it. Deliberately without a FocusRequester and without an IME
                // "search" action: it filters what is on screen, it asks the server nothing.
                Box(Modifier.fillMaxWidth()) {
                    if (moveQuery.isEmpty()) {
                        Text(
                            stringResource(R.string.inbox_filter_folders),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            // Bounded, like the search bar this shape is copied from: a Box is
                            // as tall as its tallest child, so an unbounded hint at a large font
                            // scale would steal that height from the list underneath.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(horizontal = 16.dp),
                        )
                    }
                    TextField(
                        value = moveQuery,
                        onValueChange = { moveQuery = it },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        trailingIcon = {
                            if (moveQuery.isNotEmpty()) {
                                IconButton(onClick = { moveQuery = "" }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.inbox_clear),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // `weight(1f, fill = false)` on the scroller and nothing on the field: in a
                // Column the unweighted child is measured first, so the field keeps its height
                // and it is the list that gives way.
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (shownMoveRows.isEmpty() && moveQuery.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.inbox_no_folder_matches),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    shownMoveRows.forEach { row ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // The row's own folder, never a rank in the filtered list.
                                    viewModel.moveSelectedTo(row.folder.id, moveAccountId)
                                    onDismiss()
                                    viewModel.chooseMoveAccount(null)
                                }
                                .semantics(mergeDescendants = true) { role = Role.Button }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                text = row.name,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (row.path != null) {
                                Text(
                                    text = row.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // Two lines, not one: a single line elides at the end, in
                                    // dp, after the path was already trimmed by character count,
                                    // eating the nearest parent at a large font size.
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onDismiss(); viewModel.chooseMoveAccount(null) }) { Text(stringResource(R.string.inbox_cancel)) }
        },
    )
}

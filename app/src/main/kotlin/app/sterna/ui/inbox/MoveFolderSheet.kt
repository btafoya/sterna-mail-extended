package app.sterna.ui.inbox

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.account.MoveHistory
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.mail.EmailKey
import app.sterna.core.jmap.model.Mailbox

/**
 * Move-to-folder picker for the current selection, the inbox's and the advanced search's. The
 * offered folders (and their order) come from [moveTargets], shared with the reader's picker
 * (#73). [currentMailboxId] is the folder on screen, left out of the list; null where the rows
 * come from any folder. The filter's text is the caller's: it has to survive a rotation.
 */
@Composable
internal fun MoveFolderSheet(
    viewModel: InboxViewModel,
    accounts: List<StoredAccount>,
    selectedKeys: Set<EmailKey>,
    currentMailboxId: String?,
    query: String,
    onQueryChange: (String) -> Unit,
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
    val moveHistory by viewModel.selectionMoveHistory.collectAsStateWithLifecycle()
    val moveSender by viewModel.selectionSender.collectAsStateWithLifecycle()
    // The picker's account row (#189): the account the selection belongs to, and the one chosen
    // on the row, if any, both live in the ViewModel so the choice survives a rotation.
    val moveOwnerAccountId by viewModel.moveOwnerAccountId.collectAsStateWithLifecycle()
    val moveAccountId by viewModel.moveAccountId.collectAsStateWithLifecycle()
    // The folder on screen is left out of the list only while the list is the selection's own
    // account's: listing another account's, its id names nothing there, or a homonym (#92).
    val moveExcludedMailbox = pickerExcludedMailbox(moveAccountId, moveOwnerAccountId, currentMailboxId)
    val targets = remember(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed, moveTargetsHiding, moveTargetsOrder) {
        moveTargets(moveTargetMailboxes, moveExcludedMailbox, moveTargetsOnlySubscribed, moveTargetsHiding.ids, moveTargetsHiding.inboxId, moveTargetsOrder)
    }
    MoveFolderSheet(
        // Not drawn at all when the selection spans accounts, where the move only takes the
        // owner's messages (selectionPickerAccounts).
        accounts = selectionPickerAccounts(accounts, selectedKeys),
        ownerAccountId = moveOwnerAccountId,
        chosenAccountId = moveAccountId,
        onChooseAccount = viewModel::chooseMoveAccount,
        targets = targets,
        allMailboxes = moveTargetMailboxes,
        history = moveHistory,
        sender = moveSender,
        query = query,
        onQueryChange = onQueryChange,
        onPick = { folderId -> viewModel.moveSelectedTo(folderId, moveAccountId, fromPicker = true) },
        onDismiss = onDismiss,
    )
}

/**
 * The move-to-folder sheet itself, the list's, the search's and the reader's. [targets] are the
 * offered folders in their order; [allMailboxes] the account's whole list, which the parent paths
 * are resolved against (#109). [onPick] receives the tapped row's own folder id; the sheet then
 * closes and puts the account choice back, the same three steps whichever way it is left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveFolderSheet(
    accounts: List<StoredAccount>,
    ownerAccountId: String?,
    chosenAccountId: String?,
    onChooseAccount: (String?) -> Unit,
    targets: List<Mailbox>,
    allMailboxes: List<Mailbox>,
    history: MoveHistory,
    sender: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val moveListedAccountId = pickerAccount(chosenAccountId, ownerAccountId)
    // Plain remember, not rememberSaveable: the section is computed at each opening, a rotation
    // included, so it never outlives the history it was read from.
    var moveFrozen by remember { mutableStateOf<FrozenSuggestions?>(null) }
    val moveSuggestions = freezeSuggestions(moveFrozen, moveListedAccountId, history, targets, sender, System.currentTimeMillis())
    SideEffect { moveFrozen = moveSuggestions }
    // Nested folders sharing a leaf are one and the same row without their parent path (#109).
    // Resolved against the whole folder list, not the offered subset. Memoized: the filter field
    // re-runs this block on every keystroke (#182).
    val movePaths = remember(targets, allMailboxes) {
        targets.map { folder -> mailboxPathLabel(folder, allMailboxes) }
    }
    // Painted over the whole offered list and never over the filtered one: what the filter
    // compares is what the row shows, and the rows painted must not depend on what was typed.
    val moveRows = ArrayList<FolderPickerRow>(targets.size)
    for ((folder, path) in targets.zip(movePaths)) {
        moveRows += FolderPickerRow(folder, mailboxDisplayName(folder.role, folder.name), path)
    }
    val shownMoveRows = filterFolderRows(moveRows, query)
    val suggestedMoveRows = suggestedRows(moveSuggestions, moveListedAccountId, moveRows, query)
    // One tap handler for both lists: the row's own folder, never a rank in either.
    val pickMoveRow: (FolderPickerRow) -> Unit = { row ->
        onPick(row.folder.id)
        onDismiss()
        onChooseAccount(null)
    }
    ModalBottomSheet(
        onDismissRequest = { onDismiss(); onChooseAccount(null) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            stringResource(R.string.inbox_move_to_folder),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Box(Modifier.padding(horizontal = 8.dp)) {
            // The account whose folders are listed, and the way to list another's (#189).
            MoveAccountRow(accounts, ownerAccountId, chosenAccountId, onChooseAccount)
        }
        // The filter field (#182), outside the list so it stays put while the list moves under
        // it. Deliberately without a FocusRequester and without an IME "search" action: it
        // filters what is on screen, it asks the server nothing.
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (query.isEmpty()) {
                Text(
                    stringResource(R.string.inbox_filter_folders),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Bounded, like the search bar this shape is copied from: a Box is as tall as
                    // its tallest child, so an unbounded hint at a large font scale would steal
                    // that height from the list underneath.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(horizontal = 16.dp),
                )
            }
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
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
        // Last in the sheet's column, so it is measured after the header and gets what is left.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 16.dp),
        ) {
            if (shownMoveRows.isEmpty() && query.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.inbox_no_folder_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            if (suggestedMoveRows.isNotEmpty()) {
                item { FolderPickerSectionTitle(stringResource(R.string.inbox_move_suggestions)) }
                items(suggestedMoveRows) { suggested ->
                    FolderPickerRowItem(suggested.row, suggested.usualForSender) { pickMoveRow(suggested.row) }
                }
                item { FolderPickerSectionTitle(stringResource(R.string.inbox_move_all_folders)) }
            }
            items(shownMoveRows) { row ->
                FolderPickerRowItem(row) { pickMoveRow(row) }
            }
        }
    }
}

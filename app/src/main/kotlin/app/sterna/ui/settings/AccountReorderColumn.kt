package app.sterna.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.ui.components.AccountAvatar
import app.sterna.ui.components.ReorderableColumn
import app.sterna.ui.components.accountColorOf

/** The accounts screen's reorder mode: no tap target on a row, so nothing here switches the
 *  current account or opens one. */
@Composable
internal fun AccountReorderColumn(
    accounts: List<StoredAccount>,
    viewModel: AccountsViewModel,
    modifier: Modifier = Modifier,
) {
    val rows = accountReorderRows(accounts)
    ReorderableColumn(
        items = rows,
        key = { it.account.id },
        canMove = { from, to -> accountDrop(rows, from, to) != null },
        onMove = { from, to -> accountDrop(rows, from, to)?.let { viewModel.moveAccountTo(rows[from].account.id, it) } },
        span = { accountBlockSpan(rows, it) },
        modifier = modifier,
    ) { row, handle ->
        AccountReorderLine(row, handle) { up -> viewModel.moveAccount(row.account.id, up) }
    }
}

/** The buttons stay beside the handle: a screen reader cannot drag. */
@Composable
private fun AccountReorderLine(row: AccountReorderRow, handle: Modifier, onMove: (Boolean) -> Unit) {
    val account = row.account
    val name = account.label()
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
        Row(
            Modifier.weight(1f).padding(start = if (row.underLogin) 16.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Seed and second line as on the list outside the mode, so a row keeps its colour and
            // what it says across the button.
            AccountAvatar(
                photo = account.photo,
                seed = if (account.isShared) name else account.username,
                label = name,
                color = accountColorOf(account.color),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (account.isShared) stringResource(R.string.account_shared) else account.username,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (moves != null) {
            MoveSlot(moves.mayMoveUp, Icons.Filled.KeyboardArrowUp, stringResource(R.string.folders_reorder_up, name)) { onMove(true) }
            MoveSlot(moves.mayMoveDown, Icons.Filled.KeyboardArrowDown, stringResource(R.string.folders_reorder_down, name)) { onMove(false) }
        }
    }
}

package app.sterna.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.accountColorOf

/** The list's title made a button that drops down [others] to switch to (#215). With no other
 *  account, [content] is drawn bare, with nothing to tap. */
@Composable
internal fun AccountSwitchTitle(
    others: List<StoredAccount>,
    onSwitchAccount: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    if (others.isEmpty()) {
        content()
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClickLabel = stringResource(R.string.inbox_switch_account)) { open = true }
                .semantics(mergeDescendants = true) { role = Role.Button },
        ) {
            content()
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            others.forEach { account ->
                val label = account.label()
                DropdownMenuItem(
                    leadingIcon = { Monogram(seed = label, label = label, color = accountColorOf(account.color)) },
                    text = {
                        Column {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (account.isShared) {
                                Text(
                                    stringResource(R.string.account_shared),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSwitchAccount(account.id)
                        open = false
                    },
                )
            }
        }
    }
}

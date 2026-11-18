package app.sterna.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.ui.components.AccountAvatar
import app.sterna.ui.components.accountColorOf
import kotlinx.coroutines.launch

/**
 * The body of an account's Photo section, on the account screen and on a shared mailbox's: the
 * avatar as it is drawn, Choose and Remove, and the sentence saying where the photo stays, shown
 * without opening anything. [colorArgb] is the colour the screen shows, which may be ahead of
 * [account]'s stored one. A picture that cannot be read leaves the account as it was, and says so.
 */
@Composable
internal fun AccountPhotoSetting(
    account: StoredAccount,
    colorArgb: Int?,
    viewModel: AccountsViewModel,
    onAccountsChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var unreadable by remember(account.id) { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                unreadable = !viewModel.choosePhoto(account.id, uri)
                onAccountsChanged()
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountAvatar(
            photo = account.photo,
            seed = if (account.isShared) account.label() else account.username,
            label = account.label(),
            color = accountColorOf(colorArgb),
        )
        // Stacked, not side by side: the two labels run long in German and on a 360 dp screen.
        Column(Modifier.weight(1f)) {
            OutlinedButton(onClick = { unreadable = false; picker.launch("image/*") }) {
                Text(stringResource(R.string.settings_account_photo_choose))
            }
            if (account.photo != null) {
                TextButton(onClick = { unreadable = false; viewModel.removePhoto(account.id); onAccountsChanged() }) {
                    Text(stringResource(R.string.settings_account_photo_remove))
                }
            }
        }
    }
    Text(
        stringResource(R.string.settings_account_photo_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    if (unreadable) {
        Text(
            stringResource(R.string.settings_account_photo_unreadable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

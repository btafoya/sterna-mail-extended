package app.sterna.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.pgp.chooseSmimeCertificate

/**
 * The account's S/MIME certificate: none, or the name it was chosen under. A tap opens Android's chooser, which
 * also grants this app the use of the key; the certificate is only ever taken from there.
 */
@Composable
internal fun SmimeAccountSection(
    accountId: String,
    account: StoredAccount,
    viewModel: AccountsViewModel,
) {
    val context = LocalContext.current
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val alias = (accounts.firstOrNull { it.id == accountId } ?: account).smimeCertAlias
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) {
                context.hostActivity()?.let { activity ->
                    chooseSmimeCertificate(activity, alias.takeIf { it.isNotEmpty() }) { chosen ->
                        if (chosen != null) viewModel.setSmimeCertificate(accountId, chosen)
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_smime_certificate_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = if (alias.isEmpty()) stringResource(R.string.settings_smime_certificate_none) else alias,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (alias.isNotEmpty()) {
            TextButton(onClick = { viewModel.setSmimeCertificate(accountId, "") }) {
                Text(stringResource(R.string.settings_smime_certificate_remove))
            }
        }
    }
    Text(
        stringResource(R.string.settings_smime_certificate_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

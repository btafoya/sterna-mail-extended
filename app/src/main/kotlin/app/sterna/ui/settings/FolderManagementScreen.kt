package app.sterna.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.account.showOnlySubscribedFoldersOf
import app.sterna.ui.inbox.mailboxDisplayName

/**
 * The folders of one account, each with the two switches that govern it (#207, #16). The rows and
 * the state of every switch are [folderManagementRows]'s; this draws them.
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
    val rows = folderManagementRows(folders, showOnlySubscribedFoldersOf(account), account.hiddenFolders, account.watchedFolders, account.inboxId)
    DetailScaffold(title = stringResource(R.string.folders_manage_title), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
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

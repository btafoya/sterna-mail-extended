package app.sterna.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.push.PushController
import app.sterna.ui.components.AccountPalette

/**
 * The account the `sharedAccount/{id}` route may show: a DELEGATED one, and only that. An id that
 * names a login — or nothing at all — resolves to nothing, so the two writes below can never land
 * on a login's row from a screen that says "Shared account". Pure and executed by the tests, rather
 * than restated in them.
 */
internal fun sharedAccountFor(id: String, accounts: List<StoredAccount>): StoredAccount? =
    accounts.firstOrNull { it.id == id && it.isShared }

/**
 * The inner settings route that opens [accountId]: the short shared-mailbox screen when the id
 * names a DELEGATED account, the full account screen otherwise (issue #31).
 *
 * ONE decision for BOTH ways into the settings graph — the tap in the accounts list and the
 * drawer's deep link, which arrives as the start destination. The graph itself only ever sees a
 * route string; it never re-reads [StoredAccount.isShared]. Two hand-written guards would be two
 * chances to disagree, and the disagreement that matters lands a shared mailbox on the login
 * editor, whose credential field writes under the LOGIN's key for every account grouped there.
 *
 * Reuses [sharedAccountFor], so the route and the screen it opens answer the same question. An id
 * the list does not hold resolves to the account screen rather than to nothing: a dead shortcut is
 * worse than one that opens on an empty scaffold, which is what an UNKNOWN id gets there.
 *
 * The bound, written as it really is: a BLANK id is not that case. It yields `"account/"`, which
 * matches no route in the settings graph, and navigating to it THROWS — there is no screen at the
 * end of it, empty or otherwise. Nothing can reach it today (SternaApp.kt hands the deep link over
 * as `?.ifBlank { null }`, so a blank id arrives as null and the graph starts on the hub), and that
 * single `ifBlank` is the whole reason. Whoever removes it owns this line.
 */
internal fun settingsRouteFor(accountId: String, accounts: List<StoredAccount>): String =
    if (sharedAccountFor(accountId, accounts) != null) "sharedAccount/$accountId" else "account/$accountId"

/**
 * Settings for a DELEGATED (shared) account — the two the account genuinely owns, and no more
 * (issue #31): its accent colour and its own new-mail notifications, both already honoured by the
 * engine per account id.
 *
 * WHY THIS IS NOT `AccountDetailScreen` WITH A FEW ROWS HIDDEN. That screen edits the LOGIN this
 * account borrows: its secret is stored under [StoredAccount.loginKey], so a credential typed here
 * would overwrite the login's, for every account grouped under it. Its sign-out does not cascade
 * the way round one might assume: the cascade runs from a LOGIN down to the accounts linked to it
 * (`AccountsViewModel` collects `linkedAccounts` only when the target is NOT itself linked), so
 * signing out a delegated account leaves the login untouched — and does nothing durable to the
 * account either, since the next connect's discovery pass re-adds the share (`AccountStore`,
 * `reconcileLinkedAccounts`), under a fresh id and therefore without the two settings below. A
 * separate screen means neither can be reached by accident.
 *
 * BOTH WRITES CARRY [accountId], never the borrowed login's id. That is the whole point of the
 * batch, and it is pinned line by line by SharedAccountScreenTest.
 *
 * Nothing is pending: each tap writes. There is no Save button, so there is no unsaved state to
 * warn about on the way out, and no caption claiming a wider scope than these two rows.
 */
@Composable
internal fun SharedAccountScreen(
    accountId: String,
    viewModel: AccountsViewModel,
    onBack: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    // The LIVE list, not a snapshot: a share revoked on the server is dropped by the next
    // discovery pass while this screen is open, and the empty scaffold below is what it should
    // then show.
    // PINNED BY SharedAccountScreenTest — the guard, statement by statement
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val account = sharedAccountFor(accountId, accounts)
    if (account == null) {
        // Gone, or never a delegated account at all. Nothing to show and nothing to write.
        DetailScaffold(title = stringResource(R.string.settings_account_screen_title), onBack = onBack) { padding ->
            Box(Modifier.fillMaxSize().padding(padding))
        }
        return
    }

    // PINNED BY SharedAccountScreenTest — what each row opens on
    var colorArgb by remember(accountId) { mutableStateOf(account.color) }
    var notificationsEnabled by remember(accountId) { mutableStateOf(account.notificationsEnabled) }

    DetailScaffold(title = account.label(), onBack = onBack) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // The title is the account's own label — the only thing on this screen naming the
            // mailbox — so this quiet line under it says what KIND of account it is.
            Text(
                stringResource(R.string.account_shared),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SettingsSection(stringResource(R.string.settings_account_colour_section)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ColourSwatch(color = null, selected = colorArgb == null) {
                        colorArgb = null; viewModel.setColor(accountId, null); onAccountsChanged()
                    }
                    AccountPalette.colors.forEach { swatch ->
                        val argb = swatch.toArgb()
                        ColourSwatch(color = swatch, selected = colorArgb == argb) {
                            colorArgb = argb; viewModel.setColor(accountId, argb); onAccountsChanged()
                        }
                    }
                }
            }
            SettingsSection(stringResource(R.string.settings_account_notifications_section)) {
                SettingSwitch(
                    title = stringResource(R.string.settings_account_notifications_title),
                    subtitle = stringResource(R.string.settings_account_notifications_subtitle),
                    checked = notificationsEnabled,
                    onCheckedChange = {
                        notificationsEnabled = it
                        viewModel.setNotificationsEnabled(accountId, it)
                    },
                )
                // The same three-argument decision the full account screen asks, executed here
                // rather than second-guessed: a delegated account is always linked, so the note
                // stays silent today. Written out in full anyway — hard-coding the answer would
                // mean this screen deciding for [PushController] and drifting the day the rule
                // changes.
                if (PushController.shouldShowUnwatchedNote(account.isLinked, viewModel.isWatched(accountId), notificationsEnabled)) {
                    Text(
                        stringResource(R.string.settings_account_notifications_unwatched_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

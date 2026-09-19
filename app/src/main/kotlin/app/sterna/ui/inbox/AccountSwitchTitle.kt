package app.sterna.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

/** The list's title made a tap target that drops down [others] to switch to (#215). With no
 *  other account, [content] is drawn bare, with nothing to tap.
 *
 *  `MediumTopAppBar` composes its title slot twice, once per row, and the hidden copy's zero alpha
 *  stops neither its taps nor its `DropdownMenu` popup. So only the copy on screen past the
 *  `0.5f` [collapsedFraction] where the bar swaps the rows' semantics is a target; a copy knows its
 *  row only by the `LocalTextStyle` the bar provides it, `titleLarge` in the collapsed row. The open
 *  state is held by the caller and names the copy that was tapped ([openIn] is that copy's
 *  `currentCompositeKeyHash`): a `remember` of it here would let the copies disagree. */
@Composable
internal fun AccountSwitchTitle(
    others: List<StoredAccount>,
    openIn: Int?,
    onOpenInChange: (Int?) -> Unit,
    onSwitchAccount: (String) -> Unit,
    collapsedFraction: () -> Float,
    content: @Composable () -> Unit,
) {
    if (others.isEmpty()) {
        content()
        return
    }
    val here = currentCompositeKeyHash
    val inTopRow = LocalTextStyle.current.fontSize == MaterialTheme.typography.titleLarge.fontSize
    val shown by remember(inTopRow) {
        derivedStateOf { if (inTopRow) collapsedFraction() >= 0.5f else collapsedFraction() < 0.5f }
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (shown) {
                Modifier
                    .clickable(onClickLabel = stringResource(R.string.inbox_switch_account)) { onOpenInChange(here) }
                    .semantics(mergeDescendants = true) { role = Role.Button }
            } else {
                Modifier
            },
        ) {
            content()
        }
        DropdownMenu(expanded = shown && openIn == here, onDismissRequest = { onOpenInChange(null) }) {
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
                        onOpenInChange(null)
                    },
                )
            }
        }
    }
}

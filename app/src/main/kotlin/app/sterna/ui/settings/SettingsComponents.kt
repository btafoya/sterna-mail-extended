package app.sterna.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.sterna.R
import app.sterna.ui.components.Monogram
import app.sterna.ui.components.onAccentColor

/**
 * Shared settings component kit (see DESIGN.md → "Settings & secondary screens").
 * Hub and detail screens use these so preferences stay visually consistent:
 * no cards, 16dp margins, icon tint `onSurfaceVariant`, summary in
 * `bodyMedium` / `onSurfaceVariant`, section headers in the single accent.
 */

/** Hub row: icon · title · value-summary · chevron. Navigates to a detail screen. */
@Composable
fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Accent-tinted header grouping rows in a detail screen. */
@Composable
fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        content()
    }
}

/** Boolean toggle row. [enabled] = false renders the row inert and dimmed. */
@Composable
fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    Color.Unspecified
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * Single-choice row: shows the current value as a summary and opens a
 * [SettingChoiceDialog] of options when tapped.
 */
@Composable
fun <T> SettingChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                optionLabel(selected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        SettingChoiceDialog(
            title = title,
            options = options,
            selected = selected,
            optionLabel = optionLabel,
            onSelect = {
                onSelect(it)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Multi-choice row: independent options, each ticked on its own (none of them enables or
 * disables another). Summarises the ticked ones — [noneLabel] when none — and opens a
 * [SettingMultiChoiceDialog] of checkboxes when tapped.
 */
@Composable
fun <T> SettingMultiChoiceRow(
    title: String,
    options: List<T>,
    checked: Set<T>,
    optionLabel: (T) -> String,
    noneLabel: String,
    onCheckedChange: (T, Boolean) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                options.filter { it in checked }.joinToString { optionLabel(it) }.ifEmpty { noneLabel },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showDialog) {
        SettingMultiChoiceDialog(
            title = title,
            options = options,
            checked = checked,
            optionLabel = optionLabel,
            onCheckedChange = onCheckedChange,
            onDismiss = { showDialog = false },
        )
    }
}

/** Multi-choice M3 dialog with checkbox options; each toggle applies immediately. */
@Composable
fun <T> SettingMultiChoiceDialog(
    title: String,
    options: List<T>,
    checked: Set<T>,
    optionLabel: (T) -> String,
    onCheckedChange: (T, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    val isChecked = option in checked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = isChecked,
                                role = Role.Checkbox,
                                onValueChange = { onCheckedChange(option, it) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = null)
                        Spacer(Modifier.width(16.dp))
                        Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_ok)) }
        },
    )
}

/**
 * Account list row: monogram · label · email, with a check on the current one.
 *
 * [indented] draws the row further from the left edge, and is the ONLY thing that says a delegated
 * (shared) mailbox hangs under the login above it (issue #31) — it has a row of its own now, and
 * without the indent nothing on screen tells whose credential reaches it. It changes the start
 * padding and nothing else: an ordinary row is byte-for-byte what it was.
 */
@Composable
fun AccountRow(
    seed: String,
    label: String,
    email: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    color: Color? = null,
    indented: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // PINNED BY SharedAccountScreenTest — the indent, statement by statement
            .padding(
                start = if (indented) 32.dp else 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(seed = seed, label = label, color = color)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isCurrent) {
            Spacer(Modifier.width(16.dp))
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.settings_current_account),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * A read-only value the user has to carry OUT of the app — today the relay push address (#177):
 * label above, the value itself below, and a copy icon; tapping anywhere on the row copies it and
 * confirms with [copiedMessage].
 *
 * Shaped on [AccountRow] above, and deliberately not on [SettingTextField]: a value nobody edits
 * does not belong in an edit box, where a stray keystroke silently changes what will be pasted
 * elsewhere. And it is NOT a secret — a relay address is meant to be read and given away, so
 * there is no mask and no reveal eye here to borrow.
 *
 * The value wraps rather than being cut with an ellipsis: an endpoint URL is long, and half a URL
 * on screen is a URL that cannot be checked against the one that was pasted into the relay. The
 * whole of it is selectable too, so the copy tap is a convenience and not the only way out.
 */
@Composable
fun SettingCopyableRow(
    label: String,
    value: String,
    copiedMessage: String,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            SelectionContainer {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        // Decorative: the whole row is the one click target, and a screen reader already reads the
        // label and the value it would otherwise describe a second time.
        Icon(Icons.Filled.ContentCopy, contentDescription = null)
    }
}

/** The reveal icon is offered only when there is something to reveal. */
internal fun revealIconOffered(isPassword: Boolean, value: String): Boolean =
    isPassword && value.isNotEmpty()

/** Labelled outlined text field for editable settings (server URL, username, …). */
@Composable
fun SettingTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    // Keyed on emptiness, not on the value: the reveal survives typing, but falls back to hidden
    // as soon as the field is cleared AND again on the first keystroke after that. Without the key
    // a revealed-then-cleared field would print the next secret typed into it in clear text.
    var revealRequested by remember(value.isEmpty()) { mutableStateOf(false) }
    val masked = isPassword && !revealRequested
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (revealIconOffered(isPassword, value)) {
            {
                IconButton(onClick = { revealRequested = !revealRequested }) {
                    Icon(
                        if (revealRequested) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = stringResource(
                            if (revealRequested) R.string.connect_password_hide else R.string.connect_password_show,
                        ),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Single-choice M3 dialog with radio options. */
@Composable
fun <T> SettingChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Measured on the ten-entry language picker (Moto G, 720×1280): the bottom options came
            // out as bare radio circles, no label, and tapping them did nothing. A DEAD tap is the
            // discriminating fact — a clipped row still takes taps, so those rows were MEASURED
            // short, not clipped. Working hypothesis for why, same as SenderRuleDialogFitTest's:
            // Material's `text` slot hands down a finite maxHeight, which a non-scrolling Column
            // spends row by row until the last ones are measured at ~0.
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(option) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(optionLabel(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * The question a form with its own Save button asks when the user leaves with edits still unwritten
 * (#34): the account editor, the filter rules and the vacation responder all show this one.
 *
 * @param message what is at stake, in the wording of the screen that asks.
 * @param canSave whether saving is possible at all right now — a half-filled account, a write
 *   already in flight. Disabled rather than hidden: the reason is on the screen behind, and hiding
 *   the button would look like the offer moved.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveChangesDialog(
    message: String,
    canSave: Boolean,
    onCancel: () -> Unit,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.settings_save_changes_title)) },
        text = { Text(message) },
        // AlertDialog has two button slots and this exit needs three answers, so all three go in the
        // confirm slot as a FlowRow: one line where the labels fit, wrapped where they don't
        // (German, Russian), never truncated. Save last, as the confirming action.
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_cancel))
                }
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.settings_discard), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onSave, enabled = canSave) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        },
    )
}

/**
 * A circular colour choice for the per-account accent picker; "Auto" when [color] is null.
 * Shared by the full account editor and the delegated-account screen, which is why it lives here:
 * it is the only rendering of [app.sterna.ui.components.AccountPalette], and two copies of a
 * palette drift.
 */
@Composable
internal fun ColourSwatch(color: Color?, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(44.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick)
            .semantics { role = Role.Button; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        when {
            color == null -> Text(
                stringResource(R.string.settings_account_colour_auto),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            selected -> Icon(Icons.Filled.Check, contentDescription = null, tint = onAccentColor(color))
        }
    }
}

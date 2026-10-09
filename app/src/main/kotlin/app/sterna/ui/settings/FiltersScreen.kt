package app.sterna.ui.settings

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.sterna.R
import app.sterna.core.data.filter.Action
import app.sterna.core.data.filter.Condition
import app.sterna.core.data.filter.ConditionField
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ForeignScriptNotice
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.RuleMode
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.foreignScriptNotice
import app.sterna.core.data.filter.showsNoRulesNote
import app.sterna.ui.components.LoadingRing
import app.sterna.ui.components.MinTouchTarget
import app.sterna.ui.components.ReorderableColumn
import app.sterna.util.isValidEmail
import kotlinx.coroutines.launch

/** Server-side filter rules (Sieve, over JMAP or ManageSieve) for the current account. Each gesture
 *  writes at once, so the screen carries loading / saving / error state. */
@Composable
fun FiltersScreen(
    onBack: () -> Unit,
    viewModel: FiltersViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Everything remembered is held above the editor branches below, so their early return never
    // straddles a remember. Both editors' subjects survive a rotation and the death of the process.
    var editingSaved by rememberSaveable { mutableStateOf<ArrayList<Any?>?>(null) }
    var editingTextSaved by rememberSaveable { mutableStateOf<ArrayList<Any?>?>(null) }
    var confirmDelete by remember { mutableStateOf<FiltersChange?>(null) }
    var confirmToggle by remember { mutableStateOf<FiltersChange?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }
    var reordering by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val savedText = stringResource(R.string.settings_filters_saved)
    val editing = editingSaved?.let(::restoredRuleTarget)
    val editingText = editingTextSaved?.let(::restoredTextTarget)

    // Only a write that landed closes the editor: a refusal leaves what was typed in front of the
    // user, with the server's words under it.
    LaunchedEffect(state.landed) {
        if (state.landed == null) return@LaunchedEffect
        editingSaved = null
        editingTextSaved = null
        // Outside this effect: consuming the event changes its key, which cancels this coroutine.
        snackbarScope.launch { snackbar.showSnackbar(savedText) }
        viewModel.landedTaken()
    }
    LaunchedEffect(state.proposal) {
        when (val proposed = state.proposal ?: return@LaunchedEffect) {
            is FiltersChange.PutRule -> {
                editingTextSaved = null
                editingSaved = savedRuleTarget(RuleTarget(original = proposed.was, start = proposed.rule, rank = proposed.rank))
            }
            is FiltersChange.PutText -> {
                editingSaved = null
                editingTextSaved = savedTextTarget(TextTarget(proposed.name, proposed.was, proposed.text, proposed.rank))
            }
            else -> Unit
        }
        viewModel.proposalTaken()
    }

    fun openRule(target: RuleTarget) {
        viewModel.dismissError()
        editingSaved = savedRuleTarget(target)
    }

    state.overwritePending?.let {
        // Same shape as the block-sender dialog: server-side, permanent, no Undo behind it.
        // Cancelling writes nothing, and an open editor keeps what was typed.
        AlertDialog(
            onDismissRequest = viewModel::cancelOverwrite,
            title = { Text(stringResource(R.string.settings_filters_overwrite_title)) },
            text = {
                // The body must scroll: Material's text slot is a height-bounded box, so a bare
                // Text in it is cut, not ellipsised, with nothing saying more exists.
                Text(
                    stringResource(R.string.settings_filters_overwrite_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                // A verb, not a sentence: a sentence wraps and Cancel is then drawn inside this
                // button, one tap landing on both, on a permanent server-side write.
                TextButton(onClick = viewModel::confirmOverwrite) {
                    Text(stringResource(R.string.settings_filters_overwrite_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelOverwrite) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
    confirmDelete?.let { change ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.settings_filters_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_filters_delete_body),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; viewModel.save(change) }) {
                    Text(stringResource(R.string.settings_filters_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }
    confirmToggle?.let { change ->
        // A switch writes at once and would stop the script running now: its line is on the list,
        // but a tap on a switch is not a moment anyone reads it.
        AlertDialog(
            onDismissRequest = { confirmToggle = null },
            text = {
                Text(
                    stringResource(R.string.settings_filters_stops_auto_reply),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmToggle = null; viewModel.save(change) }) {
                    Text(stringResource(R.string.settings_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmToggle = null }) { Text(stringResource(R.string.settings_cancel)) }
            },
        )
    }

    val busy = filtersBusy(state)
    if (editing != null) {
        // Full-screen editor (a dialog is too cramped for this many fields).
        key(editingSaved) {
            RuleEditScreen(
                target = editing,
                state = state,
                busy = busy,
                onSave = { viewModel.save(FiltersChange.PutRule(editing.original, it, editing.rank)) },
                onDelete = editing.original?.let { was -> { confirmDelete = FiltersChange.DeleteRule(was, editing.rank) } },
                onClose = { editingSaved = null },
            )
        }
        return
    }
    if (editingText != null) {
        key(editingTextSaved) {
            TextEditScreen(
                target = editingText,
                state = state,
                busy = busy,
                onSave = { viewModel.save(FiltersChange.PutText(editingText.name, editingText.was, it, editingText.rank)) },
                onDelete = { confirmDelete = FiltersChange.DeleteText(editingText.name, editingText.was, editingText.rank) },
                onClose = { editingTextSaved = null },
            )
        }
        return
    }

    val mayReorder = filtersMayReorder(state)
    fun back() {
        if (!filtersMayLeave(state)) return
        if (reordering && mayReorder) reordering = false else onBack()
    }
    BackHandler(enabled = reordering && mayReorder) { reordering = false }
    BackHandler(enabled = !filtersMayLeave(state)) {}
    DetailScaffold(
        title = stringResource(R.string.settings_filters_screen_title),
        onBack = { back() },
        actions = if (mayReorder) {
            {
                TextButton(onClick = { reordering = !reordering }) {
                    Text(stringResource(if (reordering) R.string.folders_reorder_done else R.string.folders_reorder_start))
                }
            }
        } else {
            null
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> LoadingRing(Modifier.align(Alignment.Center))
                state.noAccount -> FiltersNote(stringResource(R.string.settings_vacation_no_account))
                !state.supported -> FiltersNote(stringResource(R.string.settings_filters_unsupported))
                state.errorKind == FiltersError.LOAD -> FiltersNote(
                    filtersErrorLines(FiltersError.LOAD, state.errorSieveKind, state.errorDetail),
                    onRetry = viewModel::load,
                )
                reordering && mayReorder -> FiltersReorder(
                    state = state,
                    idle = !state.saving && confirmToggle == null,
                    drop = viewModel::drop,
                    onMove = { change -> if (filtersWarnsBeforeWrite(state)) confirmToggle = change else viewModel.save(change) },
                )
                else -> FiltersList(
                    state = state,
                    busy = busy,
                    advancedOpen = advancedOpen,
                    onAdvanced = { advancedOpen = !advancedOpen },
                    onEdit = { item -> openRule(RuleTarget(original = item.rule, start = item.rule, rank = item.rank)) },
                    onToggle = { item, on ->
                        val change = FiltersChange.PutRule(item.rule, item.rule.copy(enabled = on), item.rank)
                        if (filtersWarnsBeforeWrite(state)) confirmToggle = change else viewModel.save(change)
                    },
                    onEditText = { item ->
                        viewModel.dismissError()
                        editingTextSaved = savedTextTarget(TextTarget(item.name, item.text, item.text, item.rank))
                    },
                    onDeleteText = { item -> confirmDelete = FiltersChange.DeleteText(item.name, item.text, item.rank) },
                    onAdd = { openRule(RuleTarget(original = null, start = FilterRule())) },
                    onRestart = { viewModel.save(FiltersChange.Rewrite) },
                    onActivate = viewModel::activate,
                    onPickScript = { picked ->
                        when (scriptPickStep(picked = picked, opened = state.scriptName)) {
                            ScriptPick.STAY -> Unit
                            ScriptPick.OPEN -> viewModel.openScript(picked)
                        }
                    },
                )
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun BoxScope.FiltersNote(text: String) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BoxScope.FiltersNote(lines: FiltersErrorLines, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(lines.sentence),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        errorDetailText(lines)?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Filled Button to match every other error-recovery "Retry" (inbox, message load).
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(stringResource(R.string.settings_vacation_retry))
        }
    }
}

@Composable
private fun errorDetailText(lines: FiltersErrorLines): String? =
    lines.detailRes?.let { stringResource(it) } ?: lines.detail

/** The failure of the last write, SAVE, TEXT_UNCUT or ACTIVATE; a LOAD failure takes the whole screen instead. */
@Composable
private fun FiltersErrorBlock(state: FiltersUiState) {
    val kind = state.errorKind?.takeIf { it != FiltersError.LOAD } ?: return
    val lines = filtersErrorLines(kind, state.errorSieveKind, state.errorDetail)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(lines.sentence),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        errorDetailText(lines)?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** In front of every write, so on the list and in each editor: a write replaces a script nobody
 *  could read, or stops the server's auto-reply. */
@Composable
private fun FiltersNoticeLine(state: FiltersUiState) {
    foreignScriptLine(
        foreignScriptNotice(
            scriptUnreadable = state.scriptUnreadable,
            foreignActive = state.foreignActive,
            vacationScriptActive = state.vacationScriptActive,
        ),
    )?.let { line ->
        Text(
            stringResource(line),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SaveAction(enabled: Boolean, saving: Boolean, onClick: () -> Unit) {
    if (saving) {
        val savingLabel = stringResource(R.string.settings_saving)
        CircularProgressIndicator(
            modifier = Modifier.padding(horizontal = 12.dp).size(20.dp).semantics { contentDescription = savingLabel },
            strokeWidth = 2.dp,
        )
    } else {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun DiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.settings_filters_discard_title)) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.settings_discard)) }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text(stringResource(R.string.settings_filters_keep_editing)) }
        },
    )
}

@Composable
private fun FiltersList(
    state: FiltersUiState,
    busy: Boolean,
    advancedOpen: Boolean,
    onAdvanced: () -> Unit,
    onEdit: (FiltersListItem.Form) -> Unit,
    onToggle: (FiltersListItem.Form, Boolean) -> Unit,
    onEditText: (FiltersListItem.Text) -> Unit,
    onDeleteText: (FiltersListItem.Text) -> Unit,
    onAdd: () -> Unit,
    onRestart: () -> Unit,
    onActivate: () -> Unit,
    onPickScript: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (state.accountLabel.isNotBlank()) {
            Text(
                stringResource(R.string.settings_vacation_account, state.accountLabel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // Before the auto-reply line and independent of it: the rules can be stopped with no
        // other script active at all. Its button is the one gesture that puts them back.
        if (state.rulesNotRunning) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_filters_not_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRestart, enabled = !busy) {
                    Text(stringResource(R.string.settings_filters_restart))
                }
            }
        }
        FiltersNoticeLine(state)
        val listing = filtersListing(state)
        when {
            listing.isNotEmpty() -> {
                listing.forEach { item ->
                    HorizontalDivider()
                    when (item) {
                        is FiltersListItem.Form -> RuleRow(
                            rule = item.rule,
                            trashFolder = state.trashFolder,
                            switchEnabled = !busy,
                            onToggle = { onToggle(item, it) },
                            onEdit = { onEdit(item) },
                        )
                        is FiltersListItem.Text -> TextRuleCard(
                            item = item,
                            deleteEnabled = !busy,
                            onEdit = { onEditText(item) },
                            onDelete = { onDeleteText(item) },
                        )
                    }
                }
                HorizontalDivider()
            }
            // An empty list is not always "no rules": over an unreadable script the line above
            // already says a save would replace unread content, which "No rules yet" contradicts.
            showsNoRulesNote(ruleCount = listing.size, scriptUnreadable = state.scriptUnreadable) ->
                Text(
                    stringResource(R.string.settings_filters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
        }

        OutlinedButton(onClick = onAdd, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_filters_add))
        }

        FiltersErrorBlock(state)

        state.scriptName?.let { name ->
            AdvancedSection(open = advancedOpen, onToggle = onAdvanced) {
                ScriptSelector(
                    opened = name,
                    scripts = state.scripts,
                    enabled = !busy,
                    onPick = onPickScript,
                )
                if (state.scriptNotActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.settings_filters_script_inactive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onActivate, enabled = !busy) {
                            Text(stringResource(R.string.settings_filters_make_active))
                        }
                    }
                }
            }
        }
        // Room for the snackbar over the last row.
        Spacer(Modifier.padding(bottom = 72.dp))
    }
}

@Composable
private fun AdvancedSection(open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val state = stringResource(if (open) R.string.a11y_conversation_expanded else R.string.a11y_conversation_collapsed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .semantics {
                    role = Role.Button
                    stateDescription = state
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_filters_advanced),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (open) content()
    }
}

@Composable
private fun ScriptSelector(opened: String, scripts: List<ListedScript>, enabled: Boolean, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choose = stringResource(R.string.settings_filters_script_choose)
    val activeLabel = stringResource(R.string.settings_filters_script_active)
    Box(Modifier.padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier
                .clickable(enabled = enabled, onClickLabel = choose) { expanded = true }
                .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.settings_filters_script, opened),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            scripts.forEach { script ->
                DropdownMenuItem(
                    text = { Text(script.name) },
                    leadingIcon = {
                        if (script.isActive) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.width(24.dp))
                    },
                    trailingIcon = if (script.isActive) {
                        { Text(activeLabel, style = MaterialTheme.typography.labelSmall) }
                    } else {
                        null
                    },
                    onClick = { expanded = false; onPick(script.name) },
                )
            }
        }
    }
}

@Composable
private fun FiltersReorder(
    state: FiltersUiState,
    idle: Boolean,
    drop: (from: Int, to: Int) -> FiltersChange.MoveRule?,
    onMove: (FiltersChange.MoveRule) -> Unit,
) {
    val rows = filtersReorderRows(state.scriptRules)
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.settings_filters_reorder_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        FiltersErrorBlock(state)
        ReorderableColumn(
            items = rows,
            key = { it.key },
            canMove = { from, to -> drop(from, to) != null },
            onMove = { from, to -> drop(from, to)?.let(onMove) },
            idle = idle,
            modifier = Modifier.weight(1f),
        ) { row, handle ->
            val at = rows.indexOf(row)
            RuleReorderRow(row.rule, handle, up = drop(at, at - 1), down = drop(at, at + 1), onMove = onMove)
        }
    }
}

@Composable
private fun RuleReorderRow(
    rule: ScriptRule,
    handle: Modifier,
    up: FiltersChange.MoveRule?,
    down: FiltersChange.MoveRule?,
    onMove: (FiltersChange.MoveRule) -> Unit,
) {
    val name = rule.name.ifBlank { stringResource(R.string.settings_filters_untitled) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = stringResource(R.string.folders_reorder_drag),
            modifier = handle.size(48.dp).padding(12.dp),
        )
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        MoveSlot(up != null, Icons.Filled.KeyboardArrowUp, stringResource(R.string.folders_reorder_up, name)) { up?.let(onMove) }
        MoveSlot(down != null, Icons.Filled.KeyboardArrowDown, stringResource(R.string.folders_reorder_down, name)) { down?.let(onMove) }
    }
}

/** One rule in the list: summary + enable switch, which writes at once; tap to edit. */
@Composable
private fun RuleRow(rule: FilterRule, trashFolder: String?, switchEnabled: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val context = LocalContext.current
    val name = rule.name.ifBlank { stringResource(R.string.settings_filters_untitled) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                ruleSummary(context, rule, trashFolder),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = rule.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics { contentDescription = name },
            enabled = switchEnabled,
        )
    }
}

@Composable
private fun TextRuleCard(item: FiltersListItem.Text, deleteEnabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    val open = stringResource(R.string.settings_filters_edit_text)
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (item.editable) Modifier.clickable(onClickLabel = open, onClick = onEdit) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (item.name.isNotBlank()) Text(item.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(if (item.editable) R.string.settings_filters_text_rule else R.string.settings_filters_bulwark_rule),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (!item.editable) {
            TextButton(onClick = onDelete, enabled = deleteEnabled) {
                Text(stringResource(R.string.settings_filter_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun TextEditScreen(
    target: TextTarget,
    state: FiltersUiState,
    busy: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(target.start) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun leave() {
        when (editorLeaveStep(changed = text != target.was, saving = state.saving)) {
            EditorLeave.ASK -> confirmDiscard = true
            EditorLeave.LEAVE -> onClose()
            EditorLeave.STAY -> Unit
        }
    }
    BackHandler { leave() }
    if (confirmDiscard) DiscardDialog(onKeep = { confirmDiscard = false }, onDiscard = { confirmDiscard = false; onClose() })
    DetailScaffold(
        title = stringResource(R.string.settings_filters_edit_text),
        onBack = { leave() },
        actions = {
            SaveAction(
                enabled = textSaveEnabled(original = target.was, text = text, busy = busy, present = textTargetPresent(state, target)),
                saving = state.saving,
                onClick = { onSave(text) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            FiltersNoticeLine(state)
            Text(
                stringResource(R.string.settings_filters_text_help),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            FiltersErrorBlock(state)
            TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.settings_filter_delete), color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Full-screen rule editor. Save writes the rule to the server; leaving with changes asks first. */
@Composable
private fun RuleEditScreen(
    target: RuleTarget,
    state: FiltersUiState,
    busy: Boolean,
    onSave: (FilterRule) -> Unit,
    // Null for a rule not written yet: there is nothing on the server to delete.
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val folders = state.folders
    val bulwark = ruleInBulwark(state, target)
    val handCondition = ruleTargetForm(state, target)?.handCondition == true
    var rule by rememberSaveable(stateSaver = FilterRuleSaver) { mutableStateOf(target.start) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun leave() {
        when (editorLeaveStep(changed = ruleEdited(target.original, rule), saving = state.saving)) {
            EditorLeave.ASK -> confirmDiscard = true
            EditorLeave.LEAVE -> onClose()
            EditorLeave.STAY -> Unit
        }
    }
    BackHandler { leave() }
    if (confirmDiscard) DiscardDialog(onKeep = { confirmDiscard = false }, onDiscard = { confirmDiscard = false; onClose() })
    DetailScaffold(
        title = stringResource(R.string.settings_filters_edit_title),
        onBack = { leave() },
        actions = {
            SaveAction(
                enabled = ruleSaveEnabled(original = target.original, rule = rule, busy = busy, present = ruleTargetPresent(state, target)),
                saving = state.saving,
                onClick = { onSave(rule) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Keeps the rule form scrollable above the keyboard while typing (#52).
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            FiltersNoticeLine(state)
            OutlinedTextField(
                value = rule.name,
                onValueChange = { rule = rule.copy(name = it) },
                label = { Text(stringResource(R.string.settings_filter_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SettingsSection(stringResource(R.string.settings_filter_if)) {
                if (rule.conditions.size >= 2) {
                    SettingChoiceRow(
                        title = stringResource(R.string.settings_filter_mode),
                        options = RuleMode.entries,
                        selected = rule.mode,
                        optionLabel = { modeLabel(context, it) },
                        onSelect = { rule = rule.copy(mode = it) },
                    )
                }
                rule.conditions.forEachIndexed { index, condition ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    ConditionEditor(
                        condition = condition,
                        offered = state.offered,
                        bulwark = bulwark,
                        onRemove = if (rule.conditions.size > 1) ({ rule = withoutCondition(rule, index) }) else null,
                        onChange = { changed ->
                            rule = rule.copy(conditions = rule.conditions.mapIndexed { i, c -> if (i == index) changed else c })
                        },
                    )
                }
                if (handCondition) {
                    Text(
                        stringResource(R.string.settings_filter_hand_condition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                TextButton(
                    onClick = { rule = rule.copy(conditions = rule.conditions + Condition()) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_filter_add_condition))
                }
            }
            SettingsSection(stringResource(R.string.settings_filter_then)) {
                rule.actions.forEachIndexed { index, action ->
                    ActionEditor(
                        action = action,
                        folders = folders,
                        trashFolder = state.trashFolder,
                        onRemove = if (actionRemovable(action, target.original)) {
                            { rule = rule.copy(actions = rule.actions.filterIndexed { i, _ -> i != index }) }
                        } else {
                            null
                        },
                        onChange = { changed ->
                            rule = rule.copy(actions = rule.actions.mapIndexed { i, a -> if (i == index) changed else a })
                        },
                    )
                }
                AddActionButton(
                    rule = rule,
                    offered = state.offered,
                    bulwark = bulwark,
                    trashFolder = state.trashFolder,
                    onAdd = { kind -> newAction(kind, folders, state.trashFolder)?.let { rule = withAction(rule, it) } },
                )
            }
            FiltersErrorBlock(state)
            if (onDelete != null) {
                TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.settings_filter_delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

private val FilterRuleSaver = Saver<FilterRule, ArrayList<Any?>>(save = { savedRule(it) }, restore = { restoredRule(it) })

@Composable
private fun ConditionEditor(
    condition: Condition,
    offered: List<String>?,
    bulwark: Boolean,
    onRemove: (() -> Unit)?,
    onChange: (Condition) -> Unit,
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            PickerRow(
                title = stringResource(R.string.settings_filter_field),
                selected = condition.field,
                options = ConditionField.entries,
                optionLabel = { fieldLabel(context, it) },
                optionNote = { availabilityNote(context, fieldAvailability(it, offered, bulwark)) },
                onSelect = { onChange(withField(condition, it, bulwark)) },
            )
            SettingChoiceRow(
                title = stringResource(R.string.settings_filter_match),
                options = offeredMatches(condition.field, bulwark),
                selected = condition.match,
                optionLabel = { matchLabel(context, it) },
                onSelect = { onChange(condition.copy(match = it)) },
            )
            if (condition.field == ConditionField.SIZE) {
                SizeEditor(bytes = condition.value, onChange = { onChange(condition.copy(value = it)) })
            } else {
                OutlinedTextField(
                    value = condition.value,
                    onValueChange = { onChange(condition.copy(value = it)) },
                    label = { Text(stringResource(R.string.settings_filter_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_filter_remove))
            }
        }
    }
}

/** The number is read back from the bytes, never remembered: a row's state follows its place, not its condition. */
@Composable
private fun SizeEditor(bytes: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    var chosen by rememberSaveable { mutableStateOf(sizeUnitOf(bytes)) }
    val unit = if (bytes.isBlank() || sizeInUnit(bytes, chosen).isNotEmpty()) chosen else sizeUnitOf(bytes)
    val number = sizeInUnit(bytes, unit)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = number,
            onValueChange = { typed -> onChange(sizeValue(typed.filter(Char::isDigit), unit)) },
            label = { Text(stringResource(R.string.settings_filter_value)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Box {
            PickerRow(
                title = stringResource(R.string.settings_filter_unit),
                selected = unit,
                options = SizeUnit.entries,
                optionLabel = { unitLabel(context, it) },
                optionNote = { null },
                onSelect = {
                    chosen = it
                    onChange(sizeValue(number, it))
                },
            )
        }
    }
}

@Composable
private fun <T> PickerRow(
    title: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    optionNote: (T) -> String?,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(optionLabel(selected), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val note = optionNote(option)
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(optionLabel(option))
                            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    enabled = note == null,
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun ActionEditor(
    action: Action,
    folders: List<String>,
    trashFolder: String?,
    onRemove: (() -> Unit)?,
    onChange: (Action) -> Unit,
) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            when (val kind = kindOf(action, trashFolder)) {
                ActionKind.MOVE, ActionKind.COPY -> {
                    val folder = (action as? Action.MoveTo)?.folder ?: (action as Action.CopyTo).folder
                    // A target no longer among the offered folders is kept, and kept selected: opening a
                    // rule must not quietly retarget it.
                    val choices = folders.filter { kind == ActionKind.COPY || it != trashFolder } + listOfNotNull(folder.takeIf { it !in folders })
                    SettingChoiceRow(
                        title = actionLabel(context, kind),
                        options = choices,
                        selected = folder,
                        optionLabel = { it },
                        onSelect = { onChange(if (kind == ActionKind.MOVE) Action.MoveTo(it) else Action.CopyTo(it)) },
                    )
                }
                ActionKind.FORWARD -> {
                    val forward = action as Action.ForwardTo
                    val address = forward.address
                    val invalid = address.isNotBlank() && !isValidEmail(address)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { onChange(forward.copy(address = it.trim())) },
                        label = { Text(actionLabel(context, kind)) },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) ({ Text(stringResource(R.string.settings_email_invalid)) }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                ActionKind.LABEL -> Text(
                    stringResource(R.string.settings_filter_label, (action as Action.Label).value),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                else -> Text(
                    actionLabel(context, kind),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_filter_remove))
            }
        }
    }
}

@Composable
private fun AddActionButton(
    rule: FilterRule,
    offered: List<String>?,
    bulwark: Boolean,
    trashFolder: String?,
    onAdd: (ActionKind) -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 8.dp)) {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_filter_add_action))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            addableKinds
                .filter { it != ActionKind.DELETE || trashFolder != null }
                .map { it to actionAvailability(it, rule, offered, bulwark) }
                .filter { (_, availability) -> availability != Availability.ALREADY_THERE }
                .forEach { (kind, availability) ->
                    val note = availabilityNote(context, availability)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(actionLabel(context, kind))
                                if (note != null) Text(note, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        enabled = note == null,
                        onClick = { expanded = false; onAdd(kind) },
                    )
                }
        }
    }
}

/** `foreignActive` holds only for JMAP's `vacation`: ANOTHER_SCRIPT is it behind a failed status read, and Save stops it all the same. */
internal fun foreignScriptLine(notice: ForeignScriptNotice?): Int? = when (notice) {
    null -> null
    ForeignScriptNotice.UNREADABLE_SCRIPT -> R.string.settings_filters_unreadable
    ForeignScriptNotice.STOPS_AUTO_REPLY -> R.string.settings_filters_stops_auto_reply
    ForeignScriptNotice.ANOTHER_SCRIPT -> R.string.settings_filters_stops_auto_reply
}

/** "Sender contains "x", and 1 more → Move to Y, stop": the first condition, how many more, every action. */
private fun ruleSummary(context: Context, rule: FilterRule, trashFolder: String?): String {
    val first = rule.conditions.firstOrNull()?.let { conditionSummary(context, it) }.orEmpty()
    val more = rule.conditions.size - 1
    val cond = if (more > 0) context.resources.getQuantityString(R.plurals.settings_filter_summary_more, more, first, more) else first
    val actions = rule.actions.map { actionSummary(context, it, trashFolder) }
    return if (actions.isEmpty()) cond else "$cond → ${actions.joinToString(", ")}"
}

private fun conditionSummary(context: Context, condition: Condition): String {
    val value = if (condition.field == ConditionField.SIZE) {
        val unit = sizeUnitOf(condition.value)
        unitAmount(context, sizeInUnit(condition.value, unit), unit)
    } else {
        "\"${condition.value}\""
    }
    return "${fieldLabel(context, condition.field)} ${matchLabel(context, condition.match)} $value"
}

private fun actionSummary(context: Context, action: Action, trashFolder: String?): String =
    actionSummaryText(action, trashFolder).let { (res, args) -> context.getString(res, *args.toTypedArray()) }

internal fun actionSummaryText(action: Action, trashFolder: String?): Pair<Int, List<String>> = when (kindOf(action, trashFolder)) {
    ActionKind.MOVE -> R.string.settings_filter_summary_move to listOf((action as Action.MoveTo).folder)
    ActionKind.COPY -> R.string.settings_filter_summary_copy to listOf((action as Action.CopyTo).folder)
    ActionKind.DELETE -> R.string.settings_filter_action_delete to emptyList()
    ActionKind.MARK_READ -> R.string.settings_filter_mark_read to emptyList()
    ActionKind.STAR -> R.string.settings_filter_flag to emptyList()
    ActionKind.FORWARD -> R.string.settings_filter_summary_forward to listOf((action as Action.ForwardTo).address)
    ActionKind.STOP -> R.string.settings_filter_summary_stop to emptyList()
    ActionKind.LABEL -> R.string.settings_filter_label to listOf((action as Action.Label).value)
}

// Only the label's string has an argument, which the others ignore: a label row shows its own value instead.
private fun actionLabel(context: Context, kind: ActionKind): String = context.getString(actionLabelRes(kind), "…")

internal fun actionLabelRes(kind: ActionKind): Int = when (kind) {
    ActionKind.MOVE -> R.string.settings_filter_move_to
    ActionKind.COPY -> R.string.settings_filter_copy_to
    ActionKind.DELETE -> R.string.settings_filter_action_delete
    ActionKind.MARK_READ -> R.string.settings_filter_mark_read
    ActionKind.STAR -> R.string.settings_filter_flag
    ActionKind.FORWARD -> R.string.settings_filter_forward_to
    ActionKind.STOP -> R.string.settings_filter_stop
    ActionKind.LABEL -> R.string.settings_filter_label
}

private fun availabilityNote(context: Context, availability: Availability): String? = when (availability) {
    Availability.OFFERED, Availability.ALREADY_THERE -> null
    Availability.NOT_BY_SERVER -> context.getString(R.string.settings_filter_not_offered)
    Availability.NOT_IN_BULWARK -> context.getString(R.string.settings_filter_not_in_bulwark)
}

private fun unitLabel(context: Context, unit: SizeUnit): String = unitAmount(context, "", unit).trim()

private fun unitAmount(context: Context, amount: String, unit: SizeUnit): String = context.getString(
    if (unit == SizeUnit.MB) R.string.settings_filter_size_mb else R.string.settings_filter_size_kb,
    amount,
)

private fun modeLabel(context: Context, mode: RuleMode): String = context.getString(
    when (mode) {
        RuleMode.ALL -> R.string.settings_filter_mode_all
        RuleMode.ANY -> R.string.settings_filter_mode_any
    },
)

private fun fieldLabel(context: Context, field: ConditionField): String = context.getString(
    when (field) {
        ConditionField.FROM -> R.string.settings_filter_field_from
        ConditionField.TO -> R.string.settings_filter_field_to
        ConditionField.CC -> R.string.settings_filter_field_cc
        ConditionField.SUBJECT -> R.string.settings_filter_field_subject
        ConditionField.LIST -> R.string.settings_filter_field_list
        ConditionField.BODY -> R.string.settings_filter_field_body
        ConditionField.SIZE -> R.string.settings_filter_field_size
    },
)

private fun matchLabel(context: Context, match: RuleMatch): String = context.getString(
    when (match) {
        RuleMatch.CONTAINS -> R.string.settings_filter_match_contains
        RuleMatch.IS -> R.string.settings_filter_match_is
        RuleMatch.NOT_CONTAINS -> R.string.settings_filter_match_not_contains
        RuleMatch.OVER -> R.string.settings_filter_match_over
        RuleMatch.UNDER -> R.string.settings_filter_match_under
    },
)

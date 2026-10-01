package app.sterna.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sterna.R
import app.sterna.contacts.ContactSuggestion
import app.sterna.core.jmap.model.SearchQuery
import app.sterna.ui.compose.RecipientSuggestionMenu
import app.sterna.ui.inbox.InboxViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest

/**
 * The search's Filters sheet: From, To or Cc, Subject, Since, Until. It edits a draft of those five
 * fields and nothing else; Apply hands them to the search, over the words and chips of the moment.
 * System Back dismisses it without applying, as a tap outside does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilterSheet(
    criteria: SearchQuery,
    names: AddressNames,
    viewModel: InboxViewModel,
    onDismiss: () -> Unit,
) {
    var from by rememberSaveable { mutableStateOf(criteria.from) }
    var recipient by rememberSaveable { mutableStateOf(criteria.recipient) }
    var subject by rememberSaveable { mutableStateOf(criteria.subject) }
    var since by rememberSaveable { mutableStateOf(criteria.afterMillis) }
    var until by rememberSaveable { mutableStateOf(criteria.beforeMillis) }
    var named by rememberSaveable(stateSaver = AddressNamesSaver) { mutableStateOf(names.keptFor(criteria)) }
    val applied = sheetFields(from, recipient, subject, since, until, named)
    val draft = applied.query
    val addressSuggestions by viewModel.addressSuggestions.collectAsStateWithLifecycle()
    val addressFocus by viewModel.addressFocus.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    fun commit(sheet: SheetFields) {
        focusManager.clearFocus()
        viewModel.dismissAddressSuggestions()
        viewModel.applySearchFields(sheet.query, sheet.names)
        onDismiss()
    }

    fun picked(field: AddressField, contact: ContactSuggestion) {
        val next = viewModel.pickAddress(field, contact, draft)
        from = next.from
        recipient = next.recipient
        named = named.picked(field, contact)
    }

    fun edited(field: AddressField, received: String) {
        val edit = addressEdited(named.shown(field, draft), received, named.of(field), draft.address(field))
        when (field) {
            AddressField.FROM -> from = edit.criterion
            AddressField.RECIPIENT -> recipient = edit.criterion
        }
        named = named.withName(field, edit.name)
        viewModel.addressTyped(field, edit.criterion)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = {
            viewModel.dismissAddressSuggestions()
            onDismiss()
        },
        sheetState = sheetState,
    ) {
        ReopenWhenTheKeyboardMoves(sheetState)
        // Scrollable: landscape, a large font scale or the keyboard cut the height the five
        // fields and the two buttons get, and the buttons must stay reachable.
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.search_filters),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AddressCriterion(
                field = AddressField.FROM,
                value = named.shown(AddressField.FROM, draft),
                label = R.string.search_from,
                onValueChange = { edited(AddressField.FROM, it) },
                suggestions = suggestionsUnder(AddressField.FROM, addressFocus, addressSuggestions),
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onPick = { picked(AddressField.FROM, it) },
                viewModel = viewModel,
            )
            // To or Cc, one field: a message carrying the address only in copy was still received at it.
            AddressCriterion(
                field = AddressField.RECIPIENT,
                value = named.shown(AddressField.RECIPIENT, draft),
                label = R.string.search_to_or_cc,
                onValueChange = { edited(AddressField.RECIPIENT, it) },
                suggestions = suggestionsUnder(AddressField.RECIPIENT, addressFocus, addressSuggestions),
                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                onPick = { picked(AddressField.RECIPIENT, it) },
                viewModel = viewModel,
            )
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text(stringResource(R.string.search_subject)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(applied) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            SearchDateRow(stringResource(R.string.search_since), since) { picked ->
                since = picked?.let(::searchAfterBound)
            }
            SearchDateRow(stringResource(R.string.search_until), until) { picked ->
                until = picked?.let(::searchBeforeBound)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { commit(SheetFields(SearchQuery(), AddressNames())) }) { Text(stringResource(R.string.search_clear_all)) }
                Button(onClick = { commit(applied) }) { Text(stringResource(R.string.search_apply)) }
            }
        }
    }
}

/**
 * material3 1.3 sizes the sheet's anchors to the room above the keyboard and, in `ModalBottomSheet`'s
 * `draggableAnchors` block, carries a `Hidden` target across every anchor change. A sheet whose
 * target has slipped to `Hidden` while the keyboard rises is snapped to the keyboard's top edge, still
 * open, its fields and Apply under the keys. What makes it slip is not established; the suspect is the
 * remainder of the scroll that brings the focused field into view, which `ContentInViewNode` sends as
 * `UserInput` and the sheet takes as a drag. Called inside the sheet: only its dialog window's insets
 * carry the keyboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReopenWhenTheKeyboardMoves(sheetState: SheetState) {
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    LaunchedEffect(sheetState, ime, density) {
        var previousPx = ime.getBottom(density)
        snapshotFlow { ime.getBottom(density) }
            .collectLatest { imePx ->
                val beforePx = previousPx
                previousPx = imePx
                // The anchors move in the layout pass that follows the inset, so they are read a frame later.
                withFrameNanos { }
                if (reopensUnderKeyboard(beforePx, imePx, sheetState.targetValue)) {
                    // A finger dragging the sheet holds it at a higher priority and refuses this.
                    try {
                        sheetState.expand()
                    } catch (refused: CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
    }
}

/**
 * Whether the sheet is reopened after the keyboard's height went from [beforePx] to [imePx]. Only a
 * rising keyboard counts: a `hide()` started by a tap outside or a drag runs while the keyboard is
 * still or falling, and reopening then would undo a dismissal the user asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
internal fun reopensUnderKeyboard(beforePx: Int, imePx: Int, target: SheetValue): Boolean =
    imePx > beforePx && target == SheetValue.Hidden

/**
 * One active sheet criterion under the search bar, "From: Alex Martin ×" for a picked contact,
 * "From: alex@example.org ×" for a typed address. A tap anywhere on it
 * removes it and runs the search again; the cross says so to a screen reader.
 */
@Composable
fun SearchFieldInputChip(chip: SearchFieldChip, enabled: Boolean, onRemove: () -> Unit) {
    val label = when (chip.field) {
        SearchField.FROM -> criterionPair(R.string.search_from, chip.text)
        SearchField.RECIPIENT -> criterionPair(R.string.search_to_or_cc, chip.text)
        SearchField.SUBJECT -> criterionPair(R.string.search_subject, chip.text)
        // The date labels are worded as prepositions, so they take the value without a colon.
        SearchField.SINCE -> criterionDate(R.string.search_since, chip.millis)
        SearchField.UNTIL -> criterionDate(R.string.search_until, chip.millis)
    }
    InputChip(
        selected = true,
        onClick = onRemove,
        enabled = enabled,
        label = { Text(label, maxLines = 1) },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.compose_remove),
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

@Composable
private fun criterionPair(@StringRes label: Int, value: String): String =
    stringResource(R.string.search_criteria_pair, stringResource(label), value)

@Composable
private fun criterionDate(@StringRes label: Int, millis: Long?): String =
    stringResource(
        R.string.search_criteria_pair_date,
        stringResource(label),
        millis?.let { formatSearchDate(searchBoundDay(it)) }.orEmpty(),
    )

@Composable
private fun AddressCriterion(
    field: AddressField,
    value: String,
    @StringRes label: Int,
    onValueChange: (String) -> Unit,
    suggestions: List<ContactSuggestion>,
    onNext: () -> Unit,
    onPick: (ContactSuggestion) -> Unit,
    viewModel: InboxViewModel,
) {
    var fieldWidthPx by remember { mutableIntStateOf(0) }
    var fieldBottomPx by remember { mutableIntStateOf(0) }
    var windowHeightPx by remember { mutableIntStateOf(0) }
    // Only a change of focus is reported: a prefilled criterion opens no menu until it is touched.
    var focused by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(TextFieldValue(value)) }
    val shown = edited.showing(value)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = {
                edited = it
                if (it.text != shown.text) onValueChange(it.text)
            },
            label = { Text(stringResource(label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { fs ->
                    if (fs.isFocused == focused) return@onFocusChanged
                    focused = fs.isFocused
                    if (fs.isFocused) viewModel.addressFocused(field, value) else viewModel.addressLeft(field)
                }
                .onGloballyPositioned {
                    fieldWidthPx = it.size.width
                    fieldBottomPx = (it.positionInRoot().y + it.size.height).roundToInt()
                    windowHeightPx = it.findRootCoordinates().size.height
                },
        )
        RecipientSuggestionMenu(
            suggestions = suggestions,
            visible = focused,
            fieldWidthPx = fieldWidthPx,
            fieldBottomPx = fieldBottomPx,
            windowHeightPx = windowHeightPx,
            onPick = onPick,
            onDismiss = viewModel::dismissAddressSuggestions,
        )
    }
}

/** A tappable row that shows the chosen date (or "Any") and opens a date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchDateRow(label: String, boundMillis: Long?, onPick: (Long?) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                boundMillis?.let { formatSearchDate(searchBoundDay(it)) }
                    ?: stringResource(R.string.search_date_any),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (boundMillis != null) {
            IconButton(onClick = { onPick(null) }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_vacation_clear_date))
            }
        }
    }
    if (showPicker) {
        // The picker speaks UTC midnight, the query holds a local-day bound: hand it back the day.
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = boundMillis?.let(::searchPickerMillis),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onPick(pickerState.selectedDateMillis)
                    showPicker = false
                }) { Text(stringResource(R.string.settings_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text(stringResource(R.string.settings_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun formatSearchDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

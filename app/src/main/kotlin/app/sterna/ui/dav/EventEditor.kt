package app.sterna.ui.dav

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sterna.R
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** The platform's own date then time dialogs: no picker library. [withTime] false picks the date only. */
private fun pick(context: Context, current: LocalDateTime, withTime: Boolean, onPicked: (LocalDateTime) -> Unit) {
    DatePickerDialog(context, { _, y, m, d ->
        val day = LocalDate.of(y, m + 1, d)
        if (!withTime) onPicked(day.atTime(current.toLocalTime()))
        else TimePickerDialog(context, { _, h, min -> onPicked(day.atTime(h, min)) }, current.hour, current.minute, android.text.format.DateFormat.is24HourFormat(context)).show()
    }, current.year, current.monthValue - 1, current.dayOfMonth).show()
}

/** Create or edit an event or task. [onDelete] is null for a new one. */
@Composable
fun EventEditorDialog(
    initial: EventForm,
    calendars: List<CalendarInfo>,
    onSave: (EventForm) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var f by remember { mutableStateOf(initial) }
    val editing = onDelete != null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(f) }, enabled = f.title.isNotBlank() && f.calendarId.isNotEmpty()) { Text(stringResource(R.string.dav_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.dav_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dav_cancel)) }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.title, { f = f.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cal_title_hint)) }, singleLine = true)
                if (!f.isTask) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(f.allDay, { f = f.copy(allDay = it) })
                        Text(stringResource(R.string.cal_all_day))
                    }
                    PickRow(R.string.cal_start, f.start, !f.allDay, context) { f = f.copy(start = it, end = f.end?.takeIf { e -> e >= it } ?: it.plusHours(1)) }
                    PickRow(R.string.cal_end, f.end ?: f.start, !f.allDay, context) { f = f.copy(end = it) }
                } else {
                    PickRow(R.string.cal_due, f.end ?: f.start, true, context) { f = f.copy(end = it) }
                    if (f.end != null) TextButton(onClick = { f = f.copy(end = null) }) { Text(stringResource(R.string.cal_no_due)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(f.completed, { f = f.copy(completed = it) })
                        Text(stringResource(R.string.cal_done))
                    }
                }
                Choice(stringResource(R.string.cal_calendar), calendars.firstOrNull { it.id == f.calendarId }?.name.orEmpty(), calendars.map { it.name to it.id }) { f = f.copy(calendarId = it) }
                val repeats = Repeat.entries.map { stringResource(repeatLabel(it)) to it }
                Choice(
                    stringResource(R.string.cal_repeat),
                    if (f.repeat == null) stringResource(R.string.cal_repeat_custom) else stringResource(repeatLabel(f.repeat!!)),
                    repeats.map { it.first to it.second.name },
                ) { f = f.copy(repeat = Repeat.valueOf(it)) }
                if (f.repeat != null && f.repeat != Repeat.NONE) {
                    OutlinedTextField(
                        f.repeatCount?.toString().orEmpty(), { v -> f = f.copy(repeatCount = v.filter(Char::isDigit).take(4).toIntOrNull()) },
                        Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cal_repeat_count)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                if (editing && (initial.repeat != Repeat.NONE)) Text(stringResource(R.string.cal_series_note), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                val alerts = AlertPreset.entries.map { stringResource(alertLabel(it)) to it }
                Choice(
                    stringResource(R.string.cal_alert),
                    if (f.alert == null) stringResource(R.string.cal_alert_custom) else stringResource(alertLabel(f.alert!!)),
                    alerts.map { it.first to it.second.name },
                ) { f = f.copy(alert = AlertPreset.valueOf(it)) }
                if (!f.isTask) {
                    OutlinedTextField(
                        f.attendees ?: "", { f = f.copy(attendees = it) }, Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.cal_attendees)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )
                }
                OutlinedTextField(f.description, { f = f.copy(description = it) }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.cal_notes)) }, minLines = 2)
            }
        },
    )
}

@Composable
private fun PickRow(label: Int, value: LocalDateTime, withTime: Boolean, context: Context, modifier: Modifier = Modifier, onPicked: (LocalDateTime) -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(label))
        OutlinedButton(onClick = { pick(context, value, withTime, onPicked) }) {
            Text(DATE.format(value) + if (withTime) " " + TIME.format(value.toLocalTime()) else "")
        }
    }
}

@Composable
private fun Choice(label: String, current: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        OutlinedButton(onClick = { open = true }) {
            Text(current)
            DropdownMenu(open, { open = false }) {
                options.forEach { (name, value) -> DropdownMenuItem(text = { Text(name) }, onClick = { open = false; onPick(value) }) }
            }
        }
    }
}

private fun repeatLabel(r: Repeat) = when (r) {
    Repeat.NONE -> R.string.cal_repeat_none
    Repeat.DAILY -> R.string.cal_repeat_daily
    Repeat.WEEKLY -> R.string.cal_repeat_weekly
    Repeat.MONTHLY -> R.string.cal_repeat_monthly
    Repeat.YEARLY -> R.string.cal_repeat_yearly
}

private fun alertLabel(a: AlertPreset) = when (a) {
    AlertPreset.NONE -> R.string.cal_alert_none
    AlertPreset.AT_START -> R.string.cal_alert_at_start
    AlertPreset.MIN10 -> R.string.cal_alert_10m
    AlertPreset.MIN30 -> R.string.cal_alert_30m
    AlertPreset.HOUR1 -> R.string.cal_alert_1h
    AlertPreset.DAY1 -> R.string.cal_alert_1d
}

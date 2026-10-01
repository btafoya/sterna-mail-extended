package app.sterna.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.sterna.R
import app.sterna.ui.compose.pickedScheduleMillis
import app.sterna.ui.compose.scheduleClockOpensAt
import app.sterna.ui.compose.scheduleDaySelectable
import app.sterna.ui.compose.scheduleFirstYear
import app.sterna.ui.compose.schedulePickerAsInput
import java.time.ZoneId

enum class PickInstantStep { None, Day, Time }

/**
 * The two dialogs of "pick date and time" (#161): a calendar, then a clock. Nothing is held here
 * but the pickers' own state: the step, the chosen day and every way out belong to the caller,
 * which is what lets the step and the day survive a rotation taken between the two dialogs.
 *
 * @param onDayChosen the day the calendar returned, a UTC midnight, never local midnight.
 * @param onPicked called only with an instant strictly ahead, read with a fresh clock at the tap,
 * along with the hour and minute the dial showed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickInstantDialogs(
    step: PickInstantStep,
    chosenDay: Long?,
    onDayChosen: (dayUtcMidnightMillis: Long) -> Unit,
    onDismiss: () -> Unit,
    onPicked: (millis: Long, hour: Int, minute: Int) -> Unit,
) {
    val context = LocalContext.current
    when (step) {
        PickInstantStep.None -> Unit
        PickInstantStep.Day -> {
            // In landscape the calendar's grid does not fit: `DatePickerDialog` lays its surface out at a
            // max height, never a min, so turned, the ceiling is the screen's 411 dp and Material's content
            val dayConfiguration = LocalConfiguration.current
            val dayAsInput = schedulePickerAsInput(dayConfiguration.screenHeightDp, dayConfiguration.screenWidthDp)
            // Two bounds, one apiece, not interchangeable. `yearRange` decides which years the chevron
            // draws: Material's default of 1900..2100 put a hundred and twenty-six dead years in front of
            // this one. `isSelectableDate` is the lock. No upper bound of ours.
            val dayState = rememberDatePickerState(
                yearRange = scheduleFirstYear(System.currentTimeMillis(), ZoneId.systemDefault())..DatePickerDefaults.YearRange.last,
                initialDisplayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        scheduleDaySelectable(utcTimeMillis, System.currentTimeMillis(), ZoneId.systemDefault())
                },
            )
            // `rememberDatePickerState` saves its displayMode: the restored state comes back on the face
            // it was opened with, and `initialDisplayMode` is never consulted again. Without this effect
            // the defect's own gesture survives the fix, open in portrait, then turn. Keyed on the shape
            // of the window and nothing else.
            LaunchedEffect(dayAsInput) {
                dayState.displayMode = if (dayAsInput) DisplayMode.Input else DisplayMode.Picker
            }
            // Both faces answer with the same two buttons, so their bodies exist once and are handed to
            // whichever shell is drawn. They are `@Composable` slots, not values.
            val dayOk: @Composable () -> Unit = {
                TextButton(
                    // Nothing is scheduled here: the day is kept, this dialog closes and the clock opens.
                    // Until a day is picked the picker hands back null, so OK stays grey.
                    enabled = dayState.selectedDateMillis != null,
                    onClick = { onDayChosen(dayState.selectedDateMillis ?: 0L) },
                ) { Text(stringResource(R.string.settings_ok)) }
            }
            val dayCancel: @Composable () -> Unit = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
            if (dayAsInput) {
                SchedulePickerDialog(
                    onDismissRequest = onDismiss,
                    picker = {
                        // The calendar is composed here only on the typed face, and this guard is not the
                        // same test as the branch above: `rememberDatePickerState` restores the face it was
                        // left on, and the branch above reads the face the user asked for.
                        if (dayState.displayMode == DisplayMode.Input) {
                            DatePicker(state = dayState, showModeToggle = false)
                        }
                    },
                    buttons = { dayCancel(); dayOk() },
                )
            } else {
                DatePickerDialog(
                    onDismissRequest = onDismiss,
                    // `usePlatformDefaultWidth = false` is not an addition of ours: it is
                    // `DatePickerDialog`'s own default being put back, since handing it a `DialogProperties`
                    // replaces the whole object. `decorFitsSystemWindows = false` and `safeDrawingPadding()`
                    // stay on this branch too: Material's own pencil is up here.
                    modifier = Modifier.safeDrawingPadding(),
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
                    confirmButton = dayOk,
                    dismissButton = dayCancel,
                ) {
                    // Material's own face toggle stays exactly as Material puts it on THIS branch: the
                    // grid fits here. It is the shell above that hides it, because there the pencil leads
                    // into a crash.
                    DatePicker(state = dayState, showModeToggle = true)
                }
            }
        }
        PickInstantStep.Time -> {
            val zone = ZoneId.systemDefault()
            // 0L is 1970, a day that is never strictly ahead, so a clock that somehow opened with no day
            // behind it can only refuse.
            val day = chosenDay ?: 0L
            // Not Material's default of midnight: for today that is an hour long gone, so the dial would
            // open on precisely the state the OK button refuses, greyed out with no wording to explain it.
            val opensAt = scheduleClockOpensAt(day, System.currentTimeMillis(), zone)
            // The device's own 12/24-hour setting, not a forced 24 h: this dial is read by whoever set
            // that preference.
            val timeState = rememberTimePickerState(
                initialHour = opensAt.hour,
                initialMinute = opensAt.minute,
                is24Hour = android.text.format.DateFormat.is24HourFormat(context),
            )
            // In landscape Material lays its own dial out beside the time display, wider than the dialog.
            // Measured on `emu` at 914×411 dp: the right half of the dial was off screen, its digits drew
            // over the AM/PM selector, and `PM` was unreachable. Scrolling is not the answer: the cut is
            // HORIZONTAL. So the keyboard entry, on Material's own threshold.
            val configuration = LocalConfiguration.current
            val clockAsInput = schedulePickerAsInput(configuration.screenHeightDp, configuration.screenWidthDp)
            // [SchedulePickerDialog], a shell of ours, and not Material's `AlertDialog`: what makes the
            // difference is the software keyboard, and Material puts the focus in `TimeInput`'s hour field
            // so the keys come up on their own. The reading that bought that shell is written over it.
            SchedulePickerDialog(
                onDismissRequest = onDismiss,
                picker = {
                    if (clockAsInput) TimeInput(state = timeState) else TimePicker(state = timeState)
                },
                buttons = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    TextButton(
                        // Lock 2, the one the calendar cannot give: "today, at an hour already gone". Read
                        // from the picker's own state, so the button lights and unlights while the dial
                        // turns.
                        enabled = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, System.currentTimeMillis()) != null,
                        onClick = {
                            // Asked again, with a fresh clock. A button lit at composition and tapped
                            // after the minute turned would otherwise schedule into the past, which is to
                            // say send at once, irreversibly.
                            val millis = pickedScheduleMillis(day, timeState.hour, timeState.minute, zone, System.currentTimeMillis())
                            // The dialog closes only on the way that schedules something: closing on a
                            // refusal would take the clock off screen having scheduled nothing and said
                            // nothing. And only correcting it greys the button, `enabled` reads the wall
                            // clock, which is not snapshot state, a window at most the tail of one minute.
                            if (millis != null) {
                                onPicked(millis, timeState.hour, timeState.minute)
                            }
                        },
                    ) { Text(stringResource(R.string.settings_ok)) }
                },
            )
        }
    }
}

/**
 * The dialog both "pick date and time" pickers wear on their typed face (#161), where the software
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SchedulePickerDialog(
    onDismissRequest: () -> Unit,
    picker: @Composable () -> Unit,
    buttons: @Composable () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.safeDrawingPadding(),
        properties = DialogProperties(decorFitsSystemWindows = false),
    ) {
        Surface(
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .align(Alignment.CenterHorizontally)
                        .verticalScroll(rememberScrollState()),
                ) {
                    picker()
                }
                // A flow row, not a plain one, because Material's `AlertDialogFlowRow`, which this
                // shell replaces, wraps its actions onto a second line. At 360 dp with the font
                FlowRow(
                    modifier = Modifier.align(Alignment.End).padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    buttons()
                }
            }
        }
    }
}

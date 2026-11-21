package app.sterna.ui.dav

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.sterna.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

private val DAY = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
private val SHORT_DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val TIME = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** Calendar, events and tasks of one account. Reads Room through the view model; every write is queued and shown at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(accountId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: CalendarViewModel = viewModel(key = "calendar/$accountId", factory = viewModelFactory { initializer { CalendarViewModel(app, accountId) } })
    val mode by vm.mode.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val failed by vm.failed.collectAsStateWithLifecycle()

    var detail by remember { mutableStateOf<CalItem?>(null) }
    var editor by remember { mutableStateOf<Pair<CalItem?, EventForm>?>(null) }
    var calendarsMenu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CalItem?>(null) }
    val tasksMode = mode == CalMode.TASKS

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cal_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.dav_back)) }
                },
                actions = {
                    if (!tasksMode) {
                        IconButton(onClick = { vm.layout.value = if (layout == CalLayout.AGENDA) CalLayout.MONTH else CalLayout.AGENDA }) {
                            Icon(
                                if (layout == CalLayout.AGENDA) Icons.Filled.CalendarMonth else Icons.Filled.ViewAgenda,
                                stringResource(if (layout == CalLayout.AGENDA) R.string.cal_month else R.string.cal_agenda),
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { calendarsMenu = true }) { Icon(Icons.Filled.Menu, stringResource(R.string.cal_calendars)) }
                        DropdownMenu(calendarsMenu, { calendarsMenu = false }) {
                            calendars.forEach { c ->
                                Row(Modifier.clickable { vm.setVisible(c.id, !c.visible) }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(c.visible, { vm.setVisible(c.id, it) })
                                    Text(c.name)
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                vm.blankForm(day, tasksMode)?.let { editor = null to it }
            }) { Icon(Icons.Filled.Add, stringResource(if (tasksMode) R.string.cal_add_task else R.string.cal_add_event)) }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                CalMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m, onClick = { vm.mode.value = m },
                        shape = SegmentedButtonDefaults.itemShape(i, CalMode.entries.size),
                    ) { Text(stringResource(if (m == CalMode.EVENTS) R.string.cal_events else R.string.cal_tasks)) }
                }
            }
            if (pending > 0) Text(stringResource(R.string.dav_waiting_to_send), Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall)
            failed.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${stringResource(R.string.dav_failed_title)}: ${f.lastError.orEmpty()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.dismissFailed(f.id) }) { Text(stringResource(R.string.dav_dismiss)) }
                }
            }
            if (tasksMode) {
                if (tasks.isEmpty()) Empty(R.string.cal_no_tasks)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tasks, key = { it.rowId }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { detail = t }.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t.completed, { vm.setCompleted(t, it) })
                            Column(Modifier.weight(1f)) {
                                Text(t.title.ifBlank { stringResource(R.string.dav_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                t.startMs?.let { Text(whenText(t), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.cal_prev)) }
                    Text(
                        if (layout == CalLayout.MONTH) "${month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())} ${month.year}" else SHORT_DATE.format(day),
                        Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    )
                    TextButton(onClick = vm::today) { Text(stringResource(R.string.cal_today)) }
                    IconButton(onClick = { vm.shift(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.cal_next)) }
                }
                val byDay = items.groupBy { dayOf(it.startMs) }
                if (layout == CalLayout.MONTH) {
                    MonthGrid(vm, month, day, byDay.keys)
                    HorizontalDivider()
                    DayList(listOf(day to byDay[day].orEmpty()), onOpen = { detail = it })
                } else {
                    DayList(byDay.toSortedMap().map { it.key to it.value }, onOpen = { detail = it })
                }
            }
        }
    }

    detail?.let { item ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(item.title.ifBlank { stringResource(R.string.dav_untitled) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (item.startMs != null) Text(whenText(item))
                    (item.json["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
            },
            confirmButton = { TextButton(onClick = { editor = item to vm.formFor(item); detail = null }) { Text(stringResource(R.string.cal_edit)) } },
            dismissButton = { TextButton(onClick = { deleting = item }) { Text(stringResource(R.string.dav_delete)) } },
        )
    }
    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(item.title.ifBlank { stringResource(R.string.dav_untitled) }) },
            text = { Text(stringResource(R.string.cal_delete_confirm)) },
            confirmButton = { TextButton(onClick = { vm.delete(item); deleting = null; detail = null; editor = null }) { Text(stringResource(R.string.dav_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.dav_cancel)) } },
        )
    }
    editor?.let { (editing, form) ->
        EventEditorDialog(
            initial = form, calendars = calendars,
            onSave = { vm.save(editing, it); editor = null },
            onDelete = editing?.let { e -> { deleting = e } },
            onDismiss = { editor = null },
        )
    }
}

private fun dayOf(ms: Long?): LocalDate = Instant.ofEpochMilli(ms ?: 0).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Mar 1, 2026, 10:00 AM – 11:00 AM", or the date alone for an all-day item. */
private fun whenText(i: CalItem): String {
    val z = ZoneId.systemDefault()
    val s = Instant.ofEpochMilli(i.startMs ?: return "").atZone(z)
    if (i.allDay) return SHORT_DATE.format(s)
    val e = i.endMs?.takeIf { it != i.startMs }?.let { Instant.ofEpochMilli(it).atZone(z) }
    return SHORT_DATE.format(s) + " " + TIME.format(s) + (e?.let { " – " + (if (it.toLocalDate() == s.toLocalDate()) "" else SHORT_DATE.format(it) + " ") + TIME.format(it) } ?: "")
}

@Composable
private fun Empty(text: Int) = Text(stringResource(text), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)

@Composable
private fun DayList(days: List<Pair<LocalDate, List<CalItem>>>, onOpen: (CalItem) -> Unit) {
    if (days.all { it.second.isEmpty() }) Empty(R.string.cal_no_events)
    LazyColumn(Modifier.fillMaxSize()) {
        days.forEach { (date, list) ->
            if (list.isEmpty()) return@forEach
            item(key = "h$date") { Text(DAY.format(date), Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
            items(list, key = { "$date/${it.rowId}/${it.startMs}" }) { i ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(i.title.ifBlank { stringResource(R.string.dav_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (i.allDay) stringResource(R.string.cal_all_day) else whenText(i), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(vm: CalendarViewModel, month: java.time.YearMonth, selected: LocalDate, busy: Set<LocalDate>) {
    val cells = monthGrid(month, vm.weekStart)
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            cells.take(7).forEach { d ->
                Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            }
        }
        cells.chunked(7).forEach { week ->
            Row {
                week.forEach { d ->
                    val inMonth = java.time.YearMonth.from(d) == month
                    Box(
                        Modifier.weight(1f).aspectRatio(1.3f)
                            .clickable { vm.pickDay(d) }
                            .semantics { contentDescription = DAY.format(d) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                d.dayOfMonth.toString(),
                                fontWeight = if (d == selected) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    d == selected -> MaterialTheme.colorScheme.primary
                                    inMonth -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            // A dot, not a count: what is on the day is listed below when it is picked.
                            if (d in busy) Text("•", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

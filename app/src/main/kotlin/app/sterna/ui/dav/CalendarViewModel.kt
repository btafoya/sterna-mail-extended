package app.sterna.ui.dav

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.dav.CalendarOccurrence
import app.sterna.core.data.dav.expand
import app.sterna.core.data.db.DavObjectEntity
import app.sterna.core.data.db.DavPendingEntity
import app.sterna.core.jmap.JmapType
import app.sterna.dav.Dav
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

enum class CalMode { EVENTS, TASKS }
enum class CalLayout { AGENDA, MONTH }

class CalendarInfo(val id: String, val name: String, val visible: Boolean, val isDefault: Boolean)

/** One thing to draw: an occurrence of an event, or a task. [rowId] is the DavRepository id. */
class CalItem(
    val rowId: String, val calendarId: String, val json: JsonObject, val startMs: Long?, val endMs: Long?, val allDay: Boolean,
    /** The local start the series gave this occurrence; empty for a one-off event and a task. */
    val recurrenceId: String = "",
) {
    val recurring: Boolean get() = recurrenceId.isNotEmpty()
    val title: String get() = (json["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val completed: Boolean get() = (json["progress"] as? JsonPrimitive)?.contentOrNull == "completed"
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(private val app: Application, private val accountId: String) : ViewModel() {
    private val repo = app.container.davRepository

    val mode = MutableStateFlow(CalMode.EVENTS)
    val layout = MutableStateFlow(CalLayout.AGENDA)
    val month = MutableStateFlow(YearMonth.now())
    val selectedDay = MutableStateFlow(LocalDate.now())

    val weekStart: DayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek

    val calendars: StateFlow<List<CalendarInfo>> = repo.watch(accountId, JmapType.CALENDAR).map { rows ->
        rows.map {
            val j = Json.parseToJsonElement(it.json).jsonObject
            CalendarInfo(it.id, it.name, (j["isVisible"] as? JsonPrimitive)?.booleanOrNull ?: true, (j["isDefault"] as? JsonPrimitive)?.booleanOrNull == true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pending: StateFlow<Int> = repo.watchPendingCount(accountId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val failed: StateFlow<List<DavPendingEntity>> = repo.watchFailed(accountId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The window the current layout shows. Agenda: 60 days from [selectedDay]; month: the grid. */
    private val window = combine(layout, month, selectedDay) { l, m, d ->
        if (l == CalLayout.AGENDA) d to d.plusDays(60)
        else monthGrid(m, weekStart).let { it.first() to it.last().plusDays(1) }
    }

    /** Events in the window, recurring ones expanded, hidden calendars left out. Re-read when the cache changes. */
    val items: StateFlow<List<CalItem>> = combine(window, calendars, repo.watch(accountId, JmapType.CALENDAR_EVENT)) { w, cals, _ -> w to cals }
        .flatMapLatest { (w, cals) ->
            flow {
                val zone = ZoneId.systemDefault()
                val hidden = cals.filterNot { it.visible }.mapTo(HashSet()) { it.id }
                emit(
                    repo.occurrences(accountId, w.first.atStartOfDay(zone).toInstant().toEpochMilli(), w.second.atStartOfDay(zone).toInstant().toEpochMilli())
                        .filter { it.row.groupId !in hidden }
                        .map { it.toItem() },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasks: StateFlow<List<CalItem>> = combine(repo.watchTasks(accountId), calendars) { rows, cals ->
        val hidden = cals.filterNot { it.visible }.mapTo(HashSet()) { it.id }
        rows.filter { it.groupId !in hidden }
            .map { it.toItem() }
            // Open tasks first, by due time, the ones without a due date last.
            .sortedWith(compareBy<CalItem> { it.completed }.thenBy { it.startMs ?: Long.MAX_VALUE })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun CalendarOccurrence.toItem() = CalItem(
        row.id, row.groupId, occurrence.json, occurrence.startMs, occurrence.endMs,
        (occurrence.json["showWithoutTime"] as? JsonPrimitive)?.booleanOrNull == true,
        occurrence.recurrenceId,
    )

    private fun DavObjectEntity.toItem() = Json.parseToJsonElement(json).jsonObject.let {
        CalItem(id, groupId, it, startMs, endMs, (it["showWithoutTime"] as? JsonPrimitive)?.booleanOrNull == true)
    }

    fun shift(step: Int) {
        if (layout.value == CalLayout.MONTH) month.value = month.value.plusMonths(step.toLong())
        else selectedDay.value = selectedDay.value.plusDays(30L * step)
    }

    fun today() {
        month.value = YearMonth.now()
        selectedDay.value = LocalDate.now()
    }

    fun pickDay(day: LocalDate) {
        selectedDay.value = day
        if (layout.value == CalLayout.MONTH && YearMonth.from(day) != month.value) month.value = YearMonth.from(day)
    }

    /** The calendar a new item goes to: the default one, else the first. Null until calendars have synced. */
    fun defaultCalendarId(): String? = calendars.value.firstOrNull { it.isDefault }?.id ?: calendars.value.firstOrNull()?.id

    /** The editor's blank form for [day] (events start at 09:00 for an hour; tasks have no due date). */
    fun blankForm(day: LocalDate, task: Boolean): EventForm? {
        val cal = defaultCalendarId() ?: return null
        val start = LocalDateTime.of(day, java.time.LocalTime.of(9, 0))
        return EventForm(isTask = task, start = start, end = if (task) null else start.plusHours(1), calendarId = cal)
    }

    fun formFor(item: CalItem): EventForm = formOf(item.json, item.calendarId, LocalDateTime.now())

    /** [scope] matters only for an occurrence of a series; anything else is saved as it is. */
    fun save(editing: CalItem?, form: EventForm, scope: Scope = Scope.ALL) {
        val zone = ZoneId.systemDefault().id
        viewModelScope.launch {
            if (editing == null) {
                repo.create(accountId, JmapType.CALENDAR_EVENT, applyForm(null, form, UUID.randomUUID().toString(), zone))
            } else {
                // The stored object, not the occurrence: an occurrence's json can carry a per-instance override.
                val stored = repo.get(accountId, JmapType.CALENDAR_EVENT, editing.rowId)?.let { Json.parseToJsonElement(it.json).jsonObject } ?: return@launch
                val rid = editing.recurrenceId
                when {
                    !editing.recurring || scope == Scope.ALL || (scope == Scope.FOLLOWING && isFirstOccurrence(stored, rid)) -> {
                        val whole = if (editing.recurring) wholeSeriesForm(stored, rid, form) else form
                        val patch = diffPatch(stored, applyForm(stored, whole, "", zone))
                        if (patch.isNotEmpty()) repo.update(accountId, JmapType.CALENDAR_EVENT, editing.rowId, patch)
                    }
                    scope == Scope.THIS -> editOccurrencePatch(stored, rid, form, zone).takeIf { it.isNotEmpty() }
                        ?.let { repo.update(accountId, JmapType.CALENDAR_EVENT, editing.rowId, it) }
                    else -> {
                        val next = followingSeries(stored, rid, occurrencesBefore(stored, rid, editing.startMs), form, UUID.randomUUID().toString(), zone)
                        repo.update(accountId, JmapType.CALENDAR_EVENT, editing.rowId, cutSeriesPatch(stored, rid))
                        repo.create(accountId, JmapType.CALENDAR_EVENT, next)
                    }
                }
            }
            Dav.enqueue(app)
        }
    }

    /** How many occurrences the series' own rule makes before [rid]; excluded ones count, as `count` counts them. */
    private fun occurrencesBefore(stored: JsonObject, rid: String, startMs: Long?): Int =
        expand(JsonObject(stored - "recurrenceOverrides" - "excludedRecurrenceRules"), 0, (startMs ?: 0) + 366L * 86_400_000)
            .occurrences.count { it.recurrenceId < rid }

    /** [scope] matters only for an occurrence of a series. */
    fun delete(item: CalItem, scope: Scope = Scope.ALL) {
        viewModelScope.launch {
            val stored = if (item.recurring && scope != Scope.ALL) {
                repo.get(accountId, JmapType.CALENDAR_EVENT, item.rowId)?.let { Json.parseToJsonElement(it.json).jsonObject } ?: return@launch
            } else null
            when {
                stored == null || (scope == Scope.FOLLOWING && isFirstOccurrence(stored, item.recurrenceId)) ->
                    repo.destroy(accountId, JmapType.CALENDAR_EVENT, item.rowId)
                scope == Scope.THIS -> repo.update(accountId, JmapType.CALENDAR_EVENT, item.rowId, excludePatch(stored, item.recurrenceId))
                else -> repo.update(accountId, JmapType.CALENDAR_EVENT, item.rowId, cutSeriesPatch(stored, item.recurrenceId))
            }
            Dav.enqueue(app)
        }
    }

    fun setCompleted(item: CalItem, done: Boolean) {
        viewModelScope.launch {
            val patch = JsonObject(
                mapOf(
                    "progress" to JsonPrimitive(if (done) "completed" else "needs-action"),
                    "percentComplete" to (if (done) JsonPrimitive(100) else kotlinx.serialization.json.JsonNull),
                ),
            )
            repo.update(accountId, JmapType.CALENDAR_EVENT, item.rowId, patch)
            Dav.enqueue(app)
        }
    }

    fun setVisible(calendarId: String, visible: Boolean) {
        viewModelScope.launch {
            repo.update(accountId, JmapType.CALENDAR, calendarId, JsonObject(mapOf("isVisible" to JsonPrimitive(visible))))
            Dav.enqueue(app)
        }
    }

    fun dismissFailed(id: Long) {
        viewModelScope.launch { repo.dismissFailed(id) }
    }
}

package app.sterna.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the "pick date and time" dialogs stand: who asked for them ([target]), the day the calendar
 * returned, and the step that follows from the two. Held by a ViewModel for the reason given on
 * `MoveSheetOpen`, and saved through [read] and [write] for the same reason.
 *
 * The step is not saved: it is decided by [target] and [day] alone, so no restored state can say
 * "clock" with no day behind it.
 */
class PickInstantOpen(read: (key: String) -> String?, private val write: (key: String, value: String?) -> Unit) {
    private val _target = MutableStateFlow(read(TARGET))
    val target: StateFlow<String?> = _target.asStateFlow()

    private val _day = MutableStateFlow(if (_target.value == null) null else read(DAY)?.toLongOrNull())
    val day: StateFlow<Long?> = _day.asStateFlow()

    private val _step = MutableStateFlow(stepOf(_target.value, _day.value))
    val step: StateFlow<PickInstantStep> = _step.asStateFlow()

    fun open(target: String) = set(target, day = null)

    /** Ignored with nothing open: a late tap must not reopen dialogs that were closed under it. */
    fun chooseDay(dayUtcMidnightMillis: Long) {
        val target = _target.value ?: return
        set(target, dayUtcMidnightMillis)
    }

    fun close() = set(target = null, day = null)

    private fun set(target: String?, day: Long?) {
        _target.value = target
        _day.value = day
        _step.value = stepOf(target, day)
        write(TARGET, target)
        write(DAY, day?.toString())
    }

    companion object {
        private const val TARGET = "pickInstant.target"
        private const val DAY = "pickInstant.day"

        /**
         * The inbox selection's target. Never equal to a reader's target, a
         * `MessagePaging.entryKey`, which always holds a NUL between account and id.
         */
        const val SELECTION = "selection"

        private fun stepOf(target: String?, day: Long?): PickInstantStep = when {
            target == null -> PickInstantStep.None
            day == null -> PickInstantStep.Day
            else -> PickInstantStep.Time
        }

        /** Whether the host asking for [wanted] draws the dialogs. */
        fun shows(target: String?, wanted: String): Boolean = target != null && target == wanted

        /**
         * The reader's dialogs were opened on a message other than [activeKey], the one now read;
         * null when no message is read yet. The selection's target is never the reader's to close.
         */
        fun readerLeft(target: String?, activeKey: String?): Boolean =
            target != null && target != SELECTION && target != activeKey

        /** Opened for the selection, which is now empty, cleared or lost with the process. */
        fun selectionGone(target: String?, selectionEmpty: Boolean): Boolean =
            target == SELECTION && selectionEmpty

        /**
         * Opened for a row the list no longer has. Asked of a list actually read, never of a
         * placeholder empty one: restored after a process death, the row is simply not read back yet.
         */
        fun rowGone(target: String?, rowKeys: Collection<String>): Boolean =
            target != null && target !in rowKeys
    }
}

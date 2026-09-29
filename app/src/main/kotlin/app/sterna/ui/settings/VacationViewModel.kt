package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.FilterScriptWarning
import app.sterna.core.data.filter.ScriptSnapshot
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.filter.refreshedFilterWarning
import app.sterna.core.data.mail.VacationState
import app.sterna.core.imap.ManageSieveException
import app.sterna.core.jmap.model.VacationResponse
import app.sterna.ui.sender.trashFilePath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Which transient error (if any) the vacation screen should surface. */
enum class VacationError { LOAD, SAVE, INVALID_DATES }

/** UI state for the vacation responder. Dates are epoch-millis at UTC midnight of the chosen day
 *  (what Material's picker speaks), converted to RFC 8621 UTCDate strings at the JMAP boundary. */
data class VacationUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val supported: Boolean = true,
    val unavailableCause: UnavailableCause? = null,
    val accountLabel: String = "",
    val enabled: Boolean = false,
    val subject: String = "",
    val message: String = "",
    val fromDate: Long? = null,
    val toDate: Long? = null,
    val saving: Boolean = false,
    val errorKind: VacationError? = null,
    val errorDetail: String = "",
    val errorSieveKind: ManageSieveException.Kind? = null,
    /** Bumped after each successful save; the screen shows a confirmation while > 0. */
    val savedTick: Int = 0,
    /** Whether the edited fields still differ from what the server holds. Gates Save, so the button
     *  is offered only when it has something to push (#34). */
    val dirty: Boolean = false,
    /** What the account's Sieve scripts say about the user's filter rules, or null for nothing to
     *  say. Never part of [edits]: it describes the server, not the form. */
    val filterWarning: FilterScriptWarning? = null,
    val foreignActiveScript: Boolean = false,
) {
    /** The fields a Save actually writes, what "changed" is measured on. */
    internal fun edits(): List<Any?> = listOf(enabled, subject, message, fromDate, toDate)
}

/** Drives the server-side vacation responder (JMAP VacationResponse). Every read and write is a
 *  network round-trip, so the state carries explicit loading / saving / error. */
class VacationViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(VacationUiState())
    val state = _state.asStateFlow()

    /** The responder as the server last confirmed it; edits are compared to this (#34). */
    private var serverEdits: List<Any?> = VacationUiState().edits()

    private var readScript: ScriptSnapshot? = null

    private var staleSave: FilterScriptChangedException? = null

    init { load() }

    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = VacationUiState(loading = false, noAccount = true)
            return
        }
        _state.update { it.copy(loading = true, errorKind = null) }
        viewModelScope.launch {
            try {
                when (val result = repo.accounts.loadVacation(credentials)) {
                    VacationState.Unsupported, VacationState.AccountUnsupported ->
                        _state.value = vacationUnavailableState(
                            state = result,
                            accountLabel = store.accountLabel(),
                        )
                    is VacationState.Loaded -> {
                        val r = result.response
                        // Before the form is published: the warning is about the switch three
                        // rows below, and published late it arrives after a fast hand has flipped
                        // it and pressed Save. The screen holds its spinner one more round trip.
                        val warning = refreshedFilterWarning(
                            previous = null,
                            status = repo.accounts.loadFilterScriptStatus(credentials),
                            responderEnabled = r.isEnabled,
                        )
                        _state.value = VacationUiState(
                            loading = false,
                            supported = true,
                            accountLabel = store.accountLabel(),
                            enabled = r.isEnabled,
                            subject = r.subject.orEmpty(),
                            message = r.textBody.orEmpty(),
                            fromDate = r.fromDate?.let { parseMillis(it) },
                            toDate = r.toDate?.let { parseMillis(it) },
                            filterWarning = warning,
                            foreignActiveScript = result.foreignActiveScript,
                        )
                        serverEdits = _state.value.edits()
                        readScript = result.script
                        reportStaleSave()
                    }
                }
            } catch (t: Throwable) {
                _state.update { vacationFailedState(it.copy(loading = false), VacationError.LOAD, t) }
            }
        }
    }

    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }
    fun setSubject(value: String) = edit { it.copy(subject = value) }
    fun setMessage(value: String) = edit { it.copy(message = value) }
    fun setFromDate(millis: Long?) = edit { it.copy(fromDate = millis) }
    fun setToDate(millis: Long?) = edit { it.copy(toDate = millis) }

    /** Apply a field edit, clearing any pending error / saved-confirmation. */
    private fun edit(transform: (VacationUiState) -> VacationUiState) =
        _state.update {
            val next = transform(it).copy(errorKind = null, savedTick = 0)
            next.copy(dirty = next.edits() != serverEdits)
        }

    private fun reportStaleSave() {
        staleSave?.let { t -> _state.update { vacationFailedState(it, VacationError.SAVE, t) } }
        staleSave = null
    }

    fun save() {
        val credentials = store.load() ?: return
        val s = _state.value
        if (s.fromDate != null && s.toDate != null && s.fromDate > s.toDate) {
            _state.update { it.copy(errorKind = VacationError.INVALID_DATES) }
            return
        }
        _state.update { it.copy(saving = true, errorKind = null) }
        viewModelScope.launch {
            var written = false
            try {
                val vacation = VacationResponse(
                    isEnabled = s.enabled,
                    fromDate = s.fromDate?.let { isoStartOfDay(it) },
                    toDate = s.toDate?.let { isoEndOfDay(it) },
                    subject = s.subject.ifBlank { null },
                    textBody = s.message.ifBlank { null },
                    htmlBody = null,
                )
                val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                    .getOrElseUnlessCancelled { emptyList() }
                readScript = repo.accounts.saveVacation(credentials, vacation, readScript, trashFolder = trashFilePath(mailboxes)).script
                serverEdits = s.edits()
                _state.update { vacationSavedState(it) }
                written = true
            } catch (t: Throwable) {
                _state.update { vacationFailedState(it.copy(saving = false), VacationError.SAVE, t) }
                if (t is FilterScriptChangedException) {
                    staleSave = t
                    load()
                }
            }
            // Outside the try, and after the state that lets a "save then leave" go: that exit
            // cancels this read, and inside the catch above a cancellation would be painted as a
            // failed save over a write the server has already taken.
            if (written) {
                val status = repo.accounts.loadFilterScriptStatus(credentials)
                _state.update {
                    it.copy(
                        filterWarning = refreshedFilterWarning(
                            previous = it.filterWarning,
                            status = status,
                            responderEnabled = s.enabled,
                        ),
                    )
                }
            }
        }
    }

    private fun parseMillis(iso: String): Long? =
        runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()

    /** Normalise a picked day to 00:00:00 UTC and render as an RFC 8621 UTCDate. */
    private fun isoStartOfDay(millis: Long): String =
        Instant.ofEpochMilli(millis - (millis % DAY_MS)).toString()

    /** End-of-day UTC (23:59:59) for the picked day, so the day is inclusive. */
    private fun isoEndOfDay(millis: Long): String =
        Instant.ofEpochMilli(millis - (millis % DAY_MS) + DAY_MS - 1000).toString()

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

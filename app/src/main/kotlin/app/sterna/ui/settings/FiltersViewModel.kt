package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.filter.BulwarkMetadata
import app.sterna.core.data.getOrElseUnlessCancelled
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.SieveScript
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.imap.ManageSieveException
import app.sterna.ui.inbox.mailboxFilePath
import app.sterna.ui.sender.trashFilePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FiltersError { LOAD, SAVE, TEXT_UNCUT, ACTIVATE, CHANGED }

data class FiltersUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val supported: Boolean = true,
    val foreignActive: Boolean = false,
    /** The rules below are on the server but not running: the script carrying them is not the active
     *  one. Distinct from [foreignActive], which is false in the very state this flag exists for:
     *  a vacation responder switched on and off again leaves no script active at all. */
    val rulesNotRunning: Boolean = false,
    /** The script named `vacation` is the active one: the auto-reply is running, and a write would
     * switch it off by activating Sterna's script. Activity, not existence, an inactive
     *  `vacation` script beside a third active one must not claim the auto-reply is at stake. */
    val vacationScriptActive: Boolean = false,
    /** The opened script cannot be cut into blocks, so a write would replace content nobody read. */
    val scriptUnreadable: Boolean = false,
    val accountLabel: String = "",
    val scriptName: String? = null,
    val scripts: List<ListedScript> = emptyList(),
    val scriptNotActive: Boolean = false,
    val activating: Boolean = false,
    val scriptRules: List<ScriptRule> = emptyList(),
    /** Folder paths offered in the "move to folder" picker, and stored as the rule's target.
     * A path, not a name: Sieve names a subfolder by its whole path, so "Done" alone reaches the
     *  wrong folder the moment two folders share that last segment. */
    val folders: List<String> = emptyList(),
    val trashFolder: String? = null,
    val offered: List<String>? = null,
    val bulwark: Boolean = false,
    val saving: Boolean = false,
    val errorKind: FiltersError? = null,
    val errorDetail: String = "",
    val errorSieveKind: ManageSieveException.Kind? = null,
    /** The id of a write that landed, until the screen has closed its editor for it. An event, not
     *  a count: nothing saved across the death of the process can be compared with it. */
    val landed: Int? = null,
    /** A write held until the user confirms replacing a script nobody could read. */
    val overwritePending: FiltersChange? = null,
    /** An edit the server refused because the script changed meanwhile, offered back in its editor. */
    val proposal: FiltersChange? = null,
    /** A change refused by `unchangedSince`, to be decided over the script read again. */
    val retry: FiltersChange? = null,
    val replayedOnce: Boolean = false,
)

/** Drives the server-side filter rules (Sieve, over JMAP or ManageSieve): each gesture is one write
 *  (validate, write, activate only when no script was). Every read and write is a network round-trip. */
class FiltersViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(FiltersUiState())
    val state = _state.asStateFlow()

    private var opened: FilterRulesState.Loaded? = null

    private var staleFailure: Pair<FiltersError, Throwable>? = null

    private var pickedScript: String? = null

    private var loads = 0

    private var writes = 0

    private var loadJob: Job? = null

    init { load() }

    fun openScript(name: String) {
        pickedScript = name
        opened = null
        _state.update { it.leavingScript() }
        load()
    }

    fun load() {
        val credentials = store.load()
        if (credentials == null) {
            _state.value = FiltersUiState(loading = false, noAccount = true)
            return
        }
        _state.update { it.copy(loading = true, errorKind = null) }
        loads++
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                // Whole paths, resolved against the account's folder list: the server knows
                // "INBOX.ProjectA.Done", not "Done". mapNotNull, so a folder the app cannot name
                // with certainty is not offered at all.
                val mailboxes = runCatching { repo.observeMailboxes(credentials.id).first() }
                    .getOrElseUnlessCancelled { emptyList() }
                val folders = mailboxes.mapNotNull { mb -> mailboxFilePath(mb, mailboxes) }
                val trash = trashFilePath(mailboxes)
                when (val result = repo.accounts.loadFilterRules(credentials, pickedScript)) {
                    FilterRulesState.Unsupported -> {
                        opened = null
                        _state.value = filtersUnavailableState(
                            accountLabel = store.accountLabel(),
                        )
                    }
                    is FilterRulesState.Loaded -> {
                        opened = result
                        // Before the list is published: these flags decide what the red line says,
                        // whether the restart is offered and whether a replay may write.
                        val read = filtersStateWithStatus(
                            state = filtersLoadedBase(_state.value, store.accountLabel(), folders, trash).withScript(result),
                            status = repo.accounts.loadFilterScriptStatus(credentials, result.scriptName),
                            foreignFromRulesRead = result.foreignActiveScript,
                            unreadableFromRulesRead = result.scriptUnreadable,
                        )
                        val (next, again) = filtersReread(read, result)
                        _state.value = next
                        reportStaleFailure()
                        again?.let { ask(it, fromUser = false) }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { filtersLoadFailed(it, t) }
            }
        }
    }

    fun activate() {
        val credentials = store.load() ?: return
        val name = opened?.scriptName ?: return
        if (!filtersAcceptEdits(_state.value)) return
        _state.update { it.copy(activating = true, loading = true, errorKind = null) }
        viewModelScope.launch {
            try {
                repo.accounts.activateScript(credentials, name)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                staleFailure = FiltersError.ACTIVATE to t
            }
            _state.update { it.copy(activating = false) }
            load()
        }
    }

    fun proposalTaken() = _state.update { it.copy(proposal = null) }

    fun landedTaken() = _state.update { it.copy(landed = null) }

    fun dismissError() = _state.update { if (it.saving) it else it.copy(errorKind = null) }

    private fun reportStaleFailure() {
        staleFailure?.let { (kind, t) -> _state.update { filtersFailedState(it, kind, t) } }
        staleFailure = null
    }

    fun save(change: FiltersChange) = ask(change, fromUser = true)

    private var parsed: Pair<FilterRulesState.Loaded, SieveScript>? = null

    fun drop(from: Int, to: Int): FiltersChange.MoveRule? {
        val base = opened ?: return null
        if (filtersBusy(_state.value)) return null
        val script = parsed?.takeIf { it.first === base }?.second ?: base.scriptToEdit.also { parsed = base to it }
        return filtersDrop(script.rules, script, from, to)
    }

    fun confirmOverwrite() {
        val (next, now) = filtersOverwriteConfirmed(_state.value)
        _state.value = next
        now?.let(::write)
    }

    fun cancelOverwrite() = _state.update { it.copy(overwritePending = null) }

    private fun ask(change: FiltersChange, fromUser: Boolean) {
        val (next, now) = filtersSaveAsked(_state.value, change, fromUser)
        _state.value = next
        now?.let(::write)
    }

    private fun write(change: FiltersChange) {
        val credentials = store.load() ?: return
        val base = opened ?: return
        val generation = loads
        _state.update { it.copy(saving = true, errorKind = null) }
        viewModelScope.launch {
            var written: String? = null
            try {
                val write = when (val plan = filtersWrite(base, change)) {
                    is FiltersWritePlan.Write -> plan.write
                    FiltersWritePlan.TextUncut -> throw SieveTextUncut()
                    // Read again and decided there, like a script changed elsewhere: the list this
                    // change was made on is not the script the write would go over.
                    FiltersWritePlan.BlockGone -> throw FilterScriptChangedException()
                }
                val saved = repo.accounts.saveFilterScript(credentials, write)
                if (generation != loads) return@launch
                opened = saved
                _state.update { filtersWriteLanded(it, saved, ++writes) }
                written = write.name
            } catch (t: Throwable) {
                if (generation != loads) return@launch
                _state.update { filtersWriteFailed(it, change, t) }
                if (reloadsAfterFailedSave(t)) load()
            }
            // Outside the try: a cancellation there would be painted as a failed save over rules the
            // server has already taken. All three flags come from this read, so they cannot
            // describe two moments; a failed read moves none.
            if (written != null) {
                val status = repo.accounts.loadFilterScriptStatus(credentials, written)
                if (generation == loads) _state.update { filtersStateWithStatus(state = it, status = status) }
            }
        }
    }
}

internal fun FiltersUiState.withScript(loaded: FilterRulesState.Loaded): FiltersUiState = copy(
    scriptName = loaded.scriptName,
    scripts = loaded.scripts,
    scriptNotActive = loaded.inactiveBesideActive,
    scriptRules = loaded.scriptRules,
    offered = loaded.sieveExtensions,
    bulwark = loaded.script?.bulwarkMetadata == BulwarkMetadata.READABLE,
)

internal fun FiltersUiState.leavingScript(): FiltersUiState =
    FiltersUiState(loading = true, accountLabel = accountLabel, folders = folders, trashFolder = trashFolder)

internal fun filtersAcceptEdits(state: FiltersUiState): Boolean = !state.loading && !state.activating

internal fun filtersSaveFailed(state: FiltersUiState, failure: Throwable): FiltersUiState {
    val kind = if (failure is SieveTextUncut) FiltersError.TEXT_UNCUT else FiltersError.SAVE
    return filtersFailedState(state.copy(saving = false), kind, failure)
}

internal fun reloadsAfterFailedSave(failure: Throwable): Boolean = failure is FilterScriptChangedException

internal class SieveTextUncut : IllegalArgumentException("a rule edited as text leaves a bracket, a string or a comment open")

internal sealed interface FiltersListItem {
    /** [rank]: how many blocks above hold the very same, so a change names this one among twins. */
    data class Form(val rule: FilterRule, val rank: Int = 0) : FiltersListItem

    data class Text(val name: String, val text: String, val editable: Boolean, val rank: Int = 0) : FiltersListItem
}

/** Every block of the opened script, in the order the script gives them. */
internal fun filtersListing(state: FiltersUiState): List<FiltersListItem> {
    val seen = mutableMapOf<Any, Int>()
    fun rankOf(key: Any): Int = seen.getOrDefault(key, 0).also { seen[key] = it + 1 }
    return state.scriptRules.map { rule ->
        when (rule) {
            is ScriptRule.Form -> FiltersListItem.Form(rule.rule, rankOf(rule.rule))
            is ScriptRule.Text -> FiltersListItem.Text(
                name = rule.name,
                text = rule.text,
                editable = !rule.lockedByBulwark,
                rank = rankOf(Triple(rule.name, rule.text, rule.lockedByBulwark)),
            )
        }
    }
}

package app.sterna.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sterna.container
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.ListedScript
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.FilterScriptWrite
import app.sterna.core.data.filter.formRulesWrite
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.imap.ManageSieveException
import app.sterna.ui.inbox.mailboxFilePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FiltersError { LOAD, SAVE, TEXT_UNCUT, ACTIVATE }

data class FiltersUiState(
    val loading: Boolean = true,
    val noAccount: Boolean = false,
    val supported: Boolean = true,
    val foreignActive: Boolean = false,
    /** The rules below are on the server but not running: the script carrying them is not the active
     *  one. Distinct from [foreignActive], which is false in the very state this flag exists for:
     *  a vacation responder switched on and off again leaves no script active at all. */
    val rulesNotRunning: Boolean = false,
    /** The script named `vacation` is the active one: the auto-reply is running, and Save would
     * switch it off by activating Sterna's script. Activity, not existence, an inactive
     *  `vacation` script beside a third active one must not claim the auto-reply is at stake. */
    val vacationScriptActive: Boolean = false,
    /** The opened script cannot be cut into blocks, so Save would replace content nobody read. */
    val scriptUnreadable: Boolean = false,
    val accountLabel: String = "",
    val scriptName: String? = null,
    val scripts: List<ListedScript> = emptyList(),
    val scriptNotActive: Boolean = false,
    val activating: Boolean = false,
    val scriptRules: List<ScriptRule> = emptyList(),
    val rules: List<FilterRule> = emptyList(),
    /** For each of [rules], the id of the form rule it was read as, null for one added since. */
    val ruleIds: List<Int?> = emptyList(),
    val textEdits: Map<Int, String> = emptyMap(),
    val removedText: Set<Int> = emptySet(),
    /** Folder paths offered in the "move to folder" picker, and stored as the rule's target.
     * A path, not a name: Sieve names a subfolder by its whole path, so "Done" alone reaches the
     *  wrong folder the moment two folders share that last segment. */
    val folders: List<String> = emptyList(),
    val saving: Boolean = false,
    val errorKind: FiltersError? = null,
    val errorDetail: String = "",
    val errorSieveKind: ManageSieveException.Kind? = null,
    /** Bumped after each successful save; the screen shows a confirmation while > 0. */
    val savedTick: Int = 0,
    /** The list differs from what the server holds: Save is offered only then (#34), and goes out if the edits are undone. */
    val dirty: Boolean = false,
)

/** Drives the server-side filter rules (Sieve, over JMAP or ManageSieve): edited locally and pushed
 *  in one Save (validate, write, activate only when no script was). Every read and write is a network round-trip. */
class FiltersViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = application.container.mailRepository
    private val store = application.container.accountStore

    private val _state = MutableStateFlow(FiltersUiState())
    val state = _state.asStateFlow()

    /** The rules as the server last confirmed them; edits are compared to these (#34). */
    private var serverRules: List<FilterRule> = emptyList()

    private var opened: FilterRulesState.Loaded? = null

    private var staleFailure: Pair<FiltersError, Throwable>? = null

    private var pickedScript: String? = null

    private var loads = 0

    private var loadJob: Job? = null

    init { load() }

    fun openScript(name: String) {
        pickedScript = name
        opened = null
        serverRules = emptyList()
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
                val folders = runCatching {
                    repo.observeMailboxes(credentials.id).first()
                        .let { all -> all.mapNotNull { mb -> mailboxFilePath(mb, all) } }
                }.getOrDefault(emptyList())
                when (val result = repo.accounts.loadFilterRules(credentials, pickedScript)) {
                    FilterRulesState.Unsupported -> {
                        serverRules = emptyList()
                        opened = null
                        _state.value = filtersUnavailableState(
                            accountLabel = store.accountLabel(),
                        )
                    }
                    is FilterRulesState.Loaded -> {
                        serverRules = result.rules
                        opened = result
                        // Before the list is published: these flags decide whether Save is
                        // offered and what the red line says, so a list drawn without them claims
                        // the rules are running and greys out the gesture that restores them.
                        _state.value = filtersStateWithStatus(
                            state = FiltersUiState(
                                loading = false,
                                supported = true,
                                accountLabel = store.accountLabel(),
                                folders = folders,
                            ).withScript(result),
                            status = repo.accounts.loadFilterScriptStatus(credentials, result.scriptName),
                            foreignFromRulesRead = result.foreignActiveScript,
                            unreadableFromRulesRead = result.scriptUnreadable,
                        )
                        reportStaleFailure()
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { filtersFailedState(it.copy(loading = false), FiltersError.LOAD, t) }
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

    fun addRule() = edit { it.copy(rules = it.rules + FilterRule(), ruleIds = it.ruleIds + listOf(null)) }

    /** Commits an edited rule; an untouched one is dropped instead of left as a ghost row. */
    fun updateRule(index: Int, rule: FilterRule) = edit {
        it.copy(
            rules = it.rules.toMutableList().also { list ->
                if (rule.isEmpty) list.removeAt(index) else list[index] = rule
            },
            ruleIds = if (rule.isEmpty) it.ruleIds.toMutableList().also { ids -> ids.removeAt(index) } else it.ruleIds,
        )
    }

    fun removeRule(index: Int) = edit {
        it.copy(
            rules = it.rules.toMutableList().also { list -> list.removeAt(index) },
            ruleIds = it.ruleIds.toMutableList().also { ids -> ids.removeAt(index) },
        )
    }

    fun setRuleEnabled(index: Int, enabled: Boolean) = edit {
        it.copy(rules = it.rules.toMutableList().also { list -> list[index] = list[index].copy(enabled = enabled) })
    }

    fun updateText(id: Int, text: String) = edit { it.withTextEdit(id, text) }

    fun removeText(id: Int) = edit { it.withTextRemoved(id) }

    private fun edit(transform: (FiltersUiState) -> FiltersUiState) =
        _state.update {
            if (!filtersAcceptEdits(it)) return@update it
            val next = transform(it).copy(errorKind = null, savedTick = 0)
            next.copy(dirty = filtersDirty(next, serverRules))
        }

    private fun reportStaleFailure() {
        staleFailure?.let { (kind, t) -> _state.update { filtersFailedState(it, kind, t) } }
        staleFailure = null
    }

    fun save() {
        val credentials = store.load() ?: return
        val base = opened ?: return
        val edited = _state.value
        if (!filtersAcceptEdits(edited)) return
        val generation = loads
        _state.update { it.copy(saving = true, errorKind = null) }
        viewModelScope.launch {
            var written: String? = null
            try {
                val write = filtersWrite(base, edited) ?: throw SieveTextUncut()
                val saved = repo.accounts.saveFilterScript(credentials, write)
                if (generation != loads) return@launch
                opened = saved
                serverRules = saved.rules
                _state.update {
                    it.withScript(saved).copy(saving = false, savedTick = it.savedTick + 1, dirty = false)
                }
                written = write.name
            } catch (t: Throwable) {
                if (generation != loads) return@launch
                _state.update { filtersSaveFailed(it, t) }
                if (reloadsAfterFailedSave(t)) {
                    staleFailure = FiltersError.SAVE to t
                    load()
                }
            }
            // Outside the try, and after the state that lets a "save then leave" go: that exit
            // cancels this read, and inside the catch above a cancellation would be painted as a
            // failed save over rules the server has already taken. All three flags come from this
            // read, so they cannot describe two moments; a failed read moves none.
            if (written != null) {
                val status = repo.accounts.loadFilterScriptStatus(credentials, written)
                if (generation == loads) _state.update { filtersStateWithStatus(state = it, status = status) }
            }
        }
    }
}

internal fun FiltersUiState.withScript(loaded: FilterRulesState.Loaded): FiltersUiState {
    val forms = loaded.scriptRules.filterIsInstance<ScriptRule.Form>()
    return copy(
        scriptName = loaded.scriptName,
        scripts = loaded.scripts,
        scriptNotActive = loaded.inactiveBesideActive,
        scriptRules = loaded.scriptRules,
        rules = forms.map { it.rule },
        ruleIds = forms.map { it.id },
        textEdits = emptyMap(),
        removedText = emptySet(),
    )
}

/** What Save writes over the script [opened], under its name; null when an edited text cannot be cut into blocks. */
internal fun filtersWrite(opened: FilterRulesState.Loaded, state: FiltersUiState): FilterScriptWrite? {
    // Forms first: a text edited into a form's shape reads back as a form rule, which `withFormRules` would drop.
    val forms = formRulesWrite(opened, state.ruleIds, state.rules)
    var script = forms.script
    for (id in state.removedText) script = script.remove(id)
    for ((id, text) in state.textEdits) script = script.replaceText(id, text) ?: return null
    return FilterScriptWrite(forms.read, script)
}

internal fun FiltersUiState.leavingScript(): FiltersUiState =
    FiltersUiState(loading = true, accountLabel = accountLabel, folders = folders)

internal fun filtersAcceptEdits(state: FiltersUiState): Boolean = !state.loading && !state.activating

internal fun filtersDirty(state: FiltersUiState, serverRules: List<FilterRule>): Boolean =
    state.rules != serverRules || state.textEdits.isNotEmpty() || state.removedText.isNotEmpty()

internal fun FiltersUiState.withTextEdit(id: Int, text: String): FiltersUiState {
    val read = scriptRules.firstOrNull { it.id == id } as? ScriptRule.Text ?: return this
    if (read.lockedByBulwark) return this
    return copy(textEdits = if (text == read.text) textEdits - id else textEdits + (id to text))
}

internal fun FiltersUiState.withTextRemoved(id: Int): FiltersUiState =
    copy(textEdits = textEdits - id, removedText = removedText + id)

internal fun filtersSaveFailed(state: FiltersUiState, failure: Throwable): FiltersUiState {
    val kind = if (failure is SieveTextUncut) FiltersError.TEXT_UNCUT else FiltersError.SAVE
    return filtersFailedState(state.copy(saving = false), kind, failure)
}

internal fun reloadsAfterFailedSave(failure: Throwable): Boolean = failure is FilterScriptChangedException

internal class SieveTextUncut : IllegalArgumentException("a rule edited as text leaves a bracket, a string or a comment open")

internal sealed interface FiltersListItem {
    data class Form(val index: Int, val rule: FilterRule) : FiltersListItem

    data class Text(val id: Int, val name: String, val text: String, val editable: Boolean) : FiltersListItem
}

internal fun filtersListing(state: FiltersUiState): List<FiltersListItem> {
    val formAt = state.ruleIds.withIndex().mapNotNull { (i, id) -> id?.let { it to i } }.toMap()
    val read = state.scriptRules.mapNotNull { rule ->
        when (rule) {
            is ScriptRule.Form -> formAt[rule.id]?.let { FiltersListItem.Form(it, state.rules[it]) }
            is ScriptRule.Text -> if (rule.id in state.removedText) null else FiltersListItem.Text(
                id = rule.id,
                name = rule.name,
                text = state.textEdits[rule.id] ?: rule.text,
                editable = !rule.lockedByBulwark,
            )
        }
    }
    val added = state.ruleIds.indices.filter { state.ruleIds[it] == null }.map { FiltersListItem.Form(it, state.rules[it]) }
    return read + added
}

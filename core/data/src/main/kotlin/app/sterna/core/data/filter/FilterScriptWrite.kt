package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/** A script as it was read: its name and its text, both null when there was none to open. */
data class ScriptSnapshot(val name: String?, val text: String?)

/** [script] to be written over the script [read]; the write is refused when the server no longer holds [read]. */
class FilterScriptWrite(val read: ScriptSnapshot, val script: SieveScript) {
    /** The name written: the script read, or `sterna` when there was none. */
    val name: String get() = read.name ?: SieveCodec.SCRIPT_NAME
}

/** Thrown before anything is written: what [FilterScriptWrite.read] saw is no longer on the server. */
class FilterScriptChangedException :
    IllegalStateException("the filter script changed on the server since it was opened; it has been read again")

/**
 * Whether a write may go over the script the server holds now, [name] opened with [text]. Any difference
 * refuses it: a rule or an auto-reply added elsewhere would be overwritten, an unreadable one replaced unasked.
 */
fun unchangedSince(read: ScriptSnapshot, name: String?, text: String?): Boolean = read == ScriptSnapshot(name, text)

/** On JMAP, the active script is the server's `VacationResponse`: a save would activate another and stop it. */
fun stopsServerResponder(scripts: List<ListedScript>, serverResponder: Boolean): Boolean =
    serverResponder && scripts.firstOrNull { it.isActive }?.name == VACATION_SCRIPT_NAME

/** The filters list saved over [opened]: [rules] paired by [ids] with the rules read, empty rules dropped. */
fun formRulesWrite(opened: FilterRulesState.Loaded, ids: List<Int?>, rules: List<FilterRule>): FilterScriptWrite {
    val kept = rules.indices.filterNot { rules[it].isEmpty }
    return FilterScriptWrite(opened.snapshot, withFormRules(opened.scriptToEdit, kept.map { ids[it] }, kept.map { rules[it] }))
}

/** A sender block written over [opened]: one block more, the rest byte for byte. */
fun blockWrite(opened: FilterRulesState.Loaded, rule: FilterRule): FilterScriptWrite =
    FilterScriptWrite(opened.snapshot, opened.scriptToEdit.add(rule))

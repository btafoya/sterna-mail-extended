package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

data class ListedScript(val name: String, val isActive: Boolean)

/** The active script, else `sterna`, else none. [serverResponder]: on JMAP an active `vacation` is the server's responder. */
fun openedScriptName(scripts: List<ListedScript>, serverResponder: Boolean): String? {
    val active = scripts.firstOrNull { it.isActive }
        ?.takeUnless { serverResponder && it.name == VACATION_SCRIPT_NAME }
    return active?.name ?: scripts.firstOrNull { it.name == SieveCodec.SCRIPT_NAME }?.name
}

/** Only when no script was active, since activating switches the running one off; the server's responder excepted. */
fun activatesOnSave(scripts: List<ListedScript>, serverResponder: Boolean): Boolean {
    val active = scripts.firstOrNull { it.isActive } ?: return true
    return serverResponder && active.name == VACATION_SCRIPT_NAME
}

/** What reading the script [scriptName] answers, [scriptText] as the server returned it, null when none was opened. */
fun loadedFilterRules(scriptName: String?, scriptText: String?, stopsServerResponder: Boolean = false): FilterRulesState.Loaded {
    val parsed = scriptText?.let { SieveScript.parse(it) }
    val unreadable = parsed is SieveParse.Unreadable
    val rules = (parsed as? SieveParse.Readable)?.script?.rules.orEmpty()
    return FilterRulesState.Loaded(
        rules = rules.filterIsInstance<ScriptRule.Form>().map { it.rule },
        foreignActiveScript = unreadable || stopsServerResponder,
        scriptUnreadable = unreadable,
        scriptName = scriptName,
        scriptText = scriptText,
    )
}

/** [base] with its form rules made [edited], [ids] naming the rule each was read as (null: new); other blocks stay. */
fun withFormRules(base: SieveScript, ids: List<Int?>, edited: List<FilterRule>): SieveScript {
    require(ids.size == edited.size) { "${ids.size} ids for ${edited.size} rules" }
    val loaded = base.rules.filterIsInstance<ScriptRule.Form>().associate { it.id to it.rule }
    var script = base
    for (gone in loaded.keys - ids.filterNotNull().toSet()) script = script.remove(gone)
    for ((id, rule) in ids.zip(edited)) {
        script = when {
            id == null -> script.add(rule)
            loaded[id] != rule -> script.update(id, rule)
            else -> script
        }
    }
    return script
}

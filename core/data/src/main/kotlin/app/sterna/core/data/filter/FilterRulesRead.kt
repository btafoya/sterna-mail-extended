package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * What reading an account's server-side filters answers, the decision behind
 */
fun loadedFilterRules(sternaScript: String?, otherActiveScript: Boolean): FilterRulesState.Loaded {
    val parsed = sternaScript?.let { SieveCodec.parseRulesOrNull(it) }
    val unreadable = sternaScript != null &&
        (parsed == null || holdsForeignSieve(sternaScript, parsed))
    return FilterRulesState.Loaded(
        rules = parsed.orEmpty(),
        foreignActiveScript = otherActiveScript || unreadable,
        scriptUnreadable = unreadable,
    )
}

/**
 * Whether [script] holds Sieve this app did not write, its rules having been read as [parsed].
 */
internal fun holdsForeignSieve(script: String, parsed: List<FilterRule>): Boolean {
    if (script.isBlank()) return false
    val vacation = when (val marker = SieveCodec.readVacation(script)) {
        VacationMarker.Absent -> null
        VacationMarker.Unreadable -> return true
        is VacationMarker.Found -> marker.vacation
    }
    return SieveCodec.sieveBody(script) != SieveCodec.sieveBody(SieveCodec.generate(parsed, vacation))
}

package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * What reading an account's server-side filters answers — the decision behind
 * `MailRepository.loadFilterRules`, as a plain function a JVM test can execute.
 *
 * Two causes of "do not write here" add up into [FilterRulesState.Loaded.foreignActiveScript],
 * which the per-sender gesture already refuses: another script is active (saving ACTIVATES
 * Sterna's), or our own script is unreadable — wholly ([SieveCodec.parseRulesOrNull] answering
 * null) or in part ([holdsForeignSieve]). Read as "zero rules", the second recompiled the whole
 * script from one new rule. The Filters screen still writes, and ASKS first.
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
 *
 * Reading the marker is not reading the script: a rule added from Roundcube, a `vacation` block,
 * one extra `require` all sit around a marker that decodes perfectly, and all of it is recompiled
 * away the next time anything saves. The check is the codec run backwards — compile [parsed] and
 * compare, marker line excluded and both sides normalised ([SieveCodec.sieveBody]).
 *
 * A blank script is not foreign: it holds nothing to lose. This WARNS, it does not merge.
 */
internal fun holdsForeignSieve(script: String, parsed: List<FilterRule>): Boolean {
    if (script.isBlank()) return false
    return SieveCodec.sieveBody(script) != SieveCodec.sieveBody(SieveCodec.generate(parsed))
}

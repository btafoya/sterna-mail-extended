package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState

/**
 * What reading an account's server-side filters answers — the decision behind
 * `MailRepository.loadFilterRules`, as a plain function two arguments can execute, because the
 * repository itself needs a session, a JMAP client and a device to exist.
 *
 * [sternaScript] is the body of the account's `sterna` script, or null when the account has none
 * at all. [otherActiveScript] is whether some OTHER script is the active one.
 *
 * **The two ways this answer says "do not write here".** They add up into
 * [FilterRulesState.Loaded.foreignActiveScript] on purpose: that one flag is what the two
 * surfaces of the per-sender GESTURE already refuse — the reader's menu entry and the by-sender
 * list — so the write that started this is stopped without a new state to thread through them.
 *
 * ⚠ It is not REFUSED everywhere. The **Filters screen** still writes: its Save rewrites the whole
 * script from the list on screen, and that path is deliberately left open because it is the only
 * way out of "the rules are not running" (the arbitration, and what it costs, are in
 * `ai-work/issues/findings/finding-script-sieve-ecrase-par-ecran-filtres.md`).
 * What that screen does with the fact is ASK before writing: an unreadable script sends Save
 * through a confirmation that names the overwrite (`filtersSaveStep`), and through it on BOTH
 * gestures that write — the button and the "save your changes?" dialog the back gesture opens.
 * A question, not a refusal: confirming replaces the script, which is what the arbitration above
 * requires. The red line stays with it — [ForeignScriptNotice.UNREADABLE_SCRIPT], fed by
 * [FilterRulesState.Loaded.scriptUnreadable] below, because the fold above cannot tell the two
 * causes apart once they are one boolean.
 *
 * The two causes:
 *
 *  - **another script is active.** Saving does not merely write Sterna's script, it ACTIVATES
 *    it, switching off whatever was running. Known since the filter editor learned to warn in
 *    red before its Save button.
 *  - **our own script is there and unreadable** ([SieveCodec.parseRulesOrNull] answering null:
 *    content, but no marker or broken JSON) — **or read only in part** ([holdsForeignSieve]: the
 *    marker decoded, but the Sieve around it is not the Sieve those rules compile to). This one
 *    was silent. An unreadable script read as
 *    "zero rules" made the per-sender gesture available, and confirming it recompiled the whole
 *    script from that single new rule — deleting the content nobody had managed to read.
 *    `SenderBlock.addBlockRule` promises in as many words that a read which is not `Loaded`
 *    ends the gesture with no write at all, "never a 'start from an empty list and hope'"; it
 *    was the layer underneath that handed it an empty list and called it a successful read.
 *
 * Deliberately NOT conditioned on whether the `sterna` script is the active one. An unreadable
 * script that is merely inactive is overwritten by the very same save — its content is no less
 * lost for having been switched off.
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
 * ⛔ **Reading the marker is not reading the script.** A valid `# STERNA-RULES-V1:` line says what
 * the rule editor should show; it says nothing about the rest of the file. A rule added from
 * Roundcube, a `vacation` block, one extra `require`: all of it sits AROUND a marker that decodes
 * perfectly, and all of it is recompiled away the next time anything saves, because saving rewrites
 * the whole script from the rule list. The script was called fully understood because the JSON was.
 *
 * The check is the codec run backwards: compile [parsed] and compare. What is compared is the
 * script MINUS its marker line, normalised on both sides ([SieveCodec.sieveBody]) — the metadata
 * is deliberately out, so a script written by another version of this app is not mistaken for a
 * hand-edited one.
 *
 * ⭐ A blank script is not foreign, and the guard says so here rather than trusting the
 * normalisation to land on it: a blank script holds nothing to lose, and calling it foreign would
 * refuse the per-sender gesture for ever on every account whose script is an empty stub.
 * ⚠ Measured 2026-08-12: removing that guard leaves the suite green, because the only caller can
 * only reach it with an empty rule list, which compiles to the same empty body. It is kept as the
 * written form of the rule, not because a test needs it — and this stays `internal` so that
 * "the only caller" remains true.
 *
 * ⚠ This WARNS, it does not merge: the hand-written Sieve is still lost by a save that goes
 * through. What it buys is that the save is a question first, and that the per-sender gesture —
 * which asks nothing — stops.
 */
internal fun holdsForeignSieve(script: String, parsed: List<FilterRule>): Boolean {
    if (script.isBlank()) return false
    return SieveCodec.sieveBody(script) != SieveCodec.sieveBody(SieveCodec.generate(parsed))
}

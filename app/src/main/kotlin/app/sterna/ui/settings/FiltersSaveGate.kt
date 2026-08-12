package app.sterna.ui.settings

/**
 * When the filters screen may offer its Save button (#34, and the dead-rules dead end).
 *
 * Save had one meaning — "push what you changed" — so it was gated on [dirty], and that is still
 * the rule when the rules are running. But Save has a second meaning the screen never admitted:
 * it regenerates the script AND makes it the active one, so it is the ONE gesture that brings
 * back rules a server has stopped running. In the state the return from holiday leaves an account
 * in, the screen said "your filter rules are not being applied at the moment" over a list
 * identical to the server's — [dirty] false, Save greyed out, and no way to act on what had just
 * been announced. Toggling a rule off and on again, or adding one and deleting it, does not open
 * the door either: those come back to the same list.
 *
 * So the button opens on [rulesNotRunning] as well. ⚠ [dirty] itself is NOT touched: it guards the
 * confirm-on-exit dialog and `pendingExitStep`, and a dirty screen nobody edited would ask "save
 * your changes?" on the way out of a form that has none. Only the Save button's own condition
 * widens, which is why it lives here as a function and not as an expression inside the composable.
 *
 * @param saving a write is already in flight — never two.
 * @param dirty the rules on screen differ from the ones the server confirmed.
 * @param rulesNotRunning the server holds these rules but is not running them.
 */
internal fun filtersSaveEnabled(saving: Boolean, dirty: Boolean, rulesNotRunning: Boolean): Boolean =
    !saving && (dirty || rulesNotRunning)

/** What a Save gesture on the filters screen must do next. */
internal enum class FiltersSaveStep {
    /** Ask before writing: this save replaces a script nobody has read. */
    CONFIRM_OVERWRITE,

    /** Nothing unseen is at stake — write. */
    WRITE,
}

/**
 * Whether a Save on the filters screen may write straight away, or must ask first.
 *
 * The screen already SAYS it, in red: "Sterna Mail cannot read this account's filter script; saving
 * will replace it." Nothing stopped it. Save does not patch the script, it regenerates the whole
 * thing from the list on screen — and over a script this app could not parse that list is empty
 * *because* nothing could be read out of it. One tap replaced a hand-written `fileinto` with an
 * empty script, on the server, silently, with nothing to undo it.
 *
 * So the answer is a step, not a refusal. The button stays open ([filtersSaveEnabled] is untouched
 * and does not learn this flag): the user may well want to replace that script, and greying Save
 * out would state the problem and lock the door — the dead end #34 closed. What changes is that the
 * sentence has to be read once before the write.
 *
 * A value, not an `if` at the call site, because there are TWO gestures that write here — the Save
 * button and the "save your changes?" dialog the back gesture opens — and a confirmation wired to
 * one of them is walked around by the other.
 *
 * @param scriptUnreadable the account's own `sterna` script exists and could not be parsed.
 */
internal fun filtersSaveStep(scriptUnreadable: Boolean): FiltersSaveStep =
    if (scriptUnreadable) FiltersSaveStep.CONFIRM_OVERWRITE else FiltersSaveStep.WRITE

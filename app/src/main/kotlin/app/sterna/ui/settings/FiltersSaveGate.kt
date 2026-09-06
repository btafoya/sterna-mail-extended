package app.sterna.ui.settings

/**
 * When the filters screen may offer its Save button (#34). Save regenerates the script and makes it
 * active, so it is also the only gesture that brings back rules a server has stopped running —
 * hence [rulesNotRunning] opens the button with nothing edited. [dirty] must not widen to match:
 * it also guards the confirm-on-exit dialog, which would ask "save your changes?" over a form
 * nobody edited.
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
 * Whether a Save on the filters screen may write straight away, or must ask first. Save regenerates
 * the whole script from the list on screen, and over an unparseable script that list is empty
 * *because* nothing could be read out of it: one tap silently replaces a hand-written script with an
 * empty one. A step, not a refusal — [filtersSaveEnabled] stays untouched, since greying Save out
 * would lock the door on a replacement the user may well want.
 * A value, not an `if` at the call site: two gestures write here (the Save button and the "save
 * your changes?" dialog), and a confirmation wired to one is walked around by the other.
 * @param scriptUnreadable the account's own `sterna` script exists and could not be parsed.
 */
internal fun filtersSaveStep(scriptUnreadable: Boolean): FiltersSaveStep =
    if (scriptUnreadable) FiltersSaveStep.CONFIRM_OVERWRITE else FiltersSaveStep.WRITE

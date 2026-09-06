package app.sterna.core.data.filter

/**
 * The name a server that implements the vacation responder as a Sieve script gives that script. Its
 * EXISTENCE is the only observable proof that this server couples the two. Measured against
 * Stalwart; the repo deliberately carries no list of server names.
 */
const val VACATION_SCRIPT_NAME = "vacation"

/**
 * What the account's Sieve scripts say about the user's filter rules. The default is the "we know
 * nothing" value: no script, so no warning. A FAILED read is NOT this value — it is `null`, and
 * the screens act on the difference (see [refreshedFilterWarning]).
 */
data class FilterScriptStatus(
    /** A script named `sterna` exists on the server. */
    val scriptExists: Boolean = false,
    /** That script is the ACTIVE one. A server keeps a single active script per account. */
    val scriptActive: Boolean = false,
    /** How many rules it carries that actually filter mail (see [enabledRuleCount]). */
    val enabledRuleCount: Int = 0,
    /** A script named [VACATION_SCRIPT_NAME] exists next to it — EXISTENCE, not activity: what
     *  [filterScriptWarning] predicts on. */
    val vacationScriptExists: Boolean = false,
    /** That `vacation` script is the ACTIVE one. Separate from [vacationScriptExists] because only
     *  this one is about the present: "saving will stop your auto-reply" is false on an account
     *  whose active script is a third one. */
    val vacationScriptActive: Boolean = false,
    /** Some script OTHER than `sterna` is the active one — so a save, which activates Sterna's
     *  script, switches that one off. */
    val foreignActive: Boolean = false,
)

/** What a screen must tell the user about the state of their filter rules, or null for nothing. */
enum class FilterScriptWarning {
    /**
     * The rules exist but are NOT running: the script that carries them is not the active one.
     * This is the state a server leaves the account in after the vacation responder has been turned
     * on and then off again, and the rules stay dead until the filters are saved by hand.
     */
    RULES_NOT_RUNNING,

    /** The rules ARE running, but this server materialises the responder as a Sieve script, so
     *  turning it on will make that script the active one and stop the rules. */
    RESPONDER_WILL_SUSPEND_RULES,
}

/**
 * Which warning (if any) the vacation and filter screens must carry. The order of the guards is the
 * whole content: no script, nothing to say; no rule that filters, a false alarm about nothing;
 * script present but inactive, the rules are not applying; script active with a `vacation` script
 * and the responder KNOWN off, a prediction only those last two conditions make honest.
 *
 * @param responderEnabled whether the responder is on, or null when the caller does not know.
 * Unknown is NOT "off": only a known-off responder can be predicted about.
 */
fun filterScriptWarning(
    scriptExists: Boolean,
    scriptActive: Boolean,
    enabledRuleCount: Int,
    vacationScriptExists: Boolean,
    responderEnabled: Boolean?,
): FilterScriptWarning? = when {
    !scriptExists -> null
    enabledRuleCount == 0 -> null
    !scriptActive -> FilterScriptWarning.RULES_NOT_RUNNING
    vacationScriptExists && responderEnabled == false -> FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES
    else -> null
}

/**
 * The warning to show after a status read that may have failed.
 *
 * `status == null` means the read FAILED, not "nothing to report": on the return from holiday
 * the rules really are dead, and answering "nothing" would erase the line at the moment it becomes
 * true. So a failed read changes nothing, while a read that succeeds replaces the warning outright,
 * including with `null`.
 */
fun refreshedFilterWarning(
    previous: FilterScriptWarning?,
    status: FilterScriptStatus?,
    responderEnabled: Boolean?,
): FilterScriptWarning? = if (status == null) {
    previous
} else {
    filterScriptWarning(
        scriptExists = status.scriptExists,
        scriptActive = status.scriptActive,
        enabledRuleCount = status.enabledRuleCount,
        vacationScriptExists = status.vacationScriptExists,
        responderEnabled = responderEnabled,
    )
}

/** Which "another script is active" line the filters screen must carry, or null for none. */
enum class ForeignScriptNotice {
    /** Some other script is active. Whose, and what it does, is unknown — so nothing is claimed. */
    ANOTHER_SCRIPT,

    /** The `vacation` script IS the active one: what Save costs is the auto-reply. */
    STOPS_AUTO_REPLY,

    /** This account has a `sterna` script nobody could parse: what a save would do here is replace
     *  content no one has read. */
    UNREADABLE_SCRIPT,
}

/**
 * Which foreign-script line the filters screen shows above its Save button. Save does not merely
 * write Sterna's script, it ACTIVATES it, and a server keeps one active script per account.
 *
 * The order of the guards is the whole content: [scriptUnreadable] first, the only state where Save
 * destroys something; then nothing when none is foreign; then [vacationScriptActive] — ACTIVITY,
 * not existence, or an inactive `vacation` script beside a third active one would make the app
 * accuse the responder of what another script does; then the generic line. ONE line out of these
 * three, never two: they sit above one button.
 */
fun foreignScriptNotice(
    scriptUnreadable: Boolean,
    foreignActive: Boolean,
    vacationScriptActive: Boolean,
): ForeignScriptNotice? = when {
    scriptUnreadable -> ForeignScriptNotice.UNREADABLE_SCRIPT
    !foreignActive -> null
    vacationScriptActive -> ForeignScriptNotice.STOPS_AUTO_REPLY
    else -> ForeignScriptNotice.ANOTHER_SCRIPT
}

/**
 * Whether the filters screen prints "No rules yet…". When the script could not be parsed the rule
 * list is empty *because nothing could be read out of it*, and
 * [ForeignScriptNotice.UNREADABLE_SCRIPT] already says so one line above — printing "No rules yet"
 * under it makes the screen contradict itself, and the reassuring sentence is the false one.
 */
fun showsNoRulesNote(ruleCount: Int, scriptUnreadable: Boolean): Boolean =
    ruleCount == 0 && !scriptUnreadable

/** Which sentence the RESPONDER screen puts at its head, or null for none. */
enum class VacationFilterLine {
    /** The rules are not running, and the way to put them back is named. */
    RULES_NOT_RUNNING_WITH_REMEDY,

    /** The rules are not running. The fact alone: the remedy would undo this very screen. */
    RULES_NOT_RUNNING_FACT,

    /** Switching the responder on will suspend the rules. */
    RESPONDER_WILL_SUSPEND_RULES,
}

/**
 * Which of the two "not running" sentences the responder screen shows. The remedy — "you can put
 * them back in Settings → Filters" — was printed unconditionally, but while the user is AWAY the
 * warning is true *because the responder is running*, so the screen whose switch reads ON was
 * offering the one gesture that switches it back off. It is therefore offered only when the
 * responder is known off.
 *
 * @param responderEnabled what the switch on that screen shows, not the server's copy: the sentence
 *   sits three rows above the switch.
 */
fun vacationFilterLine(warning: FilterScriptWarning?, responderEnabled: Boolean): VacationFilterLine? =
    when (warning) {
        null -> null
        FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES -> VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES
        FilterScriptWarning.RULES_NOT_RUNNING ->
            if (responderEnabled) {
                VacationFilterLine.RULES_NOT_RUNNING_FACT
            } else {
                VacationFilterLine.RULES_NOT_RUNNING_WITH_REMEDY
            }
    }

/**
 * Whether [warning] is the statement of fact — the rules are on the server and not running. A
 * function rather than a comparison at the call site: it gates the Save button that is the way out
 * of the state, so it is executed by a test instead of trusted.
 */
fun rulesAreNotRunning(warning: FilterScriptWarning?): Boolean = warning == FilterScriptWarning.RULES_NOT_RUNNING

/**
 * How many of [rules] actually filter mail: enabled, and with something to match on. Same predicate
 * as the one [SieveCodec.generate] emits Sieve for.
 */
fun enabledRuleCount(rules: List<FilterRule>): Int = rules.count { it.enabled && it.value.isNotBlank() }

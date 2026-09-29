package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The decisions the filters and responder screens make on top of [filterScriptWarning]: what a
 * status read that failed does to what is on screen ([refreshedFilterWarning]), which red line
 * goes above the Save button when saving would switch another script off ([foreignScriptNotice]),
 * and whether the empty rule list is worth a sentence of its own ([showsNoRulesNote]).
 */
class FilterStatusRefreshTest {

    // ---- refreshedFilterWarning: "I could not look" is not "nothing to report" -------------

    @Test
    fun `a failed read keeps the warning that was on screen`() {
        assertEquals(
            "a null status is a read that FAILED. Treated as an all-clear it wipes the line at " +
                "the moment it becomes true — see the ON to OFF case below",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RULES_NOT_RUNNING,
                status = null,
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `a failed read keeps the prediction too, and keeps silence silent`() {
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                status = null,
                responderEnabled = false,
            ),
        )
        assertNull(
            "a failed read must not INVENT a warning either",
            refreshedFilterWarning(previous = null, status = null, responderEnabled = false),
        )
    }

    @Test
    fun `a read that succeeds and finds nothing clears the warning`() {
        // The other half of the same rule. A red line that survives a good read is the same lie
        // the other way round: after a save the Sterna script is active again and the account is
        // in the nominal state.
        assertNull(
            "a status that says the rules are running must retire a stale RULES_NOT_RUNNING",
            refreshedFilterWarning(
                previous = FilterScriptWarning.RULES_NOT_RUNNING,
                status = FilterScriptStatus(
                    scriptExists = true,
                    scriptActive = true,
                    enabledRuleCount = 2,
                    vacationScriptExists = false,
                ),
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `a read that succeeds replaces one warning with the other`() {
        assertEquals(
            "the rules just stopped: the prediction on screen must give way to the fact",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                status = FilterScriptStatus(
                    scriptExists = true,
                    scriptActive = false,
                    enabledRuleCount = 2,
                    vacationScriptExists = true,
                ),
                responderEnabled = true,
            ),
        )
    }

    /**
     * The case the whole nullable return exists for, end to end, as measured on the server:
     */
    @Test
    fun `switching the responder off and losing the read does not silence the screen`() {
        val whileAway = refreshedFilterWarning(
            previous = null,
            status = FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 3,
                vacationScriptExists = true,
                foreignActive = true,
            ),
            responderEnabled = true,
        )
        assertEquals(
            "with the responder on, the rules are already stopped and that is a fact, not a prediction",
            FilterScriptWarning.RULES_NOT_RUNNING,
            whileAway,
        )

        val afterTurningItOff = refreshedFilterWarning(
            previous = whileAway,
            status = null,
            responderEnabled = false,
        )
        assertEquals(
            "the rules are dead and the app could not check: keeping the line is the only honest " +
                "answer, and the only one that leaves the Save button open",
            FilterScriptWarning.RULES_NOT_RUNNING,
            afterTurningItOff,
        )
    }

    @Test
    fun `the status is read fact by fact, under its own name`() {
        // Three booleans and a count go into the decision, so a swapped pair compiles and the
        // decision is then right about the wrong facts. Each case below moves one fact.
        val running = FilterScriptStatus(
            scriptExists = true,
            scriptActive = true,
            enabledRuleCount = 1,
            vacationScriptExists = false,
        )
        assertNull(
            "script present, active, one rule, no vacation script: the nominal state",
            refreshedFilterWarning(previous = null, status = running, responderEnabled = false),
        )
        assertEquals(
            "only scriptActive changed",
            FilterScriptWarning.RULES_NOT_RUNNING,
            refreshedFilterWarning(
                previous = null,
                status = running.copy(scriptActive = false),
                responderEnabled = false,
            ),
        )
        assertEquals(
            "only vacationScriptExists changed",
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(
                previous = null,
                status = running.copy(vacationScriptExists = true),
                responderEnabled = false,
            ),
        )
        val stopped = running.copy(scriptActive = false)
        assertNull(
            "from the stopped state, only scriptExists changed: no script, nothing to say",
            refreshedFilterWarning(
                previous = null,
                status = stopped.copy(scriptExists = false),
                responderEnabled = false,
            ),
        )
        assertNull(
            "from the stopped state, only enabledRuleCount changed: nothing that filters, no alarm",
            refreshedFilterWarning(
                previous = null,
                status = stopped.copy(enabledRuleCount = 0),
                responderEnabled = false,
            ),
        )
    }

    @Test
    fun `the responder state reaches the decision from the caller, not from the status`() {
        val status = FilterScriptStatus(
            scriptExists = true,
            scriptActive = true,
            enabledRuleCount = 1,
            vacationScriptExists = true,
        )
        assertEquals(
            FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
            refreshedFilterWarning(previous = null, status = status, responderEnabled = false),
        )
        assertNull(
            "the prediction is not made to somebody whose responder is already on",
            refreshedFilterWarning(previous = null, status = status, responderEnabled = true),
        )
        assertNull(
            "nor on a guess: the filters screen never loads the responder and passes null",
            refreshedFilterWarning(previous = null, status = status, responderEnabled = null),
        )
    }

    // ---- foreignScriptNotice: which red line goes above Save --------------------------------

    @Test
    fun `no foreign active script says nothing, whatever else exists`() {
        assertNull(
            "our own script being the active one is the nominal state: no line at all",
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = false,
                vacationScriptActive = false,
            ),
        )
        assertNull(
            "a vacation script that is NOT the active one costs nothing to save over",
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = false,
                vacationScriptActive = true,
            ),
        )
    }

    @Test
    fun `a foreign active script with no active vacation script keeps the generic line`() {
        // Nothing observed says what that script is, so nothing is claimed about what Save costs.
        assertEquals(
            ForeignScriptNotice.ANOTHER_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = true,
                vacationScriptActive = false,
            ),
        )
    }

    @Test
    fun `a foreign active script that IS the vacation script names the auto-reply`() {
        // B-2, the whole point: this release opens Save with nothing edited, and pressing it
        // during a holiday switches the auto-reply off. "Another filter script is active on the
        // server" is true and names nothing the user recognises.
        assertEquals(
            ForeignScriptNotice.STOPS_AUTO_REPLY,
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = true,
                vacationScriptActive = true,
            ),
        )
    }

    /**
     * The E1 WITNESS.
     */
    @Test
    fun `an idle vacation script beside another active one accuses nobody`() {
        assertEquals(
            "the sentence names the auto-reply only when the auto-reply's script is the one running",
            ForeignScriptNotice.ANOTHER_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = false,
                foreignActive = true,
                vacationScriptActive = false,
            ),
        )
    }

    /**
     * The E2 case. The refusal to write can come from our own `sterna` script being
     */
    @Test
    fun `an unreadable script of our own is named before anything else`() {
        assertEquals(
            "an unreadable sterna script with nothing foreign active: the fold makes foreignActive " +
                "true, and the old order had nothing to say about it",
            ForeignScriptNotice.UNREADABLE_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = true,
                foreignActive = true,
                vacationScriptActive = false,
            ),
        )
        assertEquals(
            "and it outranks the auto-reply line: what Save destroys outranks what Save switches off",
            ForeignScriptNotice.UNREADABLE_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = true,
                foreignActive = true,
                vacationScriptActive = true,
            ),
        )
        assertEquals(
            "…and it is said even with no foreign script active at all — the state where the OLD " +
                "line promised the auto-reply would stop, which it would not have",
            ForeignScriptNotice.UNREADABLE_SCRIPT,
            foreignScriptNotice(
                scriptUnreadable = true,
                foreignActive = false,
                vacationScriptActive = true,
            ),
        )
    }

    // ---- vacationFilterLine: the responder screen does not send for the remedy mid-holiday ----

    /**
     * The E3 case.
     */
    @Test
    fun `the remedy is not offered while the responder is on`() {
        assertEquals(
            "with the responder on, the fact and only the fact",
            VacationFilterLine.RULES_NOT_RUNNING_FACT,
            vacationFilterLine(FilterScriptWarning.RULES_NOT_RUNNING, responderEnabled = true),
        )
    }

    /** The witness, and the case M4 was written for: the return from holiday. */
    @Test
    fun `the remedy IS offered once the responder is off`() {
        assertEquals(
            "responder off and the rules still dead is exactly the state the remedy exists for",
            VacationFilterLine.RULES_NOT_RUNNING_WITH_REMEDY,
            vacationFilterLine(FilterScriptWarning.RULES_NOT_RUNNING, responderEnabled = false),
        )
    }

    @Test
    fun `the prediction is the same sentence whatever the switch says`() {
        // Both switch positions are reached on screen, contrary to what this test used to claim.
        for (enabled in listOf(true, false)) {
            assertEquals(
                VacationFilterLine.RESPONDER_WILL_SUSPEND_RULES,
                vacationFilterLine(
                    FilterScriptWarning.RESPONDER_WILL_SUSPEND_RULES,
                    responderEnabled = enabled,
                ),
            )
        }
    }

    @Test
    fun `nothing to warn about stays nothing, whatever the switch says`() {
        for (enabled in listOf(true, false)) {
            assertNull(
                "a screen with nothing to say must not grow a sentence from the switch alone",
                vacationFilterLine(null, responderEnabled = enabled),
            )
        }
    }

    // ---- showsNoRulesNote: an empty list is not always "no rules yet" ------------------------

    /**
     * The contradiction. Over an unreadable `sterna` script the rule list is empty *because
     */
    @Test
    fun `an unreadable script does not also claim there are no rules`() {
        assertFalse(
            "the warning above already said what the empty list means; repeating it as \"no rules " +
                "yet\" contradicts it, and invites the very save that replaces the script",
            showsNoRulesNote(ruleCount = 0, scriptUnreadable = true),
        )
    }

    /** The witness: the ordinary empty account still gets told what to do. */
    @Test
    fun `an empty list on a readable script still says there are no rules`() {
        assertTrue(
            "a readable account with nothing in it is the case the sentence was written for — " +
                "silencing it leaves a screen with a lone Add button and no explanation",
            showsNoRulesNote(ruleCount = 0, scriptUnreadable = false),
        )
    }

    @Test
    fun `rules on screen say nothing about an empty list, unreadable or not`() {
        for (unreadable in listOf(true, false)) {
            assertFalse(
                "rules are listed: the sentence is about their absence. unreadable=$unreadable",
                showsNoRulesNote(ruleCount = 1, scriptUnreadable = unreadable),
            )
        }
    }

    // ---- the read itself: filterScriptStatus executed, then its call read out of the source ----

    private fun listed(vararg scripts: Pair<String, Boolean>) = scripts.map { (name, active) -> ListedScript(name, active) }

    private val twoRules = SieveCodec.generate(
        listOf(FilterRule(name = "A", value = "a", moveTo = "Box"), FilterRule(name = "B", value = "b", flag = true), FilterRule(name = "Off", enabled = false, value = "c")),
    )

    @Test fun `an inactive sterna beside the server's active responder`() {
        assertEquals(
            FilterScriptStatus(
                scriptExists = true,
                scriptActive = false,
                enabledRuleCount = 2,
                vacationScriptExists = true,
                vacationScriptActive = true,
                foreignActive = true,
            ),
            filterScriptStatus(listed("sterna" to false, "vacation" to true), "sterna", twoRules),
        )
    }

    @Test fun `a vacation script that exists and is not active is existence, not activity`() {
        assertEquals(
            FilterScriptStatus(scriptExists = true, scriptActive = true, enabledRuleCount = 2, vacationScriptExists = true),
            filterScriptStatus(listed("sterna" to true, "vacation" to false), "sterna", twoRules),
        )
    }

    @Test fun `with nothing opened the other scripts are still named`() {
        assertEquals(
            FilterScriptStatus(vacationScriptExists = true, vacationScriptActive = true, foreignActive = true),
            filterScriptStatus(listed("vacation" to true), null, null),
        )
        assertEquals(FilterScriptStatus(), filterScriptStatus(emptyList(), null, null))
    }

    @Test fun `every block the form cannot show counts as running, and an unreadable script counts none`() {
        assertEquals(4, enabledRuleCount(SieveScriptReadTest.fixture("roundcube")))
        assertEquals(0, enabledRuleCount("keep;\n/* never closed\n"))
        assertEquals(2, enabledRuleCount(twoRules))
        assertEquals(
            "Sleeping, a disabled Bulwark entry with no block, filters nothing: five of the six rules run",
            5,
            enabledRuleCount(SieveScriptReadTest.fixture("f237-bulwark")),
        )
    }

    /**
     * The status decision is executed above; this pins the JMAP read hands it the listing, the script
     * `openedScriptName` picked and that script's own text. Holes 2 and 3 apply.
     */
    @Test fun `the status read hands filterScriptStatus the opened script, and fails to null`() {
        val code = codeLines(loadFilterScriptStatusBody())
        assertEquals(
            listOf("val opened = openedScriptName(scripts.jmapListed(), serverResponder = true)"),
            code.filter { "openedScriptName(" in it },
        )
        assertEquals(
            listOf("filterScriptStatus(scripts.jmapListed(), opened?.name, opened?.let { jmapScriptText(ctx.session, ctx.accountId, ctx.auth, it.blobId) })"),
            code.filter { "filterScriptStatus(" in it },
        )
        assertTrue(
            "a FAILED read must answer null: read as the \"nothing known\" value it erases the " +
                "warning at the moment it becomes true",
            code.contains("}.getOrElseUnlessCancelled { null }"),
        )
    }

    /** [body] as trimmed code lines: comment lines dropped whole, trailing comments cut. */
    private fun codeLines(body: String): List<String> = body.lines().mapNotNull { line ->
        val code = line.trim()
        if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) null
        else withoutTrailingComment(code).takeIf { it.isNotBlank() }
    }

    private fun withoutTrailingComment(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '/' && line.getOrNull(i + 1) == '/' -> return line.substring(0, i).trimEnd()
            }
            i++
        }
        return line.trimEnd()
    }

    /** The body of `MailAccounts.loadFilterScriptStatus`, from its signature to the next block. */
    private fun loadFilterScriptStatusBody(): String {
        val source = locate("core/data/src/main/kotlin/app/sterna/core/data/mail/MailAccounts.kt").readText()
        val start = source.indexOf("suspend fun loadFilterScriptStatus(credentials: AccountCredentials)")
        check(start >= 0) { "MailAccounts no longer declares loadFilterScriptStatus(credentials)" }
        val end = source.indexOf("\n    /**", start)
        check(end > start) { "no declaration follows loadFilterScriptStatus — the slice would be the whole file" }
        return source.substring(start, end)
    }

    /** [relative] resolved from the test's working directory, walking up. */
    private fun locate(relative: String): File {
        val fromModule = relative.substringAfter("core/data/")
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, relative).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        error("Cannot find $relative from ${System.getProperty("user.dir")}")
    }
}

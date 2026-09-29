package app.sterna.core.data.filter

import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which script the filters open, when a save activates it, and what reading it answers: [openedScriptName],
 * [activatesOnSave], [loadedFilterRules] and [withFormRules], executed.
 */
class FilterRulesReadTest {

    private fun listed(vararg scripts: Pair<String, Boolean>) = scripts.map { (name, active) -> ListedScript(name, active) }

    // ---- which script is opened ----

    @Test fun `the active script is opened before an inactive sterna`() {
        val scripts = listed("sterna" to false, "roundcube" to true)
        assertEquals(
            "the active script is the one filtering mail: opening sterna would edit rules that do not run",
            "roundcube",
            openedScriptName(scripts, serverResponder = false),
        )
        assertEquals("roundcube", openedScriptName(scripts, serverResponder = true))
        assertEquals("sterna", openedScriptName(listed("sterna" to true, "roundcube" to false), serverResponder = false))
    }

    @Test fun `with no active script sterna is opened, and without it nothing`() {
        assertEquals("sterna", openedScriptName(listed("other" to false, "sterna" to false), serverResponder = false))
        assertNull(openedScriptName(listed("other" to false), serverResponder = false))
        assertNull(openedScriptName(emptyList(), serverResponder = true))
    }

    @Test fun `on JMAP an active vacation script is the server's responder, and sterna is opened instead`() {
        val scripts = listed("sterna" to false, "vacation" to true)
        assertEquals("sterna", openedScriptName(scripts, serverResponder = true))
        assertNull(openedScriptName(listed("vacation" to true), serverResponder = true))
        assertEquals(
            "over ManageSieve a script named vacation is the user's own, and it is the active one",
            "vacation",
            openedScriptName(scripts, serverResponder = false),
        )
    }

    // ---- when a save activates ----

    @Test fun `a save activates only when no script was active`() {
        assertTrue("an account with no script", activatesOnSave(emptyList(), serverResponder = false))
        assertTrue("an inactive sterna, rules not running", activatesOnSave(listed("sterna" to false), serverResponder = false))
        assertFalse(
            "the opened script is the active one: activating again is nothing, and elsewhere it switches a script off",
            activatesOnSave(listed("roundcube" to true), serverResponder = false),
        )
        assertFalse(
            "another script became active since the read: the save must not take it over",
            activatesOnSave(listed("sterna" to false, "roundcube" to true), serverResponder = true),
        )
    }

    @Test fun `on JMAP a save over the server's responder activates, as it always has`() {
        assertTrue(activatesOnSave(listed("sterna" to false, "vacation" to true), serverResponder = true))
        assertFalse(activatesOnSave(listed("sterna" to false, "vacation" to true), serverResponder = false))
    }

    @Test fun `on JMAP an active vacation script stops the server's responder, elsewhere nothing does`() {
        assertTrue(stopsServerResponder(listed("sterna" to false, "vacation" to true), serverResponder = true))
        assertFalse(stopsServerResponder(listed("sterna" to false, "vacation" to true), serverResponder = false))
        assertFalse(stopsServerResponder(listed("sterna" to true, "vacation" to false), serverResponder = true))
        val state = loadedFilterRules("sterna", SieveCodec.generate(emptyList()), stopsServerResponder = true)
        assertTrue("a sender block would activate sterna and stop the auto-reply", state.foreignActiveScript)
        assertFalse("the script itself reads fine: Save asks nothing more", state.scriptUnreadable)
    }

    // ---- a write goes over what was read, or not at all ----

    @Test fun `a write is allowed only over the very script that was read`() {
        val read = ScriptSnapshot("sterna", "keep;\n")
        assertTrue(unchangedSince(read, "sterna", "keep;\n"))
        assertFalse("a rule added elsewhere", unchangedSince(read, "sterna", "keep;\nstop;\n"))
        assertFalse("another script became the one opened", unchangedSince(read, "roundcube", "keep;\n"))
        assertFalse("sterna created where there was none", unchangedSince(ScriptSnapshot(null, null), "sterna", ""))
        assertTrue(unchangedSince(ScriptSnapshot(null, null), null, null))
    }

    @Test fun `formRulesWrite goes over the opened script, by the ids the list kept, empty rules dropped`() {
        val text = fixture("roundcube")
        val opened = loadedFilterRules("roundcube", text)
        val boss = opened.scriptRules.filterIsInstance<ScriptRule.Form>().single()
        val write = formRulesWrite(opened, listOf(boss.id, null), listOf(boss.rule, FilterRule()))
        assertEquals("roundcube", write.name)
        assertEquals(ScriptSnapshot("roundcube", text), write.read)
        assertEquals("the rule read, unchanged, is not written a second time", text, write.script.emit())
        assertEquals("sterna", formRulesWrite(loadedFilterRules(null, null), emptyList(), emptyList()).name)
    }

    // ---- what the opened script reads as ----

    @Test fun `a readable Bulwark script is not foreign, and its locked rules are listed`() {
        val text = fixture("f237-bulwark")
        val state = loadedFilterRules("bulwark", text)
        assertFalse("a script that cuts into blocks takes a sender block", state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
        assertEquals("bulwark", state.scriptName)
        assertEquals(listOf("Newsletters", "Boss", "Roundcube one"), state.rules.map { it.name })
        assertEquals(
            listOf("Big or invoice" to true, "Sleeping" to true, "Roundcube two" to false),
            state.scriptRules.filterIsInstance<ScriptRule.Text>().map { it.name to it.lockedByBulwark },
        )
        assertEquals(text, state.scriptToEdit.emit())
    }

    @Test fun `hand-written Sieve beside the V1 marker is read, not refused`() {
        val text = SieveCodec.generate(listOf(FilterRule(name = "Boss", value = "boss@example.com", moveTo = "Work"))) +
            "if header :contains \"subject\" \"sale\" {\n    discard;\n}\n"
        val state = loadedFilterRules("sterna", text)
        assertFalse(state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
        assertEquals(listOf("Boss"), state.rules.map { it.name })
        assertEquals(2, state.scriptRules.size)
    }

    @Test fun `only a script the splitter refuses is unreadable, and a write then starts from nothing`() {
        val state = loadedFilterRules("roundcube", "keep;\n/* never closed\n")
        assertTrue(state.foreignActiveScript)
        assertTrue(state.scriptUnreadable)
        assertEquals(emptyList<FilterRule>(), state.rules)
        assertNull(state.script)
        assertEquals("", state.scriptToEdit.emit())
    }

    @Test fun `no script opened is zero rules and no refusal`() {
        val state = loadedFilterRules(null, null)
        assertFalse(state.foreignActiveScript)
        assertFalse(state.scriptUnreadable)
        assertNull(state.scriptName)
        assertEquals(emptyList<ScriptRule>(), state.scriptRules)
        assertEquals("", state.scriptToEdit.emit())
        for (blank in listOf("", "  ", "\r\n\r\n")) assertFalse(loadedFilterRules("sterna", blank).scriptUnreadable)
    }

    // ---- the list edits the script in place ----

    @Test fun `the form list edits its rules in the script, the blocks it never showed untouched`() {
        val text = fixture("roundcube")
        val base = loadedFilterRules("roundcube", text).scriptToEdit
        val boss = base.rules.filterIsInstance<ScriptRule.Form>().single()
        val added = FilterRule(name = "new", value = "x@example.com", moveTo = "Box")
        val edited = withFormRules(base, listOf(boss.id, null), listOf(boss.rule.copy(value = "chief@example.com"), added)).emit()
        assertTrue(edited, edited.contains("# rule:[Spam]\nif header :contains \"subject\" \"[SPAM]\"\n{\n\tfileinto \"Junk\";\n\tstop;\n}\n"))
        assertTrue(edited, edited.contains(text.substring(text.indexOf("# rule:[either]"), text.indexOf("# rule:[away]"))))
        assertTrue(edited, edited.contains(text.substring(text.indexOf("# rule:[away]"))))
        val reread = loadedFilterRules("roundcube", edited)
        assertEquals(listOf(boss.rule.copy(value = "chief@example.com"), added), reread.rules)
        assertEquals(base.rules.size + 1, reread.scriptRules.size)

        val removed = withFormRules(base, emptyList(), emptyList()).emit()
        assertFalse(removed, removed.contains("boss@example.com"))
        assertEquals(text.replace("# rule:[boss]\nif address :is \"from\" \"boss@example.com\"\n{\n\taddflag \"\\\\Flagged\";\n}\n", ""), removed)
        assertEquals("nothing changed, nothing rewritten", text, withFormRules(base, listOf(boss.id), listOf(boss.rule)).emit())
    }

    // ---- the wiring, read out of the shipped source: a last resort, not the proof ----

    /**
     * The decision is executed above and the JMAP read in `FiltersOverJmapKeepTheResponderTest`; this pins the
     * argument that read passes and that nothing picks a script beside it. Holes 2 and 3 apply: the expression
     * around the pinned call and its place in the function are not seen.
     */
    @Test fun `the JMAP read opens what openedScriptName picks, the active script first and sterna as the fallback`() {
        val code = SourceText.codeLines(loadFilterRulesBody())
        assertEquals(
            "the script must be picked by openedScriptName, as the JMAP account whose `vacation` script is the " +
                "server's responder: `serverResponder = true`",
            listOf("val opened = openedScriptName(scripts.jmapListed(), serverResponder = true)"),
            code.filter { "openedScriptName(" in it },
        )
        assertEquals(
            "no second selection beside it: a script picked by name alone is the bug this replaces",
            emptyList<String>(),
            code.filter { "SieveCodec.SCRIPT_NAME" in it || "isActive" in it },
        )
        assertTrue(
            "the refusal of a sender block while the server's auto-reply runs must be asked of this listing, as JMAP",
            code.contains("stopsServerResponder = stopsServerResponder(scripts.jmapListed(), serverResponder = true),"),
        )
        assertTrue(
            "the text handed over must be the opened script's own download",
            code.contains("scriptText = opened?.let { jmapScriptText(ctx.session, ctx.accountId, ctx.auth, it.blobId) },"),
        )
        assertFalse(
            "the state must come from loadedFilterRules, not be assembled beside it",
            code.any { "FilterRulesState.Loaded(" in it },
        )
    }

    /** The body of `MailAccounts.loadFilterRules`, from its signature to the next declaration. */
    private fun loadFilterRulesBody(): String = SourceText.functionSource(
        SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailAccounts.kt"),
        "suspend fun loadFilterRules(credentials: AccountCredentials)",
    )
}

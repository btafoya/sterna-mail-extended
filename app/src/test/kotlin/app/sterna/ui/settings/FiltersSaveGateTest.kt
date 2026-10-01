package app.sterna.ui.settings

import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.copy
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.RuleField
import app.sterna.core.data.filter.RuleMatch
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.widget.DeclarationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The one door every write of the filters screen goes through, and the life of a write after it,
 * executed: [filtersSaveAsked], then landed, refused, read again. The view model only folds these;
 * the last test pins, as text, that it has no other way to write.
 */
class FiltersSaveGateTest {

    private val readable = FiltersUiState(loading = false)
    private val unreadable = FiltersUiState(loading = false, scriptUnreadable = true)
    private val boss = FilterRule(name = "Boss", value = "boss@example.com", moveTo = "Work")
    private val plain = "require [\"fileinto\"];\n\n# rule:[Boss]\nif address :is \"from\" \"boss@example.com\" {\n    fileinto \"Work\";\n}\n"

    /** One of each gesture that writes. */
    private val everyChange = listOf(
        FiltersChange.PutRule(was = null, rule = boss),
        FiltersChange.PutRule(was = boss, rule = boss.copy(value = "chief@example.com")),
        FiltersChange.PutRule(was = boss, rule = boss.copy(enabled = false)),
        FiltersChange.DeleteRule(was = boss),
        FiltersChange.PutText(name = "Keep", was = "keep;\n", text = "discard;\n"),
        FiltersChange.DeleteText(name = "Big", was = "discard;\n"),
        FiltersChange.Rewrite,
    )

    @Test fun `saving over a script this app could not read asks first`() {
        assertEquals(FiltersSaveStep.CONFIRM_OVERWRITE, filtersSaveStep(scriptUnreadable = true))
        assertEquals(FiltersSaveStep.WRITE, filtersSaveStep(scriptUnreadable = false))
    }

    @Test fun `an editor save over an unreadable script asks first and writes nothing`() {
        // The editor's Save regenerates the script from what the screen shows, and over an
        // unreadable script nothing could be read out of it: one tap would replace it, unread.
        val change = FiltersChange.PutRule(was = null, rule = boss)
        val (next, now) = filtersSaveAsked(unreadable, change)
        assertNull("nothing may be written before the overwrite is confirmed", now)
        assertEquals("the change waits on the confirmation, whole", change, next.overwritePending)
    }

    @Test fun `every gesture that writes goes through the same question`() {
        everyChange.forEach { change ->
            val (next, now) = filtersSaveAsked(unreadable, change)
            assertNull("$change over an unreadable script", now)
            assertEquals("$change", change, next.overwritePending)
        }
    }

    @Test fun `an ordinary save is not interrupted by a question`() {
        everyChange.forEach { change ->
            val (next, now) = filtersSaveAsked(readable, change)
            assertEquals("$change over a readable script is written as asked", change, now)
            assertEquals(readable, next)
        }
    }

    @Test fun `the confirmation writes the change that waited, once, and cancelling writes nothing`() {
        val change = FiltersChange.DeleteRule(was = boss)
        val (asked, _) = filtersSaveAsked(unreadable, change)
        val (confirmed, now) = filtersOverwriteConfirmed(asked)
        assertEquals(change, now)
        assertNull(confirmed.overwritePending)
        assertNull("a second confirmation has nothing left to write", filtersOverwriteConfirmed(confirmed).second)
        assertNull("nothing waited, nothing is written", filtersOverwriteConfirmed(unreadable).second)
    }

    @Test fun `never two writes at once, and none while a read, an activation or an unclosed landing runs`() {
        val change = FiltersChange.PutRule(was = null, rule = boss)
        listOf(
            readable.copy(saving = true),
            readable.copy(loading = true),
            readable.copy(activating = true),
            readable.copy(landed = 1),
            readable.copy(overwritePending = FiltersChange.Rewrite),
        ).forEach { state ->
            val (next, now) = filtersSaveAsked(state, change)
            assertNull("$state", now)
            assertEquals("a refused request leaves the state alone: $state", state, next)
        }
        val waiting = unreadable.copy(overwritePending = change, saving = true)
        assertNull("a confirmation landing on a write in flight writes nothing", filtersOverwriteConfirmed(waiting).second)
    }

    @Test fun `a landed write is an event that closes the editor, and keeps its Save off until consumed`() {
        val landed = filtersWriteLanded(readable.copy(saving = true, retry = FiltersChange.Rewrite), loadedFilterRules("sterna", plain), written = 7)
        assertEquals(7, landed.landed)
        assertFalse(landed.saving)
        assertNull("nothing is left to replay once a write landed", landed.retry)
        assertTrue("the image between the landing and the editor closing must not offer Save again", filtersBusy(landed))
        assertFalse(ruleSaveEnabled(original = null, rule = boss, busy = filtersBusy(landed), present = true))
        assertFalse("a fresh view model, after the death of the process, carries no event", filtersBusy(readable))
    }

    @Test fun `a user's save starts a new replay budget, a replay does not`() {
        val spent = readable.copy(replayedOnce = true)
        assertFalse(filtersSaveAsked(spent, FiltersChange.Rewrite).first.replayedOnce)
        assertTrue(filtersSaveAsked(spent, FiltersChange.Rewrite, fromUser = false).first.replayedOnce)
    }

    @Test fun `a write refused because the script changed keeps its change for the reread, any other failure does not`() {
        val change = FiltersChange.PutRule(was = boss, rule = boss.copy(flag = true))
        val conflict = filtersWriteFailed(readable.copy(saving = true), change, FilterScriptChangedException())
        assertEquals(change, conflict.retry)
        assertFalse(conflict.saving)
        val refused = filtersWriteFailed(readable.copy(saving = true), change, IOException("offline"))
        assertNull(refused.retry)
        assertEquals(FiltersError.SAVE, refused.errorKind)
    }

    @Test fun `a failed read forgets the change it was to replay, so a later Retry writes nothing unasked`() {
        val failed = filtersLoadFailed(readable.copy(loading = true, retry = FiltersChange.Rewrite), IOException("offline"))
        assertNull(failed.retry)
        assertEquals(FiltersError.LOAD, failed.errorKind)
    }

    @Test fun `a fresh read carries the pending change and its budget into the new state`() {
        val base = filtersLoadedBase(readable.copy(retry = FiltersChange.Rewrite, replayedOnce = true), "a@b.c", listOf("Work"))
        assertEquals(FiltersChange.Rewrite, base.retry)
        assertTrue(base.replayedOnce)
        assertEquals("a@b.c", base.accountLabel)
    }

    @Test fun `the reread replays once, spends the budget, then hands the change back`() {
        val reread = loadedFilterRules("sterna", plain)
        val read = (reread.scriptRules.single() as ScriptRule.Form).rule
        val change = FiltersChange.PutRule(was = read, rule = read.copy(value = "chief@example.com"))
        val (first, again) = filtersReread(readable.withScript(reread).copy(retry = change), reread)
        assertTrue("the premise: the block is found again", ruleTargetPresent(first, RuleTarget(read, read)))
        assertEquals(change, again)
        assertTrue("the replay spends the budget", first.replayedOnce)
        assertNull(first.retry)

        val (second, none) = filtersReread(first.copy(retry = change), reread)
        assertNull("a second refusal in a row is not replayed", none)
        assertEquals(change, second.proposal)
        assertEquals(FiltersError.CHANGED, second.errorKind)

        val (idle, nothing) = filtersReread(readable, reread)
        assertNull(nothing)
        assertEquals(readable, idle)
    }

    @Test fun `a replay never writes over the running auto-reply, it offers the edit back`() {
        val reread = loadedFilterRules("sterna", plain)
        val read = (reread.scriptRules.single() as ScriptRule.Form).rule
        val change = FiltersChange.PutRule(was = read, rule = read.copy(value = "chief@example.com"))
        val responder = readable.withScript(reread).copy(foreignActive = true, vacationScriptActive = true, retry = change)
        assertTrue(filtersWarnsBeforeWrite(responder))
        val (next, again) = filtersReread(responder, reread)
        assertNull(again)
        assertEquals(change, next.proposal)
        assertFalse(filtersWarnsBeforeWrite(readable))
    }

    @Test fun `an editor offers Save only for a change that is not empty, on a block still there, and never while busy`() {
        assertFalse("untouched", ruleSaveEnabled(original = boss, rule = boss, busy = false, present = true))
        assertTrue(ruleSaveEnabled(original = boss, rule = boss.copy(value = "x@example.com"), busy = false, present = true))
        assertFalse("busy", ruleSaveEnabled(original = boss, rule = boss.copy(value = "x@example.com"), busy = true, present = true))
        assertFalse("its block is gone", ruleSaveEnabled(original = boss, rule = boss.copy(value = "x@example.com"), busy = false, present = false))
        assertFalse("a new rule left empty", ruleSaveEnabled(original = null, rule = FilterRule(), busy = false, present = true))
        assertTrue("a new rule filled in", ruleSaveEnabled(original = null, rule = boss, busy = false, present = true))
        assertFalse("an existing rule emptied", ruleSaveEnabled(original = boss, rule = FilterRule(), busy = false, present = true))

        assertFalse(textSaveEnabled(original = "keep;", text = "keep;", busy = false, present = true))
        assertTrue(textSaveEnabled(original = "keep;", text = "discard;", busy = false, present = true))
        assertFalse(textSaveEnabled(original = "keep;", text = "discard;", busy = true, present = true))
        assertFalse("its block changed elsewhere: Save would fail again", textSaveEnabled(original = "keep;", text = "discard;", busy = false, present = false))
        assertFalse("a blank text would delete the block without Delete's question", textSaveEnabled(original = "keep;", text = "  \n", busy = false, present = true))
    }

    @Test fun `an editor knows whether its block is still in the list, by content and rank`() {
        val state = readable.withScript(loadedFilterRules("sterna", plain))
        val bossRead = (state.scriptRules.single() as ScriptRule.Form).rule
        assertTrue(ruleTargetPresent(state, RuleTarget(original = bossRead, start = bossRead)))
        assertFalse(ruleTargetPresent(state, RuleTarget(original = bossRead, start = bossRead, rank = 1)))
        assertFalse(ruleTargetPresent(state, RuleTarget(original = bossRead.copy(value = "gone"), start = bossRead)))
        assertTrue("a new rule needs no block", ruleTargetPresent(state, RuleTarget(original = null, start = FilterRule())))
        assertFalse(textTargetPresent(state, TextTarget(name = "Boss", was = "x", start = "y")))
    }

    @Test fun `leaving an editor asks only over something typed, and waits while a write runs`() {
        assertEquals(EditorLeave.LEAVE, editorLeaveStep(changed = ruleEdited(boss, boss), saving = false))
        assertEquals(EditorLeave.ASK, editorLeaveStep(changed = ruleEdited(boss, boss.copy(flag = true)), saving = false))
        assertEquals("Add rule then back: nothing typed", EditorLeave.LEAVE, editorLeaveStep(changed = ruleEdited(null, FilterRule()), saving = false))
        assertEquals(
            "an edit offered back after a refusal is something typed, even though the editor opened on it",
            EditorLeave.ASK,
            editorLeaveStep(changed = ruleEdited(null, boss), saving = false),
        )
        assertEquals("leaving would cancel the write midway", EditorLeave.STAY, editorLeaveStep(changed = true, saving = true))
        assertEquals(EditorLeave.STAY, editorLeaveStep(changed = false, saving = true))
        assertFalse(filtersMayLeave(readable.copy(saving = true)))
        assertTrue(filtersMayLeave(readable))
    }

    @Test fun `an editor's subject survives the bundle, every field of the rule included`() {
        val rule = FilterRule(
            name = "n", enabled = false, field = RuleField.SUBJECT, match = RuleMatch.IS,
            value = "v", moveTo = "INBOX.Done", markRead = true, flag = true,
        )
        assertEquals(rule, restoredRule(savedRule(rule)))
        assertEquals(FilterRule(), restoredRule(savedRule(FilterRule())))
        val target = RuleTarget(original = rule, start = rule.copy(value = "w"), rank = 2)
        assertEquals(target, restoredRuleTarget(savedRuleTarget(target)))
        assertEquals(RuleTarget(null, rule), restoredRuleTarget(savedRuleTarget(RuleTarget(null, rule))))
        val text = TextTarget(name = "t", was = "keep;", start = "discard;", rank = 1)
        assertEquals(text, restoredTextTarget(savedTextTarget(text)))
    }

    @Test fun `busy covers a read, an activation, a write and an unclosed landing`() {
        assertFalse(filtersBusy(readable))
        assertTrue(filtersBusy(readable.copy(saving = true)))
        assertTrue(filtersBusy(readable.copy(loading = true)))
        assertTrue(filtersBusy(readable.copy(activating = true)))
        assertTrue(filtersBusy(readable.copy(landed = 1)))
    }

    @Test fun `the view model writes only through the door, and folds each step through its tested function`() {
        // Source lint, same instrument and disclaimer as FilterWarningWiringTest.
        val lines = DeclarationSource.kotlinLines(FILTERS_VM)
        assertEquals(
            listOf(
                "private fun ask(change: FiltersChange, fromUser: Boolean) {",
                "val (next, now) = filtersSaveAsked(_state.value, change, fromUser)",
                "_state.value = next",
                "now?.let(::write)",
            ),
            DeclarationSource.functionBody(FILTERS_VM, "private fun ask(change: FiltersChange, fromUser: Boolean)"),
        )
        assertEquals(
            listOf("fun confirmOverwrite() {", "val (next, now) = filtersOverwriteConfirmed(_state.value)", "_state.value = next", "now?.let(::write)"),
            DeclarationSource.functionBody(FILTERS_VM, "fun confirmOverwrite()"),
        )
        assertEquals(
            "the write itself is private and reached from those two lines only: a third caller is a third door",
            listOf("now?.let(::write)", "now?.let(::write)", "private fun write(change: FiltersChange) {"),
            lines.filter { Regex("\\bwrite\\(|::write\\b").containsMatchIn(it) && "saveFilterScript" !in it },
        )
        assertEquals(
            "the user enters with a fresh budget, the replay without",
            listOf("again?.let { ask(it, fromUser = false) }", "fun save(change: FiltersChange) = ask(change, fromUser = true)"),
            lines.filter { Regex("\\bask\\(").containsMatchIn(it) && !it.startsWith("private fun ask") },
        )
        assertEquals(
            "each step of a write's life is folded by the function executed above, and nothing else touches retry or its budget",
            listOf(
                "state = filtersLoadedBase(_state.value, store.accountLabel(), folders, trash).withScript(result),",
                "val (next, again) = filtersReread(read, result)",
                "_state.update { filtersLoadFailed(it, t) }",
                "_state.update { filtersWriteLanded(it, saved, ++writes) }",
                "_state.update { filtersWriteFailed(it, change, t) }",
            ),
            lines.filter { Regex("filters(LoadedBase|Reread|LoadFailed|WriteLanded|WriteFailed)\\(").containsMatchIn(it) },
        )
        assertEquals(emptyList<String>(), lines.filter { Regex("\\b(retry|replayedOnce) =").containsMatchIn(it) })
        val screen = DeclarationSource.kotlinLines(FILTERS_SCREEN)
        assertEquals(emptyList<String>(), screen.filter { Regex("viewModel\\.(write|ask)\\b").containsMatchIn(it) })
    }

    private companion object {
        val FILTERS_VM: File by lazy { DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FiltersViewModel.kt") }
        val FILTERS_SCREEN: File by lazy { DeclarationSource.file("app/src/main/kotlin/app/sterna/ui/settings/FiltersScreen.kt") }
    }
}

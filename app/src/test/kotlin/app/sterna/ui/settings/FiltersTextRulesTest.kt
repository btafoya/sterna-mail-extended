package app.sterna.ui.settings

import app.sterna.core.data.filter.BulwarkMetadata
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.copy
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.mail.FilterRulesState
import app.sterna.core.imap.ManageSieveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filters list beyond the form, and what each gesture writes: Sieve text blocks edited as text,
 * Bulwark rules that can only be deleted, form rules edited, switched and deleted one write at a time.
 */
class FiltersTextRulesTest {

    private val loaded = loadedFilterRules("bulwark", BULWARK)
    private val opened = FiltersUiState().withScript(loaded)

    private fun text(name: String) = loaded.scriptRules.single { it is ScriptRule.Text && it.name == name } as ScriptRule.Text
    private fun form(name: String) = (loaded.scriptRules.single { it is ScriptRule.Form && it.name == name } as ScriptRule.Form).rule

    private fun written(change: FiltersChange, over: FilterRulesState.Loaded = loaded): String =
        ((filtersWrite(over, change) as FiltersWritePlan.Write).write.script.emit())

    @Test fun `the list shows every rule in script order, and only Bulwark's are not opened as text`() {
        val listing = filtersListing(opened)
        assertEquals(
            listOf("Newsletters", "Boss", "Big or invoice", "Sleeping", "Roundcube one", "Roundcube two"),
            listing.map { (it as? FiltersListItem.Form)?.rule?.name ?: (it as FiltersListItem.Text).name },
        )
        assertEquals(
            "a Bulwark rule the form cannot show is locked; an external block is edited as text",
            listOf("Big or invoice" to false, "Sleeping" to false, "Roundcube two" to true),
            listing.filterIsInstance<FiltersListItem.Text>().map { it.name to it.editable },
        )
        assertTrue(listing.filterIsInstance<FiltersListItem.Text>().single { it.name == "Roundcube two" }.text.contains("\"rc2\""))
    }

    @Test fun `Save of a text writes it in place of its block, the rest byte for byte, under the script's name`() {
        val original = text("Roundcube two").text
        val plan = filtersWrite(loaded, FiltersChange.PutText(name = "Roundcube two", was = original, text = original.replace("rc2", "rc3")))
        val write = (plan as FiltersWritePlan.Write).write
        assertEquals("bulwark", write.name)
        assertEquals(loaded.snapshot, write.read)
        assertEquals(BULWARK.replace("\"rc2\"", "\"rc3\""), write.script.emit())
    }

    @Test fun `Save of a form rule rewrites that rule alone`() {
        val boss = form("Boss")
        val emitted = written(FiltersChange.PutRule(was = boss, rule = boss.copy(value = "CRITICAL")))
        assertTrue(emitted, emitted.contains("\"CRITICAL\""))
        assertFalse(emitted, emitted.contains("\"URGENT\""))
        assertTrue("the text blocks are untouched", emitted.contains(text("Roundcube two").text))
        assertEquals(
            listOf("Newsletters", "Boss", "Big or invoice", "Sleeping", "Roundcube one", "Roundcube two"),
            loadedFilterRules("bulwark", emitted).scriptRules.map { it.name },
        )
    }

    @Test fun `a switch writes the rule with its new state and nothing else`() {
        val boss = form("Boss")
        val reread = loadedFilterRules("bulwark", written(FiltersChange.PutRule(was = boss, rule = boss.copy(enabled = false))))
        assertEquals(boss.copy(enabled = false), (reread.scriptRules.single { it.name == "Boss" } as ScriptRule.Form).rule)
        assertEquals(loaded.scriptRules.size, reread.scriptRules.size)
    }

    @Test fun `a new rule is added, and exists nowhere before its Save`() {
        val added = FilterRule(name = "new", value = "x@example.com", moveTo = "Box")
        assertEquals("the list holds no rule the server does not", loaded.scriptRules.size, filtersListing(opened).size)
        val reread = loadedFilterRules("bulwark", written(FiltersChange.PutRule(was = null, rule = added)))
        assertEquals(loaded.scriptRules.size + 1, reread.scriptRules.size)
        assertTrue(reread.scriptRules.any { it is ScriptRule.Form && it.rule.value == "x@example.com" })
    }

    @Test fun `a Bulwark rule takes only a deletion, and deleting keeps Bulwark's metadata readable`() {
        val big = text("Big or invoice")
        assertEquals(
            "a locked block is never the target of a text edit",
            FiltersWritePlan.BlockGone,
            filtersWrite(loaded, FiltersChange.PutText(name = big.name, was = big.text, text = "discard;")),
        )
        val emitted = written(FiltersChange.DeleteText(name = big.name, was = big.text))
        val reread = loadedFilterRules("bulwark", emitted)
        assertEquals(BulwarkMetadata.READABLE, reread.script!!.bulwarkMetadata)
        assertEquals(listOf("Newsletters", "Boss", "Sleeping", "Roundcube one", "Roundcube two"), reread.scriptRules.map { it.name })
        assertFalse(emitted.contains("invoice"))
    }

    @Test fun `a form rule and a text block are deleted by what they held`() {
        val emitted = written(FiltersChange.DeleteRule(was = form("Boss")))
        assertEquals(
            listOf("Newsletters", "Big or invoice", "Sleeping", "Roundcube one", "Roundcube two"),
            loadedFilterRules("bulwark", emitted).scriptRules.map { it.name },
        )
        val two = text("Roundcube two")
        assertFalse(written(FiltersChange.DeleteText(name = two.name, was = two.text)).contains("rc2"))
    }

    @Test fun `the restart writes the script back byte for byte`() {
        assertEquals(BULWARK, written(FiltersChange.Rewrite))
    }

    @Test fun `an edited text that leaves a bracket open writes nothing`() {
        val two = text("Roundcube two")
        assertEquals(
            FiltersWritePlan.TextUncut,
            filtersWrite(loaded, FiltersChange.PutText(name = two.name, was = two.text, text = "if header :contains \"subject\" \"rc2\" {\n    discard;\n")),
        )
    }

    @Test fun `a change whose block is gone writes nothing`() {
        val gone = form("Boss").copy(value = "somebody-else-changed-it")
        assertEquals(FiltersWritePlan.BlockGone, filtersWrite(loaded, FiltersChange.PutRule(was = gone, rule = gone.copy(flag = true))))
        assertEquals(FiltersWritePlan.BlockGone, filtersWrite(loaded, FiltersChange.DeleteRule(was = gone)))
        assertEquals(FiltersWritePlan.BlockGone, filtersWrite(loaded, FiltersChange.PutText(name = "Roundcube two", was = "gone;", text = "keep;")))
    }

    @Test fun `a change refused because the script changed is replayed over the new read, found by its content`() {
        val plain = "require [\"fileinto\"];\n\n# rule:[boss]\nif address :is \"from\" \"boss@example.com\" {\n    fileinto \"Work\";\n}\n"
        val before = loadedFilterRules("roundcube", plain)
        val boss = (before.scriptRules.single() as ScriptRule.Form)
        val edit = FiltersChange.PutRule(was = boss.rule, rule = boss.rule.copy(value = "chief@example.com"))
        // Somebody added a rule above it elsewhere: the ids moved, the boss rule did not change.
        val top = "# rule:[Top]\nif header :contains \"subject\" \"top\" {\n    discard;\n}\n\n"
        val elsewhere = loadedFilterRules("roundcube", plain.replace("# rule:[boss]", top + "# rule:[boss]"))
        val moved = elsewhere.scriptRules.single { it.name == "boss" }
        assertNotEquals("the premise: the id the edit was made on now names another block", boss.id, moved.id)

        assertEquals(FiltersReplay.Again(edit), filtersReplay(elsewhere, edit, replayedOnce = false, warns = false))
        val emitted = written(edit, over = elsewhere)
        assertEquals(
            "replayed on the boss block, wherever its id now is, and the rule added elsewhere survives",
            plain.replace("# rule:[boss]", top + "# rule:[boss]").replace("boss@example.com", "chief@example.com"),
            emitted,
        )
    }

    @Test fun `a replay whose block is gone offers an edited rule back, and drops the rest`() {
        val boss = form("Boss")
        val without = loadedFilterRules("bulwark", written(FiltersChange.DeleteRule(was = boss)))
        val edit = FiltersChange.PutRule(was = boss, rule = boss.copy(value = "CRITICAL"))
        assertEquals(
            "the typed rule comes back as a new one: its block is gone, so a Save must add it",
            FiltersReplay.Propose(FiltersChange.PutRule(was = null, rule = boss.copy(value = "CRITICAL"))),
            filtersReplay(without, edit, replayedOnce = false, warns = false),
        )
        assertEquals(FiltersReplay.Dropped, filtersReplay(without, FiltersChange.DeleteRule(was = boss), replayedOnce = false, warns = false))
        val two = text("Roundcube two")
        val withoutTwo = loadedFilterRules("bulwark", written(FiltersChange.DeleteText(two.name, two.text)))
        assertEquals(FiltersReplay.Dropped, filtersReplay(withoutTwo, FiltersChange.PutText(two.name, two.text, "keep;"), replayedOnce = false, warns = false))
    }

    @Test fun `an added rule and the restart need no block and are always replayed once`() {
        val added = FiltersChange.PutRule(was = null, rule = FilterRule(name = "n", value = "v"))
        val empty = loadedFilterRules(null, null)
        assertEquals(FiltersReplay.Again(added), filtersReplay(empty, added, replayedOnce = false, warns = false))
        assertEquals(FiltersReplay.Again(FiltersChange.Rewrite), filtersReplay(empty, FiltersChange.Rewrite, replayedOnce = false, warns = false))
    }

    @Test fun `a second refusal in a row hands the edit back instead of racing the other writer`() {
        val boss = form("Boss")
        val edit = FiltersChange.PutRule(was = boss, rule = boss.copy(value = "CRITICAL"))
        assertEquals(
            "still found: offered back over the same rule, not as a new one",
            FiltersReplay.Propose(edit),
            filtersReplay(loaded, edit, replayedOnce = true, warns = false),
        )
        assertEquals(FiltersReplay.Dropped, filtersReplay(loaded, FiltersChange.Rewrite, replayedOnce = true, warns = false))
    }

    @Test fun `of two identical rules, the one the change was made on is the one written`() {
        val block = "# rule:[twin]\nif address :is \"from\" \"twin@example.com\" {\n    fileinto \"Work\";\n}\n"
        val twins = "require [\"fileinto\"];\n\n$block\n$block"
        val read = loadedFilterRules("sterna", twins)
        val listing = filtersListing(FiltersUiState().withScript(read)).filterIsInstance<FiltersListItem.Form>()
        assertEquals("each twin carries its rank among the identical ones", listOf(0, 1), listing.map { it.rank })
        val second = listing[1]
        val emitted = written(FiltersChange.PutRule(was = second.rule, rule = second.rule.copy(value = "other@example.com"), rank = second.rank), over = read)
        assertEquals(
            "the second block changed, the first did not",
            twins.substringBeforeLast("twin@example.com") + "other@example.com" + twins.substringAfterLast("twin@example.com"),
            emitted,
        )
        val deleted = written(FiltersChange.DeleteRule(was = second.rule, rank = 1), over = read)
        assertEquals(1, loadedFilterRules("sterna", deleted).scriptRules.size)
        assertTrue(deleted, deleted.startsWith("require [\"fileinto\"];\n\n$block"))
    }

    @Test fun `a text block is found by its name, its text and its rank among twins`() {
        val rules = listOf(
            ScriptRule.Text(id = 11, name = "a", text = "same;", lockedByBulwark = false),
            ScriptRule.Text(id = 12, name = "b", text = "same;", lockedByBulwark = false),
            ScriptRule.Text(id = 13, name = "b", text = "same;", lockedByBulwark = false),
            ScriptRule.Text(id = 14, name = "b", text = "same;", lockedByBulwark = true),
        )
        assertEquals("the name tells two identical texts apart", 12, FiltersChange.PutText("b", "same;", "x;").targetIn(rules))
        assertEquals(11, FiltersChange.PutText("a", "same;", "x;").targetIn(rules))
        assertEquals("the rank tells two identical blocks of the same name apart", 13, FiltersChange.PutText("b", "same;", "x;", rank = 1).targetIn(rules))
        assertEquals("a Bulwark block is never edited as text", null, FiltersChange.PutText("b", "same;", "x;", rank = 2).targetIn(rules))
        assertEquals(12, FiltersChange.DeleteText("b", "same;").targetIn(rules))
        assertEquals(11, FiltersChange.DeleteText("a", "same;").targetIn(rules))
        assertEquals("a deletion reaches a Bulwark block too", 14, FiltersChange.DeleteText("b", "same;", rank = 2).targetIn(rules))
        val listed = filtersListing(FiltersUiState(scriptRules = rules)).filterIsInstance<FiltersListItem.Text>()
        assertEquals(listOf(0, 0, 1, 0), listed.map { it.rank })
    }

    @Test fun `a text whose block changed elsewhere is offered back over that block as it now reads`() {
        val two = text("Roundcube two")
        val typed = FiltersChange.PutText(name = two.name, was = two.text, text = "discard;\n")
        val changedElsewhere = loadedFilterRules("bulwark", BULWARK.replace("\"rc2\"", "\"rc4\""))
        val now = changedElsewhere.scriptRules.single { it.name == "Roundcube two" } as ScriptRule.Text
        assertEquals(
            "what was typed is kept, and the Save now goes against the text the block holds, so it cannot fail on it again",
            FiltersReplay.Propose(typed.copy(was = now.text)),
            filtersReplay(changedElsewhere, typed, replayedOnce = false, warns = false),
        )
    }

    @Test fun `a replay that would stop the auto-reply is offered back, never written`() {
        val boss = form("Boss")
        val edit = FiltersChange.PutRule(was = boss, rule = boss.copy(value = "CRITICAL"))
        assertEquals(FiltersReplay.Propose(edit), filtersReplay(loaded, edit, replayedOnce = false, warns = true))
        assertEquals(FiltersReplay.Dropped, filtersReplay(loaded, FiltersChange.Rewrite, replayedOnce = false, warns = true))
    }

    @Test fun `a text left open is its own failure, every other save failure stays a failed save with its detail`() {
        val saving = opened.copy(saving = true)

        val uncut = filtersSaveFailed(saving, SieveTextUncut())
        assertEquals(
            "a text that cannot be cut never reached the server: it must not be worded as a failed save " +
                "carrying the exception's English message",
            FiltersError.TEXT_UNCUT,
            uncut.errorKind,
        )
        assertFalse(uncut.saving)

        listOf(IllegalStateException("x"), IllegalArgumentException("x")).forEach { failure ->
            val other = filtersSaveFailed(saving, failure)
            assertEquals(
                "$failure: the model's require() calls throw IllegalArgumentException too, and they are not a text left open",
                FiltersError.SAVE,
                other.errorKind,
            )
            assertEquals("$failure", "x", other.errorDetail)
        }
    }

    @Test fun `a server refusing the text keeps the script as read, and its own words`() {
        val refused = ManageSieveException(
            "PUTSCRIPT: NO line 30: unknown command 'nonsense'",
            ManageSieveException.Kind.REFUSED,
            serverText = "line 30: unknown command 'nonsense'",
        )
        val failed = filtersSaveFailed(opened.copy(saving = true), refused)
        assertEquals(opened.scriptRules, failed.scriptRules)
        assertFalse(failed.saving)
        assertEquals(FiltersError.SAVE, failed.errorKind)
        assertEquals("line 30: unknown command 'nonsense'", failed.errorDetail)
        assertFalse("a refusal does not read the script again over the edit", reloadsAfterFailedSave(refused))
        assertTrue(reloadsAfterFailedSave(FilterScriptChangedException()))
    }

    @Test fun `a readable script with text blocks saves without asking, an unreadable one still asks`() {
        fun step(text: String): FiltersSaveStep {
            val read = loadedFilterRules("any", text)
            val state = filtersStateWithStatus(
                state = FiltersUiState().withScript(read),
                status = null,
                unreadableFromRulesRead = read.scriptUnreadable,
            )
            return filtersSaveStep(scriptUnreadable = state.scriptUnreadable)
        }
        assertEquals(FiltersSaveStep.WRITE, step(BULWARK))
        assertEquals(FiltersSaveStep.CONFIRM_OVERWRITE, step("if true {\n    discard;\n"))
    }

    private companion object {
        val BULWARK = """
/* @metadata:begin
{"version":1,"rules":[{"id":"b237-1","name":"Newsletters","enabled":true,"matchType":"all","conditions":[{"field":"from","comparator":"contains","value":"news@"}],"actions":[{"type":"move","value":"Archive"}],"stopProcessing":false},{"id":"b237-2","name":"Boss","enabled":true,"matchType":"all","conditions":[{"field":"subject","comparator":"is","value":"URGENT"}],"actions":[{"type":"star"},{"type":"mark_read"}],"stopProcessing":false},{"id":"b237-3","name":"Big or invoice","enabled":true,"matchType":"any","conditions":[{"field":"size","comparator":"greater_than","value":"5M"},{"field":"body","comparator":"contains","value":"invoice"}],"actions":[{"type":"discard"}],"stopProcessing":false},{"id":"b237-4","name":"Sleeping","enabled":false,"matchType":"all","conditions":[{"field":"cc","comparator":"contains","value":"sleep@"}],"actions":[{"type":"discard"}],"stopProcessing":false}]}
@metadata:end */

require ["body", "comparator-i;ascii-numeric", "fileinto", "imap4flags", "relational", "spamtestplus"];

# Rule: Newsletters
if allof(header :contains "From" "news@", not spamtest :percent :value "ge" :comparator "i;ascii-numeric" "50") {
    fileinto "Archive";
}

# Rule: Boss
if header :is "Subject" "URGENT" {
    addflag "\\Flagged";
    addflag "\\Seen";
}

# Rule: Big or invoice
if anyof(size :over 5M, body :contains "invoice") {
    discard;
}

# --- External rules (managed outside Bulwark) ---
# rule:[Roundcube one]
if header :contains "subject" "roundcube" {
    fileinto "Archive";
}

# rule:[Roundcube two]
if header :contains "subject" "rc2" {
    fileinto "Archive";
    keep;
}
""".trimStart()
    }
}

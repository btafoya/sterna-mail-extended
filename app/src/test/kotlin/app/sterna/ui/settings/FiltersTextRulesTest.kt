package app.sterna.ui.settings

import app.sterna.core.data.filter.BulwarkMetadata
import app.sterna.core.data.filter.FilterRule
import app.sterna.core.data.filter.FilterScriptChangedException
import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.imap.ManageSieveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The filters list beyond the form: Sieve text blocks edited as text, Bulwark rules that can only be deleted. */
class FiltersTextRulesTest {

    private val loaded = loadedFilterRules("bulwark", BULWARK)
    private val opened = FiltersUiState().withScript(loaded)
    private val serverRules = loaded.rules

    private fun textId(name: String) = loaded.scriptRules.single { it is ScriptRule.Text && it.name == name }.id

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

    @Test fun `a text edit is listed, marks the screen dirty, and undone it is no edit`() {
        val id = textId("Roundcube two")
        val original = (loaded.scriptRules.single { it.id == id } as ScriptRule.Text).text
        val edited = opened.withTextEdit(id, original.replace("rc2", "rc3"))

        assertTrue(filtersDirty(edited, serverRules))
        assertEquals(
            original.replace("rc2", "rc3"),
            filtersListing(edited).filterIsInstance<FiltersListItem.Text>().single { it.id == id }.text,
        )
        val undone = edited.withTextEdit(id, original)
        assertEquals(emptyMap<Int, String>(), undone.textEdits)
        assertFalse(filtersDirty(undone, serverRules))
    }

    @Test fun `a Bulwark rule takes no text edit, only a deletion`() {
        val id = textId("Big or invoice")
        assertEquals(opened, opened.withTextEdit(id, "discard;"))

        val removed = opened.withTextRemoved(id)
        assertTrue(filtersDirty(removed, serverRules))
        assertFalse(filtersListing(removed).any { it is FiltersListItem.Text && it.id == id })

        val write = filtersWrite(loaded, removed)!!
        val reread = loadedFilterRules(write.name, write.script.emit())
        assertEquals(BulwarkMetadata.READABLE, reread.script!!.bulwarkMetadata)
        assertEquals(
            listOf("Newsletters", "Boss", "Sleeping", "Roundcube one", "Roundcube two"),
            reread.scriptRules.map { it.name },
        )
        assertFalse(write.script.emit().contains("invoice"))
    }

    @Test fun `a block edited then deleted is written deleted, without a throw`() {
        val id = textId("Roundcube two")
        val state = opened.withTextEdit(id, "discard;").withTextRemoved(id)

        val write = filtersWrite(loaded, state)!!
        assertFalse(write.script.emit().contains("rc2"))
        assertEquals(
            listOf("Newsletters", "Boss", "Big or invoice", "Sleeping", "Roundcube one"),
            loadedFilterRules(write.name, write.script.emit()).scriptRules.map { it.name },
        )
    }

    @Test fun `Save writes the edited text in place of its block, the rest byte for byte`() {
        val id = textId("Roundcube two")
        val original = (loaded.scriptRules.single { it.id == id } as ScriptRule.Text).text
        val state = opened.withTextEdit(id, original.replace("rc2", "rc3"))

        val write = filtersWrite(loaded, state)!!
        assertEquals("bulwark", write.name)
        assertEquals(BULWARK.replace("\"rc2\"", "\"rc3\""), write.script.emit())
    }

    @Test fun `a text edited into a form rule is written along with a form edit, neither lost`() {
        val id = textId("Roundcube two")
        val asForm = "# rule:[Roundcube two]\nif header :contains \"subject\" \"rc9\" {\n    fileinto \"Archive\";\n}"
        val state = opened.withTextEdit(id, asForm)
            .let { it.copy(rules = it.rules + FilterRule(name = "new", value = "x@example.com", moveTo = "Box"), ruleIds = it.ruleIds + listOf(null)) }

        val emitted = filtersWrite(loaded, state)!!.script.emit()
        assertTrue(emitted, emitted.contains("\"rc9\""))
        assertTrue(emitted, emitted.contains("x@example.com"))
    }

    @Test fun `an edited text that leaves a bracket open writes nothing`() {
        val state = opened.withTextEdit(textId("Roundcube two"), "if header :contains \"subject\" \"rc2\" {\n    discard;\n")
        assertNull(filtersWrite(loaded, state))
    }

    @Test fun `a text left open is its own failure, every other save failure stays a failed save with its detail`() {
        val edited = opened.withTextEdit(textId("Roundcube two"), "if true {").copy(saving = true, dirty = true)

        val uncut = filtersSaveFailed(edited, SieveTextUncut())
        assertEquals(
            "a text that cannot be cut never reached the server: it must not be worded as a failed save " +
                "carrying the exception's English message",
            FiltersError.TEXT_UNCUT,
            uncut.errorKind,
        )
        assertFalse(uncut.saving)
        assertEquals(edited.textEdits, uncut.textEdits)

        listOf(IllegalStateException("x"), IllegalArgumentException("x")).forEach { failure ->
            val other = filtersSaveFailed(edited, failure)
            assertEquals(
                "$failure: the model's require() calls throw IllegalArgumentException too, and they are not a text left open",
                FiltersError.SAVE,
                other.errorKind,
            )
            assertEquals("$failure", "x", other.errorDetail)
        }
    }

    @Test fun `a server refusing the text keeps the edit on screen, and its own words`() {
        val id = textId("Roundcube two")
        val edited = opened.withTextEdit(id, "if header :contains \"subject\" \"rc2\" { nonsense; }").copy(saving = true, dirty = true)
        val refused = ManageSieveException(
            "PUTSCRIPT: NO line 30: unknown command 'nonsense'",
            ManageSieveException.Kind.REFUSED,
            serverText = "line 30: unknown command 'nonsense'",
        )

        val failed = filtersSaveFailed(edited, refused)
        assertEquals(edited.textEdits, failed.textEdits)
        assertEquals(edited.removedText, failed.removedText)
        assertTrue(failed.dirty)
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

    @Test fun `a saved script starts again with no edit pending`() {
        val id = textId("Roundcube two")
        val state = opened.withTextEdit(id, "discard;").withTextRemoved(textId("Big or invoice"))
        val saved = state.withScript(loaded)
        assertEquals(emptyMap<Int, String>(), saved.textEdits)
        assertEquals(emptySet<Int>(), saved.removedText)
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
    stop;
}
""".trimStart()
    }
}

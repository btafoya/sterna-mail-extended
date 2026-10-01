package app.sterna.ui.settings

import app.sterna.core.data.filter.ScriptRule
import app.sterna.core.data.filter.loadedFilterRules
import app.sterna.core.data.mail.FilterRulesState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a drop in the reorder mode writes, and the drops it refuses because the script would. */
class FiltersMoveTest {

    private fun block(name: String) = "# $name\nif header :contains \"subject\" \"${name.lowercase()}\" {\n    fileinto \"$name\";\n}\n"

    private val text = "# T\nif header :contains \"subject\" \"t\" {\n    discard;\n}\n"

    private fun spaced(vararg blocks: String) = "require [\"fileinto\"];\n" + blocks.joinToString("") { "\n" + it }

    private fun FilterRulesState.Loaded.drop(from: Int, to: Int) = filtersDrop(scriptRules, scriptToEdit, from, to)

    private fun FilterRulesState.Loaded.written(change: FiltersChange) =
        (filtersWrite(this, change) as FiltersWritePlan.Write).write.script.emit()

    @Test fun `a drop names the rule and the one it lands before, and writes that move`() {
        val loaded = loadedFilterRules("s", spaced(block("A"), block("B"), text))
        assertTrue(loaded.scriptRules[2] is ScriptRule.Text)
        val up = loaded.drop(2, 0)!!
        assertEquals(spaced(text, block("A"), block("B")), loaded.written(up))
        val down = loaded.drop(0, 2)!!
        assertNull("the last place is named by no rule", down.before)
        assertEquals(spaced(block("B"), text, block("A")), loaded.written(down))
        assertEquals(spaced(block("B"), block("A"), text), loaded.written(loaded.drop(1, 0)!!))
        assertEquals(spaced(block("A"), text, block("B")), loaded.written(loaded.drop(1, 2)!!))
    }

    @Test fun `a drop the script refuses, or no drop at all, writes nothing`() {
        val nextcloud = "### Nextcloud Mail: Filters ### DON'T EDIT ###\nrequire [\"fileinto\"];\n" +
            "### Nextcloud Mail: Filters ### DON'T EDIT ###\n### Nextcloud Mail: Filters ### DON'T EDIT ###\n# Invoices\nif header :contains \"subject\" \"invoice\" {\nfileinto \"Invoices\";\n}\n" +
            "### Nextcloud Mail: Filters ### DON'T EDIT ###\n" + block("Mine")
        val loaded = loadedFilterRules("s", nextcloud)
        assertEquals(listOf("Invoices", "Mine"), loaded.scriptRules.map { it.name })
        assertNull(loaded.drop(1, 0))
        assertNull(loaded.drop(0, 1))
        val plain = loadedFilterRules("s", spaced(block("A"), block("B")))
        assertNull(plain.drop(0, 0))
        assertNull(plain.drop(0, 2))
        assertNull(plain.drop(-1, 0))
    }

    @Test fun `a move written over a script changed meanwhile finds its rules by what they held`() {
        val loaded = loadedFilterRules("s", spaced(block("A"), block("B")))
        val change = loaded.drop(1, 0)!!
        val changed = loadedFilterRules("s", spaced(block("C"), block("A"), block("B")))
        assertEquals(spaced(block("C"), block("B"), block("A")), changed.written(change))
        val gone = loadedFilterRules("s", spaced(block("B"), block("C")))
        assertEquals(FiltersWritePlan.BlockGone, filtersWrite(gone, change))
    }

    @Test fun `of two twin blocks, moving the second moves the second`() {
        val loaded = loadedFilterRules("s", spaced(text + text, block("A")))
        val change = loaded.drop(1, 2)!!
        assertEquals(1, change.moved.rank)
        assertEquals(spaced(text + block("A")) + "\n" + text, loaded.written(change))
        val keys = filtersReorderRows(loaded.scriptRules).map { it.key }
        assertEquals("twins keep two keys", keys.distinct(), keys)
    }

    @Test fun `a row keeps its key once its rule moved and the script was read again`() {
        val loaded = loadedFilterRules("s", spaced(block("A"), block("B"), text))
        val before = filtersReorderRows(loaded.scriptRules)
        val after = filtersReorderRows(loadedFilterRules("s", loaded.written(loaded.drop(2, 0)!!)).scriptRules)
        assertEquals(listOf(before[2], before[0], before[1]).map { it.key }, after.map { it.key })
        assertEquals("ids are given by position: the very thing a key must not follow", before.map { it.rule.id }, after.map { it.rule.id })
    }

    @Test fun `the reorder mode is offered over two rules read, and only then`() {
        val two = FiltersUiState(loading = false).withScript(loadedFilterRules("s", spaced(block("A"), block("B"))))
        assertTrue(filtersMayReorder(two))
        assertFalse(filtersMayReorder(two.copy(loading = true)))
        assertFalse(filtersMayReorder(two.copy(errorKind = FiltersError.LOAD)))
        assertFalse(filtersMayReorder(FiltersUiState(loading = false).withScript(loadedFilterRules("s", spaced(block("A"))))))
    }
}

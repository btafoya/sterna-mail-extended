package app.sterna.core.data.filter

import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.readable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moving a rule: its block and the comments glued above it change place, every other byte stays. */
class SieveScriptMoveTest {

    private fun block(name: String) = "# $name\nif header :contains \"subject\" \"${name.lowercase()}\" {\n    fileinto \"$name\";\n}\n"

    private val require = "require [\"fileinto\"];\n"

    private fun spaced(vararg names: String) = require + names.joinToString("") { "\n" + block(it) }

    private fun SieveScript.id(name: String) = rules.single { it.name == name }.id

    private fun SieveScript.names() = rules.map { it.name }

    @Test fun `a moved block takes its name comment along, and the rest comes back byte for byte`() {
        val text = fixture("roundcube")
        val script = readable(text)
        val either = text.substring(text.indexOf("# rule:[either]"), text.indexOf("# rule:[away]"))
        val moved = script.move(script.id("either"), before = script.id("Spam"))
        assertNotNull(moved)
        assertEquals(text.replace(either, "").replace("# rule:[Spam]", either + "# rule:[Spam]"), moved!!.emit())
        assertEquals(listOf("either", "Spam", "boss", "away"), readable(moved.emit()).names())
        assertEquals(
            "the moved rule is still the form rule it was",
            (script.rules[2] as ScriptRule.Form).rule,
            (readable(moved.emit()).rules[0] as ScriptRule.Form).rule,
        )
    }

    @Test fun `a block moves with the blank line that set it apart, and moving it back restores the script`() {
        val text = spaced("A", "B", "C")
        val script = readable(text)
        val last = script.move(script.id("A"), before = null)!!.emit()
        assertEquals(spaced("B", "C", "A"), last)
        val reread = readable(last)
        assertEquals(text, reread.move(reread.id("A"), before = reread.id("B"))!!.emit())
        assertEquals(spaced("A", "C", "B"), script.move(script.id("C"), before = script.id("B"))!!.emit())
    }

    @Test fun `a block written by hand moves like any rule`() {
        val text = fixture("roundcube")
        val script = readable(text)
        val away = script.rules.single { it.name == "away" }
        assertTrue(away is ScriptRule.Text)
        val awayText = text.substring(text.indexOf("# rule:[away]"))
        val moved = script.move(away.id, before = script.id("boss"))!!.emit()
        assertEquals(text.removeSuffix(awayText).replace("# rule:[boss]", awayText + "# rule:[boss]"), moved)
        assertEquals(listOf("Spam", "away", "boss", "either"), readable(moved).names())
    }

    @Test fun `a block at the end of a script with no last line ending is moved whole`() {
        val text = spaced("A", "B").removeSuffix("\n")
        val script = readable(text)
        assertEquals(spaced("B", "A").removeSuffix("\n") + "\n", script.move(script.id("B"), before = script.id("A"))!!.emit())
    }

    @Test fun `moving a rule where it already is changes nothing`() {
        val text = spaced("A", "B")
        val script = readable(text)
        assertEquals(text, script.move(script.id("A"), before = script.id("B"))!!.emit())
        assertEquals(text, script.move(script.id("B"), before = null)!!.emit())
    }

    // ---- Bulwark: the JSON is reordered with the blocks ----

    @Test fun `a Bulwark rule moves its JSON entry and its block, and the script reads back as Bulwark's`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val newsletters = text.substring(text.indexOf("{\"id\":\"b237-1\""), text.indexOf(",{\"id\":\"b237-2\""))
        val boss = text.substring(text.indexOf("{\"id\":\"b237-2\""), text.indexOf(",{\"id\":\"b237-3\""))
        val newsBlock = text.substring(text.indexOf("# Rule: Newsletters"), text.indexOf("# Rule: Boss"))
        val bossBlock = text.substring(text.indexOf("# Rule: Boss"), text.indexOf("# Rule: Big or invoice"))
        val moved = script.move(script.id("Boss"), before = script.id("Newsletters"))!!.emit()
        assertEquals(
            text.replace("$newsletters,$boss", "$boss,$newsletters").replace(newsBlock + bossBlock, bossBlock + newsBlock),
            moved,
        )
        val reread = readable(moved)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertEquals(listOf("Boss", "Newsletters", "Big or invoice", "Sleeping", "Roundcube one", "Roundcube two"), reread.names())
        assertEquals(listOf(true, true), reread.rules.take(2).map { (it as ScriptRule.Form).bulwark })
        assertEquals(script.rules[1], reread.rules[0].let { it as ScriptRule.Form }.copy(id = script.rules[1].id))
    }

    @Test fun `a Bulwark rule with no block moves in the JSON alone`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val sleeping = text.substring(text.indexOf(",{\"id\":\"b237-4\""), text.indexOf("]}\n@metadata:end"))
        val moved = script.move(script.id("Sleeping"), before = script.id("Newsletters"))!!.emit()
        assertEquals(text.replace(sleeping, "").replace("{\"id\":\"b237-1\"", sleeping.removePrefix(",") + ",{\"id\":\"b237-1\""), moved)
        assertEquals(BulwarkMetadata.READABLE, readable(moved).bulwarkMetadata)
        assertEquals(listOf("Sleeping", "Newsletters", "Boss", "Big or invoice"), readable(moved).names().take(4))
    }

    @Test fun `a Bulwark rule and an external one never trade sides`() {
        val script = readable(fixture("f237-bulwark"))
        assertNull(script.move(script.id("Newsletters"), before = script.id("Roundcube two")))
        assertNull(script.move(script.id("Newsletters"), before = null))
        assertNull(script.move(script.id("Roundcube two"), before = script.id("Boss")))
        assertNotNull("last of Bulwark's rules is before the first external one", script.move(script.id("Newsletters"), before = script.id("Roundcube one")))
    }

    @Test fun `an external rule moves without the separator glued above it`() {
        val text = fixture("f237-bulwark")
        val script = readable(text)
        val separator = "# --- External rules (managed outside Bulwark) ---\n"
        val one = text.substring(text.indexOf("# rule:[Roundcube one]"), text.indexOf("\n# rule:[Roundcube two]"))
        val two = text.substring(text.indexOf("# rule:[Roundcube two]"))
        val moved = script.move(script.id("Roundcube one"), before = null)!!.emit()
        assertEquals(text.substringBefore(separator) + separator + two + "\n" + one, moved)
        val reread = readable(moved)
        assertEquals(BulwarkMetadata.READABLE, reread.bulwarkMetadata)
        assertEquals(listOf("Roundcube two", "Roundcube one"), reread.names().takeLast(2))
        assertEquals(text, reread.move(reread.id("Roundcube one"), before = reread.id("Roundcube two"))!!.emit())
    }

    // ---- what a move may not cross ----

    @Test fun `a rule never moves into a Nextcloud region, nor out of it`() {
        val script = readable(fixture("nextcloud"))
        assertNull(script.move(script.id("mine, below the region"), before = script.id("Invoices")))
        assertNull(script.move(script.id("Invoices"), before = null))
    }

    @Test fun `a rule lands above a Nextcloud region at the bottom, never into it`() {
        val region = "### Nextcloud Mail: Filters ### DON'T EDIT ###\n# Inv\nif header :contains \"subject\" \"invoice\" {\nfileinto \"Invoices\";\n}\n" +
            "### Nextcloud Mail: Filters ### DON'T EDIT ###\n"
        val script = readable(spaced("A", "B") + "\n" + region)
        assertEquals(listOf("A", "B", "Inv"), script.names())
        assertEquals(spaced("B", "A") + "\n" + region, script.move(script.id("A"), before = script.id("Inv"))!!.emit())
        assertNull(script.move(script.id("A"), before = null))
    }

    @Test fun `a block set after a last one with no line ending starts on its own line`() {
        val text = require + block("A") + block("B").removeSuffix("\n")
        val script = readable(text)
        assertEquals(require + block("B") + block("A"), script.move(script.id("A"), before = null)!!.emit())
    }

    @Test fun `a block written by hand above Bulwark's separator stays on its side`() {
        val text = fixture("f237-bulwark").replace(
            "}\n\n# --- External",
            "}\n\n# Manual\nif header :contains \"subject\" \"m\" {\n    fileinto \"M\";\n}\n\n# --- External",
        )
        val script = readable(text)
        assertEquals(BulwarkMetadata.READABLE, script.bulwarkMetadata)
        assertEquals(listOf("Manual", "Roundcube one", "Roundcube two"), script.names().takeLast(3))
        assertNull(script.move(script.id("Manual"), before = script.id("Roundcube two")))
        assertNull(script.move(script.id("Manual"), before = null))
        assertNull(script.move(script.id("Roundcube two"), before = script.id("Manual")))
    }

    @Test fun `a rule never moves above the first require`() {
        val text = "# lead\nif true { keep; }\n" + spaced("A", "B")
        val script = readable(text)
        assertNull(script.move(script.id("A"), before = script.id("lead")))
        assertNotNull(script.move(script.id("B"), before = script.id("A")))
    }

    @Test fun `a rule never crosses the auto-reply`() {
        val responder = "\n# STERNA-VACATION-V1: {}\nvacation :days 7 \"away\";\n"
        val text = "require [\"fileinto\", \"vacation\"];\n" + listOf("A", "B").joinToString("") { "\n" + block(it) } + responder + "\n" + block("C")
        val script = readable(text)
        assertEquals(listOf("A", "B", "C"), script.names())
        assertNull(script.move(script.id("C"), before = script.id("A")))
        assertNull(script.move(script.id("A"), before = null))
        assertEquals(
            text.replace("\n" + block("A") + "\n" + block("B"), "\n" + block("B") + "\n" + block("A")),
            script.move(script.id("B"), before = script.id("A"))!!.emit(),
        )
    }

    @Test fun `an unknown rule, or a rule before itself, is refused`() {
        val script = readable(spaced("A", "B"))
        assertNull(script.move(999, before = null))
        assertNull(script.move(script.id("A"), before = 999))
        assertNull(script.move(script.id("A"), before = script.id("A")))
    }
}

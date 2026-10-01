package app.sterna.core.data.filter

import app.sterna.core.data.filter.SieveScriptReadTest.Companion.fixture
import app.sterna.core.data.filter.SieveScriptReadTest.Companion.readable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The auto-reply's `not anyof` follows the rules into the Trash through every rule edit, not only when the auto-reply is saved. */
class ResponderExclusionTest {

    private val away = SieveVacation(isEnabled = true, subject = "Away", textBody = "Back soon", trashFolder = "Trash")

    private val alice = blockRule("alice@example.test", "Trash")
    private val bob = blockRule("bob@example.test", "Trash")

    private val marker = SieveCodec.vacationMarkerLine(away) + "\n"
    private val plain = "vacation :subject \"Away\" \"Back soon\";"

    private fun excluding(vararg tests: String) =
        "if not anyof (${tests.joinToString(", ")}) {\n    $plain\n}"

    private fun from(address: String) = "address :is \"from\" \"$address\""

    private fun SieveScript.form(name: String) = rules.filterIsInstance<ScriptRule.Form>().single { it.name == name }

    @Test fun `a sender blocked after the auto-reply was turned on gets no reply`() {
        val loaded = loadedFilterRules("sterna", SieveCodec.generate(emptyList(), away))

        val once = blockWrite(loaded, alice).script
        val written = once.emit()

        assertTrue(written, written.endsWith(marker + excluding(from("alice@example.test")) + "\n"))
        assertEquals(VacationMarker.Found(away), SieveCodec.readVacation(written))
        val twice = once.add(bob).emit()
        assertTrue(twice, twice.endsWith(marker + excluding(from("alice@example.test"), from("bob@example.test")) + "\n"))
    }

    @Test fun `a disabled rule into the Trash leaves the exclusion`() {
        val script = readable(SieveCodec.generate(listOf(alice, bob), away))

        val off = script.update(script.form("alice@example.test").id, alice.copy(enabled = false)).emit()

        assertTrue(off, off.endsWith(marker + excluding(from("bob@example.test")) + "\n"))
    }

    @Test fun `a removed rule leaves the exclusion, and the last one removed takes the not anyof with it`() {
        val script = readable(SieveCodec.generate(listOf(alice, bob), away))

        val one = script.remove(script.form("alice@example.test").id)
        assertTrue(one.emit(), one.emit().endsWith(marker + excluding(from("bob@example.test")) + "\n"))

        val none = one.remove(one.form("bob@example.test").id).emit()
        assertTrue(none, none.endsWith(marker + plain + "\n"))
        assertFalse(none, "not anyof" in none)
    }

    @Test fun `a changed value moves the exclusion with it`() {
        val script = readable(SieveCodec.generate(listOf(alice), away))

        val changed = script.update(script.form("alice@example.test").id, blockRule("carol@example.test", "Trash").copy(name = "alice@example.test")).emit()

        assertTrue(changed, changed.endsWith(marker + excluding(from("carol@example.test")) + "\n"))
    }

    @Test fun `a text block rewritten as a rule into the Trash is excluded`() {
        val text = SieveCodec.generate(emptyList(), away) + "\n# rule:[x]\nif header :contains \"subject\" \"x\" {\n    discard;\n}\n"
        val script = readable(text)
        val block = script.rules.filterIsInstance<ScriptRule.Text>().single()

        val replaced = script.replaceText(block.id, "# rule:[x]\nif address :is \"from\" \"dave@example.test\" {\n    fileinto \"Trash\";\n}")!!.emit()

        assertTrue(replaced, (marker + excluding(from("dave@example.test")) + "\n") in replaced)
    }

    @Test fun `an edit that leaves the rules into the Trash alone leaves the auto-reply byte for byte`() {
        val first = readable(SieveCodec.generate(listOf(alice), away))
        val base = first.update(first.form("alice@example.test").id, alice.copy(name = "A1"))

        val renamed = base.update(base.form("A1").id, alice.copy(name = "A2")).emit()

        assertEquals(base.emit().replace("# A1\n", "# A2\n"), renamed)
    }

    @Test fun `an unreadable auto-reply marker is left as it was, and so is its block`() {
        val tail = "# STERNA-VACATION-V1: {not json\nvacation \"Gone\";\n"
        val text = SieveCodec.generate(emptyList()) + tail

        val written = readable(text).add(alice).emit()

        assertTrue(written, written.endsWith(tail))
        assertFalse(written, "not anyof" in written)
    }

    @Test fun `a vacation block with no marker of ours is left verbatim`() {
        val written = readable("require [\"vacation\"];\n\nvacation \"away\";\n").add(alice).emit()

        assertTrue(written, "\nvacation \"away\";\n" in written)
        assertFalse(written, "not anyof" in written)
    }

    @Test fun `an auto-reply turned off, or with no Trash named, excludes nobody`() {
        for (vacation in listOf(away.copy(isEnabled = false), away.copy(trashFolder = null))) {
            val written = readable(SieveCodec.generate(emptyList(), vacation)).add(alice).emit()

            assertFalse(written, "not anyof" in written)
            assertEquals(written, VacationMarker.Found(vacation), SieveCodec.readVacation(written))
        }
    }

    @Test fun `a vacation block of another client, under a marker of ours set apart from it, is left byte for byte`() {
        val sales = "if header :contains \"to\" \"sales@\" {\n    vacation \"Sales closed\";\n}\n"
        val text = "require [\"vacation\"];\n\n" + sales + marker

        val once = readable(text).add(alice)
        val twice = once.add(bob)

        for (written in listOf(once.emit(), twice.emit())) {
            assertTrue(written, sales in written)
            assertFalse(written, "not anyof" in written)
        }
    }

    @Test fun `a block under a marker turned off stays as written`() {
        val off = SieveCodec.vacationMarkerLine(away.copy(isEnabled = false)) + "\n"
        val text = "require [\"vacation\"];\n\n" + off + plain + "\n"

        val written = readable(text).add(alice).emit()

        assertTrue(written, written.endsWith(off + plain + "\n"))
        assertFalse(written, "not anyof" in written)
    }

    @Test fun `a Bulwark rule into the Trash changed or removed moves the exclusion with it`() {
        val script = readable(fixture("f237-bulwark")).withResponder(away).add(alice) { "sterna-t" }

        val changed = script.update(script.form("alice@example.test").id, blockRule("carol@example.test", "Trash").copy(name = "alice@example.test")).emit()
        val changedBlock = changed.substringAfter(marker)
        assertTrue(changed, changedBlock.startsWith("if not anyof ("))
        assertTrue(changed, "\"carol@example.test\"" in changedBlock)
        assertFalse(changed, "\"alice@example.test\"" in changedBlock)

        val removed = script.remove(script.form("alice@example.test").id).emit()
        assertEquals(removed, plain + "\n", removed.substringAfter(marker))
    }

    @Test fun `in a CRLF script the rewritten auto-reply block is CRLF`() {
        val text = SieveCodec.generate(emptyList(), away).replace("\n", "\r\n")

        val written = readable(text).add(alice).emit()

        assertEquals(
            written,
            "if not anyof (${from("alice@example.test")}) {\r\n    $plain\r\n}\r\n",
            written.substringAfter(SieveCodec.vacationMarkerLine(away) + "\r\n"),
        )
    }

    @Test fun `a Bulwark rule into the Trash is excluded too`() {
        val script = readable(fixture("f237-bulwark")).withResponder(away)

        val written = script.add(alice) { "sterna-t" }.emit()

        val block = written.substringAfter(marker)
        assertTrue(written, block.startsWith("if not anyof ("))
        assertTrue(written, "\"alice@example.test\"" in block)
    }
}

package app.sterna.core.data.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which script the filters open when one is asked by name, which one a write checks, and what the listing says after it. */
class ScriptChoiceTest {

    private val sternaActive = listOf(ListedScript("roundcube", false), ListedScript("sterna", true))

    @Test fun `a name asked for is opened while the listing holds it, else the usual pick`() {
        assertEquals("roundcube", chosenScriptName(sternaActive, "roundcube", serverResponder = false))
        assertEquals("sterna", chosenScriptName(sternaActive, null, serverResponder = false))
        assertEquals("sterna", chosenScriptName(sternaActive, "gone", serverResponder = false))
        assertNull(chosenScriptName(emptyList(), "gone", serverResponder = false))
    }

    @Test fun `on JMAP the server's vacation script can be asked for by name`() {
        val vacation = listOf(ListedScript("sterna", false), ListedScript("vacation", true))

        assertEquals("vacation", chosenScriptName(vacation, "vacation", serverResponder = true))
        assertEquals("sterna", chosenScriptName(vacation, null, serverResponder = true))
    }

    @Test fun `a script is inactive beside the active one only when a save would leave that one running`() {
        assertTrue(inactiveBesideActive(sternaActive, "roundcube", serverResponder = false))
        assertFalse("the active one itself", inactiveBesideActive(sternaActive, "sterna", serverResponder = false))
        assertFalse("nothing opened", inactiveBesideActive(sternaActive, null, serverResponder = false))
        assertFalse(
            "nothing active: a save activates it, the 'not being applied' line owns this case",
            inactiveBesideActive(listOf(ListedScript("roundcube", false)), "roundcube", serverResponder = false),
        )
        val vacation = listOf(ListedScript("roundcube", false), ListedScript("vacation", true))
        assertFalse("the JMAP responder: a save activates over it", inactiveBesideActive(vacation, "roundcube", serverResponder = true))
        assertTrue("an IMAP vacation script is an ordinary one", inactiveBesideActive(vacation, "roundcube", serverResponder = false))
    }

    @Test fun `the listing on a read marks the script chosen only when the name asked for is the one opened`() {
        val opened = loadedFilterRules("roundcube", "keep;\n")

        val asked = opened.listing(sternaActive, "roundcube", serverResponder = false)
        assertEquals(sternaActive, asked.scripts)
        assertTrue(asked.chosen)
        assertTrue(asked.snapshot.chosen)
        assertTrue(asked.inactiveBesideActive)

        assertFalse("a fallback is not the choice", loadedFilterRules("sterna", "keep;\n").listing(sternaActive, "gone", false).chosen)
        assertFalse(loadedFilterRules("sterna", "keep;\n").listing(sternaActive, null, false).chosen)
    }

    @Test fun `a write over a chosen script checks that script, and any other write the one the filters open now`() {
        assertEquals("roundcube", scriptWrittenOver(ScriptSnapshot("roundcube", "keep;\n", chosen = true), sternaActive, serverResponder = false))
        assertEquals("sterna", scriptWrittenOver(ScriptSnapshot("roundcube", "keep;\n"), sternaActive, serverResponder = false))
        assertNull(
            "a chosen script deleted since is not found, so the write is refused",
            scriptWrittenOver(ScriptSnapshot("gone", "keep;\n", chosen = true), sternaActive, serverResponder = false),
        )
    }

    @Test fun `unchangedSince compares the name and the text, whatever opened it`() {
        assertTrue(unchangedSince(ScriptSnapshot("roundcube", "keep;\n", chosen = true), "roundcube", "keep;\n"))
        assertFalse(unchangedSince(ScriptSnapshot("roundcube", "keep;\n", chosen = true), "roundcube", "discard;\n"))
        assertFalse(unchangedSince(ScriptSnapshot("roundcube", "keep;\n"), "sterna", "keep;\n"))
    }

    @Test fun `the listing after a write moves the active mark only when the write activated`() {
        assertEquals(sternaActive, listedAfterWrite(sternaActive, "roundcube", activated = false))
        assertEquals(
            listOf(ListedScript("roundcube", true), ListedScript("sterna", false)),
            listedAfterWrite(sternaActive, "roundcube", activated = true),
        )
        assertEquals(listOf(ListedScript("sterna", true)), listedAfterWrite(emptyList(), "sterna", activated = true))
    }
}

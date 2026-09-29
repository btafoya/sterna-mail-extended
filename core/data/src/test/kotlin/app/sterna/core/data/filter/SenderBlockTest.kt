package app.sterna.core.data.filter

import app.sterna.core.data.mail.FilterRulesState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-sender "future mail to Trash" gesture, executed: the rule it writes, the composition
 */
class SenderBlockTest {

    private val existing = listOf(
        FilterRule(name = "work", field = RuleField.SUBJECT, match = RuleMatch.CONTAINS, value = "invoice", moveTo = "Work"),
        FilterRule(name = "family", field = RuleField.FROM, match = RuleMatch.CONTAINS, value = "@family.example", flag = true),
        FilterRule(name = "lists", field = RuleField.TO, match = RuleMatch.IS, value = "list@example.com", markRead = true),
    )

    @Test fun `the rule tests the whole address and files it read into the trash`() {
        val rule = blockRule("News@Example.com", "Trash")
        assertEquals(RuleField.FROM, rule.field)
        assertEquals(RuleMatch.IS, rule.match)
        assertEquals("News@Example.com", rule.value)
        assertEquals("Trash", rule.moveTo)
        assertTrue("the mail must arrive already read, or it lights a badge", rule.markRead)
        assertTrue(rule.enabled)
        assertFalse("nothing flags it: this is not a message to look at", rule.flag)
        assertEquals("the rule is named by the address, with no invented prefix", "News@Example.com", rule.name)
    }

    @Test fun `the compiled Sieve moves and marks, and can neither discard nor reject`() {
        val script = SieveCodec.generate(listOf(blockRule("news@example.com", "INBOX.Trash")))
        assertTrue(script, "address :is \"from\" \"news@example.com\"" in script)
        assertTrue(script, "fileinto \"INBOX.Trash\";" in script)
        assertTrue(script, "addflag \"\\\\Seen\";" in script)
        // The invariant of the whole feature: a one-finger gesture in a list must never be able
        // to destroy mail on the server with no undo.
        assertFalse(script, "discard" in script)
        assertFalse(script, "reject" in script)
    }

    @Test fun `an address already handled is recognised whatever its case`() {
        val rules = existing + blockRule("News@Example.com", "Trash")
        assertTrue(alreadyBlocked(rules, "news@example.com"))
        assertTrue(alreadyBlocked(rules, "NEWS@EXAMPLE.COM"))
    }

    @Test fun `a FROM IS rule counts as present whatever it does with the mail`() {
        // Deliberately broad, and it must stay broad: two from/IS rules on one address compile to
        // two sequential `if`s, so both act on the same message. We refuse to stack a rule on top
        // of an existing one whatever its target, including one that is currently disabled,
        // which the user can switch back on in the filter editor at any moment.
        val elsewhere = FilterRule(name = "boss", field = RuleField.FROM, match = RuleMatch.IS, value = "boss@example.com", moveTo = "Work")
        val disabled = FilterRule(name = "old", enabled = false, field = RuleField.FROM, match = RuleMatch.IS, value = "old@example.com", moveTo = "Trash")
        assertTrue(alreadyBlocked(listOf(elsewhere), "boss@example.com"))
        assertTrue(alreadyBlocked(listOf(disabled), "old@example.com"))
        // …which is exactly why the label may not say where the mail goes. SenderVolumeCopyTest
        // holds that end of it, per locale.
    }

    @Test fun `a CONTAINS rule or another field is not this rule`() {
        // "@family.example" as a contains would catch news@family.example, but it is not the
        // rule this gesture writes, and treating it as one would silently refuse to act.
        assertFalse(alreadyBlocked(existing, "news@family.example"))
        assertFalse(alreadyBlocked(existing, "list@example.com")) // a to/IS rule, not a from rule
        assertFalse(alreadyBlocked(emptyList(), "news@example.com"))
    }

    // -- the two addresses no rule may target ----------------------------------------------------

    @Test fun `no rule may be aimed at one of the account's own addresses`() {
        // The defect this function exists for (banc-1.4.8.md § 4, R6): the gesture was offered on
        // a message the account itself sent, and the rule it would have written files the
        // account's own mail into the Trash, marked read, for ever.
        val own = listOf("alex.rivera@masto.top", "alex@alias.example")
        assertFalse(blockableSender("alex.rivera@masto.top", own))
        assertFalse("an alias is just as much oneself", blockableSender("alex@alias.example", own))
        assertTrue("and somebody else is not", blockableSender("news@example.com", own))
    }

    @Test fun `one's own address is recognised the way the rule is written`() {
        // Same comparison alreadyBlocked makes, and it has to be: the rule is stored as the
        // address is spelled, and `address :is "from"` reads it back case-insensitively. A guard
        // that only caught the exact spelling would be walked past by tapping the same gesture on
        // the same message with a capital in it.
        val own = listOf("  Alex.Rivera@Masto.TOP  ")
        assertFalse(blockableSender("alex.rivera@masto.top", own))
        assertFalse(blockableSender("ALEX.RIVERA@MASTO.TOP", own))
        assertFalse(blockableSender(" alex.rivera@masto.top ", own))
    }

    @Test fun `an address that is not one is no rule at all`() {
        // A From: carrying only a display name maps to email = "" (EmailMapper). `from :is ""`
        // matches nothing and can never be read back as anything; it would sit in the account's
        // script for good.
        assertFalse(blockableSender("", listOf("me@example.com")))
        assertFalse(blockableSender("   ", listOf("me@example.com")))
        assertFalse("…and with no identities known either", blockableSender("", emptyList()))
    }

    @Test fun `an account whose identities are unknown still rules on other senders`() {
        // The list is read from the store, so it is never legitimately empty, but an empty list
        // must degrade to "cannot say this is me", not to "nothing can be ruled".
        assertTrue(blockableSender("news@example.com", emptyList()))
    }

    // -- the order of the two server calls -------------------------------------------------------

    /** What [addBlockRule] handed its save: the name written, the read it goes over, and the text. */
    private class Saved {
        var name: String? = null
        var read: ScriptSnapshot? = null
        var text: String? = null
        val save: suspend (FilterScriptWrite) -> Unit = { w -> name = w.name; read = w.read; text = w.script.emit() }
    }

    /** [existing] as another client wrote them, in a script named `roundcube`, with a block the form cannot show. */
    private val roundcube = loadedFilterRules(
        "roundcube",
        "require [\"fileinto\", \"imap4flags\"];\n\n# rule:[stop]\nif true {\n    stop;\n}\n",
    ).let { loaded -> loadedFilterRules("roundcube", existing.fold(loaded.scriptToEdit) { s, r -> s.add(r) }.emit()) }

    @Test fun `the save receives the opened script under its name, plus one block, the rest byte for byte`() = runBlocking {
        val saved = Saved()
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { roundcube },
            save = saved.save,
        )
        assertEquals(BlockOutcome.ADDED, outcome)
        assertEquals("roundcube", saved.name)
        assertEquals(ScriptSnapshot("roundcube", roundcube.scriptText), saved.read)
        val before = roundcube.scriptText!!
        val after = saved.text!!
        assertTrue(after, after.startsWith(before.trimEnd('\n')))
        assertEquals(existing + blockRule("news@example.com", "Trash"), loadedFilterRules("roundcube", after).rules)
        assertEquals(roundcube.scriptRules.size + 1, loadedFilterRules("roundcube", after).scriptRules.size)
    }

    @Test fun `a readable Bulwark script takes the block as one of its own rules`() = runBlocking {
        val bulwark = loadedFilterRules("bulwark", SieveScriptReadTest.fixture("f237-bulwark"))
        val saved = Saved()
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { bulwark },
            save = saved.save,
        )
        assertEquals("an active script this app can read is no longer somebody else's", BlockOutcome.ADDED, outcome)
        assertEquals("bulwark", saved.name)
        val reread = loadedFilterRules("bulwark", saved.text!!)
        assertEquals(BulwarkMetadata.READABLE, reread.script!!.bulwarkMetadata)
        assertEquals(
            "a Bulwark entry comes after the Bulwark entries, before the external rules",
            bulwark.rules.take(2) + blockRule("news@example.com", "Trash") + bulwark.rules.drop(2),
            reread.rules,
        )
        fun texts(state: FilterRulesState.Loaded) =
            state.scriptRules.filterIsInstance<ScriptRule.Text>().map { Triple(it.name, it.text, it.lockedByBulwark) }
        assertEquals(texts(bulwark), texts(reread))
    }

    @Test fun `an account with no script gets sterna, holding the block alone`() = runBlocking {
        val saved = Saved()
        val outcome = addBlockRule("news@example.com", "Trash", load = { loadedFilterRules(null, null) }, save = saved.save)
        assertEquals(BlockOutcome.ADDED, outcome)
        assertEquals("sterna", saved.name)
        assertEquals("nothing was read: the write is refused if a script appeared since", ScriptSnapshot(null, null), saved.read)
        assertEquals(listOf(blockRule("news@example.com", "Trash")), loadedFilterRules("sterna", saved.text!!).rules)
    }

    @Test fun `a load that throws writes nothing`() = runBlocking {
        val saved = Saved()
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { throw IllegalStateException("offline") },
            save = saved.save,
        )
        assertEquals(BlockOutcome.FAILED, outcome)
        assertNull("a save here would wipe the account's filters", saved.text)
    }

    @Test fun `an unsupported account writes nothing`() = runBlocking {
        val saved = Saved()
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { FilterRulesState.Unsupported },
            save = saved.save,
        )
        assertEquals(BlockOutcome.FAILED, outcome)
        assertNull(saved.text)
    }

    @Test fun `an address already handled is not written again`() = runBlocking {
        val saved = Saved()
        val outcome = addBlockRule(
            address = "NEWS@example.com",
            trashFolder = "Trash",
            load = { FilterRulesState.Loaded(existing + blockRule("news@example.com", "Trash")) },
            save = saved.save,
        )
        assertEquals(BlockOutcome.ALREADY_PRESENT, outcome)
        assertNull(saved.text)
    }

    @Test fun `a script that cannot be cut into blocks is left alone`() = runBlocking {
        // Written from an empty script, the block would replace everything the account runs.
        val saved = Saved()
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { loadedFilterRules("roundcube", "keep;\n/* never closed\n") },
            save = saved.save,
        )
        assertEquals(BlockOutcome.FAILED, outcome)
        assertNull("her own Sieve script must be left as it is", saved.text)
    }

    @Test fun `on JMAP with the server's auto-reply running, the block is refused and nothing written`() = runBlocking {
        val saved = Saved()
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { loadedFilterRules("sterna", roundcube.scriptText, stopsServerResponder = true) },
            save = saved.save,
        )
        assertEquals(BlockOutcome.FAILED, outcome)
        assertNull("saving activates sterna and would stop the auto-reply", saved.text)
    }

    @Test fun `blockWrite goes over the opened script under its own name`() {
        val write = blockWrite(roundcube, blockRule("news@example.com", "Trash"))
        assertEquals("roundcube", write.name)
        assertEquals(roundcube.snapshot, write.read)
        assertEquals(existing + blockRule("news@example.com", "Trash"), loadedFilterRules("roundcube", write.script.emit()).rules)
    }

    @Test fun `an address already handled reads as already handled, script or no script`() = runBlocking {
        // Nothing would have been written either way, so the two refusals are not the same
        // refusal: "couldn't complete the action" says something went wrong, and nothing did.
        // The duplicate check answers first because it is the answer that is true.
        val saved = Saved()
        val outcome = addBlockRule(
            address = "NEWS@example.com",
            trashFolder = "Trash",
            load = {
                FilterRulesState.Loaded(
                    existing + blockRule("news@example.com", "Trash"),
                    foreignActiveScript = true,
                )
            },
            save = saved.save,
        )
        assertEquals(BlockOutcome.ALREADY_PRESENT, outcome)
        assertNull(saved.text)
    }

    @Test fun `a save the server refuses is reported as a failure`() = runBlocking {
        val outcome = addBlockRule(
            address = "news@example.com",
            trashFolder = "Trash",
            load = { roundcube },
            save = { throw IllegalStateException("the server rejected the filters") },
        )
        assertEquals(BlockOutcome.FAILED, outcome)
    }
}
